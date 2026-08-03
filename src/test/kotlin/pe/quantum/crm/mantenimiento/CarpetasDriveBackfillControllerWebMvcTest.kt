package pe.quantum.crm.mantenimiento

import com.ninjasquad.springmockk.MockkBean
import io.mockk.every
import io.mockk.verify
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.http.HttpHeaders
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.post
import pe.quantum.crm.config.security.JwtService
import pe.quantum.crm.mantenimiento.dto.BackfillCarpetasDto
import pe.quantum.crm.support.SinBaseDeDatosMocks

/** El backfill es exclusivo de admin (matriz_permisos.md §2.12). */
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
class CarpetasDriveBackfillControllerWebMvcTest {
    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var jwtService: JwtService

    @MockkBean
    lateinit var backfillService: CarpetasDriveBackfillService

    @Test
    fun `POST carpetas-drive como admin devuelve 200`() {
        every { backfillService.ejecutar(null) } returns
            BackfillCarpetasDto(
                empresasProcesadas = 2,
                oportunidadesProcesadas = 1,
                errores = emptyList(),
                pendientesRestantes = 0,
            )
        val token = jwtService.generateAccessToken(empleadoId = 1, rol = "admin")

        mockMvc.post("/api/v1/mantenimiento/carpetas-drive") {
            header(HttpHeaders.AUTHORIZATION, "Bearer $token")
        }.andExpect {
            status { isOk() }
            jsonPath("$.data.empresas_procesadas") { value(2) }
        }
        verify { backfillService.ejecutar(null) }
    }

    @Test
    fun `POST carpetas-drive como no-admin devuelve 403 y no ejecuta nada`() {
        val token = jwtService.generateAccessToken(empleadoId = 2, rol = "gerencia")

        mockMvc.post("/api/v1/mantenimiento/carpetas-drive") {
            header(HttpHeaders.AUTHORIZATION, "Bearer $token")
        }.andExpect {
            status { isForbidden() }
            jsonPath("$.error.code") { value("PERMISO_INSUFICIENTE") }
        }
        verify(exactly = 0) { backfillService.ejecutar(any()) }
    }

    @Test
    fun `POST carpetas-drive sin token devuelve 401`() {
        mockMvc.post("/api/v1/mantenimiento/carpetas-drive").andExpect {
            status { isUnauthorized() }
        }
        verify(exactly = 0) { backfillService.ejecutar(any()) }
    }
}
