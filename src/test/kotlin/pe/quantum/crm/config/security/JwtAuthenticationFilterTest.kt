package pe.quantum.crm.config.security

import jakarta.servlet.http.Cookie
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockFilterChain
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.security.core.context.SecurityContextHolder
import java.time.Duration

/**
 * Tests del filtro de autenticacion JWT. Cubren el endurecimiento de seguridad:
 * solo un token de tipo `access` autentica, y una identidad sin rol nunca llega
 * a la cadena (una autenticacion sin authorities satisfacia `authenticated()`).
 */
class JwtAuthenticationFilterTest {
    private val props =
        JwtProperties(
            secret = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
            accessExpirationMs = Duration.ofHours(1).toMillis(),
            refreshExpirationMs = Duration.ofDays(7).toMillis(),
        )
    private val jwtService = JwtService(props)
    private val filter = JwtAuthenticationFilter(jwtService)

    @AfterEach
    fun limpiarContexto() {
        SecurityContextHolder.clearContext()
    }

    private fun filtrarConHeader(token: String) {
        val request = MockHttpServletRequest()
        request.addHeader("Authorization", "Bearer $token")
        filter.doFilter(request, MockHttpServletResponse(), MockFilterChain())
    }

    private fun filtrarConCookie(token: String) {
        val request = MockHttpServletRequest()
        request.setCookies(Cookie(AuthCookieFactory.ACCESS_TOKEN_COOKIE, token))
        filter.doFilter(request, MockHttpServletResponse(), MockFilterChain())
    }

    private fun autenticacion() = SecurityContextHolder.getContext().authentication

    @Test
    fun `un access token valido autentica con la autoridad del rol`() {
        filtrarConHeader(jwtService.generateAccessToken(empleadoId = 9, rol = "jdv"))

        val auth = autenticacion()
        assertThat(auth).isNotNull
        assertThat(auth!!.principal).isEqualTo(9L)
        assertThat(auth.authorities.map { it.authority }).containsExactly("ROLE_jdv")
    }

    @Test
    fun `un refresh token en el header Authorization no autentica`() {
        filtrarConHeader(jwtService.generateRefreshToken(empleadoId = 9))

        assertThat(autenticacion()).isNull()
    }

    @Test
    fun `un refresh token en la cookie de access no autentica`() {
        filtrarConCookie(jwtService.generateRefreshToken(empleadoId = 9))

        assertThat(autenticacion()).isNull()
    }

    @Test
    fun `un access token sin rol utilizable no autentica`() {
        filtrarConHeader(jwtService.generateAccessToken(empleadoId = 9, rol = "  "))

        assertThat(autenticacion()).isNull()
    }

    @Test
    fun `un token invalido no autentica`() {
        filtrarConHeader("no-es-un-token")

        assertThat(autenticacion()).isNull()
    }
}
