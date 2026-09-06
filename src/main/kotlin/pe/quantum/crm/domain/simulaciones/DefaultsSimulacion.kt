package pe.quantum.crm.domain.simulaciones

import java.math.BigDecimal

/**
 * Defaults de `simulaciones` en V43 (DEFAULT de columna), NO los de la
 * tabla de reglas_simulaciones.md §6.1 (esos son los de la cuota efimera
 * del item, un calculo distinto que no crea ninguna simulacion — ver
 * Plan F). Ninguno de los 5 valores de §6.1 aplica aqui: `precio_venta`
 * y `descuento` se toman del item (§6.1), y `plazo_meses`/`tea`/
 * `cuota_inicial` son obligatorios en `CrearSimulacionRequest`, asi que
 * nunca caen a un default. `valor_residual` de §6.1 es 25000, distinto
 * del 0 de V43: aqui se replica el DEFAULT de la columna, no la tabla.
 *
 * Compartidos entre `SimulacionServiceImpl` y la Calculadora Financiera (D51).
 */
object DefaultsSimulacion {
    val DESCUENTO: BigDecimal = BigDecimal.ZERO
    val VALOR_RESIDUAL: BigDecimal = BigDecimal.ZERO
    const val DIAS_TRABAJADOS = 22
    val COMISION_ESTRUCTURACION: BigDecimal = BigDecimal("1180")
}
