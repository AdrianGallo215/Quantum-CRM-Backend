package pe.quantum.crm.domain.financiadoras

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
import pe.quantum.crm.domain.financiadoras.dto.ActualizarFinanciadoraRequest
import pe.quantum.crm.domain.financiadoras.dto.CrearFinanciadoraRequest
import pe.quantum.crm.domain.financiadoras.dto.FinanciadoraDto
import pe.quantum.crm.shared.ApiResponse

/** Endpoints de financiadoras (contrato_api.md §13). Escritura: admin/gerencia. */
@RestController
@RequestMapping("/api/v1/financiadoras")
class FinanciadoraController(
    private val financiadoraService: FinanciadoraService,
) {
    @GetMapping
    fun listar(): ApiResponse<List<FinanciadoraDto>> = ApiResponse.ok(financiadoraService.listar())

    @PostMapping
    @PreAuthorize("hasAnyRole('admin', 'gerencia')")
    @ResponseStatus(HttpStatus.CREATED)
    fun crear(
        @Valid @RequestBody request: CrearFinanciadoraRequest,
    ): ApiResponse<FinanciadoraDto> = ApiResponse.ok(financiadoraService.crear(request))

    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('admin', 'gerencia')")
    fun actualizar(
        @PathVariable id: Long,
        @RequestBody request: ActualizarFinanciadoraRequest,
    ): ApiResponse<FinanciadoraDto> = ApiResponse.ok(financiadoraService.actualizar(id, request))
}
