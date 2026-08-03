package pe.quantum.crm.mantenimiento

import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import pe.quantum.crm.mantenimiento.dto.BackfillCarpetasDto
import pe.quantum.crm.shared.ApiResponse

/**
 * Operacion administrativa: crea las carpetas de Drive que faltan en registros
 * anteriores a la integracion (contrato_api.md §22).
 *
 * Idempotente y re-ejecutable: si no hay pendientes responde todo en cero sin
 * tocar Drive.
 */
@RestController
@RequestMapping("/api/v1/mantenimiento/carpetas-drive")
class CarpetasDriveBackfillController(
    private val backfillService: CarpetasDriveBackfillService,
) {
    @PostMapping
    @PreAuthorize("hasRole('admin')")
    fun crearCarpetasFaltantes(
        @RequestParam(name = "tamano_lote", required = false) tamanoLote: Int?,
    ): ApiResponse<BackfillCarpetasDto> = ApiResponse.ok(backfillService.ejecutar(tamanoLote))
}
