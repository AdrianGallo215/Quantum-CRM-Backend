package pe.quantum.crm.domain.tareas

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.JpaSpecificationExecutor
import org.springframework.data.jpa.repository.Query
import pe.quantum.crm.shared.enums.EstadoAccion

interface TareaRepository :
    JpaRepository<Tarea, Long>,
    JpaSpecificationExecutor<Tarea> {
    fun findByEstadoAccionAndIdAsignadoIsNotNullAndFechaEjecucionIsNotNull(estadoAccion: EstadoAccion): List<Tarea>

    @Query(
        """
        SELECT t FROM Tarea t
        WHERE t.idContacto = :idContacto
        ORDER BY COALESCE(t.fechaEjecucion, t.createdAt) DESC
        """,
    )
    fun findByIdContactoOrdenado(idContacto: Long): List<Tarea>
}
