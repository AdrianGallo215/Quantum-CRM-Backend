package pe.quantum.crm.domain.empresas

import jakarta.persistence.CollectionTable
import jakarta.persistence.Column
import jakarta.persistence.ElementCollection
import jakarta.persistence.Entity
import jakarta.persistence.FetchType
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.Table
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import pe.quantum.crm.shared.enums.EstadoCartera
import pe.quantum.crm.shared.enums.OrigenLead
import pe.quantum.crm.shared.enums.Segmento
import java.time.LocalDateTime

/**
 * Empresa cliente (tabla `empresas`, migracion V6). Solo personas juridicas.
 * `estado_cartera` NUNCA se escribe directamente: solo via la logica de
 * `actualizarEstadoCartera` o el cambio manual validado (reglas_negocio.md §3).
 */
@Entity
@Table(name = "empresas")
@Suppress("LongParameterList") // Una entidad JPA refleja las columnas de su tabla.
class Empresa(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,
    @Column(nullable = false, unique = true, length = 11)
    var ruc: String,
    @Column(name = "razon_social", nullable = false, unique = true)
    var razonSocial: String,
    @Column(name = "actividad_econ", nullable = true)
    var actividadEcon: String? = null,
    var ciiu: String? = null,
    @Column(name = "sector_industrial", nullable = true)
    var sectorIndustrial: String? = null,
    @Column(name = "id_vendedor")
    var idVendedor: Long? = null,
    @Column(name = "file_drive")
    var fileDrive: String? = null,
    /**
     * Carpeta de la empresa en Google Drive (V35). La administra el backend; nunca
     * se acepta como input. Distinta de [fileDrive], que es una URL manual.
     */
    @Column(name = "drive_folder_id")
    var driveFolderId: String? = null,
    @Column(name = "sitio_web")
    var sitioWeb: String? = null,
    var notas: String? = null,
    @Column(name = "estado_sunat", nullable = true)
    var estadoSunat: String? = null,
    @Column(name = "condicion_sunat", nullable = true)
    var condicionSunat: String? = null,
    @Column(name = "direccion_fiscal", nullable = true)
    var direccionFiscal: String? = null,
    @Column(name = "ubicacion_real", nullable = true)
    var ubicacionReal: String? = null,
    var distrito: String? = null,
    var provincia: String? = null,
    var departamento: String? = null,
    @Column(name = "aval_fiador", nullable = true)
    var avalFiador: String? = null,
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(name = "origen_lead", columnDefinition = "origen_lead_enum")
    var origenLead: OrigenLead? = null,
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(name = "estado_cartera", nullable = true, columnDefinition = "estado_cartera_enum")
    var estadoCartera: EstadoCartera = EstadoCartera.no_contactado,
    @Column(name = "en_cartera_maestra", nullable = false)
    var enCarteraMaestra: Boolean = false,
    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(name = "empresa_segmentos", joinColumns = [JoinColumn(name = "id_empresa")])
    @Column(name = "segmento", nullable = false, columnDefinition = "segmento_enum")
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    var segmentos: MutableSet<Segmento> = mutableSetOf(),
    @Column(name = "created_at", nullable = false)
    val createdAt: LocalDateTime = LocalDateTime.now(),
    @Column(name = "created_by", nullable = false)
    val createdBy: Long,
    @Column(name = "updated_at", nullable = false)
    var updatedAt: LocalDateTime = LocalDateTime.now(),
    @Column(name = "updated_by", nullable = false)
    var updatedBy: Long,
)
