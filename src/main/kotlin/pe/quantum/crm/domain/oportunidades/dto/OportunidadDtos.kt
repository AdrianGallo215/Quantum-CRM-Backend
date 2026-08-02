package pe.quantum.crm.domain.oportunidades.dto

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
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
 * Body de `POST /oportunidades`. `monto_total` NO se declara: si viene en el
 * body simplemente se ignora (contrato §10). `precio_unitario` se inicializa
 * con el precio base del modelo.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class CrearOportunidadRequest(
    val idEmpresa: Long,
    val idModelo: Long,
    val idFinanciadora: Long? = null,
    val cantidad: Int? = null,
    val dcto: BigDecimal? = null,
    val garantia: Boolean? = null,
    val fincParalelo: Boolean? = null,
    val fichaVenta: String? = null,
    val notas: String? = null,
    val fechaCierreEstimado: LocalDate? = null,
    val contactos: List<ContactoVinculoRequest>? = null,
    val idVendedor: Long? = null,
)

data class ContactoVinculoRequest(
    val idContacto: Long,
    val rolEnOportunidad: String? = null,
)

/**
 * Body de `PUT /oportunidades/:id`. `montoTotal` se declara SOLO para detectarlo
 * y rechazarlo con `400 MONTO_NO_EDITABLE` (contrato §10).
 */
data class ActualizarOportunidadRequest(
    val idModelo: Long? = null,
    val cantidad: Int? = null,
    val precioUnitario: BigDecimal? = null,
    val dcto: BigDecimal? = null,
    val garantia: Boolean? = null,
    val fincParalelo: Boolean? = null,
    val fichaVenta: String? = null,
    val notas: String? = null,
    val fechaCierreEstimado: LocalDate? = null,
    val montoTotal: BigDecimal? = null,
)

/** Body de `PATCH /oportunidades/:id/estado`. */
data class CambiarEstadoRequest(
    val estado: String,
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
