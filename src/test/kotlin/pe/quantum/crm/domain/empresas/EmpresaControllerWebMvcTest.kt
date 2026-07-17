package pe.quantum.crm.domain.empresas

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
import pe.quantum.crm.domain.contactos.ContactoService
import pe.quantum.crm.shared.exception.NoEncontradoException
import pe.quantum.crm.support.SinBaseDeDatosMocks

/** Tests de `DELETE /empresas/:id` (contrato_api.md §8): exclusivo admin, sin cuerpo. */
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
class EmpresaControllerWebMvcTest {
    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var jwtService: JwtService

    @MockkBean
    lateinit var empresaService: EmpresaService

    @MockkBean
    lateinit var contactoService: ContactoService

    @Test
    fun `DELETE empresas id como admin devuelve 204`() {
        every { empresaService.eliminar(7) } just Runs
        val token = jwtService.generateAccessToken(empleadoId = 1, rol = "admin")

        mockMvc.delete("/api/v1/empresas/7") {
            header(HttpHeaders.AUTHORIZATION, "Bearer $token")
        }.andExpect {
            status { isNoContent() }
        }
        verify { empresaService.eliminar(7) }
    }

    @Test
    fun `DELETE empresas id como no-admin devuelve 403`() {
        val token = jwtService.generateAccessToken(empleadoId = 2, rol = "gerencia")

        mockMvc.delete("/api/v1/empresas/7") {
            header(HttpHeaders.AUTHORIZATION, "Bearer $token")
        }.andExpect {
            status { isForbidden() }
            jsonPath("$.error.code") { value("PERMISO_INSUFICIENTE") }
        }
        verify(exactly = 0) { empresaService.eliminar(any()) }
    }

    @Test
    fun `DELETE empresas id inexistente devuelve 404`() {
        every { empresaService.eliminar(99) } throws NoEncontradoException("La empresa no existe")
        val token = jwtService.generateAccessToken(empleadoId = 1, rol = "admin")

        mockMvc.delete("/api/v1/empresas/99") {
            header(HttpHeaders.AUTHORIZATION, "Bearer $token")
        }.andExpect {
            status { isNotFound() }
            jsonPath("$.error.code") { value("NO_ENCONTRADO") }
        }
    }
}
