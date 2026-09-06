package pe.quantum.crm.domain.simulaciones

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import pe.quantum.crm.shared.enums.ModoSimulacion
import pe.quantum.crm.shared.enums.TipoEventoSimulacion
import java.math.BigDecimal
import java.time.LocalDateTime

/**
 * Bitácora permanente e inmutable de `simulaciones` (tabla `simulacion_log`,
 * migración V43): solo INSERT, nunca UPDATE ni DELETE, y sin job de purga
 * (reglas_simulaciones.md §7). Que ninguna propiedad de esta entidad sea `var`
 * es la primera línea de defensa contra un UPDATE accidental desde este módulo.
 *
 * `idSimulacion` NO tiene foreign key a propósito: el log debe sobrevivir al
 * hard delete de la simulación, incluido el propio evento `eliminada` que lo
 * registra (con `ON DELETE CASCADE` ese evento se perdería junto con la fila
 * que lo originó). Por el mismo motivo tampoco tienen FK
 * `idSimulacionOrigen`, `idOportunidadItem` ni `idOportunidad`.
 *
 * `createdBy` es nullable porque hay eventos generados por un job programado
 * sin actor humano (p. ej. la purga a 30 días de simulaciones sin enlazar, §5).
 *
 * Todos los campos de snapshot (`modo` hasta `cuotaFinal`) son nullable porque
 * el CHECK `chk_simulacion_log_snapshot` solo los exige completos para los
 * eventos `creada`/`editada`/`restaurada`/`eliminada`; los eventos
 * `marcada_principal`/`enlazada_a_item` solo requieren `idOportunidadItem`.
 */
@Entity
@Table(name = "simulacion_log")
@Suppress("LongParameterList") // Una entidad JPA refleja las columnas de su tabla.
class SimulacionLog(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,
    @Column(name = "id_simulacion", nullable = false)
    val idSimulacion: Long,
    @Column(name = "id_simulacion_origen")
    val idSimulacionOrigen: Long? = null,
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(name = "tipo_evento", nullable = false, columnDefinition = "tipo_evento_simulacion_enum")
    val tipoEvento: TipoEventoSimulacion,
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(columnDefinition = "modo_simulacion_enum")
    val modo: ModoSimulacion? = null,
    @Column(name = "precio_venta")
    val precioVenta: BigDecimal? = null,
    val descuento: BigDecimal? = null,
    @Column(name = "cuota_inicial")
    val cuotaInicial: BigDecimal? = null,
    @Column(name = "plazo_meses")
    val plazoMeses: Int? = null,
    val tea: BigDecimal? = null,
    @Column(name = "valor_residual")
    val valorResidual: BigDecimal? = null,
    @Column(name = "dias_trabajados")
    val diasTrabajados: Int? = null,
    @Column(name = "comision_estructuracion")
    val comisionEstructuracion: BigDecimal? = null,
    @Column(name = "cuota_final")
    val cuotaFinal: BigDecimal? = null,
    @Column(name = "id_oportunidad_item")
    val idOportunidadItem: Long? = null,
    @Column(name = "id_oportunidad")
    val idOportunidad: Long? = null,
    @Column(name = "created_at", nullable = false)
    val createdAt: LocalDateTime = LocalDateTime.now(),
    @Column(name = "created_by")
    val createdBy: Long? = null,
)
