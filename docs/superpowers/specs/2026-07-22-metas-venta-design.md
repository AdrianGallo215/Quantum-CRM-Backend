# Metas de venta (unidades) — Diseño

Fecha: 2026-07-22
Estado: Aprobado por el usuario, pendiente de plan de implementación.

## Contexto y objetivo

Los vendedores (y el JDV) necesitan una meta de ventas medida en **unidades** (buses), no en monto. La meta tiene dos horizontes por empleado y año: 12 metas mensuales + 1 meta anual (calculada). El JDV propone las metas de su equipo (y la suya propia); Gerencia aprueba, rechaza (con motivo) o modifica directamente. Las unidades de una oportunidad solo cuentan para la meta del vendedor cuando la oportunidad llega a `facturado`; si esa oportunidad se cancela (retrocede de estado) o se elimina estando facturada, las unidades dejan de contar automáticamente.

En la página de inicio, debajo de tareas pendientes, se muestra un medidor tipo velocímetro con el % de cumplimiento — uno mensual y uno anual. El JDV ve además el agregado de todo el equipo.

Fuera de alcance: metas en monto (S/), metas por producto/modelo, frontend (este repo solo entrega el contrato de API).

## Modelo de datos

### Tabla `metas_venta`

Una fila por `(id_empleado, anio)`. Contiene los 12 meses + el total anual calculado, y el ciclo de aprobación se aplica al año completo (no por mes) — así es como el JDV realmente propone: el año entero de una vez, no mes a mes.

```
id                  BIGSERIAL       PK
id_empleado         BIGINT          NOT NULL REFERENCES empleados(id)
anio                INT             NOT NULL
meta_enero          INT             NOT NULL CHECK (> 0)
meta_febrero        INT             NOT NULL CHECK (> 0)
meta_marzo          INT             NOT NULL CHECK (> 0)
meta_abril          INT             NOT NULL CHECK (> 0)
meta_mayo           INT             NOT NULL CHECK (> 0)
meta_junio          INT             NOT NULL CHECK (> 0)
meta_julio          INT             NOT NULL CHECK (> 0)
meta_agosto         INT             NOT NULL CHECK (> 0)
meta_septiembre     INT             NOT NULL CHECK (> 0)
meta_octubre        INT             NOT NULL CHECK (> 0)
meta_noviembre      INT             NOT NULL CHECK (> 0)
meta_diciembre      INT             NOT NULL CHECK (> 0)
meta_anual          INT             NOT NULL   -- SOLO LECTURA: suma de los 12 meses. Nunca input.
estado              estado_meta_enum NOT NULL DEFAULT 'propuesta'   -- propuesta | aprobada | rechazada
id_propuesto_por    BIGINT          NOT NULL REFERENCES empleados(id)
id_resolutor        BIGINT          REFERENCES empleados(id)
motivo_rechazo      TEXT
resolved_at         TIMESTAMP
created_at          TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP
updated_at          TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP

UNIQUE (id_empleado, anio)

CHECK: estado = 'propuesta'  → id_resolutor IS NULL AND resolved_at IS NULL AND motivo_rechazo IS NULL
CHECK: estado = 'aprobada'   → id_resolutor IS NOT NULL AND resolved_at IS NOT NULL AND motivo_rechazo IS NULL
CHECK: estado = 'rechazada'  → id_resolutor IS NOT NULL AND resolved_at IS NOT NULL AND motivo_rechazo IS NOT NULL
```

`meta_anual` sigue el mismo patrón que `oportunidades.monto_total`: calculado por el backend (`suma de meta_enero..meta_diciembre`), nunca aceptado como input, recalculado en cada creación/edición. No hay CHECK de base de datos que fuerce la igualdad (igual que `monto_total`, la garantía es a nivel de aplicación).

`id_empleado` debe referenciar un empleado `activo` con `rol` en `(vendedor, jdv)` — validado en el servicio, no en la base de datos (igual que otras validaciones de rol en el repo).

### Columna nueva `oportunidades.facturado_en`

```sql
ALTER TABLE oportunidades ADD COLUMN facturado_en TIMESTAMP NULL;
CREATE INDEX idx_oportunidades_facturado_en ON oportunidades(id_vendedor, facturado_en) WHERE estado = 'facturado';
```

- Se fija a `now()` en `OportunidadServiceImpl.cambiarEstado` cuando `nuevo == facturado`.
- Se limpia a `NULL` cuando el estado cambia **desde** `facturado` hacia cualquier otro (retroceso o cierre).
- Backfill en la misma migración: para las oportunidades que ya están en `facturado`, se completa con el `changed_at` de su entrada más reciente en `oportunidad_estados_log` donde `estado_nuevo = 'facturado'`, para no perder ventas históricas del cómputo de cumplimiento.

### Por qué no hay contador incremental

El cumplimiento de un vendedor para un periodo es una suma en vivo, no un contador que se actualiza en cada evento:

```sql
SELECT COALESCE(SUM(cantidad), 0)
FROM oportunidades
WHERE id_vendedor = :idVendedor
  AND estado = 'facturado'
  AND EXTRACT(YEAR FROM facturado_en) = :anio
  [AND EXTRACT(MONTH FROM facturado_en) = :mes]   -- omitido para el total anual
```

Cancelar (retroceder de estado), cerrar, o eliminar una oportunidad facturada actualiza el resultado automáticamente porque la fila deja de cumplir el `WHERE` — no existe código que deba "restar" nada en ningún flujo de cancelación/eliminación, eliminando una fuente entera de bugs de desincronización.

## Reglas de negocio del ciclo de aprobación

1. **JDV propone** (`POST /metas-venta`): body con `idEmpleado`, `anio`, los 12 meses. `idEmpleado` debe ser un vendedor activo o el propio JDV.
   - Si no existe fila para `(idEmpleado, anio)`, o existe pero está `rechazada`, se crea/sobreescribe en estado `propuesta`, `id_propuesto_por = actor`, limpiando `id_resolutor`/`motivo_rechazo`/`resolved_at`.
   - Si ya existe una fila `propuesta` o `aprobada` para ese periodo → 409 `META_YA_EXISTE` (usar `PATCH` para modificarla).
2. **Gerencia/admin aprueba** (`PATCH /metas-venta/:id/aprobar`): solo sobre `propuesta`. Pasa a `aprobada` tal cual fue propuesta. Notifica al JDV.
3. **Gerencia/admin rechaza** (`PATCH /metas-venta/:id/rechazar`, `motivo` obligatorio): solo sobre `propuesta`. Pasa a `rechazada`. El motivo es texto libre donde Gerencia especifica qué corregir (p.ej. "ajusta marzo"). Notifica al JDV.
4. **Gerencia/admin crea o modifica directo**: mismo endpoint `POST /metas-venta` (si no existe fila — actúa como upsert, crea ya `aprobada`) o `PATCH /metas-venta/:id` (si existe, sobreescribe cualquiera de los 12 meses, recalcula `meta_anual`). En ambos casos el resultado queda `aprobada`, `id_resolutor = actor`, `resolved_at = now()`, y se notifica al JDV proponente que su propuesta fue modificada.
5. Solo cuentan para el % de cumplimiento las metas en estado `aprobada`.

## API — endpoints nuevos

Todos bajo `/api/v1/metas-venta`, requieren autenticación.

| Endpoint | Rol | Descripción |
|---|---|---|
| `POST /metas-venta` | jdv, gerencia, admin | Crear/re-proponer (jdv) o crear/sobreescribir aprobada (gerencia/admin) |
| `PATCH /metas-venta/:id` | gerencia, admin | Editar meses de una fila existente, recalcula anual, auto-aprueba |
| `PATCH /metas-venta/:id/aprobar` | gerencia, admin | Aprobar una `propuesta` |
| `PATCH /metas-venta/:id/rechazar` | gerencia, admin | Rechazar una `propuesta` (motivo obligatorio) |
| `GET /metas-venta` | todos (visibilidad filtrada) | Listar con filtros `idEmpleado`, `anio`, `estado` |
| `GET /metas-venta/:id` | todos (visibilidad filtrada) | Detalle |

**Visibilidad** (mismo patrón que `solicitudes`):
- `vendedor`/`analista`: solo sus propias metas (si `analista` no tiene metas asignadas, la lista queda vacía — no es un rol objetivo de `id_empleado`).
- `jdv`: las suyas + las de todos los vendedores activos (equipo plano, igual que su visibilidad de pipeline).
- `gerencia`/`admin`: todas. `GET /metas-venta?estado=propuesta` funciona como bandeja de aprobación.

IDOR: pedir el detalle de una meta fuera del alcance del rol → 404 (regla #14 de CLAUDE.md).

## Integración con el panel de Inicio

`GET /inicio` (`InicioDto`) gana un campo nuevo `metaVentas: MetaVentasInicioDto?`, presente solo para `rol` en `(vendedor, jdv)`; `null` para el resto.

```
MedidorMetaDto {
  tieneMeta: Boolean
  unidadesMeta: Int?       // null si tieneMeta = false
  unidadesLogradas: Int    // siempre calculable, aunque tieneMeta sea false
  porcentaje: Int?         // null si tieneMeta = false; puede superar 100
}

MetaVentasInicioDto {
  mensual: MedidorMetaDto
  anual: MedidorMetaDto
  equipo: MetaVentasInicioDto?   // solo para jdv: mismo shape, agregado del equipo. null para vendedor.
}
```

- `tieneMeta` se basa en si existe una fila `aprobada` para `(empleado, año en curso)` — aplica igual a `mensual` y `anual` porque ambos vienen de la misma fila.
- `equipo.mensual`/`equipo.anual`: suma de `unidadesMeta` y de `unidadesLogradas` de todos los vendedores activos con meta `aprobada` para el periodo; `tieneMeta = false` si ningún vendedor activo tiene meta aprobada ese año.
- El `jdv` no incluye sus propias unidades en el agregado de `equipo` — su meta personal se reporta por separado en `mensual`/`anual` de nivel raíz.

## Notificaciones

Mismo patrón que `solicitudes` (`NotificacionService` + `TipoNotificacion`): `meta_propuesta` (a gerencia, al proponer), `meta_aprobada` (al JDV, al aprobar), `meta_rechazada` (al JDV, al rechazar), `meta_modificada` (al JDV, cuando gerencia edita directo).

## Fuera de alcance de esta spec

- Vista/gestión de metas para `admin` fuera de aprobar/editar (p.ej. reportes históricos de cumplimiento) — ya cubierto en general por el módulo `reportes`, no se extiende aquí.
- Metas para roles distintos de `vendedor`/`jdv`.
- Ajuste automático de meses cercanos al editar uno (mencionado como posibilidad en la conversación, pero la resolución manual de Gerencia vía `motivo_rechazo` o edición directa ya lo cubre; no se automatiza).
