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
@Suppress("MagicNumber") // 34 y 50 son precisiones decimales y 4 el margen de la tolerancia: parametros del algoritmo.
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
