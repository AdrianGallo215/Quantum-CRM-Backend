package pe.quantum.crm.domain.solicitudes

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import pe.quantum.crm.shared.enums.AprobadorSolicitud
import pe.quantum.crm.shared.enums.EntidadSolicitud
import pe.quantum.crm.shared.enums.EstadoSolicitud
import pe.quantum.crm.shared.enums.TipoSolicitud
import java.math.BigDecimal
import java.time.LocalDateTime

/**
 * Solicitud de aprobacion (tabla `solicitudes`, migracion V26). Es a la vez la
 * capa intermedia de permisos y el registro de trazabilidad: nunca se borra y
 * su unica transicion es pendiente -> aprobada|denegada
 * (docs/gerencia_solicitudes_modelo_datos.md).
 */
@Entity
@Table(name = "solicitudes")
@Suppress("LongParameterList") // Una entidad JPA refleja las columnas de su tabla.
class Solicitud(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(nullable = false, columnDefinition = "tipo_solicitud_enum")
    val tipo: TipoSolicitud,
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(nullable = false, columnDefinition = "estado_solicitud_enum")
    var estado: EstadoSolicitud = EstadoSolicitud.pendiente,
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(name = "rol_aprobador", nullable = false, columnDefinition = "aprobador_solicitud_enum")
    val rolAprobador: AprobadorSolicitud,
    @Column(name = "id_solicitante", nullable = false)
    val idSolicitante: Long,
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(name = "entidad_tipo", nullable = false, columnDefinition = "entidad_solicitud_enum")
    val entidadTipo: EntidadSolicitud,
    @Column(name = "entidad_id", nullable = false)
    val entidadId: Long,
    @Column(name = "entidad_descripcion", nullable = false)
    val entidadDescripcion: String,
    @Column(nullable = false)
    val motivo: String,
    @Column(name = "dcto_solicitado")
    val dctoSolicitado: BigDecimal? = null,
    @Column(name = "id_vendedor_nuevo")
    val idVendedorNuevo: Long? = null,
    @Column(name = "id_resolutor")
    var idResolutor: Long? = null,
    @Column(name = "motivo_resolucion")
    var motivoResolucion: String? = null,
    @Column(name = "resolved_at")
    var resolvedAt: LocalDateTime? = null,
    @Column(name = "created_at", nullable = false)
    val createdAt: LocalDateTime = LocalDateTime.now(),
    @Column(name = "updated_at", nullable = false)
    var updatedAt: LocalDateTime = LocalDateTime.now(),
)
