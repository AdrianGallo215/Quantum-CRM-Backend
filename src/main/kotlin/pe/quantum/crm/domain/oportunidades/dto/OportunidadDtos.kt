package pe.quantum.crm.domain.oportunidades.dto

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import jakarta.validation.Valid
import jakarta.validation.constraints.DecimalMax
import jakarta.validation.constraints.DecimalMin
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Positive
import jakarta.validation.constraints.Size
import pe.quantum.crm.domain.empleados.dto.EmpleadoResumen
import pe.quantum.crm.domain.empresas.dto.EmpresaResumen
import pe.quantum.crm.domain.financiadoras.dto.FinanciadoraDto
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime

/** Modelo embebido en la oportunidad (contrato_api.md §10). */
data class ModeloEnOportunidadDto(
    val id: Long,
    val codigo: String,
    val precioBase: String?,
)

/** Contacto de la oportunidad con su rol (contrato_api.md §10). */
data class ContactoEnOportunidadDto(
    val id: Long,
    val nombres: String,
    val apellidos: String,
    val rolEnOportunidad: String?,
)

/** Oportunidad en listado y detalle (contrato_api.md §10). Montos como string. */
data class OportunidadDto(
    val id: Long,
    val idEmpresa: Long,
    val empresa: EmpresaResumen?,
    val idVendedor: Long,
    val vendedor: EmpleadoResumen?,
    val idFinanciadora: Long,
    val financiadora: FinanciadoraDto?,
    val idModelo: Long?,
    val modelo: ModeloEnOportunidadDto?,
    val estado: String,
    val cantidad: Int?,
    val precioUnitario: String?,
    val dcto: String?,
    val montoTotal: String?,
    val garantia: Boolean?,
    val fincParalelo: Boolean?,
    val fichaVenta: String?,
    /** Carpeta de Drive de la oportunidad. SOLO LECTURA: la administra el backend. */
    val driveFolderId: String?,
    val notas: String?,
    val motivoCierre: String?,
    val fechaCierreEstimado: LocalDate?,
    val tareasPendientesCount: Int = 0,
    val eventosPendientesCount: Int = 0,
    val createdAt: LocalDateTime,
    // Solo en detalle:
    val contactos: List<ContactoEnOportunidadDto>? = null,
    val entradaEtapaActual: LocalDateTime? = null,
    // Advertencias de la operacion (p. ej. precio editado manualmente):
    val advertencias: List<String>? = null,
)

/**
 * Limites de los campos numericos de la oportunidad. Espejo de los CHECK de la
 * migracion V36 y de los tipos de columna de V10 (`dcto NUMERIC(5,2)`,
 * `precio_unitario NUMERIC(12,2)`).
 *
 * `dcto` fuera de 0..100 no es solo un dato feo: un descuento negativo invierte
 * el factor de `MontoTotal.calcular` (`1 - dcto/100 > 1`) e infla el
 * `monto_total`, y ademas se cuela por `PoliticaDescuento.excedeLimite`, que
 * solo compara `dcto > limite`.
 */
private const val DCTO_MIN = "0.0"
private const val DCTO_MAX = "100.0"
private const val PRECIO_MIN = "0.0"
private const val MAX_TEXTO_CORTO = 255
private const val MAX_TEXTO_MEDIO = 1000
private const val MAX_TEXTO_LARGO = 5000

/**
 * Body de `POST /oportunidades`. `monto_total` NO se declara: si viene en el
 * body simplemente se ignora (contrato §10). `precio_unitario` se inicializa
 * con el precio base del modelo.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class CrearOportunidadRequest(
    @field:Positive(message = "id_empresa debe ser un identificador valido")
    val idEmpresa: Long,
    @field:Positive(message = "id_modelo debe ser un identificador valido")
    val idModelo: Long,
    @field:Positive(message = "id_financiadora debe ser un identificador valido")
    val idFinanciadora: Long? = null,
    @field:Positive(message = "cantidad debe ser mayor a 0")
    val cantidad: Int? = null,
    @field:DecimalMin(value = DCTO_MIN, message = "dcto no puede ser negativo")
    @field:DecimalMax(value = DCTO_MAX, message = "dcto no puede superar 100")
    val dcto: BigDecimal? = null,
    val garantia: Boolean? = null,
    val fincParalelo: Boolean? = null,
    @field:Size(max = MAX_TEXTO_MEDIO, message = "ficha_venta supera la longitud maxima")
    val fichaVenta: String? = null,
    @field:Size(max = MAX_TEXTO_LARGO, message = "notas supera la longitud maxima")
    val notas: String? = null,
    val fechaCierreEstimado: LocalDate? = null,
    @field:Valid
    val contactos: List<ContactoVinculoRequest>? = null,
    @field:Positive(message = "id_vendedor debe ser un identificador valido")
    val idVendedor: Long? = null,
)

data class ContactoVinculoRequest(
    @field:Positive(message = "id_contacto debe ser un identificador valido")
    val idContacto: Long,
    @field:Size(max = MAX_TEXTO_CORTO, message = "rol_en_oportunidad supera la longitud maxima")
    val rolEnOportunidad: String? = null,
)

/**
 * Body de `PUT /oportunidades/:id`. `montoTotal` se declara SOLO para detectarlo
 * y rechazarlo con `400 MONTO_NO_EDITABLE` (contrato §10): por eso NO lleva
 * anotaciones de rango, el rechazo es por presencia, no por valor.
 */
data class ActualizarOportunidadRequest(
    @field:Positive(message = "id_modelo debe ser un identificador valido")
    val idModelo: Long? = null,
    @field:Positive(message = "cantidad debe ser mayor a 0")
    val cantidad: Int? = null,
    @field:DecimalMin(value = PRECIO_MIN, message = "precio_unitario no puede ser negativo")
    val precioUnitario: BigDecimal? = null,
    @field:DecimalMin(value = DCTO_MIN, message = "dcto no puede ser negativo")
    @field:DecimalMax(value = DCTO_MAX, message = "dcto no puede superar 100")
    val dcto: BigDecimal? = null,
    val garantia: Boolean? = null,
    val fincParalelo: Boolean? = null,
    @field:Size(max = MAX_TEXTO_MEDIO, message = "ficha_venta supera la longitud maxima")
    val fichaVenta: String? = null,
    @field:Size(max = MAX_TEXTO_LARGO, message = "notas supera la longitud maxima")
    val notas: String? = null,
    val fechaCierreEstimado: LocalDate? = null,
    val montoTotal: BigDecimal? = null,
)

/**
 * Body de `PATCH /oportunidades/:id/estado`. `motivo_cierre` es obligatorio solo
 * cuando `estado = 'cerrado'`: esa regla cruzada la valida el servicio, aqui
 * solo se acota la longitud.
 */
data class CambiarEstadoRequest(
    @field:NotBlank(message = "estado es obligatorio")
    val estado: String,
    @field:Size(max = MAX_TEXTO_MEDIO, message = "motivo_cierre supera la longitud maxima")
    val motivoCierre: String? = null,
)

/** Respuesta del cambio de estado (contrato §10). */
data class CambioEstadoDto(
    val estado: String,
    val esRetroceso: Boolean,
    val advertencias: List<String>,
)

/** Entrada del historial de estados (contrato §10). */
data class LogEstadoDto(
    val estadoAnterior: String?,
    val estadoNuevo: String,
    val changedAt: LocalDateTime,
    val changedBy: EmpleadoResumen?,
)

/** Filtros del listado de oportunidades. */
data class OportunidadFiltros(
    val estado: String? = null,
    val idEmpresa: Long? = null,
    val idVendedor: Long? = null,
    val idFinanciadora: Long? = null,
    val incluirCerradas: Boolean = false,
)

/** Datos minimos de una oportunidad para otros modulos (eventos, tareas). */
data class OportunidadVinculo(
    val id: Long,
    val idEmpresa: Long,
    val idVendedor: Long,
    val estado: String,
)

/** Para el job de recordatorios (notificaciones): sin chequeo de visibilidad — lo usa un job de sistema, no un usuario. */
data class OportunidadRecordatorioDatos(
    val idEmpresa: Long,
    val idVendedor: Long,
)
