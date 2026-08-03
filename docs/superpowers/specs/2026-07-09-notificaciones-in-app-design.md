# Notificaciones in-app

## Contexto

El CRM necesita notificar a un usuario cuando ocurre una acción relacionada con él pero no
accionada por él mismo (nadie se notifica de su propia acción), más recordatorios de tareas y
eventos. Es puramente backend: persistencia + 4 endpoints + 2 jobs. Sin WebSocket, sin
preferencias configurables, sin inbox — eso es responsabilidad del frontend.

## Hallazgo clave: no existe concepto de "equipo"

El catálogo de eventos original asume que un vendedor tiene "su" jdv. Investigando el código
existente (`UsuarioActual.esSupervisor` / `visibilidadRestringida`, `EmpresaServiceImpl`,
`OportunidadServiceImpl`) se confirmó que **no existe ninguna relación jdv↔vendedor**: no hay
`id_jefe` en `Empleado`, y `admin`, `gerente`, `jdv` son tratados de forma idéntica — todos ven
el sistema completo (`visibilidadRestringida = false` para los tres). No hay tampoco un query
"mi equipo" en ningún repositorio.

**Decisión (confirmada con el usuario):** para todo evento donde el destinatario documentado es
"su jdv, gerente, admin", el destinatario real es el **broadcast a todos los empleados activos
con rol `admin`, `gerente` o `jdv`**, sin excepción. Esto es consistente con el modelo de
visibilidad ya existente (los tres roles ya ven todo por igual) y no requiere tocar el esquema
de `empleados`. Si en el futuro se modela una jerarquía real, este broadcast se puede acotar sin
cambiar el contrato de los endpoints.

## Modelo de datos

Migración `V22__create_notificaciones.sql` (el repo está en V21, no V19 — `CLAUDE.md` está
desactualizado; confirmado por `SeedFixtures.MIGRACIONES_TOTAL`).

```sql
CREATE TYPE tipo_notificacion_enum AS ENUM (
  'oportunidad_cambio_estado', 'empresa_convertida', 'evento_creado', 'tarea_creada',
  'empresa_asignada', 'oportunidad_traspasada', 'tarea_recordatorio', 'evento_recordatorio'
);
CREATE TYPE entidad_notificacion_enum AS ENUM ('oportunidad', 'empresa');

CREATE TABLE notificaciones (
    id BIGSERIAL PRIMARY KEY,
    id_empleado_destinatario BIGINT NOT NULL REFERENCES empleados(id),
    id_actor BIGINT NOT NULL REFERENCES empleados(id),
    tipo tipo_notificacion_enum NOT NULL,
    mensaje TEXT NOT NULL,
    entidad_tipo entidad_notificacion_enum NOT NULL,
    entidad_id BIGINT NOT NULL,
    leida BOOLEAN NOT NULL DEFAULT false,
    created_at TIMESTAMP NOT NULL DEFAULT now()
);
CREATE INDEX idx_notificaciones_destinatario ON notificaciones (id_empleado_destinatario, created_at DESC);

-- Dedup del job de recordatorios. Tabla separada (no un flag en `notificaciones`) para que el
-- job de limpieza (purga leidas > 30 dias) nunca pueda reabrir una ventana de duplicado.
CREATE TYPE origen_recordatorio_enum AS ENUM ('tarea', 'evento');
CREATE TYPE umbral_recordatorio_enum AS ENUM ('proximo', 'vencido');

CREATE TABLE recordatorios_enviados (
    id BIGSERIAL PRIMARY KEY,
    origen origen_recordatorio_enum NOT NULL,
    id_origen BIGINT NOT NULL,
    umbral umbral_recordatorio_enum NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT now(),
    CONSTRAINT uq_recordatorio UNIQUE (origen, id_origen, umbral)
);
```

`entidad_tipo` solo tiene `oportunidad` | `empresa` (no `tarea`/`evento`) porque el frontend
navega a la oportunidad o empresa relacionada, nunca a una tarea/evento suelto. Para
`tarea_creada`/`tarea_recordatorio` la entidad referenciada es la oportunidad de la tarea si
tiene una (`Tarea.idOportunidad != null`), si no la empresa (`Tarea.idEmpresa`). Mismo criterio
para `evento_creado`/`evento_recordatorio` según `Evento.idOportunidad`/`Evento.idEmpresa`
(son mutuamente excluyentes, ya lo son en el modelo actual).

## Estructura del módulo

```
src/main/kotlin/pe/quantum/crm/domain/notificaciones/
├── Notificacion.kt
├── NotificacionRepository.kt
├── NotificacionService.kt          (interfaz)
├── NotificacionServiceImpl.kt
├── NotificacionController.kt
├── dto/NotificacionDtos.kt
└── jobs/
    ├── RecordatorioJob.kt
    └── LimpiezaNotificacionesJob.kt
```

### Interfaz pública (usada por los otros módulos como efecto secundario)

```kotlin
interface NotificacionService {
    fun notificar(
        destinatarios: Set<Long>,
        idActor: Long,
        tipo: TipoNotificacion,
        mensaje: String,
        entidadTipo: EntidadNotificacion,
        entidadId: Long,
    )
    // + los 4 métodos de lectura/escritura para los endpoints
}
```

`notificar` descarta `idActor` del set de `destinatarios` (la regla "nadie se notifica de su
propia acción" se aplica una sola vez, centralizada, en vez de en cada punto de enganche) y
no hace nada si el set resultante queda vacío. El mensaje llega **ya armado** por quien llama —
`notificaciones` no conoce el dominio de oportunidades/empresas más allá de lo que le pasan.

## Endpoints (`/api/v1/notificaciones`, requieren JWT, sin rol adicional)

Todos con el envelope estándar `{ data, meta, error }` de `contrato_api.md`.

1. **`GET /notificaciones/no-leidas/count`** → `{ "data": { "count": 5 }, "meta": null, "error": null }`
2. **`GET /notificaciones`** → últimas 20 (leídas + no leídas) del usuario autenticado,
   `ORDER BY created_at DESC`, sin paginación. `data` es un array de `NotificacionDto`.
3. **`PATCH /notificaciones/{id}/leida`** → marca una como leída. `404 NO_ENCONTRADO` si no
   existe o `id_empleado_destinatario != usuario.id` (mismo patrón IDOR que el resto del
   backend: `NoEncontradoException`, ya manejada por `GlobalExceptionHandler`, sin código nuevo
   de manejo de errores).
4. **`PATCH /notificaciones/leidas`** → marca todas las no leídas del usuario autenticado.

```kotlin
data class NotificacionDto(
    val id: Long,
    val tipo: String,
    val mensaje: String,
    val entidadTipo: String,
    val entidadId: Long,
    val leida: Boolean,
    val createdAt: LocalDateTime,
    val actor: EmpleadoResumen,
)
```

`actor` se resuelve con `EmpleadoService.resumenPorIds(...)`, igual que en el resto del
contrato (mismo patrón que `TareaDto.asignado`).

## Enganches en los flujos mutadores existentes

Ningún endpoint nuevo de negocio; todos son efectos secundarios agregados a métodos que ya
existen, dentro de la misma transacción (para que la notificación no exista si la operación de
negocio falla).

| Evento | Archivo / método | Cambio |
|---|---|---|
| `oportunidad_cambio_estado` | `OportunidadServiceImpl.cambiarEstado` | Al final, antes del `return`: notificar a `empleadoService.idsSupervisoresActivos()` (menos el actor). Mensaje: `"{actor} cambió el estado de {empresa.razonSocial} a {etiqueta(nuevo)}"`. |
| `empresa_convertida` | `EmpresaService.aplicarEstadoDerivado` + `EstadoCarteraService.actualizar` + `OportunidadServiceImpl.crear`/`cambiarEstado` | Ver sección siguiente — requiere ampliar el tipo de retorno de `aplicarEstadoDerivado`. |
| `evento_creado` | `EventoServiceImpl.crear` (privado, llamado por `crearEnOportunidad`/`crearEnEmpresa`) | Resolver vendedor con el `OportunidadVinculo`/`EmpresaVinculo` que el caller ya obtuvo. Si `actor != vendedor` → notificar al vendedor. Si `actor == vendedor` → notificar a supervisores. |
| `tarea_creada` | `TareaServiceImpl.crear` | Notificar a `idAsignado` si `actor != idAsignado` (ya se auto-excluye cuando `idAsignado` no viene en el body, porque por defecto es `usuario.id`). |
| `empresa_asignada` | `EmpresaServiceImpl.reasignarVendedor` | **Cambio de firma**: agregar `usuario: UsuarioActual` (hoy no se recibe, aunque el controller sí lo tiene disponible vía `usuarioProvider.actual()` y simplemente no se lo pasa). Notificar al vendedor destino si `actor != destino`. |
| `oportunidad_traspasada` | `OportunidadServiceImpl.traspasar` | Mismo cambio de firma y mismo criterio. |
| `tarea_recordatorio` / `evento_recordatorio` | job nuevo | Ver "Jobs programados". |

### `empresa_convertida`: detección de la transición

No existe un endpoint "convertir". La conversión es un efecto colateral de `POST
/oportunidades` cuando la empresa estaba en `prospeccion` y pasa a `oportunidad_activa` via
`estadoCarteraService.actualizar(empresa.id)` (la única vía de escritura de `estado_cartera`,
regla #3 de `CLAUDE.md` — no se toca esa regla, solo se hace visible su resultado).

`EmpresaService.aplicarEstadoDerivado` hoy devuelve `Unit`. Se amplía a:

```kotlin
data class CambioEstadoCartera(val anterior: EstadoCartera, val nuevo: EstadoCartera)

fun aplicarEstadoDerivado(idEmpresa: Long, derivado: EstadoCartera?): CambioEstadoCartera?
```

Devuelve `null` cuando la guarda de "sin cambio real" ya existente decide no escribir (mismo
comportamiento de hoy); devuelve el par cuando sí escribe. `EstadoCarteraService.actualizar`
propaga ese valor a su propio caller.

Tanto `OportunidadServiceImpl.crear` como `OportunidadServiceImpl.cambiarEstado` llaman a
`estadoCarteraService.actualizar(...)` — y la lógica de retroceso (`esRetroceso`, reglas §13.4)
hace que, en teoría, `cambiarEstado` también pueda producir la transición
`prospeccion → oportunidad_activa` (una empresa que había vuelto a `prospeccion` porque se
cerraron todas sus oportunidades, y luego una de ellas retrocede a un estado activo). Por eso
el mismo chequeo se aplica en **ambos** call sites, no solo en `crear`: si el resultado de
`estadoCarteraService.actualizar(...)` es `CambioEstadoCartera(anterior = prospeccion, nuevo =
oportunidad_activa)` → dispara `empresa_convertida` hacia `idsSupervisoresActivos()` (menos el
actor). Mensaje: `"{actor} convirtió {empresa.razonSocial} de prospección a oportunidad"`.

## Jobs programados

No existe ningún job hoy (`@Scheduled`/`@EnableScheduling` no aparecen en el repo) — se agrega
`@EnableScheduling` en `CrmApplication`.

### Recordatorios (`@Scheduled(cron = "0 0 * * * *")`, cada hora)

Necesita proyecciones de solo lectura que hoy no existen, se agregan a las interfaces públicas
existentes (mismo patrón que `resumenPorIds`):

- `TareaService.pendientesParaRecordatorio(): List<TareaRecordatorioProyeccion>` — `(id,
  idAsignado, idEmpresa, idOportunidad, fechaEjecucion)`, filtrado a `estadoAccion = pendiente`,
  `idAsignado != null`, `fechaEjecucion != null`.
- `EventoService.pendientesParaRecordatorio(): List<EventoRecordatorioProyeccion>` — `(id,
  idOportunidad, idEmpresa, fechaEstimada)`, filtrado a `estado = pendiente`, `fechaEstimada !=
  null`.
- `OportunidadService.vendedorAsignado(id: Long): Long?` y
  `EmpresaService.vendedorAsignado(id: Long): Long?` — sin chequeo de visibilidad (es un job de
  sistema, no actúa en nombre de un usuario concreto); usados para resolver a quién le llega el
  recordatorio de un evento.

Umbrales (confirmados con el usuario):

- **Tareas** (`fechaEjecucion: LocalDateTime`): "próximo" = faltan ≤ 24h; "vencido" = ya pasó.
- **Eventos** (`fechaEstimada: LocalDate`, sin componente de hora): "próximo" = `fechaEstimada
  == mañana`; "vencido" = `fechaEstimada < hoy`. Granularidad de día porque el dato de origen no
  tiene hora.

Cada `(origen, id_origen, umbral)` dispara como máximo una vez — se verifica/inserta en
`recordatorios_enviados` (constraint único `uq_recordatorio` como defensa adicional ante una
carrera entre dos corridas del job).

### Limpieza (`@Scheduled(cron = "0 0 3 * * *")`, diario a las 3am)

`DELETE FROM notificaciones WHERE leida = true AND created_at < now() - interval '30 days'`.

## Cambios de firma en módulos existentes (resumen, para que quede explícito)

1. `EmpresaService.aplicarEstadoDerivado`: retorno `Unit` → `CambioEstadoCartera?`.
2. `EmpresaService.reasignarVendedor(id, idVendedor)` → `reasignarVendedor(id, idVendedor,
   usuario: UsuarioActual)`. Controller actualizado para pasar `usuarioProvider.actual()`.
3. `OportunidadService.traspasar(id, idVendedor)` → `traspasar(id, idVendedor, usuario:
   UsuarioActual)`. Mismo ajuste en el controller.
4. Nuevos métodos en `EmpleadoService`: `idsSupervisoresActivos(): List<Long>`.
5. Nuevos métodos en `TareaService`/`EventoService`/`OportunidadService`/`EmpresaService` para
   las proyecciones del job de recordatorios (listados arriba).

Ninguno de estos cambios altera comportamiento observable existente (los tests actuales de
`traspasar`/`reasignarVendedor` deberían seguir pasando con el nuevo parámetro).

## Testing (TDD, `TESTING-backend.md`)

Sigue los dos patrones ya presentes en el repo:

- **Unit (MockK)**, estilo `EventoServiceImplTest.kt`, para `NotificacionServiceImpl`: dedup de
  auto-notificación (actor en el set de destinatarios se descarta), set vacío → no-op, mapeo de
  `entidad_tipo` según origen.
- **Unit (MockK)** en cada módulo enganchado: verificar que `NotificacionService.notificar(...)`
  se invoca con los destinatarios/mensaje/tipo correctos para cada fila de la tabla de arriba,
  y que **no** se invoca cuando el actor es el único destinatario posible (p. ej. vendedor crea
  su propia tarea sin especificar `idAsignado`).
- **Integración (Testcontainers)**, estilo `IntegrationTestBase`, para:
  - Los 4 endpoints (envelope, 404 `NO_ENCONTRADO` en `PATCH /{id}/leida` ajena/inexistente,
    orden `created_at DESC`, límite de 20).
  - El job de recordatorios: mismo umbral no duplica fila en `recordatorios_enviados` en una
    segunda corrida; distinto umbral (próximo → vencido) sí genera una segunda notificación.
  - El job de limpieza: borra solo `leida = true` y `created_at` > 30 días.
  - La migración `V22` aplica limpio sobre el schema actual (patrón
    `SchemaMigrationIntegrationTest`).

## Actualización de `contrato_api.md`

Nueva sección "19. Notificaciones", mismo formato que las secciones existentes (endpoint,
request, response, tabla de errores), documentando los 4 endpoints, el enum `tipo`, el enum
`entidad_tipo`, y el DTO `NotificacionDto` con su `actor: EmpleadoResumen`.

## Fuera de alcance

- Sin WebSocket/tiempo real — el frontend hace polling del `count` y de la lista.
- Sin preferencias configurables por usuario (silenciar tipos, etc.).
- Sin paginación en `GET /notificaciones` (siempre top 20, como se pidió).
- Sin jerarquía jdv→vendedor real — el broadcast a supervisores es la solución explícitamente
  aprobada mientras esa jerarquía no exista en el esquema.
- No se modifica `matriz_permisos.md` (los 4 endpoints no tienen restricción de rol más allá de
  "usuario autenticado").
