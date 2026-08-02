package pe.quantum.crm.domain.empleados

import com.ninjasquad.springmockk.MockkBean
import io.mockk.every
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.http.HttpHeaders
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import pe.quantum.crm.config.security.JwtService
import pe.quantum.crm.support.SinBaseDeDatosMocks

/**
 * Tests de `GET /empleados/me` (B0.8): requiere autenticacion y devuelve el perfil
 * del empleado del token. Sin base de datos (EmpleadoService mockeado).
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
class EmpleadoMeControllerTest {
    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var jwtService: JwtService

    @MockkBean
    lateinit var empleadoService: EmpleadoService

    @Test
    fun `me sin token devuelve 401`() {
        mockMvc.get("/api/v1/empleados/me").andExpect {
            status { isUnauthorized() }
        }
    }

    @Test
    fun `me con token valido devuelve el perfil del empleado`() {
        val empleado =
            Empleado(
                id = 7,
                nombres = "Aldo",
                apellidos = "Martinez",
                email = "aldo@quantum.pe",
                rol = RolEmpleado.jdv,
                activo = true,
            )
        every { empleadoService.porId(7) } returns empleado
        val token = jwtService.generateAccessToken(empleadoId = 7, rol = "jdv")

        mockMvc.get("/api/v1/empleados/me") {
            header(HttpHeaders.AUTHORIZATION, "Bearer $token")
        }.andExpect {
            status { isOk() }
            jsonPath("$.data.id") { value(7) }
            jsonPath("$.data.email") { value("aldo@quantum.pe") }
            jsonPath("$.data.rol") { value("jdv") }
            jsonPath("$.error") { isEmpty() }
        }
    }
}
