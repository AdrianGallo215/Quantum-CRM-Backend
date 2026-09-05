package pe.quantum.crm.shared.simulacion

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode

/**
 * Casos dorados de docs/reglas_simulaciones.md §3.6 para la TNM y garantias de
 * §3.1 (precision completa, la TNM nunca se redondea).
 */
class AritmeticaFinancieraTest {
    private val comparacion = MathContext(60, RoundingMode.HALF_EVEN)

    /**
     * Primeros 22 digitos significativos de `1.18^(1/12) - 1`, tomados del valor
     * completo que registra `docs/planes/plan-00-mapa-simulaciones.md` linea 69
     * (`0.013888430348410033338673230028230`) y consistentes con el caso dorado de
     * `docs/reglas_simulaciones.md` §3.6 (`0.013888430348410033…`).
     */
    @Test
    fun `tnm de TEA 18 coincide con el caso dorado de leasing`() {
        assertThat(AritmeticaFinanciera.tnm(BigDecimal("18")).toPlainString())
            .startsWith("0.01388843034841003333867")
    }

    @Test
    fun `tnm de TEA 13 coincide con el caso dorado de credito directo`() {
        assertThat(AritmeticaFinanciera.tnm(BigDecimal("13")).toPlainString())
            .startsWith("0.0102368443581763633608")
    }

    @Test
    fun `ida y vuelta - la TNM elevada a 12 reconstruye la TEA`() {
        val tolerancia = BigDecimal.ONE.scaleByPowerOfTen(-30)
        listOf("13", "18", "14", "1").forEach { tea ->
            val esperado = BigDecimal.ONE.add(BigDecimal(tea).divide(BigDecimal(100), comparacion))
            val reconstruido = BigDecimal.ONE.add(AritmeticaFinanciera.tnm(BigDecimal(tea))).pow(12, comparacion)
            assertThat(reconstruido.subtract(esperado).abs())
                .`as`("TEA %s", tea)
                .isLessThan(tolerancia)
        }
    }

    @Test
    fun `raizN rechaza radicandos no positivos`() {
        assertThatThrownBy { AritmeticaFinanciera.raizN(BigDecimal.ZERO, 12) }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { AritmeticaFinanciera.raizN(BigDecimal("-1.18"), 12) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `la TNM no se redondea a 2 decimales`() {
        assertThat(AritmeticaFinanciera.tnm(BigDecimal("18")).scale()).isGreaterThan(20)
    }
}
