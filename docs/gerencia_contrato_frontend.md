# Gerencia y Solicitudes — Contrato para el FrontEnd

> Documento dirigido al equipo/agente de FrontEnd. Describe los cambios de API, los flujos de UI esperados y las reglas que el backend aplica (y que el frontend no puede sobreescribir) para la feature de rol Gerencia + Solicitudes de aprobación. Extiende `contrato_api.md`; las convenciones generales (envelope, paginación, snake_case, montos como string) aplican igual.

**Fecha:** 2026-07-16 · **Estado:** backend implementado (Tasks 1–13, rama `feature/b08-auth-endpoints`). Contrato estable; este documento es la fuente de verdad para el frontend.

---

## 1. Resumen ejecutivo

1. El rol `gerente` se renombra a **`gerencia`** (valor del enum en JWT y en `/empleados/me`). Actualizar todos los checks de rol del frontend.
2. Nace la **Solicitud**: cuando un `vendedor` o `jdv` intenta una acción por encima de su permiso (hoy: descuentos sobre su límite, y reasignación de clientes para el jdv), el backend responde `422 APROBACION_REQUERIDA`. El frontend muestra entonces un panel/modal que explica que se enviará una solicitud al aprobador, con un campo de texto obligatorio para el motivo, y hace `POST /solicitudes`.
3. Nueva **vista "Gerencia"**: bandeja de solicitudes con aprobación/denegación. La denegación exige un mensaje. Visible solo para `gerencia` y `admin`. El `jdv` tiene su propia bandeja (los descuentos 3–7% de vendedores le llegan a él) — misma UI, alcance filtrado por el backend.
4. **Cartera Maestra**: empresas reservadas de Gerencia. Invisibles para `jdv`/`vendedor`/`analista` en TODOS los endpoints hasta que Gerencia las libere asignando un vendedor. El frontend no necesita filtrar nada: el backend ya lo hace; solo debe renderizar la vista de cartera maestra para `gerencia`/`admin`.
5. Gerencia **ve todo y puede modificar todo**, pero **no puede tener** empresas ni oportunidades asignadas. No mostrar a usuarios `gerencia`/`admin` como opción en ningún selector de vendedor.

---

## 2. Límites de descuento (regla de negocio)

| Rol | Aplica directo | Requiere solicitud a `jdv` | Requiere solicitud a `gerencia` |
|---|---|---|---|
| `vendedor` / `analista` | `dcto ≤ 3` | `3 < dcto ≤ 7` | `dcto > 7` |
| `jdv` | `dcto ≤ 7` | — | `dcto > 7` |
| `gerencia` / `admin` | sin límite | — | — |

El frontend PUEDE usar esta tabla para UX proactiva (avisar antes de enviar), pero la validación autoritativa es del backend: siempre manejar el `422`.

---

## 3. Cambios en endpoints existentes

### 3.1 `POST /oportunidades` y `PUT /oportunidades/:id` — validación de `dcto`

Si el `dcto` enviado supera el límite del rol, el backend NO guarda nada y responde:

```json
HTTP 422
{
  "data": null,
  "error": {
    "code": "APROBACION_REQUERIDA",
    "message": "Un descuento de 5% supera tu límite de 3%; requiere aprobación del jefe de ventas",
    "field": "dcto"
  }
}
```

Flujo esperado en el frontend:
1. Recibir el `422` → abrir el modal de solicitud ("Se enviará una solicitud a [aprobador] para aplicar X% de descuento") con textarea obligatorio `motivo`.
2. Al confirmar → `POST /solicitudes` con `tipo: "descuento"` (ver §4.1). El backend decide solo el aprobador.
3. **Importante:** en edición, el resto de campos del formulario SÍ puede guardarse con un `PUT` sin el `dcto` fuera de límite. El descuento pendiente NO queda aplicado en la oportunidad hasta la aprobación.

En **creación** de oportunidad el `422` bloquea la creación completa: crear primero la oportunidad con `dcto` dentro del límite (o sin `dcto`) y luego solicitar el descuento mayor sobre la oportunidad ya creada.

### 3.2 `PATCH /empresas/:id/vendedor` — reasignación directa restringida

- **Roles ahora:** `admin`, `gerencia` (antes incluía `jdv`).
- `jdv` recibe `403 PERMISO_INSUFICIENTE` → el frontend abre el modal de solicitud y hace `POST /solicitudes` con `tipo: "reasignacion_cliente"`.
- `vendedor`/`analista`: siguen sin poder reasignar y **tampoco** pueden solicitarlo (el backend rechaza la solicitud con 403). No ofrecer la opción en la UI.
- Restricción nueva: `id_vendedor` destino debe ser un empleado activo con rol `vendedor` o `jdv`. Nunca listar `gerencia`/`admin`/`analista` como destino.

### 3.3 `POST /oportunidades` — creación por Gerencia

- Gerencia puede crear oportunidades sobre cualquier empresa. La oportunidad se asigna **al vendedor responsable de la empresa**, nunca a Gerencia.
- Si la empresa **no tiene vendedor asignado**, el body debe incluir `id_vendedor` (nuevo campo opcional). En el mismo modal de creación el frontend debe pedir el vendedor cuando `empresa.id_vendedor == null`. Esa asignación también queda aplicada a la empresa (misma transacción) y dispara la notificación `empresa_asignada`.
- Si falta `id_vendedor` en ese caso: `400 VALIDACION` con `field: "id_vendedor"`.

### 3.4 `GET /empresas` — filtro de cartera maestra

- Nuevo query param `cartera_maestra=true|false` (solo tiene efecto para `gerencia`/`admin`; para el resto el backend fuerza la exclusión).
- Los items de empresa incluyen el campo nuevo `en_cartera_maestra: boolean`.
- Para `jdv`/`vendedor`/`analista` las empresas en cartera maestra **no existen**: no aparecen en listados, búsquedas, prospección, reportes ni por acceso directo (`404`).

---

## 4. Endpoints nuevos

### 4.1 `POST /solicitudes`
> Crea una solicitud de aprobación.

**Roles:** `vendedor`, `analista`, `jdv` (según tipo; `gerencia`/`admin` no solicitan: ejecutan directo)

**Body (descuento):**
```json
{
  "tipo": "descuento",
  "entidad_tipo": "oportunidad",
  "entidad_id": 45,
  "dcto_solicitado": "5.00",
  "motivo": "Cliente frecuente, tercera compra del año"
}
```

**Body (reasignación de cliente — solo jdv):**
```json
{
  "tipo": "reasignacion_cliente",
  "entidad_tipo": "empresa",
  "entidad_id": 12,
  "id_vendedor_nuevo": 8,
  "motivo": "El vendedor actual sale de vacaciones largas"
}
```

**Respuesta 201:**
```json
{
  "data": {
    "id": 7,
    "tipo": "descuento",
    "estado": "pendiente",
    "rol_aprobador": "jdv",
    "entidad_tipo": "oportunidad",
    "entidad_id": 45,
    "entidad_descripcion": "Transportes Lima Norte S.A.C. — Oportunidad #45",
    "dcto_solicitado": "5.00",
    "id_vendedor_nuevo": null,
    "vendedor_nuevo": null,
    "motivo": "Cliente frecuente, tercera compra del año",
    "solicitante": { "id": 3, "nombres": "María", "apellidos": "Quispe" },
    "resolutor": null,
    "motivo_resolucion": null,
    "resolved_at": null,
    "created_at": "2026-07-16T15:30:00Z"
  }
}
```

**Errores:**
| Código | HTTP | Cuándo |
|---|---|---|
| `VALIDACION` | 400 | Falta `motivo`, payload no corresponde al tipo, o el `dcto_solicitado` está dentro del límite propio (no necesita solicitud) |
| `PERMISO_INSUFICIENTE` | 403 | Rol no puede solicitar ese tipo (p. ej. vendedor pidiendo reasignación) |
| `NO_ENCONTRADO` | 404 | La entidad no existe o no es visible para el solicitante |
| `SOLICITUD_DUPLICADA` | 409 | Ya hay una solicitud pendiente del mismo tipo sobre esa entidad |

### 4.2 `GET /solicitudes`
> Lista solicitudes. Paginado estándar (§4 de contrato_api.md). La visibilidad la decide el backend.

**Visibilidad por rol:**
- `gerencia`: las dirigidas a `gerencia` (todas: pendientes y resueltas).
- `admin`: todas.
- `jdv`: las dirigidas a `jdv` + las que él mismo creó.
- `vendedor`/`analista`: solo las propias.

**Query params:** `estado` (`pendiente|aprobada|denegada`), `tipo`, `mias=true` (fuerza "solo las que yo creé", útil para jdv), más paginación.

**Respuesta 200:** lista de objetos con la misma forma que §4.1, con `meta` de paginación. La vista Gerencia se construye sobre este endpoint con `estado=pendiente` por defecto y tabs para el historial.

### 4.3 `GET /solicitudes/:id`
> Detalle. Misma forma que §4.1. `404` si no es visible según §4.2.

### 4.4 `PATCH /solicitudes/:id/aprobar`
> Aprueba y **aplica el cambio inmediatamente** (misma transacción). Notifica al solicitante.

**Roles:** el rol `rol_aprobador` de la solicitud, o `admin`.

**Body:** vacío.

**Respuesta 200:** el detalle de la solicitud con `estado: "aprobada"`, `resolutor` y `resolved_at`.

**Errores:**
| Código | HTTP | Cuándo |
|---|---|---|
| `SOLICITUD_YA_RESUELTA` | 409 | Otro aprobador la resolvió primero (refrescar la bandeja) |
| `SOLICITUD_NO_APLICABLE` | 409 | La entidad cambió y el efecto ya no aplica (p. ej. oportunidad cerrada). La solicitud sigue `pendiente`; el aprobador debe denegarla manualmente con su motivo |
| `PERMISO_INSUFICIENTE` | 403 | No es el aprobador correspondiente |

### 4.5 `PATCH /solicitudes/:id/denegar`
> Deniega. El mensaje es **obligatorio**. Notifica al solicitante.

**Roles:** el rol `rol_aprobador` de la solicitud, o `admin`.

**Body:** `{ "motivo": "El margen de este modelo no soporta ese descuento" }`

**Respuesta 200:** detalle con `estado: "denegada"` y `motivo_resolucion`.

**Errores:** `400 VALIDACION` si falta `motivo`; `409 SOLICITUD_YA_RESUELTA`.

### 4.6 `PATCH /empresas/:id/cartera-maestra`
> Mueve una empresa a la cartera maestra o la libera de ella.

**Roles:** `gerencia`, `admin`

**Body (mover a cartera maestra):** `{ "en_cartera_maestra": true }`
— Requiere que la empresa no tenga oportunidades activas (`409 CARTERA_MAESTRA_CON_OPORTUNIDADES`). Si tenía vendedor, se desasigna (`id_vendedor` queda `null`).

**Body (liberar):** `{ "en_cartera_maestra": false, "id_vendedor": 8 }`
— `id_vendedor` obligatorio al liberar (`400 VALIDACION` si falta). La empresa se vuelve visible para el jdv y el vendedor asignado, y este recibe la notificación `empresa_asignada`.

**Respuesta 200:** `{ "data": { "en_cartera_maestra": false, "id_vendedor": 8 } }`

---

## 5. Vista "Gerencia" (nueva)

- **Ruta sugerida:** `/gerencia`. Visible en la navegación solo para roles `gerencia` y `admin`.
- **Contenido:** bandeja de solicitudes (`GET /solicitudes?estado=pendiente`) mostrando por fila: solicitante, tipo (chip "Descuento" / "Reasignación de cliente"), entidad relacionada (`entidad_descripcion`, clickeable → navega al detalle de la oportunidad/empresa), payload legible ("5% de descuento", "Reasignar a Juan Pérez"), motivo del solicitante, fecha.
- **Acciones por fila:** botón Aprobar (confirmación simple) y Denegar (abre input de motivo obligatorio).
- **Historial:** tab o filtro con `estado=aprobada|denegada` mostrando además resolutor, fecha de resolución y motivo de denegación. Esto cubre la trazabilidad requerida.
- **Bandeja del jdv:** el mismo componente sirve para el `jdv` (recibe descuentos 3–7%); decidir si se muestra como sección "Aprobaciones" en su navegación. El backend ya filtra el alcance.
- Además, para `gerencia`/`admin`, la vista de Cartera incluye el acceso a la **Cartera Maestra** (`GET /empresas?cartera_maestra=true`) con la acción "Liberar" (modal con selector de vendedor → §4.6).

## 6. Vista del solicitante

- "Mis solicitudes" (`GET /solicitudes?mias=true`): estado de cada una (`pendiente`/`aprobada`/`denegada` + motivo de denegación).
- Notificaciones in-app nuevas (mismo endpoint `GET /notificaciones` de siempre):

| `tipo` | `entidad_tipo` | Navega a |
|---|---|---|
| `solicitud_creada` | `solicitud` | Bandeja de aprobación (vista Gerencia / bandeja jdv) |
| `solicitud_aprobada` | `solicitud` | Detalle en "Mis solicitudes" |
| `solicitud_denegada` | `solicitud` | Detalle en "Mis solicitudes" (mostrar `motivo_resolucion`) |

Cuando una solicitud de descuento se aprueba, la oportunidad ya tiene el `dcto` nuevo y el `monto_total` recalculado por el backend — basta refrescar el detalle.

---

## 7. Reglas que el backend aplica solas (no duplicar, pero no contradecir)

1. Toda la visibilidad (cartera maestra, solicitudes por rol, IDOR→404) se filtra server-side. El frontend nunca recibe datos que el rol no deba ver.
2. `monto_total` sigue siendo calculado; la aprobación de descuento lo recalcula sola.
3. El aprobador de una solicitud lo deriva el backend; el body de `POST /solicitudes` no lo incluye.
4. Los selectores de "vendedor asignado" deben poblarse con `GET /empleados?rol=vendedor` (+ `jdv` si negocio lo confirma) — nunca incluir `gerencia`/`admin`.
5. `gerencia` conserva todos los permisos que tenía `gerente` en `matriz_permisos.md` (validar facturado, editar cualquier oportunidad, crear tareas, reportes, etc.), más los nuevos de este documento.

## 8. Checklist de migración para el FrontEnd

- [ ] Reemplazar todo check `rol === "gerente"` por `"gerencia"`.
- [ ] Manejar `422 APROBACION_REQUERIDA` en formularios de oportunidad → modal de solicitud.
- [ ] Manejar `403` en reasignación para jdv → modal de solicitud.
- [ ] Construir vista `/gerencia` (bandeja + historial) y bandeja jdv.
- [ ] Construir "Mis solicitudes" + integrar 3 tipos nuevos de notificación.
- [ ] Cartera Maestra: listado (`cartera_maestra=true`), acciones mover/liberar, ocultar toda la sección a roles no autorizados.
- [ ] Modal de crear oportunidad: pedir `id_vendedor` cuando la empresa no tiene vendedor (solo lo verán gerencia/admin/jdv).
- [ ] Excluir `gerencia`/`admin` de selectores de vendedor.
