# DELETE definitivo de empresas y oportunidades, exclusivo admin

## Contexto

Pedido del usuario (sesión 2026-07-17): endpoints DELETE para eliminar definitivamente (hard delete) entidades del CRM, disponibles **exclusivamente para el rol `admin`**. Caso concreto: al eliminar una empresa, deben eliminarse también sus oportunidades y actividades relacionadas; los contactos relacionados **no** deben eliminarse (solo se desvinculan).

Alcance acordado con el usuario: **empresas y oportunidades**. `DELETE /contactos/:id` ya existe (`admin`, `gerencia`, `jdv`) y se deja tal cual — no se toca. No se agregan endpoints DELETE para eventos ni tareas como entidades independientes en esta iteración (se eliminan solo como efecto de la cascada de empresa/oportunidad).

Estado actual del código (verificado):
- Ninguna entidad usa relaciones JPA (`@OneToMany`/`@ManyToOne`/`cascade`); todo son FKs planas (`Long`) y el comportamiento de borrado vive por completo en las constraints SQL de Flyway.
- `reglas_negocio.md §11.2` documenta hoy la regla contraria a la que pide el usuario: *"No se puede eliminar una empresa que tiene oportunidades (`ON DELETE RESTRICT`)"*. Esta iteración cambia esa regla de negocio de forma explícita y documentada.
- `reglas_negocio.md` línea 124 ya anticipaba: *"Al eliminar una oportunidad (si se implementa) → recalcular [estado_cartera]"* — esta iteración lo implementa.

## Decisiones tomadas (confirmadas con el usuario)

1. **Cascada a nivel de base de datos, no manual en el service.** Se cambian 3 FKs de `RESTRICT` a `CASCADE` vía migración Flyway. Un solo `DELETE` en Postgres arrastra todo de forma atómica; es el mismo mecanismo que ya usa `eventos.id_empresa` (V21). Se descartó la cascada manual en Kotlin por más superficie de error para el mismo resultado.
2. **Sin restricción por estado `facturado`.** El admin puede eliminar una empresa u oportunidad aunque tenga ventas en estado `facturado`. Es una decisión explícita del usuario, a pesar de que `facturado` es la "regla de oro" del negocio (venta cerrada/dinero desembolsado) — se documenta como irreversible en `contrato_api.md` para que quede claro el riesgo.
3. **Contactos nunca se eliminan por cascada.** Solo se elimina la fila de vínculo en `empresa_contactos` (la relación N:M), nunca la fila de `contactos`. La FK `empresa_contactos_id_contacto_fkey` se queda en `RESTRICT`.
4. **`estado_cartera` se recalcula tras eliminar una oportunidad individual**, reutilizando `EstadoCarteraService.actualizar()` sin modificarlo (ya re-consulta el conjunto completo de oportunidades de la empresa, así que simplemente no cuenta la que se acaba de borrar). No aplica al eliminar una empresa completa, porque la empresa deja de existir.
5. **Sin chequeo de "recurso ajeno" (IDOR) en estos dos endpoints.** Solo `admin` puede invocarlos, y `admin` no tiene `visibilidadRestringida` — un `entidad(id)` simple basta; 404 si el id no existe, sin necesidad de distinguir dueño.
6. **`DELETE /contactos/:id` no se modifica.** El usuario pidió "exclusivamente admin" pensando en los endpoints nuevos; al preguntar el alcance explícitamente, confirmó que solo empresas y oportunidades entran en esta iteración. Los roles actuales de contactos (`admin`, `gerencia`, `jdv`) se mantienen.

## Diseño técnico

### 1. Migración `V29__cascada_eliminacion_empresa.sql`

Cambia el `ON DELETE` de 3 constraints existentes (se identifican por su nombre autogenerado de Postgres, visible en las migraciones originales):

```sql
ALTER TABLE oportunidades
    DROP CONSTRAINT oportunidades_id_empresa_fkey,
    ADD CONSTRAINT oportunidades_id_empresa_fkey
        FOREIGN KEY (id_empresa) REFERENCES empresas(id) ON DELETE CASCADE;

ALTER TABLE tareas
    DROP CONSTRAINT tareas_id_empresa_fkey,
    ADD CONSTRAINT tareas_id_empresa_fkey
        FOREIGN KEY (id_empresa) REFERENCES empresas(id) ON DELETE CASCADE;

ALTER TABLE empresa_contactos
    DROP CONSTRAINT empresa_contactos_id_empresa_fkey,
    ADD CONSTRAINT empresa_contactos_id_empresa_fkey
        FOREIGN KEY (id_empresa) REFERENCES empresas(id) ON DELETE CASCADE;
```

`empresa_contactos_id_contacto_fkey` (lado contacto) **no se toca** — se queda `RESTRICT`, así el contacto en sí sigue protegido de borrado mientras tenga vínculos.

No se necesita ningún otro cambio de constraint: `eventos.id_empresa` ya es `CASCADE` (V21), y al eliminar una oportunidad (por cascada desde empresa, o directo) ya arrastran limpio `oportunidad_estados_log` (`CASCADE`, V11), `oportunidad_contactos` del lado oportunidad (`CASCADE`, V12 — el lado contacto sigue `RESTRICT` y no aplica aquí), `eventos.id_oportunidad` (`CASCADE`, V14) y `tareas.id_oportunidad` (`CASCADE`, V15).

Cadena resultante al hacer `DELETE FROM empresas WHERE id = X`:
```
empresa
 ├─ empresa_contactos (fila de vínculo borrada; contacto intacto)
 ├─ eventos.id_empresa = X (borrados)
 ├─ tareas.id_empresa = X (borradas)
 └─ oportunidades.id_empresa = X (borradas), y para cada una:
     ├─ oportunidad_estados_log (borrado)
     ├─ oportunidad_contactos (fila de vínculo borrada; contacto intacto)
     ├─ eventos.id_oportunidad = esa oportunidad (borrados)
     └─ tareas.id_oportunidad = esa oportunidad (borradas)
```

### 2. `DELETE /api/v1/empresas/{id}`

**`EmpresaController.kt`**:
```kotlin
@DeleteMapping("/{id}")
@ResponseStatus(HttpStatus.NO_CONTENT)
@PreAuthorize("hasRole('admin')")
fun eliminar(@PathVariable id: Long) {
    empresaService.eliminar(id)
}
```

**`EmpresaService`** — nuevo método:
```kotlin
fun eliminar(id: Long)
```

**`EmpresaServiceImpl.eliminar`**:
```kotlin
@Transactional
override fun eliminar(id: Long) {
    val empresa = entidad(id)
    empresaRepository.delete(empresa)
}
```
Reutiliza el `entidad(id)` privado ya existente (lanza `NoEncontradoException` → 404 si no existe). No usa `visible()` — ese helper filtra por cartera maestra/vendedor, pero `admin` siempre pasa esos filtros, así que sería una llamada redundante; se usa `entidad()` directamente, igual que hace `ContactoServiceImpl.eliminar()` hoy.

### 3. `DELETE /api/v1/oportunidades/{id}`

**`OportunidadController.kt`**:
```kotlin
@DeleteMapping("/{id}")
@ResponseStatus(HttpStatus.NO_CONTENT)
@PreAuthorize("hasRole('admin')")
fun eliminar(@PathVariable id: Long) {
    oportunidadService.eliminar(id)
}
```

**`OportunidadService`** — nuevo método:
```kotlin
fun eliminar(id: Long)
```

**`OportunidadServiceImpl.eliminar`**:
```kotlin
@Transactional
override fun eliminar(id: Long) {
    val oportunidad = entidad(id)
    val idEmpresa = oportunidad.idEmpresa
    oportunidadRepository.delete(oportunidad)
    estadoCarteraService.actualizar(idEmpresa)
}
```
`estadoCarteraService.actualizar()` ya existe y ya re-consulta el conjunto completo de oportunidades de la empresa (no asume nada de la transición individual) — no requiere ningún cambio, solo se invoca en el punto nuevo. Misma transacción, coherente con el patrón que ya usan `crear()` y `cambiarEstado()` en este mismo archivo.

### 4. Manejo de errores

No se agrega ninguna excepción de negocio nueva. Ambos métodos solo pueden fallar con `NoEncontradoException` (404) si el id no existe, o `AccessDeniedException` → 403 `PERMISO_INSUFICIENTE` (vía `@PreAuthorize`, manejado ya por `GlobalExceptionHandler`) si el rol no es `admin`. Respuesta de éxito: `204 No Content`, sin body — mismo patrón que `DELETE /contactos/:id`.

### 5. Documentación a actualizar

- **`docs/contrato_api.md`**: nuevas secciones `DELETE /empresas/:id` y `DELETE /oportunidades/:id`, mismo formato que la sección existente de `DELETE /contactos/:id` (§ roles, 204, notas). La nota debe ser explícita sobre el alcance de la cascada y la irreversibilidad, por ejemplo:
  > **Roles:** `admin`
  > **Respuesta 204:** sin body.
  > **Notas:**
  > - Elimina también todas sus oportunidades, tareas, eventos y el log de estados de esas oportunidades. Los contactos vinculados **no** se eliminan, solo se desvinculan.
  > - No hay restricción por estado (incluye oportunidades `facturado`). Operación irreversible.

  Actualizar también la tabla resumen de §5 "Autorización por rol" con las dos filas nuevas.
- **`docs/matriz_permisos.md`**: filas nuevas "Eliminar empresa" y "Eliminar oportunidad", con `✓` solo en la columna `admin`.
- **`docs/reglas_negocio.md §11.2`**: reemplazar el texto actual ("No se puede eliminar una empresa que tiene oportunidades") por la descripción de la cascada real, y agregar una línea sobre el recálculo de `estado_cartera` al eliminar una oportunidad individual.

## Testing (TDD obligatorio)

1. **`EmpresaRepositoryTest` o integración con Testcontainers**: crear empresa con una oportunidad (que a su vez tiene log, un evento y una tarea), un evento y una tarea propios de la empresa, y un contacto vinculado. Ejecutar `DELETE /empresas/{id}` como `admin` → `204`. Verificar: la oportunidad, sus eventos/tareas/log, el evento y la tarea directos de la empresa, y la fila de `empresa_contactos` ya no existen; el contacto sigue existiendo en `contactos`.
2. **Permisos**: `DELETE /empresas/{id}` como no-admin (`gerencia`, `vendedor`, etc.) → `403`. Id inexistente → `404`.
3. **`OportunidadServiceImplTest` / integración**: empresa con una oportunidad en estado activo (`estado_cartera = oportunidad_activa`) → `DELETE /oportunidades/{id}` → verificar que `estado_cartera` de la empresa se recalcula (vuelve a `null` si no quedan otras oportunidades, o se mantiene si quedan otras activas/facturadas).
4. **Permisos**: `DELETE /oportunidades/{id}` como no-admin → `403`. Id inexistente → `404`.
5. **Caso facturado**: `DELETE /oportunidades/{id}` sobre una oportunidad en estado `facturado` → `204` (confirma que no hay bloqueo de negocio, según decisión 2).
6. Unitarios de `EmpresaServiceImpl.eliminar` y `OportunidadServiceImpl.eliminar` mockeando repositorios (incluye verificar que `estadoCarteraService.actualizar` se invoca con el `idEmpresa` correcto tras eliminar una oportunidad).

## Fuera de alcance

- `DELETE /eventos/:id` y `DELETE /tareas/:id` como endpoints independientes — no se piden en esta iteración; esas entidades solo se eliminan como efecto de cascada.
- Modificar `DELETE /contactos/:id` (roles o comportamiento) — queda igual que hoy.
- Cualquier forma de soft-delete (columna `activo`/`deleted_at`) — el pedido es explícitamente hard delete, y ninguna de estas tablas tiene ese patrón hoy (a diferencia de `empleados`).
