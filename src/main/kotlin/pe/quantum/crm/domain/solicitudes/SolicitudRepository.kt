package pe.quantum.crm.domain.solicitudes

import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.JpaSpecificationExecutor
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import pe.quantum.crm.shared.enums.EntidadSolicitud
import pe.quantum.crm.shared.enums.EstadoSolicitud
import pe.quantum.crm.shared.enums.TipoSolicitud

interface SolicitudRepository :
    JpaRepository<Solicitud, Long>,
    JpaSpecificationExecutor<Solicitud> {
    fun existsByTipoAndEntidadTipoAndEntidadIdAndEstado(
        tipo: TipoSolicitud,
        entidadTipo: EntidadSolicitud,
        entidadId: Long,
        estado: EstadoSolicitud,
    ): Boolean

    /** SELECT ... FOR UPDATE: dos aprobadores en paralelo no resuelven dos veces. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from Solicitud s where s.id = :id")
    fun findParaResolver(id: Long): Solicitud?
}
