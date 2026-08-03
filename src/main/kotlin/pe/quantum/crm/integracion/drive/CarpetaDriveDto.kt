package pe.quantum.crm.integracion.drive

/** Respuesta de los endpoints `POST .../carpeta-drive` (contrato_api.md §8, §10). */
data class CarpetaDriveDto(
    val driveFolderId: String,
)
