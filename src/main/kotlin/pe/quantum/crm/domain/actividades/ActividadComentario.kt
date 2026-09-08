package pe.quantum.crm.domain.actividades

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.LocalDateTime

/**
 * Comentario de seguimiento sobre una tarea o un evento (tabla
 * `actividad_comentarios`, migracion V48).
 *
 * Append-only por diseno: no hay `updated_at` ni operacion de edicion. Un
 * comentario es un hecho fechado, no un campo mutable.
 *
 * `idTarea` e `idEvento` son mutuamente excluyentes (CHECK
 * `chk_comentario_origen`): exactamente uno de los dos es NOT NULL.
 */
@Entity
@Table(name = "actividad_comentarios")
class ActividadComentario(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,
    @Column(name = "id_tarea")
    val idTarea: Long? = null,
    @Column(name = "id_evento")
    val idEvento: Long? = null,
    @Column(nullable = false)
    val texto: String,
    @Column(name = "created_at", nullable = false)
    val createdAt: LocalDateTime = LocalDateTime.now(),
    @Column(name = "created_by", nullable = false)
    val createdBy: Long,
)
