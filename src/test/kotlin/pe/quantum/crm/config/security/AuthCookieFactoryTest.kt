package pe.quantum.crm.config.security

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Duration

/**
 * Tests unitarios de las cookies de autenticacion (B0.7). Verifican los flags de
 * seguridad de SECURITY-backend.md §2.1: HttpOnly, Secure, SameSite=Strict.
 */
class AuthCookieFactoryTest {
    private val factory = AuthCookieFactory()

    @Test
    fun `la cookie de access token lleva los flags de seguridad`() {
        val cookie = factory.accessTokenCookie("token-abc", Duration.ofHours(1).toMillis())

        assertThat(cookie.name).isEqualTo("access_token")
        assertThat(cookie.value).isEqualTo("token-abc")
        assertThat(cookie.isHttpOnly).isTrue()
        assertThat(cookie.isSecure).isTrue()
        assertThat(cookie.sameSite).isEqualTo("Strict")
        assertThat(cookie.path).isEqualTo("/")
        assertThat(cookie.maxAge).isEqualTo(Duration.ofHours(1))
    }

    @Test
    fun `la cookie de refresh token lleva los mismos flags de seguridad`() {
        val cookie = factory.refreshTokenCookie("refresh-xyz", Duration.ofDays(7).toMillis())

        assertThat(cookie.name).isEqualTo("refresh_token")
        assertThat(cookie.isHttpOnly).isTrue()
        assertThat(cookie.isSecure).isTrue()
        assertThat(cookie.sameSite).isEqualTo("Strict")
        assertThat(cookie.maxAge).isEqualTo(Duration.ofDays(7))
    }

    @Test
    fun `las cookies expiradas vacian el valor y ponen maxAge en cero`() {
        val access = factory.expiredAccessTokenCookie()
        val refresh = factory.expiredRefreshTokenCookie()

        assertThat(access.value).isEmpty()
        assertThat(access.maxAge).isZero()
        assertThat(access.isHttpOnly).isTrue()
        assertThat(refresh.value).isEmpty()
        assertThat(refresh.maxAge).isZero()
    }
}
