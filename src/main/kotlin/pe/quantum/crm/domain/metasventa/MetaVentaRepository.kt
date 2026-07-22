package pe.quantum.crm.domain.metasventa

import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.JpaSpecificationExecutor
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import pe.quantum.crm.shared.enums.EstadoMeta

interface MetaVentaRepository :
    JpaRepository<MetaVenta, Long>,
    JpaSpecificationExecutor<MetaVenta> {
    fun findByIdEmpleadoAndAnio(
        idEmpleado: Long,
        anio: Int,
    ): MetaVenta?

    fun findByIdEmpleadoInAndAnioAndEstado(
        idsEmpleado: Collection<Long>,
        anio: Int,
        estado: EstadoMeta,
    ): List<MetaVenta>

    /** SELECT ... FOR UPDATE: dos resolutores en paralelo no resuelven dos veces. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select m from MetaVenta m where m.id = :id")
    fun findByIdForUpdate(id: Long): MetaVenta?
}
