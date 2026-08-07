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
    /**
     * Empresas creadas que aun no tienen carpeta de Google Drive. El import no
     * llama a Drive por fila (tardaria minutos y el proxy cortaria la respuesta):
     * las carpetas se crean despues con `POST /mantenimiento/carpetas-drive`, que
     * es idempotente y reanudable. Default para no romper llamadores existentes.
     */
    val carpetasDrivePendientes: Int = 0,
)
