package pe.quantum.crm.domain.empresas

import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
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
import pe.quantum.crm.domain.empresas.dto.ActualizarEmpresaRequest
import pe.quantum.crm.domain.empresas.dto.CambiarCarteraMaestraRequest
import pe.quantum.crm.domain.empresas.dto.CambiarEstadoCarteraRequest
import pe.quantum.crm.domain.empresas.dto.CarteraMaestraDto
import pe.quantum.crm.domain.empresas.dto.CrearEmpresaRequest
import pe.quantum.crm.domain.empresas.dto.EmpresaDetalleDto
import pe.quantum.crm.domain.empresas.dto.EmpresaFiltros
import pe.quantum.crm.domain.empresas.dto.EmpresaListaDto
import pe.quantum.crm.domain.empresas.dto.ReasignarVendedorRequest
import pe.quantum.crm.domain.empresas.dto.RucCheckDto
import pe.quantum.crm.shared.ApiResponse
import pe.quantum.crm.shared.enums.Segmento
import pe.quantum.crm.shared.exception.ValidacionException
import pe.quantum.crm.shared.security.UsuarioActualProvider

/** Endpoints de empresas (contrato_api.md §8). */
@RestController
@RequestMapping("/api/v1/empresas")
class EmpresaController(
    private val empresaService: EmpresaService,
    private val usuarioProvider: UsuarioActualProvider,
) {
    @GetMapping
    @Suppress("LongParameterList") // Query params del contrato §8.
    fun listar(
        @RequestParam(required = false) q: String?,
        @RequestParam(name = "estado_cartera", required = false) estadoCartera: String?,
        @RequestParam(name = "id_vendedor", required = false) idVendedor: Long?,
        @RequestParam(required = false) segmento: Segmento?,
        @RequestParam(required = false) distrito: String?,
        @RequestParam(name = "cartera_maestra", required = false) carteraMaestra: Boolean?,
        @RequestParam(required = false) page: Int?,
        @RequestParam(name = "per_page", required = false) perPage: Int?,
        @RequestParam(required = false) sort: String?,
        @RequestParam(required = false) dir: String?,
    ): ApiResponse<List<EmpresaListaDto>> {
        val usuario = usuarioProvider.actual()
        val filtros =
            EmpresaFiltros(
                q = q,
                estadoCartera = estadoCartera,
                idVendedor = idVendedor,
                segmento = segmento,
                distrito = distrito,
                carteraMaestra = carteraMaestra,
            )
        val resultado = empresaService.listar(filtros, usuario, page, perPage, sort, dir)
        return ApiResponse.ok(resultado.items, resultado.meta)
    }

    /** Ruta literal antes que `{id}` para que Spring no las confunda. */
    @GetMapping("/ruc/{ruc}")
    fun checkRuc(
        @PathVariable ruc: String,
    ): ApiResponse<RucCheckDto> = ApiResponse.ok(empresaService.checkRuc(ruc))

    @GetMapping("/{id}")
    fun detalle(
        @PathVariable id: Long,
    ): ApiResponse<EmpresaDetalleDto> = ApiResponse.ok(empresaService.detalle(id, usuarioProvider.actual()))

    @PostMapping
    fun crear(
        @Valid @RequestBody request: CrearEmpresaRequest,
    ): ResponseEntity<ApiResponse<EmpresaDetalleDto>> {
        val resultado = empresaService.crear(request, usuarioProvider.actual())
        // 201 solo si de verdad se creo; si el RUC ya era suyo, 200 (reglas §2.1).
        val status = if (resultado.creada) HttpStatus.CREATED else HttpStatus.OK
        return ResponseEntity.status(status).body(ApiResponse.ok(resultado.empresa))
    }

    @PutMapping("/{id}")
    fun actualizar(
        @PathVariable id: Long,
        @RequestBody request: ActualizarEmpresaRequest,
    ): ApiResponse<EmpresaDetalleDto> = ApiResponse.ok(empresaService.actualizar(id, request, usuarioProvider.actual()))

    @PatchMapping("/{id}/estado-cartera")
    fun cambiarEstadoCartera(
        @PathVariable id: Long,
        @RequestBody request: CambiarEstadoCarteraRequest,
    ): ApiResponse<Map<String, String>> {
        val estado = empresaService.cambiarEstadoCarteraManual(id, request.estadoCartera, usuarioProvider.actual())
        return ApiResponse.ok(mapOf("estado_cartera" to estado))
    }

    @PatchMapping("/{id}/vendedor")
    @PreAuthorize("hasAnyRole('admin', 'gerencia')")
    fun reasignarVendedor(
        @PathVariable id: Long,
        @RequestBody request: ReasignarVendedorRequest,
    ): ApiResponse<Map<String, Long>> {
        val idVendedor = empresaService.reasignarVendedor(id, request.idVendedor, usuarioProvider.actual())
        return ApiResponse.ok(mapOf("id_vendedor" to idVendedor))
    }

    @PatchMapping("/{id}/cartera-maestra")
    @PreAuthorize("hasAnyRole('admin', 'gerencia')")
    fun cambiarCarteraMaestra(
        @PathVariable id: Long,
        @RequestBody request: CambiarCarteraMaestraRequest,
    ): ApiResponse<CarteraMaestraDto> {
        val enCarteraMaestra =
            request.enCarteraMaestra
                ?: throw ValidacionException("en_cartera_maestra es obligatorio", field = "en_cartera_maestra")
        return ApiResponse.ok(
            empresaService.cambiarCarteraMaestra(id, enCarteraMaestra, request.idVendedor, usuarioProvider.actual()),
        )
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('admin')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun eliminar(
        @PathVariable id: Long,
    ) {
        empresaService.eliminar(id)
    }
}
