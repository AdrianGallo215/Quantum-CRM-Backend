package pe.quantum.crm.domain.empresas.dto

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
import pe.quantum.crm.domain.empleados.dto.EmpleadoResumen
import pe.quantum.crm.domain.empresas.Empresa
import pe.quantum.crm.shared.enums.EstadoCartera
import pe.quantum.crm.shared.enums.OrigenLead
import pe.quantum.crm.shared.enums.Segmento
import java.time.Instant

/** Fila del listado de empresas (contrato_api.md §8). */
data class EmpresaListaDto(
    val id: Long,
    val ruc: String,
    val razonSocial: String,
    val estadoSunat: String?,
    val condicionSunat: String?,
    val estadoCartera: String?,
    val distrito: String?,
    val idVendedor: Long?,
    val vendedor: EmpleadoResumen?,
    val segmentos: List<String>,
    val contactosCount: Int,
    val enCarteraMaestra: Boolean = false,
)

/** Contacto embebido en el detalle de empresa (cargo/rol de la vinculacion). */
data class ContactoDeEmpresaDto(
    val id: Long,
    val nombres: String,
    val apellidos: String,
    val cargo: String?,
    val tomaDecision: Boolean?,
    val esPrincipal: Boolean,
    val email_1: String?,
    val tlf_1: String?,
)

/** Detalle completo de empresa (contrato_api.md §8). */
data class EmpresaDetalleDto(
    val id: Long,
    val ruc: String,
    val razonSocial: String,
    val actividadEcon: String?,
    val ciiu: String?,
    val sectorIndustrial: String?,
    val estadoSunat: String?,
    val condicionSunat: String?,
    val direccionFiscal: String?,
    val ubicacionReal: String?,
    val distrito: String?,
    val provincia: String?,
    val departamento: String?,
    val avalFiador: String?,
    val origenLead: String?,
    val estadoCartera: String?,
    val fileDrive: String?,
    /** Carpeta de Drive de la empresa. SOLO LECTURA: la administra el backend. */
    val driveFolderId: String?,
    val sitioWeb: String?,
    val notas: String?,
    val idVendedor: Long?,
    val vendedor: EmpleadoResumen?,
    val segmentos: List<String>,
    val contactos: List<ContactoDeEmpresaDto>?,
    val enCarteraMaestra: Boolean = false,
    val createdAt: Instant,
    val createdBy: Long,
)

/** Body de `POST /empresas`. `estado_cartera` NO se acepta (nace no_contactado). */
data class CrearEmpresaRequest(
    @field:NotBlank
    @field:Pattern(regexp = "\\d{11}", message = "El RUC debe tener 11 dígitos")
    val ruc: String,
    @field:NotBlank
    val razonSocial: String,
    val actividadEcon: String? = null,
    val ciiu: String? = null,
    val sectorIndustrial: String? = null,
    val estadoSunat: String? = null,
    val condicionSunat: String? = null,
    val direccionFiscal: String? = null,
    val ubicacionReal: String? = null,
    val distrito: String? = null,
    val provincia: String? = null,
    val departamento: String? = null,
    val avalFiador: String? = null,
    val origenLead: OrigenLead? = null,
    val fileDrive: String? = null,
    val sitioWeb: String? = null,
    val notas: String? = null,
    val segmentos: List<Segmento>? = null,
    val idVendedor: Long? = null,
)

/**
 * Body de `PUT /empresas/:id`. No toca `estado_cartera` ni `id_vendedor`.
 *
 * TODOS los campos son opcionales (contrato §8: "mismos campos que POST, todos
 * opcionales"): ausente significa "no lo toques", y el servicio actualiza campo a
 * campo con `?.let`. `ruc` no es la excepcion — declararlo no-nulo y sin default
 * hacia que jackson-module-kotlin lanzase `MissingKotlinParameterException` ante
 * cualquier edicion parcial, respondiendo 400 salvo que el cliente reenviara el
 * RUC que no queria cambiar.
 */
data class ActualizarEmpresaRequest(
    val ruc: String? = null,
    val razonSocial: String? = null,
    val actividadEcon: String? = null,
    val ciiu: String? = null,
    val sectorIndustrial: String? = null,
    val estadoSunat: String? = null,
    val condicionSunat: String? = null,
    val direccionFiscal: String? = null,
    val ubicacionReal: String? = null,
    val distrito: String? = null,
    val provincia: String? = null,
    val departamento: String? = null,
    val avalFiador: String? = null,
    val origenLead: OrigenLead? = null,
    val fileDrive: String? = null,
    val sitioWeb: String? = null,
    val notas: String? = null,
    val segmentos: List<Segmento>? = null,
)

data class CambiarEstadoCarteraRequest(
    val estadoCartera: String,
)

data class ReasignarVendedorRequest(
    val idVendedor: Long,
)

/** Respuesta de `GET /empresas/ruc/:ruc`. Nunca expone el vendedor dueño. */
data class RucCheckDto(
    val existe: Boolean,
    val mensaje: String? = null,
)

/** Filtros del listado de empresas. */
data class EmpresaFiltros(
    val q: String? = null,
    val estadoCartera: String? = null,
    val idVendedor: Long? = null,
    val segmento: Segmento? = null,
    val distrito: String? = null,
    val carteraMaestra: Boolean? = null,
)

data class CarteraMaestraDto(
    val enCarteraMaestra: Boolean,
    val idVendedor: Long?,
)

data class CambiarCarteraMaestraRequest(
    val enCarteraMaestra: Boolean? = null,
    val idVendedor: Long? = null,
)

/** Datos minimos de una empresa para otros modulos (oportunidades, tareas...). */
data class EmpresaVinculo(
    val id: Long,
    val razonSocial: String,
    val idVendedor: Long?,
    val estadoCartera: String?,
    /** Carpeta de Drive de la empresa; null si nacio antes de V35 o aun no se creo. */
    val driveFolderId: String? = null,
)

/** Resumen para DTOs compuestos de otros modulos. */
data class EmpresaResumen(
    val id: Long,
    val razonSocial: String,
    val distrito: String?,
)

/** Resultado de `aplicarEstadoDerivado`: null si no hubo escritura (regla §3.2 paso 3). */
data class CambioEstadoCartera(
    val anterior: EstadoCartera,
    val nuevo: EstadoCartera,
)

fun Empresa.toResumen(): EmpresaResumen = EmpresaResumen(id = requireNotNull(id), razonSocial = razonSocial, distrito = distrito)

fun Empresa.toVinculo(): EmpresaVinculo =
    EmpresaVinculo(
        id = requireNotNull(id),
        razonSocial = razonSocial,
        idVendedor = idVendedor,
        estadoCartera = estadoCartera.name,
        driveFolderId = driveFolderId,
    )

/**
 * Resultado del alta de una empresa. `creada = false` significa que el RUC ya
 * existía en la cartera del MISMO vendedor y se devuelve la empresa existente
 * (reglas_negocio.md §2.1), lo que el controller traduce a 200 en vez de 201.
 */
data class AltaEmpresaResultado(
    val empresa: EmpresaDetalleDto,
    val creada: Boolean,
)
