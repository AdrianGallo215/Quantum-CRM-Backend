# Listado paginado con `oportunidades_count` y detalle de contacto

## Contexto

El frontend está construyendo una vista de Contactos (lista + detalle) y pidió dos cambios en `/api/v1/contactos` (correo, 2026-07-15):

1. `GET /contactos` debe devolver `oportunidades_count` por contacto y paginación estándar (`page`, `per_page`, `meta`), en vez de la lista plana actual.
2. Nuevo `GET /contactos/:id` con el detalle del contacto: sus vínculos a empresas, sus oportunidades vinculadas, y una línea de tiempo `actividades[]` (tareas, eventos, notas).

Al explorar el schema se encontraron dos huecos frente a lo pedido en `actividades[]`:

- `eventos` no tiene columna `id_contacto` en ninguna migración (V14/V21 solo vinculan a `id_oportunidad`/`id_empresa`).
- No existe una entidad "nota" con `id`/`titulo`/`fecha` — solo un campo de texto libre `notas` en varias tablas.

Decidido con el usuario (sesión 2026-07-15): **`actividades[]` solo incluye tareas en esta iteración.** Eventos y notas quedan fuera — agregar `id_contacto` a `eventos` o crear una tabla de notas es una feature nueva, no mencionada en el PRD, y merece su propio diseño.

## Decisiones tomadas

1. **`ContactoListaDto` separado de `ContactoDto`.** `oportunidades_count` no se agrega al `ContactoDto` compartido por `POST`/`PUT`/`GET` — ahí sería engañoso (una tarjeta de contacto recién actualizada mostraría `0` aunque ya tenga oportunidades, porque no se recalcularía). Se crea un DTO propio para el listado, igual que `empresas` separa `EmpresaListaDto` de `EmpresaDetalleDto`.

2. **Composición cruzada de módulos en el controller, no en el service.** `oportunidades` y `tareas` ya dependen de `contactos` (`OportunidadServiceImpl` y `TareaServiceImpl` inyectan `ContactoService`). Si `ContactoServiceImpl` inyectara `OportunidadService`/`TareaService` de vuelta, sería una dependencia circular entre beans de Spring (falla en arranque). Por eso:
   - `oportunidades_count` en el listado y `oportunidades[]`/`actividades[]` en el detalle se resuelven en `ContactoController`, inyectando `OportunidadService` y `TareaService` ahí — mismo patrón que ya usa `EmpresaController` con `ContactoService` para `contactos_count`.
   - `empresas[]` (incluyendo `segmentos`) sí se arma dentro de `ContactoServiceImpl`, porque `EmpresaService` ya es su dependencia y no hay ciclo (`EmpresaServiceImpl` no depende de `contactos`).

3. **`ContactoRepository` pasa a `Specification`.** El `@Query` manual de texto libre y la rama especial de `id_empresa` se reemplazan por `JpaSpecificationExecutor<Contacto>` + `Specification`, igual que `EmpresaRepository`/`EmpresaServiceImpl.especificacion()`. Esto es lo que permite combinar filtros (`q`, `id_empresa`) con un `Pageable` de una sola vez.

4. **Mapeos honestos donde el dato pedido no existe:**
   - `oportunidades[].modelo` expone `codigo` (no `nombre` — `Modelo` no tiene ese campo, y `contrato_api.md §10` ya usa `codigo` para lo mismo en oportunidades). Confirmado con el usuario.
   - `actividades[]` de tipo `tarea`: `titulo = tipo_accion` (enum: `llamada`, `correo`, `reunion`, `whatsapp`, `otro`), `descripcion = descripcion`. `Tarea` no tiene un campo de título libre. Confirmado con el usuario.
   - `actividades[].estado` usa los valores de `EstadoAccion` (`pendiente`, `completada`, `cancelada`), no de `EstadoEvento`.

5. **Sin restricción de visibilidad adicional.** `matriz_permisos.md §2.3` dice "Contactos: Todos → Todos (búsqueda global)" sin restricción por vendedor. El detalle nuevo hereda esa misma regla: cualquier rol autenticado ve el contacto completo, sus empresas y sus oportunidades, sin filtrar por `id_vendedor`. Coherente con que `ContactoServiceImpl.buscar()` tampoco filtra hoy.

## Diseño técnico

### 1. `GET /contactos` — paginación + `oportunidades_count`

**`ContactoRepository`** (nuevo):
```kotlin
interface ContactoRepository :
    JpaRepository<Contacto, Long>,
    JpaSpecificationExecutor<Contacto>
```
Se elimina el método `buscar(q)` con `@Query` manual.

**`ContactoServiceImpl`** — nueva `Specification<Contacto>`. `id_empresa` se resuelve igual que hoy: primero `empresaService.vinculoVisible(idEmpresa, usuario)` (para no perder el chequeo IDOR), luego `empresaContactoRepository.findByIdIdEmpresa(idEmpresa)` → lista de ids de contacto, que se pasa ya resuelta a la specification (no como subquery SQL):
```kotlin
private fun especificacion(q: String?, idsPermitidos: List<Long>?): Specification<Contacto> =
    Specification { root, _, cb ->
        val predicados = mutableListOf<Predicate>()
        idsPermitidos?.let { predicados += root.get<Long>("id").`in`(it) }
        q?.takeIf { it.isNotBlank() }?.let { texto ->
            val patron = "%${texto.lowercase()}%"
            predicados += cb.or(
                cb.like(cb.lower(cb.concat(cb.concat(root.get("nombres"), " "), root.get("apellidos"))), patron),
                cb.like(root.get("tlf1"), "%${texto.trim()}%"),
                cb.like(root.get("tlf2"), "%${texto.trim()}%"),
            )
        }
        cb.and(*predicados.toTypedArray())
    }
```
Si `idsPermitidos` queda vacío (empresa sin contactos vinculados), la specification debe devolver `cb.disjunction()` para esa rama en vez de un `IN ()` vacío (inválido en JPA Criteria).

`ContactoService.buscar(...)` cambia de firma:
```kotlin
fun buscar(
    q: String?,
    idEmpresa: Long?,
    usuario: UsuarioActual,
    page: Int?,
    perPage: Int?,
    sort: String?,
    dir: String?,
): Paginado<ContactoListaDto>
```
Usa `Paginacion.pageRequest(page, perPage, sort, dir, defaultSort = "id")` — mismo default que empresas (orden `id DESC` si no se especifica `sort`/`dir`).

**Nuevo DTO** en `ContactoDtos.kt`:
```kotlin
data class ContactoListaDto(
    val id: Long,
    val nombres: String,
    val apellidos: String,
    val email1: String?,
    val email2: String?,
    val tlf1: String?,
    val tlf2: String?,
    val notas: String?,
    val empresas: List<EmpresaDeContactoDto>,
    val oportunidadesCount: Int = 0,
)
```

**`OportunidadContactoRepository`** — nuevo método:
```kotlin
fun countByIdIdContacto(idContacto: Long): Long
```

**`OportunidadService`** — nuevo método público:
```kotlin
/** Cantidad de oportunidades distintas vinculadas a un contacto (listado de contactos). */
fun countPorContacto(idContacto: Long): Int
```

**`ContactoController.buscar`** — acepta `page`/`per_page`, enriquece igual que `EmpresaController.listar`:
```kotlin
@GetMapping
fun buscar(
    @RequestParam(required = false) q: String?,
    @RequestParam(name = "id_empresa", required = false) idEmpresa: Long?,
    @RequestParam(required = false) page: Int?,
    @RequestParam(name = "per_page", required = false) perPage: Int?,
): ApiResponse<List<ContactoListaDto>> {
    val resultado = contactoService.buscar(q, idEmpresa, usuarioProvider.actual(), page, perPage, null, null)
    val conConteo = resultado.items.map { it.copy(oportunidadesCount = oportunidadService.countPorContacto(it.id)) }
    return ApiResponse.ok(conConteo, resultado.meta)
}
```

### 2. `GET /contactos/:id` (nuevo)

**`ContactoService`** — nuevo método:
```kotlin
fun detalle(id: Long): ContactoDetalleDto
```
**`ContactoServiceImpl.detalle`**: reutiliza `entidad(id)` (ya lanza `NoEncontradoException` → 404) y arma `empresas[]` con `cargo`/`tomaDecision`/`esPrincipal` (de `EmpresaContacto`) + `segmentos` (nuevo método en `EmpresaService`, ver abajo).

**`EmpresaService`** — nuevo método público (no reutiliza `EmpresaResumen` para no tocar su forma en otros módulos que ya la consumen sin `segmentos`, p. ej. `OportunidadDto.empresa`):
```kotlin
fun segmentosPorIds(ids: Collection<Long>): Map<Long, List<String>>
```

**Nuevo DTO**:
```kotlin
data class EmpresaDeContactoDetalleDto(
    val id: Long,
    val razonSocial: String,
    val cargo: String?,
    val tomaDecision: Boolean?,
    val esPrincipal: Boolean,
    val segmentos: List<String>,
)

data class ContactoDetalleDto(
    val id: Long,
    val nombres: String,
    val apellidos: String,
    val email1: String?,
    val email2: String?,
    val tlf1: String?,
    val tlf2: String?,
    val notas: String?,
    val empresas: List<EmpresaDeContactoDetalleDto>,
    val oportunidades: List<OportunidadResumenParaContacto> = emptyList(),
    val actividades: List<ActividadContactoDto> = emptyList(),
)
```
Los campos `oportunidades` y `actividades` quedan en `0`/vacío por defecto al construirse en `ContactoServiceImpl.detalle()` (que no conoce esos módulos) y se completan con `.copy(...)` en el controller — mismo mecanismo que ya usa `EmpresaListaDto.contactosCount` en `EmpresaController.listar`. La respuesta final es un único objeto aplanado (sin envoltorio `contacto` anidado), igual que el ejemplo del correo.

**`OportunidadService`** — nuevo método público:
```kotlin
/** Oportunidades vinculadas a un contacto, para el detalle de contacto. */
fun oportunidadesPorContacto(idContacto: Long): List<OportunidadResumenParaContacto>
```
Implementación en `OportunidadServiceImpl`: `oportunidadContactoRepository.findByIdIdContacto(idContacto)` → `oportunidadRepository.findAllById(...)` → enriquecer con `empresaService.resumenPorIds` + `modeloService.resumenPorIds` (mismo patrón ya usado para construir `OportunidadDto`).

```kotlin
data class OportunidadResumenParaContacto(
    val id: Long,
    val empresa: EmpresaResumen?,
    val modelo: ModeloEnOportunidadDto?,
    val estado: String,
    val montoTotal: String?,
    val fechaCierreEstimado: LocalDate?,
    val rolEnOportunidad: String?,
)
```

**`TareaService`** — nuevo método público:
```kotlin
/** Tareas vinculadas a un contacto, para su linea de tiempo en el detalle. */
fun actividadesPorContacto(idContacto: Long): List<ActividadContactoDto>
```
**`TareaRepository`** — nuevo método `findByIdContactoOrderByFechaEjecucionDesc(idContacto: Long): List<Tarea>` (o `createdAt` desc si `fechaEjecucion` es null — usar `COALESCE(fecha_ejecucion, created_at)` para el orden, ya que `fechaEjecucion` es nullable).

```kotlin
data class ActividadContactoDto(
    val id: Long,
    val tipo: String = "tarea",
    val titulo: String,       // = tipoAccion.name
    val descripcion: String?,
    val fecha: LocalDateTime, // fechaEjecucion ?: createdAt
    val estado: String,       // estadoAccion.name
)
```

**`ContactoController`** — nuevo endpoint, compone las tres fuentes:
```kotlin
@GetMapping("/{id}")
fun detalle(@PathVariable id: Long): ApiResponse<ContactoDetalleDto> {
    val contacto = contactoService.detalle(id)
    val completo = contacto.copy(
        oportunidades = oportunidadService.oportunidadesPorContacto(id),
        actividades = tareaService.actividadesPorContacto(id),
    )
    return ApiResponse.ok(completo)
}
```

### Documentación a actualizar

- `docs/contrato_api.md §9`: reescribir `GET /contactos` (paginación, `oportunidades_count`) y agregar `GET /contactos/:id` con nota explícita: *"`actividades[]` incluye solo tareas por ahora; eventos y notas se agregarán cuando el schema lo soporte."*

## Testing (TDD obligatorio)

1. `ContactoRepositoryTest` (o equivalente Testcontainers): specification con `q`, con `id_empresa`, combinados con paginación.
2. `ContactoServiceImplTest`: `buscar()` devuelve `Paginado` correcto; `detalle()` arma `empresas[]` con `segmentos`; `detalle()` de un id inexistente lanza `NoEncontradoException`.
3. `OportunidadServiceImplTest`: `countPorContacto` cuenta oportunidades distintas (no duplica si el contacto está en varias filas de otra tabla); `oportunidadesPorContacto` devuelve vacío si no hay vínculos, mapea `modelo.codigo` y `montoTotal` como string.
4. `TareaServiceImplTest`: `actividadesPorContacto` ordena por fecha descendente usando `fechaEjecucion ?: createdAt`; `titulo` = `tipoAccion`.
5. `ContactoControllerWebMvcTest` (o `ContactoIntegrationTest`): `GET /contactos?page=2&per_page=10` devuelve `meta` correcto y `oportunidades_count` por item; `GET /contactos/:id` con id inexistente → 404; con id existente → shape completo (`empresas`, `oportunidades`, `actividades`).

## Fuera de alcance

- `actividades[]` de tipo `evento` y `nota`: requieren cambios de schema (columna `id_contacto` en `eventos`, tabla nueva de notas) no autorizados en esta iteración.
- No se filtra el detalle ni el listado por visibilidad de vendedor — coherente con la matriz de permisos actual de contactos ("Todos").
