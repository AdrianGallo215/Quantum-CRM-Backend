package pe.quantum.crm.domain.catalogoeventos

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import pe.quantum.crm.shared.enums.EstadoOportunidad

/**
 * Evento estandar del catalogo (tabla `catalogo_eventos`, migraciones V13 + V18).
 * `dispara_cambio_estado = true` exige `estado_destino` (CHECK + validacion).
 */
@Entity
@Table(name = "catalogo_eventos")
@Suppress("LongParameterList") // Entidad JPA: un parametro por columna de la tabla.
class CatalogoEvento(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,
    @Column(nullable = false, unique = true)
    var nombre: String,
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(name = "etapa_asociada", columnDefinition = "estado_op_enum")
    var etapaAsociada: EstadoOportunidad? = null,
    @Column(name = "dispara_cambio_estado", nullable = false)
    var disparaCambioEstado: Boolean = false,
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(name = "estado_destino", columnDefinition = "estado_op_enum")
    var estadoDestino: EstadoOportunidad? = null,
    @Column(name = "es_recomendado", nullable = false)
    var esRecomendado: Boolean = false,
    @Column(name = "es_hito_prospeccion", nullable = false)
    var esHitoProspeccion: Boolean = false,
)
