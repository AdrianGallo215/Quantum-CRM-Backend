package pe.quantum.crm.domain.inicio

import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import pe.quantum.crm.domain.inicio.dto.InicioDto
import pe.quantum.crm.shared.ApiResponse
import pe.quantum.crm.shared.security.UsuarioActualProvider

/** Endpoint del panel de inicio (contrato_api.md §17). */
@RestController
@RequestMapping("/api/v1/inicio")
class InicioController(
    private val inicioService: InicioService,
    private val usuarioProvider: UsuarioActualProvider,
) {
    @GetMapping
    fun panel(): ApiResponse<InicioDto> = ApiResponse.ok(inicioService.panel(usuarioProvider.actual()))
}
