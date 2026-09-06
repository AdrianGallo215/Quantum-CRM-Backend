package pe.quantum.crm.domain.simulaciones

import jakarta.validation.Valid
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import pe.quantum.crm.domain.simulaciones.dto.CalculadoraDto
import pe.quantum.crm.domain.simulaciones.dto.CalculadoraRequest
import pe.quantum.crm.shared.ApiResponse
import pe.quantum.crm.shared.security.UsuarioActualProvider

/**
 * Endpoint de la Calculadora Financiera (`reglas_simulaciones.md` §9):
 * estimacion rapida durante la prospeccion, con el mismo motor y las mismas
 * validaciones que el modulo de simulaciones, pero **cero persistencia** — no
 * escribe en `simulaciones` ni en `simulacion_log`.
 *
 * Controller **propio y separado** de [SimulacionController] a proposito
 * (decision D50 de plan-11-mapa-historial-calculadora.md): §9 llama a la
 * Calculadora "modulo aparte", y colgar esta ruta de `/simulaciones`
 * sugeriria que comparte su ciclo de vida cuando la premisa entera es que no
 * persiste nada.
 *
 * **Sin `@PreAuthorize` a proposito**, por la misma razon que
 * [SimulacionController]: toda la autorizacion vive en [SimulacionPermisos],
 * que [CalculadoraFinancieraServiceImpl] ya invoca. Agregar una anotacion de
 * rol aqui partiria la decision en dos sitios.
 */
@RestController
@RequestMapping("/api/v1/calculadora")
class CalculadoraFinancieraController(
    private val calculadoraFinancieraService: CalculadoraFinancieraService,
    private val usuarioProvider: UsuarioActualProvider,
) {
    @PostMapping
    fun calcular(
        @Valid @RequestBody request: CalculadoraRequest,
    ): ApiResponse<CalculadoraDto> = ApiResponse.ok(calculadoraFinancieraService.calcular(request, usuarioProvider.actual()))
}
