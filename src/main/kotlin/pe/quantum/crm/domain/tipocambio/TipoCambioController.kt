package pe.quantum.crm.domain.tipocambio

import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import pe.quantum.crm.domain.tipocambio.dto.TipoCambioDto
import pe.quantum.crm.shared.ApiResponse

/**
 * Tipo de cambio PEN/USD vigente, visible en el layout global del CRM para
 * cualquier rol autenticado (reglas_simulaciones.md §12). Sin filtro de rol.
 *
 * Si aun no hay ningun valor guardado (el job diario no ha corrido todavia)
 * la respuesta es 200 con `data: null`, no 404: la ausencia del dato no es
 * un recurso inexistente.
 */
@RestController
@RequestMapping("/api/v1/tipo-cambio")
class TipoCambioController(
    private val tipoCambioService: TipoCambioService,
) {
    @GetMapping
    fun vigente(): ApiResponse<TipoCambioDto?> = ApiResponse.ok(tipoCambioService.vigente())
}
