# Plan D — Fundación del módulo `simulaciones` + CRUD

> **Destinatario: agentes ejecutores, no humanos.** Cada tarea es autocontenida.
> Ejecutar en orden estricto: cada una asume que la anterior está cerrada,
> compilando y con `./gradlew test` en verde.
>
> **Regla para el ejecutor:** si algo de tu tarea es ambiguo, contradice a otra
> tarea, o el repo no coincide con lo que la tarea describe — **detente y
> consulta al arquitecto**. No infieras, no inventes, no "arregles" de paso nada
> que la tarea no te pida. Esto es producción con usuarios reales.
>
> **Sobre los tests `@Tag("integration")`:** Docker Desktop 29 rompe el cliente
> de Testcontainers en local. **Ninguna tarea de este plan puede confirmarlos en
> verde**; solo lo hace CI. La tarea que toque uno debe decir explícitamente
> *"no ejecutable en local, verificado por lectura cuidadosa"* en vez de
> reportar un falso verde. Esto ya costó dos rondas de CI en el Plan B.
>
> **Nota de infraestructura (vale para todas las tareas):** si Gradle falla con
> `CorruptedException`, "Could not delete" o "Failed to clean up output files"
> (locks de Windows), mata los procesos `java.exe` colgados, ejecuta
> `./gradlew --stop`, borra `build/` y reintenta con `--no-daemon`.
> **Nunca confíes en el exit code de un pipeline con `| tail`**: redirige a un
> archivo y comprueba `$?` en un comando separado.

---

## Fase de investigación (leer antes de la Task D1)

### Documentos que gobiernan este plan

| Documento | Qué manda |
|---|---|
| `docs/planes/plan-09-mapa-simulaciones-modulo.md` | **Léelo entero primero.** Hallazgos K10-K21, decisiones D30-D42 |
| `docs/reglas_simulaciones.md` | Fuente de verdad del comportamiento. §1, §2, §3, §4, §5, §6.1, §6.3, §8, §10, §13 |
| `src/main/resources/db/migration/V43__create_simulaciones.sql` | El schema real, ya aplicado en producción. Sus CHECK e índices son contrato |
| `Instrucciones_simulaciones.md` | El encargo y sus restricciones no negociables |
| `docs/contrato_api.md` | Estilo de endpoints (§21 metas de venta y §22 tipo de cambio son las referencias más recientes) |
| `CLAUDE.md` | Reglas 1, 8, 9, 10, 11, 12, 14 |

### Las cuatro trampas de este plan

Están explicadas a fondo en el mapa; se repiten aquí porque son las que más
fácilmente se rompen por inercia:

1. **La visibilidad de `simulaciones` NO es la de `oportunidades`** (K12/D30).
   `analista` tiene acceso **total** al módulo aunque sea rol de apoyo en
   oportunidades; `jdv` **no tiene acceso** aunque sea supervisor en
   oportunidades. **No uses `UsuarioActual.esRolApoyo`, `esSupervisor` ni
   `visibilidadRestringida` en este módulo.**
2. **`es_principal` tiene un índice único parcial** (K14/D38): desmarcar la
   anterior **antes** de insertar la nueva, en la misma transacción.
3. **`simulacion_log` tiene un CHECK por tipo de evento** (K15): los eventos con
   snapshot lo llevan completo o el INSERT revienta con 500.
4. **`cuota_final` jamás se acepta del cliente** (restricción 2 del encargo):
   no existe en ningún request DTO.

### Alcance — lista cerrada de archivos

Ver `plan-09-mapa-simulaciones-modulo.md` §4 completa. **Cualquier archivo fuera
de esa lista: detente y consulta.**

En particular **no se toca** `src/test/kotlin/pe/quantum/crm/db/SchemaMigrationIntegrationTest.kt`:
Plan D no añade ninguna migración, así que las cuentas de tablas/enums/migraciones
no cambian. Si crees que hay que tocarlo, se coló una migración donde no debía.

---

## Tabla de tareas

| ID | Tarea | Modelo | Esfuerzo |
|---|---|---|---|
| D1 | Enum `TipoEventoSimulacion` | Sonnet 5 | Low |
| D2 | Entidades `Simulacion` y `SimulacionLog` | Sonnet 5 | Medium |
| D3 | Repositorios + test de repositorio | Sonnet 5 | Medium |
| D4 | Ampliar `OportunidadItemService` con `datosParaSimulacion` (D32) | Opus 5 | High |
| D5 | `SimulacionPermisos`: punto único de decisión (D30/D31) | Opus 5 | High |
| D6 | DTOs del módulo | Sonnet 5 | Medium |
| D7 | `NombreSimulacion`: autogeneración §8.1 (D37) | Sonnet 5 | Medium |
| D8 | Interfaz `SimulacionService` | Sonnet 5 | Low |
| D9 | `SimulacionServiceImpl.crear` + evento `creada` | Opus 5 | Extra High |
| D10 | `SimulacionServiceImpl`: `detalle` y `listar` | Sonnet 5 | High |
| D11 | `SimulacionServiceImpl.actualizar` + evento `editada` | Opus 5 | High |
| D12 | `SimulacionServiceImpl.eliminar` + evento `eliminada` | Sonnet 5 | Medium |
| D13 | `SimulacionServiceImpl.cronograma` (D40) | Sonnet 5 | Medium |
| D14 | `SimulacionController` + test WebMvc | Sonnet 5 | High |
| D15 | Verificación de build completa (local, sin `integrationTest`) | Sonnet 5 | Low |
| D16 | Auditoría final del diff contra los documentos citados | Opus 5 | High |

**Fuera de este plan** (Planes E y F, coordinados aparte): historial/diff,
restaurar, bifurcar, marcar principal explícito, Calculadora Financiera,
integración §6.2 con oportunidades, jobs de purga y aviso, migración de enums de
notificaciones, documentación de `contrato_api.md`/`matriz_permisos.md`/`CLAUDE.md`.

---

## D1 · Enum `TipoEventoSimulacion`

**Modelo:** Sonnet 5 · **Esfuerzo:** Low

Crea `src/main/kotlin/pe/quantum/crm/shared/enums/TipoEventoSimulacion.kt`.

Abre antes `src/main/kotlin/pe/quantum/crm/shared/enums/ModoSimulacion.kt` y
`src/main/kotlin/pe/quantum/crm/shared/enums/EstadoMeta.kt`: copia su estilo
exacto (KDoc, anotación `@Suppress`, minúsculas).

Los seis valores deben coincidir **exactamente** con
`tipo_evento_simulacion_enum` de `V43__create_simulaciones.sql` líneas 23-30
(ábrela y cópialos de ahí, no de memoria):

```kotlin
@Suppress("ktlint:standard:enum-entry-name-case", "EnumNaming", "EnumEntryName")
enum class TipoEventoSimulacion {
    creada,
    editada,
    restaurada,
    marcada_principal,
    enlazada_a_item,
    eliminada,
}
```

El KDoc debe decir: valores de `tipo_evento_simulacion_enum` (migración V43); en
minúscula a propósito para coincidir con las etiquetas del enum nativo de
PostgreSQL, que Hibernate mapea por nombre vía `@JdbcTypeCode(NAMED_ENUM)`; y que
`simulacion_log` es solo INSERT y sin purga (`reglas_simulaciones.md` §7).

**Un enum por archivo, con el nombre del archivo igual al de la clase** — es la
regla ktlint `standard:filename` y ya nos mordió en la tarea T1 del Plan 1.

**Criterio de aceptación:**
```bash
./gradlew compileKotlin ktlintCheck detekt --console=plain -q --no-daemon > /tmp/d1.log 2>&1; echo "EXIT:$?"
```
en EXIT:0. Reporta el contenido del archivo y `git status --short`.

---

## D2 · Entidades `Simulacion` y `SimulacionLog`

**Modelo:** Sonnet 5 · **Esfuerzo:** Medium

Abre **enteros** antes de escribir nada:
- `src/main/resources/db/migration/V43__create_simulaciones.sql` (las columnas exactas)
- `src/main/kotlin/pe/quantum/crm/domain/oportunidades/OportunidadItem.kt` (referencia de estilo: columnas `Long` simples, no `@ManyToOne`)
- `src/main/kotlin/pe/quantum/crm/domain/metasventa/MetaVenta.kt` (referencia de estilo: `@JdbcTypeCode(NAMED_ENUM)` sobre un enum)

### `domain/simulaciones/Simulacion.kt`

Mapea la tabla `simulaciones`. Columnas **en el mismo orden que la migración**,
con los tipos Kotlin que corresponden:

| Columna SQL | Propiedad Kotlin | Tipo | Notas |
|---|---|---|---|
| `id` | `id` | `Long?` | `@Id @GeneratedValue(IDENTITY)`, `val`, default `null` |
| `modo` | `modo` | `ModoSimulacion` | `@JdbcTypeCode(SqlTypes.NAMED_ENUM)` + `@Column(nullable = false, columnDefinition = "modo_simulacion_enum")`. `val`, **no `var`**: es inmutable (§2) |
| `nombre` | `nombre` | `String?` | `var`, default `null` |
| `id_oportunidad_item` | `idOportunidadItem` | `Long?` | `var`, default `null` |
| `id_modelo` | `idModelo` | `Long?` | `var`, default `null` |
| `id_simulacion_origen` | `idSimulacionOrigen` | `Long?` | `val`, default `null` |
| `precio_venta` | `precioVenta` | `BigDecimal` | `var` |
| `descuento` | `descuento` | `BigDecimal` | `var`, default `BigDecimal.ZERO` |
| `cuota_inicial` | `cuotaInicial` | `BigDecimal` | `var` |
| `plazo_meses` | `plazoMeses` | `Int` | `var` |
| `tea` | `tea` | `BigDecimal` | `var` |
| `valor_residual` | `valorResidual` | `BigDecimal` | `var`, default `BigDecimal.ZERO` |
| `dias_trabajados` | `diasTrabajados` | `Int` | `var`, default `22` |
| `comision_estructuracion` | `comisionEstructuracion` | `BigDecimal` | `var`, default `BigDecimal("1180")` |
| `cuota_final` | `cuotaFinal` | `BigDecimal` | `var` |
| `es_principal` | `esPrincipal` | `Boolean` | `var`, default `false` |
| `created_at` | `createdAt` | `LocalDateTime` | `val`, default `LocalDateTime.now()` |
| `created_by` | `createdBy` | `Long` | `val` |
| `updated_at` | `updatedAt` | `LocalDateTime` | `var`, default `LocalDateTime.now()` |
| `updated_by` | `updatedBy` | `Long` | `var` |

`modo` es `val` **a propósito**: la inmutabilidad de §2 empieza por el tipo. Si
la entidad no deja mutarlo, un bug de servicio no puede llegar al trigger.

KDoc de la clase: qué es una simulación (financiamiento de **una unidad**, por eso
cuelga de `oportunidad_items` y no de `oportunidades`, §1.1), que el cronograma
**no se persiste** (§4) y que `cuota_final` es el único derivado persistido y
**nunca** se acepta del cliente. Cita `reglas_simulaciones.md` y la migración V43.

Anota la clase con `@Suppress("LongParameterList")` y el mismo comentario que
llevan `OportunidadItem` y `MetaVenta` ("Una entidad JPA refleja las columnas de
su tabla").

### `domain/simulaciones/SimulacionLog.kt`

Mapea `simulacion_log`. **Todos los campos de snapshot son nullable** porque el
CHECK `chk_simulacion_log_snapshot` los exige solo para ciertos tipos de evento:

| Columna SQL | Propiedad | Tipo |
|---|---|---|
| `id` | `id` | `Long?` (`@Id @GeneratedValue(IDENTITY)`) |
| `id_simulacion` | `idSimulacion` | `Long` |
| `id_simulacion_origen` | `idSimulacionOrigen` | `Long?` |
| `tipo_evento` | `tipoEvento` | `TipoEventoSimulacion` (`@JdbcTypeCode(NAMED_ENUM)`, `columnDefinition = "tipo_evento_simulacion_enum"`) |
| `modo` | `modo` | `ModoSimulacion?` (`@JdbcTypeCode(NAMED_ENUM)`, `columnDefinition = "modo_simulacion_enum"`) |
| `precio_venta` | `precioVenta` | `BigDecimal?` |
| `descuento` | `descuento` | `BigDecimal?` |
| `cuota_inicial` | `cuotaInicial` | `BigDecimal?` |
| `plazo_meses` | `plazoMeses` | `Int?` |
| `tea` | `tea` | `BigDecimal?` |
| `valor_residual` | `valorResidual` | `BigDecimal?` |
| `dias_trabajados` | `diasTrabajados` | `Int?` |
| `comision_estructuracion` | `comisionEstructuracion` | `BigDecimal?` |
| `cuota_final` | `cuotaFinal` | `BigDecimal?` |
| `id_oportunidad_item` | `idOportunidadItem` | `Long?` |
| `id_oportunidad` | `idOportunidad` | `Long?` |
| `created_at` | `createdAt` | `LocalDateTime` (default `now()`) |
| `created_by` | `createdBy` | `Long?` |

**Todas las propiedades `val`**: el log es inmutable, solo INSERT (§7). Que la
entidad no exponga ningún `var` es la primera línea de defensa contra un UPDATE
accidental.

KDoc: bitácora permanente, solo INSERT, sin job de purga; `idSimulacion` **no
tiene FK a propósito** para que el log sobreviva al hard delete de la simulación
(incluido el evento `eliminada`); `createdBy` es null cuando el evento lo genera
un job sin actor humano.

**Restricciones:** nada de `@ManyToOne`/`@OneToMany` en ninguna de las dos
entidades (CLAUDE.md regla 9 y el patrón de `OportunidadItem`). No crees
repositorios ni servicios aquí (son D3 y siguientes).

**Criterio de aceptación:** `./gradlew compileKotlin ktlintCheck detekt --console=plain -q --no-daemon > /tmp/d2.log 2>&1; echo "EXIT:$?"` en EXIT:0.
Reporta ambos archivos completos y confirma, columna por columna contra V43, que
no falta ni sobra ninguna.

---

## D3 · Repositorios + test de repositorio

**Modelo:** Sonnet 5 · **Esfuerzo:** Medium

Abre `src/main/kotlin/pe/quantum/crm/domain/metasventa/MetaVentaRepository.kt` y
`src/main/kotlin/pe/quantum/crm/domain/oportunidades/OportunidadItemRepository.kt`
como referencia de estilo.

### `domain/simulaciones/SimulacionRepository.kt`

```kotlin
interface SimulacionRepository :
    JpaRepository<Simulacion, Long>,
    JpaSpecificationExecutor<Simulacion> {
```

Métodos:

1. **Desmarcar la principal de un ítem** (D38, se usa antes de insertar la nueva):
```kotlin
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE Simulacion s SET s.esPrincipal = false WHERE s.idOportunidadItem = :idItem AND s.esPrincipal = true")
    fun desmarcarPrincipalDe(idItem: Long): Int
```
KDoc: debe ejecutarse **antes** de insertar la nueva principal, o el índice único
parcial `uq_simulacion_principal` aborta la transacción (K14).

2. **Correlativo `#{n}` por lotes** (§8.1, D37). Query **nativa**, cópiala literal:
```kotlin
    @Query(
        value = """
            SELECT t.id AS id, t.correlativo AS correlativo
            FROM (
                SELECT s.id,
                       ROW_NUMBER() OVER (
                           PARTITION BY s.id_oportunidad_item,
                                        (CASE WHEN s.id_oportunidad_item IS NULL THEN s.id_modelo END),
                                        (CASE WHEN s.id_oportunidad_item IS NULL THEN s.modo END)
                           ORDER BY s.created_at, s.id
                       ) AS correlativo
                FROM simulaciones s
            ) t
            WHERE t.id IN (:ids)
        """,
        nativeQuery = true,
    )
    fun correlativos(ids: Collection<Long>): List<CorrelativoProjection>
```
con la proyección declarada en el mismo archivo:
```kotlin
/** Fila de [SimulacionRepository.correlativos]: el `#{n}` del nombre autogenerado (§8.1). */
interface CorrelativoProjection {
    fun getId(): Long
    fun getCorrelativo(): Int
}
```
KDoc de `correlativos`: el correlativo cuenta dentro del mismo ítem; para las no
enlazadas el scope es `(id_modelo, modo)` —en Postgres `PARTITION BY` agrupa
todos los NULL juntos, y por eso las dos columnas `CASE` los vuelven a separar—.
§8.1 dice explícitamente que para las no enlazadas **no es un dato crítico**.
Una sola consulta para toda la página: evita el N+1.

3. **Ítems de una página, para resolver nombres y modelos en lote** — no hace
   falta un método extra: el servicio ya tiene las entidades de la página.

### `domain/simulaciones/SimulacionLogRepository.kt`

```kotlin
interface SimulacionLogRepository : JpaRepository<SimulacionLog, Long>
```
Sin métodos propios en Plan D (el historial y su ventana de restauración son
Plan E). KDoc: solo INSERT; nunca UPDATE ni DELETE (§7).

### Test: `src/test/kotlin/pe/quantum/crm/domain/simulaciones/SimulacionRepositoryTest.kt`

`@Tag("integration")` + `@SpringBootTest`, extiende `IntegrationTestBase`. Abre
`src/test/kotlin/pe/quantum/crm/domain/solicitudes/SolicitudRepositoryTest.kt`
como plantilla exacta de estilo (constructor injection con `@Autowired`, fixtures
privados).

Casos que debe cubrir:

1. **Una simulación se guarda y se relee** con todos sus campos.
2. **El índice único de principal**: guardar dos simulaciones `esPrincipal = true`
   para el **mismo** `idOportunidadItem` lanza `DataIntegrityViolationException`.
3. **`desmarcarPrincipalDe` permite el relevo**: con una principal existente,
   llamar al método y luego guardar otra con `esPrincipal = true` **no** falla.
4. **CHECK `chk_simulacion_principal_requiere_item`**: `esPrincipal = true` con
   `idOportunidadItem = null` lanza `DataIntegrityViolationException`.
5. **CHECK `chk_simulacion_log_snapshot`**: un `SimulacionLog` con
   `tipoEvento = creada` y `modo = null` lanza `DataIntegrityViolationException`.
6. **`correlativos` numera 1, 2, 3** por orden de `createdAt` dentro del mismo ítem.

Para los ids de FK (`created_by`, `updated_by`) usa **`1`**, el admin sembrado por
V19 — igual que hace `SolicitudRepositoryTest`. `id_oportunidad_item` e
`id_modelo` son nullable y sus FK son `ON DELETE SET NULL`: para los casos que no
necesiten un ítem real, déjalos en `null`; para el caso 2/3/6, **necesitas un
`oportunidad_items` real** — créalo con `JdbcTemplate` crudo (empresa, vendedor,
financiadora, modelo, oportunidad, ítem), siguiendo el patrón de
`ReporteServiceSqlIntegrationTest.kt`, que ya lo hace.

**Este test es `@Tag("integration")` y NO lo puedes ejecutar en local** (Docker 29).
Verifícalo por lectura cuidadosa y repórtalo explícitamente como *"no ejecutable
en local, verificado por lectura"*. **Nunca digas que pasó.**

**Criterio de aceptación:**
```bash
./gradlew compileKotlin compileTestKotlin ktlintCheck detekt --console=plain -q --no-daemon > /tmp/d3.log 2>&1; echo "EXIT:$?"
```
en EXIT:0 (solo compila; no ejecuta el test de integración).

---

## D4 · Ampliar `OportunidadItemService` con `datosParaSimulacion`

**Modelo:** Opus 5 · **Esfuerzo:** High

Esta tarea **cruza la frontera entre módulos**: léete la decisión **D32** del mapa
y la **regla 12** de `CLAUDE.md` antes de tocar nada.

Abre enteros:
- `src/main/kotlin/pe/quantum/crm/domain/oportunidades/OportunidadItemService.kt`
- `src/main/kotlin/pe/quantum/crm/domain/oportunidades/OportunidadItemServiceImpl.kt`
- `src/main/kotlin/pe/quantum/crm/domain/oportunidades/dto/OportunidadItemVinculo.kt`
- `src/test/kotlin/pe/quantum/crm/arquitectura/ArquitecturaModulosTest.kt`

### Nuevo DTO

`src/main/kotlin/pe/quantum/crm/domain/oportunidades/dto/OportunidadItemParaSimulacion.kt`:

```kotlin
data class OportunidadItemParaSimulacion(
    val id: Long,
    val idOportunidad: Long,
    val idEmpresa: Long,
    val idVendedor: Long,
    val idModelo: Long,
    val cantidad: Int?,
    val precioVenta: BigDecimal?,
    val descuento: BigDecimal?,
    val cuotaFinanciadora: BigDecimal,
)
```

KDoc: datos del ítem que necesita `simulaciones` para prellenar los campos
esenciales (§6.1) y para la agregación de cuota (§6.2). `idVendedor` viaja aquí
para que `SimulacionPermisos` aplique su regla sin una segunda ida a la base.

### Método nuevo en la interfaz

```kotlin
    /**
     * Datos de estos items para `simulaciones`, SIN chequeo de visibilidad —
     * igual que [porOportunidades] y [montoTotalPorOportunidades].
     *
     * Quien llama decide la regla a proposito: la visibilidad de simulaciones NO
     * es la de oportunidades (reglas_simulaciones.md §10 — `analista` tiene
     * acceso total al modulo de simulaciones pese a ser rol de apoyo aqui, y
     * `jdv` no tiene ninguno pese a ser supervisor aqui). Aplicar la visibilidad
     * de oportunidades desde dentro le quitaria al analista el acceso que §10 le
     * da. La decision vive en `SimulacionPermisos`
     * (plan-09-mapa-simulaciones-modulo.md, decisiones D30-D32).
     *
     * Por lotes para no abrir un N+1 en el listado. Los items inexistentes
     * simplemente no aparecen en el mapa.
     */
    fun datosParaSimulacion(idsItem: Collection<Long>): Map<Long, OportunidadItemParaSimulacion>
```

### Implementación

En `OportunidadItemServiceImpl`, `@Transactional(readOnly = true)` (regla 10).

Necesita el `idEmpresa` y el `idVendedor` de la oportunidad dueña de cada ítem.
**Revisa qué método de `OportunidadItemRepository` y `OportunidadRepository` te
sirve** — el ítem trae `idOportunidad`, y de ahí sales a la oportunidad. Hazlo en
**dos consultas por lotes** (ítems por id, oportunidades por id), nunca una por
ítem. Devuelve `emptyMap()` si `idsItem` viene vacío, sin tocar la base.

Si al implementarlo ves que hace falta un método nuevo en algún repositorio de
`oportunidades`, **agrégalo** (es el mismo módulo, no cruza frontera). Si te
parece que hace falta tocar cualquier archivo **fuera** de
`domain/oportunidades/`, **detente y consulta**.

### Test

En `src/test/kotlin/pe/quantum/crm/domain/oportunidades/OportunidadItemServiceImplTest.kt`
(archivo existente, con mockk). Añade tests **sin tocar los que ya están**:

1. Devuelve los datos de dos ítems de oportunidades distintas, con `idEmpresa` e
   `idVendedor` correctos de cada oportunidad.
2. Con `idsItem` vacío devuelve `emptyMap()` y **no llama a ningún repositorio**
   (`verify(exactly = 0)`).
3. Un id de ítem inexistente simplemente no aparece en el mapa (no lanza).
4. **No aplica visibilidad**: un usuario `vendedor` que no es el dueño recibe
   igualmente los datos —ese es el contrato del método (D32)—. Nota: la firma no
   recibe `UsuarioActual`, así que este test se expresa comprobando que el método
   no consulta `OportunidadVisibilidad` en absoluto.

**Restricciones:** no toques `vinculoVisible` ni ningún otro método existente. No
crees nada en `domain/simulaciones/` (eso es de otras tareas).

**Criterio de aceptación:**
```bash
./gradlew compileKotlin ktlintCheck detekt --console=plain -q --no-daemon > /tmp/d4a.log 2>&1; echo "EXIT:$?"
./gradlew test --tests '*OportunidadItem*' --tests '*Arquitectura*' --console=plain -q --no-daemon > /tmp/d4b.log 2>&1; echo "EXIT:$?"
```
ambos en EXIT:0. **El test de ArchUnit es parte del criterio**: es el que verifica
que no rompiste la frontera de módulos.

---

## D5 · `SimulacionPermisos`: el punto único de decisión

**Modelo:** Opus 5 · **Esfuerzo:** High

**Esta es la tarea más delicada del plan.** Lee **K12, D30 y D31** del mapa
enteros, y `reglas_simulaciones.md` §9 y §10 completos, antes de escribir nada.

Abre también `src/main/kotlin/pe/quantum/crm/shared/security/UsuarioActual.kt`
y `src/main/kotlin/pe/quantum/crm/domain/oportunidades/OportunidadVisibilidad.kt`
(referencia de estilo de un componente de autorización, **no** de su contenido:
sus reglas son las de oportunidades y **aquí no aplican**).

### El archivo

`src/main/kotlin/pe/quantum/crm/domain/simulaciones/SimulacionPermisos.kt`,
`@Component`, sin dependencias inyectadas (es lógica pura sobre el rol).

KDoc de la clase, obligatorio y explícito:

> Único punto de decisión de autorización del módulo `simulaciones`
> (`reglas_simulaciones.md` §10 lo exige: *"debe estar centralizado en un solo
> punto de decisión, no disperso en condicionales por endpoint"*, y el reparto es
> candidato a cambiar).
>
> **No usa `UsuarioActual.esRolApoyo`, `esSupervisor` ni `visibilidadRestringida`
> a propósito**: ninguno parte los roles como los parte §10. `esRolApoyo` agrupa
> `analista` y `otro`, que aquí están en extremos opuestos (el primero tiene
> acceso total al módulo, el segundo ninguno); `esSupervisor` incluye a `jdv`,
> que aquí **no tiene acceso**. Ver
> `plan-09-mapa-simulaciones-modulo.md`, hallazgo K12 y decisiones D30/D31.

### La API

```kotlin
private companion object {
    /** Roles con acceso total al modulo: ven y editan cualquier simulacion (§10). */
    val ROLES_MODULO = setOf("admin", "gerencia", "analista")
}

/**
 * 403 si el rol no tiene ninguna funcion de simulaciones (`jdv`, `otro`).
 * Es 403 y no 404 a proposito: no es una pregunta sobre si el recurso existe,
 * es que el rol no tiene la funcion (CLAUDE.md regla 14 vs. este caso).
 */
fun exigirAcceso(usuario: UsuarioActual)

/**
 * 403 adicional para el listado del modulo (`GET /simulaciones`): solo
 * `admin`, `gerencia` y `analista`. El `vendedor` llega a sus simulaciones por
 * el contexto de la oportunidad y por la Calculadora, nunca por este listado
 * (§10, decision D39).
 */
fun exigirAccesoAlModulo(usuario: UsuarioActual)

/**
 * Regla de alcance de una simulacion concreta (decision D31):
 *  - rol de [ROLES_MODULO] -> alcanza cualquiera,
 *  - `vendedor` -> la enlazada cuyo item es de SU oportunidad
 *    (`idVendedorDelItem == usuario.id`), y la NO enlazada que el creo
 *    (`idCreador == usuario.id`); sin item no hay cadena a oportunidad, asi
 *    que la autoria es el unico vinculo posible,
 *  - cualquier otro rol -> false.
 *
 * `idVendedorDelItem` es null cuando la simulacion no esta enlazada a un item.
 */
fun alcanza(idCreador: Long, idVendedorDelItem: Long?, usuario: UsuarioActual): Boolean

/** [alcanza] o 404 — recurso ajeno se trata como inexistente (CLAUDE.md regla 14). */
fun exigirAlcance(idCreador: Long, idVendedorDelItem: Long?, usuario: UsuarioActual)
```

`exigirAcceso` y `exigirAccesoAlModulo` lanzan `PermisoInsuficienteException`
(403). `exigirAlcance` lanza `NoEncontradoException` (404). Ambas están en
`pe.quantum.crm.shared.exception`.

### Test: `src/test/kotlin/pe/quantum/crm/domain/simulaciones/SimulacionPermisosTest.kt`

Sin mockk: `SimulacionPermisos` no tiene dependencias. Escribe **primero** los
tests (regla 1, TDD) y luego la clase.

Cubre la matriz **completa**, un test por celda relevante:

| Caso | Esperado |
|---|---|
| `exigirAcceso` con `admin`, `gerencia`, `analista`, `vendedor` | no lanza |
| `exigirAcceso` con `jdv`, `otro` | `PermisoInsuficienteException` |
| `exigirAccesoAlModulo` con `admin`, `gerencia`, `analista` | no lanza |
| `exigirAccesoAlModulo` con `vendedor`, `jdv`, `otro` | `PermisoInsuficienteException` |
| `alcanza` con `analista` sobre simulación de otro | **true** (es el punto de K12) |
| `alcanza` con `jdv` sobre cualquiera | **false** (aunque sea supervisor en oportunidades) |
| `alcanza` con `vendedor`, ítem cuyo `idVendedorDelItem == usuario.id` | true |
| `alcanza` con `vendedor`, ítem de otro vendedor | false |
| `alcanza` con `vendedor`, sin ítem y `idCreador == usuario.id` | true |
| `alcanza` con `vendedor`, sin ítem y creada por otro | false |
| `exigirAlcance` cuando no alcanza | `NoEncontradoException` (**404, no 403**) |

Añade un test con nombre explícito del tipo
`` `analista alcanza simulaciones ajenas aunque sea rol de apoyo en oportunidades` ``
y un comentario que cite §10: es la regla que más fácil se rompe por inercia y el
test tiene que explicar por qué está ahí.

**Criterio de aceptación:**
```bash
./gradlew compileKotlin compileTestKotlin ktlintCheck detekt --console=plain -q --no-daemon > /tmp/d5a.log 2>&1; echo "EXIT:$?"
./gradlew test --tests '*SimulacionPermisos*' --console=plain -q --no-daemon > /tmp/d5b.log 2>&1; echo "EXIT:$?"
```
ambos en EXIT:0. Reporta la clase completa y la lista de tests que escribiste.

---

## D6 · DTOs del módulo

**Modelo:** Sonnet 5 · **Esfuerzo:** Medium

Archivo único: `src/main/kotlin/pe/quantum/crm/domain/simulaciones/dto/SimulacionDtos.kt`.

Abre antes, como referencia **de estilo y de convenciones de validación**:
- `src/main/kotlin/pe/quantum/crm/domain/oportunidades/dto/OportunidadItemDtos.kt`
  (cómo se declaran `@field:DecimalMin` / `@field:Digits` y por qué)
- `src/main/kotlin/pe/quantum/crm/domain/metasventa/dto/MetaVentaDtos.kt`
- `src/main/resources/db/migration/V43__create_simulaciones.sql` (los CHECK que
  hay que espejar)

**Convención de dinero del repo:** los importes salen del backend como `String`
(`toPlainString()`), no como número — ver `OportunidadItemDto.precioVenta`,
`montoItem`, `cuotaFinanciadora`. Respétala.

### `SimulacionDto` (respuesta)

```kotlin
data class SimulacionDto(
    val id: Long,
    /** Real si `nombre IS NOT NULL`; si no, el autogenerado de §8.1. NUNCA se persiste el autogenerado. */
    val nombre: String,
    /** true si `nombre` viene de la columna; false si se autogeneró al leer. */
    val nombreEsManual: Boolean,
    val modo: String,
    val idOportunidadItem: Long?,
    val idModelo: Long?,
    val modelo: ModeloEnSimulacionDto?,
    val idSimulacionOrigen: Long?,
    val precioVenta: String,
    val descuento: String,
    val cuotaInicial: String,
    val plazoMeses: Int,
    val tea: String,
    val valorResidual: String,
    val diasTrabajados: Int,
    val comisionEstructuracion: String,
    /** SOLO LECTURA: siempre server-side, nunca aceptado del cliente (§4). */
    val cuotaFinal: String,
    val esPrincipal: Boolean,
    val createdAt: Instant,
    val updatedAt: Instant,
)

/** Modelo de bus mostrado en la simulación; no participa del cálculo (§3.2). */
data class ModeloEnSimulacionDto(
    val id: Long,
    val codigo: String,
)
```

Para `Instant` usa el mismo helper que ya usa `OportunidadDto` (`comoInstanteUtc()`);
localízalo con `grep -rn "fun LocalDateTime.comoInstanteUtc" src/main/kotlin/`.

### `CrearSimulacionRequest`

```kotlin
data class CrearSimulacionRequest(
    val modo: String,
    val nombre: String? = null,
    val idOportunidadItem: Long? = null,
    val idModelo: Long? = null,
    val precioVenta: BigDecimal,
    val descuento: BigDecimal? = null,
    val cuotaInicial: BigDecimal,
    val plazoMeses: Int,
    val tea: BigDecimal,
    val valorResidual: BigDecimal? = null,
    val diasTrabajados: Int? = null,
    val comisionEstructuracion: BigDecimal? = null,
)
```

**No declares `cuotaFinal` ni `esPrincipal`.** `cuotaFinal` la calcula el backend
(restricción 2 del encargo); `esPrincipal` la decide D38.

Anotaciones de validación, **espejo exacto de los CHECK de V43** (cópialos de la
migración, líneas 67-78):

| Campo | Anotación |
|---|---|
| `precioVenta` | `@field:DecimalMin(value = "0.01", …)` + `@field:Digits(integer = 10, fraction = 2, …)` |
| `descuento` | `@field:DecimalMin("0.00")` + `@field:DecimalMax("100.00")` + `@field:Digits(integer = 3, fraction = 2)` |
| `cuotaInicial` | `@field:DecimalMin("0.00")` + `@field:Digits(integer = 10, fraction = 2)` |
| `plazoMeses` | `@field:Positive` |
| `tea` | `@field:DecimalMin(value = "0.01")` + `@field:DecimalMax(value = "199.99")` + `@field:Digits(integer = 4, fraction = 2)` |
| `valorResidual` | `@field:DecimalMin("0.00")` + `@field:Digits(integer = 10, fraction = 2)` |
| `diasTrabajados` | `@field:Positive` |
| `comisionEstructuracion` | `@field:DecimalMin("0.00")` + `@field:Digits(integer = 10, fraction = 2)` |
| `nombre` | `@field:Size(min = 1, max = 200)` — espejo de `chk_simulacion_nombre_no_vacio` |

Declara los literales como constantes privadas del archivo, igual que hace
`OportunidadItemDtos.kt` (`PRECIO_MIN`, `PRECIO_DIGITOS_ENTEROS`, …). **No repitas
números mágicos inline.**

### `ActualizarSimulacionRequest`

Todos los campos nullable (PATCH parcial: solo se toca lo que viene), **más
`modo`** por la decisión **D36**:

```kotlin
data class ActualizarSimulacionRequest(
    /**
     * Se acepta y se RECHAZA en el Service si difiere del actual (§2 exige que el
     * Service sea una de las tres lineas de defensa; sin este campo la unica
     * defensa de backend seria el trigger, que responde 500). Ver decision D36.
     */
    val modo: String? = null,
    val nombre: String? = null,
    val idOportunidadItem: Long? = null,
    val idModelo: Long? = null,
    val precioVenta: BigDecimal? = null,
    val descuento: BigDecimal? = null,
    val cuotaInicial: BigDecimal? = null,
    val plazoMeses: Int? = null,
    val tea: BigDecimal? = null,
    val valorResidual: BigDecimal? = null,
    val diasTrabajados: Int? = null,
    val comisionEstructuracion: BigDecimal? = null,
)
```
Mismas anotaciones de rango que en el request de creación.

### `SimulacionFiltros`

```kotlin
data class SimulacionFiltros(
    val idOportunidadItem: Long? = null,
    val idModelo: Long? = null,
    val modo: String? = null,
)
```

### DTOs del cronograma

```kotlin
/** Salida de GET /simulaciones/:id/cronograma. Nada de esto se persiste (§4). */
data class CronogramaDto(
    val cuotaFinal: String,
    val cuotaFinanciera: String,
    val valorVenta: String,
    val igv: String,
    val principal: String,
    val tasaNominalMensual: String,
    val filas: List<FilaCronogramaDto>,
)

/**
 * Una fila del cronograma. `interes`, `igv`, `cuota` y `cuotaConIgv` van null en
 * el mes 0 (la fila de la cuota inicial); `igv` va null en todo el modo leasing,
 * que no desglosa IGV (§3.3).
 */
data class FilaCronogramaDto(
    val mes: Int,
    val saldoInicial: String,
    val amortizacion: String,
    val interes: String?,
    val igv: String?,
    val saldoFinal: String,
    val cuota: String?,
    val cuotaConIgv: String?,
)
```

**Restricciones:** solo este archivo y el nuevo `dto/`. No escribas mappers desde
la entidad todavía (van con el servicio, D9/D10). No toques nada de `oportunidades`.

**Criterio de aceptación:** `./gradlew compileKotlin ktlintCheck detekt --console=plain -q --no-daemon > /tmp/d6.log 2>&1; echo "EXIT:$?"` en EXIT:0.
Reporta el archivo completo y confirma, uno por uno, que cada anotación de rango
coincide con su CHECK de V43.

---

## D7 · `NombreSimulacion`: autogeneración §8.1

**Modelo:** Sonnet 5 · **Esfuerzo:** Medium

Lee `reglas_simulaciones.md` §8.1 entero y la decisión **D37** del mapa.

Archivo: `src/main/kotlin/pe/quantum/crm/domain/simulaciones/NombreSimulacion.kt`.
**Función pura en un `object`**, sin Spring ni JPA — igual que `MontoTotal.kt` de
`oportunidades` (ábrelo como referencia de estilo).

```kotlin
object NombreSimulacion {
    /** Lo que se muestra cuando la simulacion no esta enlazada a un item (§8.1). */
    const val SIN_ENLAZAR = "Sin enlazar"

    /**
     * Nombre autogenerado de §8.1: `{Empresa} · {Modelo} · {Modo} · #{n}`.
     *
     * NUNCA se persiste (restriccion 1 del encargo y §4): se compone al leer.
     * El nombre manual es PEGAJOSO — si `simulaciones.nombre` tiene valor, ese
     * manda y esta funcion no se llama, ni siquiera al editar parametros o al
     * enlazar a un item.
     */
    fun autogenerado(
        razonSocialEmpresa: String?,
        codigoModelo: String?,
        modo: ModoSimulacion,
        correlativo: Int,
    ): String
}
```

Reglas de composición, exactas:

- Separador: `" · "` (espacio, U+00B7 MIDDLE DOT, espacio).
- `{Empresa}`: `razonSocialEmpresa` si no es null ni en blanco; si no, `SIN_ENLAZAR`.
- `{Modelo}`: `codigoModelo` si no es null ni en blanco; si no, se **omite ese
  segmento entero junto con su separador** (no dejes `· ·` ni un hueco).
- `{Modo}`: etiqueta legible, **no** el valor del enum:
  `ModoSimulacion.leasing` → `"Leasing"`, `ModoSimulacion.credito_directo` → `"Crédito Directo"`.
  Resuélvelo con un `when` exhaustivo sobre el enum, no con un `map` ni con
  manipulación de strings.
- `#{n}`: `"#" + correlativo`.

Ejemplos de §8.1 que deben salir literales (úsalos como tests):
```
Transportes Lima SAC · MB-O500 · Leasing · #2
Sin enlazar · MB-O500 · Crédito Directo · #1
```

### Test: `src/test/kotlin/pe/quantum/crm/domain/simulaciones/NombreSimulacionTest.kt`

TDD: primero los tests. Sin mockk (la función es pura). Casos:

1. Los dos ejemplos literales de §8.1 de arriba, carácter por carácter.
2. `razonSocialEmpresa = null` → empieza por `Sin enlazar`.
3. `razonSocialEmpresa = "   "` (en blanco) → también `Sin enlazar`.
4. `codigoModelo = null` → el segmento del modelo desaparece **y no queda doble
   separador**: `Transportes Lima SAC · Leasing · #1`.
5. Los dos valores de `ModoSimulacion` producen `Leasing` y `Crédito Directo`.
6. `correlativo = 12` → termina en `#12`.

**Restricciones:** no consultes la base ni inyectes nada; los datos llegan por
parámetro. No toques el motor ni ningún otro archivo.

**Criterio de aceptación:**
```bash
./gradlew compileKotlin compileTestKotlin ktlintCheck detekt --console=plain -q --no-daemon > /tmp/d7a.log 2>&1; echo "EXIT:$?"
./gradlew test --tests '*NombreSimulacion*' --console=plain -q --no-daemon > /tmp/d7b.log 2>&1; echo "EXIT:$?"
```
ambos en EXIT:0.

---

## D8 · Interfaz `SimulacionService`

**Modelo:** Sonnet 5 · **Esfuerzo:** Low

Archivo: `src/main/kotlin/pe/quantum/crm/domain/simulaciones/SimulacionService.kt`.
Solo la interfaz; **ninguna implementación** (esa es D9-D13).

Abre `src/main/kotlin/pe/quantum/crm/domain/metasventa/MetaVentaService.kt` y
`src/main/kotlin/pe/quantum/crm/domain/oportunidades/OportunidadItemService.kt`
como referencia de estilo del KDoc.

```kotlin
interface SimulacionService {
    fun crear(request: CrearSimulacionRequest, usuario: UsuarioActual): SimulacionDto

    /** IDOR: simulacion ajena → 404, nunca 403 (CLAUDE.md regla 14). */
    fun detalle(id: Long, usuario: UsuarioActual): SimulacionDto

    /** Listado del modulo. 403 para `vendedor`, `jdv` y `otro` (§10, decision D39). */
    @Suppress("LongParameterList") // Query params del contrato.
    fun listar(
        filtros: SimulacionFiltros,
        usuario: UsuarioActual,
        page: Int?,
        perPage: Int?,
        sort: String?,
        dir: String?,
    ): Paginado<SimulacionDto>

    /** `modo` distinto del actual → 409 `MODO_INMUTABLE` (§2, decision D36). */
    fun actualizar(id: Long, request: ActualizarSimulacionRequest, usuario: UsuarioActual): SimulacionDto

    fun eliminar(id: Long, usuario: UsuarioActual)

    /** Cronograma recalculado al vuelo; nunca persistido (§4, decision D40). */
    fun cronograma(id: Long, usuario: UsuarioActual): CronogramaDto
}
```

KDoc de la interfaz: API pública del módulo de simulaciones del financiamiento
propio de Quantum (`reglas_simulaciones.md`); el cronograma **no se persiste** y
`cuota_final` **nunca** se acepta del cliente.

**Criterio de aceptación:** `./gradlew compileKotlin ktlintCheck detekt --console=plain -q --no-daemon > /tmp/d8.log 2>&1; echo "EXIT:$?"` en EXIT:0.

---

## D9 · `SimulacionServiceImpl.crear` + evento `creada`

**Modelo:** Opus 5 · **Esfuerzo:** Extra High

**La tarea más densa del plan.** Lee antes, enteros: `reglas_simulaciones.md`
§3.2, §4, §5, §6.1, §6.3, §8.1, §13; y del mapa **D35, D37, D38** y los hallazgos
**K14, K15**.

Crea `src/main/kotlin/pe/quantum/crm/domain/simulaciones/SimulacionServiceImpl.kt`
con **solo el método `crear`** (los demás llegan en D10-D13; deja el resto de la
interfaz sin implementar todavía **no es opción** — Kotlin no compila una clase
que no implementa toda la interfaz, así que declara los otros métodos con
`TODO("D10")`, `TODO("D11")`, etc., y la siguiente tarea los reemplaza).

### Dependencias (inyección por constructor, regla 8)

```kotlin
@Service
class SimulacionServiceImpl(
    private val simulacionRepository: SimulacionRepository,
    private val simulacionLogRepository: SimulacionLogRepository,
    private val permisos: SimulacionPermisos,
    private val oportunidadItemService: OportunidadItemService,
    private val modeloService: ModeloService,
    private val empresaService: EmpresaService,
) : SimulacionService
```

### Algoritmo de `crear`, paso por paso

`@Transactional` (escritura, regla 10). En este orden **exacto**:

1. **`permisos.exigirAcceso(usuario)`** — 403 para `jdv` y `otro`.

2. **Resolver el modo**: `request.modo` → `ModoSimulacion`. Un valor fuera del
   enum es `ValidacionException` (400) con `field = "modo"`, **no** una
   `IllegalArgumentException` sin capturar (que sería 500). Usa
   `ModoSimulacion.entries.firstOrNull { it.name == request.modo }` o equivalente.

3. **Resolver el ítem, si viene** (`request.idOportunidadItem != null`):
   `oportunidadItemService.datosParaSimulacion(listOf(id))[id]`.
   - Si no existe → `NoEncontradoException` (404).
   - **`permisos.exigirAlcance(idCreador = usuario.id, idVendedorDelItem = item.idVendedor, usuario)`**
     — para un `vendedor` que apunta al ítem de otro, esto da **404** (§9: "solo
     puede enlazar a ítems de oportunidades donde él es el vendedor asignado").

4. **Resolver el modelo**: `request.idModelo` si viene; si no viene **y hay ítem**,
   se hereda `item.idModelo`. Si el resultado no es null, valida que exista con
   `modeloService.resumen(idModelo)` (404 si no).

5. **Aplicar los valores por defecto de §6.1** a lo que el request deje en null:

   | Campo | Default |
   |---|---|
   | `descuento` | `BigDecimal.ZERO` |
   | `valorResidual` | `BigDecimal.ZERO` |
   | `diasTrabajados` | `22` |
   | `comisionEstructuracion` | `BigDecimal("1180")` |

   Decláralos como constantes privadas del `companion object` con un comentario
   que cite §6.1, **no** como literales sueltos.
   Ojo: los defaults §6.1 de `plazoMeses` (48), `tea` (14) y `cuotaInicial`
   (45 000) **no aplican aquí** — esos tres son obligatorios en el request y su
   uso como default es para la **cuota efímera** de §6.1, que es Plan F.

6. **Validación §13 previa**: `cuota_inicial < PV_efectivo`, donde
   `PV_efectivo = precioVenta × (1 − descuento/100)`.
   Si no se cumple → `ValidacionException` con `field = "cuota_inicial"` y un
   mensaje que explique la relación. Usa `BigDecimal` y `AritmeticaFinanciera.MC`
   para el cálculo; **nunca** `Double`.

7. **Ejecutar el motor una sola vez** (decisión **D35**):
   ```kotlin
   val resultado = MotorSimulacion.calcular(
       ParametrosSimulacion(modo, precioVenta, descuento, cuotaInicial, plazoMeses, tea, valorResidual),
   )
   ```

8. **Validación §13 posterior**: `valorResidual < resultado.principal` →
   si no, `ValidacionException` con `field = "valor_residual"`.
   **No recalcules `principal` tú**: sale del motor, porque su fórmula depende del
   modo y ya vive ahí (D35 — duplicarla sería la tercera copia).

9. **Relevo de la principal** (decisión **D38**), solo si hay ítem:
   ```kotlin
   simulacionRepository.desmarcarPrincipalDe(idItem)
   ```
   **antes** de insertar. Con ítem, la nueva nace `esPrincipal = true` (§6.3: "por
   defecto es la última creada"). **Sin ítem, `esPrincipal = false` siempre** — lo
   exige el CHECK `chk_simulacion_principal_requiere_item`.

10. **Persistir la `Simulacion`** con `createdBy = updatedBy = usuario.id` y
    `createdAt = updatedAt = LocalDateTime.now()`. `cuotaFinal = resultado.cuotaFinal`.
    `nombre = request.nombre?.trim()?.takeIf { it.isNotEmpty() }` — nunca cadena
    vacía (CHECK `chk_simulacion_nombre_no_vacio`).

11. **Registrar el evento `creada`** en `simulacion_log`, con el **snapshot
    completo** que exige el CHECK (K15): `modo`, `precioVenta`, `descuento`,
    `cuotaInicial`, `plazoMeses`, `tea`, `valorResidual`, `diasTrabajados`,
    `comisionEstructuracion`, `cuotaFinal`, más `idSimulacion`,
    `idOportunidadItem`, **`idOportunidad`** (derivado del ítem, o null si no hay
    ítem) y `createdBy = usuario.id`.

12. **Devolver el DTO**, con el nombre resuelto. Extrae el ensamblado a un método
    privado `toDto(simulacion)` reutilizable —D10 lo va a necesitar para el
    listado en lotes, así que hazlo de forma que se pueda generalizar sin
    reescribirlo—. El nombre sale de:
    - `simulacion.nombre` si no es null → `nombreEsManual = true`;
    - si no, `NombreSimulacion.autogenerado(razonSocial, codigoModelo, modo, correlativo)`
      con `razonSocial` vía `empresaService.resumenPorIds`, `codigoModelo` vía
      `modeloService.resumenPorIds` y `correlativo` vía
      `simulacionRepository.correlativos(listOf(id))` → `nombreEsManual = false`.

### Tests (TDD: escríbelos antes)

`src/test/kotlin/pe/quantum/crm/domain/simulaciones/SimulacionServiceImplTest.kt`,
con mockk. Abre
`src/test/kotlin/pe/quantum/crm/domain/oportunidades/OportunidadItemServiceImplTest.kt`
como plantilla de estilo (stubs, `slot`, `verify`).

Casos obligatorios:

1. **Caso dorado leasing de §3.6**: `PV 110000 · CI 56000 · n 48 · TEA 18 · balloon 0`
   → la `cuotaFinal` persistida es **`1548.86`**. Captura la entidad con `slot`.
2. **Caso dorado crédito directo de §3.6**: `PV 90000 · CI 45000 · n 48 · TEA 13 · balloon 35000`
   → `cuotaFinal` = **`697.67`**.
3. `cuota_inicial >= PV_efectivo` → `ValidacionException` y **no se guarda nada**
   (`verify(exactly = 0) { simulacionRepository.save(any()) }`).
4. `valor_residual >= principal` → `ValidacionException`, sin guardar.
5. **Con ítem**: se llama `desmarcarPrincipalDe(idItem)` **antes** de `save`, y la
   entidad guardada tiene `esPrincipal = true`. Verifica el orden con
   `verifyOrder`.
6. **Sin ítem**: `esPrincipal = false` y **no** se llama `desmarcarPrincipalDe`.
7. **Evento `creada` con snapshot completo**: captura el `SimulacionLog` guardado
   y comprueba que `modo`, `precioVenta`, `cuotaInicial`, `plazoMeses`, `tea`,
   `valorResidual` y `cuotaFinal` **no son null** (es lo que exige el CHECK) y que
   `tipoEvento == TipoEventoSimulacion.creada`.
8. **`idOportunidad` derivado**: con ítem, el log lleva el `idOportunidad` del
   ítem; sin ítem, lo lleva en null.
9. **IDOR (regla 14)**: `vendedor` que apunta al ítem de otro vendedor →
   `NoEncontradoException` (**404**), sin guardar.
10. **`jdv` → `PermisoInsuficienteException`** (403), sin guardar.
11. **`modo` inválido** (`"leasing_raro"`) → `ValidacionException` con
    `field == "modo"`, sin guardar.
12. **Defaults de §6.1**: un request con `descuento`, `valorResidual`,
    `diasTrabajados` y `comisionEstructuracion` en null persiste `0`, `0`, `22` y
    `1180` respectivamente.
13. **Nombre manual en blanco**: `nombre = "   "` se persiste como `null`, no como
    cadena vacía.

**Restricciones:** no toques el motor (K10). No implementes `detalle`, `listar`,
`actualizar`, `eliminar` ni `cronograma` — déjalos en `TODO("Dxx")`. No toques
nada de `domain/oportunidades/`.

**Criterio de aceptación:**
```bash
./gradlew compileKotlin compileTestKotlin ktlintCheck detekt --console=plain -q --no-daemon > /tmp/d9a.log 2>&1; echo "EXIT:$?"
./gradlew test --tests '*SimulacionServiceImpl*' --console=plain -q --no-daemon > /tmp/d9b.log 2>&1; echo "EXIT:$?"
```
ambos en EXIT:0. Reporta los dos casos dorados con la cifra exacta obtenida.

---

## D10 · `SimulacionServiceImpl`: `detalle` y `listar`

**Modelo:** Sonnet 5 · **Esfuerzo:** High

Reemplaza los `TODO` de `detalle` y `listar`. Lee del mapa **D39** (por qué el
listado **no** lleva filtro de visibilidad) y **D37** (el nombre).

Abre `src/main/kotlin/pe/quantum/crm/shared/Paginacion.kt` y el método `listar`
de `MetaVentaServiceImpl` como referencia del patrón `CamposOrdenables` +
`Specification` + `Paginado`.

### `detalle(id, usuario)`

`@Transactional(readOnly = true)`.

1. `permisos.exigirAcceso(usuario)`.
2. Cargar la simulación; si no existe → `NoEncontradoException` (404).
3. Resolver el `idVendedor` del ítem: si `idOportunidadItem != null`, vía
   `oportunidadItemService.datosParaSimulacion`; si no, `null`.
4. `permisos.exigirAlcance(sim.createdBy, idVendedorDelItem, usuario)` → 404 si no.
5. Devolver el DTO.

### `listar(filtros, usuario, page, perPage, sort, dir)`

`@Transactional(readOnly = true)`.

1. **`permisos.exigirAccesoAlModulo(usuario)`** — no `exigirAcceso`: el listado
   del módulo es más restrictivo (403 también para `vendedor`).
2. **Sin filtro de visibilidad en la query** (D39): los tres roles que llegan
   aquí ven todo. Escribe un comentario que lo diga y cite §10 y D39 — es
   exactamente lo que un lector futuro pensaría que es un bug.
3. `Specification` con los filtros opcionales `idOportunidadItem`, `idModelo` y
   `modo` (este último resuelto a `ModoSimulacion`; un valor inválido →
   `ValidacionException` 400, igual que en D9 paso 2).
4. Paginación con:
   ```kotlin
   val CAMPOS_ORDENABLES = CamposOrdenables("createdAt", "id", "cuotaFinal", "updatedAt")
   ```
   en el `companion object`, con KDoc que diga que el primero es el orden por
   defecto.
5. Ensamblar los DTOs **en lotes, sin N+1**: una sola llamada a
   `simulacionRepository.correlativos(ids)`, una a `modeloService.resumenPorIds`,
   una a `oportunidadItemService.datosParaSimulacion` (para los ítems de la
   página) y una a `empresaService.resumenPorIds`. Generaliza el `toDto` privado
   de D9 a un `toDtos(List<Simulacion>)`, igual que hace
   `OportunidadServiceImpl.toDtos`.
6. Devolver `Paginado(items, Paginacion.meta(...))`.

### Tests

Añade a `SimulacionServiceImplTest.kt`, sin tocar los de D9:

1. `detalle` de una simulación propia devuelve el DTO.
2. `detalle` de una ajena, con `vendedor` → `NoEncontradoException` (404).
3. `detalle` con `analista` sobre una ajena → **funciona** (K12).
4. `listar` con `vendedor` → `PermisoInsuficienteException` (403).
5. `listar` con `analista` → devuelve resultados.
6. **`listar` no aplica filtro por vendedor**: con dos simulaciones de vendedores
   distintos y usuario `gerencia`, vuelven las dos.
7. `listar` con `modo` inválido → `ValidacionException`.
8. **Sin N+1**: con 3 simulaciones en la página, `correlativos` se llama
   **exactamente una vez** (`verify(exactly = 1)`).
9. Nombre manual gana sobre el autogenerado (`nombreEsManual = true`).
10. Nombre null → se autogenera y `nombreEsManual = false`.

**Criterio de aceptación:** compilación + `./gradlew test --tests '*Simulacion*'`
en EXIT:0 (ambos comandos redirigidos a archivo, `$?` comprobado por separado).

---

## D11 · `SimulacionServiceImpl.actualizar` + evento `editada`

**Modelo:** Opus 5 · **Esfuerzo:** High

Reemplaza el `TODO` de `actualizar`. Lee **D35, D36** del mapa, §2 y §13 de las
reglas, y **K16** (por qué el trigger no basta).

`@Transactional`. Pasos:

1. `permisos.exigirAcceso(usuario)`; cargar (404 si no existe); resolver
   `idVendedorDelItem`; `permisos.exigirAlcance(...)` → 404.
2. **`modo` (D36)**: si `request.modo != null` y, resuelto a `ModoSimulacion`,
   **difiere** del actual → `ConflictoException(code = "MODO_INMUTABLE", …)` →
   **409**. El mensaje debe mencionar "Guardar como Nueva Simulación" (§2). Si es
   igual al actual o viene null, sigue. **Nunca** asignes `modo` a la entidad (es
   `val`, D2).
3. **PATCH parcial**: cada campo se toca **solo si viene en el body**. Un campo
   ausente conserva su valor; un `nombre` en blanco se guarda como `null`.
   Ojo con `idOportunidadItem`: en Plan D, enlazar/desenlazar por PATCH **sí** se
   permite, pero si el nuevo ítem no es alcanzable por el usuario → 404
   (`permisos.exigirAlcance` sobre el ítem **nuevo**), y **no** se registra el
   evento `enlazada_a_item` (ese evento es Plan E; aquí basta `editada`).
4. **Revalidar §13 con los valores ya fusionados** (los del request encima de los
   actuales), en el mismo orden que D9: `cuota_inicial < PV_efectivo`, luego
   motor, luego `valor_residual < resultado.principal`.
5. **Recalcular `cuotaFinal`** server-side con el resultado del motor. Nunca del
   cliente, nunca conservando la anterior.
6. `updatedAt = now()`, `updatedBy = usuario.id`. Guardar.
7. **Evento `editada`** con el **snapshot completo posterior a la edición**
   (mismos campos que el `creada` de D9 paso 11, K15).
8. Devolver el DTO.

### Tests (añadir a `SimulacionServiceImplTest.kt`)

1. Cambia solo `tea` → `cuotaFinal` se recalcula y **cambia**; el resto de campos
   queda igual.
2. **PATCH vacío** (`ActualizarSimulacionRequest()`) → nada cambia, pero
   `cuotaFinal` se recalcula al mismo valor y se registra `editada`.
3. **`modo` distinto → `ConflictoException` con `code == "MODO_INMUTABLE"`** y
   **no se guarda nada**.
4. `modo` **igual** al actual → no lanza, la edición procede.
5. Edición que rompe `cuota_inicial < PV_efectivo` → `ValidacionException`, sin
   guardar.
6. Edición que rompe `valor_residual < principal` → `ValidacionException`, sin
   guardar.
7. El evento registrado es `editada` con snapshot completo (ningún campo del
   CHECK en null).
8. `vendedor` sobre simulación ajena → 404, sin guardar.
9. Reenlazar a un ítem de otro vendedor siendo `vendedor` → 404, sin guardar.
10. `nombre = "  "` → se persiste `null`.

**Criterio de aceptación:** compilación + `./gradlew test --tests '*Simulacion*'`
en EXIT:0.

---

## D12 · `SimulacionServiceImpl.eliminar` + evento `eliminada`

**Modelo:** Sonnet 5 · **Esfuerzo:** Medium

Reemplaza el `TODO` de `eliminar`. Lee §5 y §7 de las reglas y **K15** del mapa.

`@Transactional`. Pasos:

1. `permisos.exigirAcceso(usuario)`; cargar (404); resolver `idVendedorDelItem`;
   `permisos.exigirAlcance(...)` → 404.
2. **Registrar el evento `eliminada` con el snapshot completo ANTES de borrar.**
   Este orden es obligatorio: §5 dice "se registra el evento `eliminada` con
   snapshot completo en `simulacion_log` (que sobrevive, por eso `id_simulacion`
   no tiene FK)". Si borras primero, el snapshot ya no existe.
3. **Hard delete** de la fila de `simulaciones` (`simulacionRepository.delete`).
   No hay borrado lógico en esta tabla.
4. Devolver `Unit`.

**El log NO se borra nunca** (§7: permanente, solo INSERT, sin job de purga).

### Tests (añadir a `SimulacionServiceImplTest.kt`)

1. El evento `eliminada` se registra **antes** del `delete` (`verifyOrder`).
2. El snapshot del evento lleva todos los campos del CHECK no nulos.
3. `vendedor` sobre simulación ajena → 404 y **no se borra** (`verify(exactly = 0) { …delete(any()) }`).
4. `analista` puede eliminar una ajena (K12).
5. Simulación inexistente → `NoEncontradoException`, sin borrar.

**Criterio de aceptación:** compilación + `./gradlew test --tests '*Simulacion*'` en EXIT:0.

---

## D13 · `SimulacionServiceImpl.cronograma`

**Modelo:** Sonnet 5 · **Esfuerzo:** Medium

Reemplaza el último `TODO`. Lee **D40** del mapa y §3.3, §3.4, §4 de las reglas.

`@Transactional(readOnly = true)`. Pasos:

1. `permisos.exigirAcceso(usuario)`; cargar (404); resolver `idVendedorDelItem`;
   `permisos.exigirAlcance(...)` → 404.
2. Ejecutar `MotorSimulacion.calcular(...)` con los campos esenciales de la
   entidad (mismo `ParametrosSimulacion` que arma D9).
3. Mapear `ResultadoSimulacion` → `CronogramaDto`, importes con `toPlainString()`.
   `tasaNominalMensual` va **sin redondear** (§3.1: "la Tasa Nominal Mensual no se
   redondea nunca") — `toPlainString()` sobre el `BigDecimal` que da el motor, tal
   cual.
4. **No se persiste nada.** El método no escribe ni en `simulaciones` ni en
   `simulacion_log` (§4, restricción 1 del encargo).

### Tests: `src/test/kotlin/pe/quantum/crm/domain/simulaciones/SimulacionCronogramaTest.kt`

Archivo propio (el de servicio ya va cargado; separarlo evita `LargeClass` en
detekt, igual que hicieron `OportunidadCambiarEstadoInvariantesTest` y
`OportunidadActualizarTest`).

1. **Caso dorado leasing §3.6**: el cronograma trae **49 filas** (mes 0 más 48),
   y las filas 0, 1, 2 y 48 coinciden **al centavo** con la tabla de §3.6.
2. **Caso dorado crédito directo §3.6**: ídem, con sus cifras y con `igv` no null
   en los meses ≥ 1.
3. **Leasing no desglosa IGV** (§3.3): `igv == null` en **todas** las filas.
4. **Mes 0**: `interes`, `igv`, `cuota` y `cuotaConIgv` son null; `amortizacion`
   es la cuota inicial.
5. **No hay fila extra por el balloon** (restricción 3 del encargo): con
   `valorResidual = 35000`, la última fila es el mes 48 y su `saldoFinal` es
   `35000.00`; **no existe** una fila 49.
6. **No escribe nada**: `verify(exactly = 0)` sobre `simulacionRepository.save` y
   `simulacionLogRepository.save`.
7. `vendedor` sobre ajena → 404.

**Criterio de aceptación:** compilación + `./gradlew test --tests '*Simulacion*'`
en EXIT:0. Reporta las cifras obtenidas para las filas 0, 1, 2 y 48 de ambos
casos dorados.

---

## D14 · `SimulacionController` + test WebMvc

**Modelo:** Sonnet 5 · **Esfuerzo:** High

Abre `src/main/kotlin/pe/quantum/crm/domain/metasventa/MetaVentaController.kt`
como plantilla exacta de estilo, y
`src/test/kotlin/pe/quantum/crm/domain/metasventa/MetaVentaControllerWebMvcTest.kt`
como plantilla del test.

### `domain/simulaciones/SimulacionController.kt`

```kotlin
@RestController
@RequestMapping("/api/v1/simulaciones")
class SimulacionController(
    private val simulacionService: SimulacionService,
    private val usuarioProvider: UsuarioActualProvider,
)
```

| Método | Ruta | Servicio | Status |
|---|---|---|---|
| `POST` | `` | `crear` | `201 CREATED` (`@ResponseStatus`) |
| `GET` | `` | `listar` | 200, envelope con `meta` |
| `GET` | `/{id}` | `detalle` | 200 |
| `GET` | `/{id}/cronograma` | `cronograma` | 200 |
| `PATCH` | `/{id}` | `actualizar` | 200 |
| `DELETE` | `/{id}` | `eliminar` | `204 NO_CONTENT` |

Query params del listado: `id_oportunidad_item`, `id_modelo`, `modo`, `page`,
`per_page`, `sort`, `dir` — con `@RequestParam(required = false, name = "…")`
usando **snake_case en el nombre público**, igual que hace `MetaVentaController`
con `id_empleado` y `per_page`. Anota el método con
`@Suppress("LongParameterList") // Query params del contrato.`

`@Valid @RequestBody` en `POST` y `PATCH`. Respuestas envueltas en
`ApiResponse.ok(...)`; el listado con `ApiResponse.ok(resultado.items, resultado.meta)`.

**Sin `@PreAuthorize`**: toda la autorización de este módulo vive en
`SimulacionPermisos` (D30 — punto único de decisión). Escribe un comentario en la
clase que lo diga explícitamente, para que nadie añada anotaciones de rol después
y parta la decisión en dos sitios.

### Test: `SimulacionControllerWebMvcTest.kt`

`@WebMvcTest` con el servicio mockeado, siguiendo la plantilla de metas de venta
(fíjate en cómo resuelve la seguridad y el `UsuarioActualProvider`).

Casos:
1. `POST` válido → 201 y el JSON del DTO.
2. `POST` con `precio_venta` negativo → **400** por Bean Validation.
3. `POST` que incluye `cuota_final` en el body → el campo **se ignora**
   (no está en el DTO); comprueba que el servicio recibe el request sin él.
4. `GET /{id}` → 200.
5. `GET` listado → 200 con `meta` en el envelope.
6. `GET /{id}/cronograma` → 200 con las filas.
7. `PATCH` → 200.
8. `DELETE` → 204 **sin body**.
9. Los nombres de los query params viajan en snake_case.

**Criterio de aceptación:**
```bash
./gradlew compileKotlin compileTestKotlin ktlintCheck detekt --console=plain -q --no-daemon > /tmp/d14a.log 2>&1; echo "EXIT:$?"
./gradlew test --tests '*Simulacion*' --console=plain -q --no-daemon > /tmp/d14b.log 2>&1; echo "EXIT:$?"
```
ambos en EXIT:0.

---

## D15 · Verificación de build completa (local, sin `integrationTest`)

**Modelo:** Sonnet 5 · **Esfuerzo:** Low

No edites código. Ejecuta, cada uno redirigido a archivo y con `$?` comprobado
**en un comando separado**:

```bash
./gradlew ktlintCheck --console=plain -q --no-daemon > /tmp/d15_lint.log 2>&1; echo "EXIT:$?"
./gradlew detekt      --console=plain -q --no-daemon > /tmp/d15_detekt.log 2>&1; echo "EXIT:$?"
./gradlew test        --console=plain -q --no-daemon > /tmp/d15_test.log 2>&1; echo "EXIT:$?"
```

**No ejecutes `integrationTest` ni `koverVerify`** — `koverVerify` arrastra
`integrationTest` como dependencia y Docker 29 lo bloquea en local.

Esta tarea **no puede** confirmar en verde:
- `SimulacionRepositoryTest` (D3, `@Tag("integration")`),
- el trinquete de cobertura (85 % global / 84 % dominio), que solo corre en CI.

**Repórtalo así, explícitamente, como una limitación de primer orden**, no como
una nota al pie. El módulo nuevo es grande: si su cobertura quedó corta, el
build de CI se cae en `koverVerify` y hace falta otra vuelta.

Si alguno de los tres comandos falla, **no lo arregles**: reporta el fallo exacto
(con el fragmento relevante del log) y detente ahí.

**Criterio de aceptación:** los tres en EXIT:0, con su salida, más
`git status --short` confirmando que no modificaste ningún archivo.

---

## D16 · Auditoría final del diff contra los documentos citados

**Modelo:** Opus 5 · **Esfuerzo:** High

Tarea exigida por `CLAUDE.md` §"Cómo escribir un plan de implementación en este
repo". **Auditoría del diff completo, no un resumen. No arregles nada. No hagas
commit.** Solo reporta hallazgos.

Contrasta el diff completo (`git status` / `git diff` contra HEAD, abriendo los
archivos enteros que haga falta) contra:
`plan-09-mapa-simulaciones-modulo.md` (K10-K21, D30-D42) ·
`docs/reglas_simulaciones.md` §1-§10 y §13 ·
`Instrucciones_simulaciones.md` §"Restricciones que no se negocian" ·
`CLAUDE.md` reglas 1, 8, 9, 10, 11, 12, 14 ·
`V43__create_simulaciones.sql`.

Busca, uno por uno:

1. **Contradicciones con documentación ya vigente y correcta** — no "falta
   documentar X", sino algo que ya estaba bien escrito antes de empezar y que el
   código nuevo ignoró o pisó.
2. **K12/D30 respetado**: `grep -rn "esRolApoyo\|esSupervisor\|visibilidadRestringida" src/main/kotlin/pe/quantum/crm/domain/simulaciones/`
   debe salir **vacío**. Cualquier resultado es un hallazgo **bloqueante**.
3. **`cuota_final` nunca del cliente**: `CrearSimulacionRequest` y
   `ActualizarSimulacionRequest` **no** la declaran, y en el servicio siempre sale
   de `MotorSimulacion`.
4. **Nada derivable persistido** (restricción 1): busca cualquier intento de
   guardar cronograma o nombre autogenerado. El nombre solo se persiste cuando
   viene del usuario.
5. **Orden del relevo de principal** (K14/D38): `desmarcarPrincipalDe` se invoca
   **antes** del `save` de la nueva.
6. **Snapshot del log** (K15): cada evento `creada`/`editada`/`eliminada` lleva
   los siete campos que el CHECK exige no nulos.
7. **`eliminada` se registra antes del delete** (§5).
8. **Frontera de módulos** (regla 12): el único punto de contacto con
   `oportunidades` es `OportunidadItemService`/sus DTOs. `./gradlew test --tests '*Arquitectura*'`
   debe pasar. `grep -rn "import pe.quantum.crm.domain.oportunidades" src/main/kotlin/pe/quantum/crm/domain/simulaciones/`
   no debe importar entidades, repositorios ni `*Impl`.
9. **El motor no se tocó** (K10): `git diff --stat -- src/main/kotlin/pe/quantum/crm/shared/simulacion/`
   debe salir vacío.
10. **Ninguna migración nueva** (D42): `git status --short -- src/main/resources/db/migration/`
    vacío, y `SchemaMigrationIntegrationTest.kt` **sin modificar**.
11. **Ninguna dependencia nueva** en `build.gradle.kts` (K20 — no se implementa
    PDF ni Excel, así que no debe aparecer POI ni nada parecido). Nota: ese
    archivo puede venir ya modificado de antes por un cambio ajeno (`bootRun` /
    timezone); confírmalo leyendo su diff y di si Plan D le añadió algo o no.
12. **Honestidad sobre `integrationTest`**: `SimulacionRepositoryTest` debe llevar
    `@Tag("integration")` correcto, y **ningún comentario ni reporte** debe
    afirmar que corrió. Si alguna tarea reportó verde sobre él, es un hallazgo
    **bloqueante de proceso**, no solo de código.
13. **TDD (regla 1)**: cada clase de producción nueva con lógica
    (`SimulacionPermisos`, `NombreSimulacion`, `SimulacionServiceImpl`) tiene su
    archivo de tests, y los casos dorados de §3.6 aparecen literalmente en algún
    test.

**Entregable:** informe con hallazgos clasificados en **bloqueante / menor /
ninguno**, cada uno con archivo, línea y la regla o sección concreta que
contradice.

---

## Cierre del plan

Al terminar D16, **para y resume** qué se hizo. **No abras PR ni hagas commit** —
eso lo decide el arquitecto, igual que en los planes anteriores.

Estado esperado al cerrar Plan D: el módulo `simulaciones` existe, es alcanzable
desde la API (CRUD completo + cronograma on demand), su autorización vive en un
único punto de decisión, y el motor de la Fase 1 por fin tiene consumidor. Sin
historial, sin Calculadora y sin cuota en la oportunidad — eso son los Planes E y F.
