package pe.quantum.crm.shared.simulacion

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import pe.quantum.crm.shared.enums.ModoSimulacion
import java.math.BigDecimal

/**
 * Casos dorados del motor de simulaciones: las tablas de
 * `docs/reglas_simulaciones.md` §3.6, extraidas y verificadas al centavo contra
 * los Excel de produccion.
 *
 * Los importes se comparan SIEMPRE con `isEqualByComparingTo` y nunca con
 * `isEqualTo`: `BigDecimal.equals` distingue `1548.86` de `1548.860` y aqui lo
 * que importa es el valor, no la escala.
 */
class MotorSimulacionCasosDoradosTest {
    /**
     * Comprueba una fila completa del cronograma. Un `null` esperado significa
     * que la columna no aplica a ese modo o a ese mes (§3.3 y §3.4).
     */
    @Suppress("LongParameterList")
    private fun verificarFila(
        fila: FilaCronograma,
        saldoInicial: String,
        amortizacion: String,
        interes: String?,
        igv: String?,
        saldoFinal: String,
        cuota: String?,
        cuotaConIgv: String?,
    ) {
        val mes = "mes ${fila.mes}"
        assertThat(fila.saldoInicial).`as`("$mes saldoInicial").isEqualByComparingTo(saldoInicial)
        assertThat(fila.amortizacion).`as`("$mes amortizacion").isEqualByComparingTo(amortizacion)
        assertThat(fila.saldoFinal).`as`("$mes saldoFinal").isEqualByComparingTo(saldoFinal)
        verificarOpcional(fila.interes, interes, "$mes interes")
        verificarOpcional(fila.igv, igv, "$mes igv")
        verificarOpcional(fila.cuota, cuota, "$mes cuota")
        verificarOpcional(fila.cuotaConIgv, cuotaConIgv, "$mes cuotaConIgv")
    }

    private fun verificarOpcional(
        actual: BigDecimal?,
        esperado: String?,
        descripcion: String,
    ) {
        if (esperado == null) {
            assertThat(actual).`as`(descripcion).isNull()
        } else {
            assertThat(actual).`as`(descripcion).isNotNull().isEqualByComparingTo(esperado)
        }
    }

    private fun mes(
        resultado: ResultadoSimulacion,
        numero: Int,
    ): FilaCronograma = resultado.cronograma.single { it.mes == numero }

    /**
     * Leasing — `PV 110 000 · CI 56 000 · n 48 · TEA 18 · balloon 0` (§3.6).
     */
    @Nested
    inner class Leasing {
        private val parametros =
            ParametrosSimulacion(
                modo = ModoSimulacion.leasing,
                precioVenta = BigDecimal("110000"),
                descuento = BigDecimal("0"),
                cuotaInicial = BigDecimal("56000"),
                plazoMeses = 48,
                tea = BigDecimal("18"),
                valorResidual = BigDecimal("0"),
            )

        @Test
        fun `el valor venta es el precio de venta sin IGV`() {
            assertThat(MotorSimulacion.calcular(parametros).valorVenta)
                .isEqualByComparingTo("93220.34")
        }

        @Test
        fun `el principal descuenta la cuota inicial sin IGV del valor venta`() {
            assertThat(MotorSimulacion.calcular(parametros).principal)
                .isEqualByComparingTo("45762.71")
        }

        @Test
        fun `la cuota financiera del caso dorado de leasing es 1312 punto 59`() {
            assertThat(MotorSimulacion.calcular(parametros).cuotaFinanciera)
                .isEqualByComparingTo("1312.59")
        }

        @Test
        fun `la cuota final de leasing es la cuota financiera con IGV`() {
            assertThat(MotorSimulacion.calcular(parametros).cuotaFinal)
                .isEqualByComparingTo("1548.86")
        }

        @Test
        fun `el cronograma tiene el mes 0 mas los 48 meses y ninguna fila extra de balloon`() {
            val cronograma = MotorSimulacion.calcular(parametros).cronograma
            assertThat(cronograma).hasSize(49)
            assertThat(cronograma.map { it.mes }).isEqualTo((0..48).toList())
        }

        @Test
        fun `el mes 0 amortiza la cuota inicial sin IGV y no tiene interes ni cuota`() {
            verificarFila(
                fila = mes(MotorSimulacion.calcular(parametros), 0),
                saldoInicial = "93220.34",
                amortizacion = "47457.63",
                interes = null,
                igv = null,
                saldoFinal = "45762.71",
                cuota = null,
                cuotaConIgv = null,
            )
        }

        @Test
        fun `el mes 1 reproduce la fila dorada de leasing`() {
            verificarFila(
                fila = mes(MotorSimulacion.calcular(parametros), 1),
                saldoInicial = "45762.71",
                amortizacion = "677.02",
                interes = "635.57",
                igv = null,
                saldoFinal = "45085.69",
                cuota = "1312.59",
                cuotaConIgv = "1548.86",
            )
        }

        @Test
        fun `el mes 2 reproduce la fila dorada de leasing`() {
            verificarFila(
                fila = mes(MotorSimulacion.calcular(parametros), 2),
                saldoInicial = "45085.69",
                amortizacion = "686.42",
                interes = "626.17",
                igv = null,
                saldoFinal = "44399.27",
                cuota = "1312.59",
                cuotaConIgv = "1548.86",
            )
        }

        @Test
        fun `el mes 48 cierra el saldo en cero sin balloon`() {
            verificarFila(
                fila = mes(MotorSimulacion.calcular(parametros), 48),
                saldoInicial = "1294.61",
                amortizacion = "1294.61",
                interes = "17.98",
                igv = null,
                saldoFinal = "0.00",
                cuota = "1312.59",
                cuotaConIgv = "1548.86",
            )
        }

        @Test
        fun `ninguna fila de leasing trae columna de IGV`() {
            val cronograma = MotorSimulacion.calcular(parametros).cronograma
            assertThat(cronograma).allSatisfy { fila ->
                assertThat(fila.igv).`as`("mes ${fila.mes} igv").isNull()
            }
        }
    }

    /**
     * Credito Directo — `PV 90 000 · CI 45 000 · n 48 · TEA 13 · balloon 35 000` (§3.6).
     */
    @Nested
    inner class CreditoDirecto {
        private val parametros =
            ParametrosSimulacion(
                modo = ModoSimulacion.credito_directo,
                precioVenta = BigDecimal("90000"),
                descuento = BigDecimal("0"),
                cuotaInicial = BigDecimal("45000"),
                plazoMeses = 48,
                tea = BigDecimal("13"),
                valorResidual = BigDecimal("35000"),
            )

        @Test
        fun `el principal es el precio efectivo menos la cuota inicial`() {
            assertThat(MotorSimulacion.calcular(parametros).principal)
                .isEqualByComparingTo("45000.00")
        }

        @Test
        fun `la cuota financiera del caso dorado de credito directo es 623 punto 03`() {
            assertThat(MotorSimulacion.calcular(parametros).cuotaFinanciera)
                .isEqualByComparingTo("623.03")
        }

        @Test
        fun `la cuota final es el promedio de las cuotas con IGV de intereses`() {
            assertThat(MotorSimulacion.calcular(parametros).cuotaFinal)
                .isEqualByComparingTo("697.67")
        }

        @Test
        fun `el cronograma tiene el mes 0 mas los 48 meses y ninguna fila extra de balloon`() {
            val cronograma = MotorSimulacion.calcular(parametros).cronograma
            assertThat(cronograma).hasSize(49)
            assertThat(cronograma.map { it.mes }).isEqualTo((0..48).toList())
        }

        @Test
        fun `el mes 0 amortiza la cuota inicial y no tiene interes ni IGV ni cuota`() {
            verificarFila(
                fila = mes(MotorSimulacion.calcular(parametros), 0),
                saldoInicial = "90000.00",
                amortizacion = "45000.00",
                interes = null,
                igv = null,
                saldoFinal = "45000.00",
                cuota = null,
                cuotaConIgv = null,
            )
        }

        @Test
        fun `el mes 1 reproduce la fila dorada de credito directo`() {
            verificarFila(
                fila = mes(MotorSimulacion.calcular(parametros), 1),
                saldoInicial = "45000.00",
                amortizacion = "162.37",
                interes = "460.66",
                igv = "82.92",
                saldoFinal = "44837.63",
                cuota = "623.03",
                cuotaConIgv = "705.94",
            )
        }

        @Test
        fun `el mes 2 reproduce la fila dorada de credito directo`() {
            verificarFila(
                fila = mes(MotorSimulacion.calcular(parametros), 2),
                saldoInicial = "44837.63",
                amortizacion = "164.03",
                interes = "459.00",
                igv = "82.62",
                saldoFinal = "44673.60",
                cuota = "623.03",
                cuotaConIgv = "705.64",
            )
        }

        @Test
        fun `el mes 48 reproduce la fila dorada de credito directo`() {
            verificarFila(
                fila = mes(MotorSimulacion.calcular(parametros), 48),
                saldoInicial = "35262.05",
                amortizacion = "262.05",
                interes = "360.97",
                igv = "64.97",
                saldoFinal = "35000.00",
                cuota = "623.03",
                cuotaConIgv = "688.00",
            )
        }

        @Test
        fun `el balloon sale por consecuencia - el saldo final del mes 48 es el valor residual`() {
            val ultima = mes(MotorSimulacion.calcular(parametros), 48)
            assertThat(ultima.saldoFinal).isEqualByComparingTo("35000.00")
        }
    }
}
