# Plan F — Cierre completo del módulo Simulaciones

> **Destinatario: agentes ejecutores, no humanos.** Cada tarea es autocontenida.
> Ejecutar en orden estricto: cada una asume que la anterior está cerrada,
> compilando y con `./gradlew test` en verde.
>
> **Regla para el ejecutor:** si algo de tu tarea es ambiguo, contradice a otra
> tarea, o el repo no coincide con lo que la tarea describe — **detente y
> consulta al arquitecto**. No infieras, no inventes, no "arregles" de paso nada
> que la tarea no te pida. Esto es producción con usuarios reales.
>
> **Tests `@Tag("integration")`:** Docker Desktop 29 rompe Testcontainers en
> local. **Ninguna tarea puede confirmarlos en verde**; solo CI. Quien toque uno
> dice *"no ejecutable en local, verificado por lectura cuidadosa"*, nunca un
> falso verde.
>
> **Infraestructura:** ante `CorruptedException`, "Could not delete" o "Failed to
> clean up output files" (locks de Windows): mata los `java.exe`,
> `./gradlew --stop`, borra `build/`, reintenta con `--no-daemon`. **Nunca
> confíes en el exit code de un pipeline con `| tail`**: redirige a archivo y
> comprueba `$?` aparte.
>
> **MSYS/Git-Bash:** `--tests '*Simulacion*'` puede expandirse contra
> `Instrucciones_simulaciones.md` y hacer que Gradle diga "No tests found". Usa
> siempre patrones específicos (`--tests '*SimulacionServiceImpl*'`,
> `--tests '*CuotaEfimera*'`, `--tests '*Arquitectura*'`).

---

## Fase de investigación (leer antes de la Task F1)

| Documento | Qué manda |
|---|---|
| `docs/planes/plan-13-mapa-cierre-simulaciones.md` | **Léelo entero primero.** K31-K39, D54-D65 |
| `docs/reglas_simulaciones.md` §5, §6.1, §6.2, §6.3, §8.2, §10, §13 | Comportamiento exacto |
| `docs/planes/plan-09-mapa-simulaciones-modulo.md` · `plan-11-mapa-historial-calculadora.md` | Contexto vigente de Planes D y E |
| `src/main/resources/db/migration/V22__create_notificaciones.sql` y `V43__create_simulaciones.sql` | Los enums y los CHECK que este plan respeta |
| `CLAUDE.md` | Reglas 1, 8, 9, 10, 11, 12, 14 |

### Las cinco trampas de este plan

1. **La cuota efímera NUNCA puede lanzar** (K32/D55). Con los defaults de §6.1
   y un ítem de 60 000, `valor_residual (25 000) > Principal (12 711)`: §13 no
   se cumple. Si eso propaga una excepción, **`GET /oportunidades` devuelve
   500**. Degrada a `null`, siempre.
2. **`DefaultsSimulacion` NO son los defaults de §6.1.** Guarda los `DEFAULT`
   de columna de V43 (`valor_residual` 0); §6.1 dice 25 000. Su propio KDoc lo
   advierte. **No los mezcles**: los de §6.1 viven en `CuotaEfimera`.
3. **`oportunidades` no puede leer `DefaultsSimulacion` ni `ValidacionesSimulacion`**
   (K34): son `object`, no API pública. ArchUnit lo rechaza.
4. **Este plan toca `SeedFixtures`, no `SchemaMigrationIntegrationTest`** (K39):
   añadir valores a un enum no cambia ni la lista de tablas (24) ni la de tipos
   (21). Confundirlos costó dos rondas de CI en el Plan B.
5. **El número de la migración se asigna al desplegar**, no al escribirla (K19):
   Flyway corre con `out-of-order = false` y ya hubo una renumeración V40→V43 en
   este módulo.

### Alcance — lista cerrada de archivos

Ver `plan-13-mapa-cierre-simulaciones.md` §4. Fuera de esa lista: **detente y
consulta**.

---

## Tabla de tareas

| ID | Tarea | Modelo | Esfuerzo |
|---|---|---|---|
| F1 | Migración de enums de notificación + enums Kotlin + `SeedFixtures` (D59) | Sonnet 5 | Medium |
| F2 | `SimulacionDto`: `idOportunidad` y `eliminacionPrevistaEl` (D63) | Sonnet 5 | Medium |
| F3 | `CuotaEfimera`: el cálculo de §6.1 que nunca falla (D54/D55) | Opus 5 | Extra High |
| F4 | `SimulacionService.cuotaQuantumPorItems` + query de principales (D56) | Opus 5 | High |
| F5 | §6.2 en `oportunidades`, con `@Lazy` (D57/D62) | Opus 5 | Extra High |
| F6 | `registrarEvento(idUsuario)` + `purgarHuerfanas` (D60) | Opus 5 | High |
| F7 | `avisarPorExpirar` + `PurgaSimulacionesJob` (D58/D61) | Opus 5 | High |
| F8 | `contrato_api.md`: §23/§24 nuevas, renumeración, enums, changelog (D64) | Sonnet 5 | Extra High |
| F9 | `matriz_permisos.md` §2.15 + `CLAUDE.md` (K38) | Sonnet 5 | Medium |
| F10 | Verificación de build completa (local, sin `integrationTest`) | Sonnet 5 | Low |
| F11 | Auditoría final del diff contra los documentos citados | Opus 5 | High |

**F12, fuera de este plan de tareas** (coordinada aparte con el arquitecto):
aplicar la migración de F1 a producción, **solo** después de que el PR tenga CI
en verde — igual que C13 en el Plan C y que la V46.

---

## F1 · Migración de enums de notificación + enums Kotlin + `SeedFixtures`

**Modelo:** Sonnet 5 · **Esfuerzo:** Medium

Lee `plan-13` decisión **D59** y hallazgo **K39**. Abre
`src/main/resources/db/migration/V22__create_notificaciones.sql` (los cuatro
enums), `V44__solicitudes_entidad_item.sql` (el precedente exacto de un
`ALTER TYPE ... ADD VALUE` en este repo) y
`src/main/kotlin/pe/quantum/crm/domain/notificaciones/NotificacionEnums.kt`.

**No apliques nada a producción.** Solo archivos locales.

### La migración

Verifica primero cuál es el número máximo en
`src/main/resources/db/migration/` (`ls` la carpeta y ordena). Si es V46, tu
archivo es **`V47__notificaciones_simulacion.sql`**; si hubiera algo más alto,
usa el siguiente y **repórtalo**, no lo fuerces.

```sql
-- =============================================================================
-- V47 — Valores de enum para el aviso de expiracion de simulaciones huerfanas
-- (reglas_simulaciones.md §5: aviso al creador 3 dias antes del borrado a los
-- 30 dias). El encargo autoriza expresamente esta migracion de enums.
--
-- Los dos valores se AGREGAN aqui y se USAN despues, desde el job: nunca en
-- esta misma transaccion. `origen_recordatorio_enum` NO se toca — el aviso
-- deduplica por ventana de 24 h, sin `recordatorios_enviados`
-- (plan-13-mapa-cierre-simulaciones.md, decision D58).
-- =============================================================================

ALTER TYPE tipo_notificacion_enum   ADD VALUE 'simulacion_por_expirar';
ALTER TYPE entidad_notificacion_enum ADD VALUE 'simulacion';
```

### Los enums Kotlin

En `NotificacionEnums.kt`, añade `simulacion_por_expirar` **al final** de
`TipoNotificacion` y `simulacion` **al final** de `EntidadNotificacion`.
Respeta el estilo del archivo (minúsculas, el `@Suppress` que ya tiene).
No reordenes los valores existentes.

### `SeedFixtures.kt`

`src/test/kotlin/pe/quantum/crm/support/SeedFixtures.kt`: sube **ambas**
constantes en uno — `MIGRACIONES_TOTAL` 45 → **46**, `MIGRACION_VERSION_MAX`
46 → **47**. Siguen sin coincidir por el hueco permanente de V40, y el
comentario que lo explica ya está ahí: **actualiza los números que menciona**,
no borres el razonamiento.

**Restricción explícita:** **NO toques
`src/test/kotlin/pe/quantum/crm/db/SchemaMigrationIntegrationTest.kt`.** Añadir
valores a un enum no crea tipos ni tablas: sus aserciones de 24 tablas y 21
enums no cambian (K39). Si crees que sí hace falta, **detente y consulta**.

**Criterio de aceptación:**
```bash
./gradlew compileKotlin compileTestKotlin ktlintCheck detekt --console=plain -q --no-daemon > /tmp/f1.log 2>&1; echo "EXIT:$?"
```
en EXIT:0. Reporta el contenido de la migración, el diff de los dos enums y de
`SeedFixtures`, y confirma con `git status --short` que
`SchemaMigrationIntegrationTest.kt` **no** aparece.

---

## F2 · `SimulacionDto`: `idOportunidad` y `eliminacionPrevistaEl`

**Modelo:** Sonnet 5 · **Esfuerzo:** Medium

Lee `plan-13` decisión **D63**, y `reglas_simulaciones.md` §5 y §8.2. Abre
`dto/SimulacionDtos.kt`, `SimulacionServiceImpl.kt` (los privados `ensamblar`,
`toDto` y `toDtos`) y `OportunidadItemParaSimulacion.kt` (ya trae
`idOportunidad`).

Añade a `SimulacionDto`, después de `idOportunidadItem`:

```kotlin
    /**
     * Oportunidad dueña del item enlazado, derivada — no es columna de
     * `simulaciones`. Existe para que el modulo pueda agrupar por oportunidad
     * sin resolver item->oportunidad por su cuenta (§8.2). Null si la
     * simulacion no esta enlazada.
     */
    val idOportunidad: Long?,
```

y al final, junto a `createdAt`/`updatedAt`:

```kotlin
    /**
     * Fecha prevista de borrado de una simulacion huerfana: `created_at` + 30
     * dias. §5 exige que la regla sea VISIBLE EN LA UI, no solo logica de
     * servidor. Null en cuanto la simulacion tiene item: enlazarla la salva de
     * forma definitiva. Derivado, nunca persistido.
     */
    val eliminacionPrevistaEl: Instant?,
```

En `ensamblar` (el que arma el DTO sin consultar nada):
- `idOportunidad` llega como parámetro nuevo (los llamadores ya tienen el
  `OportunidadItemParaSimulacion`; pásale `item?.idOportunidad`).
- `eliminacionPrevistaEl` se calcula ahí mismo:
  `simulacion.idOportunidadItem?.let { null } ?: simulacion.createdAt.plusDays(30).comoInstanteUtc()`
  — o la forma que te resulte legible, **pero el valor solo existe cuando
  `idOportunidadItem == null`**. Declara el `30` como constante privada del
  archivo o del `companion object`, con un comentario que cite §5; **no lo
  dejes como literal suelto**, y ojo: F6 va a necesitar el mismo número para la
  purga, así que ponlo donde ambos puedan usarlo (`DefaultsSimulacion` es un
  buen sitio: es del mismo módulo).

Ajusta los tres llamadores (`toDto`, `toDtos` y cualquier otro que compile en
rojo) para pasar el `idOportunidad`.

### Tests

Añade a `SimulacionServiceImplTest.kt`, sin tocar los existentes:
1. Simulación **con** ítem → `idOportunidad` es el de su ítem y
   `eliminacionPrevistaEl` es **`null`**.
2. Simulación **sin** ítem → `idOportunidad` es `null` y
   `eliminacionPrevistaEl` es exactamente `createdAt + 30 días` (compáralo
   contra el `createdAt` que sembró el fixture, no contra `now()`).
3. En `listar` (lote), ambos campos se resuelven igual que en `detalle`.

**Criterio de aceptación:**
```bash
./gradlew compileKotlin compileTestKotlin ktlintCheck detekt --console=plain -q --no-daemon > /tmp/f2a.log 2>&1; echo "EXIT:$?"
./gradlew test --tests '*SimulacionServiceImpl*' --tests '*SimulacionController*' --console=plain -q --no-daemon > /tmp/f2b.log 2>&1; echo "EXIT:$?"
```
ambos en EXIT:0 (el segundo incluye el WebMvc porque el DTO cambia de forma).

---

## F3 · `CuotaEfimera`: el cálculo de §6.1 que nunca falla

**Modelo:** Opus 5 · **Esfuerzo:** Extra High

**La tarea más delicada del plan.** Lee enteros: `plan-13` **K31, K32, D54,
D55**, y `reglas_simulaciones.md` §6.1 y §13. Abre también
`shared/simulacion/MotorSimulacion.kt` (no se toca),
`ValidacionesSimulacion.kt`, `DefaultsSimulacion.kt` y
`shared/exception/NegocioExceptions.kt` (`CronogramaInconsistenteException`).

### Por qué esta tarea existe

§6.1 dice que un ítem sin simulación principal muestra una cuota calculada al
vuelo con parámetros por defecto. Pero esos parámetros son constantes y el
`precio_venta` viene del ítem, así que **hay ítems reales para los que la
combinación es inválida**: con un ítem de 60 000 en leasing,
`Principal = 60 000/1.18 − 45 000/1.18 = 12 711`, y el `valor_residual` por
defecto (25 000) es mayor → §13 no se cumple. Si eso lanza, **el `GET
/oportunidades` entero responde 500**. No puede pasar.

### El archivo

`src/main/kotlin/pe/quantum/crm/domain/simulaciones/CuotaEfimera.kt`, `object`
puro (sin Spring, sin JPA), estilo `NombreSimulacion`/`DiffSimulacion`:

```kotlin
object CuotaEfimera {
    /**
     * Cuota Quantum estimada de un item que todavia no tiene simulacion
     * principal (§6.1): se calcula al vuelo con los parametros por defecto y
     * NO se persiste nada.
     *
     * Devuelve `null` —nunca lanza— cuando no hay una cuota razonable que
     * mostrar (decision D55). Es un dato informativo en un listado: jamas
     * puede impedir leer la oportunidad.
     */
    fun calcular(precioVenta: BigDecimal?, descuento: BigDecimal?): BigDecimal?
}
```

### Los parámetros por defecto de §6.1 — **no reutilices `DefaultsSimulacion`**

`DefaultsSimulacion` guarda los `DEFAULT` de **columna de V43**
(`VALOR_RESIDUAL = 0`), y §6.1 dice **25 000**. Su propio KDoc advierte que
son cosas distintas. Declara los de §6.1 como constantes privadas **dentro de
`CuotaEfimera`**:

| Constante | Valor | Fuente |
|---|---|---|
| `PLAZO_MESES` | 48 | §6.1 |
| `TEA` | `BigDecimal("14")` | §6.1 |
| `CUOTA_INICIAL` | `BigDecimal("45000")` | §6.1 |
| `VALOR_RESIDUAL` | `BigDecimal("25000")` | §6.1 |
| `MODO` | `ModoSimulacion.leasing` | **D54 — §6.1 NO lo especifica** |

`MODO` lleva un comentario explícito: *§6.1 no dice con qué modo se calcula la
cuota efímera; leasing es la decisión del backend (D54), no una regla citada.*

`dias_trabajados` y `comision_estructuracion` de §6.1 **no entran aquí**: no
participan del cronograma (§3.2), así que el motor no los usa y esta función no
los necesita.

### El algoritmo, con las tres salidas `null`

1. Si `precioVenta == null` → `null`. (Ítem incompleto, D15 del Plan B.)
2. `descuentoEfectivo = descuento ?: BigDecimal.ZERO`.
3. `PV_efectivo = precioVenta × (1 − descuentoEfectivo/100)`, con
   `AritmeticaFinanciera.MC`.
   Si `CUOTA_INICIAL >= PV_efectivo` → `null` (§13 no se cumple).
4. Ejecutar el motor **una vez** con `ParametrosSimulacion(MODO, precioVenta,
   descuentoEfectivo, CUOTA_INICIAL, PLAZO_MESES, TEA, VALOR_RESIDUAL)`,
   **envuelto en `runCatching`**.
   - Si lanza (`CronogramaInconsistenteException` u otra) → `null`, y **un
     `log.warn`** con el `precioVenta` y el `descuento` que lo provocaron. Es
     una anomalía que alguien debe poder ver en los logs, nunca un 500.
5. Si `VALOR_RESIDUAL >= resultado.principal` → `null` (§13 no se cumple).
6. Devolver `resultado.cuotaFinal`.

**Comprueba §13 sin lanzar.** No llames a `ValidacionesSimulacion` para luego
capturar su `ValidacionException`: compara directamente con `>=`. Usar
excepciones como control de flujo aquí oscurecería justo lo que esta función
tiene que dejar clarísimo. (`ValidacionesSimulacion` sigue siendo la vía para
`crear`/`actualizar`/`bifurcar`/`restaurar`, donde el 400 sí es la respuesta
correcta.)

### Test: `CuotaEfimeraTest.kt`

TDD: primero los tests. Sin mockk (función pura). Casos:

1. **Caso feliz**: `precioVenta = 190000`, sin descuento → devuelve un
   `BigDecimal` no nulo y **positivo**. Calcula el valor esperado ejecutando
   `MotorSimulacion` directamente en el propio test con los mismos parámetros
   y compara: así el test verifica que `CuotaEfimera` usa los defaults de §6.1
   y no otros, sin acoplarse a un número mágico.
2. `precioVenta = null` → `null`, y **el motor no se invoca** (no puedes
   verificarlo con mockk sobre un `object`; basta con que no lance y devuelva
   `null`).
3. **`cuota_inicial >= PV_efectivo`**: `precioVenta = 45000` → `null`.
4. **`cuota_inicial >= PV_efectivo` por el descuento**: `precioVenta = 50000`
   con `descuento = 20` (PV_efectivo = 40 000 < 45 000) → `null`.
5. **`valor_residual >= Principal`** — el caso de K32: `precioVenta = 60000`,
   sin descuento. `Principal = 60000/1.18 − 45000/1.18 ≈ 12 711`, menor que
   25 000 → **`null`, sin excepción**. Añade un comentario en el test que diga
   que este es el caso que, sin la guarda, devolvería 500 en `GET /oportunidades`.
6. `descuento = null` se trata como 0 (mismo resultado que `descuento = 0`).
7. **Ningún caso lanza**: recorre una lista de precios variados
   (`1`, `100`, `45000`, `46000`, `60000`, `110000`, `1000000`) y verifica que
   **ninguno** produce excepción — cada uno devuelve un `BigDecimal` o `null`.
   Este es el test que protege el endpoint.

**Restricciones:** no toques el motor, ni `ValidacionesSimulacion`, ni
`DefaultsSimulacion`, ni ningún Service. Solo estos dos archivos.

**Criterio de aceptación:**
```bash
./gradlew compileKotlin compileTestKotlin ktlintCheck detekt --console=plain -q --no-daemon > /tmp/f3a.log 2>&1; echo "EXIT:$?"
./gradlew test --tests '*CuotaEfimera*' --console=plain -q --no-daemon > /tmp/f3b.log 2>&1; echo "EXIT:$?"
```
ambos en EXIT:0. Reporta el archivo completo y, del test 5, el valor de
`Principal` que calculaste para justificar por qué da `null`.

---

## F4 · `SimulacionService.cuotaQuantumPorItems` + query de principales

**Modelo:** Opus 5 · **Esfuerzo:** High

Lee `plan-13` decisión **D56** y hallazgo **K34**. Abre `SimulacionService.kt`,
`SimulacionServiceImpl.kt`, `SimulacionRepository.kt`, `CuotaEfimera.kt` (F3,
cerrada) y `ArquitecturaModulosTest.kt` (qué cuenta como API pública).

### DTO de entrada — `dto/ItemParaCuota.kt`

```kotlin
/**
 * Datos minimos de un item de oportunidad para resolver su cuota Quantum
 * (§6.2). Vive en el subpaquete `dto` porque `oportunidades` lo construye y lo
 * pasa: es API publica de `simulaciones` (regla 12).
 *
 * Se pasan los datos y no solo el id a proposito: `OportunidadServiceImpl.toDtos`
 * ya tiene los items cargados, y pedirle a `simulaciones` que los relea seria
 * una consulta redundante y una llamada re-entrante
 * `oportunidades -> simulaciones -> oportunidades`.
 */
data class ItemParaCuota(
    val idItem: Long,
    val precioVenta: BigDecimal?,
    val descuento: BigDecimal?,
)
```

### Query nueva en `SimulacionRepository`

```kotlin
    /**
     * La simulacion principal de cada item, si la tiene (§6.3: una sola por
     * item, garantizada por `uq_simulacion_principal`). Por lotes: una consulta
     * para toda la pagina, nunca una por item.
     */
    fun findByIdOportunidadItemInAndEsPrincipalTrue(idsItem: Collection<Long>): List<Simulacion>
```

Método derivado de Spring Data — **no hace falta `@Query`**. Verifica que el
nombre resuelve correctamente contra las propiedades de `Simulacion`
(`idOportunidadItem`, `esPrincipal`); si Spring Data no lo acepta tal cual,
escríbelo como `@Query` JPQL parametrizada y repórtalo.

### Método nuevo en `SimulacionService`

```kotlin
    /**
     * Cuota Quantum por item (§6.2): la `cuota_final` de su simulacion
     * principal si la tiene, o el calculo efimero de §6.1 si no (sin
     * persistir nada).
     *
     * SIN chequeo de visibilidad, igual que
     * `OportunidadItemService.datosParaSimulacion` en el sentido contrario
     * (D32): quien llama —`oportunidades`— ya filtro que el usuario alcanza
     * esas oportunidades. Aplicar aqui la regla de §10 le quitaria la cuota al
     * `vendedor`, que SI debe verla en su propia oportunidad.
     *
     * Un item ausente del mapa es un item cuya cuota no se pudo calcular
     * (incompleto, o parametros por defecto invalidos para su precio, D55).
     * Eso NO es un error: es "no hay cuota que mostrar".
     */
    fun cuotaQuantumPorItems(items: Collection<ItemParaCuota>): Map<Long, BigDecimal>
```

Y en `SimulacionServiceImpl`, `@Transactional(readOnly = true)`:

1. `items` vacío → `emptyMap()` sin tocar la base.
2. Una sola llamada a `findByIdOportunidadItemInAndEsPrincipalTrue(items.map { it.idItem })`
   → `Map<idItem, cuotaFinal>`.
3. Para cada ítem: si está en ese mapa, su `cuotaFinal`; si no,
   `CuotaEfimera.calcular(item.precioVenta, item.descuento)`.
4. Descarta los `null` (`mapNotNull` / `filterValues`) y devuelve el mapa.

**Sin `permisos.*` en ninguna rama** — es lo que el KDoc promete.

### Tests: `SimulacionCuotaPorItemsTest.kt` (archivo propio)

`SimulacionServiceImplTest` ya es grande y lleva `@Suppress("LargeClass")`;
este va aparte. Mockk sobre los repositorios, `SimulacionPermisos` real.

1. Ítem **con** principal → devuelve su `cuotaFinal` exacta, y **no** se
   calcula nada efímero (el valor coincide con el de la entidad, no con el que
   daría `CuotaEfimera` para ese precio).
2. Ítem **sin** principal, con precio válido → devuelve el efímero (compáralo
   con `CuotaEfimera.calcular(...)` llamado desde el test).
3. Ítem sin principal y con `precioVenta = null` → **no aparece en el mapa**.
4. Ítem sin principal cuyo precio hace inválidos los defaults (60 000, el caso
   de K32) → **no aparece en el mapa**, sin excepción.
5. Mezcla de los cuatro casos en una sola llamada → el mapa trae exactamente
   los que sí tienen cuota.
6. Colección vacía → `emptyMap()` y `verify(exactly = 0)` sobre el repositorio.
7. **Sin N+1**: con 5 ítems, `findByIdOportunidadItemInAndEsPrincipalTrue` se
   llama **una sola vez** (`verify(exactly = 1)`).
8. **No aplica permisos**: la firma no recibe `UsuarioActual`; verifica que el
   método no consulta `SimulacionPermisos` en absoluto (usa un mock de
   `SimulacionPermisos` solo para este test y comprueba `verify(exactly = 0)`
   sobre sus métodos, o razónalo por la firma y dilo en el reporte).

**Criterio de aceptación:**
```bash
./gradlew compileKotlin compileTestKotlin ktlintCheck detekt --console=plain -q --no-daemon > /tmp/f4a.log 2>&1; echo "EXIT:$?"
./gradlew test --tests '*SimulacionCuotaPorItems*' --tests '*Arquitectura*' --console=plain -q --no-daemon > /tmp/f4b.log 2>&1; echo "EXIT:$?"
```
ambos en EXIT:0. **ArchUnit es parte del criterio**: verifica que el DTO nuevo
no rompió la frontera.

---

## F5 · §6.2 en `oportunidades`, con `@Lazy`

**Modelo:** Opus 5 · **Esfuerzo:** Extra High

Lee `plan-13` decisiones **D57 y D62** y hallazgo **K33**. Abre
`OportunidadServiceImpl.kt` (entero, en especial `toDtos`),
`OportunidadVisibilidad.kt` (el precedente de `@Lazy`, con su comentario),
`dto/OportunidadDtos.kt`, `dto/OportunidadItemDtos.kt`, y
`SimulacionService.kt` (F4, cerrada).

### La inyección

En `OportunidadServiceImpl`, añade al constructor:

```kotlin
    // `@Lazy` porque `simulaciones` ya depende de `oportunidades`
    // (OportunidadItemService.datosParaSimulacion, D32) y Spring Boot 3 rechaza
    // los ciclos de constructor; el proxy corta el ciclo al arrancar. Mismo
    // patron y mismo motivo que `OportunidadVisibilidad.tareaService`.
    @Lazy private val simulacionService: SimulacionService,
```

### Campos nuevos

`OportunidadItemDto` (+2, después de `cuotaFinanciadora`):

```kotlin
    /** Cuota Quantum del item: su simulacion principal, o el estimado de §6.1. Null si no hay ninguna calculable. */
    val cuotaQuantum: String?,
    /** `cuotaQuantum + cuotaFinanciadora`. Null cuando `cuotaQuantum` lo es (§6.2). */
    val cuotaTotal: String?,
```

`OportunidadDto` (+3, después de `montoTotal`):

```kotlin
    /** §6.2: Σ (cuota Quantum del item × cantidad). Null si algun item no aporta la suya. */
    val cuotaQuantumTotal: String?,
    /** §6.2: Σ (cuota total del item × cantidad). Null bajo la misma condicion. */
    val cuotaTotal: String?,
    /**
     * §6.2: `cuotaTotal / dias_trabajados`. El divisor es la constante 22
     * (`SimulacionService.DIAS_TRABAJADOS_POR_DEFECTO`), no el de ninguna
     * simulacion: es una cifra de nivel oportunidad y sus items pueden tener
     * simulaciones con valores distintos, asi que no hay un divisor "del item"
     * bien definido.
     */
    val cuotaDiariaTotal: String?,
```

Todos `String?` (convención de dinero del repo). Colócalos con valor por
defecto `null` si eso evita romper llamadores; si no, arréglalos (F5 incluye
cerrar la compilación de los fixtures que instancien estos DTOs).

### El divisor cruza la frontera por la interfaz

`oportunidades` **no puede** leer `DefaultsSimulacion` (K34). Expón la
constante en el `companion object` de la interfaz `SimulacionService` (una
interfaz sí es API pública para ArchUnit):

```kotlin
    companion object {
        /**
         * Divisor de `cuota_diaria` (§6.1/§6.2), espejo publico de
         * `DefaultsSimulacion.DIAS_TRABAJADOS` para que `oportunidades` pueda
         * usarlo sin cruzar hacia un `object` interno del modulo (K34).
         */
        const val DIAS_TRABAJADOS_POR_DEFECTO = DefaultsSimulacion.DIAS_TRABAJADOS
    }
```

### El ensamblado en `toDtos`

Dentro de `toDtos`, **una sola llamada por página**:

```kotlin
val itemsDeLaPagina = itemsPorOportunidad.values.flatten()
val cuotasPorItem = simulacionService.cuotaQuantumPorItems(
    itemsDeLaPagina.map { ItemParaCuota(it.id, /* precioVenta */, /* descuento */) },
)
```

**Ojo**: `OportunidadItemDto.precioVenta` y `.descuento` son `String?` (ya
formateados). Necesitas los `BigDecimal`. Revisa de dónde salen en
`OportunidadItemServiceImpl.porOportunidades` y decide la vía más limpia:
volver a parsear el `String` es feo y frágil. **Si concluyes que hace falta que
`porOportunidades` devuelva también los `BigDecimal` crudos, o un método nuevo
que los dé, detente y consulta al arquitecto** — es un cambio de la API de
`oportunidades` que no está en la lista cerrada de archivos.

Luego, por ítem:
- `cuotaQuantum = cuotasPorItem[item.id]`
- `cuotaTotal = cuotaQuantum?.add(cuotaFinanciadora)` (la `cuotaFinanciadora`
  del ítem, que ya tienes)

Y por oportunidad, aplicando **D62 literal**:
- Si **algún** ítem tiene `cuotaQuantum == null` **o** `cantidad == null` → los
  **tres** totales son `null`.
- Si no: `cuotaQuantumTotal = Σ (cuotaQuantum × cantidad)`,
  `cuotaTotal = Σ (cuotaTotal_item × cantidad)`,
  `cuotaDiariaTotal = cuotaTotal / DIAS_TRABAJADOS_POR_DEFECTO` con
  `AritmeticaFinanciera.MC` y `setScale(2, HALF_UP)` al exponer.
- Una oportunidad **sin ítems** no puede ocurrir (D17 del Plan B), pero si el
  mapa viniera vacío, los tres totales son `null`.

Todos los importes salen con `toPlainString()`.

### Tests

En `OportunidadServiceImplTest.kt` (o el archivo de tests de `toDtos` que
corresponda — localízalo):

1. Un ítem con simulación principal → `cuotaQuantum` es su `cuota_final`, y
   `cuotaTotal = cuotaQuantum + cuotaFinanciadora`.
2. Un ítem sin simulación → `cuotaQuantum` es el efímero (mockea
   `cuotaQuantumPorItems` para devolverlo).
3. **Un ítem sin cuota calculable** (no aparece en el mapa) → su
   `cuotaQuantum` y `cuotaTotal` son `null`, **y los tres totales de la
   oportunidad son `null`** aunque el otro ítem sí tenga cuota. Es la regla de
   D62 y el test que la fija.
4. Un ítem con `cantidad == null` → mismos tres totales en `null`.
5. Dos ítems con cuota y cantidad → los totales multiplican por cantidad y
   suman correctamente (usa números que hagan la aritmética verificable a
   mano, y escribe el cálculo en un comentario).
6. `cuotaDiariaTotal == cuotaTotal / 22`.
7. **Una sola llamada** a `cuotaQuantumPorItems` para toda la página, aunque
   haya varias oportunidades con varios ítems (`verify(exactly = 1)`).

**Criterio de aceptación:**
```bash
./gradlew compileKotlin compileTestKotlin ktlintCheck detekt --console=plain -q --no-daemon > /tmp/f5a.log 2>&1; echo "EXIT:$?"
./gradlew test --console=plain -q --no-daemon > /tmp/f5b.log 2>&1; echo "EXIT:$?"
```
El segundo es la suite **completa** a propósito: esta tarea cambia la forma de
`OportunidadDto`, que muchos tests construyen, y además introduce el `@Lazy`
que podría romper el arranque del contexto de Spring en los `*WebMvcTest`. Si
el contexto no levanta por un ciclo de beans, **es un fallo real de esta
tarea**, no ruido: repórtalo con el mensaje exacto.

---

## F6 · `registrarEvento(idUsuario)` + `purgarHuerfanas`

**Modelo:** Opus 5 · **Esfuerzo:** High

Lee `plan-13` decisión **D60**, `reglas_simulaciones.md` §5 y §7, y el
hallazgo **K15** del mapa de Plan D (el CHECK del snapshot). Abre
`SimulacionServiceImpl.kt` (el privado `registrarEvento` y sus **cuatro**
llamadores), `SimulacionLog.kt` (el KDoc de `created_by`) y
`SimulacionRepository.kt`.

### Cambio de firma de `registrarEvento`

`usuario: UsuarioActual` → **`idUsuario: Long?`**. `simulacion_log.created_by`
es nullable exactamente para esto: su KDoc en V43 dice *"NULL cuando el evento
lo genera un job programado sin actor humano (p. ej. la purga a 30 días)"*.

Los cuatro llamadores existentes (`crear`, `actualizar`, `restaurar` ×2,
`bifurcar`) pasan `usuario.id`. **No cambies nada más de esos métodos.**

### Query nueva en `SimulacionRepository`

```kotlin
    /**
     * Simulaciones huerfanas mas antiguas que `limite` (§5: 30 dias sin
     * enlazar). Parametrizada, sin concatenacion (regla 11).
     */
    fun findByIdOportunidadItemIsNullAndCreatedAtBefore(limite: LocalDateTime): List<Simulacion>
```

Derivado de Spring Data; si no resuelve, `@Query` JPQL parametrizada.

### Método nuevo en `SimulacionService`

```kotlin
    /**
     * Purga de §5: hard delete de las simulaciones sin item creadas antes de
     * `limite`. Registra el evento `eliminada` con snapshot completo ANTES de
     * borrar cada una — el log sobrevive (`id_simulacion` no tiene FK, por eso
     * mismo). Devuelve cuantas elimino.
     *
     * Sin `UsuarioActual`: la ejecuta un job programado, y `created_by` del
     * evento queda en null.
     */
    fun purgarHuerfanas(limite: LocalDateTime): Int
```

`@Transactional` — el log y el borrado de cada fila van juntos.

Implementación: traer las huérfanas, y por cada una
`registrarEvento(simulacion, item = null, idUsuario = null,
TipoEventoSimulacion.eliminada, LocalDateTime.now())` **y luego**
`simulacionRepository.delete(simulacion)`. Devolver el tamaño de la lista.

`item = null` es correcto: son huérfanas por definición, no tienen ítem, así
que `idOportunidadItem` e `idOportunidad` del log van en null. El CHECK para
`eliminada` solo exige el snapshot de los 7 campos, que `registrarEvento` ya
arma.

**No implementes el job** (es F7). **No toques** `crear`, `detalle`, `listar`,
`actualizar`, `eliminar`, `cronograma`, `historial`, `restaurar`,
`marcarPrincipal`, `bifurcar` más allá del cambio mecánico de firma.

### Tests (añadir a `SimulacionServiceImplTest.kt`)

1. Dos huérfanas antiguas → se registran **dos** eventos `eliminada` y se
   borran **las dos**; devuelve `2`.
2. `verifyOrder` sobre una: el `save` del log ocurre **antes** del `delete`.
3. El evento `eliminada` lleva el snapshot completo (los 7 campos del CHECK no
   nulos) y **`createdBy == null`**.
4. Sin huérfanas → devuelve `0`, sin registrar ni borrar nada.
5. Los cuatro llamadores existentes siguen registrando con `createdBy` del
   usuario (basta con que sus tests previos sigan pasando **sin tocarlos** —
   dilo en el reporte).

**Criterio de aceptación:**
```bash
./gradlew compileKotlin compileTestKotlin ktlintCheck detekt --console=plain -q --no-daemon > /tmp/f6a.log 2>&1; echo "EXIT:$?"
./gradlew test --tests '*SimulacionServiceImpl*' --console=plain -q --no-daemon > /tmp/f6b.log 2>&1; echo "EXIT:$?"
```
ambos en EXIT:0.

---

## F7 · `avisarPorExpirar` + `PurgaSimulacionesJob`

**Modelo:** Opus 5 · **Esfuerzo:** High

Lee `plan-13` decisiones **D58 y D61** y hallazgo **K35**. Abre
`reglas_simulaciones.md` §5, `domain/notificaciones/NotificacionService.kt`
(la firma de `notificar`), `NotificacionEnums.kt` (F1, ya con los dos valores
nuevos), y **`domain/notificaciones/jobs/LimpiezaNotificacionesJob.kt`** como
plantilla exacta de estilo de job (`@Component`, `@Scheduled(cron = …)`,
`Clock` inyectado).

### Query nueva en `SimulacionRepository`

```kotlin
    /**
     * Huerfanas cuya antiguedad cruza la frontera del preaviso en esta corrida
     * (D58): `created_at` en `(desde, hasta]`. La ventana es de 24 h y el job
     * corre cada 24 h, asi que cada simulacion cae en ella UNA sola vez en
     * toda su vida: no hace falta recordar que se aviso.
     */
    fun findByIdOportunidadItemIsNullAndCreatedAtBetween(
        desde: LocalDateTime,
        hasta: LocalDateTime,
    ): List<Simulacion>
```

Cuidado con la semántica de `Between` en Spring Data (**inclusiva en ambos
extremos**). Si eso te incomoda por el solape de un instante, usa una `@Query`
JPQL con `> :desde AND <= :hasta` y dilo en el reporte; cualquiera de las dos
es aceptable, no hace falta consultar.

### Método nuevo en `SimulacionService`

```kotlin
    /**
     * Aviso de §5: notifica al creador de cada simulacion huerfana que cruza
     * la frontera de los 3 dias previos al borrado. Devuelve cuantas notifico.
     *
     * Sin `UsuarioActual`: lo ejecuta un job, y `notificar` recibe
     * `idActor = null` (nadie se excluye del set de destinatarios).
     */
    fun avisarHuerfanasPorExpirar(desde: LocalDateTime, hasta: LocalDateTime): Int
```

`@Transactional`. Por cada simulación de la ventana:

```kotlin
notificacionService.notificar(
    destinatarios = setOf(simulacion.createdBy),
    idActor = null,
    tipo = TipoNotificacion.simulacion_por_expirar,
    mensaje = "…",
    entidadTipo = EntidadNotificacion.simulacion,
    entidadId = requireNotNull(simulacion.id),
)
```

El mensaje debe decir cuántos días faltan y qué hacer para evitarlo (enlazarla
a una oportunidad), en el tono de los mensajes que ya existen en el repo —
míralos antes de redactarlo. Inyecta `NotificacionService` en
`SimulacionServiceImpl` (interfaz pública de otro módulo: legal, regla 12).

**No registres ningún evento en `simulacion_log`**: avisar no es un cambio de
estado de la simulación, y el enum no tiene un tipo para ello.

### El job — `domain/simulaciones/jobs/PurgaSimulacionesJob.kt`

`@Component`, con `Clock` inyectado (default `Clock.systemUTC()`), igual que
`LimpiezaNotificacionesJob`. Dos métodos `@Scheduled`:

```kotlin
    /** Aviso 3 dias antes del borrado (§5). Corre ANTES que la purga del mismo dia. */
    @Scheduled(cron = "0 0 5 * * *")
    fun avisar() {
        val ahora = LocalDateTime.now(clock)
        simulacionService.avisarHuerfanasPorExpirar(
            desde = ahora.minusDays(DIAS_RETENCION - DIAS_AVISO + 1),   // 28
            hasta = ahora.minusDays(DIAS_RETENCION - DIAS_AVISO),       // 27
        )
    }

    /** Hard delete de las huerfanas de mas de 30 dias (§5). */
    @Scheduled(cron = "0 30 5 * * *")
    fun purgar() {
        simulacionService.purgarHuerfanas(LocalDateTime.now(clock).minusDays(DIAS_RETENCION))
    }

    private companion object {
        const val DIAS_RETENCION = 30L
        const val DIAS_AVISO = 3L
    }
```

Verifica tú la aritmética de la ventana antes de escribirla: `desde` debe ser
**más antiguo** que `hasta`, y `hasta` debe caer en el día 27 de vida. Ajusta
los `minusDays` si mi cálculo de arriba no cuadra — **es tu responsabilidad
que la ventana sea de 24 h y caiga en el día 27**, y decir en el reporte con
qué números lo dejaste.

KDoc del job, obligatorio: la limitación de D58 — *si el job no corre un día,
las simulaciones de esa ventana se quedan sin aviso y aun así se purgan a los
30 días; es un aviso, no una garantía transaccional, y el precio a cambio es no
ampliar la API pública de `notificaciones` ni añadir un tercer enum de
recordatorio.*

### Tests: `PurgaSimulacionesJobTest.kt`

Con un `Clock.fixed(...)` para que las fechas sean deterministas, mockk sobre
`SimulacionService`.

1. `avisar()` llama a `avisarHuerfanasPorExpirar` con una ventana de
   **exactamente 24 h** que termina en el día 27 antes de "ahora".
2. `purgar()` llama a `purgarHuerfanas` con el límite en el día 30 antes de
   "ahora".
3. Los dos `cron` están declarados y no colisionan con los de
   `LimpiezaNotificacionesJob` (03:00) ni el de tipo de cambio (14:30) —
   verifícalo leyendo esos dos archivos y dilo en el reporte.

Y añade a `SimulacionServiceImplTest.kt`:

4. `avisarHuerfanasPorExpirar` notifica **una vez por simulación**, con
   `destinatarios = setOf(createdBy)`, `idActor = null`,
   `tipo = simulacion_por_expirar`, `entidadTipo = simulacion`,
   `entidadId = id de la simulacion`.
5. Ventana vacía → devuelve `0` y **no** llama a `notificar`.
6. **No** registra ningún evento en `simulacion_log`
   (`verify(exactly = 0) { simulacionLogRepository.save(any()) }`).

**Criterio de aceptación:**
```bash
./gradlew compileKotlin compileTestKotlin ktlintCheck detekt --console=plain -q --no-daemon > /tmp/f7a.log 2>&1; echo "EXIT:$?"
./gradlew test --tests '*PurgaSimulaciones*' --tests '*SimulacionServiceImpl*' --console=plain -q --no-daemon > /tmp/f7b.log 2>&1; echo "EXIT:$?"
```
ambos en EXIT:0.

---

## F8 · `contrato_api.md`: secciones nuevas, renumeración, enums, changelog

**Modelo:** Sonnet 5 · **Esfuerzo:** Extra High

Lee `plan-13` decisión **D64** y hallazgos **K36 y K37**. Abre
`docs/contrato_api.md` **entero** (2445 líneas) — en particular §22 (Tipo de
cambio) como plantilla de sección de módulo reciente, §24 (Enums) y §26
(Changelog). Abre también los tres controllers para documentar lo que el código
hace de verdad, no lo que el plan supone:
`SimulacionController.kt`, `CalculadoraFinancieraController.kt`, y los DTOs de
`dto/SimulacionDtos.kt`.

Esta tarea **no toca ningún `.kt`**.

### 1. Renumeración (D64)

| Antes | Después |
|---|---|
| — | **§23 Simulaciones** (nueva) |
| — | **§24 Calculadora Financiera** (nueva) |
| §23 Mantenimiento | §25 |
| §24 Enums | §26 |
| §25 Notas operativas — Drive | §27 |
| §26 Changelog del contrato | §28 |

Y arregla las **tres** referencias cruzadas, **incluida la que ya estaba rota
antes de este plan**:

- `CLAUDE.md:136`: *"contrato_api.md §25 Changelog del contrato"* → **§28**.
  Estaba rota: §25 es hoy "Notas operativas — Drive". *(Sí, esta es la única
  línea de `CLAUDE.md` que tocas en esta tarea; el resto es F9.)*
- En la sección de Drive (antes §25, ahora §27), primera línea: *"§23
  Mantenimiento"* → **"§25 Mantenimiento"**.
- En el changelog (antes §26, ahora §28): *"las notas de §25"* → **"§27"**.

Busca cualquier otra referencia con `grep -n "§2[2-8]" docs/contrato_api.md` y
revísalas una por una antes de dar la renumeración por hecha.

### 2. §23 Simulaciones

Documenta los **10 endpoints** que `SimulacionController` expone hoy. Léelos
del código, no de este plan:

`POST /simulaciones` · `GET /simulaciones` · `GET /simulaciones/:id` ·
`GET /simulaciones/:id/cronograma` · `PATCH /simulaciones/:id` ·
`DELETE /simulaciones/:id` · `GET /simulaciones/:id/historial` ·
`POST /simulaciones/:id/restaurar` · `POST /simulaciones/:id/bifurcar` ·
`PATCH /simulaciones/:id/principal`

Para cada uno, el formato de §22: verbo + ruta, una línea de qué hace,
**Roles**, Request (body/query params con sus nombres snake_case reales),
Respuesta de ejemplo con el envelope `{data, meta, error}`, y Notas.

Puntos que la sección **debe** dejar dichos, porque no se deducen de las firmas:
- `cuota_final` es **solo lectura**: se calcula server-side y se ignora si
  viene en el body (§4).
- `modo` es **inmutable**: un `PATCH` que lo cambie responde **409
  `MODO_INMUTABLE`**; para cambiarlo está `POST /:id/bifurcar`.
- El listado del módulo es **403 para `vendedor`, `jdv` y `otro`** (§10), pero
  el detalle y la escritura sí son accesibles al `vendedor` sobre las suyas.
  Recurso ajeno → **404**, nunca 403.
- El historial es una **ventana de 7 días / 15 versiones** (§7.2), no la
  bitácora completa; y su `diff` puede venir **vacío** legítimamente (un PATCH
  que solo reenlaza no cambia ninguno de los 10 parámetros).
- `POST /:id/restaurar` **recalcula** `cuota_final`; no restaura la que
  estuviera guardada en esa versión.
- `eliminacion_prevista_el` solo viene con valor si la simulación no está
  enlazada a un ítem (§5).

### 3. §24 Calculadora Financiera

`POST /calculadora`, un solo endpoint. Debe decir explícitamente:
**no persiste nada** (§9), ni siquiera auditoría; y que "Enlazar a Oportunidad"
**no es un endpoint de esta sección**: es `POST /simulaciones` con los mismos
parámetros más el `id_oportunidad_item` elegido.

### 4. Tabla de enums (ahora §26)

Añade las filas que faltan — dos de ellas son **deuda preexistente** (K37):

| Enum | Usado en | Valores |
|---|---|---|
| `modo_simulacion_enum` | `Simulacion.modo` | `leasing`, `credito_directo` |
| `tipo_evento_simulacion_enum` | `SimulacionLog.tipo_evento` | `creada`, `editada`, `restaurada`, `marcada_principal`, `enlazada_a_item`, `eliminada` |

Y **añade los dos valores nuevos** de F1 a las filas que ya existen:
`tipo_notificacion_enum` += `simulacion_por_expirar`;
`entidad_notificacion_enum` += `simulacion`.

### 5. Changelog (ahora §28)

Una entrada **Non-breaking** con la fecha de hoy, que cubra de una vez:
- Los 11 endpoints nuevos de `/simulaciones` y `/calculadora` (Planes D, E y F).
- Los **campos nuevos** en `GET /oportunidades` y su detalle:
  `cuota_quantum_total`, `cuota_total`, `cuota_diaria_total`, y en cada ítem
  `cuota_quantum` y `cuota_total`. Aclara que **son `null` si algún ítem no
  tiene cuota calculable** — no es un error, y el cliente debe tratarlo.
- Los dos valores de enum nuevos.

Sin acción requerida para el frontend salvo adoptar los campos cuando quiera.

**Criterio de aceptación:** no hay build que correr. Relee el documento
completo tras editarlo y confirma en tu reporte: (a) que no queda ninguna
referencia a una sección con el número viejo, (b) que los 11 endpoints
documentados coinciden **uno a uno** con los que exponen los dos controllers
(lístalos desde el código y compara), y (c) que ninguna ruta o nombre de campo
que escribiste es inventado.

---

## F9 · `matriz_permisos.md` + `CLAUDE.md`

**Modelo:** Sonnet 5 · **Esfuerzo:** Medium

Lee `plan-13` hallazgo **K38**. Abre `docs/matriz_permisos.md` entero (§2.13
Metas de venta y §2.14 Mantenimiento como plantilla) y `CLAUDE.md` entero.
**No toques ningún `.kt`.**

### `matriz_permisos.md` — nueva §2.15 Simulaciones

Con la tabla de §10 de `reglas_simulaciones.md`, tal cual:

| Rol | Módulo Simulaciones | Simulador en su oportunidad | Calculadora Financiera |
|---|---|---|---|
| `admin` | Total | Sí | Sí |
| `analista` | **Total** | Sí | Sí |
| `gerencia` | Total | Sí | Sí |
| `vendedor` | **Sin acceso** | Solo donde es el vendedor asignado | Sí |
| `jdv`, `otro` | Sin acceso | No | No |

Y debajo, en prosa, los dos puntos que hacen a este módulo distinto de todos
los demás del documento — están en §10 y valen la pena escribirlos aquí:
- **`analista` es de solo lectura en oportunidades pero tiene escritura
  completa en simulaciones**: es el rol dueño del módulo.
- **`jdv` es supervisor en oportunidades pero no tiene acceso aquí.**

Añade una nota de implementación: la decisión vive centralizada en
`SimulacionPermisos`, **no** en los predicados compartidos de `UsuarioActual`
(`esRolApoyo` agrupa `analista` con `otro`, que aquí están en extremos
opuestos; `esSupervisor` incluye a `jdv`, que aquí no entra).

Si §4 ("Casos especiales") merece una entrada sobre este reparto, añádela; si
no, no fuerces nada.

### `CLAUDE.md`

1. **Inventario de módulos** (línea ~46): "los 15 que existen hoy" → **17**, y
   añade `simulaciones` y `tipocambio` a la lista, **en orden alfabético** con
   los demás. Verifica el número contando los directorios reales de
   `src/main/kotlin/pe/quantum/crm/domain/` — no confíes en mi cuenta.
2. **Tabla de documentos de referencia**: añade una fila para
   `reglas_simulaciones.md` — *"Antes de tocar el módulo de simulaciones. La
   fuente de verdad de su comportamiento: modos, motor de cálculo, purga,
   cuota en la oportunidad, permisos"*.
3. **Sección "Fuera del MVP"**: hoy no menciona simulaciones. Si el texto da a
   entender que algo de este módulo está fuera de alcance, corrígelo con el
   mismo tono que ya se usó para notificaciones (*"Notificaciones YA NO está
   fuera de alcance…"*). Si no lo menciona en absoluto, **no inventes una
   entrada**: limítate a comprobarlo y decirlo en el reporte.
4. **NO toques la línea 136** (la referencia al changelog): esa la arregla F8.
   Si al llegar aquí ya dice §28, F8 hizo su trabajo; si dice §25, **repórtalo**
   — significa que F8 se saltó su punto 1.

**Criterio de aceptación:** ambas ediciones hechas y coherentes con el código
real. Reporta el diff completo y la cuenta de módulos que verificaste con `ls`.

---

## F10 · Verificación de build completa (local, sin `integrationTest`)

**Modelo:** Sonnet 5 · **Esfuerzo:** Low

No edites código.

```bash
./gradlew ktlintCheck --console=plain -q --no-daemon > /tmp/f10_lint.log 2>&1; echo "EXIT:$?"
./gradlew detekt      --console=plain -q --no-daemon > /tmp/f10_detekt.log 2>&1; echo "EXIT:$?"
./gradlew test        --console=plain -q --no-daemon > /tmp/f10_test.log 2>&1; echo "EXIT:$?"
```

**No ejecutes `integrationTest` ni `koverVerify`** (Docker 29).

Esta tarea **no puede** confirmar:
- Que la migración de F1 aplica limpio contra un Postgres real.
- El trinquete de cobertura Kover (85 % / 84 %).

**Repórtalo como limitación de primer orden.** Si alguno de los tres falla, no
lo arregles: reporta el fallo exacto y detente.

**Criterio de aceptación:** los tres en EXIT:0 y `git status --short` sin
archivos modificados por ti. Reporta además el total de archivos nuevos y
modificados de todo el ciclo (Planes D + E + F) para la auditoría.

---

## F11 · Auditoría final del diff contra los documentos citados

**Modelo:** Opus 5 · **Esfuerzo:** High

Exigida por `CLAUDE.md`. **Auditoría del diff completo de F1-F10, no un
resumen. No arregles nada. No hagas commit.**

Contrasta contra: `plan-13-mapa-cierre-simulaciones.md` (K31-K39, D54-D65) ·
`plan-09` y `plan-11` (vigentes) · `reglas_simulaciones.md` §5, §6, §8.2, §10,
§13 · `Instrucciones_simulaciones.md` (restricciones no negociables) ·
`CLAUDE.md` reglas 1, 8, 9, 10, 11, 12, 14 · `V43` y la migración de F1.

Verifica, uno por uno:

1. **Contradicciones con documentación ya vigente y correcta.**
2. **K32/D55 — la cuota efímera no puede lanzar (BLOQUEANTE si falla)**: lee
   `CuotaEfimera.calcular` entero. Debe devolver `null` en los tres casos y
   tener el motor envuelto en `runCatching`. Confirma que existe el test que
   recorre precios variados sin que ninguno lance. Un `throw` alcanzable desde
   aquí es un 500 en `GET /oportunidades`.
3. **D54 — el modo de la cuota efímera está declarado como constante con
   nombre**, con el comentario de que §6.1 no lo especifica.
4. **Trampa 2 — `CuotaEfimera` NO usa `DefaultsSimulacion.VALOR_RESIDUAL`**
   (que es 0, de columna) sino su propia constante de §6.1 (25 000).
   `grep -n "DefaultsSimulacion" src/main/kotlin/pe/quantum/crm/domain/simulaciones/CuotaEfimera.kt`
   debería salir vacío o justificado.
5. **D62 — los tres totales son `null` si algún ítem no aporta**: lee el
   ensamblado en `toDtos` y su test. Un total parcial es un hallazgo.
6. **D62 — el divisor de `cuotaDiariaTotal` es 22**, vía la constante pública
   de `SimulacionService`, no un literal en `oportunidades`.
7. **K34 — `oportunidades` no lee `object`s internos de `simulaciones`**:
   `grep -n "DefaultsSimulacion\|ValidacionesSimulacion\|CuotaEfimera" src/main/kotlin/pe/quantum/crm/domain/oportunidades/`
   debe salir **vacío**. Y `./gradlew test --tests '*Arquitectura*'` pasa.
8. **D57 — el `@Lazy` está y el contexto levanta**: los `*WebMvcTest` pasan.
9. **D56 — `cuotaQuantumPorItems` no aplica permisos**: no hay ninguna llamada
   a `permisos.*` en ese método.
10. **D60 — la purga registra antes de borrar**, y su evento lleva
    `createdBy == null`.
11. **D58 — la ventana del aviso es de 24 h** y el job no usa
    `recordatorios_enviados` ni ningún repositorio de `notificaciones`:
    `grep -n "RecordatorioEnviado\|recordatorioEnviado" src/main/kotlin/pe/quantum/crm/domain/simulaciones/`
    debe salir vacío.
12. **D61 — los dos cron no colisionan** con `LimpiezaNotificacionesJob`
    (03:00) ni con el de tipo de cambio (14:30), y el aviso corre antes que la
    purga.
13. **K39 — `SchemaMigrationIntegrationTest.kt` SIN modificar**, y
    `SeedFixtures` en 46/47.
14. **La migración solo añade valores de enum**: no crea tablas, no altera
    columnas, no toca `origen_recordatorio_enum`.
15. **K36/D64 — la renumeración del contrato no dejó referencias rotas**:
    `grep -n "§2[2-8]" docs/contrato_api.md CLAUDE.md` y revísalas una por una.
    Presta atención especial a `CLAUDE.md:136`, que **estaba rota antes** de
    este plan: debe apuntar ahora a §28.
16. **K37 — los enums de simulaciones están en la tabla del contrato.**
17. **K38 — `CLAUDE.md` dice 17 módulos** y los lista, e incluye
    `reglas_simulaciones.md` en la tabla de documentos.
18. **Los endpoints documentados coinciden con el código**: lista los
    `@GetMapping`/`@PostMapping`/`@PatchMapping`/`@DeleteMapping` de los dos
    controllers y compáralos uno a uno con lo escrito en §23/§24. Una ruta
    documentada que no existe (o al revés) es un hallazgo.
19. **Restricciones no negociables del encargo**: nada derivable persistido
    (cronograma, diff, nombre autogenerado, cuota efímera, los totales de
    §6.2); `cuota_final` nunca aceptada del cliente; la Calculadora sigue sin
    escribir; sin fila extra para el balloon; sin dependencias nuevas en
    `build.gradle.kts`.
20. **Honestidad sobre `integrationTest`**: ningún reporte de F1-F10 afirma
    haber verificado en verde la migración contra Postgres real ni el trinquete
    de cobertura.
21. **TDD (regla 1)**: `CuotaEfimeraTest`, `SimulacionCuotaPorItemsTest`,
    `PurgaSimulacionesJobTest` existen y cubren los casos exigidos.

**Entregable:** informe con hallazgos en *bloqueante / menor / ninguno*, cada
uno con archivo, línea y la regla, decisión o sección que contradice.

---

## Cierre del plan

Al terminar F11, **para y resume**. **No abras PR ni hagas commit** — lo decide
el arquitecto.

Estado esperado al cerrar Plan F: **el encargo de
`Instrucciones_simulaciones.md` queda completo.** Las 6 fases cerradas, las
secciones de `reglas_simulaciones.md` implementadas o explícitamente fuera de
backend (§11), y los tres documentos de contrato al día. La migración de F1
queda escrita y verificada localmente pero **sin aplicar a producción** hasta
que el PR tenga CI en verde (F12, coordinada aparte).
