package pe.quantum.crm.domain.oportunidades

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.JpaSpecificationExecutor
import pe.quantum.crm.shared.enums.EstadoOportunidad

interface OportunidadRepository :
    JpaRepository<Oportunidad, Long>,
    JpaSpecificationExecutor<Oportunidad> {
    fun existsByIdEmpresaAndEstado(
        idEmpresa: Long,
        estado: EstadoOportunidad,
    ): Boolean

    fun existsByIdEmpresaAndEstadoIn(
        idEmpresa: Long,
        estados: Collection<EstadoOportunidad>,
    ): Boolean

    fun findByIdEmpresaAndEstadoIn(
        idEmpresa: Long,
        estados: Collection<EstadoOportunidad>,
    ): List<Oportunidad>
}

interface OportunidadEstadoLogRepository : JpaRepository<OportunidadEstadoLog, Long> {
    fun findByIdOportunidadOrderByChangedAtAscIdAsc(idOportunidad: Long): List<OportunidadEstadoLog>

    fun findFirstByIdOportunidadOrderByChangedAtDescIdDesc(idOportunidad: Long): OportunidadEstadoLog?
}

interface OportunidadContactoRepository : JpaRepository<OportunidadContacto, OportunidadContactoId> {
    fun findByIdIdOportunidad(idOportunidad: Long): List<OportunidadContacto>
}
