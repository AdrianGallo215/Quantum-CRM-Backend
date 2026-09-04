package pe.quantum.crm.domain.oportunidades

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Calculo de `monto_total` como funcion pura unica y reutilizable (B3.2):
 *
 *     monto_total = cantidad × precio_unitario × (1 − dcto/100)
 *
 * - `dcto` null se trata como 0.
 * - `cantidad` o `precio_unitario` null → monto_total null (reglas §7.2).
 */
object MontoTotal {
    private val CIEN = BigDecimal(100)

    fun calcular(
        cantidad: Int?,
        precioUnitario: BigDecimal?,
        dcto: BigDecimal?,
    ): BigDecimal? {
        if (cantidad == null || precioUnitario == null) {
            return null
        }
        val factorDescuento = BigDecimal.ONE - (dcto ?: BigDecimal.ZERO).divide(CIEN)
        return BigDecimal(cantidad)
            .multiply(precioUnitario)
            .multiply(factorDescuento)
            .setScale(2, RoundingMode.HALF_UP)
    }

    /**
     * Suma los subtotales de una lista de items (plan-05-mapa-migrar-items.md,
     * decision D15). Un item incompleto (cantidad o precioVenta null) aporta 0 a
     * la suma en vez de anular el total: a diferencia de un solo campo de
     * `oportunidades`, un item incompleto no debe tumbar el monto de los demas
     * items que si estan completos. Null solo si NINGUN item tiene datos
     * completos, o si la lista esta vacia.
     */
    fun sumarItems(items: List<OportunidadItem>): BigDecimal? {
        val subtotales = items.mapNotNull { calcular(it.cantidad, it.precioVenta, it.descuento) }
        if (subtotales.isEmpty()) return null
        return subtotales.reduce(BigDecimal::add)
    }
}
