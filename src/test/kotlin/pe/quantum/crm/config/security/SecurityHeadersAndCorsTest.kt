package pe.quantum.crm.config.security

import com.ninjasquad.springmockk.MockkBean
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.http.HttpHeaders
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController
import pe.quantum.crm.domain.empleados.EmpleadoService
import pe.quantum.crm.support.SinBaseDeDatosMocks

/**
 * Tests de las cabeceras de seguridad HTTP (SECURITY-backend.md §6) y del CORS
 * restrictivo (§7). Excluye DataSource/JPA/Flyway para correr sin Docker: la
 * cadena de seguridad no depende de la base.
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
@Import(SecurityHeadersAndCorsTest.ProbeController::class, SinBaseDeDatosMocks::class)
class SecurityHeadersAndCorsTest {
    @RestController
    class ProbeController {
        @GetMapping("/probe")
        fun probe(): Map<String, Boolean> = mapOf("ok" to true)
    }

    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var jwtService: JwtService

    // Sin DataSource no hay repositorios JPA; se mockea el servicio que los usa.
    @MockkBean
    lateinit var empleadoService: EmpleadoService

    private fun bearer(): String = "Bearer " + jwtService.generateAccessToken(empleadoId = 1, rol = "admin")

    @Test
    fun `toda respuesta incluye las cabeceras de seguridad`() {
        mockMvc.perform(get("/probe").header(HttpHeaders.AUTHORIZATION, bearer()))
            .andExpect(status().isOk)
            .andExpect(header().string("X-Content-Type-Options", "nosniff"))
            .andExpect(header().string("X-Frame-Options", "DENY"))
            .andExpect(header().string("Content-Security-Policy", "default-src 'self'; frame-ancestors 'none'"))
            .andExpect(header().string("Referrer-Policy", "strict-origin-when-cross-origin"))
            .andExpect(header().string("Permissions-Policy", "geolocation=(), microphone=(), camera=()"))
    }

    @Test
    fun `sobre HTTPS incluye HSTS`() {
        mockMvc.perform(get("/probe").secure(true).header(HttpHeaders.AUTHORIZATION, bearer()))
            .andExpect(status().isOk)
            .andExpect(header().string("Strict-Transport-Security", "max-age=31536000 ; includeSubDomains"))
    }

    @Test
    fun `un request sin token a una ruta protegida devuelve 401`() {
        mockMvc.perform(get("/probe"))
            .andExpect(status().isUnauthorized)
    }

    @Test
    fun `CORS acepta el preflight desde un origen permitido`() {
        mockMvc.perform(
            options("/probe")
                .header(HttpHeaders.ORIGIN, "http://localhost:5173")
                .header("Access-Control-Request-Method", "GET"),
        )
            .andExpect(status().isOk)
            .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:5173"))
            .andExpect(header().string("Access-Control-Allow-Credentials", "true"))
    }

    @Test
    fun `CORS rechaza el preflight desde un origen no permitido`() {
        mockMvc.perform(
            options("/probe")
                .header(HttpHeaders.ORIGIN, "http://sitio-malicioso.example")
                .header("Access-Control-Request-Method", "GET"),
        )
            .andExpect(status().isForbidden)
            .andExpect(header().doesNotExist("Access-Control-Allow-Origin"))
    }
}
