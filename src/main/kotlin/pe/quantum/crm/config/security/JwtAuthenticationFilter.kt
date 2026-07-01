package pe.quantum.crm.config.security

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.filter.OncePerRequestFilter

/**
 * Filtro que autentica cada request a partir del JWT (SECURITY-backend.md §2, §3).
 *
 * Lee el access token de la cookie httpOnly `access_token` (o del header
 * `Authorization: Bearer` como alternativa para clientes no-navegador). Si el token
 * es valido, coloca la autenticacion en el contexto con la autoridad `ROLE_<rol>`.
 * Si es invalido o falta, no autentica: el filtro es stateless y no lanza; la
 * decision de rechazar la toma la cadena de seguridad (401).
 */
class JwtAuthenticationFilter(
    private val jwtService: JwtService,
) : OncePerRequestFilter() {
    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val token = extractToken(request)
        if (token != null && SecurityContextHolder.getContext().authentication == null) {
            val principal = jwtService.validate(token)
            if (principal != null) {
                val authorities =
                    principal.rol
                        ?.let { listOf(SimpleGrantedAuthority("ROLE_$it")) }
                        .orEmpty()
                val authentication =
                    UsernamePasswordAuthenticationToken(principal.empleadoId, null, authorities)
                SecurityContextHolder.getContext().authentication = authentication
            }
        }
        filterChain.doFilter(request, response)
    }

    private fun extractToken(request: HttpServletRequest): String? {
        request.cookies
            ?.firstOrNull { it.name == AuthCookieFactory.ACCESS_TOKEN_COOKIE }
            ?.value
            ?.takeIf { it.isNotBlank() }
            ?.let { return it }

        val header = request.getHeader("Authorization")
        return if (header != null && header.startsWith(BEARER_PREFIX)) {
            header.substring(BEARER_PREFIX.length)
        } else {
            null
        }
    }

    private companion object {
        const val BEARER_PREFIX = "Bearer "
    }
}
