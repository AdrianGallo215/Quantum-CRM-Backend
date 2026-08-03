package pe.quantum.crm.domain.tareas.dto

import java.time.LocalDateTime

/** Actividad en la linea de tiempo del detalle de contacto. Solo tareas por ahora (contrato_api.md §9). */
data class ActividadContactoDto(
    val id: Long,
    val tipo: String = "tarea",
    val titulo: String,
    val descripcion: String?,
    val fecha: LocalDateTime,
    val estado: String,
)
