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
