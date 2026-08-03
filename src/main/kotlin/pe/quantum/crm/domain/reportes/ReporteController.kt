package pe.quantum.crm.domain.reportes

import org.springframework.format.annotation.DateTimeFormat
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import pe.quantum.crm.domain.reportes.dto.ReporteDescuentosDto
import pe.quantum.crm.domain.reportes.dto.ReporteEquipoItemDto
import pe.quantum.crm.domain.reportes.dto.ReportePipelineDto
import pe.quantum.crm.domain.reportes.dto.ReporteProspeccionDto
import pe.quantum.crm.domain.reportes.dto.ReporteVentasDto
import pe.quantum.crm.domain.reportes.dto.VelocidadEtapaDto
import pe.quantum.crm.shared.ApiResponse
import java.time.LocalDate

/**
 * Endpoints de reportes (contrato_api.md §18). Solo admin/gerencia/jdv: los
 * vendedores no tienen acceso a reportes en el MVP.
 */
@RestController
@RequestMapping("/api/v1/reportes")
@PreAuthorize("hasAnyRole('admin', 'gerencia', 'jdv')")
class ReporteController(
    private val reporteService: ReporteService,
) {
    @GetMapping("/ventas")
    fun ventas(
        @RequestParam(name = "fecha_desde", required = false)
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) fechaDesde: LocalDate?,
        @RequestParam(name = "fecha_hasta", required = false)
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) fechaHasta: LocalDate?,
        @RequestParam(name = "id_vendedor", required = false) idVendedor: Long?,
    ): ApiResponse<ReporteVentasDto> = ApiResponse.ok(reporteService.ventas(PeriodoReporte.de(fechaDesde, fechaHasta), idVendedor))

    @GetMapping("/pipeline")
    fun pipeline(): ApiResponse<ReportePipelineDto> = ApiResponse.ok(reporteService.pipeline())

    @GetMapping("/equipo")
    fun equipo(
        @RequestParam(name = "fecha_desde", required = false)
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) fechaDesde: LocalDate?,
        @RequestParam(name = "fecha_hasta", required = false)
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) fechaHasta: LocalDate?,
    ): ApiResponse<List<ReporteEquipoItemDto>> = ApiResponse.ok(reporteService.equipo(PeriodoReporte.de(fechaDesde, fechaHasta)))

    @GetMapping("/velocidad-etapas")
    fun velocidadEtapas(): ApiResponse<List<VelocidadEtapaDto>> {
        val (etapas, advertencia) = reporteService.velocidadEtapas()
        return ApiResponse.ok(etapas, advertencia?.let { mapOf("advertencia" to it) })
    }

    @GetMapping("/prospeccion")
    fun prospeccion(
        @RequestParam(name = "fecha_desde", required = false)
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) fechaDesde: LocalDate?,
        @RequestParam(name = "fecha_hasta", required = false)
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) fechaHasta: LocalDate?,
        @RequestParam(name = "id_vendedor", required = false) idVendedor: Long?,
    ): ApiResponse<ReporteProspeccionDto> =
        ApiResponse.ok(reporteService.prospeccion(PeriodoReporte.de(fechaDesde, fechaHasta), idVendedor))

    @GetMapping("/descuentos")
    fun descuentos(
        @RequestParam(name = "fecha_desde", required = false)
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) fechaDesde: LocalDate?,
        @RequestParam(name = "fecha_hasta", required = false)
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) fechaHasta: LocalDate?,
    ): ApiResponse<ReporteDescuentosDto> = ApiResponse.ok(reporteService.descuentos(PeriodoReporte.de(fechaDesde, fechaHasta)))
}
