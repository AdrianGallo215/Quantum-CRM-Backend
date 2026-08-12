package pe.quantum.crm.domain.empresas

import com.fasterxml.jackson.databind.ObjectMapper
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.test.web.servlet.MockMvc
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
import pe.quantum.crm.support.SinBaseDeDatosMocks

/**
 * El parseo multipart real lo cubre `DriveMultipartUploaderTest`; aqui se prueba
 * el enrutamiento del controller: visibilidad, delegacion al servicio correcto y
 * los codigos de estado del contrato.
 *
 * `standaloneSetup` por defecto NO carga el `ObjectMapper` de la app: serializaria
 * `driveFolderId` en camelCase mientras el contrato exige `drive_folder_id`, y el
 * test pasaria igual aunque la serializacion real estuviera rota. Por eso se le
 * registra el `MappingJackson2HttpMessageConverter` con el `ObjectMapper` real
 * (contexto Spring, `spring.jackson.property-naming-strategy=SNAKE_CASE`).
 */
@SpringBootTest(
    properties = [
        "spring.autoconfigure.exclude=" +
            "org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration," +
            "org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration," +
            "org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration",
    ],
)
@Import(SinBaseDeDatosMocks::class)
class EmpresaDriveControllerTest {
    private val empresaService = mockk<EmpresaService>()
    private val driveMultipartUploader = mockk<DriveMultipartUploader>()
    private val usuarioProvider = mockk<UsuarioActualProvider>()

    @Autowired
    lateinit var objectMapper: ObjectMapper

    private lateinit var mockMvc: MockMvc

    @BeforeEach
    fun setUp() {
        every { usuarioProvider.actual() } returns UsuarioActual(id = 3, rol = "vendedor")
        mockMvc =
            MockMvcBuilders
                .standaloneSetup(EmpresaDriveController(empresaService, driveMultipartUploader, usuarioProvider))
                .setControllerAdvice(GlobalExceptionHandler())
                .setMessageConverters(MappingJackson2HttpMessageConverter(objectMapper))
                .build()
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
            .andExpect(jsonPath("$.data.drive_folder_id").value("carpeta-nueva"))
    }

    @Test
    fun `POST carpeta-drive es idempotente - si ya existe devuelve la misma`() {
        every { empresaService.asegurarCarpetaDrive(10, any()) } returns "ya-existe"

        mockMvc
            .perform(post("/api/v1/empresas/10/carpeta-drive"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.drive_folder_id").value("ya-existe"))
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
