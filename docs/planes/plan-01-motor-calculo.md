# Plan 1 — Motor de cálculo de simulaciones (fase 1)

> **Destinatario: agentes ejecutores, no humanos.** Cada tarea es autocontenida.
> Ejecutar en orden estricto: cada una asume que la anterior está cerrada y verde.
>
> **Regla para el ejecutor:** si algo de tu tarea es ambiguo, contradice a otra tarea,
> o el repo no coincide con lo que la tarea describe — **detente y consulta al
> arquitecto**. No infieras, no inventes, no "arregles" de paso nada que la tarea no
> te pida. Esto es producción con usuarios reales.

---

## Fase de investigación (leer antes de la Task 1)

### Documentos que gobiernan este plan y qué dicen exactamente

| Documento | Qué manda sobre este cambio |
|---|---|
| `docs/reglas_simulaciones.md` §3.1 | `BigDecimal` en todo, **nunca `Double`**. Redondeo a 2 decimales **solo al mostrar o guardar, jamás entre meses**. La TNM **no se redondea nunca**. La función es pura y determinística |
| `docs/reglas_simulaciones.md` §3.2 | Fórmulas comunes: `PV_efectivo`, `VV`, `IGV`, `TNM`, `CuotaFin`. `PMT` con convención Excel y su **equivalente algebraico**, que es el que se implementa |
| `docs/reglas_simulaciones.md` §3.3 | Leasing: base sin IGV, `cuota_final = CuotaFin × 1.18`, 7 columnas, **sin columna de IGV** |
| `docs/reglas_simulaciones.md` §3.4 | Crédito directo: base con IGV, `cuota_final` = promedio de cuotas con IGV de intereses, 8 columnas. Divisor = `plazo_meses` real, **nunca 48 fijo** |
| `docs/reglas_simulaciones.md` §3.5 | `\|SaldoFinal(n) − valor_residual\| < 0.01` o **error**. **Nunca** se fuerza la última amortización ni se agrega fila extra |
| `docs/reglas_simulaciones.md` §3.6 | Los dos casos dorados. **Fixture obligatorio**, al centavo |
| `docs/TESTING-backend.md` §2, §9, §11 | TDD Red-Green-Refactor. Nombres de test que describen comportamiento. **Nunca código de producción sin un test que falle primero** |
| `CLAUDE.md` | Convenciones de arquitectura, estilo y build |
| `docs/planes/plan-00-mapa-simulaciones.md` | Decisiones D1 (dónde vive el motor), D2 (algoritmo de la raíz), D5 (convenciones heredadas) |

### Reglas de `CLAUDE.md` que tocan este cambio

| Regla | Texto | Cómo aplica aquí |
|---|---|---|
| **1. TDD siempre** | *"Escribe el test que falla ANTES del código"* | Estructural en este plan: T4 escribe los tests dorados **en rojo**, y T5/T6 los ponen en verde. T4 **no debe** implementar nada |
| **8. Inyección por constructor** | Nunca `@Autowired` en campo | El motor no tiene dependencias: es un `object` de Kotlin sin estado. No hay nada que inyectar |
| **12. Frontera entre módulos** | Verificada por ArchUnit sobre bytecode | Por eso el motor va a `shared/` y no a `domain/simulaciones/`. Ver D1 del mapa. **Ninguna tarea de este plan crea nada bajo `domain/`** |

### Reglas que **no** aplican y por qué (para que nadie las fuerce)

- **Regla 2** (`monto_total` se calcula, no se acepta): es de `oportunidades`. Su análogo
  aquí es `cuota_final`, pero ese guard vive en el Service (fase 2), no en el motor.
- **Reglas 3, 4, 5, 6, 7** (estado de cartera, eventos, motivo de cierre, facturado,
  estado `perdido`): pipeline de oportunidades. Nada que ver con este plan.
- **Reglas 9, 10, 11** (JPA `LAZY`, `@Transactional`, queries parametrizadas): **este
  plan no toca JPA ni base de datos**. Si tu tarea te lleva a escribir una entidad, un
  repositorio o una query, la has entendido mal: **detente y consulta**.
- **Regla 14** (IDOR → 404): pertenece a los endpoints, fase 3.

### Alcance — lo que este plan NO hace

Sin entidades JPA · sin repositorios · sin Service · sin controllers · sin DTOs de API ·
sin migraciones · sin tocar `docs/migrations/V40__create_simulaciones.sql` · sin tocar
ninguna tabla · sin añadir dependencias a `build.gradle.kts` · sin tocar `domain/`.

**Archivos que este plan puede crear o modificar, lista cerrada:**

```
src/main/kotlin/pe/quantum/crm/shared/enums/ModoSimulacion.kt          (nuevo)
src/main/kotlin/pe/quantum/crm/shared/simulacion/AritmeticaFinanciera.kt (nuevo)
src/main/kotlin/pe/quantum/crm/shared/simulacion/ParametrosSimulacion.kt (nuevo)
src/main/kotlin/pe/quantum/crm/shared/simulacion/ResultadoSimulacion.kt  (nuevo)
src/main/kotlin/pe/quantum/crm/shared/simulacion/MotorSimulacion.kt      (nuevo)
src/main/kotlin/pe/quantum/crm/shared/exception/NegocioExceptions.kt     (modificar: añadir 1 excepción)
src/test/kotlin/pe/quantum/crm/shared/simulacion/*.kt                    (nuevos)
```

Cualquier otro archivo: **detente y consulta**.

---

## Tabla de tareas

| ID | Tarea | Modelo | Esfuerzo |
|---|---|---|---|
| T1 | Enum `ModoSimulacion` | Sonnet 5 | Low |
| T2 | Tipos de entrada y salida del motor | Sonnet 5 | Medium |
| T3 | `AritmeticaFinanciera` + sus tests | Opus 5 | High |
| T4 | Tests de los casos dorados **en rojo** | Opus 5 | Extra High |
| T5 | Implementar el motor hasta poner T4 en verde | Opus 5 | Extra High |
| T6 | Tests de casos borde y de error | Sonnet 5 | Medium |
| T7 | Verificación de build completa | Sonnet 5 | Low |
| T8 | Revisión final del diff contra los documentos citados | Opus 5 | High |

---

## T1 · Enum `ModoSimulacion`

**Modelo:** Sonnet 5 · **Esfuerzo:** Low

**Archivo único a crear:** `src/main/kotlin/pe/quantum/crm/shared/enums/ModoSimulacion.kt`

Crea el enum que refleja el tipo nativo `modo_simulacion_enum` de PostgreSQL, definido
en `docs/migrations/V40__create_simulaciones.sql` línea 21.

Contenido exacto:

```kotlin
package pe.quantum.crm.shared.enums

/**
 * Valores de `modo_simulacion_enum` (migracion V40). En minuscula a proposito:
 * deben coincidir con las etiquetas del enum nativo de PostgreSQL, que Hibernate
 * mapea por nombre via `@JdbcTypeCode(NAMED_ENUM)`.
 *
 * `modo` es INMUTABLE tras la creacion (reglas_simulaciones.md §2): leasing y
 * credito directo usan formulas y columnas de cronograma distintas.
 */
@Suppress("ktlint:standard:enum-entry-name-case", "EnumNaming", "EnumEntryName")
enum class ModoSimulacion {
    leasing,
    credito_directo,
}
```

**Restricciones**
- No añadas métodos, propiedades ni `companion object`. Solo los dos valores.
- No crees el archivo en `domain/`. Va en `shared/enums/`, junto a `EstadoMeta.kt`.

**Criterio de aceptación:** `./gradlew compileKotlin ktlintCheck` pasa.

---

## T2 · Tipos de entrada y salida del motor

**Modelo:** Sonnet 5 · **Esfuerzo:** Medium

Data classes puras, **sin lógica** — ni cálculos, ni validaciones, ni `init {}`.
La lógica entra en T5.

### Archivo 1: `src/main/kotlin/pe/quantum/crm/shared/simulacion/ParametrosSimulacion.kt`

```kotlin
package pe.quantum.crm.shared.simulacion

import pe.quantum.crm.shared.enums.ModoSimulacion
import java.math.BigDecimal

/**
 * Entrada del motor de calculo: los campos esenciales de una simulacion
 * (reglas_simulaciones.md §3.2). Estructura pura, sin dependencias de Spring,
 * JPA ni framework: la consumen dos flujos, el que persiste y la Calculadora
 * Financiera, que no persiste nada (§9).
 *
 * `precio_venta` es UNITARIO y CON IGV. La cantidad de unidades del item NO
 * participa del calculo (§3.2).
 *
 * `tea` va en escala 1-100 (15.00 = 15%). OJO: `financiadoras.tea` usa escala
 * fraccionaria (0.15). NO se comparan ni se copian sin convertir.
 */
data class ParametrosSimulacion(
    val modo: ModoSimulacion,
    val precioVenta: BigDecimal,
    val descuento: BigDecimal,
    val cuotaInicial: BigDecimal,
    val plazoMeses: Int,
    val tea: BigDecimal,
    val valorResidual: BigDecimal,
)
```

### Archivo 2: `src/main/kotlin/pe/quantum/crm/shared/simulacion/ResultadoSimulacion.kt`

Dos data classes en este archivo.

`FilaCronograma` cubre las columnas de **ambos** modos (§3.3 y §3.4). Los campos que
no aplican a un modo van `null`; el motor decide cuáles:

- **Leasing** (7 columnas, §3.3): `igv` siempre `null`. Se expone `cuotaConIgv`.
- **Crédito directo** (8 columnas, §3.4): `igv` poblado. `cuotaConIgv` transporta la
  "Cuota con IGV de Intereses".
- **Mes 0** en ambos modos: `interes`, `igv`, `cuota` y `cuotaConIgv` van `null`
  (§3.3/§3.4 lo marcan como *"Cuota = (vacío)"*).

```kotlin
package pe.quantum.crm.shared.simulacion

import java.math.BigDecimal

/**
 * Una fila del cronograma. Cubre las columnas de ambos modos
 * (reglas_simulaciones.md §3.3 y §3.4); los campos que no aplican van null.
 *
 * Todos los importes llegan YA redondeados a 2 decimales: el redondeo se aplica
 * solo al exponer, nunca entre meses (§3.1).
 *
 * Mes 0 es la fila de la cuota inicial: sin interes, sin IGV y sin cuota.
 */
data class FilaCronograma(
    val mes: Int,
    val saldoInicial: BigDecimal,
    val amortizacion: BigDecimal,
    val interes: BigDecimal?,
    val igv: BigDecimal?,
    val saldoFinal: BigDecimal,
    val cuota: BigDecimal?,
    val cuotaConIgv: BigDecimal?,
)

/**
 * Salida del motor: la cuota que se le muestra al cliente mas el cronograma
 * completo. Nada de esto se persiste salvo `cuotaFinal` (§4).
 *
 * `cuotaFinanciera` es la CuotaFin de §3.2, antes del ajuste por modo; se expone
 * porque la propuesta la muestra y porque hace verificable el calculo.
 */
data class ResultadoSimulacion(
    val cuotaFinal: BigDecimal,
    val cuotaFinanciera: BigDecimal,
    val valorVenta: BigDecimal,
    val igv: BigDecimal,
    val principal: BigDecimal,
    val tasaNominalMensual: BigDecimal,
    val cronograma: List<FilaCronograma>,
)
```

### Archivo 3: modificar `src/main/kotlin/pe/quantum/crm/shared/exception/NegocioExceptions.kt`

Añade **al final** del archivo, sin tocar nada de lo que ya está:

```kotlin
/**
 * El saldo final del ultimo mes no coincide con `valor_residual`
 * (reglas_simulaciones.md §3.5). Con precision completa el residuo es del orden
 * de 1e-30, asi que superar la tolerancia de 0.01 significa que hay un bug en el
 * motor: se devuelve error, nunca un cronograma silenciosamente incorrecto.
 */
class CronogramaInconsistenteException(
    message: String,
) : ApiException(
        code = "CRONOGRAMA_INCONSISTENTE",
        message = message,
        status = HttpStatus.INTERNAL_SERVER_ERROR,
    )
```

La firma de la clase base está verificada contra el repo:
`ApiException(code: String, message: String, status: HttpStatus, field: String? = null)`
en `shared/exception/ApiException.kt`. `HttpStatus` ya está importado al inicio de
`NegocioExceptions.kt`; no añadas el import de nuevo.

**Restricciones**
- Cero lógica en las data classes. Sin `init`, sin `require`, sin propiedades calculadas.
- No borres ni modifiques ninguna excepción existente.

**Criterio de aceptación:** `./gradlew compileKotlin ktlintCheck detekt` pasa.

---

## T3 · `AritmeticaFinanciera` + sus tests

**Modelo:** Opus 5 · **Esfuerzo:** High

El corazón numérico. Se implementa **antes** que el motor porque el motor depende de él
y porque tiene un criterio de aceptación propio y verificable por separado.

**Escribe primero el test, luego la implementación** (`CLAUDE.md` regla 1).

### Archivo de test: `src/test/kotlin/pe/quantum/crm/shared/simulacion/AritmeticaFinancieraTest.kt`

Casos obligatorios:

1. `raizN` de 1.18 con n=12 devuelve `0.013888430348410033…` al sumarle −1
   → comprobar que `tnm(BigDecimal("18"))` empieza por `0.01388843034841003333867`
   (corregido 2026-09-01: el literal original tenía un dígito de menos en la
   posición 20; el valor completo verificado es
   `0.013888430348410033338673230028230`, igual que `plan-00-mapa-simulaciones.md` línea 69)
   (compara los primeros 22 dígitos significativos, no la igualdad exacta del `BigDecimal`
   completo, que depende de la escala).
2. `tnm(BigDecimal("13"))` empieza por `0.0102368443581763633608`.
3. **Ida y vuelta:** `(1 + tnm(tea))^12` reconstruye `1 + tea/100` con error `< 1e-30`,
   para `tea` = 13, 18, 14 y 1.
4. `raizN` con radicando ≤ 0 lanza `IllegalArgumentException`.
5. La TNM **no se redondea** (§3.1): `tnm(BigDecimal("18")).scale()` es mucho mayor que 2
   → asertar `> 20`.

### Archivo de implementación: `src/main/kotlin/pe/quantum/crm/shared/simulacion/AritmeticaFinanciera.kt`

**Algoritmo ya probado durante la investigación — impleméntalo tal cual, no lo cambies:**

```kotlin
package pe.quantum.crm.shared.simulacion

import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode

/**
 * Aritmetica decimal del motor de simulaciones. Todo en `BigDecimal`: nunca
 * `Double`, ni siquiera como semilla intermedia (reglas_simulaciones.md §3.1).
 *
 * Existe porque `BigDecimal.pow` solo acepta exponente entero y la TNM necesita
 * raiz 12-esima: `TNM = (1 + tea/100)^(1/12) - 1` (§3.2).
 */
object AritmeticaFinanciera {
    /** Precision de salida: 34 digitos significativos (equivalente a DECIMAL128). */
    val MC: MathContext = MathContext(34, RoundingMode.HALF_EVEN)

    /** Precision de trabajo interno, con holgura sobre MC para que el redondeo final sea limpio. */
    private val TRABAJO: MathContext = MathContext(50, RoundingMode.HALF_EVEN)

    private val CIEN = BigDecimal(100)
    private const val MESES_POR_ANIO = 12
    private const val MAX_ITERACIONES = 100

    /**
     * Raiz n-esima por Newton-Raphson.
     *
     * Semilla `1 + (a-1)/n`: aproximacion de primer orden de `a^(1/n)`, valida
     * porque `a = 1 + tea/100` siempre esta cerca de 1 (la BD acota `tea` a
     * 0 < tea < 200 via `chk_simulacion_tea_rango`).
     *
     * NO uses la semilla `a/n`: diverge. Para a=1.18 y n=12 arranca en 0.098,
     * `a/x^11` explota a ~1.5e11 y a 200 iteraciones todavia devuelve ~356 en vez
     * de ~1.0139. Se comprobo fallando.
     */
    fun raizN(
        a: BigDecimal,
        n: Int,
    ): BigDecimal {
        require(a.signum() > 0) { "El radicando debe ser positivo: $a" }
        require(n > 0) { "El indice de la raiz debe ser positivo: $n" }
        val bigN = BigDecimal(n)
        val tolerancia = BigDecimal.ONE.scaleByPowerOfTen(-(TRABAJO.precision - 4))
        var x = BigDecimal.ONE.add(a.subtract(BigDecimal.ONE).divide(bigN, TRABAJO))
        repeat(MAX_ITERACIONES) {
            val siguiente =
                bigN
                    .subtract(BigDecimal.ONE)
                    .multiply(x, TRABAJO)
                    .add(a.divide(x.pow(n - 1, TRABAJO), TRABAJO), TRABAJO)
                    .divide(bigN, TRABAJO)
            val delta = siguiente.subtract(x).abs()
            x = siguiente
            if (delta < tolerancia) return x.round(MC)
        }
        return x.round(MC)
    }

    /**
     * Tasa Nominal Mensual a partir de la TEA en escala 1-100 (§3.2).
     * NUNCA se redondea a 2 decimales (§3.1).
     */
    fun tnm(tea: BigDecimal): BigDecimal =
        raizN(BigDecimal.ONE.add(tea.divide(CIEN, TRABAJO)), MESES_POR_ANIO)
            .subtract(BigDecimal.ONE)

    /**
     * PMT con convencion Excel (`pv` negativo, `fv` positivo, vencida), en su
     * forma algebraica equivalente (§3.2):
     *
     *     CuotaFin = (Principal x (1+TNM)^n - valor_residual) / (((1+TNM)^n - 1) / TNM)
     */
    fun pmt(
        principal: BigDecimal,
        plazoMeses: Int,
        tnm: BigDecimal,
        valorResidual: BigDecimal,
    ): BigDecimal {
        val factor = BigDecimal.ONE.add(tnm).pow(plazoMeses, MC)
        val numerador = principal.multiply(factor, MC).subtract(valorResidual)
        val denominador = factor.subtract(BigDecimal.ONE).divide(tnm, MC)
        return numerador.divide(denominador, MC)
    }
}
```

**Restricciones**
- **Ni una sola aparición de `Double`, `Float`, `Math.pow` o `toDouble()`** en el archivo.
- No añadas la dependencia `big-math` ni ninguna otra a `build.gradle.kts`.
- Si detekt se queja de `MagicNumber` por el 100, el 12 o el 34: ya están extraídos a
  constantes con nombre. Si aun así protesta, añade `@Suppress("MagicNumber")` a nivel
  de `object` con un comentario que diga por qué, siguiendo el estilo de
  `shared/PoliticaDescuento.kt`. **No bajes la severidad global de detekt.**

**Criterio de aceptación**
- `./gradlew test --tests '*AritmeticaFinancieraTest*'` en verde.
- `./gradlew ktlintCheck detekt` pasa.
- `grep -nE 'Double|Float|Math\.pow|toDouble' src/main/kotlin/pe/quantum/crm/shared/simulacion/AritmeticaFinanciera.kt` no devuelve nada.

---

## T4 · Tests de los casos dorados, en rojo

**Modelo:** Opus 5 · **Esfuerzo:** Extra High

> **Esta tarea NO implementa el motor.** Escribe únicamente los tests, más un stub de
> `MotorSimulacion` que compile y falle. Al terminar, los tests dorados deben estar
> **en ROJO**. Si terminan en verde, la tarea está mal hecha (`TESTING-backend.md` §11.3:
> *"un test que no ha fallado nunca no es confiable"*).

### Paso 1 — stub

Crea `src/main/kotlin/pe/quantum/crm/shared/simulacion/MotorSimulacion.kt`:

```kotlin
package pe.quantum.crm.shared.simulacion

/**
 * Motor de calculo de simulaciones financieras: funcion pura y deterministica,
 * sin dependencias de Spring, BD ni framework (reglas_simulaciones.md §3.1).
 *
 * Vive en `shared` y no en `domain/simulaciones` a proposito: lo consumen dos
 * flujos —el modulo que persiste y la Calculadora Financiera, que no persiste
 * nada (§9)— y `oportunidades` lo necesita para la cuota efimera de §6.1.
 * Ver docs/planes/plan-00-mapa-simulaciones.md, decision D1.
 */
object MotorSimulacion {
    fun calcular(parametros: ParametrosSimulacion): ResultadoSimulacion = TODO("T5")
}
```

### Paso 2 — tests dorados

Crea `src/test/kotlin/pe/quantum/crm/shared/simulacion/MotorSimulacionCasosDoradosTest.kt`.

Los valores de abajo **ya fueron verificados** durante la investigación con precisión
decimal de 50 dígitos y, por separado, con `BigDecimal` en Java: reproducen exactamente
las tablas de `reglas_simulaciones.md` §3.6. **Cópialos tal cual; no los recalcules ni
los redondees de otra forma.**

Compara siempre con `compareTo(...) == 0` o con `isEqualByComparingTo` de AssertJ,
**nunca** con `equals` (que distingue `1548.86` de `1548.860`).

#### Caso dorado 1 — Leasing

Entrada: `modo = leasing`, `precioVenta = 110000`, `descuento = 0`,
`cuotaInicial = 56000`, `plazoMeses = 48`, `tea = 18`, `valorResidual = 0`.

| Dato | Valor esperado |
|---|---|
| `valorVenta` | `93220.34` |
| `principal` | `45762.71` |
| `cuotaFinanciera` | `1312.59` |
| `cuotaFinal` | `1548.86` |
| `cronograma.size` | `49` (mes 0 más 48 meses; **sin fila extra de balloon**, §3.5) |

| Mes | saldoInicial | interes | amortizacion | saldoFinal | cuota | cuotaConIgv | igv |
|---|---|---|---|---|---|---|---|
| 0 | `93220.34` | `null` | `47457.63` | `45762.71` | `null` | `null` | `null` |
| 1 | `45762.71` | `635.57` | `677.02` | `45085.69` | `1312.59` | `1548.86` | `null` |
| 2 | `45085.69` | `626.17` | `686.42` | `44399.27` | `1312.59` | `1548.86` | `null` |
| 48 | `1294.61` | `17.98` | `1294.61` | `0.00` | `1312.59` | `1548.86` | `null` |

Añade además un test que verifique que **en todas** las filas de leasing `igv` es `null`
(§3.3: *"Sin columna de IGV en este modo"*).

#### Caso dorado 2 — Crédito directo

Entrada: `modo = credito_directo`, `precioVenta = 90000`, `descuento = 0`,
`cuotaInicial = 45000`, `plazoMeses = 48`, `tea = 13`, `valorResidual = 35000`.

| Dato | Valor esperado |
|---|---|
| `principal` | `45000.00` |
| `cuotaFinanciera` | `623.03` |
| `cuotaFinal` | `697.67` (promedio, §3.4) |
| `cronograma.size` | `49` |

| Mes | saldoInicial | interes | igv | amortizacion | saldoFinal | cuota | cuotaConIgv |
|---|---|---|---|---|---|---|---|
| 0 | `90000.00` | `null` | `null` | `45000.00` | `45000.00` | `null` | `null` |
| 1 | `45000.00` | `460.66` | `82.92` | `162.37` | `44837.63` | `623.03` | `705.94` |
| 2 | `44837.63` | `459.00` | `82.62` | `164.03` | `44673.60` | `623.03` | `705.64` |
| 48 | `35262.05` | `360.97` | `64.97` | `262.05` | `35000.00` | `623.03` | `688.00` |

Añade un test que verifique que el saldo final del mes 48 es exactamente `35000.00`,
es decir que **el balloon sale por consecuencia** y no por ajuste de la última
amortización (§3.5).

**Restricciones**
- No implementes `MotorSimulacion.calcular`. Se queda en `TODO("T5")`.
- No toques `AritmeticaFinanciera.kt`.
- Nombres de test en backticks describiendo comportamiento (`TESTING-backend.md` §9).

**Criterio de aceptación (invertido — esta tarea cierra en ROJO)**
- `./gradlew compileTestKotlin` compila.
- `./gradlew test --tests '*MotorSimulacionCasosDorados*'` **falla**, y falla por
  `NotImplementedError` del `TODO`, **no** por error de compilación ni de import.
- Reporta al arquitecto el número exacto de tests que fallan.

---

## T5 · Implementar el motor hasta poner T4 en verde

**Modelo:** Opus 5 · **Esfuerzo:** Extra High

**Archivo único a modificar:** `src/main/kotlin/pe/quantum/crm/shared/simulacion/MotorSimulacion.kt`
(sustituye el `TODO("T5")` de T4 por la implementación).

**No toques los tests de T4.** Si un test dorado no pasa, el bug está en tu
implementación, nunca en el fixture: sus valores están verificados por partida doble.
Si crees de verdad que un valor esperado está mal, **detente y consulta al arquitecto**.

### Algoritmo

Común a ambos modos (§3.2):

```
PV_efectivo = precioVenta × (1 − descuento/100)
VV          = PV_efectivo / 1.18
IGV         = PV_efectivo − VV
TNM         = AritmeticaFinanciera.tnm(tea)
```

**Leasing** (§3.3):
```
CuotaInicial_sinIGV = cuotaInicial / 1.18
Principal           = VV − CuotaInicial_sinIGV
CuotaFin            = pmt(Principal, plazoMeses, TNM, valorResidual)
cuotaFinal          = CuotaFin × 1.18

mes 0: saldoInicial = VV
       amortizacion = CuotaInicial_sinIGV
       saldoFinal   = saldoInicial − amortizacion
       interes, igv, cuota, cuotaConIgv = null

mes k: saldoInicial = saldoFinal(k−1)
       interes      = saldoInicial × TNM
       amortizacion = CuotaFin − interes
       saldoFinal   = saldoInicial − amortizacion
       cuota        = CuotaFin
       cuotaConIgv  = CuotaFin × 1.18
       igv          = null
```

**Crédito directo** (§3.4):
```
Principal   = PV_efectivo − cuotaInicial
CuotaFin    = pmt(Principal, plazoMeses, TNM, valorResidual)
cuotaFinal  = Σ cuotaConIgv(1..n) / plazoMeses      ← divisor REAL, nunca 48 fijo

mes 0: saldoInicial = PV_efectivo
       amortizacion = cuotaInicial
       saldoFinal   = saldoInicial − amortizacion
       interes, igv, cuota, cuotaConIgv = null

mes k: saldoInicial = saldoFinal(k−1)
       interes      = saldoInicial × TNM
       igv          = interes × 0.18
       amortizacion = CuotaFin − interes
       saldoFinal   = saldoInicial − amortizacion
       cuota        = CuotaFin
       cuotaConIgv  = CuotaFin + igv
```

### Las tres reglas que hacen que los casos dorados cuadren al centavo

1. **Precisión completa entre meses.** El bucle arrastra `saldoInicial`, `interes`,
   `amortizacion` y `saldoFinal` **sin redondear**, con `AritmeticaFinanciera.MC`. El
   `setScale(2, RoundingMode.HALF_UP)` se aplica **solo al construir cada
   `FilaCronograma`** y al construir el `ResultadoSimulacion` (§3.1). Redondear el saldo
   de un mes y usarlo como entrada del siguiente desalinea el balloon: es el error que
   §3.1 prohíbe por su nombre.
2. **El promedio de crédito directo se acumula sin redondear** y se redondea solo al
   final. Sumar los `cuotaConIgv` ya redondeados da un centavo de diferencia.
3. **No agregues fila extra para el balloon ni fuerces la última amortización** (§3.5).
   `cronograma` tiene exactamente `plazoMeses + 1` filas.

### Validación del balloon (§3.5)

Tras construir el cronograma, con el saldo final **sin redondear** del último mes:

```
si |saldoFinal(n) − valorResidual| >= 0.01:
    registrar en el log de la aplicacion (nivel ERROR, con los parametros de entrada)
    lanzar CronogramaInconsistenteException
```

Usa `org.slf4j.LoggerFactory` siguiendo el estilo del repo. Con precisión completa el
residuo medido es del orden de `1e-30`, así que esto no debe dispararse nunca en la
práctica: es una red de seguridad contra un bug de fórmula, no un caso de negocio.

**Restricciones**
- **Ni `Double`, ni `Float`, ni `Math.pow`, ni `toDouble()`.**
- `MotorSimulacion` sigue siendo un `object` sin estado, sin anotaciones de Spring, sin
  constructor y sin dependencias inyectadas.
- No importes nada de `pe.quantum.crm.domain.*`. El motor no conoce el dominio.
- Extrae `1.18`, `0.18` y `100` a constantes con nombre (`IGV_FACTOR`, `IGV_TASA`, `CIEN`).

**Criterio de aceptación**
- `./gradlew test --tests '*MotorSimulacion*' --tests '*AritmeticaFinanciera*'` **todo en verde**.
- Los tests de T4 pasan **sin haber sido modificados**: verifícalo con
  `git diff --stat src/test/` y reporta el resultado.
- `grep -rnE 'Double|Float|Math\.pow|toDouble' src/main/kotlin/pe/quantum/crm/shared/simulacion/` no devuelve nada.
- `./gradlew ktlintCheck detekt` pasa.

---

## T6 · Tests de casos borde y de error

**Modelo:** Sonnet 5 · **Esfuerzo:** Medium

**Archivo único a crear:** `src/test/kotlin/pe/quantum/crm/shared/simulacion/MotorSimulacionCasosBordeTest.kt`

Los cuatro casos que `Instrucciones_simulaciones.md` exige además de los dorados, más
los invariantes que se derivan de las reglas.

| # | Caso | Qué asertar |
|---|---|---|
| 1 | **balloon = 0** en crédito directo (`PV 90000 · CI 45000 · n 48 · TEA 13 · vr 0`) | El saldo final del último mes es `0.00`. `cronograma.size == 49` |
| 2 | **descuento > 0** (leasing, `PV 110000 · dcto 10 · CI 56000 · n 48 · TEA 18 · vr 0`) | El cálculo parte de `PV_efectivo = 99000`, no de 110000: `valorVenta` es `99000/1.18 = 83898.31`. Saldo final `0.00` |
| 3 | **plazo distinto de 48** (crédito directo, `n = 36`, resto como el caso dorado 2 pero `vr 0`) | `cronograma.size == 37`. El promedio de `cuotaFinal` usa divisor **36**: verifica que `cuotaFinal` coincide con la media de los `cuotaConIgv` de las 36 filas de pago calculada en el propio test |
| 4 | **Inconsistencia del balloon** | Ver abajo |
| 5 | Invariante de tamaño | Para varios `plazoMeses` (12, 24, 36, 48, 60): `cronograma.size == plazoMeses + 1`. **Nunca** `plazoMeses + 2` (§3.5: sin fila extra) |
| 6 | Determinismo (§3.1) | Dos llamadas con los mismos `ParametrosSimulacion` devuelven resultados iguales campo a campo |
| 7 | Mes 0 | En ambos modos, la fila 0 tiene `interes`, `igv`, `cuota` y `cuotaConIgv` en `null` |

### Sobre el caso 4

`CronogramaInconsistenteException` es una red de seguridad interna: con el motor
correcto **no se puede disparar desde la API pública**, porque el balloon cuadra por
consecuencia matemática para cualquier entrada válida.

**No falsees una entrada para provocarla, y sobre todo no relajes el motor para poder
testearla.** Escribe el test que verifique el *predicado* de §3.5 directamente:

- que para las entradas de los casos 1, 2 y 3 se cumple `|saldoFinal(n) − valorResidual| < 0.01`
  usando el valor **redondeado a 2 decimales** que expone el cronograma, y
- que la excepción existe y expone el código `CRONOGRAMA_INCONSISTENTE`.

Si al escribirlo concluyes que hace falta tocar la visibilidad de algo en
`MotorSimulacion` para poder probar la rama de error, **detente y consulta al
arquitecto** antes de cambiar nada de producción.

**Restricciones**
- No modifiques ningún archivo de `src/main/`. Esta tarea solo añade tests.
- No modifiques los tests de T3 ni de T4.
- Los valores esperados que no estén en esta tabla, calcúlalos **dentro del test** a
  partir de las fórmulas de §3.2–§3.4, no los pegues como constantes mágicas sin origen.

**Criterio de aceptación:** `./gradlew test --tests '*MotorSimulacion*'` en verde, y
`git diff --stat src/main/` vacío.

---

## T7 · Verificación de build completa

**Modelo:** Sonnet 5 · **Esfuerzo:** Low

Ejecuta, **en este orden**, y reporta la salida de cada uno:

```bash
./gradlew ktlintCheck
./gradlew detekt
./gradlew test
./gradlew koverVerify
```

**Contexto para interpretar el resultado:** el baseline antes de este plan estaba en
verde (`./gradlew test ktlintCheck` → exit 0, verificado el 2026-09-01). Cualquier rojo
lo introdujo este plan.

Sobre `koverVerify`: el trinquete es 85 % global y 84 % de dominio
(`build.gradle.kts`). Este plan **no añade nada bajo `pe.quantum.crm.domain`**, así que
la métrica de dominio no debería moverse. La global solo puede subir: el código nuevo
de `shared/simulacion/` llega con tests densos.

**Si algo falla:**
- `ktlintCheck` en rojo → `./gradlew ktlintFormat`, y vuelve a correr la cadena entera.
- `detekt` en rojo → arregla el hallazgo. **No** bajes umbrales ni edites
  `config/detekt/detekt.yml`. Si el único arreglo posible parece ser tocar la config,
  **detente y consulta**.
- `test` o `koverVerify` en rojo → **no lo arregles por tu cuenta**: reporta al
  arquitecto la salida completa. Un fallo aquí significa que una tarea anterior quedó
  mal cerrada, y eso se diagnostica antes de parchear.

**No ejecutes `./gradlew integrationTest`**: requiere Docker y está bloqueado en local
por la incompatibilidad con Docker 29. Este plan no añade tests de integración.

**Criterio de aceptación:** los cuatro comandos en verde, con su salida reportada.

---

## T8 · Revisión final del diff contra los documentos citados

**Modelo:** Opus 5 · **Esfuerzo:** High

Tarea exigida por `CLAUDE.md`, apartado *"Cómo escribir un plan de implementación en
este repo"*. **No es un resumen del trabajo hecho: es una auditoría del diff completo
de la rama contra la documentación que ya existía antes de empezar.**

### Qué revisar

Lee el diff completo (`git diff main...HEAD`, o `git diff` si no hay rama) y contrástalo,
línea por línea, contra:

- `docs/reglas_simulaciones.md` §1 a §4 y §13
- `CLAUDE.md`: las 14 reglas, y muy en particular la 1 (TDD), la 8 y la 12
- `docs/TESTING-backend.md` §2, §9 y §11
- `docs/planes/plan-00-mapa-simulaciones.md`: decisiones D1, D2 y D5

### Qué buscar, en concreto

1. **Contradicciones con documentación ya vigente y correcta** — el caso que
   `CLAUDE.md` manda cazar por su nombre: no "falta documentar X", sino "algo que ya
   estaba bien escrito se ignoró o se pisó a mitad de una sesión larga".
2. **Fugas de alcance**: cualquier archivo modificado fuera de la lista cerrada de la
   *Fase de investigación* de este plan. Repórtalo aunque parezca una mejora.
3. **`Double` en cualquier forma** dentro de `shared/simulacion/`:
   `grep -rnE 'Double|Float|Math\.pow|toDouble' src/main/kotlin/pe/quantum/crm/shared/simulacion/`
4. **Redondeo prematuro**: que ningún `setScale` caiga dentro del bucle mensual sobre
   una variable que se arrastra al mes siguiente (§3.1).
5. **Fila extra de balloon**: que `cronograma.size` sea `plazoMeses + 1` y que ninguna
   amortización final esté forzada (§3.5).
6. **Que el motor no dependa de `domain/`**:
   `grep -rn 'pe.quantum.crm.domain' src/main/kotlin/pe/quantum/crm/shared/simulacion/`
   debe salir vacío.
7. **Que V40 siga intacta y en su sitio**: `docs/migrations/V40__create_simulaciones.sql`
   sin cambios, y **nada nuevo** en `src/main/resources/db/migration/` (decisión D3 —
   moverla ahí tumbaría el arranque en producción).
8. **TDD verificable en el historial**: que los tests dorados existieran en rojo antes
   de la implementación. Compruébalo en el log de commits del plan.

### Entregable

Un informe al arquitecto con: hallazgos clasificados en *bloqueante / menor / ninguno*,
y para cada bloqueante el archivo, la línea y la regla o sección concreta que incumple.

**No arregles nada en esta tarea.** Solo reporta. Los arreglos se planifican después,
con el diagnóstico completo a la vista.

---

## Cierre del plan

Al terminar T8, **para y resume** qué se hizo antes de tocar cualquier otra fase
(`Instrucciones_simulaciones.md`: *"Al terminar cada fase, para y resume qué hiciste
antes de seguir a la siguiente"*).

Recuerda que la fase 2 **sigue bloqueada** por la ausencia de `oportunidad_items`
(hallazgo H1 del mapa). No la empieces.
