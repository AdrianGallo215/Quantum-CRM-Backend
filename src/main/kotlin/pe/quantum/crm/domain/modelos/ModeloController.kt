package pe.quantum.crm.domain.modelos

import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import pe.quantum.crm.domain.modelos.dto.ActualizarModeloRequest
import pe.quantum.crm.domain.modelos.dto.CrearModeloRequest
import pe.quantum.crm.domain.modelos.dto.ModeloDto
import pe.quantum.crm.shared.ApiResponse

/** Endpoints del catalogo de modelos (contrato_api.md §14). Escritura: admin/gerencia. */
@RestController
@RequestMapping("/api/v1/modelos")
class ModeloController(
    private val modeloService: ModeloService,
) {
    @GetMapping
    fun listar(): ApiResponse<List<ModeloDto>> = ApiResponse.ok(modeloService.listar())

    @PostMapping
    @PreAuthorize("hasAnyRole('admin', 'gerencia')")
    @ResponseStatus(HttpStatus.CREATED)
    fun crear(
        @Valid @RequestBody request: CrearModeloRequest,
    ): ApiResponse<ModeloDto> = ApiResponse.ok(modeloService.crear(request))

    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('admin', 'gerencia')")
    fun actualizar(
        @PathVariable id: Long,
        @RequestBody request: ActualizarModeloRequest,
    ): ApiResponse<ModeloDto> = ApiResponse.ok(modeloService.actualizar(id, request))
}
