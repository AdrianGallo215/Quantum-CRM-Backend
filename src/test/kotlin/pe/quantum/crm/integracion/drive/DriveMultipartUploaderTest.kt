package pe.quantum.crm.integracion.drive

import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockHttpServletRequest
import pe.quantum.crm.shared.exception.ValidacionException
import java.io.InputStream

/**
 * Prueba el parseo multipart real (sin mockear Commons FileUpload): un
 * `MockHttpServletRequest` con un cuerpo crudo, tal como llega desde el cliente.
 */
class DriveMultipartUploaderTest {
    private val driveStorageService = mockk<DriveStorageService>()
    private val propiedades =
        DriveProperties(
            credentialsBase64 = "eyJ0eXBlIjoic2VydmljZV9hY2NvdW50In0=",
            rootFolderId = "0ATestSharedDriveId",
            maxFileSizeBytes = 20,
        )
    private val uploader = DriveMultipartUploader(driveStorageService, propiedades)

    @Test
    fun `sube el campo file y devuelve el resultado de Drive`() {
        val contenido = slot<InputStream>()
        every {
            driveStorageService.subirArchivo(capture(contenido), "contrato.pdf", "application/pdf", "carpeta-1")
        } answers {
            DriveArchivoSubido(
                id = "archivo-1",
                nombre = "contrato.pdf",
                url = "https://drive.google.com/file/d/archivo-1/view",
                tamanoBytes = contenido.captured.readBytes().size.toLong(),
                mimeType = "application/pdf",
            )
        }

        val resultado = uploader.subirPrimerArchivo(multipart("contrato.pdf", "application/pdf", "hola"), "carpeta-1")

        assertThat(resultado.id).isEqualTo("archivo-1")
        assertThat(resultado.tamanoBytes).isEqualTo(4)
    }

    @Test
    fun `un request que no es multipart lanza VALIDACION`() {
        val request = MockHttpServletRequest().apply { contentType = "application/json" }

        assertThatThrownBy { uploader.subirPrimerArchivo(request, "carpeta-1") }
            .isInstanceOf(ValidacionException::class.java)
    }

    @Test
    fun `un cuerpo sin el campo file lanza VALIDACION`() {
        assertThatThrownBy {
            uploader.subirPrimerArchivo(multipart("x.pdf", "application/pdf", "hola", campo = "otro"), "carpeta-1")
        }.isInstanceOf(ValidacionException::class.java)
    }

    @Test
    fun `un multipart malformado lanza VALIDACION en vez de propagar la excepcion de FileUpload`() {
        val request =
            MockHttpServletRequest().apply {
                method = "POST"
                contentType = "multipart/form-data; boundary=$FRONTERA"
                setContent("esto no respeta el formato multipart".toByteArray())
            }

        assertThatThrownBy { uploader.subirPrimerArchivo(request, "carpeta-1") }
            .isInstanceOf(ValidacionException::class.java)
    }

    @Test
    fun `un archivo que supera el tope corta el stream antes de completar la subida`() {
        every { driveStorageService.subirArchivo(any(), any(), any(), any()) } answers {
            firstArg<InputStream>().readBytes()
            error("no deberia completarse: StreamAcotado debio cortar antes")
        }

        assertThatThrownBy {
            uploader.subirPrimerArchivo(multipart("grande.bin", "application/octet-stream", "x".repeat(100)), "carpeta-1")
        }.isInstanceOf(ArchivoDemasiadoGrandeException::class.java)
    }

    @Test
    fun `un archivo de exactamente maxFileSizeBytes pasa aunque el cuerpo HTTP completo sea mas grande por el framing multipart`() {
        val contenido = "x".repeat(50)
        val tamanoArchivo = contenido.toByteArray().size.toLong()
        // Limite igual al archivo, byte a byte: si el framing (boundary, headers,
        // CRLFs) contara, esto rebotaria con ARCHIVO_DEMASIADO_GRANDE.
        val uploaderExacto = DriveMultipartUploader(driveStorageService, propiedades.copy(maxFileSizeBytes = tamanoArchivo))
        every { driveStorageService.subirArchivo(any(), any(), any(), any()) } answers {
            DriveArchivoSubido(
                id = "archivo-1",
                nombre = "justo.txt",
                url = null,
                tamanoBytes = firstArg<InputStream>().readBytes().size.toLong(),
                mimeType = "text/plain",
            )
        }
        val request = multipart("justo.txt", "text/plain", contenido)
        // El cuerpo HTTP crudo es mas grande que el archivo por el framing multipart.
        assertThat(request.contentAsByteArray!!.size.toLong()).isGreaterThan(tamanoArchivo)

        val resultado = uploaderExacto.subirPrimerArchivo(request, "carpeta-1")

        // Commons FileUpload ya entrega el stream sin el framing: StreamAcotado
        // solo ve los bytes reales del archivo, nunca la sobrecarga del multipart.
        assertThat(resultado.tamanoBytes).isEqualTo(tamanoArchivo)
    }

    private fun multipart(
        nombreArchivo: String,
        mimeType: String,
        contenido: String,
        campo: String = "file",
    ): MockHttpServletRequest {
        val cuerpo =
            buildString {
                append("--$FRONTERA\r\n")
                append("Content-Disposition: form-data; name=\"$campo\"; filename=\"$nombreArchivo\"\r\n")
                append("Content-Type: $mimeType\r\n\r\n")
                append(contenido)
                append("\r\n--$FRONTERA--\r\n")
            }.toByteArray(Charsets.UTF_8)
        return MockHttpServletRequest().apply {
            method = "POST"
            contentType = "multipart/form-data; boundary=$FRONTERA"
            setContent(cuerpo)
        }
    }

    private companion object {
        const val FRONTERA = "QuantumBoundary"
    }
}
