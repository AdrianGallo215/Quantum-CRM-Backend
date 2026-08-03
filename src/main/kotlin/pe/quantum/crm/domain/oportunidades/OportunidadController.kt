package pe.quantum.crm.domain.oportunidades

import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.DeleteMapping
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
import pe.quantum.crm.domain.oportunidades.dto.ActualizarOportunidadRequest
import pe.quantum.crm.domain.oportunidades.dto.CambiarEstadoRequest
import pe.quantum.crm.domain.oportunidades.dto.CambioEstadoDto
import pe.quantum.crm.domain.oportunidades.dto.ContactoVinculoRequest
import pe.quantum.crm.domain.oportunidades.dto.CrearOportunidadRequest
import pe.quantum.crm.domain.oportunidades.dto.LogEstadoDto
import pe.quantum.crm.domain.oportunidades.dto.OportunidadDto
import pe.quantum.crm.domain.oportunidades.dto.OportunidadFiltros
import pe.quantum.crm.shared.ApiResponse
import pe.quantum.crm.shared.security.UsuarioActualProvider

/** Endpoints de oportunidades (contrato_api.md §10). */
@RestController
@RequestMapping("/api/v1/oportunidades")
class OportunidadController(
    private val oportunidadService: OportunidadService,
    private val usuarioProvider: UsuarioActualProvider,
) {
    @GetMapping
    @Suppress("LongParameterList") // Query params del contrato §10.
    fun listar(
        @RequestParam(required = false) estado: String?,
        @RequestParam(name = "id_empresa", required = false) idEmpresa: Long?,
        @RequestParam(name = "id_vendedor", required = false) idVendedor: Long?,
        @RequestParam(name = "id_financiadora", required = false) idFinanciadora: Long?,
        @RequestParam(name = "incluir_cerradas", required = false, defaultValue = "false") incluirCerradas: Boolean,
        @RequestParam(required = false) page: Int?,
        @RequestParam(name = "per_page", required = false) perPage: Int?,
        @RequestParam(required = false) sort: String?,
        @RequestParam(required = false) dir: String?,
    ): ApiResponse<List<OportunidadDto>> {
        val filtros =
            OportunidadFiltros(
                estado = estado,
                idEmpresa = idEmpresa,
                idVendedor = idVendedor,
                idFinanciadora = idFinanciadora,
                incluirCerradas = incluirCerradas,
            )
        val resultado = oportunidadService.listar(filtros, usuarioProvider.actual(), page, perPage, sort, dir)
        return ApiResponse.ok(resultado.items, resultado.meta)
    }

    @GetMapping("/{id}")
    fun detalle(
        @PathVariable id: Long,
    ): ApiResponse<OportunidadDto> = ApiResponse.ok(oportunidadService.detalle(id, usuarioProvider.actual()))

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun crear(
        @Valid @RequestBody request: CrearOportunidadRequest,
    ): ApiResponse<OportunidadDto> = ApiResponse.ok(oportunidadService.crear(request, usuarioProvider.actual()))

    @PutMapping("/{id}")
    fun actualizar(
        @PathVariable id: Long,
        @Valid @RequestBody request: ActualizarOportunidadRequest,
    ): ApiResponse<OportunidadDto> = ApiResponse.ok(oportunidadService.actualizar(id, request, usuarioProvider.actual()))

    @PatchMapping("/{id}/estado")
    fun cambiarEstado(
        @PathVariable id: Long,
        @Valid @RequestBody request: CambiarEstadoRequest,
    ): ApiResponse<CambioEstadoDto> = ApiResponse.ok(oportunidadService.cambiarEstado(id, request, usuarioProvider.actual()))

    @GetMapping("/{id}/log")
    fun log(
        @PathVariable id: Long,
    ): ApiResponse<List<LogEstadoDto>> = ApiResponse.ok(oportunidadService.log(id, usuarioProvider.actual()))

    @PostMapping("/{id}/contactos")
    @ResponseStatus(HttpStatus.CREATED)
    fun vincularContacto(
        @PathVariable id: Long,
        @Valid @RequestBody request: ContactoVinculoRequest,
    ): ApiResponse<ContactoVinculoRequest> = ApiResponse.ok(oportunidadService.vincularContacto(id, request, usuarioProvider.actual()))

    @PutMapping("/{id}/contactos/{idContacto}")
    fun actualizarContacto(
        @PathVariable id: Long,
        @PathVariable idContacto: Long,
        @Valid @RequestBody request: ContactoVinculoRequest,
    ): ApiResponse<ContactoVinculoRequest> =
        ApiResponse.ok(
            oportunidadService.actualizarContacto(id, idContacto, request.rolEnOportunidad, usuarioProvider.actual()),
        )

    @DeleteMapping("/{id}/contactos/{idContacto}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun desvincularContacto(
        @PathVariable id: Long,
        @PathVariable idContacto: Long,
    ) {
        oportunidadService.desvincularContacto(id, idContacto, usuarioProvider.actual())
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('admin')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun eliminar(
        @PathVariable id: Long,
    ) {
        oportunidadService.eliminar(id)
    }
}
