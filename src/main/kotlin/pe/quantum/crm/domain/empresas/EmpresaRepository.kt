package pe.quantum.crm.domain.empresas

import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.JpaSpecificationExecutor
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface EmpresaRepository :
    JpaRepository<Empresa, Long>,
    JpaSpecificationExecutor<Empresa> {
    fun findByRuc(ruc: String): Empresa?

    fun existsByRuc(ruc: String): Boolean

    /** Ids de empresas sin carpeta de Drive (backfill, ver modulo `mantenimiento`). */
    @Query("select e.id from Empresa e where e.driveFolderId is null order by e.id")
    fun findIdsSinCarpetaDrive(): List<Long>

    /**
     * Bloqueo pesimista de fila: usado por `asegurarCarpetaDrive` para que dos
     * requests concurrentes sobre la misma empresa no creen dos carpetas en Drive.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select e from Empresa e where e.id = :id")
    fun findByIdForUpdate(
        @Param("id") id: Long,
    ): Empresa?
}
