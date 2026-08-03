package pe.quantum.crm.domain.eventos

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import pe.quantum.crm.shared.enums.EstadoEvento
import pe.quantum.crm.shared.enums.EstadoOportunidad
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * Evento del proceso comercial (tabla `eventos`, migraciones V14 + V21).
 *
 * - Del catalogo: `id_catalogo_evento NOT NULL`, `es_personalizado = false`.
 * - Personalizado: `id_catalogo_evento NULL`, `nombre_personalizado NOT NULL`,
 *   nunca dispara cambio de estado (reglas_negocio.md §5.1).
 * - De prospeccion (V21): `id_oportunidad NULL`, `id_empresa NOT NULL`.
 *
 * Marcar un evento como ocurrido NUNCA cambia el estado de la oportunidad;
 * el backend solo devuelve la sugerencia (reglas §5.3).
 */
@Entity
@Table(name = "eventos")
@Suppress("LongParameterList") // Una entidad JPA refleja las columnas de su tabla.
class Evento(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,
    @Column(name = "id_oportunidad")
    val idOportunidad: Long? = null,
    @Column(name = "id_empresa")
    val idEmpresa: Long? = null,
    @Column(name = "id_catalogo_evento")
    val idCatalogoEvento: Long? = null,
    @Column(name = "es_personalizado", nullable = false)
    val esPersonalizado: Boolean = false,
    @Column(name = "nombre_personalizado")
    var nombrePersonalizado: String? = null,
    var descripcion: String? = null,
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(nullable = false, columnDefinition = "estado_evento_enum")
    var estado: EstadoEvento = EstadoEvento.pendiente,
    @Column(name = "fecha_estimada")
    var fechaEstimada: LocalDate? = null,
    @Column(name = "fecha_seguimiento")
    var fechaSeguimiento: LocalDate? = null,
    @Column(name = "fecha_ocurrencia")
    var fechaOcurrencia: LocalDateTime? = null,
    @Column(name = "dispara_cambio_estado", nullable = false)
    val disparaCambioEstado: Boolean = false,
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(name = "estado_destino", columnDefinition = "estado_op_enum")
    val estadoDestino: EstadoOportunidad? = null,
    @Column(name = "registrado_por")
    var registradoPor: Long? = null,
    @Column(name = "created_at", nullable = false)
    val createdAt: LocalDateTime = LocalDateTime.now(),
    @Column(name = "created_by", nullable = false)
    val createdBy: Long,
    @Column(name = "updated_at", nullable = false)
    var updatedAt: LocalDateTime = LocalDateTime.now(),
    @Column(name = "updated_by", nullable = false)
    var updatedBy: Long,
)
