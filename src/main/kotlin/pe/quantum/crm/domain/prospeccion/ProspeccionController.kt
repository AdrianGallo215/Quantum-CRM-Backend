package pe.quantum.crm.domain.prospeccion

import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import pe.quantum.crm.domain.prospeccion.dto.ProspeccionItemDto
import pe.quantum.crm.shared.ApiResponse
import pe.quantum.crm.shared.security.UsuarioActualProvider

/** Endpoint de prospeccion (contrato_api.md §16). */
@RestController
@RequestMapping("/api/v1/prospeccion")
class ProspeccionController(
    private val prospeccionService: ProspeccionService,
    private val usuarioProvider: UsuarioActualProvider,
) {
    @GetMapping
    fun listar(
        @RequestParam(required = false) page: Int?,
        @RequestParam(name = "per_page", required = false) perPage: Int?,
    ): ApiResponse<List<ProspeccionItemDto>> {
        val resultado = prospeccionService.listar(usuarioProvider.actual(), page, perPage)
        return ApiResponse.ok(resultado.items, resultado.meta)
    }
}
