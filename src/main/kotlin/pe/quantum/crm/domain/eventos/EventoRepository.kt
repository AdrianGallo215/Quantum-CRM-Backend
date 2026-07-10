package pe.quantum.crm.domain.eventos

import org.springframework.data.jpa.repository.JpaRepository
import pe.quantum.crm.shared.enums.EstadoEvento

interface EventoRepository : JpaRepository<Evento, Long> {
    fun findByIdOportunidadOrderByIdAsc(idOportunidad: Long): List<Evento>

    fun findByIdEmpresaAndIdOportunidadIsNullOrderByIdAsc(idEmpresa: Long): List<Evento>

    fun findByEstadoAndFechaEstimadaIsNotNull(estado: EstadoEvento): List<Evento>
}
