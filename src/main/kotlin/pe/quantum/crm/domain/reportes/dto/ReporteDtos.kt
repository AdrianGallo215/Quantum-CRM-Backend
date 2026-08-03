package pe.quantum.crm.domain.reportes.dto

/** GET /reportes/ventas (contrato_api.md §18). */
data class ReporteVentasDto(
    val montoTotal: String,
    val unidadesTotal: Int,
    val operacionesCount: Int,
    val ticketPromedio: String?,
    val dctoPromedio: String?,
    val porMes: List<VentasPorMesDto>,
    val porVendedor: List<VentasPorVendedorDto>,
    val porModelo: List<VentasPorModeloDto>,
)

data class VentasPorMesDto(
    val mes: String,
    val monto: String,
    val unidades: Int,
    val operaciones: Int,
)

data class VentasPorVendedorDto(
    val idVendedor: Long,
    val nombre: String,
    val monto: String,
    val unidades: Int,
)

data class VentasPorModeloDto(
    val modelo: String,
    val unidades: Int,
    val monto: String,
)

/** GET /reportes/pipeline. */
data class ReportePipelineDto(
    val porEtapa: List<EtapaPipelineDto>,
    val totalActivo: String,
    val concentracionCaliddaPct: String,
    val oportunidadesSinActividad: List<OportunidadSinActividadDto>,
)

data class EtapaPipelineDto(
    val etapa: String,
    val count: Int,
    val valor: String,
    val tiempoPromedioDias: Int,
    val oportunidadesSobrePromedio: Int,
)

data class OportunidadSinActividadDto(
    val id: Long,
    val empresa: String,
    val estado: String,
    val diasSinActividad: Int,
    val montoTotal: String?,
    val vendedor: String?,
)

/** GET /reportes/equipo. */
data class ReporteEquipoItemDto(
    val vendedor: VendedorRefDto,
    val oportunidadesActivas: Int,
    val valorPipeline: String,
    val oportunidadesCerradasMes: Int,
    val valorCerradoMes: String,
    val tareasCompletadasSemana: Int,
    val tareasVencidas: Int,
    val diasUltimoRegistro: Int?,
    val dctoPromedio: String?,
    val velocidadPromedioDias: Int?,
)

data class VendedorRefDto(
    val id: Long,
    val nombre: String,
)

/** GET /reportes/velocidad-etapas. */
data class VelocidadEtapaDto(
    val etapa: String,
    val diasPromedio: Int,
    val diasMediana: Int,
    val muestra: Int,
)

/** GET /reportes/prospeccion. */
data class ReporteProspeccionDto(
    val ingresadas: Int,
    val hito1Completado: Int,
    val hito2Completado: Int,
    val hito3Completado: Int,
    // SNAKE_CASE colapsa "AO" en "ao"; el contrato exige convertidas_a_oportunidad.
    @get:com.fasterxml.jackson.annotation.JsonProperty("convertidas_a_oportunidad")
    val convertidasAOportunidad: Int,
    val tasaConversionPct: String,
    val tiempoPromedioConversionDias: Int?,
    val porOrigenLead: List<ProspeccionPorOrigenDto>,
)

data class ProspeccionPorOrigenDto(
    val origen: String,
    val ingresadas: Int,
    val convertidas: Int,
    val tasaPct: String,
)

/** GET /reportes/descuentos. */
data class ReporteDescuentosDto(
    val dctoPromedioGlobal: String?,
    val porVendedor: List<DescuentosPorVendedorDto>,
)

data class DescuentosPorVendedorDto(
    val vendedor: String,
    val dctoPromedio: String?,
    val operacionesSinDcto: Int,
    val operacionesConDcto: Int,
    val dctoMaximoAplicado: String?,
)
