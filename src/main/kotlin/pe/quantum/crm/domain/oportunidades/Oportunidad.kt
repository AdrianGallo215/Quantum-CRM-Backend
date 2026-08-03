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
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * Oportunidad de venta, nucleo del pipeline (tabla `oportunidades`, migracion V10).
 *
 * - `id_vendedor` es snapshot de `empresas.id_vendedor` al crear; solo muta en un
 *   traspaso explicito (reglas_negocio.md §8).
 * - `monto_total` es CALCULADO (cantidad × precio_unitario × (1 − dcto/100));
 *   nunca se acepta como input (§7.2).
 * - `motivo_cierre` obligatorio cuando estado = 'cerrado' (CHECK + backend, §4.4).
 */
@Entity
@Table(name = "oportunidades")
@Suppress("LongParameterList") // Una entidad JPA refleja las columnas de su tabla.
class Oportunidad(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,
    @Column(name = "id_empresa", nullable = false)
    val idEmpresa: Long,
    @Column(name = "id_vendedor", nullable = false)
    var idVendedor: Long,
    @Column(name = "id_financiadora", nullable = false)
    var idFinanciadora: Long,
    @Column(name = "id_modelo")
    var idModelo: Long? = null,
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(nullable = false, columnDefinition = "estado_op_enum")
    var estado: EstadoOportunidad = EstadoOportunidad.evaluacion_calidda,
    var cantidad: Int? = null,
    @Column(name = "precio_unitario")
    var precioUnitario: BigDecimal? = null,
    var dcto: BigDecimal? = null,
    @Column(name = "monto_total")
    var montoTotal: BigDecimal? = null,
    @Column(name = "finc_paralelo")
    var fincParalelo: Boolean? = null,
    var garantia: Boolean? = null,
    @Column(name = "ficha_venta")
    var fichaVenta: String? = null,
    /**
     * Subcarpeta de la oportunidad en Drive, hija de la carpeta de su empresa (V35).
     * La administra el backend; nunca se acepta como input.
     */
    @Column(name = "drive_folder_id")
    var driveFolderId: String? = null,
    var notas: String? = null,
    @Column(name = "motivo_cierre")
    var motivoCierre: String? = null,
    @Column(name = "fecha_cierre_estimado")
    var fechaCierreEstimado: LocalDate? = null,
    @Column(name = "facturado_en")
    var facturadoEn: LocalDateTime? = null,
    @Column(name = "created_at", nullable = false)
    val createdAt: LocalDateTime = LocalDateTime.now(),
    @Column(name = "created_by", nullable = false)
    val createdBy: Long,
    @Column(name = "updated_at", nullable = false)
    var updatedAt: LocalDateTime = LocalDateTime.now(),
    @Column(name = "updated_by", nullable = false)
    var updatedBy: Long,
)
