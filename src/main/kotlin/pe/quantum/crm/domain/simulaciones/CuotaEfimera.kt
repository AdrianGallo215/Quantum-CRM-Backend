package pe.quantum.crm.domain.simulaciones

import org.slf4j.LoggerFactory
import pe.quantum.crm.shared.enums.ModoSimulacion
import pe.quantum.crm.shared.simulacion.AritmeticaFinanciera
import pe.quantum.crm.shared.simulacion.MotorSimulacion
import pe.quantum.crm.shared.simulacion.ParametrosSimulacion
import java.math.BigDecimal

/**
 * Cuota Quantum estimada de un item que todavia no tiene simulacion principal
 * (`reglas_simulaciones.md` §6.1). Funcion pura en un `object`, sin Spring ni
 * JPA — mismo estilo que [NombreSimulacion] y [DiffSimulacion].
 *
 * Los parametros por defecto de §6.1 son constantes fijas, pero el
 * `precio_venta` viene del item real: hay items para los que esa combinacion
 * NO cumple §13 (`cuota_inicial < PV_efectivo` y `valor_residual < Principal`).
 * Por eso este calculo es defensivo por contrato (decision D55 de
 * `plan-13-mapa-cierre-simulaciones.md`): degrada a `null` en vez de lanzar.
 * Un item barato jamas puede tumbar el `GET /oportunidades` con un 500.
 *
 * Esto NO relaja §13 en `POST /simulaciones`: cuando el usuario crea una
 * simulacion de verdad con parametros invalidos sigue recibiendo su 400 por
 * [ValidacionesSimulacion]. Lo que degrada aqui es solo el calculo automatico
 * y no solicitado.
 */
object CuotaEfimera {
    private val log = LoggerFactory.getLogger(CuotaEfimera::class.java)

    /** §6.1: plazo por defecto de la cuota efimera. */
    private const val PLAZO_MESES = 48

    /** §6.1: TEA por defecto, en escala 1-100 como exige [ParametrosSimulacion]. */
    private val TEA = BigDecimal("14")

    /** §6.1: cuota inicial por defecto. */
    private val CUOTA_INICIAL = BigDecimal("45000")

    /**
     * §6.1: valor residual por defecto. OJO: NO es el de
     * [DefaultsSimulacion.VALOR_RESIDUAL], que replica el DEFAULT de columna de
     * V43 (0). Son dos cosas distintas y no se reutilizan entre si.
     */
    private val VALOR_RESIDUAL = BigDecimal("25000")

    /**
     * §6.1 no dice con que modo se calcula la cuota efimera; leasing es una
     * decision del backend (D54), no una regla citada de las reglas de negocio.
     */
    private val MODO = ModoSimulacion.leasing

    /** Base porcentual del descuento en `PV_efectivo` (§3.2). */
    private val CIEN = BigDecimal("100")

    /**
     * Cuota Quantum estimada de un item que todavia no tiene simulacion
     * principal (§6.1): se calcula al vuelo con los parametros por defecto y
     * NO se persiste nada.
     *
     * Devuelve `null` —nunca lanza— cuando no hay una cuota razonable que
     * mostrar (decision D55 de plan-13-mapa-cierre-simulaciones.md). Es un
     * dato informativo en un listado: jamas puede impedir leer la oportunidad.
     *
     * Las salidas `null`:
     * 1. El item no tiene `precio_venta` (item incompleto).
     * 2. `CUOTA_INICIAL >= PV_efectivo` (§13 no se cumple).
     * 3. `VALOR_RESIDUAL >= Principal` (§13 no se cumple), y cualquier
     *    excepcion del motor, que se degrada con un `warn` en el log
     *    ([cuotaDelMotor]).
     *
     * Las comparaciones de §13 se hacen aqui directamente con `>=` y NO
     * llamando a [ValidacionesSimulacion]: usar su `ValidacionException` como
     * control de flujo oscureceria justo lo que esta funcion debe dejar
     * clarisimo, que por diseño no lanza.
     *
     * `dias_trabajados` y `comision_estructuracion` de §6.1 no aparecen: no
     * participan del cronograma (§3.2), asi que el motor no los usa.
     */
    fun calcular(
        precioVenta: BigDecimal?,
        descuento: BigDecimal?,
    ): BigDecimal? {
        if (precioVenta == null) return null
        val descuentoEfectivo = descuento ?: BigDecimal.ZERO

        // §13, primera comparacion: `cuota_inicial < PV_efectivo`.
        val precioEfectivo = precioEfectivo(precioVenta, descuentoEfectivo)
        return if (CUOTA_INICIAL >= precioEfectivo) {
            null
        } else {
            cuotaDelMotor(precioVenta, descuentoEfectivo)
        }
    }

    /** `PV_efectivo = precio_venta x (1 - descuento/100)` (§3.2), con la precision del motor. */
    private fun precioEfectivo(
        precioVenta: BigDecimal,
        descuento: BigDecimal,
    ): BigDecimal {
        val mc = AritmeticaFinanciera.MC
        return precioVenta.multiply(BigDecimal.ONE.subtract(descuento.divide(CIEN, mc)), mc)
    }

    /**
     * Corre el motor UNA vez con los defaults de §6.1 y devuelve su
     * `cuota_final`, o `null` si el motor lanza (se registra un `warn`: es una
     * anomalia que alguien debe poder ver en los logs, nunca un 500) o si el
     * `Principal` que devuelve no cumple la segunda comparacion de §13,
     * `valor_residual < Principal`.
     */
    private fun cuotaDelMotor(
        precioVenta: BigDecimal,
        descuentoEfectivo: BigDecimal,
    ): BigDecimal? {
        val resultado =
            runCatching {
                MotorSimulacion.calcular(
                    ParametrosSimulacion(
                        modo = MODO,
                        precioVenta = precioVenta,
                        descuento = descuentoEfectivo,
                        cuotaInicial = CUOTA_INICIAL,
                        plazoMeses = PLAZO_MESES,
                        tea = TEA,
                        valorResidual = VALOR_RESIDUAL,
                    ),
                )
            }.getOrElse { error ->
                log.warn(
                    "Cuota efimera no calculable: el motor fallo con los defaults de §6.1. " +
                        "precioVenta={} descuento={}",
                    precioVenta,
                    descuentoEfectivo,
                    error,
                )
                null
            }

        if (resultado == null || VALOR_RESIDUAL >= resultado.principal) return null
        return resultado.cuotaFinal
    }
}
