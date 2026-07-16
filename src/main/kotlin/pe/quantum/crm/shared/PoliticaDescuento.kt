package pe.quantum.crm.shared

import pe.quantum.crm.shared.enums.AprobadorSolicitud
import java.math.BigDecimal

/**
 * Limites de descuento por rol y derivacion del aprobador
 * (docs/gerencia_contrato_frontend.md §2): vendedor/analista hasta 3%, jdv
 * hasta 7%, gerencia/admin sin limite. Por encima del limite propio el cambio
 * requiere una solicitud aprobada.
 */
object PoliticaDescuento {
    val LIMITE_VENDEDOR: BigDecimal = BigDecimal(3)
    val LIMITE_JDV: BigDecimal = BigDecimal(7)

    /** Limite directo del rol; null = sin limite. */
    fun limitePara(rol: String): BigDecimal? =
        when (rol) {
            "vendedor", "analista" -> LIMITE_VENDEDOR
            "jdv" -> LIMITE_JDV
            else -> null
        }

    fun excedeLimite(
        rol: String,
        dcto: BigDecimal?,
    ): Boolean {
        if (dcto == null) return false
        val limite = limitePara(rol) ?: return false
        return dcto > limite
    }

    /** Quien aprueba un descuento fuera de limite; null si no requiere solicitud. */
    fun aprobadorPara(
        rol: String,
        dcto: BigDecimal,
    ): AprobadorSolicitud? =
        when {
            !excedeLimite(rol, dcto) -> null
            dcto <= LIMITE_JDV && (rol == "vendedor" || rol == "analista") -> AprobadorSolicitud.jdv
            else -> AprobadorSolicitud.gerencia
        }
}
