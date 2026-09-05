package pe.quantum.crm.shared.simulacion

import pe.quantum.crm.shared.enums.ModoSimulacion
import java.math.BigDecimal

/**
 * Entrada del motor de calculo: los campos esenciales de una simulacion
 * (reglas_simulaciones.md §3.2). Estructura pura, sin dependencias de Spring,
 * JPA ni framework: la consumen dos flujos, el que persiste y la Calculadora
 * Financiera, que no persiste nada (§9).
 *
 * `precio_venta` es UNITARIO y CON IGV. La cantidad de unidades del item NO
 * participa del calculo (§3.2).
 *
 * `tea` va en escala 1-100 (15.00 = 15%). OJO: `financiadoras.tea` usa escala
 * fraccionaria (0.15). NO se comparan ni se copian sin convertir.
 */
data class ParametrosSimulacion(
    val modo: ModoSimulacion,
    val precioVenta: BigDecimal,
    val descuento: BigDecimal,
    val cuotaInicial: BigDecimal,
    val plazoMeses: Int,
    val tea: BigDecimal,
    val valorResidual: BigDecimal,
)
