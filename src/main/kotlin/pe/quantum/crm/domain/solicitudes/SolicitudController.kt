package pe.quantum.crm.domain.solicitudes

import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import pe.quantum.crm.domain.solicitudes.dto.CrearSolicitudRequest
import pe.quantum.crm.domain.solicitudes.dto.DenegarSolicitudRequest
import pe.quantum.crm.domain.solicitudes.dto.SolicitudDto
import pe.quantum.crm.domain.solicitudes.dto.SolicitudFiltros
import pe.quantum.crm.shared.ApiResponse
import pe.quantum.crm.shared.security.UsuarioActualProvider

/** Endpoints de solicitudes de aprobacion (gerencia_contrato_frontend.md §4). */
@RestController
@RequestMapping("/api/v1/solicitudes")
class SolicitudController(
    private val solicitudService: SolicitudService,
    private val usuarioProvider: UsuarioActualProvider,
) {
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun crear(
        @Valid @RequestBody request: CrearSolicitudRequest,
    ): ApiResponse<SolicitudDto> = ApiResponse.ok(solicitudService.crear(request, usuarioProvider.actual()))

    @GetMapping
    @Suppress("LongParameterList") // Query params del contrato.
    fun listar(
        @RequestParam(required = false) estado: String?,
        @RequestParam(required = false) tipo: String?,
        @RequestParam(required = false, defaultValue = "false") mias: Boolean,
        @RequestParam(required = false) page: Int?,
        @RequestParam(name = "per_page", required = false) perPage: Int?,
        @RequestParam(required = false) sort: String?,
        @RequestParam(required = false) dir: String?,
    ): ApiResponse<List<SolicitudDto>> {
        val resultado =
            solicitudService.listar(
                SolicitudFiltros(estado = estado, tipo = tipo, mias = mias),
                usuarioProvider.actual(),
                page,
                perPage,
                sort,
                dir,
            )
        return ApiResponse.ok(resultado.items, resultado.meta)
    }

    @GetMapping("/{id}")
    fun detalle(
        @PathVariable id: Long,
    ): ApiResponse<SolicitudDto> = ApiResponse.ok(solicitudService.detalle(id, usuarioProvider.actual()))

    @PatchMapping("/{id}/aprobar")
    fun aprobar(
        @PathVariable id: Long,
    ): ApiResponse<SolicitudDto> = ApiResponse.ok(solicitudService.aprobar(id, usuarioProvider.actual()))

    @PatchMapping("/{id}/denegar")
    fun denegar(
        @PathVariable id: Long,
        @Valid @RequestBody request: DenegarSolicitudRequest,
    ): ApiResponse<SolicitudDto> = ApiResponse.ok(solicitudService.denegar(id, requireNotNull(request.motivo), usuarioProvider.actual()))
}
