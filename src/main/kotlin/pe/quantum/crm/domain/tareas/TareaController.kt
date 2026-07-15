package pe.quantum.crm.domain.tareas

import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import pe.quantum.crm.domain.tareas.dto.ActualizarTareaRequest
import pe.quantum.crm.domain.tareas.dto.CompletarTareaRequest
import pe.quantum.crm.domain.tareas.dto.CrearTareaRequest
import pe.quantum.crm.domain.tareas.dto.TareaDto
import pe.quantum.crm.domain.tareas.dto.TareaFiltros
import pe.quantum.crm.shared.ApiResponse
import pe.quantum.crm.shared.security.UsuarioActualProvider

/** Endpoints de tareas (contrato_api.md §12). */
@RestController
@RequestMapping("/api/v1/tareas")
class TareaController(
    private val tareaService: TareaService,
    private val usuarioProvider: UsuarioActualProvider,
) {
    @GetMapping
    @Suppress("LongParameterList") // Query params del contrato §12.
    fun listar(
        @RequestParam(name = "id_empresa", required = false) idEmpresa: Long?,
        @RequestParam(name = "id_oportunidad", required = false) idOportunidad: Long?,
        @RequestParam(name = "estado_accion", required = false) estadoAccion: String?,
        @RequestParam(name = "id_asignado", required = false) idAsignado: Long?,
        @RequestParam(name = "solo_prospeccion", required = false, defaultValue = "false") soloProspeccion: Boolean,
        @RequestParam(required = false, defaultValue = "false") vencidas: Boolean,
        @RequestParam(required = false) page: Int?,
        @RequestParam(name = "per_page", required = false) perPage: Int?,
        @RequestParam(required = false) sort: String?,
        @RequestParam(required = false) dir: String?,
    ): ApiResponse<List<TareaDto>> {
        val filtros =
            TareaFiltros(
                idEmpresa = idEmpresa,
                idOportunidad = idOportunidad,
                estadoAccion = estadoAccion,
                idAsignado = idAsignado,
                soloProspeccion = soloProspeccion,
                vencidas = vencidas,
            )
        val resultado = tareaService.listar(filtros, usuarioProvider.actual(), page, perPage, sort, dir)
        return ApiResponse.ok(resultado.items, resultado.meta)
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun crear(
        @RequestBody request: CrearTareaRequest,
    ): ApiResponse<TareaDto> = ApiResponse.ok(tareaService.crear(request, usuarioProvider.actual()))

    @PatchMapping("/{id}/completada")
    fun completar(
        @PathVariable id: Long,
        @RequestBody(required = false) request: CompletarTareaRequest?,
    ): ApiResponse<TareaDto> = ApiResponse.ok(tareaService.completar(id, request?.descripcion, usuarioProvider.actual()))

    @PatchMapping("/{id}/cancelada")
    fun cancelar(
        @PathVariable id: Long,
    ): ApiResponse<TareaDto> = ApiResponse.ok(tareaService.cancelar(id, usuarioProvider.actual()))

    @PutMapping("/{id}")
    fun actualizar(
        @PathVariable id: Long,
        @RequestBody request: ActualizarTareaRequest,
    ): ApiResponse<TareaDto> = ApiResponse.ok(tareaService.actualizar(id, request, usuarioProvider.actual()))
}
