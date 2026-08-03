package pe.quantum.crm.domain.notificaciones

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.time.LocalDateTime

/**
 * Notificacion in-app (tabla `notificaciones`, migracion V22). `idActor` es
 * nullable: los recordatorios generados por un job programado no tienen
 * actor humano.
 */
@Entity
@Table(name = "notificaciones")
@Suppress("LongParameterList") // Una entidad JPA refleja las columnas de su tabla.
class Notificacion(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,
    @Column(name = "id_empleado_destinatario", nullable = false)
    val idEmpleadoDestinatario: Long,
    @Column(name = "id_actor")
    val idActor: Long?,
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(nullable = false, columnDefinition = "tipo_notificacion_enum")
    val tipo: TipoNotificacion,
    @Column(nullable = false)
    val mensaje: String,
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(name = "entidad_tipo", nullable = false, columnDefinition = "entidad_notificacion_enum")
    val entidadTipo: EntidadNotificacion,
    @Column(name = "entidad_id", nullable = false)
    val entidadId: Long,
    @Column(nullable = false)
    var leida: Boolean = false,
    @Column(name = "created_at", nullable = false)
    val createdAt: LocalDateTime = LocalDateTime.now(),
)
