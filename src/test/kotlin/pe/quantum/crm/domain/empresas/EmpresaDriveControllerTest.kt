package pe.quantum.crm.domain.empresas

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
class EmpresaDriveControllerTest {
    private val empresaService = mockk<EmpresaService>()
    private val driveMultipartUploader = mockk<DriveMultipartUploader>()
    private val usuarioProvider = mockk<UsuarioActualProvider>()

    private val mockMvc =
        MockMvcBuilders
            .standaloneSetup(EmpresaDriveController(empresaService, driveMultipartUploader, usuarioProvider))
            .setControllerAdvice(GlobalExceptionHandler())
            .build()

    init {
        every { usuarioProvider.actual() } returns UsuarioActual(id = 3, rol = "vendedor")
    }

    @Test
    fun `GET archivos devuelve los documentos de la empresa`() {
        every { empresaService.archivosDrive(10, any()) } returns listOf(archivo("archivo-1", "ficha-ruc.pdf"))

        mockMvc
            .perform(get("/api/v1/empresas/10/archivos"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data[0].id").value("archivo-1"))
            .andExpect(jsonPath("$.data[0].nombre").value("ficha-ruc.pdf"))
    }

    @Test
    fun `GET archivos de una empresa sin carpeta devuelve lista vacia`() {
        every { empresaService.archivosDrive(10, any()) } returns emptyList()

        mockMvc
            .perform(get("/api/v1/empresas/10/archivos"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data").isArray)
            .andExpect(jsonPath("$.data").isEmpty)
    }

    @Test
    fun `GET archivos de una empresa ajena responde 404`() {
        every { empresaService.archivosDrive(10, any()) } throws NoEncontradoException("La empresa no existe")

        mockMvc
            .perform(get("/api/v1/empresas/10/archivos"))
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.error.code").value("NO_ENCONTRADO"))
    }

    @Test
    fun `POST archivos asegura la carpeta y delega la subida al uploader`() {
        every { empresaService.asegurarCarpetaDrive(10, any()) } returns "carpeta-empresa"
        every {
            driveMultipartUploader.subirPrimerArchivo(any(), "carpeta-empresa")
        } returns archivo("archivo-1", "ficha-ruc.pdf")

        mockMvc
            .perform(post("/api/v1/empresas/10/archivos").content(ByteArray(0)))
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.data.id").value("archivo-1"))

        verify { driveMultipartUploader.subirPrimerArchivo(any<MockHttpServletRequest>(), "carpeta-empresa") }
    }

    @Test
    fun `POST archivos en una empresa ajena responde 404 sin invocar al uploader`() {
        every { empresaService.asegurarCarpetaDrive(10, any()) } throws NoEncontradoException("La empresa no existe")

        mockMvc
            .perform(post("/api/v1/empresas/10/archivos").content(ByteArray(0)))
            .andExpect(status().isNotFound)

        verify(exactly = 0) { driveMultipartUploader.subirPrimerArchivo(any(), any()) }
    }

    @Test
    fun `POST carpeta-drive crea la carpeta y devuelve su id`() {
        every { empresaService.asegurarCarpetaDrive(10, any()) } returns "carpeta-nueva"

        mockMvc
            .perform(post("/api/v1/empresas/10/carpeta-drive"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.driveFolderId").value("carpeta-nueva"))
    }

    @Test
    fun `POST carpeta-drive es idempotente - si ya existe devuelve la misma`() {
        every { empresaService.asegurarCarpetaDrive(10, any()) } returns "ya-existe"

        mockMvc
            .perform(post("/api/v1/empresas/10/carpeta-drive"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.driveFolderId").value("ya-existe"))
    }

    @Test
    fun `POST carpeta-drive en una empresa ajena responde 404`() {
        every { empresaService.asegurarCarpetaDrive(10, any()) } throws NoEncontradoException("La empresa no existe")

        mockMvc
            .perform(post("/api/v1/empresas/10/carpeta-drive"))
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
