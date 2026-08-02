# Gerencia y Solicitudes — Modelo de Datos

> Especificación de las nuevas entidades, columnas y relaciones en la base de datos para el requerimiento de rol Gerencia + sistema de Solicitudes de aprobación. Este documento es la fuente de verdad del schema para esta feature; las migraciones Flyway V25–V28 lo implementan. Complementa `schema.sql` (que debe actualizarse al mergear).

**Fecha:** 2026-07-16
**Alcance de aprobaciones en esta fase:** descuentos en oportunidades y reasignación de clientes (empresas). El modelo queda extensible para tipos futuros.

---

## 1. Resumen de cambios

| Migración | Cambio |
|---|---|
| `V25__rename_rol_gerente_a_gerencia.sql` | Renombra el valor `gerente` → `gerencia` en el enum nativo `rol_empleado` |
| `V26__create_solicitudes.sql` | Nueva tabla `solicitudes` + 4 enums nuevos |
| `V27__empresas_cartera_maestra.sql` | Columna `en_cartera_maestra` en `empresas` + CHECK + índice parcial |
| `V28__notificaciones_solicitudes.sql` | Nuevos valores en `tipo_notificacion_enum` y `entidad_notificacion_enum` |

No se elimina ni renombra ninguna tabla existente. No hay pérdida de datos.

---

## 2. V25 — Renombrar rol `gerente` → `gerencia`

```sql
ALTER TYPE rol_empleado RENAME VALUE 'gerente' TO 'gerencia';
```

- Los empleados existentes con rol `gerente` pasan automáticamente a `gerencia` (es el mismo valor, renombrado).
- **Impacto en código:** `RolEmpleado.gerente` → `RolEmpleado.gerencia` (Hibernate mapea el enum por nombre) y todos los checks de `UsuarioActual` (`rol == "gerente"`).
- **Impacto en sesiones:** los JWT vigentes emitidos con `rol = "gerente"` dejan de coincidir con los checks; el usuario Gerencia debe re-loguearse tras el deploy. Aceptable por la corta vida de los access tokens.

---

## 3. V26 — Tabla `solicitudes`

La capa intermedia de aprobación y a la vez el registro de trazabilidad: cada fila es una solicitud con su ciclo de vida completo (quién la pidió, qué pidió, sobre qué entidad, por qué, quién la resolvió, cuándo y con qué resultado). No se borra nunca; las resueltas son el historial.

### 3.1 Enums nuevos

```sql
CREATE TYPE tipo_solicitud_enum AS ENUM ('descuento', 'reasignacion_cliente');

CREATE TYPE estado_solicitud_enum AS ENUM ('pendiente', 'aprobada', 'denegada');

-- Quién debe resolverla. El backend lo deriva al crear; nunca lo elige el solicitante.
CREATE TYPE aprobador_solicitud_enum AS ENUM ('jdv', 'gerencia');

-- Entidad sobre la que actúa la solicitud. Extensible (contacto, etc.) en fases futuras.
CREATE TYPE entidad_solicitud_enum AS ENUM ('oportunidad', 'empresa');
```

### 3.2 Tabla

```sql
CREATE TABLE solicitudes (
    id                  BIGSERIAL                   PRIMARY KEY,
    tipo                tipo_solicitud_enum         NOT NULL,
    estado              estado_solicitud_enum       NOT NULL DEFAULT 'pendiente',
    rol_aprobador       aprobador_solicitud_enum    NOT NULL,
    id_solicitante      BIGINT                      NOT NULL REFERENCES empleados(id),
    entidad_tipo        entidad_solicitud_enum      NOT NULL,
    entidad_id          BIGINT                      NOT NULL,
    -- Snapshot legible de la entidad al momento de solicitar (p. ej. "Transportes
    -- Lima Norte S.A.C. — Oportunidad #45"). Evita joins polimórficos al listar y
    -- preserva la traza aunque la entidad cambie de nombre después.
    entidad_descripcion TEXT                        NOT NULL,
    motivo              TEXT                        NOT NULL,

    -- Payload tipado por tipo de solicitud (ver CHECK chk_solicitud_payload)
    dcto_solicitado     NUMERIC(5,2),
    id_vendedor_nuevo   BIGINT                      REFERENCES empleados(id),

    -- Resolución
    id_resolutor        BIGINT                      REFERENCES empleados(id),
    motivo_resolucion   TEXT,
    resolved_at         TIMESTAMP,

    created_at          TIMESTAMP                   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at          TIMESTAMP                   NOT NULL DEFAULT CURRENT_TIMESTAMP,

    -- Cada tipo exige su payload y su tipo de entidad.
    CONSTRAINT chk_solicitud_payload CHECK (
        (tipo = 'descuento'
            AND dcto_solicitado IS NOT NULL
            AND dcto_solicitado > 0 AND dcto_solicitado <= 100
            AND entidad_tipo = 'oportunidad'
            AND id_vendedor_nuevo IS NULL)
        OR
        (tipo = 'reasignacion_cliente'
            AND id_vendedor_nuevo IS NOT NULL
            AND entidad_tipo = 'empresa'
            AND dcto_solicitado IS NULL)
    ),

    -- Una solicitud resuelta siempre tiene resolutor y fecha; una pendiente, nunca.
    CONSTRAINT chk_solicitud_resolucion CHECK (
        (estado = 'pendiente' AND id_resolutor IS NULL AND resolved_at IS NULL AND motivo_resolucion IS NULL)
        OR
        (estado IN ('aprobada', 'denegada') AND id_resolutor IS NOT NULL AND resolved_at IS NOT NULL)
    ),

    -- La denegación siempre lleva mensaje del aprobador.
    CONSTRAINT chk_solicitud_denegada_motivo CHECK (
        estado <> 'denegada' OR motivo_resolucion IS NOT NULL
    )
);

-- Panel del aprobador: pendientes por rol, más recientes primero.
CREATE INDEX idx_solicitudes_aprobador ON solicitudes(rol_aprobador, estado, created_at DESC);

-- "Mis solicitudes" del solicitante.
CREATE INDEX idx_solicitudes_solicitante ON solicitudes(id_solicitante, created_at DESC);

-- Historial por entidad (trazabilidad en el detalle de oportunidad/empresa).
CREATE INDEX idx_solicitudes_entidad ON solicitudes(entidad_tipo, entidad_id);

-- No puede haber dos solicitudes pendientes del mismo tipo sobre la misma entidad.
CREATE UNIQUE INDEX uq_solicitud_pendiente_por_entidad
    ON solicitudes(tipo, entidad_tipo, entidad_id)
    WHERE estado = 'pendiente';
```

### 3.3 Decisiones de diseño

- **Columnas tipadas en vez de JSONB para el payload.** Con dos tipos de solicitud, columnas dedicadas (`dcto_solicitado`, `id_vendedor_nuevo`) permiten FK reales, CHECKs y queries directas. Un tipo nuevo de solicitud implicará una migración que agregue su columna — coherente con la regla "solo Flyway toca el schema". Si los tipos crecen mucho (>5), reevaluar JSONB.
- **`entidad_id` sin FK polimórfica.** PostgreSQL no soporta FK condicionada por `entidad_tipo`. La integridad la garantiza el servicio al crear (valida que la entidad exista y sea visible para el solicitante) y al aprobar (revalida que siga existiendo y en estado aplicable).
- **`rol_aprobador` materializado en la fila** (no derivado al leer): la regla de derivación puede cambiar en el futuro y las solicitudes ya creadas deben seguir dirigidas a quien fueron enviadas.
- **No hay estado `cancelada` ni `expirada` en esta fase.** Si se necesita retiro por el solicitante o expiración automática, es un valor nuevo del enum (migración trivial con `ALTER TYPE ... ADD VALUE`).
- **La tabla es el log.** No se crea una tabla de historial aparte: una solicitud es inmutable salvo su transición única `pendiente → aprobada|denegada`, así que la fila completa ES la traza. El efecto de una aprobación de descuento queda además auditado en `oportunidades.updated_by` y el de una reasignación en `empresas.updated_by` + notificaciones.

### 3.4 Reglas de derivación de `rol_aprobador` (lógica de servicio, no schema)

| Solicitante | Tipo | Condición | Aprobador |
|---|---|---|---|
| `vendedor` / `analista` | `descuento` | `3 < dcto ≤ 7` | `jdv` |
| `vendedor` / `analista` | `descuento` | `dcto > 7` | `gerencia` |
| `jdv` | `descuento` | `dcto > 7` | `gerencia` |
| `jdv` | `reasignacion_cliente` | siempre | `gerencia` |

Límites de descuento directo (sin solicitud): `vendedor`/`analista` ≤ 3%, `jdv` ≤ 7%, `gerencia`/`admin` sin límite. Un `vendedor` no crea solicitudes de `reasignacion_cliente` (no reasigna, ni con aprobación). `gerencia` y `admin` nunca crean solicitudes: ejecutan directo.

---

## 4. V27 — Cartera Maestra en `empresas`

Cartera reservada de Gerencia: empresas invisibles para `jdv`, `vendedor` y `analista` hasta que Gerencia las libere asignándoles un vendedor.

```sql
ALTER TABLE empresas
    ADD COLUMN en_cartera_maestra BOOLEAN NOT NULL DEFAULT false;

-- Una empresa en cartera maestra no tiene vendedor: recién al liberarla se asigna.
ALTER TABLE empresas
    ADD CONSTRAINT chk_cartera_maestra_sin_vendedor
    CHECK (NOT en_cartera_maestra OR id_vendedor IS NULL);

-- Índice parcial: la cartera maestra será pequeña respecto al total.
CREATE INDEX idx_empresas_cartera_maestra ON empresas(en_cartera_maestra)
    WHERE en_cartera_maestra;
```

### 4.1 Semántica

- **Visibilidad:** filas con `en_cartera_maestra = true` solo las devuelven los endpoints a `admin` y `gerencia`. Para `jdv`, `vendedor` y `analista` es como si no existieran (listados las excluyen en la query; acceso directo por id responde `404`, regla IDOR).
- **Entrar a la cartera maestra:** operación de `gerencia`/`admin`. Requiere que la empresa no tenga oportunidades activas; al entrar, `id_vendedor` se pone `NULL`.
- **Liberar:** operación atómica de `gerencia`/`admin`: `en_cartera_maestra = false` + `id_vendedor = X` en el mismo UPDATE. Dispara la notificación `empresa_asignada` existente al vendedor.
- No es una tabla nueva porque no hay datos propios de la membresía (ni fecha de liberación con requisitos de reporte, por ahora); un booleano con CHECK es el modelo mínimo que cumple. Si a futuro se pide trazabilidad de liberaciones, se agrega una tabla `cartera_maestra_log`.

---

## 5. V28 — Notificaciones de solicitudes

```sql
ALTER TYPE tipo_notificacion_enum ADD VALUE 'solicitud_creada';
ALTER TYPE tipo_notificacion_enum ADD VALUE 'solicitud_aprobada';
ALTER TYPE tipo_notificacion_enum ADD VALUE 'solicitud_denegada';

ALTER TYPE entidad_notificacion_enum ADD VALUE 'solicitud';
```

| Tipo | Destinatarios | Cuándo |
|---|---|---|
| `solicitud_creada` | Empleados activos con el rol `rol_aprobador` de la solicitud | Al crear la solicitud |
| `solicitud_aprobada` | El solicitante | Al aprobar (el cambio ya se aplicó) |
| `solicitud_denegada` | El solicitante | Al denegar (incluye el motivo en el mensaje) |

`entidad_tipo = 'solicitud'` y `entidad_id = solicitudes.id`: el frontend navega al detalle de la solicitud (vista Gerencia o "mis solicitudes").

> Nota Flyway/PostgreSQL: `ALTER TYPE ... ADD VALUE` corre dentro de la transacción de la migración en PG ≥ 12, pero el valor nuevo no puede usarse en esa misma transacción. V28 solo agrega valores, no los usa — es seguro.

---

## 6. Relaciones (diagrama lógico)

```
empleados 1 ──── * solicitudes (id_solicitante)   "quién pide"
empleados 1 ──── * solicitudes (id_resolutor)     "quién resuelve"
empleados 1 ──── * solicitudes (id_vendedor_nuevo) "a quién se reasigna" (solo reasignacion_cliente)

solicitudes * ──── 1 oportunidades (entidad_id cuando entidad_tipo='oportunidad', sin FK)
solicitudes * ──── 1 empresas      (entidad_id cuando entidad_tipo='empresa', sin FK)

empresas.en_cartera_maestra ── flag de visibilidad exclusiva gerencia/admin
```

---

## 7. Restricciones de negocio que NO viven en el schema

Se validan en la capa de servicio (y se testean):

1. `gerencia` y `admin` nunca pueden ser `id_vendedor` de una empresa ni de una oportunidad (Gerencia no tiene cartera propia). Destinos válidos de asignación: empleados activos con rol `vendedor` o `jdv`.
2. Al crear una solicitud, la entidad debe existir y ser visible para el solicitante (404 si no — IDOR).
3. Al aprobar, la solicitud debe seguir `pendiente` (409 `SOLICITUD_YA_RESUELTA` si no) y la entidad debe seguir en estado aplicable (409 `SOLICITUD_NO_APLICABLE` si, p. ej., la oportunidad se cerró).
4. La aprobación aplica el cambio **en la misma transacción** que marca la solicitud como aprobada: descuento → `dcto` + recálculo de `monto_total`; reasignación → `reasignarVendedor` con su cascada existente a oportunidades activas.
5. El aprobador debe tener el rol `rol_aprobador` de la solicitud (`admin` puede resolver ambas bandejas).

---

## 8. Preguntas abiertas (decisiones tomadas por defecto, confirmar con negocio)

| # | Pregunta | Default asumido |
|---|---|---|
| 1 | ¿El `analista` tiene los mismos límites de descuento que el vendedor? | Sí (3%) |
| 2 | ¿El `jdv` puede ser destino de una asignación de empresa (tener cartera propia)? | Sí |
| 3 | ¿El solicitante puede cancelar una solicitud pendiente? | No en esta fase |
| 4 | ¿Las solicitudes expiran? | No en esta fase |
| 5 | ¿`admin` ve/resuelve también la bandeja del `jdv`? | Sí (admin es superusuario) |
