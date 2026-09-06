package pe.quantum.crm.domain.simulaciones

import pe.quantum.crm.domain.simulaciones.dto.CampoDiffDto
import java.math.BigDecimal

/**
 * Diff entre dos eventos consecutivos del historial de una simulacion
 * (`reglas_simulaciones.md` §7.1, `plan-11-mapa-historial-calculadora.md`
 * decision D44).
 *
 * Funcion pura en un `object`, sin Spring ni JPA — mismo estilo que
 * [NombreSimulacion]. El diff se computa al leer y NUNCA se persiste (§7.1):
 * es informacion derivable de los snapshots que ya existen en
 * `simulacion_log`.
 */
object DiffSimulacion {
    /**
     * Compara los 10 campos del snapshot entre [anterior] y [actual], en el
     * orden declarado abajo, e incluye en el resultado SOLO los que
     * cambiaron.
     *
     * Si [anterior] es `null` (es el primer evento de todos para esa
     * simulacion, K23 de `plan-11`), no hay "antes" que mostrar: el diff es
     * vacio.
     *
     * Los `BigDecimal` se comparan por VALOR (`compareTo(...) != 0`), nunca
     * por `equals`: `100.00` y `100.0` representan el mismo importe y no
     * deben salir marcados como cambio pese a tener distinta escala.
     */
    fun calcular(
        anterior: SimulacionLog?,
        actual: SimulacionLog,
    ): List<CampoDiffDto> {
        if (anterior == null) return emptyList()

        return listOfNotNull(
            diffEnum("modo", anterior.modo, actual.modo),
            diffMonto("precioVenta", anterior.precioVenta, actual.precioVenta),
            diffMonto("descuento", anterior.descuento, actual.descuento),
            diffMonto("cuotaInicial", anterior.cuotaInicial, actual.cuotaInicial),
            diffEntero("plazoMeses", anterior.plazoMeses, actual.plazoMeses),
            diffMonto("tea", anterior.tea, actual.tea),
            diffMonto("valorResidual", anterior.valorResidual, actual.valorResidual),
            diffEntero("diasTrabajados", anterior.diasTrabajados, actual.diasTrabajados),
            diffMonto("comisionEstructuracion", anterior.comisionEstructuracion, actual.comisionEstructuracion),
            diffMonto("cuotaFinal", anterior.cuotaFinal, actual.cuotaFinal),
        )
    }

    /** `BigDecimal?`: comparacion por valor, nunca por `equals` (escala). */
    private fun diffMonto(
        campo: String,
        valorAnterior: BigDecimal?,
        valorActual: BigDecimal?,
    ): CampoDiffDto? {
        val cambio =
            when {
                valorAnterior == null && valorActual == null -> false
                valorAnterior == null || valorActual == null -> true
                else -> valorAnterior.compareTo(valorActual) != 0
            }
        if (!cambio) return null
        return CampoDiffDto(campo, valorAnterior?.toPlainString(), valorActual?.toPlainString())
    }

    /** `Int?`: comparacion directa. */
    private fun diffEntero(
        campo: String,
        valorAnterior: Int?,
        valorActual: Int?,
    ): CampoDiffDto? {
        if (valorAnterior == valorActual) return null
        return CampoDiffDto(campo, valorAnterior?.toString(), valorActual?.toString())
    }

    /** Enum `modo`: comparacion directa, valor crudo (`.name`), no traducido. */
    private fun <T : Enum<T>> diffEnum(
        campo: String,
        valorAnterior: T?,
        valorActual: T?,
    ): CampoDiffDto? {
        if (valorAnterior == valorActual) return null
        return CampoDiffDto(campo, valorAnterior?.name, valorActual?.name)
    }
}
