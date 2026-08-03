package pe.quantum.crm.integracion.drive

import java.io.InputStream

/**
 * Almacenamiento headless de documentos en Google Drive.
 *
 * El CRM no guarda archivos: solo el ID de la carpeta donde viven. Esta es la
 * unica puerta de entrada a Drive; los modulos de dominio dependen de esta
 * interfaz, nunca del SDK (CLAUDE.md regla 12).
 */
interface DriveStorageService {
    /** ID de la unidad compartida raiz, padre por defecto de las carpetas de empresa. */
    val carpetaRaizId: String

    /**
     * Crea una carpeta y devuelve su ID.
     *
     * @param parentFolderId padre; si es null, cuelga de [carpetaRaizId].
     */
    fun crearCarpeta(
        nombre: String,
        parentFolderId: String? = null,
    ): String

    /**
     * Sube un archivo leyendo [contenido] en streaming.
     *
     * El stream se consume por chunks contra el endpoint resumable de Drive: el
     * servidor nunca retiene el archivo completo en RAM ni lo vuelca a disco. El
     * llamador es dueno de [contenido] y de cerrarlo.
     */
    fun subirArchivo(
        contenido: InputStream,
        nombre: String,
        mimeType: String?,
        parentFolderId: String,
    ): DriveArchivoSubido

    /**
     * Documentos directos de una carpeta (no recursivo, sin subcarpetas ni
     * elementos en la papelera). Orden alfabetico por nombre.
     */
    fun listarArchivos(parentFolderId: String): List<DriveArchivoSubido>
}

/** Documento de Drive, en una subida o en un listado (contrato_api.md §8, §10, §14). */
data class DriveArchivoSubido(
    val id: String,
    val nombre: String,
    val url: String?,
    val tamanoBytes: Long?,
    val mimeType: String?,
)
