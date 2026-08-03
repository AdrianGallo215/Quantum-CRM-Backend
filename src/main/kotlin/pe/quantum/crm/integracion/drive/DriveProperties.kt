package pe.quantum.crm.integracion.drive

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Configuracion de la integracion con Google Drive (`app.drive.*`).
 *
 * Las credenciales NUNCA se leen de un archivo en disco en runtime: el JSON del
 * Service Account viaja entero codificado en base64 por variable de entorno
 * (CLAUDE.md regla 13).
 */
@ConfigurationProperties(prefix = "app.drive")
data class DriveProperties(
    /** JSON del Service Account en base64, desde `GOOGLE_DRIVE_CREDENTIALS_BASE64`. */
    val credentialsBase64: String,
    /**
     * ID de la unidad compartida raiz. DEBE ser una unidad compartida (empieza en
     * `0A`), no una carpeta de "Mi unidad": las cuentas de servicio no tienen cuota
     * de almacenamiento y no pueden ser duenas de archivos, asi que subir a Mi
     * unidad falla con 403 storageQuotaExceeded.
     */
    val rootFolderId: String,
    /**
     * Tamano de chunk del upload resumable. Es lo que acota la RAM por subida en
     * curso: el servidor nunca retiene el archivo completo. Debe ser multiplo de
     * 256 KB (lo exige el protocolo resumable de Google).
     */
    val uploadChunkSizeBytes: Int = DEFAULT_CHUNK_SIZE_BYTES,
    /** Tope de tamano por archivo aceptado en el endpoint de subida. */
    val maxFileSizeBytes: Long = DEFAULT_MAX_FILE_SIZE_BYTES,
    /** Acotan cuanto puede una llamada a Drive retener la transaccion abierta. */
    val connectTimeoutMs: Int = DEFAULT_CONNECT_TIMEOUT_MS,
    val readTimeoutMs: Int = DEFAULT_READ_TIMEOUT_MS,
    val applicationName: String = "Quantum CRM",
) {
    init {
        require(uploadChunkSizeBytes % CHUNK_MULTIPLE_BYTES == 0 && uploadChunkSizeBytes > 0) {
            "app.drive.upload-chunk-size-bytes debe ser un multiplo positivo de $CHUNK_MULTIPLE_BYTES (256 KB)"
        }
    }

    companion object {
        const val CHUNK_MULTIPLE_BYTES = 262_144
        const val DEFAULT_CHUNK_SIZE_BYTES = 5_242_880
        const val DEFAULT_MAX_FILE_SIZE_BYTES = 104_857_600L
        const val DEFAULT_CONNECT_TIMEOUT_MS = 10_000
        const val DEFAULT_READ_TIMEOUT_MS = 120_000
    }
}
