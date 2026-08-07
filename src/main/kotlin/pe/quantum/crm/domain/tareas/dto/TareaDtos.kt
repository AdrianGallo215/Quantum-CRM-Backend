package pe.quantum.crm.domain.tareas.dto

import jakarta.validation.constraints.Positive
import jakarta.validation.constraints.Size
import pe.quantum.crm.domain.contactos.dto.ContactoResumen
import pe.quantum.crm.domain.empleados.dto.EmpleadoResumen
import pe.quantum.crm.domain.empresas.dto.EmpresaResumen
import pe.quantum.crm.shared.enums.TipoAccion
import java.time.Instant
import java.time.LocalDateTime

/** Tarea expuesta en respuestas (contrato_api.md §12). */
data class TareaDto(
    val id: Long,
    val idEmpresa: Long,
    val empresa: EmpresaResumen?,
    val idOportunidad: Long?,
    val idContacto: Long?,
    val contacto: ContactoResumen?,
    val idAsignado: Long?,
    val asignado: EmpleadoResumen?,
    val idsColaboradores: List<Long>,
    val colaboradores: List<EmpleadoResumen>,
    val tipoAccion: String,
    val estadoAccion: String,
    val descripcion: String?,
    val fechaEjecucion: Instant?,
    val createdAt: Instant,
)

/** Longitud maxima de los textos libres de una tarea (columna TEXT, sin tope en BD). */
private const val MAX_DESCRIPCION = 5000

/** Body de `POST /tareas`. */
data class CrearTareaRequest(
    @field:Positive(message = "id_empresa debe ser un identificador valido")
    val idEmpresa: Long,
    @field:Positive(message = "id_oportunidad debe ser un identificador valido")
    val idOportunidad: Long? = null,
    @field:Positive(message = "id_contacto debe ser un identificador valido")
    val idContacto: Long? = null,
    @field:Positive(message = "id_asignado debe ser un identificador valido")
    val idAsignado: Long? = null,
    // Sin constraint sobre los elementos: `List<@Positive Long>` compila pero NO
    // valida nada. Kotlin no emite la anotacion como type annotation del argumento
    // generico donde Hibernate Validator la busca, asi que el request pasaba entero
    // sin que saltase la validacion. Un constraint que no se aplica es peor que su
    // ausencia: da falsa confianza. Los ids se validan en TareaServiceImpl contra
    // `empleadoService.existeActivo`, que responde 404 si no existe o esta inactivo.
    val idsColaboradores: List<Long> = emptyList(),
    val tipoAccion: TipoAccion,
    @field:Size(max = MAX_DESCRIPCION, message = "descripcion supera la longitud maxima")
    val descripcion: String? = null,
    val fechaEjecucion: Instant? = null,
)

/**
 * Body de `PUT /tareas/:id` (solo tareas pendientes). `idsColaboradores`, si
 * viene (aunque sea `[]`), reemplaza el set completo de colaboradores; si se
 * omite (`null`), los colaboradores existentes no se tocan.
 */
data class ActualizarTareaRequest(
    val tipoAccion: TipoAccion? = null,
    @field:Size(max = MAX_DESCRIPCION, message = "descripcion supera la longitud maxima")
    val descripcion: String? = null,
    val fechaEjecucion: Instant? = null,
    @field:Positive(message = "id_contacto debe ser un identificador valido")
    val idContacto: Long? = null,
    @field:Positive(message = "id_asignado debe ser un identificador valido")
    val idAsignado: Long? = null,
    // Ver la nota de CrearTareaRequest: los elementos se validan en el servicio.
    val idsColaboradores: List<Long>? = null,
)

/** Body opcional de `PATCH /tareas/:id/completada`. */
data class CompletarTareaRequest(
    @field:Size(max = MAX_DESCRIPCION, message = "descripcion supera la longitud maxima")
    val descripcion: String? = null,
)

/** Filtros del listado de tareas (contrato §12). */
data class TareaFiltros(
    val idEmpresa: Long? = null,
    val idOportunidad: Long? = null,
    val estadoAccion: String? = null,
    val idAsignado: Long? = null,
    val soloProspeccion: Boolean = false,
    val vencidas: Boolean = false,
)

/** Proyeccion de solo lectura para el job de recordatorios (notificaciones). */
data class TareaRecordatorioProyeccion(
    val id: Long,
    val idAsignado: Long,
    val idEmpresa: Long,
    val idOportunidad: Long?,
    val fechaEjecucion: LocalDateTime,
)
