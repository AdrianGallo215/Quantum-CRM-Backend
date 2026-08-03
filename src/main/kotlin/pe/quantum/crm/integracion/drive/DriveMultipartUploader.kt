package pe.quantum.crm.integracion.drive

import jakarta.servlet.http.HttpServletRequest
import org.apache.commons.fileupload2.core.FileUploadException
import org.apache.commons.fileupload2.jakarta.servlet6.JakartaServletFileUpload
import org.springframework.stereotype.Component
import pe.quantum.crm.shared.exception.ValidacionException

/**
 * Toma un request `multipart/form-data` y sube su campo `file` a Drive, todo en
 * streaming: el archivo NUNCA se materializa en el servidor.
 *
 * Compartido por los controllers de archivos de empresa y de oportunidad — ambos
 * necesitan exactamente este mismo parseo, solo cambia la carpeta destino.
 *
 * Requiere que la ruta que lo usa este excluida del resolver de multipart de
 * Spring (ver [DriveUploadMultipartResolver]): si Spring resuelve el multipart
 * antes, Tomcat ya volco el archivo a un temporal en disco.
 */
@Component
class DriveMultipartUploader(
    private val driveStorageService: DriveStorageService,
    private val propiedades: DriveProperties,
) {
    fun subirPrimerArchivo(
        request: HttpServletRequest,
        carpeta: String,
    ): DriveArchivoSubido {
        if (!JakartaServletFileUpload.isMultipartContent(request)) {
            throw ValidacionException("La peticion debe ser multipart/form-data", field = CAMPO_ARCHIVO)
        }
        return try {
            recorrerMultipart(request, carpeta)
        } catch (ex: FileUploadException) {
            throw ValidacionException("El cuerpo multipart es invalido: ${ex.message}", field = CAMPO_ARCHIVO)
                .also { it.initCause(ex) }
        }
    }

    /**
     * Toma la primera parte que sea el campo `file` y la sube. El tope de tamano lo
     * aplica [StreamAcotado] mientras Drive consume el stream: no hay un momento en
     * que el archivo completo exista en el servidor.
     */
    private fun recorrerMultipart(
        request: HttpServletRequest,
        carpeta: String,
    ): DriveArchivoSubido {
        val partes = JakartaServletFileUpload().getItemIterator(request)
        while (partes.hasNext()) {
            val parte = partes.next()
            if (parte.isFormField || parte.fieldName != CAMPO_ARCHIVO) {
                continue
            }
            val nombre =
                parte.name?.takeIf { it.isNotBlank() }
                    ?: throw ValidacionException("El archivo no tiene nombre", field = CAMPO_ARCHIVO)
            return parte.inputStream.use { contenido ->
                driveStorageService.subirArchivo(
                    contenido = StreamAcotado(contenido, propiedades.maxFileSizeBytes),
                    nombre = nombre,
                    mimeType = parte.contentType,
                    parentFolderId = carpeta,
                )
            }
        }
        throw ValidacionException("No se recibio ningun archivo en el campo '$CAMPO_ARCHIVO'", field = CAMPO_ARCHIVO)
    }

    private companion object {
        const val CAMPO_ARCHIVO = "file"
    }
}
