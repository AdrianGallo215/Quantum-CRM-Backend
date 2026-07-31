package pe.quantum.crm.domain.empresas

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.JpaSpecificationExecutor
import org.springframework.data.jpa.repository.Query

interface EmpresaRepository :
    JpaRepository<Empresa, Long>,
    JpaSpecificationExecutor<Empresa> {
    fun findByRuc(ruc: String): Empresa?

    fun existsByRuc(ruc: String): Boolean

    /** Ids de empresas sin carpeta de Drive (backfill, ver modulo `mantenimiento`). */
    @Query("select e.id from Empresa e where e.driveFolderId is null order by e.id")
    fun findIdsSinCarpetaDrive(): List<Long>
}
