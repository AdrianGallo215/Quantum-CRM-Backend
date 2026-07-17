package pe.quantum.crm.domain.oportunidades

import com.ninjasquad.springmockk.MockkBean
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.verify
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.http.HttpHeaders
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.delete
import pe.quantum.crm.config.security.JwtService
import pe.quantum.crm.shared.exception.NoEncontradoException
import pe.quantum.crm.support.SinBaseDeDatosMocks

/** Tests de `DELETE /oportunidades/:id` (contrato_api.md §10): exclusivo admin, sin cuerpo. */
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
class OportunidadControllerWebMvcTest {
    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var jwtService: JwtService

    @MockkBean
    lateinit var oportunidadService: OportunidadService

    @Test
    fun `DELETE oportunidades id como admin devuelve 204`() {
        every { oportunidadService.eliminar(50) } just Runs
        val token = jwtService.generateAccessToken(empleadoId = 1, rol = "admin")

        mockMvc.delete("/api/v1/oportunidades/50") {
            header(HttpHeaders.AUTHORIZATION, "Bearer $token")
        }.andExpect {
            status { isNoContent() }
        }
        verify { oportunidadService.eliminar(50) }
    }

    @Test
    fun `DELETE oportunidades id como no-admin devuelve 403`() {
        val token = jwtService.generateAccessToken(empleadoId = 2, rol = "vendedor")

        mockMvc.delete("/api/v1/oportunidades/50") {
            header(HttpHeaders.AUTHORIZATION, "Bearer $token")
        }.andExpect {
            status { isForbidden() }
            jsonPath("$.error.code") { value("PERMISO_INSUFICIENTE") }
        }
        verify(exactly = 0) { oportunidadService.eliminar(any()) }
    }

    @Test
    fun `DELETE oportunidades id inexistente devuelve 404`() {
        every { oportunidadService.eliminar(999) } throws NoEncontradoException("La oportunidad no existe")
        val token = jwtService.generateAccessToken(empleadoId = 1, rol = "admin")

        mockMvc.delete("/api/v1/oportunidades/999") {
            header(HttpHeaders.AUTHORIZATION, "Bearer $token")
        }.andExpect {
            status { isNotFound() }
            jsonPath("$.error.code") { value("NO_ENCONTRADO") }
        }
    }
}
