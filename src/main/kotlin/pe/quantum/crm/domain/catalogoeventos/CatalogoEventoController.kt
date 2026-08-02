package pe.quantum.crm.domain.catalogoeventos

import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import pe.quantum.crm.domain.catalogoeventos.dto.ActualizarCatalogoEventoRequest
import pe.quantum.crm.domain.catalogoeventos.dto.CatalogoEventoDto
import pe.quantum.crm.domain.catalogoeventos.dto.CrearCatalogoEventoRequest
import pe.quantum.crm.shared.ApiResponse
import pe.quantum.crm.shared.enums.EstadoOportunidad

/** Endpoints del catalogo de eventos (contrato_api.md §15). Escritura: solo admin. */
@RestController
@RequestMapping("/api/v1/catalogo-eventos")
class CatalogoEventoController(
    private val catalogoEventoService: CatalogoEventoService,
) {
    @GetMapping
    fun listar(
        @RequestParam(name = "etapa_asociada", required = false) etapaAsociada: EstadoOportunidad?,
    ): ApiResponse<List<CatalogoEventoDto>> = ApiResponse.ok(catalogoEventoService.listar(etapaAsociada))

    @PostMapping
    @PreAuthorize("hasRole('admin')")
    @ResponseStatus(HttpStatus.CREATED)
    fun crear(
        @Valid @RequestBody request: CrearCatalogoEventoRequest,
    ): ApiResponse<CatalogoEventoDto> = ApiResponse.ok(catalogoEventoService.crear(request))

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('admin')")
    fun actualizar(
        @PathVariable id: Long,
        @RequestBody request: ActualizarCatalogoEventoRequest,
    ): ApiResponse<CatalogoEventoDto> = ApiResponse.ok(catalogoEventoService.actualizar(id, request))
}
