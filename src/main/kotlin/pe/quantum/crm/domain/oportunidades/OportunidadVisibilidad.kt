package pe.quantum.crm.domain.oportunidades

import jakarta.persistence.criteria.CriteriaBuilder
import jakarta.persistence.criteria.Predicate
import jakarta.persistence.criteria.Root
import org.springframework.context.annotation.Lazy
import org.springframework.stereotype.Component
import pe.quantum.crm.domain.tareas.TareaService
import pe.quantum.crm.shared.exception.PermisoInsuficienteException
import pe.quantum.crm.shared.security.UsuarioActual

/**
 * Regla de visibilidad y autorizacion de oportunidades (matriz_permisos.md),
 * separada de `OportunidadServiceImpl` porque la usan tanto el detalle/escritura
 * (`alcanza`) como el listado paginado (`predicadoVisibilidad`), y ambos deben
 * aplicar exactamente la misma regla: rol de apoyo solo donde colabora via
 * tarea, vendedor solo lo suyo, supervisor todo.
 */
@Component
class OportunidadVisibilidad(
    // Solo la interfaz publica de tareas (regla 12). `@Lazy` porque tareas ya
    // depende de OportunidadService (vinculoVisible) y Spring Boot 3 rechaza los
    // ciclos de constructor; el proxy corta el ciclo al arrancar (mismo patron
    // que `EmpresaServiceImpl.contactoService`).
    @Lazy private val tareaService: TareaService,
) {
    /**
     * Roles de apoyo: solo lectura sobre oportunidades (matriz_permisos.md).
     * 403 y no 404 a proposito: la entidad puede ser perfectamente visible para
     * el (colabora en una tarea suya); lo que no tiene es permiso de escritura, y
     * el mensaje debe decirlo para que el cliente no lo confunda con "no existe".
     */
    fun rechazarSiEsApoyo(usuario: UsuarioActual) {
        if (usuario.esRolApoyo) {
            throw PermisoInsuficienteException(
                "Tu rol es de apoyo: puedes consultar esta oportunidad, pero no modificarla",
            )
        }
    }

    /**
     * Visibilidad unificada para detalle y listado. Rol de apoyo: solo donde
     * colabora via tarea (no tiene cartera propia). Vendedor: solo lo suyo.
     * Supervisor: todo.
     */
    fun alcanza(
        oportunidad: Oportunidad,
        usuario: UsuarioActual,
    ): Boolean =
        when {
            usuario.esRolApoyo ->
                oportunidad.id in tareaService.idsOportunidadesDondeColabora(usuario.id)
            usuario.visibilidadRestringida -> oportunidad.idVendedor == usuario.id
            else -> true
        }

    /**
     * IDs de oportunidades donde el rol de apoyo colabora, resuelto una sola vez
     * ANTES de construir la Specification del listado, no dentro de su lambda:
     * Spring Data JPA evalua `toPredicate` dos veces por pagina (contenido y
     * conteo), y esto es una consulta, no un `equal` gratis como el resto de
     * predicados. `null` cuando el usuario no es rol de apoyo.
     */
    fun idsColaboracion(usuario: UsuarioActual): Set<Long>? =
        if (usuario.esRolApoyo) tareaService.idsOportunidadesDondeColabora(usuario.id) else null

    /**
     * Predicado de visibilidad para el listado, misma regla que [alcanza]:
     * rol de apoyo restringido a [idsColaboracion] (conjunto vacio → `false`
     * explicito, porque `in(emptySet())` es SQL invalido o no filtra nada),
     * vendedor restringido a lo suyo. `null` cuando el usuario ve todo
     * (supervisor) y el llamador debe aplicar sus propios filtros opcionales.
     */
    fun predicadoVisibilidad(
        root: Root<Oportunidad>,
        cb: CriteriaBuilder,
        idsColaboracion: Set<Long>?,
        usuario: UsuarioActual,
    ): Predicate? =
        when {
            idsColaboracion != null ->
                if (idsColaboracion.isEmpty()) cb.disjunction() else root.get<Long>("id").`in`(idsColaboracion)
            usuario.visibilidadRestringida -> cb.equal(root.get<Long>("idVendedor"), usuario.id)
            else -> null
        }
}
