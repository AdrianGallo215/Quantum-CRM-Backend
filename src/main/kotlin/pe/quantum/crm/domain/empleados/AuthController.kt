package pe.quantum.crm.domain.empleados

import jakarta.servlet.http.HttpServletResponse
import jakarta.validation.Valid
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.CookieValue
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import pe.quantum.crm.config.security.AuthCookieFactory
import pe.quantum.crm.config.security.JwtProperties
import pe.quantum.crm.config.security.JwtService
import pe.quantum.crm.config.security.LoginRateLimiter
import pe.quantum.crm.config.security.TipoToken
import pe.quantum.crm.domain.empleados.dto.CambiarContrasenaRequest
import pe.quantum.crm.domain.empleados.dto.LoginRequest
import pe.quantum.crm.domain.empleados.dto.LoginResponse
import pe.quantum.crm.domain.empleados.dto.RefreshResponse
import pe.quantum.crm.domain.empleados.dto.toDto
import pe.quantum.crm.shared.ApiResponse
import pe.quantum.crm.shared.exception.CredencialesInvalidasException
import pe.quantum.crm.shared.exception.DemasiadosIntentosException
import pe.quantum.crm.shared.exception.NoEncontradoException

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
        // Solo un token de tipo refresh renueva la sesion: un access token en esta
        // cookie no debe alargarla.
        val principal =
            refreshToken?.let { jwtService.validate(it, TipoToken.REFRESH) }
                ?: throw CredencialesInvalidasException()
        val empleado = empleadoActivoDe(principal.empleadoId)
        // token_version desactualizada = sesion revocada por logout o cambio de
        // contraseña (B0.9): misma credencial muerta que un empleado inactivo.
        if (principal.tokenVersion != empleado.tokenVersion) {
            throw CredencialesInvalidasException()
        }

        emitAuthCookies(empleado, response)
        return ApiResponse.ok(RefreshResponse(expiresIn = jwtProperties.accessExpirationMs / MILLIS_PER_SECOND))
    }

    /**
     * Cierra sesion (B0.9). Idempotente a proposito: responde 204 siempre, con o
     * sin sesion valida, para que el cierre de sesion nunca pueda fallar. Si trae
     * un refresh token vigente, revoca la sesion en servidor (incrementa
     * `token_version`) ademas de limpiar las cookies; si no, solo limpia cookies.
     */
    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun logout(
        @CookieValue(name = AuthCookieFactory.REFRESH_TOKEN_COOKIE, required = false) refreshToken: String?,
        response: HttpServletResponse,
    ) {
        refreshToken
            ?.let { jwtService.validate(it, TipoToken.REFRESH) }
            ?.let { empleadoService.revocarSesiones(it.empleadoId) }

        response.addHeader(HttpHeaders.SET_COOKIE, cookieFactory.expiredAccessTokenCookie().toString())
        response.addHeader(HttpHeaders.SET_COOKIE, cookieFactory.expiredRefreshTokenCookie().toString())
    }

    /**
     * Cambio de contraseña del usuario autenticado. Vive bajo `/auth` por afinidad
     * de dominio, pero a diferencia del resto de las rutas de auth EXIGE
     * autenticacion: ver el matcher explicito en SecurityConfig.
     *
     * `cambiarContrasena` incrementa `token_version` para invalidar el refresh
     * token de cualquier OTRA sesion abierta (B0.9). Sin reemitir cookies aqui,
     * esa misma revocacion tumbaria tambien la sesion que acaba de hacer el
     * cambio en su proximo refresh — se reemiten con la version ya actualizada
     * para que la sesion actual siga viva.
     */
    @PostMapping("/cambiar-contrasena")
    fun cambiarContrasena(
        @Valid @RequestBody request: CambiarContrasenaRequest,
        authentication: Authentication,
        response: HttpServletResponse,
    ): ApiResponse<Unit> {
        val id = authentication.principal as Long
        empleadoService.cambiarContrasena(id, request.passwordActual, request.passwordNueva)
        emitAuthCookies(empleadoService.porId(id), response)
        return ApiResponse.ok(Unit)
    }

    /**
     * Token valido apuntando a un empleado que ya no existe es una credencial
     * muerta (401), no un recurso ausente (404): el 404 filtraba ademas que ese id
     * existio alguna vez.
     */
    @Suppress("SwallowedException") // Traduce NoEncontradoException a la excepcion correcta a proposito.
    private fun empleadoActivoDe(idEmpleado: Long): Empleado {
        val empleado =
            try {
                empleadoService.porId(idEmpleado)
            } catch (ex: NoEncontradoException) {
                throw CredencialesInvalidasException()
            }
        if (!empleado.activo) {
            throw CredencialesInvalidasException()
        }
        return empleado
    }

    private fun emitAuthCookies(
        empleado: Empleado,
        response: HttpServletResponse,
    ) {
        val id = requireNotNull(empleado.id)
        val access = jwtService.generateAccessToken(id, empleado.rol.name)
        val refresh = jwtService.generateRefreshToken(id, empleado.tokenVersion)
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
