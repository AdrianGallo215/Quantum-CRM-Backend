package pe.quantum.crm.shared

import com.ninjasquad.springmockk.MockkBean
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.http.HttpHeaders
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import pe.quantum.crm.config.security.JwtService
import pe.quantum.crm.domain.empleados.EmpleadoService
import pe.quantum.crm.support.SinBaseDeDatosMocks

/**
 * `NoResourceFoundException` (recursos estaticos inexistentes, p. ej. `/favicon.ico`
 * que todo navegador pide solo) debe responder 404 silencioso, no un 500 "error
 * interno" logueado como excepcion no controlada.
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
class GlobalExceptionHandlerWebMvcTest {
    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var jwtService: JwtService

    // Sin DataSource no hay repositorios JPA; se mockea el servicio que los usa.
    @MockkBean
    lateinit var empleadoService: EmpleadoService

    @Test
    fun `recurso estatico inexistente devuelve 404 NO_ENCONTRADO, no 500`() {
        val token = jwtService.generateAccessToken(empleadoId = 1, rol = "admin")

        mockMvc.get("/favicon.ico") {
            header(HttpHeaders.AUTHORIZATION, "Bearer $token")
        }.andExpect {
            status { isNotFound() }
            jsonPath("$.error.code") { value("NO_ENCONTRADO") }
        }
    }
}
