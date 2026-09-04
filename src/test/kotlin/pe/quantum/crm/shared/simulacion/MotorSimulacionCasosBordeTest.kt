package pe.quantum.crm.shared.simulacion

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import pe.quantum.crm.shared.enums.ModoSimulacion
import pe.quantum.crm.shared.exception.CronogramaInconsistenteException
import java.math.BigDecimal

/**
 * Casos borde y de error del motor de simulaciones, complementarios a los
 * casos dorados de `MotorSimulacionCasosDoradosTest` (docs/reglas_simulaciones.md).
 *
 * Los importes se comparan SIEMPRE con `isEqualByComparingTo`: lo que importa
 * es el valor, no la escala del `BigDecimal`.
 */
class MotorSimulacionCasosBordeTest {
    private fun mes(
        resultado: ResultadoSimulacion,
        numero: Int,
    ): FilaCronograma = resultado.cronograma.single { it.mes == numero }

    /** Caso 1: balloon = 0 en credito directo (PV 90000 · CI 45000 · n 48 · TEA 13 · vr 0). */
    @Nested
    inner class BalloonCero {
        private val parametros =
            ParametrosSimulacion(
                modo = ModoSimulacion.credito_directo,
                precioVenta = BigDecimal("90000"),
                descuento = BigDecimal("0"),
                cuotaInicial = BigDecimal("45000"),
                plazoMeses = 48,
                tea = BigDecimal("13"),
                valorResidual = BigDecimal("0"),
            )

        @Test
        fun `el saldo final del ultimo mes es cero cuando el balloon es cero`() {
            val resultado = MotorSimulacion.calcular(parametros)
            assertThat(mes(resultado, 48).saldoFinal).isEqualByComparingTo("0.00")
        }

        @Test
        fun `el cronograma tiene el mes 0 mas los 48 meses`() {
            val resultado = MotorSimulacion.calcular(parametros)
            assertThat(resultado.cronograma).hasSize(49)
        }
    }

    /** Caso 2: descuento > 0 en leasing (PV 110000 · dcto 10 · CI 56000 · n 48 · TEA 18 · vr 0). */
    @Nested
    inner class DescuentoPositivo {
        private val parametros =
            ParametrosSimulacion(
                modo = ModoSimulacion.leasing,
                precioVenta = BigDecimal("110000"),
                descuento = BigDecimal("10"),
                cuotaInicial = BigDecimal("56000"),
                plazoMeses = 48,
                tea = BigDecimal("18"),
                valorResidual = BigDecimal("0"),
            )

        @Test
        fun `el calculo parte del precio con descuento aplicado, no del precio de lista`() {
            // PV_efectivo = 110000 x (1 - 10/100) = 99000
            // VV = 99000 / 1.18 = 83898.31 (redondeado)
            val resultado = MotorSimulacion.calcular(parametros)
            assertThat(resultado.valorVenta).isEqualByComparingTo("83898.31")
        }

        @Test
        fun `el saldo final del ultimo mes es cero`() {
            val resultado = MotorSimulacion.calcular(parametros)
            assertThat(mes(resultado, 48).saldoFinal).isEqualByComparingTo("0.00")
        }
    }

    /**
     * Caso 3: plazo distinto de 48 en credito directo — mismos datos que el caso
     * dorado de credito directo, pero `n = 36` y `vr = 0`.
     */
    @Nested
    inner class PlazoDistintoDe48 {
        private val parametros =
            ParametrosSimulacion(
                modo = ModoSimulacion.credito_directo,
                precioVenta = BigDecimal("90000"),
                descuento = BigDecimal("0"),
                cuotaInicial = BigDecimal("45000"),
                plazoMeses = 36,
                tea = BigDecimal("13"),
                valorResidual = BigDecimal("0"),
            )

        @Test
        fun `el cronograma tiene el mes 0 mas los 36 meses`() {
            val resultado = MotorSimulacion.calcular(parametros)
            assertThat(resultado.cronograma).hasSize(37)
        }

        @Test
        fun `la cuota final es el promedio de las 36 cuotas con IGV, no de 48`() {
            val resultado = MotorSimulacion.calcular(parametros)
            val filasDePago = resultado.cronograma.filter { it.mes in 1..36 }
            assertThat(filasDePago).hasSize(36)

            val sumaCuotasConIgv =
                filasDePago.fold(BigDecimal.ZERO) { acumulado, fila ->
                    acumulado.add(fila.cuotaConIgv)
                }
            val promedioEsperado = sumaCuotasConIgv.divide(BigDecimal(36), AritmeticaFinanciera.MC)

            assertThat(resultado.cuotaFinal)
                .isEqualByComparingTo(promedioEsperado.setScale(2, java.math.RoundingMode.HALF_UP))
        }
    }

    /**
     * Caso 4: la inconsistencia del balloon es una red de seguridad interna que
     * el motor correcto nunca dispara desde entradas validas. Se verifica el
     * predicado de §3.5 directamente sobre los casos 1, 2 y 3, y por separado
     * que la excepcion existe y expone el codigo correcto.
     */
    @Nested
    inner class PredicadoDelBalloon {
        @Test
        fun `el saldo final del caso 1 cierra en el valor residual dentro de la tolerancia`() {
            val parametros =
                ParametrosSimulacion(
                    modo = ModoSimulacion.credito_directo,
                    precioVenta = BigDecimal("90000"),
                    descuento = BigDecimal("0"),
                    cuotaInicial = BigDecimal("45000"),
                    plazoMeses = 48,
                    tea = BigDecimal("13"),
                    valorResidual = BigDecimal("0"),
                )
            verificarCierreBalloon(parametros)
        }

        @Test
        fun `el saldo final del caso 2 cierra en el valor residual dentro de la tolerancia`() {
            val parametros =
                ParametrosSimulacion(
                    modo = ModoSimulacion.leasing,
                    precioVenta = BigDecimal("110000"),
                    descuento = BigDecimal("10"),
                    cuotaInicial = BigDecimal("56000"),
                    plazoMeses = 48,
                    tea = BigDecimal("18"),
                    valorResidual = BigDecimal("0"),
                )
            verificarCierreBalloon(parametros)
        }

        @Test
        fun `el saldo final del caso 3 cierra en el valor residual dentro de la tolerancia`() {
            val parametros =
                ParametrosSimulacion(
                    modo = ModoSimulacion.credito_directo,
                    precioVenta = BigDecimal("90000"),
                    descuento = BigDecimal("0"),
                    cuotaInicial = BigDecimal("45000"),
                    plazoMeses = 36,
                    tea = BigDecimal("13"),
                    valorResidual = BigDecimal("0"),
                )
            verificarCierreBalloon(parametros)
        }

        private fun verificarCierreBalloon(parametros: ParametrosSimulacion) {
            val resultado = MotorSimulacion.calcular(parametros)
            val saldoFinalUltimoMes = resultado.cronograma.last().saldoFinal
            val residuo = saldoFinalUltimoMes.subtract(parametros.valorResidual).abs()
            assertThat(residuo).isLessThan(BigDecimal("0.01"))
        }

        @Test
        fun `CronogramaInconsistenteException expone el codigo CRONOGRAMA_INCONSISTENTE`() {
            val excepcion = CronogramaInconsistenteException("mensaje de prueba")
            assertThat(excepcion.code).isEqualTo("CRONOGRAMA_INCONSISTENTE")
        }
    }

    /** Caso 5: invariante de tamaño del cronograma para varios plazos, en ambos modos. */
    @Nested
    inner class InvarianteDeTamano {
        @Test
        fun `el cronograma siempre tiene plazoMeses mas 1 filas, nunca una fila extra`() {
            val plazos = listOf(12, 24, 36, 48, 60)
            val modos = listOf(ModoSimulacion.leasing, ModoSimulacion.credito_directo)

            for (modo in modos) {
                for (plazo in plazos) {
                    val parametros =
                        ParametrosSimulacion(
                            modo = modo,
                            precioVenta = BigDecimal("90000"),
                            descuento = BigDecimal("0"),
                            cuotaInicial = BigDecimal("45000"),
                            plazoMeses = plazo,
                            tea = BigDecimal("13"),
                            valorResidual = BigDecimal("0"),
                        )
                    val resultado = MotorSimulacion.calcular(parametros)
                    assertThat(resultado.cronograma)
                        .`as`("modo=$modo plazo=$plazo")
                        .hasSize(plazo + 1)
                    assertThat(resultado.cronograma.map { it.mes })
                        .`as`("modo=$modo plazo=$plazo")
                        .isEqualTo((0..plazo).toList())
                }
            }
        }
    }

    /** Caso 6: determinismo (§3.1) — mismos parametros, mismo resultado campo a campo. */
    @Nested
    inner class Determinismo {
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
        fun `dos llamadas con los mismos parametros devuelven resultados iguales`() {
            val primero = MotorSimulacion.calcular(parametros)
            val segundo = MotorSimulacion.calcular(parametros)
            assertThat(segundo).isEqualTo(primero)
        }

        @Test
        fun `el determinismo tambien vale para credito directo`() {
            val parametrosCd =
                ParametrosSimulacion(
                    modo = ModoSimulacion.credito_directo,
                    precioVenta = BigDecimal("90000"),
                    descuento = BigDecimal("0"),
                    cuotaInicial = BigDecimal("45000"),
                    plazoMeses = 48,
                    tea = BigDecimal("13"),
                    valorResidual = BigDecimal("35000"),
                )
            val primero = MotorSimulacion.calcular(parametrosCd)
            val segundo = MotorSimulacion.calcular(parametrosCd)
            assertThat(segundo).isEqualTo(primero)
        }
    }

    /** Caso 7: la fila del mes 0 nunca trae interes, igv, cuota ni cuotaConIgv, en ningun modo. */
    @Nested
    inner class MesCero {
        @Test
        fun `mes 0 en leasing tiene interes igv cuota y cuotaConIgv en null`() {
            val parametros =
                ParametrosSimulacion(
                    modo = ModoSimulacion.leasing,
                    precioVenta = BigDecimal("110000"),
                    descuento = BigDecimal("0"),
                    cuotaInicial = BigDecimal("56000"),
                    plazoMeses = 48,
                    tea = BigDecimal("18"),
                    valorResidual = BigDecimal("0"),
                )
            val filaCero = mes(MotorSimulacion.calcular(parametros), 0)
            assertThat(filaCero.interes).isNull()
            assertThat(filaCero.igv).isNull()
            assertThat(filaCero.cuota).isNull()
            assertThat(filaCero.cuotaConIgv).isNull()
        }

        @Test
        fun `mes 0 en credito directo tiene interes igv cuota y cuotaConIgv en null`() {
            val parametros =
                ParametrosSimulacion(
                    modo = ModoSimulacion.credito_directo,
                    precioVenta = BigDecimal("90000"),
                    descuento = BigDecimal("0"),
                    cuotaInicial = BigDecimal("45000"),
                    plazoMeses = 48,
                    tea = BigDecimal("13"),
                    valorResidual = BigDecimal("35000"),
                )
            val filaCero = mes(MotorSimulacion.calcular(parametros), 0)
            assertThat(filaCero.interes).isNull()
            assertThat(filaCero.igv).isNull()
            assertThat(filaCero.cuota).isNull()
            assertThat(filaCero.cuotaConIgv).isNull()
        }
    }
}
