package pe.quantum.crm.domain.empleados

import com.ninjasquad.springmockk.MockkBean
import io.mockk.every
import io.mockk.verify
import jakarta.servlet.http.Cookie
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.post
import pe.quantum.crm.config.security.AuthCookieFactory
import pe.quantum.crm.config.security.JwtService
import pe.quantum.crm.shared.exception.CredencialesInvalidasException
import pe.quantum.crm.support.SinBaseDeDatosMocks

/**
 * Tests de los endpoints de auth (B0.8) via MockMvc, sin base de datos: se mockea
 * EmpleadoService. Ejercitan la cadena de seguridad, el rate limiting, el envelope
 * de error y las cookies httpOnly reales.
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
class AuthControllerWebMvcTest {
    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var jwtService: JwtService

    @MockkBean
    lateinit var empleadoService: EmpleadoService

    private fun empleado(email: String) =
        Empleado(
            id = 1,
            nombres = "Ana",
            apellidos = "Diaz",
            email = email,
            rol = RolEmpleado.jdv,
            activo = true,
            passwordHash = "\$2a\$12\$hash",
            requiereCambioContrasena = false,
        )

    private fun loginBody(
        email: String,
        password: String,
    ) = """{"email":"$email","password":"$password"}"""

    @Test
    fun `login exitoso setea cookies httpOnly y devuelve el empleado`() {
        val email = "ok@quantum.pe"
        every { empleadoService.autenticar(email, "secreta") } returns empleado(email)

        val result =
            mockMvc.post("/api/v1/auth/login") {
                contentType = MediaType.APPLICATION_JSON
                content = loginBody(email, "secreta")
            }.andExpect {
                status { isOk() }
            }.andReturn()

        assertThat(result.response.contentAsString).contains("\"email\":\"$email\"", "\"rol\":\"jdv\"")
        val setCookies = result.response.getHeaders("Set-Cookie")
        assertThat(setCookies).anyMatch {
            it.startsWith("access_token=") && it.contains("HttpOnly") && it.contains("SameSite=Strict")
        }
        assertThat(setCookies).anyMatch { it.startsWith("refresh_token=") && it.contains("HttpOnly") }
    }

    @Test
    fun `login con credenciales invalidas devuelve 401 generico`() {
        val email = "malas@quantum.pe"
        every { empleadoService.autenticar(email, "mala") } throws CredencialesInvalidasException()

        mockMvc.post("/api/v1/auth/login") {
            contentType = MediaType.APPLICATION_JSON
            content = loginBody(email, "mala")
        }.andExpect {
            status { isUnauthorized() }
            jsonPath("$.error.code") { value("CREDENCIALES_INVALIDAS") }
            jsonPath("$.error.message") { value("Email o contraseña incorrectos") }
            jsonPath("$.data") { isEmpty() }
        }
    }

    @Test
    fun `login con email invalido devuelve 400 de validacion`() {
        mockMvc.post("/api/v1/auth/login") {
            contentType = MediaType.APPLICATION_JSON
            content = loginBody("no-es-un-email", "x")
        }.andExpect {
            status { isBadRequest() }
            jsonPath("$.error.code") { value("VALIDACION") }
        }
    }

    @Test
    fun `login con json incompleto devuelve 400 de validacion en vez de 500`() {
        mockMvc.post("/api/v1/auth/login") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"email":"sin-password@quantum.pe"}"""
        }.andExpect {
            status { isBadRequest() }
            jsonPath("$.error.code") { value("VALIDACION") }
        }
    }

    @Test
    fun `tras 5 intentos fallidos el login responde 429 con Retry-After`() {
        val email = "bloqueo@quantum.pe"
        every { empleadoService.autenticar(email, "mala") } throws CredencialesInvalidasException()

        repeat(5) {
            mockMvc.post("/api/v1/auth/login") {
                contentType = MediaType.APPLICATION_JSON
                content = loginBody(email, "mala")
            }.andExpect { status { isUnauthorized() } }
        }

        mockMvc.post("/api/v1/auth/login") {
            contentType = MediaType.APPLICATION_JSON
            content = loginBody(email, "mala")
        }.andExpect {
            status { isTooManyRequests() }
            jsonPath("$.error.code") { value("DEMASIADOS_INTENTOS") }
            header { exists("Retry-After") }
        }
    }

    @Test
    fun `refresh sin cookie devuelve 401`() {
        mockMvc.post("/api/v1/auth/refresh").andExpect {
            status { isUnauthorized() }
            jsonPath("$.error.code") { value("CREDENCIALES_INVALIDAS") }
        }
    }

    @Test
    fun `refresh con cookie valida renueva las cookies`() {
        every { empleadoService.porId(1) } returns empleado("ana@quantum.pe")
        val refreshToken = jwtService.generateRefreshToken(empleadoId = 1)

        val result =
            mockMvc.post("/api/v1/auth/refresh") {
                cookie(Cookie(AuthCookieFactory.REFRESH_TOKEN_COOKIE, refreshToken))
            }.andExpect {
                status { isOk() }
                jsonPath("$.data.expires_in") { isNumber() }
            }.andReturn()

        assertThat(result.response.getHeaders("Set-Cookie"))
            .anyMatch { it.startsWith("access_token=") && it.contains("HttpOnly") }
    }

    @Test
    fun `refresh con un access token en la cookie devuelve 401`() {
        val accessToken = jwtService.generateAccessToken(empleadoId = 1, rol = "jdv")

        mockMvc.post("/api/v1/auth/refresh") {
            cookie(Cookie(AuthCookieFactory.REFRESH_TOKEN_COOKIE, accessToken))
        }.andExpect {
            status { isUnauthorized() }
            jsonPath("$.error.code") { value("CREDENCIALES_INVALIDAS") }
        }
    }

    @Test
    fun `refresh de un empleado inactivo devuelve 401`() {
        every { empleadoService.porId(1) } returns empleado("ana@quantum.pe").apply { activo = false }
        val refreshToken = jwtService.generateRefreshToken(empleadoId = 1)

        mockMvc.post("/api/v1/auth/refresh") {
            cookie(Cookie(AuthCookieFactory.REFRESH_TOKEN_COOKIE, refreshToken))
        }.andExpect {
            status { isUnauthorized() }
            jsonPath("$.error.code") { value("CREDENCIALES_INVALIDAS") }
        }
    }

    // ── Cambio de contraseña (D1) ────────────────────────────────────────────
    // Este endpoint vive bajo /auth/**, que en SecurityConfig es permitAll(). Es el
    // UNICO de la familia que exige sesion: el test 1 es la prueba de que el matcher
    // explicito quedo antes del permitAll y no quedo publico por accidente.

    private fun cambiarContrasenaBody(
        actual: String,
        nueva: String,
    ) = """{"password_actual":"$actual","password_nueva":"$nueva"}"""

    @Test
    fun `cambiar contrasena sin autenticacion responde 401`() {
        mockMvc.post("/api/v1/auth/cambiar-contrasena") {
            contentType = MediaType.APPLICATION_JSON
            content = cambiarContrasenaBody("vieja", "NuevaSegura123")
        }.andExpect {
            status { isUnauthorized() }
        }

        verify(exactly = 0) { empleadoService.cambiarContrasena(any(), any(), any()) }
    }

    @Test
    fun `cambiar contrasena autenticado con body valido responde 200 y llama al servicio`() {
        val token = jwtService.generateAccessToken(empleadoId = 1, rol = "vendedor")
        every { empleadoService.cambiarContrasena(1, "vieja", "NuevaSegura123") } returns Unit

        mockMvc.post("/api/v1/auth/cambiar-contrasena") {
            header(HttpHeaders.AUTHORIZATION, "Bearer $token")
            contentType = MediaType.APPLICATION_JSON
            content = cambiarContrasenaBody("vieja", "NuevaSegura123")
        }.andExpect {
            status { isOk() }
        }

        verify(exactly = 1) { empleadoService.cambiarContrasena(1, "vieja", "NuevaSegura123") }
    }

    @Test
    fun `cambiar contrasena con password_nueva corta responde 400 VALIDACION`() {
        val token = jwtService.generateAccessToken(empleadoId = 1, rol = "vendedor")

        mockMvc.post("/api/v1/auth/cambiar-contrasena") {
            header(HttpHeaders.AUTHORIZATION, "Bearer $token")
            contentType = MediaType.APPLICATION_JSON
            content = cambiarContrasenaBody("vieja", "corta")
        }.andExpect {
            status { isBadRequest() }
            jsonPath("$.error.code") { value("VALIDACION") }
        }

        verify(exactly = 0) { empleadoService.cambiarContrasena(any(), any(), any()) }
    }
}
