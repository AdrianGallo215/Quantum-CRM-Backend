package pe.quantum.crm.mantenimiento.dto

/** Resultado del backfill de carpetas de Drive (contrato_api.md §22). */
data class BackfillCarpetasDto(
    val empresasProcesadas: Int,
    val oportunidadesProcesadas: Int,
    val errores: List<ErrorBackfillDto>,
    val pendientesRestantes: Int,
)

/** Un registro que no pudo procesarse; el resto del lote continuo igualmente. */
data class ErrorBackfillDto(
    val entidad: String,
    val id: Long,
    val motivo: String,
)
