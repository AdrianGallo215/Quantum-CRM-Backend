package pe.quantum.crm.domain.eventos.dto

import jakarta.validation.constraints.Positive
import jakarta.validation.constraints.Size
import java.time.Instant
import java.time.LocalDate

/** Evento expuesto en respuestas (contrato_api.md §11). */
data class EventoDto(
    val id: Long,
    val idOportunidad: Long?,
    val idEmpresa: Long?,
    val idCatalogoEvento: Long?,
    val nombre: String,
    val esPersonalizado: Boolean,
    val descripcion: String?,
    val estado: String,
    val fechaEstimada: LocalDate?,
    val fechaSeguimiento: LocalDate?,
    val fechaOcurrencia: Instant?,
    val disparaCambioEstado: Boolean,
    val estadoDestino: String?,
    val esRecomendado: Boolean,
    val etapaAsociada: String?,
    val esHitoProspeccion: Boolean,
)

/** Eventos de una oportunidad separados por estado (contrato §11). */
data class EventosAgrupadosDto(
    val pendientes: List<EventoDto>,
    val ocurridos: List<EventoDto>,
    val descartados: List<EventoDto>,
)

/** Longitudes maximas de los textos libres del evento (columnas TEXT, sin tope en BD). */
private const val MAX_NOMBRE = 200
private const val MAX_DESCRIPCION = 5000

/**
 * Body de `POST /oportunidades/:id/eventos` (catalogo o personalizado). La
 * exclusion mutua entre `id_catalogo_evento` y `es_personalizado` +
 * `nombre_personalizado` es una regla cruzada: la valida el servicio (y el
 * CHECK `chk_evento_origen` de V14), aqui solo se acotan los valores sueltos.
 */
data class CrearEventoRequest(
    @field:Positive(message = "id_catalogo_evento debe ser un identificador valido")
    val idCatalogoEvento: Long? = null,
    val esPersonalizado: Boolean = false,
    @field:Size(max = MAX_NOMBRE, message = "nombre_personalizado supera la longitud maxima")
    val nombrePersonalizado: String? = null,
    val fechaEstimada: LocalDate? = null,
    val fechaSeguimiento: LocalDate? = null,
    @field:Size(max = MAX_DESCRIPCION, message = "descripcion supera la longitud maxima")
    val descripcion: String? = null,
)

/** Body de `PATCH /eventos/:id/ocurrido`. */
data class MarcarOcurridoRequest(
    val fechaOcurrencia: Instant? = null,
    @field:Size(max = MAX_DESCRIPCION, message = "descripcion supera la longitud maxima")
    val descripcion: String? = null,
)

/** Body de `PATCH /eventos/:id/descartado`. */
data class MarcarDescartadoRequest(
    @field:Size(max = MAX_DESCRIPCION, message = "descripcion supera la longitud maxima")
    val descripcion: String? = null,
)

/** Body de `PUT /eventos/:id` (solo eventos pendientes). */
data class ActualizarEventoRequest(
    val fechaEstimada: LocalDate? = null,
    val fechaSeguimiento: LocalDate? = null,
    @field:Size(max = MAX_DESCRIPCION, message = "descripcion supera la longitud maxima")
    val descripcion: String? = null,
)

/** Sugerencia de cambio de estado: el backend NUNCA lo ejecuta (reglas §5.3). */
data class SugerenciaDto(
    val dispara: Boolean,
    val estadoDestino: String,
    val mensaje: String,
)

/** Respuesta de `PATCH /eventos/:id/ocurrido`. */
data class EventoOcurridoDto(
    val id: Long,
    val estado: String,
    val fechaOcurrencia: Instant?,
    val sugerencia: SugerenciaDto?,
)

/** Proyeccion de solo lectura para el job de recordatorios (notificaciones). */
data class EventoRecordatorioProyeccion(
    val id: Long,
    val idOportunidad: Long?,
    val idEmpresa: Long?,
    val fechaEstimada: LocalDate,
)
