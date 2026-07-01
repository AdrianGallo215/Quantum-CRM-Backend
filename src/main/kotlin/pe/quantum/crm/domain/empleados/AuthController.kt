package pe.quantum.crm.domain.empleados

import jakarta.servlet.http.HttpServletResponse
import jakarta.validation.Valid
import org.springframework.http.HttpHeaders
import org.springframework.web.bind.annotation.CookieValue
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import pe.quantum.crm.config.security.AuthCookieFactory
import pe.quantum.crm.config.security.JwtProperties
import pe.quantum.crm.config.security.JwtService
import pe.quantum.crm.config.security.LoginRateLimiter
import pe.quantum.crm.domain.empleados.dto.LoginRequest
import pe.quantum.crm.domain.empleados.dto.LoginResponse
import pe.quantum.crm.domain.empleados.dto.RefreshResponse
import pe.quantum.crm.domain.empleados.dto.toDto
import pe.quantum.crm.shared.ApiResponse
import pe.quantum.crm.shared.exception.CredencialesInvalidasException
import pe.quantum.crm.shared.exception.DemasiadosIntentosException

/**
 * Endpoints de autenticacion (contrato_api.md §6, SECURITY §2).
 *
 * Los tokens se emiten en cookies httpOnly (`Secure; SameSite=Strict`), no en el
 * body. Login aplica rate limiting por email (SECURITY §8) y responde 401 generico
 * ante credenciales invalidas. Refresh lee el refresh token de su cookie y revalida
 * que el empleado siga activo antes de renovar.
 */
@RestController
@RequestMapping("/api/v1/auth")
class AuthController(
    private val empleadoService: EmpleadoService,
    private val jwtService: JwtService,
    private val cookieFactory: AuthCookieFactory,
    private val rateLimiter: LoginRateLimiter,
    private val jwtProperties: JwtProperties,
) {
    @PostMapping("/login")
    fun login(
        @Valid @RequestBody request: LoginRequest,
        response: HttpServletResponse,
    ): ApiResponse<LoginResponse> {
        val key = request.email.lowercase()
        if (rateLimiter.isBlocked(key)) {
            throw DemasiadosIntentosException(rateLimiter.retryAfterSeconds(key))
        }

        val empleado =
            try {
                empleadoService.autenticar(request.email, request.password)
            } catch (ex: CredencialesInvalidasException) {
                rateLimiter.recordFailure(key)
                throw ex
            }
        rateLimiter.reset(key)

        emitAuthCookies(empleado, response)
        return ApiResponse.ok(
            LoginResponse(
                empleado = empleado.toDto(),
                expiresIn = jwtProperties.accessExpirationMs / MILLIS_PER_SECOND,
                requiereCambioContrasena = empleado.requiereCambioContrasena,
            ),
        )
    }

    @PostMapping("/refresh")
    fun refresh(
        @CookieValue(name = AuthCookieFactory.REFRESH_TOKEN_COOKIE, required = false) refreshToken: String?,
        response: HttpServletResponse,
    ): ApiResponse<RefreshResponse> {
        val principal = refreshToken?.let { jwtService.validate(it) } ?: throw CredencialesInvalidasException()
        val empleado = empleadoService.porId(principal.empleadoId)
        if (!empleado.activo) {
            throw CredencialesInvalidasException()
        }

        emitAuthCookies(empleado, response)
        return ApiResponse.ok(RefreshResponse(expiresIn = jwtProperties.accessExpirationMs / MILLIS_PER_SECOND))
    }

    private fun emitAuthCookies(
        empleado: Empleado,
        response: HttpServletResponse,
    ) {
        val id = requireNotNull(empleado.id)
        val access = jwtService.generateAccessToken(id, empleado.rol.name)
        val refresh = jwtService.generateRefreshToken(id)
        response.addHeader(
            HttpHeaders.SET_COOKIE,
            cookieFactory.accessTokenCookie(access, jwtProperties.accessExpirationMs).toString(),
        )
        response.addHeader(
            HttpHeaders.SET_COOKIE,
            cookieFactory.refreshTokenCookie(refresh, jwtProperties.refreshExpirationMs).toString(),
        )
    }

    private companion object {
        const val MILLIS_PER_SECOND = 1000L
    }
}
