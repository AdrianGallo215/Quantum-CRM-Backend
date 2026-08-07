package pe.quantum.crm.integracion.drive

import com.google.api.client.googleapis.json.GoogleJsonResponseException
import com.google.api.client.http.InputStreamContent
import com.google.api.services.drive.Drive
import com.google.api.services.drive.model.File
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Service
import java.io.IOException
import java.io.InputStream

/**
 * Implementacion contra el SDK oficial de Google Drive.
 *
 * Dos invariantes que no se pueden relajar:
 *
 * 1. `supportsAllDrives = true` en TODA llamada. Sin ese flag el SDK opera solo
 *    sobre "Mi unidad" y ni siquiera ve la unidad compartida.
 * 2. El [InputStreamContent] se crea SIN `setLength()`. Con longitud desconocida el
 *    SDK usa el protocolo resumable y consume el stream por chunks, de modo que la
 *    memoria usada es el chunk configurado y no el tamano del archivo.
 */
@Service
class DriveStorageServiceImpl(
    private val drive: Drive,
    /**
     * Cliente con el timeout de lectura corto (ver [DriveConfig]). Solo para crear
     * carpetas: es la unica llamada a Drive que puede quedar dentro de una
     * transaccion, asi que no puede heredar el timeout de las subidas.
     */
    @Qualifier("googleDriveCarpetas") private val driveCarpetas: Drive,
    private val propiedades: DriveProperties,
) : DriveStorageService {
    private val log = LoggerFactory.getLogger(DriveStorageServiceImpl::class.java)

    override val carpetaRaizId: String get() = propiedades.rootFolderId

    override fun crearCarpeta(
        nombre: String,
        parentFolderId: String?,
    ): String {
        val padre = parentFolderId ?: propiedades.rootFolderId
        val metadatos =
            File().apply {
                name = sanitizarNombre(nombre)
                mimeType = MIME_CARPETA
                parents = listOf(padre)
            }
        val creada =
            ejecutar("crear la carpeta '$nombre'") {
                driveCarpetas
                    .files()
                    .create(metadatos)
                    .setSupportsAllDrives(true)
                    .setFields("id")
                    .execute()
            }
        log.info("Carpeta creada en Drive: id={} nombre='{}' padre={}", creada.id, nombre, padre)
        return creada.id
    }

    override fun subirArchivo(
        contenido: InputStream,
        nombre: String,
        mimeType: String?,
        parentFolderId: String,
    ): DriveArchivoSubido {
        val metadatos =
            File().apply {
                name = sanitizarNombre(nombre)
                parents = listOf(parentFolderId)
            }
        // Sin setLength(): longitud desconocida => upload resumable por chunks.
        // setRetrySupported(false): un InputStream de un request no se puede rebobinar,
        // asi que el SDK no debe intentar reenviar el cuerpo desde cero.
        val media =
            InputStreamContent(mimeType ?: MIME_BINARIO, contenido)
                .setRetrySupported(false)

        val subido =
            ejecutar("subir el archivo '$nombre'") {
                val peticion =
                    drive
                        .files()
                        .create(metadatos, media)
                        .setSupportsAllDrives(true)
                        .setFields("id, name, size, mimeType, webViewLink")
                peticion.mediaHttpUploader
                    .setDirectUploadEnabled(false)
                    .setChunkSize(propiedades.uploadChunkSizeBytes)
                peticion.execute()
            }
        log.info(
            "Archivo subido a Drive: id={} nombre='{}' carpeta={} bytes={}",
            subido.id,
            subido.name,
            parentFolderId,
            subido.getSize(),
        )
        return DriveArchivoSubido(
            id = subido.id,
            nombre = subido.name,
            url = subido.webViewLink,
            tamanoBytes = subido.getSize(),
            mimeType = subido.mimeType,
        )
    }

    override fun listarArchivos(parentFolderId: String): List<DriveArchivoSubido> {
        val encontrados = mutableListOf<File>()
        var paginaSiguiente: String? = null
        do {
            val pagina =
                ejecutar("listar los archivos de la carpeta '$parentFolderId'") {
                    drive
                        .files()
                        .list()
                        .setQ(consultaHijosNoCarpeta(parentFolderId))
                        .setSupportsAllDrives(true)
                        .setIncludeItemsFromAllDrives(true)
                        .setOrderBy("name")
                        .setPageSize(PAGE_SIZE)
                        .setPageToken(paginaSiguiente)
                        .setFields("nextPageToken, files(id, name, size, mimeType, webViewLink)")
                        .execute()
                }
            encontrados += pagina.files.orEmpty()
            paginaSiguiente = pagina.nextPageToken
        } while (paginaSiguiente != null)
        return encontrados.map {
            DriveArchivoSubido(
                id = it.id,
                nombre = it.name,
                url = it.webViewLink,
                tamanoBytes = it.getSize(),
                mimeType = it.mimeType,
            )
        }
    }

    /** Escapa comillas simples: sintaxis de consulta de Drive, no SQL, pero mismo riesgo de inyeccion. */
    private fun consultaHijosNoCarpeta(parentFolderId: String): String {
        val padreEscapado = parentFolderId.replace("'", "\\'")
        return "'$padreEscapado' in parents and mimeType != '$MIME_CARPETA' and trashed = false"
    }

    /**
     * Traduce los fallos del SDK al envelope de error del CRM. Distingue el caso de
     * cuota (configuracion incorrecta, accionable) del resto (indisponibilidad).
     */
    private fun <T> ejecutar(
        accion: String,
        bloque: () -> T,
    ): T =
        try {
            bloque()
        } catch (ex: GoogleJsonResponseException) {
            val razones = ex.details?.errors?.mapNotNull { it.reason }.orEmpty()
            if (RAZON_SIN_CUOTA in razones) {
                log.error("Drive rechazo por cuota al {}: revisar ROOT_DRIVE_FOLDER_ID", accion, ex)
                throw DriveSinCuotaException(ex)
            }
            log.error("Drive respondio {} al {}: {}", ex.statusCode, accion, razones, ex)
            throw DriveException("Google Drive no pudo $accion", ex)
        } catch (ex: IOException) {
            log.error("Fallo de red al {} en Drive", accion, ex)
            throw DriveException("Google Drive no pudo $accion", ex)
        }

    /**
     * Drive rechaza los caracteres de control y trunca nombres muy largos. Las
     * barras no se escapan: Drive las admite en el nombre y no implican jerarquia.
     */
    private fun sanitizarNombre(nombre: String): String {
        val limpio = nombre.filter { !it.isISOControl() }.trim()
        val seguro = limpio.ifBlank { "sin-nombre" }
        return seguro.take(MAX_NOMBRE)
    }

    private companion object {
        const val MIME_CARPETA = "application/vnd.google-apps.folder"
        const val MIME_BINARIO = "application/octet-stream"
        const val RAZON_SIN_CUOTA = "storageQuotaExceeded"
        const val MAX_NOMBRE = 255
        const val PAGE_SIZE = 200
    }
}
