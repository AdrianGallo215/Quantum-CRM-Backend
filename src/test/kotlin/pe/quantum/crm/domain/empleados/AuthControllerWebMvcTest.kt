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
import pe.quantum.crm.shared.exception.NoEncontradoException
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

    private fun empleado(
        email: String,
        tokenVersion: Int = 0,
    ) = Empleado(
        id = 1,
        nombres = "Ana",
        apellidos = "Diaz",
        email = email,
        rol = RolEmpleado.jdv,
        activo = true,
        passwordHash = "\$2a\$12\$hash",
        requiereCambioContrasena = false,
        tokenVersion = tokenVersion,
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

    /**
     * `Retry-After` llega en la respuesta, pero sin `Access-Control-Expose-Headers`
     * el navegador la bloquea para JS en un origen cross-site (crm.* vs api.*): el
     * frontend no puede mostrar una cuenta atrás real. CORS lo agrega solo cuando
     * la request trae `Origin`, por eso el test lo simula.
     */
    @Test
    fun `la respuesta 429 de login expone Retry-After via CORS`() {
        val email = "bloqueo-cors@quantum.pe"
        every { empleadoService.autenticar(email, "mala") } throws CredencialesInvalidasException()

        repeat(5) {
            mockMvc.post("/api/v1/auth/login") {
                header(HttpHeaders.ORIGIN, "http://localhost:5173")
                contentType = MediaType.APPLICATION_JSON
                content = loginBody(email, "mala")
            }.andExpect { status { isUnauthorized() } }
        }

        val result =
            mockMvc.post("/api/v1/auth/login") {
                header(HttpHeaders.ORIGIN, "http://localhost:5173")
                contentType = MediaType.APPLICATION_JSON
                content = loginBody(email, "mala")
            }.andExpect {
                status { isTooManyRequests() }
            }.andReturn()

        assertThat(result.response.getHeader("Access-Control-Expose-Headers")).contains("Retry-After")
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

    /**
     * Un refresh token valido cuyo empleado ya no existe es una credencial muerta
     * (401), no un recurso ausente (404). El 404 ademas confirmaba al portador del
     * token que ese id llego a existir.
     */
    @Test
    fun `refresh con un empleado ya borrado responde 401 y no 404`() {
        every { empleadoService.porId(99) } throws NoEncontradoException("El empleado no existe")
        val refreshToken = jwtService.generateRefreshToken(empleadoId = 99)

        mockMvc.post("/api/v1/auth/refresh") {
            cookie(Cookie(AuthCookieFactory.REFRESH_TOKEN_COOKIE, refreshToken))
        }.andExpect {
            status { isUnauthorized() }
            jsonPath("$.error.code") { value("CREDENCIALES_INVALIDAS") }
        }
    }

    /**
     * Un refresh token con una token_version anterior a la actual del empleado
     * fue revocado (logout o cambio de contraseña en otra sesion, ver B0.9).
     * Debe rechazarse igual que un empleado inactivo: 401 generico.
     */
    @Test
    fun `refresh con token_version desactualizada responde 401`() {
        every { empleadoService.porId(1) } returns empleado("ana@quantum.pe", tokenVersion = 1)
        val refreshToken = jwtService.generateRefreshToken(empleadoId = 1, tokenVersion = 0)

        mockMvc.post("/api/v1/auth/refresh") {
            cookie(Cookie(AuthCookieFactory.REFRESH_TOKEN_COOKIE, refreshToken))
        }.andExpect {
            status { isUnauthorized() }
            jsonPath("$.error.code") { value("CREDENCIALES_INVALIDAS") }
        }
    }

    // ── Logout (B0.9) ─────────────────────────────────────────────────────────
    // Debe ser idempotente (siempre 204, nunca 401) y limpiar ambas cookies con
    // los mismos atributos con los que se emitieron, para que el navegador las
    // borre. Si trae un refresh token vigente, revoca la sesion en servidor.

    @Test
    fun `logout sin cookie responde 204 y limpia ambas cookies`() {
        val result =
            mockMvc.post("/api/v1/auth/logout").andExpect {
                status { isNoContent() }
            }.andReturn()

        val setCookies = result.response.getHeaders("Set-Cookie")
        assertThat(setCookies).anyMatch {
            it.startsWith("access_token=") && it.contains("Max-Age=0") && it.contains("HttpOnly") && it.contains("SameSite=Strict")
        }
        assertThat(setCookies).anyMatch {
            it.startsWith("refresh_token=") && it.contains("Max-Age=0") && it.contains("HttpOnly") && it.contains("SameSite=Strict")
        }
        verify(exactly = 0) { empleadoService.revocarSesiones(any()) }
    }

    @Test
    fun `logout con refresh token vigente revoca la sesion y responde 204`() {
        val refreshToken = jwtService.generateRefreshToken(empleadoId = 1, tokenVersion = 0)
        every { empleadoService.revocarSesiones(1) } returns Unit

        mockMvc.post("/api/v1/auth/logout") {
            cookie(Cookie(AuthCookieFactory.REFRESH_TOKEN_COOKIE, refreshToken))
        }.andExpect {
            status { isNoContent() }
        }

        verify(exactly = 1) { empleadoService.revocarSesiones(1) }
    }

    @Test
    fun `logout con cookie invalida igual responde 204 y no toca el servicio`() {
        mockMvc.post("/api/v1/auth/logout") {
            cookie(Cookie(AuthCookieFactory.REFRESH_TOKEN_COOKIE, "esto-no-es-un-jwt"))
        }.andExpect {
            status { isNoContent() }
        }

        verify(exactly = 0) { empleadoService.revocarSesiones(any()) }
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
        every { empleadoService.porId(1) } returns empleado("ana@quantum.pe", tokenVersion = 1)

        mockMvc.post("/api/v1/auth/cambiar-contrasena") {
            header(HttpHeaders.AUTHORIZATION, "Bearer $token")
            contentType = MediaType.APPLICATION_JSON
            content = cambiarContrasenaBody("vieja", "NuevaSegura123")
        }.andExpect {
            status { isOk() }
        }

        verify(exactly = 1) { empleadoService.cambiarContrasena(1, "vieja", "NuevaSegura123") }
    }

    /**
     * cambiarContrasena incrementa token_version en servidor (invalida otras
     * sesiones), pero la sesion que hizo el cambio no debe quedar rota en su
     * proximo refresh: el controller reemite cookies frescas con la nueva
     * version tras aplicar el cambio.
     */
    @Test
    fun `cambiar contrasena reemite cookies frescas para no cerrar la sesion actual`() {
        val token = jwtService.generateAccessToken(empleadoId = 1, rol = "vendedor")
        every { empleadoService.cambiarContrasena(1, "vieja", "NuevaSegura123") } returns Unit
        every { empleadoService.porId(1) } returns empleado("ana@quantum.pe", tokenVersion = 1)

        val result =
            mockMvc.post("/api/v1/auth/cambiar-contrasena") {
                header(HttpHeaders.AUTHORIZATION, "Bearer $token")
                contentType = MediaType.APPLICATION_JSON
                content = cambiarContrasenaBody("vieja", "NuevaSegura123")
            }.andExpect {
                status { isOk() }
            }.andReturn()

        val setCookies = result.response.getHeaders("Set-Cookie")
        assertThat(setCookies).anyMatch { it.startsWith("access_token=") && it.contains("HttpOnly") }
        assertThat(setCookies).anyMatch { it.startsWith("refresh_token=") && it.contains("HttpOnly") }
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
