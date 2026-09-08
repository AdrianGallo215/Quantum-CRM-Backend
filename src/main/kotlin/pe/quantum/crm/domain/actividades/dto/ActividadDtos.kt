package pe.quantum.crm.domain.actividades.dto

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import pe.quantum.crm.domain.empleados.dto.EmpleadoResumen
import pe.quantum.crm.domain.empresas.dto.EmpresaResumen
import java.time.Instant
import java.time.LocalDate

/** Longitud maxima del texto de un comentario (columna TEXT, sin tope en BD). */
private const val MAX_TEXTO_COMENTARIO = 5000

/**
 * Una actividad (tarea o evento) en el historial unificado.
 *
 * DOS campos de fecha, a proposito:
 *  - `fechaHora`  (TIMESTAMP): `fecha_ejecucion` de una tarea, `fecha_ocurrencia`
 *    de un evento. Son instantes reales.
 *  - `fechaDia`   (DATE): `fecha_estimada` de un evento. Es un dia del calendario
 *    de Lima, NO un instante. Darle hora lo desplazaria (ver shared/TiempoUtc.kt).
 *
 * Nunca los unifiques. En una tarea, `fechaDia` es siempre null.
 *
 * `createdAt` es el eje del historial: siempre TIMESTAMP, nunca null en ninguna
 * de las dos entidades, y por eso es la clave de orden y de filtro por rango.
 */
@Suppress("LongParameterList") // Un DTO de composicion refleja dos entidades a la vez.
data class ActividadDto(
    /** `tarea` o `evento` — el valor de `TipoActividad`. */
    val tipo: String,
    val id: Long,
    /** `tipo_accion` de la tarea, o el nombre del evento (catalogo o personalizado). */
    val titulo: String,
    val descripcion: String?,
    val estado: String,
    val fechaHora: Instant?,
    val fechaDia: LocalDate?,
    val idEmpresa: Long?,
    val empresa: EmpresaResumen?,
    val idOportunidad: Long?,
    /** Asignado de la tarea, o creador del evento (un evento no tiene asignado). */
    val idEmpleado: Long?,
    val empleado: EmpleadoResumen?,
    /** Cuantos comentarios de seguimiento tiene esta actividad. */
    val comentarios: Int,
    val createdAt: Instant,
)

/**
 * Filtros de `GET /actividades`. `idEmpleado` es obligatorio: la vista es "el
 * historial de UNA persona", y exigirlo acota el volumen que se une en memoria.
 */
data class HistorialFiltros(
    val idEmpleado: Long,
    /** Filtran por `created_at`, no por la fecha planificada. Ambos inclusive. */
    val desde: Instant? = null,
    val hasta: Instant? = null,
    /** `tarea`, `evento`, o null para ambos. */
    val tipo: String? = null,
    val idEmpresa: Long? = null,
    val idOportunidad: Long? = null,
)

/** Comentario de seguimiento expuesto en respuestas. */
data class ComentarioDto(
    val id: Long,
    val tipo: String,
    val idActividad: Long,
    val texto: String,
    val createdAt: Instant,
    val createdBy: Long,
    val autor: EmpleadoResumen?,
)

/** Body de `POST /actividades/{tipo}/{id}/comentarios`. */
data class CrearComentarioRequest(
    @field:NotBlank(message = "El comentario no puede estar vacío")
    @field:Size(max = MAX_TEXTO_COMENTARIO, message = "texto supera la longitud maxima")
    val texto: String,
)

/**
 * Un campo que cambio en una edicion. Lo producen `tareas` y `eventos` al
 * editar, y lo consume `AuditoriaActividadService`. Los valores van como texto
 * porque la tabla guarda cualquier campo con la misma forma.
 */
data class CambioCampo(
    /** Nombre publico del campo, en snake_case: `fecha_ejecucion`, no `fechaEjecucion`. */
    val campo: String,
    val valorAnterior: String?,
    val valorNuevo: String?,
)

/** Entrada de auditoria expuesta en respuestas. */
data class CambioAuditoriaDto(
    val id: Long,
    val campo: String,
    val valorAnterior: String?,
    val valorNuevo: String?,
    val changedAt: Instant,
    val changedBy: Long,
    val autor: EmpleadoResumen?,
)
