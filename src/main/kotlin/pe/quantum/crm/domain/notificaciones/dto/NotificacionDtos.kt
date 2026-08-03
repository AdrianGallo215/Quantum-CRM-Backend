package pe.quantum.crm.domain.notificaciones.dto

import pe.quantum.crm.domain.empleados.dto.EmpleadoResumen
import java.time.LocalDateTime

data class NotificacionDto(
    val id: Long,
    val tipo: String,
    val mensaje: String,
    val entidadTipo: String,
    val entidadId: Long,
    val leida: Boolean,
    val createdAt: LocalDateTime,
    val actor: EmpleadoResumen?,
)

data class ContadorNoLeidasDto(
    val count: Long,
)
