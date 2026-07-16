package pe.quantum.crm.shared.security

import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Component
import pe.quantum.crm.shared.exception.CredencialesInvalidasException

/**
 * Identidad del usuario autenticado, extraida del JWT por el filtro de seguridad.
 * `rol` es el nombre del enum `rol_empleado` (admin, gerencia, jdv, vendedor,
 * analista, otro). Los servicios usan esto para el filtro de visibilidad por rol.
 */
data class UsuarioActual(
    val id: Long,
    val rol: String,
) {
    /** Roles que ven todo el pipeline y la cartera del equipo (matriz_permisos.md). */
    val esSupervisor: Boolean
        get() = rol == "admin" || rol == "gerencia" || rol == "jdv"

    /** Roles que pueden confirmar el paso a `facturado` (matriz_permisos.md). */
    val puedeValidarFacturado: Boolean
        get() = rol == "admin" || rol == "gerencia" || rol == "analista"

    /** vendedor/analista solo ven sus propios registros (contrato_api.md §5). */
    val visibilidadRestringida: Boolean
        get() = !esSupervisor

    /** Cartera Maestra: exclusiva de gerencia y admin (gerencia_contrato_frontend.md §1). */
    val puedeVerCarteraMaestra: Boolean
        get() = rol == "admin" || rol == "gerencia"

    /** Reasignación directa de empresas; el jdv ahora requiere solicitud aprobada. */
    val puedeReasignarDirecto: Boolean
        get() = rol == "admin" || rol == "gerencia"

    /** true si este usuario puede resolver una solicitud dirigida a `rolAprobador`. */
    fun puedeAprobar(rolAprobador: String): Boolean = rol == "admin" || rol == rolAprobador
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
