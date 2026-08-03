package pe.quantum.crm.importcsvtemp.dto

/** Resultado de procesar una fila del CSV de importación de empresas. */
data class ImportEmpresaFilaResultado(
    val fila: Int,
    val ruc: String?,
    val razonSocial: String?,
    val estado: String,
    val motivo: String?,
)

/** Resultado agregado de `POST /import-csv-temp/empresas`. */
data class ImportEmpresasResultDto(
    val totalFilas: Int,
    val creadas: Int,
    val conError: Int,
    val detalle: List<ImportEmpresaFilaResultado>,
)
