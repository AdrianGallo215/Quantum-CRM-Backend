package pe.quantum.crm.domain.metasventa

import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import pe.quantum.crm.domain.metasventa.dto.CrearMetaVentaRequest
import pe.quantum.crm.domain.metasventa.dto.EditarMetaVentaRequest
import pe.quantum.crm.domain.metasventa.dto.MetaVentaDto
import pe.quantum.crm.domain.metasventa.dto.MetaVentaFiltros
import pe.quantum.crm.domain.metasventa.dto.RechazarMetaVentaRequest
import pe.quantum.crm.shared.ApiResponse
import pe.quantum.crm.shared.security.UsuarioActualProvider

/** Endpoints de metas de venta en unidades (contrato_api.md §21). */
@RestController
@RequestMapping("/api/v1/metas-venta")
class MetaVentaController(
    private val metaVentaService: MetaVentaService,
    private val usuarioProvider: UsuarioActualProvider,
) {
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun crear(
        @Valid @RequestBody request: CrearMetaVentaRequest,
    ): ApiResponse<MetaVentaDto> = ApiResponse.ok(metaVentaService.crear(request, usuarioProvider.actual()))

    @PatchMapping("/{id}")
    fun editar(
        @PathVariable id: Long,
        @Valid @RequestBody request: EditarMetaVentaRequest,
    ): ApiResponse<MetaVentaDto> = ApiResponse.ok(metaVentaService.editar(id, request, usuarioProvider.actual()))

    @PatchMapping("/{id}/aprobar")
    fun aprobar(
        @PathVariable id: Long,
    ): ApiResponse<MetaVentaDto> = ApiResponse.ok(metaVentaService.aprobar(id, usuarioProvider.actual()))

    @PatchMapping("/{id}/rechazar")
    fun rechazar(
        @PathVariable id: Long,
        @Valid @RequestBody request: RechazarMetaVentaRequest,
    ): ApiResponse<MetaVentaDto> = ApiResponse.ok(metaVentaService.rechazar(id, requireNotNull(request.motivo), usuarioProvider.actual()))

    @GetMapping
    @Suppress("LongParameterList") // Query params del contrato.
    fun listar(
        @RequestParam(required = false, name = "id_empleado") idEmpleado: Long?,
        @RequestParam(required = false) anio: Int?,
        @RequestParam(required = false) estado: String?,
        @RequestParam(required = false) page: Int?,
        @RequestParam(name = "per_page", required = false) perPage: Int?,
        @RequestParam(required = false) sort: String?,
        @RequestParam(required = false) dir: String?,
    ): ApiResponse<List<MetaVentaDto>> {
        val resultado =
            metaVentaService.listar(
                MetaVentaFiltros(idEmpleado = idEmpleado, anio = anio, estado = estado),
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
    ): ApiResponse<MetaVentaDto> = ApiResponse.ok(metaVentaService.detalle(id, usuarioProvider.actual()))
}
