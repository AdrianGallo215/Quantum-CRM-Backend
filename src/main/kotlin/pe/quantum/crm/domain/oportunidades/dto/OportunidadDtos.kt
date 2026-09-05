package pe.quantum.crm.domain.oportunidades.dto

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import jakarta.validation.Valid
import jakarta.validation.constraints.DecimalMax
import jakarta.validation.constraints.DecimalMin
import jakarta.validation.constraints.Digits
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Positive
import jakarta.validation.constraints.Size
import pe.quantum.crm.domain.empleados.dto.EmpleadoResumen
import pe.quantum.crm.domain.empresas.dto.EmpresaResumen
import pe.quantum.crm.domain.financiadoras.dto.FinanciadoraDto
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate

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
    val estado: String,
    val items: List<OportunidadItemDto>,
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
    val createdAt: Instant,
    // Solo en detalle:
    val contactos: List<ContactoEnOportunidadDto>? = null,
    val entradaEtapaActual: Instant? = null,
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
 *
 * ESCALA (`@Digits`): el rango no basta. `monto_total` se calcula en Java con el
 * valor recibido, pero la columna lo redondea a su escala; con `dcto = 2.994`
 * sobre 100 x 45000 se guarda `monto_total = 4365270.00` junto a `dcto = 2.99`,
 * y recalcular desde lo persistido da 4365450.00: 180 USD que la formula del
 * §7.2 ya no explica. Encima el PUT responde "2.994" (entidad en memoria) y el
 * GET siguiente "2.99". Se rechaza en el borde en vez de aceptar en silencio un
 * valor distinto del enviado. Los digitos son los de V10, no una eleccion:
 * NUMERIC(5,2) = 3 enteros + 2 decimales; NUMERIC(12,2) = 10 + 2.
 *
 * Los limites de `precio_venta`/`cuota_financiadora` ya no viven aqui: desde
 * D19 esos campos son de los items, y sus constantes estan en
 * `OportunidadItemDtos.kt`.
 */
private const val DCTO_MIN = "0.0"
private const val DCTO_MAX = "100.0"
private const val DCTO_DIGITOS_ENTEROS = 3
private const val DECIMALES_MONETARIOS = 2
private const val MAX_TEXTO_CORTO = 255
private const val MAX_TEXTO_MEDIO = 1000
private const val MAX_TEXTO_LARGO = 5000

/**
 * Body de `POST /oportunidades`. `monto_total` NO se declara: si viene en el
 * body simplemente se ignora (contrato §10). Los campos del item viajan planos
 * (D19): `crear()` construye con ellos un unico `OportunidadItem`, cuyo
 * `precio_venta` se inicializa con el precio base del modelo.
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
    @field:DecimalMin(value = DCTO_MIN, message = "descuento no puede ser negativo")
    @field:DecimalMax(value = DCTO_MAX, message = "descuento no puede superar 100")
    @field:Digits(
        integer = DCTO_DIGITOS_ENTEROS,
        fraction = DECIMALES_MONETARIOS,
        message = "descuento admite como maximo 2 decimales",
    )
    val descuento: BigDecimal? = null,
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

/**
 * Body de `POST /oportunidades/:id/contactos` y elemento de `contactos` en el
 * POST de creacion: aqui `id_contacto` SI viaja en el body, porque no hay otro
 * sitio de donde sacarlo.
 */
data class ContactoVinculoRequest(
    @field:Positive(message = "id_contacto debe ser un identificador valido")
    val idContacto: Long,
    @field:Size(max = MAX_TEXTO_CORTO, message = "rol_en_oportunidad supera la longitud maxima")
    val rolEnOportunidad: String? = null,
)

/**
 * Body de `PUT /oportunidades/:id/contactos/:contacto_id`, exactamente el del
 * contrato §10: `{ "rol_en_oportunidad": "Aprobador" }`.
 *
 * DTO propio y no `ContactoVinculoRequest`: el contacto ya viene en la URL y el
 * servicio lo toma de ahi. Reutilizar el DTO del POST metia en el body un
 * `id_contacto` no-nulo, sin default y con `@Positive` que el backend ni
 * siquiera lee — el cliente que seguia el contrato se llevaba un 400 y el
 * endpoint solo respondia si adivinaba un id que luego se descarta.
 */
data class ActualizarRolContactoRequest(
    @field:Size(max = MAX_TEXTO_CORTO, message = "rol_en_oportunidad supera la longitud maxima")
    val rolEnOportunidad: String? = null,
)

/**
 * Body de `PUT /oportunidades/:id` (D19 del plan 05). Ya NO acepta `id_modelo`,
 * `cantidad`, `precio_venta`/`precio_unitario`, `descuento`/`dcto` ni
 * `monto_total`: esos campos viven en los items y se editan por
 * `PUT /oportunidades/:id/items/:itemId`. Al no estar declarados,
 * `@JsonIgnoreProperties(ignoreUnknown = true)` los ignora en silencio; ya no
 * hace falta el guard de presencia de `montoTotal`.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class ActualizarOportunidadRequest(
    val garantia: Boolean? = null,
    val fincParalelo: Boolean? = null,
    @field:Size(max = MAX_TEXTO_MEDIO, message = "ficha_venta supera la longitud maxima")
    val fichaVenta: String? = null,
    @field:Size(max = MAX_TEXTO_LARGO, message = "notas supera la longitud maxima")
    val notas: String? = null,
    val fechaCierreEstimado: LocalDate? = null,
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
    val changedAt: Instant,
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
