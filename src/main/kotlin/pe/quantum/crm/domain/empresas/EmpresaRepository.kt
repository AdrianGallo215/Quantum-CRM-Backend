package pe.quantum.crm.domain.empresas

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.JpaSpecificationExecutor
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.transaction.annotation.Transactional

interface EmpresaRepository :
    JpaRepository<Empresa, Long>,
    JpaSpecificationExecutor<Empresa> {
    fun findByRuc(ruc: String): Empresa?

    fun existsByRuc(ruc: String): Boolean

    /** Ids de empresas sin carpeta de Drive (backfill, ver modulo `mantenimiento`). */
    @Query("select e.id from Empresa e where e.driveFolderId is null order by e.id")
    fun findIdsSinCarpetaDrive(): List<Long>

    /** Carpeta de Drive de la empresa, leida de la BD sin pasar por el contexto de persistencia. */
    @Query("select e.driveFolderId from Empresa e where e.id = :id")
    fun findDriveFolderId(
        @Param("id") id: Long,
    ): String?

    /**
     * Fija la carpeta de Drive SOLO si la empresa aun no tiene ninguna, y devuelve
     * las filas afectadas (1 = la fijo esta peticion, 0 = otra se adelanto).
     *
     * Sustituye al `SELECT ... FOR UPDATE` que antes envolvia la llamada a Drive:
     * da la misma garantia de "una sola carpeta por empresa" — el UPDATE es atomico
     * y su `WHERE ... IS NULL` solo puede casar una vez — pero la latencia de red
     * transcurre ya sin la fila bloqueada ni la conexion del pool retenida.
     *
     * `@Transactional` propio: es una escritura y puede invocarse sin transaccion
     * ambiente. Sin `clearAutomatically`: vaciar el contexto de persistencia
     * desharia las entidades que la transaccion llamante tenga en curso.
     */
    @Modifying
    @Transactional
    @Query("update Empresa e set e.driveFolderId = :carpeta where e.id = :id and e.driveFolderId is null")
    fun asignarCarpetaDriveSiFalta(
        @Param("id") id: Long,
        @Param("carpeta") carpeta: String,
    ): Int
}
