package pe.quantum.crm.domain.notificaciones

import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import pe.quantum.crm.domain.notificaciones.dto.ContadorNoLeidasDto
import pe.quantum.crm.domain.notificaciones.dto.NotificacionDto
import pe.quantum.crm.shared.ApiResponse
import pe.quantum.crm.shared.security.UsuarioActualProvider

/** Endpoints de notificaciones (contrato_api.md §19). */
@RestController
@RequestMapping("/api/v1/notificaciones")
class NotificacionController(
    private val notificacionService: NotificacionService,
    private val usuarioProvider: UsuarioActualProvider,
) {
    @GetMapping("/no-leidas/count")
    fun contarNoLeidas(): ApiResponse<ContadorNoLeidasDto> =
        ApiResponse.ok(ContadorNoLeidasDto(notificacionService.contarNoLeidas(usuarioProvider.actual())))

    @GetMapping
    fun listar(): ApiResponse<List<NotificacionDto>> = ApiResponse.ok(notificacionService.listar(usuarioProvider.actual()))

    @PatchMapping("/{id}/leida")
    fun marcarLeida(
        @PathVariable id: Long,
    ): ApiResponse<Map<String, Boolean>> {
        notificacionService.marcarLeida(id, usuarioProvider.actual())
        return ApiResponse.ok(mapOf("leida" to true))
    }

    @PatchMapping("/leidas")
    fun marcarTodasLeidas(): ApiResponse<Map<String, Boolean>> {
        notificacionService.marcarTodasLeidas(usuarioProvider.actual())
        return ApiResponse.ok(mapOf("leida" to true))
    }
}
