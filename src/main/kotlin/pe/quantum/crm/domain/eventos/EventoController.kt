package pe.quantum.crm.domain.eventos

import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import pe.quantum.crm.domain.eventos.dto.ActualizarEventoRequest
import pe.quantum.crm.domain.eventos.dto.CrearEventoRequest
import pe.quantum.crm.domain.eventos.dto.EventoDto
import pe.quantum.crm.domain.eventos.dto.EventoOcurridoDto
import pe.quantum.crm.domain.eventos.dto.EventosAgrupadosDto
import pe.quantum.crm.domain.eventos.dto.MarcarDescartadoRequest
import pe.quantum.crm.domain.eventos.dto.MarcarOcurridoRequest
import pe.quantum.crm.shared.ApiResponse
import pe.quantum.crm.shared.security.UsuarioActualProvider

/** Eventos de una oportunidad (contrato_api.md §11). */
@RestController
@RequestMapping("/api/v1/oportunidades/{idOportunidad}/eventos")
class OportunidadEventoController(
    private val eventoService: EventoService,
    private val usuarioProvider: UsuarioActualProvider,
) {
    @GetMapping
    fun listar(
        @PathVariable idOportunidad: Long,
    ): ApiResponse<EventosAgrupadosDto> = ApiResponse.ok(eventoService.listarPorOportunidad(idOportunidad, usuarioProvider.actual()))

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun crear(
        @PathVariable idOportunidad: Long,
        @Valid @RequestBody request: CrearEventoRequest,
    ): ApiResponse<EventoDto> = ApiResponse.ok(eventoService.crearEnOportunidad(idOportunidad, request, usuarioProvider.actual()))
}

/**
 * Eventos de prospeccion vinculados a la empresa (hitos, reglas §10.3).
 * Extension necesaria para registrar hitos sin oportunidad (migracion V21).
 */
@RestController
@RequestMapping("/api/v1/empresas/{idEmpresa}/eventos")
class EmpresaEventoController(
    private val eventoService: EventoService,
    private val usuarioProvider: UsuarioActualProvider,
) {
    @GetMapping
    fun listar(
        @PathVariable idEmpresa: Long,
    ): ApiResponse<EventosAgrupadosDto> = ApiResponse.ok(eventoService.listarPorEmpresa(idEmpresa, usuarioProvider.actual()))

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun crear(
        @PathVariable idEmpresa: Long,
        @Valid @RequestBody request: CrearEventoRequest,
    ): ApiResponse<EventoDto> = ApiResponse.ok(eventoService.crearEnEmpresa(idEmpresa, request, usuarioProvider.actual()))
}

/** Acciones sobre un evento (contrato_api.md §11). */
@RestController
@RequestMapping("/api/v1/eventos")
class EventoController(
    private val eventoService: EventoService,
    private val usuarioProvider: UsuarioActualProvider,
) {
    @PatchMapping("/{id}/ocurrido")
    fun ocurrido(
        @PathVariable id: Long,
        @Valid @RequestBody(required = false) request: MarcarOcurridoRequest?,
    ): ApiResponse<EventoOcurridoDto> =
        ApiResponse.ok(
            eventoService.marcarOcurrido(id, request ?: MarcarOcurridoRequest(), usuarioProvider.actual()),
        )

    @PatchMapping("/{id}/descartado")
    fun descartado(
        @PathVariable id: Long,
        @Valid @RequestBody(required = false) request: MarcarDescartadoRequest?,
    ): ApiResponse<EventoDto> =
        ApiResponse.ok(
            eventoService.marcarDescartado(id, request ?: MarcarDescartadoRequest(), usuarioProvider.actual()),
        )

    @PutMapping("/{id}")
    fun actualizar(
        @PathVariable id: Long,
        @Valid @RequestBody request: ActualizarEventoRequest,
    ): ApiResponse<EventoDto> = ApiResponse.ok(eventoService.actualizar(id, request, usuarioProvider.actual()))
}
