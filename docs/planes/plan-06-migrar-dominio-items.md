# Plan B — El dominio migra a `oportunidad_items`

> **Destinatario: agentes ejecutores, no humanos.** Cada tarea es autocontenida.
> Ejecutar en orden estricto: cada una asume que la anterior está cerrada,
> compilando y con la suite completa en verde.
>
> **Regla para el ejecutor:** si algo de tu tarea es ambiguo, contradice a otra
> tarea, o el repo no coincide con lo que la tarea describe — **detente y
> consulta al arquitecto**. No infieras, no inventes, no "arregles" de paso
> nada que la tarea no te pida. Esto es producción con usuarios reales, y este
> plan toca el núcleo del pipeline comercial.

---

## Fase de investigación (leer antes de la Task 1)

### Documentos que gobiernan este plan

| Documento | Qué manda |
|---|---|
| `docs/planes/plan-05-mapa-migrar-items.md` | **Léelo entero primero.** Hallazgos J1-J13, decisiones D12-D21. Este plan de tareas no repite el razonamiento, solo la ejecución |
| `docs/planes/plan-03-mapa-oportunidad-items.md` | Decisiones D6-D11 del rediseño completo |
| `docs/reglas_negocio.md` §4, §7, §8, §12, §13 | Reglas de negocio de oportunidades que **no cambian**: motivo de cierre, retroceso, snapshot de vendedor, cambio de modelo |
| `CLAUDE.md` | Reglas 1, 2, 8, 9, 10, 11, 12, 14 — todas aplican en este plan |
| `docs/contrato_api.md` §10 | Forma actual del contrato, la que se reescribe |

### Reglas de `CLAUDE.md` que tocan este plan, todas

| Regla | Cómo aplica |
|---|---|
| **1. TDD siempre** | Cada tarea que introduce lógica nueva escribe el test primero. Las tareas que solo migran tests existentes a la nueva forma del contrato no son "TDD nuevo" — son actualización de fixtures, y se marcan así explícitamente |
| **2. `monto_total` se calcula, nunca se acepta** | Se extiende: ahora tampoco se acepta `cantidad`/`precio_venta`/`descuento`/`id_modelo` en `PUT /oportunidades` (D19) — viven solo en el sub-recurso de ítems |
| **8. Inyección por constructor** | `private val` en todo lo nuevo |
| **9. JPA `LAZY`; nunca exponer entidades** | Los DTOs de ítem ya existen (Plan A); no exponer `OportunidadItem` fuera del módulo |
| **10. `@Transactional`** | Cada operación de escritura de ítem, con su sincronización de columnas viejas (D21), es **una sola transacción** |
| **11. Queries parametrizadas** | Sin SQL por concatenación en la subconsulta de sort (D13) |
| **12. Frontera entre módulos** | `OportunidadItemService`/`Impl` viven en `domain/oportunidades/`, el mismo módulo — **no** cruzan frontera, así que pueden depender de `OportunidadRepository`/`OportunidadServiceImpl` directamente sin pasar por la interfaz pública. Verificado: ArchUnit solo restringe dependencias **entre módulos distintos** (`domain.oportunidades` vs `domain.solicitudes`, etc.), no dentro del mismo |
| **14. IDOR → 404** | `OportunidadItemService.vinculoVisible` (D14) sigue el mismo criterio que `OportunidadService.vinculoVisible`: ajeno → 404, nunca 403 |

### Alcance — lista cerrada de archivos

**Nuevos:**
```
src/main/resources/db/migration/V44__solicitudes_entidad_item.sql
src/main/kotlin/pe/quantum/crm/domain/oportunidades/OportunidadItemService.kt
src/main/kotlin/pe/quantum/crm/domain/oportunidades/OportunidadItemServiceImpl.kt
src/main/kotlin/pe/quantum/crm/domain/oportunidades/OportunidadItemController.kt
src/main/kotlin/pe/quantum/crm/domain/oportunidades/dto/OportunidadItemVinculo.kt
src/test/kotlin/pe/quantum/crm/domain/oportunidades/OportunidadItemServiceImplTest.kt
src/test/kotlin/pe/quantum/crm/domain/oportunidades/OportunidadItemControllerWebMvcTest.kt
src/test/kotlin/pe/quantum/crm/domain/oportunidades/MontoTotalSumarItemsTest.kt
```

**Modificados:**
```
src/main/kotlin/pe/quantum/crm/domain/oportunidades/OportunidadServiceImpl.kt
src/main/kotlin/pe/quantum/crm/domain/oportunidades/OportunidadService.kt
src/main/kotlin/pe/quantum/crm/domain/oportunidades/MontoTotal.kt
src/main/kotlin/pe/quantum/crm/domain/oportunidades/dto/OportunidadDtos.kt
src/main/kotlin/pe/quantum/crm/domain/oportunidades/OportunidadesDeContacto.kt
src/main/kotlin/pe/quantum/crm/domain/oportunidades/dto/OportunidadResumenParaContacto.kt
src/main/kotlin/pe/quantum/crm/shared/enums/SolicitudEnums.kt
src/main/kotlin/pe/quantum/crm/shared/exception/NegocioExceptions.kt          (posible: retirar MontoNoEditableException)
src/main/kotlin/pe/quantum/crm/domain/solicitudes/SolicitudServiceImpl.kt
src/main/kotlin/pe/quantum/crm/domain/solicitudes/dto/SolicitudDtos.kt        (si expone entidadTipo como enum)
src/test/kotlin/pe/quantum/crm/domain/oportunidades/*.kt                     (13 archivos, ver tarea B9)
src/test/kotlin/pe/quantum/crm/domain/solicitudes/*.kt                       (los que tocan descuento)
docs/contrato_api.md, docs/matriz_permisos.md
```

**Fuera de alcance explícito:** `domain/reportes/`, `domain/inicio/` (Plan C).
Ningún `DROP COLUMN` sobre `oportunidades` (Plan C). No se añade el endpoint
para crear una oportunidad con **más de un** ítem de entrada — `crear()` sigue
creando exactamente uno (D16); sí se puede **añadir** un segundo ítem después,
vía el sub-recurso (D17).

Cualquier archivo fuera de esta lista: **detente y consulta**.

---

## Tabla de tareas

| ID | Tarea | Modelo | Esfuerzo |
|---|---|---|---|
| B1 | Migración V44: enum de solicitudes | Sonnet 5 | Low |
| B2 | `MontoTotal.sumarItems` + tests | Sonnet 5 | Medium |
| B3 | `OportunidadItemService`/`Impl`: CRUD + sincronización de columnas viejas | Opus 5 | Extra High |
| B4 | `OportunidadItemController` + tests HTTP | Sonnet 5 | Medium |
| B5 | Reescritura de `OportunidadDtos.kt` (D19) | Opus 5 | High |
| B6 | `OportunidadServiceImpl.crear()`: crea vía ítem | Opus 5 | High |
| B7 | `OportunidadServiceImpl.actualizar()`: ya no toca campos de ítem | Sonnet 5 | Medium |
| B8 | `toDto`/`toDtos`: ensamblar `items`, `montoTotal` derivado | Opus 5 | High |
| B9 | Migrar los 13 tests existentes a la nueva forma del contrato | Opus 5 | Extra High |
| B10 | Sort por agregado (`cantidad`, `montoTotal`) vía subconsulta | Opus 5 | High |
| B11 | Nombre de carpeta Drive sin código de modelo (ítems nuevos) | Sonnet 5 | Medium |
| B12 | `aplicarDescuentoAprobado` pasa a nivel de ítem | Opus 5 | High |
| B13 | `SolicitudServiceImpl`: descuento referencia al ítem (`entidad_tipo`) | Opus 5 | High |
| B14 | `OportunidadesDeContacto` + `OportunidadResumenParaContacto` | Sonnet 5 | Medium |
| B15 | Verificación de build completa | Sonnet 5 | Low |
| B16 | Documentación de contrato y permisos | Sonnet 5 | Medium |
| B17 | Auditoría final del diff contra los documentos citados | Opus 5 | High |

---

## B1 · Migración V44: enum de solicitudes

**Modelo:** Sonnet 5 · **Esfuerzo:** Low

**Archivo único a crear:** `src/main/resources/db/migration/V44__solicitudes_entidad_item.sql`

```sql
-- =============================================================================
-- V44 — `entidad_solicitud_enum` gana el valor `oportunidad_item`, para que
-- una solicitud de descuento (TipoSolicitud.descuento) referencie el ítem
-- concreto en vez de la oportunidad completa: con varios ítems por
-- oportunidad, "el descuento de la oportunidad" ya no es una sola cosa
-- (docs/planes/plan-05-mapa-migrar-items.md, decision D12).
--
-- Sin backfill: no hay solicitudes de descuento pendientes en produccion.
-- ALTER TYPE ... ADD VALUE no puede combinarse en la misma transaccion que un
-- INSERT/UPDATE que ya use el enum, por eso esta migracion no hace nada mas.
-- =============================================================================

ALTER TYPE entidad_solicitud_enum ADD VALUE 'oportunidad_item';
```

**Restricciones**
- Solo este archivo. No apliques nada a producción (eso es una tarea posterior
  fuera de este plan de tareas — se coordina aparte con el arquitecto).
- No toques `EntidadSolicitud.kt` en esta tarea (es la B13).

**Criterio de aceptación:** el archivo existe, sintaxis correcta.

---

## B2 · `MontoTotal.sumarItems` + tests

**Modelo:** Sonnet 5 · **Esfuerzo:** Medium

**TDD: escribe el test primero.**

Abre `src/main/kotlin/pe/quantum/crm/domain/oportunidades/MontoTotal.kt` y
`src/main/kotlin/pe/quantum/crm/domain/oportunidades/OportunidadItem.kt` antes
de escribir nada.

### Test: `src/test/kotlin/pe/quantum/crm/domain/oportunidades/MontoTotalSumarItemsTest.kt`

Casos obligatorios (decisión D15 de `plan-05-mapa-migrar-items.md`):
- Lista vacía → `null`
- Un ítem con `cantidad`/`precioVenta` completos → igual a
  `MontoTotal.calcular(cantidad, precioVenta, descuento)` de ese ítem
- Dos ítems, ambos completos → suma de los dos subtotales
- Un ítem completo + un ítem con `cantidad = null` → el incompleto cuenta como
  `0`, el total es el subtotal del que sí está completo (**no** `null`)
- Todos los ítems incompletos (`cantidad` o `precioVenta` null en todos) →
  `null`
- Ítem con `descuento = null` → tratado como `0` (ya lo hace `calcular`, solo
  confirmar que se propaga)

### Implementación

Añade a `MontoTotal.kt`, sin tocar `calcular()`:

```kotlin
/**
 * Suma los subtotales de una lista de items (plan-05-mapa-migrar-items.md,
 * decision D15). Un item incompleto (cantidad o precioVenta null) aporta 0 a
 * la suma en vez de anular el total: a diferencia de un solo campo de
 * `oportunidades`, un item incompleto no debe tumbar el monto de los demas
 * items que si estan completos. Null solo si NINGUN item tiene datos
 * completos, o si la lista esta vacia.
 */
fun sumarItems(items: List<OportunidadItem>): BigDecimal? {
    val subtotales = items.mapNotNull { calcular(it.cantidad, it.precioVenta, it.descuento) }
    if (subtotales.isEmpty()) return null
    return subtotales.reduce(BigDecimal::add)
}
```

**Restricciones**
- No modifiques `calcular()` existente.
- No toques `OportunidadServiceImpl.kt` en esta tarea.

**Criterio de aceptación:** `./gradlew test --tests '*MontoTotalSumarItems*' ktlintCheck detekt --console=plain -q` en verde.

---

## B3 · `OportunidadItemService`/`Impl`: CRUD + sincronización de columnas viejas

**Modelo:** Opus 5 · **Esfuerzo:** Extra High

La tarea más delicada del plan. Lee `plan-05-mapa-migrar-items.md` decisiones
**D14, D17, D21** antes de escribir una línea — están ahí con el detalle
completo, este enunciado las resume pero no las repite entero.

**TDD: escribe los tests primero.** El fixture del test necesita una
`Oportunidad` y sus `OportunidadItem` reales (o mockeados vía MockK, sigue el
patrón de `OportunidadServiceImplTest.kt` — ábrelo para el estilo de mocks).

### `OportunidadItemService.kt` (interfaz)

```kotlin
interface OportunidadItemService {
    /** IDOR: item de oportunidad ajena → 404, nunca 403 (CLAUDE.md regla 14). */
    fun vinculoVisible(idItem: Long, usuario: UsuarioActual): OportunidadItemVinculo

    fun crear(idOportunidad: Long, request: CrearOportunidadItemRequest, usuario: UsuarioActual): OportunidadItemDto

    fun actualizar(idItem: Long, request: ActualizarOportunidadItemRequest, usuario: UsuarioActual): OportunidadItemDto

    /** 409 ULTIMO_ITEM_NO_ELIMINABLE si es el unico item de su oportunidad. */
    fun eliminar(idItem: Long, usuario: UsuarioActual)

    /** Sin chequeo de visibilidad: lo usa OportunidadServiceImpl para ensamblar toDtos. */
    fun porOportunidades(idsOportunidad: Collection<Long>): Map<Long, List<OportunidadItemDto>>
}
```

### `dto/OportunidadItemVinculo.kt`

```kotlin
/** Datos minimos de un item para chequeos de visibilidad cruzados con otros modulos (D14). */
data class OportunidadItemVinculo(
    val id: Long,
    val idOportunidad: Long,
    val idEmpresa: Long,
    val descuento: BigDecimal?,
)
```

### `OportunidadItemServiceImpl.kt`

Inyecta `OportunidadItemRepository`, `OportunidadRepository`, `ModeloService`,
`OportunidadVisibilidad` (la misma clase que usa `OportunidadServiceImpl` para
IDOR — reutilízala, no la dupliques).

**`vinculoVisible(idItem, usuario)`:**
1. Busca el ítem por id → si no existe, `NoEncontradoException`.
2. Busca la oportunidad dueña (`item.idOportunidad`) → si no existe (no
   debería pasar por la FK, pero defensivo), `NoEncontradoException`.
3. Aplica la misma visibilidad que `OportunidadServiceImpl.visible()` (mira
   cómo lo hace, usa `OportunidadVisibilidad.alcanza`) — ajena → 404.
4. Devuelve `OportunidadItemVinculo`.

**`crear(idOportunidad, request, usuario)`:**
1. `visibilidad.rechazarSiEsApoyo(usuario)` (mismo guard que el resto del módulo).
2. Resuelve la oportunidad con el mismo criterio de visibilidad que
   `OportunidadServiceImpl.visible()` — **no** dupliques la lógica: expórtala
   como función interna reusable o inyecta lo necesario. Si no es visible → 404.
3. Valida el límite de descuento del `request.descuento` contra el rol
   (`PoliticaDescuento`, igual que hoy hace `validarLimiteDescuento` en
   `OportunidadServiceImpl` — mismo mensaje/excepción `AprobacionRequeridaException`).
4. Crea el `OportunidadItem` con los datos del request, `precioVenta` default
   al `precioBase` del modelo si no viene en el request (mismo criterio que
   `crear()` de oportunidad hoy).
5. **Llama a la sincronización de columnas viejas (D21)** sobre la oportunidad
   completa (con el ítem ya guardado).
6. Todo en una `@Transactional`.

**`actualizar(idItem, request, usuario)`:** análogo, pero sobre un ítem
existente, con `?.let` por campo igual que `OportunidadServiceImpl.actualizar()`
hace hoy. Vuelve a validar el límite de descuento si `request.descuento` viene
en el body. Sincroniza columnas viejas al final.

**`eliminar(idItem, usuario)`:**
1. Resuelve visibilidad.
2. Cuenta los ítems de la oportunidad dueña. Si es el único (`count == 1`) →
   `ConflictoException("ULTIMO_ITEM_NO_ELIMINABLE", "La oportunidad debe tener al menos un ítem")`.
3. Borra. Sincroniza columnas viejas (con el ítem ya fuera).

**`porOportunidades(idsOportunidad)`:** `findByIdOportunidadInOrderByIdAsc`
(ya existe desde el Plan A) + mapeo a `OportunidadItemDto` con `modelo` resuelto
por lotes (`modeloService.resumenPorIds`) y `montoItem` calculado con
`MontoTotal.calcular`. Agrupa por `idOportunidad` en un `Map`. Sin chequeo de
visibilidad — lo llama `OportunidadServiceImpl`, que ya filtró qué oportunidades
son visibles antes de pedir sus ítems.

### La función de sincronización (D21)

Puede vivir como método `internal`/paquete-privado en `OportunidadServiceImpl`
(inyectado o expuesto), o como una función de extensión en el propio paquete
`domain.oportunidades` que ambos Services comparten — **tu decisión de
organización, siempre que no cruce el módulo y siga la fórmula exacta de D21**:

```kotlin
cantidad      = Σ item.cantidad
monto_total   = MontoTotal.sumarItems(items)
precio_unitario, dcto = si hay exactamente 1 item, los de ese item; si no, null
id_modelo     = idModelo del item de menor id
```

Coméntala explícitamente como código puente que se retira en el Plan C
(D21 lo pide así, cópialo).

### Tests obligatorios (`OportunidadItemServiceImplTest.kt`)

- `vinculoVisible` de un ítem de oportunidad ajena (vendedor distinto) → 404
- `crear` con descuento sobre el límite del rol → `AprobacionRequeridaException`
- `crear` sincroniza `id_modelo`/`cantidad`/`precio_unitario`/`dcto`/`monto_total`
  correctamente cuando es el único ítem
- `crear` un segundo ítem → `cantidad`/`monto_total` de la oportunidad son la
  suma de ambos; `precio_unitario`/`dcto` quedan `null`; `id_modelo` es el del
  ítem más antiguo
- `eliminar` el único ítem de una oportunidad → `ConflictoException` con código
  `ULTIMO_ITEM_NO_ELIMINABLE`, **la oportunidad sigue con su ítem intacto**
- `eliminar` uno de dos ítems → el restante queda, columnas viejas se
  resincronizan con solo ese ítem

**Restricciones**
- No toques `OportunidadServiceImpl.crear()`/`actualizar()`/`toDto()` en esta
  tarea — eso es B6/B7/B8. Esta tarea solo construye el Service de ítems y su
  función de sincronización, sin cambiar el comportamiento observable de
  `OportunidadServiceImpl` todavía (que sigue escribiendo sus propias columnas
  como hoy, sin usar `OportunidadItemService`).
- No crees el Controller (es B4).

**Criterio de aceptación:** `./gradlew test --tests '*OportunidadItem*' ktlintCheck detekt --console=plain -q` en verde, **y** `./gradlew test --console=plain -q` completo sigue en verde (nada de lo existente se rompió).

---

## B4 · `OportunidadItemController` + tests HTTP

**Modelo:** Sonnet 5 · **Esfuerzo:** Medium

Abre `OportunidadController.kt` (el de oportunidades) como referencia de
estilo — este controller sigue el mismo patrón de sub-recurso que ya usa para
`/oportunidades/:id/contactos`.

```kotlin
@RestController
@RequestMapping("/api/v1/oportunidades/{id}/items")
class OportunidadItemController(
    private val oportunidadItemService: OportunidadItemService,
    private val usuarioProvider: UsuarioActualProvider,
) {
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun crear(@PathVariable id: Long, @Valid @RequestBody request: CrearOportunidadItemRequest): ApiResponse<OportunidadItemDto> =
        ApiResponse.ok(oportunidadItemService.crear(id, request, usuarioProvider.actual()))

    @PutMapping("/{itemId}")
    fun actualizar(@PathVariable id: Long, @PathVariable itemId: Long, @Valid @RequestBody request: ActualizarOportunidadItemRequest): ApiResponse<OportunidadItemDto> =
        ApiResponse.ok(oportunidadItemService.actualizar(itemId, request, usuarioProvider.actual()))

    @DeleteMapping("/{itemId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun eliminar(@PathVariable id: Long, @PathVariable itemId: Long) {
        oportunidadItemService.eliminar(itemId, usuarioProvider.actual())
    }
}
```

`id` (de la oportunidad) no se usa dentro del cuerpo de `actualizar`/`eliminar`
más que para la forma de la URL — el Service ya resuelve todo desde `itemId`.
Si detekt se queja del parámetro no usado, añade `@Suppress("UnusedParameter")`
con un comentario: la URL necesita el id de la oportunidad para ser un
sub-recurso coherente, aunque el Service no lo requiera.

### Tests (`OportunidadItemControllerWebMvcTest.kt`)

Sigue `OportunidadControllerWebMvcTest.kt` como referencia. Casos: `POST` 201,
`PUT` 200, `DELETE` 204, `DELETE` del último ítem → 409 con el código
`ULTIMO_ITEM_NO_ELIMINABLE` en el envelope de error, sin autenticación → 401.

**Restricciones:** no toques `OportunidadController.kt` (el de oportunidades).

**Criterio de aceptación:** `./gradlew test --tests '*OportunidadItemController*' ktlintCheck detekt --console=plain -q` en verde.

---

## B5 · Reescritura de `OportunidadDtos.kt` (D19)

**Modelo:** Opus 5 · **Esfuerzo:** High

Aplica **exactamente** la decisión D19 de `plan-05-mapa-migrar-items.md` —
ábrela y sigue la especificación al detalle, no la reinterpretes.

- `OportunidadDto`: quita `idModelo`, `modelo`, `cantidad`, `precioUnitario`,
  `dcto`. Añade `items: List<OportunidadItemDto>`. `montoTotal` se queda
  (ahora es derivado, pero el DTO no cambia de tipo).
- `CrearOportunidadRequest`: renombra `precioUnitario`→ no existe hoy en el
  request (se inicializa server-side), así que solo renombra `dcto` →
  `descuento`. Revisa: hoy `CrearOportunidadRequest` no tiene
  `precio_unitario` como campo de entrada (se inicializa con el precio base
  del modelo) — confirma esto releyendo el archivo actual antes de tocarlo, y
  si tu lectura difiere de lo que este enunciado asume, **detente y consulta**.
- `ActualizarOportunidadRequest`: quita `idModelo`, `cantidad`, `precioUnitario`,
  `dcto`, `montoTotal` por completo. Se queda con `garantia`, `fincParalelo`,
  `fichaVenta`, `notas`, `fechaCierreEstimado`.
- `ModeloEnOportunidadDto` no se toca (ya la reutiliza `OportunidadItemDto`
  desde el Plan A).

**No toques `OportunidadServiceImpl.kt` en esta tarea** — eso rompe la
compilación temporalmente, es esperado y lo resuelven B6-B8 a continuación.
**No corras `./gradlew test` como criterio de aceptación de esta tarea
individual** (fallará por diseño, el resto del módulo aún no está actualizado):
el criterio aquí es que el archivo compile de forma aislada
(`./gradlew compileKotlin` fallará también, por las mismas razones — repórtalo
como esperado, no como error tuyo).

**Criterio de aceptación real:** revisa a ojo que el archivo `OportunidadDtos.kt`
compilaría de forma aislada si el resto del módulo ya usara la nueva forma
(no hay compilador que lo verifique sin B6-B8, así que es una revisión
manual cuidadosa). Reporta el contenido completo del archivo modificado para
que el arquitecto lo revise antes de que las tareas siguientes construyan
sobre él.

---

## B6 · `OportunidadServiceImpl.crear()`: crea vía ítem

**Modelo:** Opus 5 · **Esfuerzo:** High

Ahora que `OportunidadDtos.kt` (B5) y `OportunidadItemService` (B3) existen,
reescribe `crear()`.

Sigue exactamente los 8 pasos que ya existen en `reglas_negocio.md §4.2` —
**no cambia el orden ni se salta ninguno**. Solo cambia el paso donde hoy se
escriben `cantidad`/`precioUnitario`/`dcto`/`montoTotal`/`idModelo` en la
propia `Oportunidad`: en vez de eso, tras guardar la `Oportunidad` (sin esos 5
campos NI el `idModelo` en el constructor — pero `idModelo` es `NOT NULL` en
la entidad `Oportunidad.kt`, así que **no lo quites de la entidad ni de la
tabla**, esa es cosa del Plan C; simplemente escríbelo con el mismo valor que
tendrá el ítem, calculado antes de construir la `Oportunidad`), se crea **un**
`OportunidadItem` con los datos del request (vía el mismo mecanismo interno
que usa `OportunidadItemServiceImpl.crear()` — puedes llamarlo directamente o
extraer la lógica compartida a una función privada; no dupliques la validación
del límite de descuento, que ya se hizo en `validarLimiteDescuento` antes en
`crear()`, así que no la repitas al crear el ítem interno).

`toDto`/`toDtos` (B8) todavía no existen actualizados — para que esta tarea
sea verificable de forma aislada, puedes dejar `toDto`/`toDtos` sin tocar por
ahora (compilarán en rojo contra `OportunidadDtos.kt` de B5, esperado) y
reportarlo así. **Si prefieres, y te parece más seguro, combina B6+B7+B8 en tu
propia ejecución de esta tarea** (compilar solo al final de las tres) — avísalo
en tu respuesta si lo haces así, no es una desviación, es la forma más segura
de no dejar el árbol en un estado que no compila entre tareas.

**Restricciones**
- El precio, cantidad, descuento del request se validan y aplican **una sola
  vez**: o los aplica `crear()` de oportunidad directamente construyendo el
  `OportunidadItem`, o delega en `OportunidadItemServiceImpl` — decide y
  documenta cuál, pero no dupliques la creación del ítem.
- La carpeta de Drive sigue creándose en la misma transacción (B11 solo cambia
  el nombre, no el momento).

**Criterio de aceptación:** si hiciste B6 aislada, reporta que compila en rojo
contra el resto (esperado) y qué falta. Si combinaste B6-B8, el criterio es el
de B8 más abajo.

---

## B7 · `OportunidadServiceImpl.actualizar()`: ya no toca campos de ítem

**Modelo:** Sonnet 5 · **Esfuerzo:** Medium

Quita de `actualizar()`: el bloque de cambio de modelo (líneas 188-203 del
archivo original, el `if (nuevoModeloId != null...)`), las líneas
`request.cantidad?.let`, `request.precioUnitario?.let`, `request.dcto?.let`, y
el recálculo de `montoTotal` al final. **Quita también** el guard
`if (request.montoTotal != null) throw MontoNoEditableException()` — ya no
hace falta, el campo ni existe en `ActualizarOportunidadRequest` tras B5
(Jackson lo ignora solo por `@JsonIgnoreProperties(ignoreUnknown = true)`, que
ya tiene el DTO — confírmalo).

Verifica si `MontoNoEditableException` queda sin ningún uso en todo el repo
tras este cambio (`grep -rn "MontoNoEditableException" src/main`); si es así,
bórrala de `NegocioExceptions.kt` y su import. Si algo más la usa, repórtalo y
no la borres.

`actualizar()` se queda con: `garantia`, `fincParalelo`, `fichaVenta`, `notas`,
`fechaCierreEstimado`. La llamada a `validarLimiteDescuento(request.dcto, usuario)`
al principio de la función también se quita — `request` ya no tiene `dcto`.

**Restricciones:** no toques `crear()` (B6) ni `toDto`/`toDtos` (B8) salvo que
ya lo hayas combinado según lo permitido en B6.

**Criterio de aceptación:** igual que B6 — puede quedar compilando en rojo si
`toDto`/`toDtos` (B8) no está hecho todavía; repórtalo así.

---

## B8 · `toDto`/`toDtos`: ensamblar `items`, `montoTotal` derivado

**Modelo:** Opus 5 · **Esfuerzo:** High

Esta es la tarea que **cierra** el ciclo B5-B8: al terminar, el módulo debe
compilar y la suite (ajustada en B9, siguiente) debe volver a estar en verde.

Reescribe `toDtos()` (el método por lotes, líneas 726-771 del archivo
original):
- Quita la carga de `modelos` por `modeloService.resumenPorIds` a nivel de
  oportunidad (eso ahora lo resuelve `OportunidadItemService.porOportunidades`,
  que internamente ya carga los modelos de cada ítem).
- Añade: `val items = oportunidadItemService.porOportunidades(ids)`.
- Construye `OportunidadDto` con `items = items[opId].orEmpty()` y
  `montoTotal = MontoTotal.sumarItems(...)` — pero ojo: `porOportunidades`
  devuelve `List<OportunidadItemDto>` (con montos como `String`), no
  `List<OportunidadItem>` (la entidad, que es lo que pide `sumarItems`). Dos
  opciones válidas: (a) que `porOportunidades` también devuelva las entidades
  crudas en un método separado o sobrecarga para este caso interno, o (b) que
  `sumarItems` calcule sobre los DTOs parseando el `String` de vuelta a
  `BigDecimal`. **Prefiere (a)**: es más limpio y evita parseo de ida y vuelta.
  Decide la forma exacta de la API interna entre ambos Services — es una
  decisión tuya de implementación, no hace falta consultar, mientras el
  resultado sea correcto y no exponga la entidad JPA fuera del módulo (regla 9
  sigue aplicando **dentro** del módulo también, por higiene, aunque ArchUnit
  no lo exija entre clases del mismo paquete).
- `OportunidadServiceImpl` pasa a inyectar `OportunidadItemService` en su
  constructor (`private val`, regla 8).

**No toques `CAMPOS_ORDENABLES`** todavía (es B10).

### Tests nuevos de esta tarea

Ninguno propio — los tests de `crear()`/`actualizar()`/`listar()`/`detalle()`
existentes son los que verifican esto, y se actualizan en B9. Si quieres
verificar tu propio trabajo antes de B9, puedes escribir un test ad-hoc y
descartarlo, pero **no lo dejes** en el repo: sería redundante con lo que B9
va a escribir de forma sistemática.

**Criterio de aceptación:** `./gradlew compileKotlin ktlintCheck detekt --console=plain -q`
en verde (el módulo compila limpio). `./gradlew test` **no** se pide en verde
todavía — los 13 archivos de test viejos van a fallar hasta B9, y eso es
esperado. Reporta cuántos tests fallan y por qué tipo de error (deben ser
todos de compilación/aserción sobre campos que ya no existen, **nunca** un
error de lógica de negocio genuino — si ves algo que huela a bug real,
detente y consulta antes de seguir).

---

## B9 · Migrar los 13 tests existentes a la nueva forma del contrato

**Modelo:** Opus 5 · **Esfuerzo:** Extra High

Ahora el módulo compila (B8) pero la suite de tests está rota porque
referencia campos que ya no existen. Esta tarea la pone verde.

Archivos a revisar (todos en `src/test/kotlin/pe/quantum/crm/domain/oportunidades/`,
según `plan-05-mapa-migrar-items.md` J13 y `plan-03` I8):

```
OportunidadActualizarTest.kt
OportunidadCambiarEstadoInvariantesTest.kt
OportunidadContactosTest.kt
OportunidadControllerWebMvcTest.kt
OportunidadCrearTest.kt
OportunidadDtosEscalaTest.kt
OportunidadesDeContactoImplTest.kt
OportunidadLecturasTest.kt
OportunidadRolApoyoTest.kt
OportunidadServiceImplTest.kt
```
(más `OportunidadListadoSpecificationTest.kt` y `EstadoCarteraServiceTest.kt`
si tras leerlos usan alguno de los campos movidos — verifícalo, no asumas.)

**Confirmado tras B8:** `compileTestKotlin` falla hoy con 54 errores en 11
archivos, dos tipos nada más: (a) construcción de `OportunidadDto`/requests con
campos planos ya inexistentes, y (b) `OportunidadServiceImpl` ahora exige el
parámetro `oportunidadItemService` en su constructor (B6) — cualquier test que
lo instancie a mano necesita el mock nuevo. Incluye también
`src/test/kotlin/pe/quantum/crm/shared/FormatoFechasContratoTest.kt` — **vive
fuera de `domain/oportunidades/`**, es fácil pasarlo por alto, y tiene 6 de los
54 errores.

Y en `domain/reportes/`: **no los toques** — son del Plan C
(`ReporteServiceIntegrationTest.kt`, `ReporteServiceSqlIntegrationTest.kt`).
Si al correr la suite ves que fallan, es esperado (siguen leyendo columnas
que ahora pueden estar en `null` por la sincronización de D21) — repórtalo,
no los arregles.

### Regla de esta tarea, la más importante

**No debilites ninguna aserción para que pase.** Si un test comprobaba que
`monto_total = 713952.00`, el test actualizado debe seguir comprobando ese
mismo valor — solo cambia **dónde** lo lee (antes `dto.montoTotal` con el
mismo nombre, ahora puede seguir siendo `dto.montoTotal` si no cambiaste ese
campo, o `dto.items[0].montoItem` si el test verificaba el subtotal de un
ítem específico). Si un test comprobaba `dto.cantidad`, y ahora `cantidad`
vive en `dto.items[0].cantidad`, actualiza la ruta de acceso, **no borres la
aserción**.

Para los tests que exercitan `MontoNoEditableException` (§B7 puede haberla
eliminado): si la excepción ya no existe, el test entero pierde sentido —
**bórralo**, no lo "arregles" para que compile de otra forma. Repórtalo
explícitamente en tu respuesta final (qué test borraste y por qué).

Para los tests que comprobaban el flujo de "cambio de modelo con precio no
editado manualmente" (`reglas_negocio.md §12.2`, antes en `actualizar()`):
esa lógica se movió a `OportunidadItemServiceImpl.actualizar()` (B3) — si esos
tests siguen en `OportunidadActualizarTest.kt`/`OportunidadServiceImplTest.kt`,
**muévelos** a `OportunidadItemServiceImplTest.kt` en vez de borrarlos, ya que
la B3 (según cómo la haya resuelto quien la ejecutó) puede o no haberlos
cubierto ya — revisa `OportunidadItemServiceImplTest.kt` primero para no
duplicar.

**Restricciones**
- No toques código de `src/main/` en esta tarea. Solo tests.
- Si al reescribir un test descubres una **discrepancia real de
  comportamiento** (no solo de forma del DTO) entre lo que B3/B6/B7/B8
  implementaron y lo que `reglas_negocio.md` exige, **detente y consulta**: no
  es tu trabajo decidir cuál de los dos tiene razón.

**Criterio de aceptación:** `./gradlew test --console=plain -q` (suite
**completa**, excepto `domain/reportes/` que puede seguir roja — repórtalo por
separado si lo está) en verde. `./gradlew ktlintCheck detekt --console=plain -q`
en verde.

---

## B10 · Sort por agregado (`cantidad`, `montoTotal`) vía subconsulta

**Modelo:** Opus 5 · **Esfuerzo:** High

Decisión D13 de `plan-05-mapa-migrar-items.md`. Abre `especificacion()` y
`CAMPOS_ORDENABLES` en `OportunidadServiceImpl.kt`, y `shared/CamposOrdenables.kt`
y `shared/Paginacion.kt` para entender cómo se conecta `sort`/`dir` con la
`Specification<Oportunidad>` actual.

Retira `"precioUnitario"` de `CAMPOS_ORDENABLES` (D9 de `plan-03`, ya no
significa nada). Mantén `"cantidad"` y `"montoTotal"`, pero su `orderBy` deja
de ser `root.get<Int>("cantidad")` (ya no existe esa columna con ese
significado — la columna vieja sigue ahí por D21, pero ordenar por la columna
sincronizada daría resultados incorrectos en cuanto hay 2+ ítems, donde
`precio_unitario`/`dcto` son `null` pero `cantidad`/`monto_total` sí están
sincronizados correctamente por D21 — así que en realidad **puedes ordenar
por las columnas viejas sincronizadas sin subconsulta**, porque D21 ya las
mantiene como la suma exacta).

> **Verifica esto contra D21 antes de escribir la subconsulta**: si `cantidad`
> y `monto_total` en `oportunidades` siempre reflejan la suma de los ítems
> (que es literalmente lo que D21 sincroniza), **ordenar por esas columnas
> sigue siendo correcto sin ninguna subconsulta** — la sincronización de B3 ya
> resolvió este problema de raíz. Si tras revisar D21 y el código de B3
> confirmas que esto es así, esta tarea se reduce a: quitar
> `"precioUnitario"` de `CAMPOS_ORDENABLES`, y **nada más** — repórtalo así,
> no inventes una subconsulta que sería redundante con la sincronización que ya
> existe. Si por el contrario encuentras un caso donde la columna sincronizada
> y el agregado real de los ítems pueden divergir, **detente y consulta**
> antes de decidir tú si hace falta la subconsulta.

**Criterio de aceptación:** `./gradlew test --tests '*OportunidadListado*' --console=plain -q` en verde, con al menos un test que verifique que `sort=cantidad` y `sort=monto_total` siguen ordenando correctamente con 2+ ítems por oportunidad (créalo si no existe, siguiendo TDD).

---

## B11 · Nombre de carpeta Drive sin código de modelo (ítems nuevos)

**Modelo:** Sonnet 5 · **Esfuerzo:** Medium

Decisión D18/D9. Cambia `nombreCarpetaDrive(idOportunidad, codigoModelo)` a
`nombreCarpetaDrive(idOportunidad)` → `"OP-$idOportunidad"`, sin el
`joinToString` con el código de modelo. Actualiza las dos llamadas
(`crear()` y `asegurarCarpetaDriveDe()`), quitando la resolución de
`modeloService.resumen(oportunidad.idModelo).codigo` que ya no hace falta.

**Restricción:** las carpetas ya creadas (`driveFolderId` no nulo) no se tocan
— la función solo se invoca cuando hace falta crear una carpeta nueva, así que
este cambio no requiere ninguna migración de datos ni afecta las 5 carpetas
existentes.

**Criterio de aceptación:** `./gradlew test --tests '*Drive*' --console=plain -q` en verde, con al menos un test que confirme el nuevo formato de nombre.

---

## B12 · `aplicarDescuentoAprobado` pasa a nivel de ítem

**Modelo:** Opus 5 · **Esfuerzo:** High

Cambia la firma en `OportunidadService.kt` (interfaz) y
`OportunidadServiceImpl.kt` (o muévela a `OportunidadItemService`, ver nota):

```kotlin
fun aplicarDescuentoAprobado(idItem: Long, descuento: BigDecimal, idAprobador: Long)
```

> **Decisión de ubicación:** esta función pertenece semánticamente a
> `OportunidadItemService` (opera sobre un ítem), no a `OportunidadService`.
> Muévela ahí si `OportunidadItemService` (B3) ya existe con la forma
> adecuada — es más coherente que dejarla en el Service de oportunidad
> operando por id de ítem. Si mover la interfaz pública rompe algo que no
> esperabas (por ejemplo, algo fuera del módulo depende de la firma vieja de
> forma que no anticipaste), **detente y consulta** antes de decidir tú.

Lógica: igual que hoy (bloquea si `cerrado`/`facturado`), pero sobre el ítem:
actualiza `item.descuento`, recalcula `item.montoItem` (vía `MontoTotal.calcular`,
no se persiste como campo propio — solo `descuento` se persiste, el subtotal
se deriva al leer, igual que hoy), y **sincroniza las columnas viejas de la
oportunidad dueña** (D21) — esta función es una escritura de ítem como
cualquier otra, la sincronización aplica igual.

**Restricciones:** no toques `SolicitudServiceImpl.kt` todavía (es B13) —
puedes dejar esa llamada temporalmente rota (no compilará `solicitudes` contra
la nueva firma), repórtalo como esperado.

**Criterio de aceptación:** `./gradlew test --tests '*OportunidadItem*' --console=plain -q` en verde. `domain/solicitudes` puede no compilar todavía — repórtalo explícitamente.

---

## B13 · `SolicitudServiceImpl`: descuento referencia al ítem

**Modelo:** Opus 5 · **Esfuerzo:** High

Cierra el ciclo cross-módulo abierto en B12. Decisión D12.

1. **`shared/enums/SolicitudEnums.kt`**: añade `oportunidad_item` a
   `EntidadSolicitud`, en el mismo estilo que los otros dos valores.
2. **`SolicitudServiceImpl.validarDescuento()`**: cambia
   `if (request.entidadTipo != EntidadSolicitud.oportunidad)` a
   `EntidadSolicitud.oportunidad_item`. Cambia
   `oportunidadService.vinculoVisible(entidadId, usuario)` a
   `oportunidadItemService.vinculoVisible(entidadId, usuario)` (D14, ya
   construido en B3) — inyecta `OportunidadItemService` en el constructor de
   `SolicitudServiceImpl` (regla 8). La descripción de la solicitud
   (`entidadDescripcion`) sigue componiéndose con el nombre de la empresa
   (ahora sale de `OportunidadItemVinculo.idEmpresa` en vez de
   `oportunidad.idEmpresa`) — mantén el mismo formato de mensaje
   (`"{empresa} — Oportunidad #{id}"`), ajustando si hace falta indicar
   también el ítem (a tu criterio, mientras sea informativo — no es una regla
   de negocio estricta).
3. **`resolverDescuentoAprobado()`** (o donde esté la llamada de aprobación,
   línea ~279-284 del archivo original): cambia
   `oportunidadService.aplicarDescuentoAprobado(solicitud.entidadId, dcto, idAprobador)`
   a `oportunidadItemService.aplicarDescuentoAprobado(solicitud.entidadId, dcto, idAprobador)`
   (o el nombre que B12 le haya dado si la moviste de Service).

**Restricciones:** no cambies `validarReasignacion()` ni nada del flujo de
`reasignacion_cliente` — ese sigue apuntando a `empresa` sin cambios.

**Criterio de aceptación:** `./gradlew test --tests '*Solicitud*' --console=plain -q` en verde. Actualiza los tests de `domain/solicitudes/` que referencien `EntidadSolicitud.oportunidad` para un caso de descuento — deben pasar a `oportunidad_item`, con el id de un ítem real en el fixture, no el id de la oportunidad.

---

## B14 · `OportunidadesDeContacto` + `OportunidadResumenParaContacto`

**Modelo:** Sonnet 5 · **Esfuerzo:** Medium

Hallazgo J10 de `plan-05-mapa-migrar-items.md` — no estaba en el mapa original
del Plan A/B, lo encontró la investigación de código de este plan.

Abre `OportunidadesDeContacto.kt` líneas 84-95 y
`dto/OportunidadResumenParaContacto.kt`. Este resumen usa `op.idModelo` y
`op.montoTotal` de la entidad `Oportunidad` — `montoTotal` sigue existiendo
(columna sincronizada por D21, o puedes preferir inyectar
`OportunidadItemService`/`MontoTotal.sumarItems` para el valor "de verdad" en
vez de depender de la columna puente; **prefiere esto último**, es más
correcto y no depende de que D21 se mantenga funcionando). `idModelo` de la
entidad puede quedarse (es la columna sincronizada, D21), o resolverse igual
que en `toDtos()` de `OportunidadServiceImpl` a través de los ítems si
`OportunidadResumenParaContacto` necesita mostrar más de un modelo — **decide
mirando qué expone hoy el DTO y si el consumidor (frontend de contactos)
necesitaría ver varios modelos aquí; si no tienes forma de saberlo, usa el
criterio más simple (un solo `modelo` resuelto del ítem principal/más antiguo,
igual que D21) y repórtalo para que el arquitecto lo revise**.

**Criterio de aceptación:** `./gradlew test --tests '*OportunidadesDeContacto*' --console=plain -q` en verde.

---

## B15 · Verificación de build completa

**Modelo:** Sonnet 5 · **Esfuerzo:** Low

Igual patrón que en los planes anteriores de esta sesión:

```bash
./gradlew ktlintCheck --console=plain -q
./gradlew detekt --console=plain -q
./gradlew test --console=plain -q
```

**No ejecutes `koverVerify`** (bloqueo conocido de Docker 29/Testcontainers).

Nota de infraestructura ya conocida en esta sesión: si Gradle falla con
`CorruptedException`, "Could not delete" o "Failed to clean up output files"
(locks de Windows), `./gradlew --stop` y reintenta; si persiste, `--no-daemon`;
si aun así falla, puede haber procesos `java.exe` colgados — repórtalo si no
puedes resolverlo tú mismo con `--stop`.

**Excepción esperada:** `domain/reportes/` puede seguir en rojo o con
resultados semánticamente incorrectos (lee columnas que D21 sincroniza de
forma imperfecta cuando hay 2+ ítems) — es responsabilidad del Plan C, no de
este. Repórtalo si lo ves, no lo arregles.

**Criterio de aceptación:** todo en verde salvo la excepción anotada arriba,
reportada con detalle si aplica.

---

## B16 · Documentación de contrato y permisos

**Modelo:** Sonnet 5 · **Esfuerzo:** Medium

`CLAUDE.md`: *"Todo cambio a un endpoint documentado se registra en
`contrato_api.md §26` Changelog del contrato, en el mismo PR."* (el número de
sección puede haber cambiado tras el Plan 2 — verifica el índice actual antes
de escribir).

1. **`docs/contrato_api.md` §10 Oportunidades**: reescribe `GET`/`POST`/`PUT`
   con la nueva forma (D19): `items` en la respuesta, sin campos planos de
   ítem, `POST` con `precio_venta`/`descuento` en vez de `dcto`. Documenta los
   tres endpoints nuevos: `POST/PUT/DELETE /oportunidades/:id/items`, con su
   forma de request/response y el error `409 ULTIMO_ITEM_NO_ELIMINABLE`.
2. **Changelog**: una entrada **Breaking** — lista todos los endpoints
   afectados, explica el cambio de forma, y en "Acción para frontend" sé
   explícito sobre qué campos migran a dónde.
3. **`docs/matriz_permisos.md`**: si el sub-recurso de ítems tiene el mismo
   reparto de permisos que editar la oportunidad (probablemente sí — mismos
   roles que hoy editan `cantidad`/`precio`/`dcto`), dilo explícitamente en
   vez de dejarlo implícito.
4. **`docs/reglas_negocio.md` §7 y §12**: estas reglas describían
   `monto_total`/`precio_unitario` como columnas de `oportunidades`. Actualiza
   para reflejar que ahora viven en `oportunidad_items`, sin perder el
   contenido de negocio (la fórmula no cambia, solo dónde vive el dato).

**Criterio de aceptación:** las ediciones hechas, coherentes con el código real de B1-B14 (relee el código antes de documentar, no documentes lo que el plan pedía si el código terminó siendo distinto).

---

## B17 · Auditoría final del diff contra los documentos citados

**Modelo:** Opus 5 · **Esfuerzo:** High

Tarea exigida por `CLAUDE.md`. **Auditoría del diff completo, no resumen.**

Contrasta contra: `plan-05-mapa-migrar-items.md` completo (J1-J13, D12-D21) ·
`plan-03-mapa-oportunidad-items.md` (D6-D11) · `reglas_negocio.md` §4, §7, §8,
§12, §13 · `CLAUDE.md` reglas 1, 2, 8, 9, 10, 11, 12, 14.

Busca en concreto:

1. **Contradicciones con documentación ya vigente y correcta.**
2. **Fugas de alcance**: `domain/reportes/`, `domain/inicio/` no deben tener
   ni una línea tocada (son del Plan C). Ninguna columna de `oportunidades` se
   eliminó (`DROP COLUMN`) — siguen ahí, solo que D21 las mantiene
   sincronizadas como puente.
3. **Que D21 esté realmente implementada como código puente**, con el
   comentario explícito de que se retira en el Plan C — no como si fuera
   arquitectura final.
4. **Que ningún test se haya debilitado** para pasar (B9 lo prohibía
   explícitamente) — compara al menos 3 tests migrados contra su forma
   original (usa `git diff` si están trackeados, o compara con lo que
   describe este plan) y confirma que verifican el mismo hecho de negocio,
   no una versión relajada.
5. **IDOR (regla 14)**: `OportunidadItemService.vinculoVisible` devuelve 404
   para un ítem ajeno, nunca 403. Verifica el test que lo prueba (B3).
6. **`MontoNoEditableException`**: o se eliminó (si quedó sin uso) o sigue
   existiendo con un uso real — no debe quedar código muerto sin explicación.
7. **Que `EntidadSolicitud.oportunidad_item` se use consistentemente**: la
   migración V44 la crea, `SolicitudEnums.kt` la declara, `SolicitudServiceImpl`
   la usa para descuento. Los tres deben estar alineados.
8. **Que no se añadiera ninguna dependencia** a `build.gradle.kts`.
9. **Consistencia de nombres**: `precio_venta`/`descuento` en todo lo nuevo,
   nunca `precio_unitario`/`dcto` salvo en las columnas viejas de
   `oportunidades` (que siguen existiendo por D21, con esos nombres
   originales — eso sí es correcto, son las columnas viejas).

**Entregable:** informe con hallazgos en *bloqueante / menor / ninguno*,
archivo, línea y regla o sección concreta. **No arregles nada. No hagas
commit.**

---

## Cierre del plan

Al terminar, **para y resume** qué se hizo.

Estado esperado al cerrar: la API de oportunidades expone `items`, el CRUD de
ítems funciona con su propio guard de IDOR y su propio límite de descuento,
`solicitudes` aprueba descuentos por ítem, y los reportes siguen mostrando
números correctos gracias a la sincronización puente (D21) — que queda
marcada en el código para que el Plan C la retire. La capacidad de que una
oportunidad tenga **más de un** modelo ya existe (se puede añadir un segundo
ítem vía `POST /oportunidades/:id/items`), aunque `crear()` siga arrancando
con exactamente uno (D16).
