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
        get() = rol == "admin" || rol == "gerencia"

    /**
     * Roles de apoyo: sin cartera propia, solo lectura sobre empresas y
     * oportunidades. Solo ven aquello en lo que colaboran via una tarea
     * (matriz_permisos.md). Unica fuente de verdad de esta condicion: el resto
     * de modulos consulta este predicado, nunca compara el string del rol.
     */
    val esRolApoyo: Boolean
        get() = rol == "analista" || rol == "otro"

    /** vendedor/analista solo ven sus propios registros (contrato_api.md §5). */
    val visibilidadRestringida: Boolean
        get() = !esSupervisor

    /** Id a pasar como filtro de vendedor en queries; null cuando el rol ve todo. */
    val filtroVendedor: Long?
        get() = id.takeIf { visibilidadRestringida }

    /** true si un registro de `idVendedor` cae dentro de la visibilidad de este usuario. */
    fun alcanza(idVendedor: Long?): Boolean = !visibilidadRestringida || idVendedor == id

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
        // Los tres datos se resuelven antes de decidir: falte el que falte, la
        // respuesta es la misma (401), asi que un unico punto de fallo basta.
        val auth = SecurityContextHolder.getContext().authentication
        val id = auth?.principal as? Long
        val rol = auth?.authorities?.firstOrNull()?.authority?.removePrefix("ROLE_")
        if (id == null || rol == null) {
            throw CredencialesInvalidasException()
        }
        return UsuarioActual(id = id, rol = rol)
    }
}
