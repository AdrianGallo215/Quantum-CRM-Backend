package pe.quantum.crm.domain.empleados

import jakarta.servlet.http.Cookie
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import org.springframework.transaction.annotation.Transactional
import pe.quantum.crm.config.security.AuthCookieFactory
import pe.quantum.crm.support.IntegrationTestBase

/**
 * Flujo de auth end-to-end (B0.8) contra PostgreSQL real: inserta un empleado con
 * contraseña BCrypt, hace login (verifica el hash real, setea cookies httpOnly) y
 * accede a `/me` con la cookie de acceso. Valida el camino de cookie del filtro JWT
 * y la persistencia de `password_hash`.
 */
@Tag("integration")
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class AuthFlowIntegrationTest : IntegrationTestBase() {
    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var empleadoRepository: EmpleadoRepository

    @Autowired
    lateinit var passwordEncoder: PasswordEncoder

    @Test
    fun `login exitoso setea cookie y permite consultar me`() {
        empleadoRepository.save(
            Empleado(
                nombres = "Test",
                apellidos = "Login",
                email = "test.login@quantum.pe",
                rol = RolEmpleado.vendedor,
                activo = true,
                passwordHash = passwordEncoder.encode("Secreta123"),
                requiereCambioContrasena = false,
            ),
        )

        val loginResult =
            mockMvc.post("/api/v1/auth/login") {
                contentType = MediaType.APPLICATION_JSON
                content = """{"email":"test.login@quantum.pe","password":"Secreta123"}"""
            }.andExpect {
                status { isOk() }
                jsonPath("$.data.empleado.email") { value("test.login@quantum.pe") }
                jsonPath("$.data.empleado.rol") { value("vendedor") }
            }.andReturn()

        val accessToken =
            loginResult.response.getHeaders("Set-Cookie")
                .first { it.startsWith("${AuthCookieFactory.ACCESS_TOKEN_COOKIE}=") }
                .substringAfter("=")
                .substringBefore(";")

        mockMvc.get("/api/v1/empleados/me") {
            cookie(Cookie(AuthCookieFactory.ACCESS_TOKEN_COOKIE, accessToken))
        }.andExpect {
            status { isOk() }
            jsonPath("$.data.email") { value("test.login@quantum.pe") }
            jsonPath("$.data.rol") { value("vendedor") }
        }
    }

    @Test
    fun `login con contraseña incorrecta devuelve 401 generico`() {
        empleadoRepository.save(
            Empleado(
                nombres = "Test",
                apellidos = "Malo",
                email = "test.malo@quantum.pe",
                rol = RolEmpleado.vendedor,
                activo = true,
                passwordHash = passwordEncoder.encode("Correcta123"),
                requiereCambioContrasena = false,
            ),
        )

        mockMvc.post("/api/v1/auth/login") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"email":"test.malo@quantum.pe","password":"Incorrecta"}"""
        }.andExpect {
            status { isUnauthorized() }
            jsonPath("$.error.code") { value("CREDENCIALES_INVALIDAS") }
        }
    }
}
