package pe.quantum.crm.domain.actividades

import org.springframework.data.jpa.repository.JpaRepository

interface ActividadComentarioRepository : JpaRepository<ActividadComentario, Long> {
    fun findByIdTareaOrderByCreatedAtAsc(idTarea: Long): List<ActividadComentario>

    fun findByIdEventoOrderByCreatedAtAsc(idEvento: Long): List<ActividadComentario>

    /** Para contar comentarios de muchas actividades de una vez (listado del historial). */
    fun findByIdTareaInOrderByCreatedAtAsc(idsTarea: Collection<Long>): List<ActividadComentario>

    fun findByIdEventoInOrderByCreatedAtAsc(idsEvento: Collection<Long>): List<ActividadComentario>
}
