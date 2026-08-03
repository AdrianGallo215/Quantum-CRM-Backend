package pe.quantum.crm.domain.notificaciones

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.LocalDateTime

interface NotificacionRepository : JpaRepository<Notificacion, Long> {
    fun findTop20ByIdEmpleadoDestinatarioOrderByCreatedAtDesc(idEmpleadoDestinatario: Long): List<Notificacion>

    fun countByIdEmpleadoDestinatarioAndLeidaFalse(idEmpleadoDestinatario: Long): Long

    fun findByIdAndIdEmpleadoDestinatario(
        id: Long,
        idEmpleadoDestinatario: Long,
    ): Notificacion?

    fun findByIdEmpleadoDestinatarioAndLeidaFalse(idEmpleadoDestinatario: Long): List<Notificacion>

    @Modifying
    @Query("DELETE FROM Notificacion n WHERE n.leida = true AND n.createdAt < :umbral")
    fun purgarLeidasAntesDe(
        @Param("umbral") umbral: LocalDateTime,
    ): Int
}
