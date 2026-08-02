package pe.quantum.crm.domain.tareas

import jakarta.persistence.Column
import jakarta.persistence.Embeddable
import jakarta.persistence.EmbeddedId
import jakarta.persistence.Entity
import jakarta.persistence.Table
import java.io.Serializable
import java.time.LocalDateTime

/** PK compuesta de la vinculacion tarea-colaborador. */
@Embeddable
data class TareaResponsableId(
    @Column(name = "id_tarea")
    val idTarea: Long = 0,
    @Column(name = "id_empleado")
    val idEmpleado: Long = 0,
) : Serializable

/**
 * Colaborador adicional de una tarea (tabla `tarea_responsables`, migracion V31).
 * El dueno de la tarea sigue siendo `Tarea.idAsignado`; esta tabla es
 * estrictamente para colaboradores adicionales (trabajo en conjunto).
 */
@Entity
@Table(name = "tarea_responsables")
class TareaResponsable(
    @EmbeddedId
    val id: TareaResponsableId,
    @Column(name = "created_at", nullable = false)
    val createdAt: LocalDateTime = LocalDateTime.now(),
    @Column(name = "created_by", nullable = false)
    val createdBy: Long,
)
