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
}
