package pe.quantum.crm.integracion.drive

import com.google.api.client.googleapis.json.GoogleJsonError
import com.google.api.client.googleapis.json.GoogleJsonResponseException
import com.google.api.client.googleapis.media.MediaHttpUploader
import com.google.api.client.http.AbstractInputStreamContent
import com.google.api.client.http.HttpHeaders
import com.google.api.client.http.HttpResponseException
import com.google.api.services.drive.Drive
import com.google.api.services.drive.model.File
import com.google.api.services.drive.model.FileList
import io.mockk.CapturingSlot
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.IOException

/**
 * El SDK de Drive va mockeado: lo que se verifica aqui son las invariantes que
 * hacen que la integracion funcione y no sature el servidor.
 */
class DriveStorageServiceImplTest {
    private val drive = mockk<Drive>()
    private val files = mockk<Drive.Files>()
    private val propiedades =
        DriveProperties(
            credentialsBase64 = "eyJ0eXBlIjoic2VydmljZV9hY2NvdW50In0=",
            rootFolderId = RAIZ,
            uploadChunkSizeBytes = 524_288,
        )
    private val service = DriveStorageServiceImpl(drive, propiedades)

    init {
        every { drive.files() } returns files
    }

    // ── crearCarpeta ──────────────────────────────────────────

    @Test
    fun `crea la carpeta con el mimeType de Drive y devuelve su id`() {
        val metadatos = slot<File>()
        val peticion = peticionDeCarpeta(File().setId("carpeta-nueva"), metadatos)

        val id = service.crearCarpeta("20512345678 - ACME SAC", "padre-123")

        assertThat(id).isEqualTo("carpeta-nueva")
        assertThat(metadatos.captured.mimeType).isEqualTo("application/vnd.google-apps.folder")
        assertThat(metadatos.captured.name).isEqualTo("20512345678 - ACME SAC")
        assertThat(metadatos.captured.parents).containsExactly("padre-123")
        // Sin supportsAllDrives el SDK no ve la unidad compartida: es obligatorio.
        verify { peticion.setSupportsAllDrives(true) }
    }

    @Test
    fun `sin padre explicito la carpeta cuelga de la unidad compartida raiz`() {
        val metadatos = slot<File>()
        peticionDeCarpeta(File().setId("x"), metadatos)

        service.crearCarpeta("ACME")

        assertThat(metadatos.captured.parents).containsExactly(RAIZ)
    }

    @Test
    fun `un nombre con caracteres de control se sanea antes de llegar a Drive`() {
        val metadatos = slot<File>()
        peticionDeCarpeta(File().setId("x"), metadatos)

        service.crearCarpeta("  ACME\u0007SAC  ")

        assertThat(metadatos.captured.name).isEqualTo("ACMESAC")
    }

    // ── subirArchivo ──────────────────────────────────────────

    @Test
    fun `sube en streaming sin declarar la longitud del contenido`() {
        val media = slot<AbstractInputStreamContent>()
        val uploader = peticionDeArchivo(archivoSubido(), contenido = media)

        val resultado =
            service.subirArchivo(
                contenido = ByteArrayInputStream("contrato".toByteArray()),
                nombre = "contrato.pdf",
                mimeType = "application/pdf",
                parentFolderId = "carpeta-op",
            )

        // getLength() == -1 significa "longitud desconocida": el SDK usa el
        // protocolo resumable y consume el stream por chunks en vez de
        // materializar el archivo entero para medirlo.
        assertThat(media.captured.length).isEqualTo(-1L)
        assertThat(media.captured.type).isEqualTo("application/pdf")
        // Un stream de request no se puede rebobinar: el SDK no debe reintentar.
        assertThat(media.captured.retrySupported()).isFalse()
        // Resumable con chunk acotado: la RAM usada no depende del tamano del archivo.
        verify { uploader.setDirectUploadEnabled(false) }
        verify { uploader.setChunkSize(524_288) }
        assertThat(resultado.id).isEqualTo("archivo-1")
        assertThat(resultado.url).isEqualTo("https://drive.google.com/file/d/archivo-1/view")
        assertThat(resultado.tamanoBytes).isEqualTo(2048L)
    }

    @Test
    fun `sin mimeType declarado usa octet-stream`() {
        val media = slot<AbstractInputStreamContent>()
        peticionDeArchivo(archivoSubido(), contenido = media)

        service.subirArchivo(ByteArrayInputStream(ByteArray(0)), "sin-tipo", null, "carpeta-op")

        assertThat(media.captured.type).isEqualTo("application/octet-stream")
    }

    // ── listarArchivos ────────────────────────────────────────

    @Test
    fun `lista solo los archivos - excluye carpetas y elementos en la papelera via la consulta`() {
        val lista = mockk<Drive.Files.List>()
        val consulta = slot<String>()
        every { files.list() } returns lista
        every { lista.setQ(capture(consulta)) } returns lista
        every { lista.setSupportsAllDrives(any()) } returns lista
        every { lista.setIncludeItemsFromAllDrives(any()) } returns lista
        every { lista.setOrderBy(any()) } returns lista
        every { lista.setPageSize(any()) } returns lista
        every { lista.setPageToken(any()) } returns lista
        every { lista.setFields(any()) } returns lista
        every { lista.execute() } returns
            FileList().setFiles(listOf(archivoSubido())).setNextPageToken(null)

        val resultado = service.listarArchivos("carpeta-op")

        assertThat(resultado).hasSize(1)
        assertThat(resultado[0].id).isEqualTo("archivo-1")
        assertThat(consulta.captured).contains("'carpeta-op' in parents")
        assertThat(consulta.captured).contains("mimeType != 'application/vnd.google-apps.folder'")
        assertThat(consulta.captured).contains("trashed = false")
        verify { lista.setSupportsAllDrives(true) }
        verify { lista.setIncludeItemsFromAllDrives(true) }
    }

    @Test
    fun `recorre todas las paginas hasta que nextPageToken es null`() {
        val lista = mockk<Drive.Files.List>()
        every { files.list() } returns lista
        every { lista.setQ(any()) } returns lista
        every { lista.setSupportsAllDrives(any()) } returns lista
        every { lista.setIncludeItemsFromAllDrives(any()) } returns lista
        every { lista.setOrderBy(any()) } returns lista
        every { lista.setPageSize(any()) } returns lista
        every { lista.setFields(any()) } returns lista
        every { lista.setPageToken(null) } returns lista
        every { lista.setPageToken("pagina-2") } returns lista
        every { lista.execute() } returnsMany
            listOf(
                FileList().setFiles(listOf(File().setId("a").setName("a.pdf"))).setNextPageToken("pagina-2"),
                FileList().setFiles(listOf(File().setId("b").setName("b.pdf"))).setNextPageToken(null),
            )

        val resultado = service.listarArchivos("carpeta-op")

        assertThat(resultado.map { it.id }).containsExactly("a", "b")
        verify { lista.setPageToken("pagina-2") }
    }

    @Test
    fun `una carpeta vacia devuelve una lista vacia`() {
        val lista = mockk<Drive.Files.List>()
        every { files.list() } returns lista
        every { lista.setQ(any()) } returns lista
        every { lista.setSupportsAllDrives(any()) } returns lista
        every { lista.setIncludeItemsFromAllDrives(any()) } returns lista
        every { lista.setOrderBy(any()) } returns lista
        every { lista.setPageSize(any()) } returns lista
        every { lista.setPageToken(any()) } returns lista
        every { lista.setFields(any()) } returns lista
        every { lista.execute() } returns FileList().setFiles(emptyList())

        assertThat(service.listarArchivos("carpeta-op")).isEmpty()
    }

    // ── errores ───────────────────────────────────────────────

    @Test
    fun `un fallo de red se traduce a DriveException y no a un 500 generico`() {
        val peticion = mockk<Drive.Files.Create>()
        every { files.create(any()) } returns peticion
        every { peticion.setSupportsAllDrives(any()) } returns peticion
        every { peticion.setFields(any()) } returns peticion
        every { peticion.execute() } throws IOException("connection reset")

        assertThatThrownBy { service.crearCarpeta("ACME") }
            .isInstanceOf(DriveException::class.java)
            .hasMessageContaining("crear la carpeta")
    }

    @Test
    fun `storageQuotaExceeded se traduce a un error que senala la unidad compartida`() {
        val peticion = mockk<Drive.Files.Create>()
        every { files.create(any()) } returns peticion
        every { peticion.setSupportsAllDrives(any()) } returns peticion
        every { peticion.setFields(any()) } returns peticion
        every { peticion.execute() } throws errorDeCuota()

        assertThatThrownBy { service.crearCarpeta("ACME") }
            .isInstanceOf(DriveSinCuotaException::class.java)
            .hasMessageContaining("unidad compartida")
    }

    // ── helpers ───────────────────────────────────────────────

    private fun peticionDeCarpeta(
        respuesta: File,
        metadatos: CapturingSlot<File>,
    ): Drive.Files.Create {
        val peticion = mockk<Drive.Files.Create>()
        every { files.create(capture(metadatos)) } returns peticion
        every { peticion.setSupportsAllDrives(any()) } returns peticion
        every { peticion.setFields(any()) } returns peticion
        every { peticion.execute() } returns respuesta
        return peticion
    }

    private fun peticionDeArchivo(
        respuesta: File,
        contenido: CapturingSlot<AbstractInputStreamContent>,
    ): MediaHttpUploader {
        val peticion = mockk<Drive.Files.Create>()
        val uploader = mockk<MediaHttpUploader>()
        every { files.create(any(), capture(contenido)) } returns peticion
        every { peticion.setSupportsAllDrives(any()) } returns peticion
        every { peticion.setFields(any()) } returns peticion
        every { peticion.mediaHttpUploader } returns uploader
        every { uploader.setDirectUploadEnabled(any()) } returns uploader
        every { uploader.setChunkSize(any()) } returns uploader
        every { peticion.execute() } returns respuesta
        return uploader
    }

    private fun archivoSubido(): File =
        File()
            .setId("archivo-1")
            .setName("contrato.pdf")
            .setMimeType("application/pdf")
            .setWebViewLink("https://drive.google.com/file/d/archivo-1/view")
            .setSize(2048L)

    private fun errorDeCuota(): GoogleJsonResponseException {
        val detalle =
            GoogleJsonError().apply {
                errors = listOf(GoogleJsonError.ErrorInfo().apply { reason = "storageQuotaExceeded" })
            }
        return GoogleJsonResponseException(
            HttpResponseException.Builder(403, "Forbidden", HttpHeaders()),
            detalle,
        )
    }

    private companion object {
        const val RAIZ = "0AGVp12fg_LWEUk9PVA"
    }
}
