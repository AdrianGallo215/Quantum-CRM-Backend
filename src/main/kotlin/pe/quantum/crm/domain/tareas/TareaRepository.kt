package pe.quantum.crm.domain.tareas

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.JpaSpecificationExecutor
import pe.quantum.crm.shared.enums.EstadoAccion

interface TareaRepository :
    JpaRepository<Tarea, Long>,
    JpaSpecificationExecutor<Tarea> {
    fun findByEstadoAccionAndIdAsignadoIsNotNullAndFechaEjecucionIsNotNull(estadoAccion: EstadoAccion): List<Tarea>
}
