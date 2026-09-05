package pe.quantum.crm.shared.simulacion

import org.slf4j.LoggerFactory
import pe.quantum.crm.shared.enums.ModoSimulacion
import pe.quantum.crm.shared.exception.CronogramaInconsistenteException
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Motor de calculo de simulaciones financieras: funcion pura y deterministica,
 * sin dependencias de Spring, BD ni framework (reglas_simulaciones.md §3.1).
 *
 * Vive en `shared` y no en `domain/simulaciones` a proposito: lo consumen dos
 * flujos —el modulo que persiste y la Calculadora Financiera, que no persiste
 * nada (§9)— y `oportunidades` lo necesita para la cuota efimera de §6.1.
 * Ver docs/planes/plan-00-mapa-simulaciones.md, decision D1.
 *
 * Toda la aritmetica interna del cronograma se arrastra SIN redondear con
 * `AritmeticaFinanciera.MC`: nada de tipos binarios de coma flotante, y nunca
 * un saldo redondeado como entrada del mes siguiente, porque eso desalinea el
 * balloon (§3.1). El `setScale(2)` se aplica solo al construir cada fila y el
 * resultado.
 */
object MotorSimulacion {
    private val log = LoggerFactory.getLogger(MotorSimulacion::class.java)

    /** Factor de IGV: convierte valor venta en precio con IGV y viceversa. */
    private val IGV_FACTOR = BigDecimal("1.18")

    /** Tasa de IGV aplicada al interes en credito directo (§3.4). */
    private val IGV_TASA = BigDecimal("0.18")

    /** Base porcentual del descuento. */
    private val CIEN = BigDecimal("100")

    /** Tolerancia del cierre del balloon: un centavo (§3.5). */
    private val TOLERANCIA_BALLOON = BigDecimal("0.01")

    private const val ESCALA_MONETARIA = 2

    fun calcular(parametros: ParametrosSimulacion): ResultadoSimulacion {
        val mc = AritmeticaFinanciera.MC
        val factorDescuento = BigDecimal.ONE.subtract(parametros.descuento.divide(CIEN, mc))
        val precioEfectivo = parametros.precioVenta.multiply(factorDescuento, mc)
        val valorVenta = precioEfectivo.divide(IGV_FACTOR, mc)
        val igv = precioEfectivo.subtract(valorVenta)
        val tnm = AritmeticaFinanciera.tnm(parametros.tea)

        return when (parametros.modo) {
            ModoSimulacion.leasing ->
                calcularLeasing(parametros, valorVenta, igv, tnm)

            ModoSimulacion.credito_directo ->
                calcularCreditoDirecto(parametros, precioEfectivo, valorVenta, igv, tnm)
        }
    }

    private fun calcularLeasing(
        parametros: ParametrosSimulacion,
        valorVenta: BigDecimal,
        igv: BigDecimal,
        tnm: BigDecimal,
    ): ResultadoSimulacion {
        val mc = AritmeticaFinanciera.MC
        val cuotaInicialSinIgv = parametros.cuotaInicial.divide(IGV_FACTOR, mc)
        val principal = valorVenta.subtract(cuotaInicialSinIgv)
        val cuotaFinanciera =
            AritmeticaFinanciera.pmt(principal, parametros.plazoMeses, tnm, parametros.valorResidual)
        val cuotaConIgv = cuotaFinanciera.multiply(IGV_FACTOR, mc)

        val filas = mutableListOf<FilaCronograma>()
        filas += filaInicial(valorVenta, cuotaInicialSinIgv, principal)

        var saldo = principal
        for (mes in 1..parametros.plazoMeses) {
            val saldoInicial = saldo
            val interes = saldoInicial.multiply(tnm, mc)
            val amortizacion = cuotaFinanciera.subtract(interes)
            saldo = saldoInicial.subtract(amortizacion)
            filas +=
                FilaCronograma(
                    mes = mes,
                    saldoInicial = redondear(saldoInicial),
                    amortizacion = redondear(amortizacion),
                    interes = redondear(interes),
                    igv = null,
                    saldoFinal = redondear(saldo),
                    cuota = redondear(cuotaFinanciera),
                    cuotaConIgv = redondear(cuotaConIgv),
                )
        }
        validarBalloon(parametros, saldo)

        return ResultadoSimulacion(
            cuotaFinal = redondear(cuotaConIgv),
            cuotaFinanciera = redondear(cuotaFinanciera),
            valorVenta = redondear(valorVenta),
            igv = redondear(igv),
            principal = redondear(principal),
            tasaNominalMensual = tnm,
            cronograma = filas,
        )
    }

    private fun calcularCreditoDirecto(
        parametros: ParametrosSimulacion,
        precioEfectivo: BigDecimal,
        valorVenta: BigDecimal,
        igv: BigDecimal,
        tnm: BigDecimal,
    ): ResultadoSimulacion {
        val mc = AritmeticaFinanciera.MC
        val principal = precioEfectivo.subtract(parametros.cuotaInicial)
        val cuotaFinanciera =
            AritmeticaFinanciera.pmt(principal, parametros.plazoMeses, tnm, parametros.valorResidual)

        val filas = mutableListOf<FilaCronograma>()
        filas += filaInicial(precioEfectivo, parametros.cuotaInicial, principal)

        var saldo = principal
        // Se acumula sin redondear: sumar las cuotas ya redondeadas desvia el
        // promedio en un centavo (§3.1).
        var acumuladoCuotaConIgv = BigDecimal.ZERO
        for (mes in 1..parametros.plazoMeses) {
            val saldoInicial = saldo
            val interes = saldoInicial.multiply(tnm, mc)
            val igvInteres = interes.multiply(IGV_TASA, mc)
            val amortizacion = cuotaFinanciera.subtract(interes)
            val cuotaConIgv = cuotaFinanciera.add(igvInteres)
            saldo = saldoInicial.subtract(amortizacion)
            acumuladoCuotaConIgv = acumuladoCuotaConIgv.add(cuotaConIgv)
            filas +=
                FilaCronograma(
                    mes = mes,
                    saldoInicial = redondear(saldoInicial),
                    amortizacion = redondear(amortizacion),
                    interes = redondear(interes),
                    igv = redondear(igvInteres),
                    saldoFinal = redondear(saldo),
                    cuota = redondear(cuotaFinanciera),
                    cuotaConIgv = redondear(cuotaConIgv),
                )
        }
        validarBalloon(parametros, saldo)

        // Divisor real: el plazo de la simulacion, nunca 48 fijo.
        val promedio = acumuladoCuotaConIgv.divide(BigDecimal(parametros.plazoMeses), mc)

        return ResultadoSimulacion(
            cuotaFinal = redondear(promedio),
            cuotaFinanciera = redondear(cuotaFinanciera),
            valorVenta = redondear(valorVenta),
            igv = redondear(igv),
            principal = redondear(principal),
            tasaNominalMensual = tnm,
            cronograma = filas,
        )
    }

    /** Mes 0: la fila de la cuota inicial. Sin interes, sin IGV y sin cuota (§3.3 y §3.4). */
    private fun filaInicial(
        saldoInicial: BigDecimal,
        amortizacion: BigDecimal,
        saldoFinal: BigDecimal,
    ): FilaCronograma =
        FilaCronograma(
            mes = 0,
            saldoInicial = redondear(saldoInicial),
            amortizacion = redondear(amortizacion),
            interes = null,
            igv = null,
            saldoFinal = redondear(saldoFinal),
            cuota = null,
            cuotaConIgv = null,
        )

    /**
     * El saldo final del ultimo mes debe cerrar en `valor_residual` (§3.5). Se
     * mide sobre el saldo SIN redondear; con precision completa el residuo es
     * del orden de 1e-30, asi que dispararse aqui significa un bug de formula.
     */
    private fun validarBalloon(
        parametros: ParametrosSimulacion,
        saldoFinalSinRedondear: BigDecimal,
    ) {
        val residuo = saldoFinalSinRedondear.subtract(parametros.valorResidual).abs()
        if (residuo >= TOLERANCIA_BALLOON) {
            log.error(
                "Cronograma inconsistente: el saldo final no cierra en el valor residual. residuo={} parametros={}",
                residuo,
                parametros,
            )
            throw CronogramaInconsistenteException(
                "El saldo final del ultimo mes no coincide con el valor residual",
            )
        }
    }

    private fun redondear(valor: BigDecimal): BigDecimal = valor.setScale(ESCALA_MONETARIA, RoundingMode.HALF_UP)
}
