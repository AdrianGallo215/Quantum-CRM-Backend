package pe.quantum.crm.domain.eventos

import org.springframework.data.jpa.repository.JpaRepository
import pe.quantum.crm.shared.enums.EstadoEvento
import java.time.LocalDateTime

interface EventoRepository : JpaRepository<Evento, Long> {
    fun findByIdOportunidadOrderByIdAsc(idOportunidad: Long): List<Evento>

    fun findByIdEmpresaAndIdOportunidadIsNullOrderByIdAsc(idEmpresa: Long): List<Evento>

    fun findByEstadoAndFechaEstimadaIsNotNull(estado: EstadoEvento): List<Evento>

    /** Historial de un empleado por rango de `created_at` (modulo actividades). */
    fun findByCreatedByAndCreatedAtBetweenOrderByCreatedAtDesc(
        createdBy: Long,
        desde: LocalDateTime,
        hasta: LocalDateTime,
    ): List<Evento>
}
