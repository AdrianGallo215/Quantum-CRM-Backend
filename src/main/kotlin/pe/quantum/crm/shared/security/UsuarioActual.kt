package pe.quantum.crm.shared.security

import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Component
import pe.quantum.crm.shared.exception.CredencialesInvalidasException

/**
 * Identidad del usuario autenticado, extraida del JWT por el filtro de seguridad.
 * `rol` es el nombre del enum `rol_empleado` (admin, gerente, jdv, vendedor,
 * analista, otro). Los servicios usan esto para el filtro de visibilidad por rol.
 */
data class UsuarioActual(
    val id: Long,
    val rol: String,
) {
    /** Roles que ven todo y pueden reasignar el vendedor de una empresa (matriz_permisos.md). */
    val esSupervisor: Boolean
        get() = rol == "admin" || rol == "gerente" || rol == "jdv"

    /** Roles que pueden confirmar el paso a `facturado` (matriz_permisos.md). */
    val puedeValidarFacturado: Boolean
        get() = rol == "admin" || rol == "gerente" || rol == "analista"

    /** vendedor/analista solo ven sus propios registros (contrato_api.md §5). */
    val visibilidadRestringida: Boolean
        get() = !esSupervisor
}

/** Lee la identidad del `SecurityContext`. Falla con 401 si no hay autenticacion. */
@Component
class UsuarioActualProvider {
    fun actual(): UsuarioActual {
        val auth =
            SecurityContextHolder.getContext().authentication
                ?: throw CredencialesInvalidasException()
        val id = auth.principal as? Long ?: throw CredencialesInvalidasException()
        val rol =
            auth.authorities.firstOrNull()?.authority?.removePrefix("ROLE_")
                ?: throw CredencialesInvalidasException()
        return UsuarioActual(id = id, rol = rol)
    }
}
