package pe.quantum.crm.domain.actividades

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.LocalDateTime

/**
 * Una modificacion de UN campo de una tarea o un evento (tabla
 * `actividad_auditoria`, migracion V49).
 *
 * `campo` guarda el nombre publico en snake_case (`fecha_ejecucion`), no el de
 * la propiedad Kotlin: quien lee esta tabla es la vista de supervision, que
 * habla el idioma del contrato de API.
 */
@Entity
@Table(name = "actividad_auditoria")
@Suppress("LongParameterList") // Una entidad JPA refleja las columnas de su tabla.
class ActividadAuditoria(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,
    @Column(name = "id_tarea")
    val idTarea: Long? = null,
    @Column(name = "id_evento")
    val idEvento: Long? = null,
    @Column(nullable = false)
    val campo: String,
    @Column(name = "valor_anterior")
    val valorAnterior: String? = null,
    @Column(name = "valor_nuevo")
    val valorNuevo: String? = null,
    @Column(name = "changed_at", nullable = false)
    val changedAt: LocalDateTime = LocalDateTime.now(),
    @Column(name = "changed_by", nullable = false)
    val changedBy: Long,
)
