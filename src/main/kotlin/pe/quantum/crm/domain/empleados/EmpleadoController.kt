package pe.quantum.crm.domain.empleados

import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import pe.quantum.crm.domain.empleados.dto.EmpleadoDto
import pe.quantum.crm.domain.empleados.dto.toDto
import pe.quantum.crm.shared.ApiResponse

/**
 * Endpoints de empleados (contrato_api.md §7). Por ahora solo `/me`; el CRUD de
 * empleados es B1.4.
 */
@RestController
@RequestMapping("/api/v1/empleados")
class EmpleadoController(
    private val empleadoService: EmpleadoService,
) {
    /** Perfil del usuario autenticado. El id sale del JWT (principal del filtro). */
    @GetMapping("/me")
    fun me(authentication: Authentication): ApiResponse<EmpleadoDto> {
        val empleadoId = authentication.principal as Long
        return ApiResponse.ok(empleadoService.porId(empleadoId).toDto())
    }
}
