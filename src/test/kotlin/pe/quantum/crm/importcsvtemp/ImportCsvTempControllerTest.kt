package pe.quantum.crm.importcsvtemp

import com.ninjasquad.springmockk.MockkBean
import io.mockk.every
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.http.HttpHeaders
import org.springframework.mock.web.MockMultipartFile
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import pe.quantum.crm.config.security.JwtService
import pe.quantum.crm.importcsvtemp.dto.ImportEmpresaFilaResultado
import pe.quantum.crm.importcsvtemp.dto.ImportEmpresasResultDto
import pe.quantum.crm.shared.exception.ValidacionException
import pe.quantum.crm.shared.security.UsuarioActual
import pe.quantum.crm.support.SinBaseDeDatosMocks

/**
 * Tests del endpoint temporal `POST /import-csv-temp/empresas` (B08-temp), sin base
 * de datos: ImportCsvTempService se mockea. Ejercita la cadena de seguridad (mismo
 * patron que AuthControllerWebMvcTest / EmpleadoMeControllerTest) y el envelope.
 */
@SpringBootTest(
    properties = [
        "spring.autoconfigure.exclude=" +
            "org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration," +
            "org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration," +
            "org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration",
    ],
)
@AutoConfigureMockMvc
@Import(SinBaseDeDatosMocks::class)
class ImportCsvTempControllerTest {
    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var jwtService: JwtService

    @MockkBean
    lateinit var importCsvTempService: ImportCsvTempService

    private val archivo =
        MockMultipartFile(
            "file",
            "empresas.csv",
            "text/csv",
            "ruc,razon_social,segmento\n20999999999,Beta SRL,urbano".toByteArray(),
        )

    @Test
    fun `importar sin token devuelve 401`() {
        mockMvc.perform(multipart("/api/v1/import-csv-temp/empresas").file(archivo))
            .andExpect(status().isUnauthorized)
    }

    @Test
    fun `importar con token valido devuelve el resultado de la importacion`() {
        val token = jwtService.generateAccessToken(empleadoId = 7, rol = "vendedor")
        every { importCsvTempService.importarEmpresas(any(), UsuarioActual(id = 7, rol = "vendedor")) } returns
            ImportEmpresasResultDto(
                totalFilas = 1,
                creadas = 1,
                conError = 0,
                detalle =
                    listOf(
                        ImportEmpresaFilaResultado(
                            fila = 2,
                            ruc = "20999999999",
                            razonSocial = "Beta SRL",
                            estado = "creada",
                            motivo = null,
                        ),
                    ),
            )

        mockMvc.perform(
            multipart("/api/v1/import-csv-temp/empresas")
                .file(archivo)
                .header(HttpHeaders.AUTHORIZATION, "Bearer $token"),
        ).andExpect(status().isOk)
            .andExpect(jsonPath("$.data.creadas").value(1))
            .andExpect(jsonPath("$.data.detalle[0].estado").value("creada"))
    }

    @Test
    fun `archivo invalido responde 400 VALIDACION`() {
        val token = jwtService.generateAccessToken(empleadoId = 7, rol = "vendedor")
        every { importCsvTempService.importarEmpresas(any(), any()) } throws
            ValidacionException("El archivo CSV está vacío")

        mockMvc.perform(
            multipart("/api/v1/import-csv-temp/empresas")
                .file(archivo)
                .header(HttpHeaders.AUTHORIZATION, "Bearer $token"),
        ).andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.error.code").value("VALIDACION"))
    }
}
