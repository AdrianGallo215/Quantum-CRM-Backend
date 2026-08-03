package pe.quantum.crm.config.security

import org.springframework.http.ResponseCookie
import org.springframework.stereotype.Component
import java.time.Duration

/**
 * Construye las cookies de autenticacion con los flags de SECURITY-backend.md §2.1:
 * `HttpOnly` (inaccesible a JS), `Secure` (solo HTTPS), `SameSite=Strict` (mitiga
 * CSRF), `Path=/`. El token viaja en cookie, nunca accesible desde el frontend.
 */
@Component
class AuthCookieFactory(
    private val cookieProperties: CookieProperties = CookieProperties(),
) {
    companion object {
        const val ACCESS_TOKEN_COOKIE = "access_token"
        const val REFRESH_TOKEN_COOKIE = "refresh_token"
    }

    fun accessTokenCookie(
        token: String,
        maxAgeMs: Long,
    ): ResponseCookie = build(ACCESS_TOKEN_COOKIE, token, Duration.ofMillis(maxAgeMs))

    fun refreshTokenCookie(
        token: String,
        maxAgeMs: Long,
    ): ResponseCookie = build(REFRESH_TOKEN_COOKIE, token, Duration.ofMillis(maxAgeMs))

    /** Cookies vencidas (maxAge 0) para cerrar sesion. */
    fun expiredAccessTokenCookie(): ResponseCookie = build(ACCESS_TOKEN_COOKIE, "", Duration.ZERO)

    fun expiredRefreshTokenCookie(): ResponseCookie = build(REFRESH_TOKEN_COOKIE, "", Duration.ZERO)

    private fun build(
        name: String,
        value: String,
        maxAge: Duration,
    ): ResponseCookie =
        ResponseCookie.from(name, value)
            .httpOnly(true)
            .secure(cookieProperties.secure)
            .sameSite("Strict")
            .path("/")
            .maxAge(maxAge)
            .build()
}
