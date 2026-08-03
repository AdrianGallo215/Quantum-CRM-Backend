package pe.quantum.crm.integracion.drive

import org.springframework.http.HttpStatus
import pe.quantum.crm.shared.exception.ApiException

/**
 * Drive no pudo completar la operacion. Es 502 y no 500: el fallo es de un
 * sistema aguas abajo, no del CRM.
 */
class DriveException(
    message: String,
    causa: Throwable? = null,
) : ApiException(code = "DRIVE_NO_DISPONIBLE", message = message, status = HttpStatus.BAD_GATEWAY) {
    init {
        causa?.let { initCause(it) }
    }
}

/**
 * Caso especial de configuracion, no de disponibilidad: Drive respondio
 * `storageQuotaExceeded`. Casi siempre significa que ROOT_DRIVE_FOLDER_ID apunta a
 * una carpeta de "Mi unidad" en vez de a una unidad compartida. El mensaje lo dice
 * explicito porque el sintoma es enganoso: crear carpetas funciona (pesan 0 bytes)
 * y solo falla la subida.
 */
class DriveSinCuotaException(
    causa: Throwable? = null,
) : ApiException(
        code = "DRIVE_SIN_CUOTA",
        message =
            "Google Drive rechazo la subida por cuota. La cuenta de servicio no tiene almacenamiento " +
                "propio: ROOT_DRIVE_FOLDER_ID debe apuntar a una unidad compartida (empieza en 0A), " +
                "no a una carpeta de Mi unidad.",
        status = HttpStatus.BAD_GATEWAY,
    ) {
    init {
        causa?.let { initCause(it) }
    }
}

/** El archivo supera `app.drive.max-file-size-bytes`. */
class ArchivoDemasiadoGrandeException(
    maxBytes: Long,
) : ApiException(
        code = "ARCHIVO_DEMASIADO_GRANDE",
        message = "El archivo supera el maximo permitido de $maxBytes bytes",
        status = HttpStatus.PAYLOAD_TOO_LARGE,
        field = "file",
    )
