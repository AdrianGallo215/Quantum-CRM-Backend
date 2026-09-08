package pe.quantum.crm.domain.actividades

import pe.quantum.crm.domain.actividades.dto.ComentarioDto
import pe.quantum.crm.shared.security.UsuarioActual

/**
 * Comentarios de seguimiento sobre tareas y eventos.
 *
 * Append-only: no hay editar ni borrar. Un comentario es un hecho fechado.
 */
interface ComentarioActividadService {
    /**
     * Agrega un comentario. Comprueba que el usuario alcance la actividad:
     * `NoEncontradoException` (404) si no, nunca 403 — es un recurso ajeno.
     */
    fun crear(
        tipo: TipoActividad,
        idActividad: Long,
        texto: String,
        usuario: UsuarioActual,
    ): ComentarioDto

    /** Comentarios de una actividad, del mas antiguo al mas reciente. */
    fun listar(
        tipo: TipoActividad,
        idActividad: Long,
        usuario: UsuarioActual,
    ): List<ComentarioDto>

    /**
     * Cuantos comentarios tiene cada actividad. SIN chequeo de visibilidad: solo
     * lo llama el historial, que ya filtro lo que el usuario puede ver.
     */
    fun contar(
        tipo: TipoActividad,
        idsActividad: Collection<Long>,
    ): Map<Long, Int>
}
