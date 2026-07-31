package pe.quantum.crm.domain.oportunidades

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import pe.quantum.crm.integracion.drive.DriveArchivoSubido
import pe.quantum.crm.integracion.drive.DriveMultipartUploader
import pe.quantum.crm.shared.GlobalExceptionHandler
import pe.quantum.crm.shared.exception.NoEncontradoException
import pe.quantum.crm.shared.security.UsuarioActual
import pe.quantum.crm.shared.security.UsuarioActualProvider

/**
 * El parseo multipart real lo cubre `DriveMultipartUploaderTest`; aqui se prueba
 * el enrutamiento del controller: visibilidad, delegacion al servicio correcto y
 * los codigos de estado del contrato.
 */
class OportunidadDriveControllerTest {
    private val oportunidadService = mockk<OportunidadService>()
    private val driveMultipartUploader = mockk<DriveMultipartUploader>()
    private val usuarioProvider = mockk<UsuarioActualProvider>()

    private val mockMvc =
        MockMvcBuilders
            .standaloneSetup(OportunidadDriveController(oportunidadService, driveMultipartUploader, usuarioProvider))
            .setControllerAdvice(GlobalExceptionHandler())
            .build()

    init {
        every { usuarioProvider.actual() } returns UsuarioActual(id = 3, rol = "vendedor")
    }

    @Test
    fun `GET archivos devuelve los documentos de la oportunidad`() {
        every { oportunidadService.archivosDrive(100, any()) } returns
            listOf(archivo("archivo-1", "contrato.pdf"))

        mockMvc
            .perform(get("/api/v1/oportunidades/100/archivos"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data[0].id").value("archivo-1"))
            .andExpect(jsonPath("$.data[0].nombre").value("contrato.pdf"))
    }

    @Test
    fun `GET archivos de una oportunidad sin carpeta devuelve lista vacia`() {
        every { oportunidadService.archivosDrive(100, any()) } returns emptyList()

        mockMvc
            .perform(get("/api/v1/oportunidades/100/archivos"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data").isArray)
            .andExpect(jsonPath("$.data").isEmpty)
    }

    @Test
    fun `GET archivos de una oportunidad ajena responde 404`() {
        every { oportunidadService.archivosDrive(100, any()) } throws NoEncontradoException("La oportunidad no existe")

        mockMvc
            .perform(get("/api/v1/oportunidades/100/archivos"))
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.error.code").value("NO_ENCONTRADO"))
    }

    @Test
    fun `POST archivos asegura la carpeta y delega la subida al uploader`() {
        every { oportunidadService.asegurarCarpetaDrive(100, any()) } returns "carpeta-op"
        every { driveMultipartUploader.subirPrimerArchivo(any(), "carpeta-op") } returns archivo("archivo-1", "contrato.pdf")

        mockMvc
            .perform(post("/api/v1/oportunidades/100/archivos").content(ByteArray(0)))
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.data.id").value("archivo-1"))

        verify { driveMultipartUploader.subirPrimerArchivo(any<MockHttpServletRequest>(), "carpeta-op") }
    }

    @Test
    fun `POST archivos en una oportunidad ajena responde 404 sin invocar al uploader`() {
        every { oportunidadService.asegurarCarpetaDrive(100, any()) } throws NoEncontradoException("La oportunidad no existe")

        mockMvc
            .perform(post("/api/v1/oportunidades/100/archivos").content(ByteArray(0)))
            .andExpect(status().isNotFound)

        verify(exactly = 0) { driveMultipartUploader.subirPrimerArchivo(any(), any()) }
    }

    @Test
    fun `POST carpeta-drive crea la carpeta y devuelve su id`() {
        every { oportunidadService.asegurarCarpetaDrive(100, any()) } returns "carpeta-nueva"

        mockMvc
            .perform(post("/api/v1/oportunidades/100/carpeta-drive"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.driveFolderId").value("carpeta-nueva"))
    }

    @Test
    fun `POST carpeta-drive es idempotente - si ya existe devuelve la misma`() {
        every { oportunidadService.asegurarCarpetaDrive(100, any()) } returns "ya-existe"

        mockMvc
            .perform(post("/api/v1/oportunidades/100/carpeta-drive"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.driveFolderId").value("ya-existe"))
    }

    @Test
    fun `POST carpeta-drive en una oportunidad ajena responde 404`() {
        every { oportunidadService.asegurarCarpetaDrive(100, any()) } throws NoEncontradoException("La oportunidad no existe")

        mockMvc
            .perform(post("/api/v1/oportunidades/100/carpeta-drive"))
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.error.code").value("NO_ENCONTRADO"))
    }

    private fun archivo(
        id: String,
        nombre: String,
    ) = DriveArchivoSubido(
        id = id,
        nombre = nombre,
        url = "https://drive.google.com/file/d/$id/view",
        tamanoBytes = 1024,
        mimeType = "application/pdf",
    )
}
