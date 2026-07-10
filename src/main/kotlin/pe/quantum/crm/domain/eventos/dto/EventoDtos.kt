package pe.quantum.crm.domain.eventos.dto

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime

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
    val fechaOcurrencia: LocalDateTime?,
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

/** Body de `POST /oportunidades/:id/eventos` (catalogo o personalizado). */
data class CrearEventoRequest(
    val idCatalogoEvento: Long? = null,
    val esPersonalizado: Boolean = false,
    val nombrePersonalizado: String? = null,
    val fechaEstimada: LocalDate? = null,
    val fechaSeguimiento: LocalDate? = null,
    val descripcion: String? = null,
)

/** Body de `PATCH /eventos/:id/ocurrido`. */
data class MarcarOcurridoRequest(
    val fechaOcurrencia: Instant? = null,
    val descripcion: String? = null,
)

/** Body de `PATCH /eventos/:id/descartado`. */
data class MarcarDescartadoRequest(
    val descripcion: String? = null,
)

/** Body de `PUT /eventos/:id` (solo eventos pendientes). */
data class ActualizarEventoRequest(
    val fechaEstimada: LocalDate? = null,
    val fechaSeguimiento: LocalDate? = null,
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
    val fechaOcurrencia: LocalDateTime?,
    val sugerencia: SugerenciaDto?,
)

/** Proyeccion de solo lectura para el job de recordatorios (notificaciones). */
data class EventoRecordatorioProyeccion(
    val id: Long,
    val idOportunidad: Long?,
    val idEmpresa: Long?,
    val fechaEstimada: LocalDate,
)
