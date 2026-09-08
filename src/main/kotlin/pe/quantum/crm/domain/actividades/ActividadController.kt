package pe.quantum.crm.domain.actividades

import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import pe.quantum.crm.domain.actividades.dto.ActividadDto
import pe.quantum.crm.domain.actividades.dto.CambioAuditoriaDto
import pe.quantum.crm.domain.actividades.dto.ComentarioDto
import pe.quantum.crm.domain.actividades.dto.CrearComentarioRequest
import pe.quantum.crm.domain.actividades.dto.HistorialFiltros
import pe.quantum.crm.shared.ApiResponse
import pe.quantum.crm.shared.exception.ValidacionException
import pe.quantum.crm.shared.security.UsuarioActualProvider
import java.time.Instant

/**
 * Historial de actividades: la vista de supervision que une tareas y eventos
 * (contrato_api.md §29).
 *
 * `id_empleado` es obligatorio: la vista es "el historial de UNA persona".
 * Exigirlo acota el volumen que se une en memoria y hace el guard de permisos
 * explicito en cada llamada.
 */
@RestController
@RequestMapping("/api/v1/actividades")
class ActividadController(
    private val historialService: HistorialActividadService,
    private val comentarioService: ComentarioActividadService,
    private val usuarioProvider: UsuarioActualProvider,
) {
    @GetMapping
    @Suppress("LongParameterList") // Query params del contrato §29.
    fun historial(
        @RequestParam(name = "id_empleado") idEmpleado: Long,
        @RequestParam(required = false) desde: Instant?,
        @RequestParam(required = false) hasta: Instant?,
        @RequestParam(required = false) tipo: String?,
        @RequestParam(name = "id_empresa", required = false) idEmpresa: Long?,
        @RequestParam(name = "id_oportunidad", required = false) idOportunidad: Long?,
        @RequestParam(required = false) page: Int?,
        @RequestParam(name = "per_page", required = false) perPage: Int?,
    ): ApiResponse<List<ActividadDto>> {
        // Un `tipo` desconocido es error del cliente, no un filtro que se ignora:
        // devolver todo cuando pidieron "solo tareas" seria una fuga silenciosa.
        tipo?.let { aTipo(it) }
        val filtros =
            HistorialFiltros(
                idEmpleado = idEmpleado,
                desde = desde,
                hasta = hasta,
                tipo = tipo,
                idEmpresa = idEmpresa,
                idOportunidad = idOportunidad,
            )
        val resultado = historialService.historial(filtros, usuarioProvider.actual(), page, perPage)
        return ApiResponse.ok(resultado.items, resultado.meta)
    }

    @GetMapping("/{tipo}/{id}/comentarios")
    fun comentarios(
        @PathVariable tipo: String,
        @PathVariable id: Long,
    ): ApiResponse<List<ComentarioDto>> = ApiResponse.ok(comentarioService.listar(aTipo(tipo), id, usuarioProvider.actual()))

    @PostMapping("/{tipo}/{id}/comentarios")
    @ResponseStatus(HttpStatus.CREATED)
    fun comentar(
        @PathVariable tipo: String,
        @PathVariable id: Long,
        @Valid @RequestBody request: CrearComentarioRequest,
    ): ApiResponse<ComentarioDto> = ApiResponse.ok(comentarioService.crear(aTipo(tipo), id, request.texto, usuarioProvider.actual()))

    @GetMapping("/{tipo}/{id}/auditoria")
    fun auditoria(
        @PathVariable tipo: String,
        @PathVariable id: Long,
    ): ApiResponse<List<CambioAuditoriaDto>> = ApiResponse.ok(historialService.auditoria(aTipo(tipo), id, usuarioProvider.actual()))

    /** `tarea` o `evento`; cualquier otra cosa es 400, no un 500 por `valueOf`. */
    private fun aTipo(tipo: String): TipoActividad =
        runCatching { TipoActividad.valueOf(tipo) }.getOrElse {
            throw ValidacionException(
                "El tipo de actividad '$tipo' no es válido. Valores permitidos: tarea, evento",
                field = "tipo",
            )
        }
}
