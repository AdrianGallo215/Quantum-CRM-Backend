package pe.quantum.crm.shared

import pe.quantum.crm.shared.enums.AprobadorSolicitud
import java.math.BigDecimal

/**
 * Limites de descuento por rol y derivacion del aprobador
 * (docs/gerencia_contrato_frontend.md §2): vendedor/analista hasta 3%, jdv
 * hasta 7%, gerencia/admin sin limite. Por encima del limite propio el cambio
 * requiere una solicitud aprobada.
 */
@Suppress("MagicNumber") // 3% y 7% son los limites de negocio, no numeros arbitrarios.
object PoliticaDescuento {
    val LIMITE_VENDEDOR: BigDecimal = BigDecimal(3)
    val LIMITE_JDV: BigDecimal = BigDecimal(7)

    /**
     * Roles que pueden aplicar cualquier descuento sin solicitud. Lista cerrada a
     * proposito: es la unica puerta al "sin limite" y por eso se enumera aqui en
     * vez de deducirse por descarte.
     */
    private val ROLES_SIN_LIMITE = setOf("admin", "gerencia")

    /**
     * Limite directo del rol; null = sin limite.
     *
     * Fail-closed: cualquier rol que no este en `ROLES_SIN_LIMITE` y no sea `jdv`
     * cae en el limite mas bajo. Eso cubre `otro` (valor real de `RolEmpleado`),
     * el `gerente` viejo que V25 renombro a `gerencia` y cualquier rol futuro que
     * se agregue al enum sin pasar por aqui: un rol desconocido pide aprobacion,
     * no la esquiva.
     */
    fun limitePara(rol: String): BigDecimal? =
        when (rol) {
            in ROLES_SIN_LIMITE -> null
            "jdv" -> LIMITE_JDV
            else -> LIMITE_VENDEDOR
        }

    @Suppress("ReturnCount") // Guard clauses de salida temprana; dividir la funcion no mejora la legibilidad.
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
