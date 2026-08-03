package pe.quantum.crm.domain.tareas

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import pe.quantum.crm.shared.enums.EstadoAccion
import pe.quantum.crm.shared.enums.TipoAccion
import java.time.LocalDateTime

/**
 * Tarea interna de Quantum (tabla `tareas`, migracion V15).
 * `id_oportunidad = NULL` → tarea de prospeccion vinculada a la empresa
 * (reglas_negocio.md §10.2).
 */
@Entity
@Table(name = "tareas")
@Suppress("LongParameterList") // Una entidad JPA refleja las columnas de su tabla.
class Tarea(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,
    @Column(name = "id_empresa", nullable = false)
    val idEmpresa: Long,
    @Column(name = "id_oportunidad")
    val idOportunidad: Long? = null,
    @Column(name = "id_contacto")
    var idContacto: Long? = null,
    @Column(name = "id_asignado")
    var idAsignado: Long? = null,
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(name = "tipo_accion", nullable = false, columnDefinition = "tipo_accion_enum")
    var tipoAccion: TipoAccion,
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(name = "estado_accion", nullable = false, columnDefinition = "estado_accion_enum")
    var estadoAccion: EstadoAccion = EstadoAccion.pendiente,
    var descripcion: String? = null,
    @Column(name = "fecha_ejecucion")
    var fechaEjecucion: LocalDateTime? = null,
    @Column(name = "created_at", nullable = false)
    val createdAt: LocalDateTime = LocalDateTime.now(),
    @Column(name = "created_by", nullable = false)
    val createdBy: Long,
    @Column(name = "updated_at", nullable = false)
    var updatedAt: LocalDateTime = LocalDateTime.now(),
    @Column(name = "updated_by", nullable = false)
    var updatedBy: Long,
)
