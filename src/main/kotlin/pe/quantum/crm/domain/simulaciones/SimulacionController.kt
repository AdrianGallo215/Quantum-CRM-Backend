package pe.quantum.crm.domain.simulaciones

import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import pe.quantum.crm.domain.simulaciones.dto.ActualizarSimulacionRequest
import pe.quantum.crm.domain.simulaciones.dto.BifurcarSimulacionRequest
import pe.quantum.crm.domain.simulaciones.dto.CrearSimulacionRequest
import pe.quantum.crm.domain.simulaciones.dto.CronogramaDto
import pe.quantum.crm.domain.simulaciones.dto.EventoHistorialDto
import pe.quantum.crm.domain.simulaciones.dto.RestaurarSimulacionRequest
import pe.quantum.crm.domain.simulaciones.dto.SimulacionDto
import pe.quantum.crm.domain.simulaciones.dto.SimulacionFiltros
import pe.quantum.crm.shared.ApiResponse
import pe.quantum.crm.shared.security.UsuarioActualProvider

/**
 * Endpoints del modulo de simulaciones del financiamiento propio de Quantum
 * (`reglas_simulaciones.md`).
 *
 * **Sin `@PreAuthorize` a proposito**: toda la autorizacion de este modulo vive
 * en [SimulacionPermisos], el unico punto de decision que exige §10
 * (plan-09-mapa-simulaciones-modulo.md, decision D30). No agregues anotaciones
 * de rol aqui — partiria la decision en dos sitios.
 */
@RestController
@RequestMapping("/api/v1/simulaciones")
class SimulacionController(
    private val simulacionService: SimulacionService,
    private val usuarioProvider: UsuarioActualProvider,
) {
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun crear(
        @Valid @RequestBody request: CrearSimulacionRequest,
    ): ApiResponse<SimulacionDto> = ApiResponse.ok(simulacionService.crear(request, usuarioProvider.actual()))

    @GetMapping
    @Suppress("LongParameterList") // Query params del contrato.
    fun listar(
        @RequestParam(required = false, name = "id_oportunidad_item") idOportunidadItem: Long?,
        @RequestParam(required = false, name = "id_modelo") idModelo: Long?,
        @RequestParam(required = false) modo: String?,
        @RequestParam(required = false) page: Int?,
        @RequestParam(name = "per_page", required = false) perPage: Int?,
        @RequestParam(required = false) sort: String?,
        @RequestParam(required = false) dir: String?,
    ): ApiResponse<List<SimulacionDto>> {
        val resultado =
            simulacionService.listar(
                SimulacionFiltros(idOportunidadItem = idOportunidadItem, idModelo = idModelo, modo = modo),
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
    ): ApiResponse<SimulacionDto> = ApiResponse.ok(simulacionService.detalle(id, usuarioProvider.actual()))

    @GetMapping("/{id}/cronograma")
    fun cronograma(
        @PathVariable id: Long,
    ): ApiResponse<CronogramaDto> = ApiResponse.ok(simulacionService.cronograma(id, usuarioProvider.actual()))

    @PatchMapping("/{id}")
    fun actualizar(
        @PathVariable id: Long,
        @Valid @RequestBody request: ActualizarSimulacionRequest,
    ): ApiResponse<SimulacionDto> = ApiResponse.ok(simulacionService.actualizar(id, request, usuarioProvider.actual()))

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun eliminar(
        @PathVariable id: Long,
    ) {
        simulacionService.eliminar(id, usuarioProvider.actual())
    }

    @GetMapping("/{id}/historial")
    fun historial(
        @PathVariable id: Long,
    ): ApiResponse<List<EventoHistorialDto>> = ApiResponse.ok(simulacionService.historial(id, usuarioProvider.actual()))

    @PostMapping("/{id}/restaurar")
    fun restaurar(
        @PathVariable id: Long,
        @Valid @RequestBody request: RestaurarSimulacionRequest,
    ): ApiResponse<SimulacionDto> = ApiResponse.ok(simulacionService.restaurar(id, request.idEventoLog, usuarioProvider.actual()))

    @PostMapping("/{id}/bifurcar")
    @ResponseStatus(HttpStatus.CREATED)
    fun bifurcar(
        @PathVariable id: Long,
        @Valid @RequestBody request: BifurcarSimulacionRequest,
    ): ApiResponse<SimulacionDto> = ApiResponse.ok(simulacionService.bifurcar(id, request, usuarioProvider.actual()))

    @PatchMapping("/{id}/principal")
    fun marcarPrincipal(
        @PathVariable id: Long,
    ): ApiResponse<SimulacionDto> = ApiResponse.ok(simulacionService.marcarPrincipal(id, usuarioProvider.actual()))
}
