package pe.quantum.crm.domain.oportunidades

import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.JpaSpecificationExecutor
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
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

    /** Ids de oportunidades sin carpeta de Drive (backfill, ver modulo `mantenimiento`). */
    @Query("select o.id from Oportunidad o where o.driveFolderId is null order by o.id")
    fun findIdsSinCarpetaDrive(): List<Long>

    /**
     * Bloqueo pesimista de fila: usado por `asegurarCarpetaDrive` para que dos
     * requests concurrentes sobre la misma oportunidad no creen dos carpetas en Drive.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from Oportunidad o where o.id = :id")
    fun findByIdForUpdate(
        @Param("id") id: Long,
    ): Oportunidad?
}

interface OportunidadEstadoLogRepository : JpaRepository<OportunidadEstadoLog, Long> {
    fun findByIdOportunidadOrderByChangedAtAscIdAsc(idOportunidad: Long): List<OportunidadEstadoLog>

    fun findFirstByIdOportunidadOrderByChangedAtDescIdDesc(idOportunidad: Long): OportunidadEstadoLog?
}

interface OportunidadContactoRepository : JpaRepository<OportunidadContacto, OportunidadContactoId> {
    fun findByIdIdOportunidad(idOportunidad: Long): List<OportunidadContacto>

    fun findByIdIdContacto(idContacto: Long): List<OportunidadContacto>

    fun countByIdIdContacto(idContacto: Long): Long
}
