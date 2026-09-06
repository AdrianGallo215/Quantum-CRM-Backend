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
import java.math.BigDecimal
import java.time.LocalDateTime

/**
 * Simulación de financiamiento propio de Quantum para UNA unidad (un tipo de
 * bus), no para la operación completa (tabla `simulaciones`, migración V43).
 * Por eso cuelga de `oportunidad_items` (`idOportunidadItem`) y no de
 * `oportunidades`: una oportunidad con varios modelos distintos necesita varias
 * simulaciones, cada una con su propia inicial, tasa y cuota
 * (reglas_simulaciones.md §1.1).
 *
 * El cronograma de pagos NUNCA se persiste: es una función pura del motor de
 * cálculo (`shared/simulacion/`) sobre los campos esenciales de esta entidad, y
 * se recalcula on demand en cada lectura (reglas_simulaciones.md §4).
 * `cuotaFinal` es el ÚNICO derivado que sí se persiste — solo para no
 * recalcularlo en cada fila de un listado — y NUNCA se acepta como input del
 * cliente: el backend siempre la recalcula server-side antes de guardar.
 *
 * `idOportunidad` no es una columna directa a propósito, mismo patrón que
 * `OportunidadItem.idOportunidad` y `MetaVenta.idEmpleado` (CLAUDE.md regla 9):
 * la cadena hacia la oportunidad y la empresa pasa por `idOportunidadItem`.
 */
@Entity
@Table(name = "simulaciones")
@Suppress("LongParameterList") // Una entidad JPA refleja las columnas de su tabla.
class Simulacion(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,
    /**
     * INMUTABLE tras la creación (reglas_simulaciones.md §2): leasing y crédito
     * directo usan fórmulas y columnas de cronograma distintas. Se declara `val`
     * a propósito — la inmutabilidad empieza por el tipo, antes de llegar al
     * trigger `trg_simulacion_modo_inmutable` (tercera línea de defensa).
     */
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(nullable = false, columnDefinition = "modo_simulacion_enum")
    val modo: ModoSimulacion,
    var nombre: String? = null,
    @Column(name = "id_oportunidad_item")
    var idOportunidadItem: Long? = null,
    @Column(name = "id_modelo")
    var idModelo: Long? = null,
    @Column(name = "id_simulacion_origen")
    val idSimulacionOrigen: Long? = null,
    @Column(name = "precio_venta", nullable = false)
    var precioVenta: BigDecimal,
    @Column(nullable = false)
    var descuento: BigDecimal = BigDecimal.ZERO,
    @Column(name = "cuota_inicial", nullable = false)
    var cuotaInicial: BigDecimal,
    @Column(name = "plazo_meses", nullable = false)
    var plazoMeses: Int,
    @Column(nullable = false)
    var tea: BigDecimal,
    @Column(name = "valor_residual", nullable = false)
    var valorResidual: BigDecimal = BigDecimal.ZERO,
    @Column(name = "dias_trabajados", nullable = false)
    var diasTrabajados: Int = 22,
    @Column(name = "comision_estructuracion", nullable = false)
    var comisionEstructuracion: BigDecimal = BigDecimal("1180"),
    @Column(name = "cuota_final", nullable = false)
    var cuotaFinal: BigDecimal,
    @Column(name = "es_principal", nullable = false)
    var esPrincipal: Boolean = false,
    @Column(name = "created_at", nullable = false)
    val createdAt: LocalDateTime = LocalDateTime.now(),
    @Column(name = "created_by", nullable = false)
    val createdBy: Long,
    @Column(name = "updated_at", nullable = false)
    var updatedAt: LocalDateTime = LocalDateTime.now(),
    @Column(name = "updated_by", nullable = false)
    var updatedBy: Long,
)
