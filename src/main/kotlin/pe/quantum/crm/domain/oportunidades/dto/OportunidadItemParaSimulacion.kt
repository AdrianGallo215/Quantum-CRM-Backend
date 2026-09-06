package pe.quantum.crm.domain.oportunidades.dto

import java.math.BigDecimal

/**
 * Datos del item que necesita el modulo `simulaciones` para prellenar los
 * campos esenciales (reglas_simulaciones.md §6.1) y para la agregacion de
 * cuota (§6.2). `idVendedor` viaja aqui para que `SimulacionPermisos` aplique
 * su regla de alcance sin una segunda ida a la base
 * (plan-09-mapa-simulaciones-modulo.md, decision D32).
 */
data class OportunidadItemParaSimulacion(
    val id: Long,
    val idOportunidad: Long,
    val idEmpresa: Long,
    val idVendedor: Long,
    val idModelo: Long,
    val cantidad: Int?,
    val precioVenta: BigDecimal?,
    val descuento: BigDecimal?,
    val cuotaFinanciadora: BigDecimal,
)
