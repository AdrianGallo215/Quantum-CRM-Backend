package pe.quantum.crm.domain.oportunidades

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import pe.quantum.crm.shared.enums.EstadoOportunidad
import java.time.LocalDateTime

/**
 * Historial de cambios de estado (tabla `oportunidad_estados_log`, migracion V11).
 * El primer registro de cada oportunidad tiene `estado_anterior = NULL`. Es la
 * fuente de verdad de la pronta facturacion (reglas_negocio.md §6).
 */
@Entity
@Table(name = "oportunidad_estados_log")
class OportunidadEstadoLog(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,
    @Column(name = "id_oportunidad", nullable = false)
    val idOportunidad: Long,
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(name = "estado_anterior", columnDefinition = "estado_op_enum")
    val estadoAnterior: EstadoOportunidad? = null,
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(name = "estado_nuevo", nullable = false, columnDefinition = "estado_op_enum")
    val estadoNuevo: EstadoOportunidad,
    @Column(name = "changed_at", nullable = false)
    val changedAt: LocalDateTime = LocalDateTime.now(),
    @Column(name = "changed_by", nullable = false)
    val changedBy: Long,
)
