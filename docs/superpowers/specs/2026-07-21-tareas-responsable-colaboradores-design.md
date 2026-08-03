# Responsable y colaboradores en tareas

## Contexto

El usuario pidió poder agregar "responsables" opcionales a una tarea (un empleado del equipo,
no un contacto), que al asignarse reciba una notificación y vea la tarea en sus actividades.

Investigando el código existente se confirmó que **la mayor parte de esto ya existe**:
`tareas.id_asignado` (migración V15) ya es un FK opcional a `empleados`, ya se notifica al
crear la tarea (`TipoNotificacion.tarea_creada`, `TareaServiceImpl.crear`), y ya aparece en las
actividades del empleado vía `GET /tareas` (filtrado por rol) y el panel `GET /inicio`.

Confirmado con el usuario que lo que realmente falta es soportar que una tarea sea de **un
dueño único + varios colaboradores** (trabajo conjunto), y cerrar dos huecos puntuales:

1. Reasignar el dueño de una tarea existente (`PUT /tareas/:id` cambiando `id_asignado`) no
   dispara ninguna notificación hoy.
2. `matriz_permisos.md §2.6` documenta que "asignar tarea a otro empleado" es exclusivo de
   admin/gerencia/jdv, pero el código no lo valida — cualquier rol puede asignar hoy una tarea
   a cualquier otro empleado.

## Modelo de datos

`tareas.id_asignado` (dueño principal) no cambia. Se agrega una tabla N a N para los
colaboradores, mismo patrón que `oportunidad_contactos` (join table, PK compuesta):

```sql
-- V31__create_tarea_responsables.sql

CREATE TABLE tarea_responsables (
    id_tarea    BIGINT      NOT NULL REFERENCES tareas(id) ON DELETE CASCADE,
    id_empleado BIGINT      NOT NULL REFERENCES empleados(id),
    created_at  TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by  BIGINT      NOT NULL REFERENCES empleados(id),
    PRIMARY KEY (id_tarea, id_empleado)
);

CREATE INDEX idx_tarea_responsables_empleado ON tarea_responsables(id_empleado);

ALTER TYPE tipo_notificacion_enum ADD VALUE 'tarea_colaborador_agregado';
```

`ON DELETE CASCADE` desde `tareas`: si se elimina la tarea (o en cascada desde
oportunidad/empresa, reglas ya existentes), sus filas de colaboradores se eliminan con ella.
No hay `ON DELETE` especial en `id_empleado` — un empleado no se elimina físicamente
(`cambiarActivo`), así que no aplica.

Se reutiliza `TipoNotificacion.tarea_creada` para la notificación de reasignación de dueño (no
se agrega un enum nuevo para eso: desde la óptica del destinatario el mensaje es equivalente,
"te asignaron esta tarea").

## Entidad y repositorio

Nueva entidad `TareaResponsable` con `@EmbeddedId` (mismo patrón que
`OportunidadContacto`/`OportunidadContactoId`):

```kotlin
@Embeddable
data class TareaResponsableId(
    @Column(name = "id_tarea") val idTarea: Long = 0,
    @Column(name = "id_empleado") val idEmpleado: Long = 0,
) : Serializable

@Entity
@Table(name = "tarea_responsables")
class TareaResponsable(
    @EmbeddedId val id: TareaResponsableId,
    @Column(name = "created_at") val createdAt: LocalDateTime,
    @Column(name = "created_by") val createdBy: Long,
)

interface TareaResponsableRepository : JpaRepository<TareaResponsable, TareaResponsableId> {
    fun findByIdIdTarea(idTarea: Long): List<TareaResponsable>
    fun findByIdIdTareaIn(idsTarea: Collection<Long>): List<TareaResponsable>
    fun findByIdIdEmpleado(idEmpleado: Long): List<TareaResponsable>
    fun deleteByIdIdTarea(idTarea: Long)
}
```

## Contrato de API (`contrato_api.md §12`)

- `TareaDto`: agrega `ids_colaboradores: long[]` y `colaboradores: EmpleadoResumen[]` (mismo
  patrón que `asignado`/`contacto`, resuelto vía `empleadoService.resumenPorIds(...)`).
- `POST /tareas` — body agrega `ids_colaboradores: long[]` (opcional, default `[]`).
- `PUT /tareas/:id` — body agrega `ids_colaboradores: long[]?` (opcional). Si el campo viene en
  el body (no es `null`), **reemplaza el set completo** de colaboradores de la tarea; si se
  omite, los colaboradores existentes no se tocan. Mismo criterio que los demás campos
  opcionales de este endpoint.
- `GET /tareas`: sin nuevos query params. El filtro de visibilidad por rol
  (`visibilidadRestringida`) ahora también hace match si el usuario autenticado es colaborador
  de la tarea, no solo si es el dueño (`id_asignado`).

**Nuevo código de error:**

| Código | HTTP | Cuándo |
|---|---|---|
| `PERMISO_INSUFICIENTE` (ya existe, `PermisoInsuficienteException`) | 403 | vendedor/analista intenta poner como dueño o colaborador a un empleado distinto de sí mismo. |

## Lógica de servicio (`TareaServiceImpl`)

### Permisos (aplica `matriz_permisos.md §2.6`)

Nueva función privada:

```kotlin
private fun validarPermisoAsignacion(idsEmpleadosAAsignar: Set<Long>, usuario: UsuarioActual) {
    if (!usuario.esSupervisor && idsEmpleadosAAsignar.any { it != usuario.id }) {
        throw PermisoInsuficienteException("Solo admin, gerencia o jdv pueden asignar la tarea a otro empleado")
    }
}
```

Se llama en `crear` con `setOf(idAsignado) + idsColaboradores` y en `actualizar` con el/los
valores que efectivamente cambian (`idAsignado` nuevo si viene, `idsColaboradores` nuevo si
viene). Un vendedor/analista sigue pudiendo dejarse a sí mismo como dueño o colaborador sin
restricción (comportamiento actual, sin cambios).

### `crear()`

1. (sin cambios) resuelve empresa/oportunidad/contacto, valida `idAsignado` default al usuario.
2. `idsColaboradores` = `request.idsColaboradores.orEmpty().toSet() - idAsignado` (excluye
   duplicado del dueño; el propio dueño no puede ser también colaborador).
3. `validarPermisoAsignacion(setOf(idAsignado) + idsColaboradores, usuario)`.
4. Valida cada id de `idsColaboradores` con `empleadoService.existeActivo(it)` →
   `NoEncontradoException` si alguno no existe/inactivo (mismo criterio que `idAsignado` hoy).
5. Guarda la `Tarea` (sin cambios), luego guarda una fila `TareaResponsable` por cada
   colaborador.
6. Notifica al dueño (sin cambios, ya existe). Notifica a cada colaborador con
   `TipoNotificacion.tarea_colaborador_agregado`, mensaje:
   `"${actor} te agregó como colaborador en una tarea en ${empresa.razonSocial}"`.

### `actualizar()`

1. (sin cambios) valida que la tarea siga `pendiente`.
2. Si `request.idAsignado != null && request.idAsignado != tarea.idAsignado`:
   `validarPermisoAsignacion(setOf(request.idAsignado), usuario)`, valida
   `empleadoService.existeActivo(...)`, actualiza `tarea.idAsignado`, y **notifica al nuevo
   dueño** con `TipoNotificacion.tarea_creada` (mensaje: `"${actor} te asignó una tarea en
   ${empresa.razonSocial}"` — mismo mensaje que la creación).
3. Si `request.idsColaboradores != null` (el campo vino en el body, aunque sea `[]`):
   - `nuevoSet = request.idsColaboradores.toSet() - (request.idAsignado ?: tarea.idAsignado)`.
   - `validarPermisoAsignacion(nuevoSet, usuario)`.
   - Valida cada id con `empleadoService.existeActivo(...)`.
   - `actualSet = tareaResponsableRepository.findByIdIdTarea(tarea.id).map { it.id.idEmpleado }.toSet()`.
   - `agregados = nuevoSet - actualSet`.
   - Borra todas las filas existentes de esa tarea, inserta una fila por cada id de `nuevoSet`.
   - Notifica **solo a `agregados`** con `tarea_colaborador_agregado` (los que ya estaban no se
     re-notifican; los removidos no reciben notificación de remoción — no se pidió).
   - Tradeoff aceptado: al reemplazar el set completo, un colaborador que ya estaba y sigue
     estando pierde su `created_at` original (se reinserta con la fecha de esta actualización).
     No se pidió preservar ese historial; si en el futuro importa, se resuelve con un diff
     insertar/borrar en vez de borrar-todo-e-insertar-todo, sin cambiar el contrato de API.

### Visibilidad (`especificacion()`, `visible()`, `actividadesPorContacto()`)

`visibilidadRestringida` debe hacer match si el usuario es dueño **o** colaborador. Como
`Specification`/`JpaSpecificationExecutor` no puede hacer fácilmente un `EXISTS` contra otra
tabla desde una lambda simple sin acoplar la entidad `Tarea` a la relación, la forma más simple
y consistente con el resto del módulo (que ya resuelve todo con queries explícitas, no con
`@ManyToMany` bidireccional) es:

- `especificacion()`: además del predicado por `idAsignado`, agrega un `root.get<Long>("id")`
  `IN` una subquery a `tarea_responsables` filtrada por `id_empleado = usuario.id`, combinados
  con `cb.or(...)` en vez de `cb.and(...)` cuando `visibilidadRestringida`.
- `visible(id, usuario)`: además de `tarea.idAsignado == usuario.id`, permite si existe una fila
  `TareaResponsable` con `idTarea = id, idEmpleado = usuario.id`.
- `actividadesPorContacto()`: mismo criterio (dueño o colaborador) al filtrar `visibles`.

`InicioService.tareasPendientes()` no requiere cambios: ya delega en
`tareaService.listar(...)`, que hereda automáticamente el filtro de visibilidad extendido.

### `toDtos()`

Batch-carga `tareaResponsableRepository.findByIdIdTareaIn(tareas.mapNotNull { it.id })`, agrupa
por `idTarea`, resuelve `EmpleadoResumen` con `empleadoService.resumenPorIds(...)` (una sola
consulta batch, mismo patrón que `asignados`/`contactos`/`empresas` ya existente en esa
función) y arma `idsColaboradores`/`colaboradores` por tarea.

## Notificaciones

No hay cambios en `NotificacionService`/`NotificacionServiceImpl` — se reutiliza `notificar(...)`
tal cual existe. `entidadTipo`/`entidadId` siguen el mismo criterio ya establecido (oportunidad
si la tarea tiene una, si no la empresa).

## Testing

Sin TDD estricto (MVP, confirmado con el usuario) pero con cobertura equivalente escrita junto
con la implementación, antes de dar la tarea por cerrada (`./gradlew test` en verde):

- `TareaServiceImplTest` (unit, MockK): crear con colaboradores nuevos notifica a cada uno;
  reasignar dueño notifica al nuevo dueño y no al anterior; reemplazar colaboradores solo
  notifica a los agregados, no a los que ya estaban ni a los removidos; vendedor que intenta
  asignar dueño/colaborador a otro empleado → `PermisoInsuficienteException`; vendedor
  asignándose a sí mismo → sin excepción.
- `TareaControllerWebMvcTest` (o el archivo equivalente que exista): `POST`/`PUT /tareas`
  aceptan `ids_colaboradores`, la respuesta incluye `colaboradores[]`.
- Integración (Testcontainers), estilo `SchemaMigrationIntegrationTest`: migración `V31` aplica
  limpio; `ON DELETE CASCADE` borra `tarea_responsables` al eliminar la tarea (o su
  oportunidad/empresa en cascada); un colaborador ve la tarea en `GET /tareas` aunque no sea el
  `id_asignado`.

## Actualización de `contrato_api.md` y `matriz_permisos.md`

- `contrato_api.md §12`: agregar `ids_colaboradores`/`colaboradores` a `TareaDto`, al body de
  `POST`/`PUT /tareas`, y una nota explicando el reemplazo-de-set en `PUT`.
- `matriz_permisos.md §2.6`: sin cambio de contenido (la fila "Asignar tarea a otro empleado" ya
  documenta la regla correcta) — este trabajo simplemente hace que el código la cumpla. Se
  agrega una fila "Agregar colaborador a otro empleado" con el mismo criterio.

## Fuera de alcance

- No se modela una jerarquía jdv→vendedor ni un concepto de "equipo" — no es necesario para
  esta funcionalidad.
- No se notifica al colaborador removido, ni al dueño anterior tras una reasignación (confirmado
  con el usuario: solo se notifica al nuevo responsable).
- No se agrega un endpoint dedicado de "mis actividades" — `GET /tareas` (con el filtro de
  visibilidad extendido) y `GET /inicio` ya cubren el caso sin duplicar lógica.
- No se permite que el dueño (`id_asignado`) esté vacío cuando hay colaboradores — sigue
  existiendo siempre un dueño (default: el creador), los colaboradores son estrictamente
  adicionales.
