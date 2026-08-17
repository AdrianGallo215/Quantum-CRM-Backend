package pe.quantum.crm.config.security

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.filter.OncePerRequestFilter

/**
 * Autoridad que marca una sesion con el cambio de contraseña inicial pendiente
 * (B1.4). Sin prefijo `ROLE_` a proposito: no debe participar en `hasRole`.
 */
const val CAMBIO_CONTRASENA_PENDIENTE_AUTHORITY = "CAMBIO_CONTRASENA_PENDIENTE"

/**
 * Filtro que autentica cada request a partir del JWT (SECURITY-backend.md §2, §3).
 *
 * Lee el access token de la cookie httpOnly `access_token` (o del header
 * `Authorization: Bearer` como alternativa para clientes no-navegador). Si el token
 * es valido, coloca la autenticacion en el contexto con la autoridad `ROLE_<rol>`.
 * Si es invalido o falta, no autentica: el filtro es stateless y no lanza; la
 * decision de rechazar la toma la cadena de seguridad (401).
 *
 * Solo autentica tokens de tipo `access`: un refresh token (vida de 7 dias) no vale
 * como credencial de acceso. Y solo si el token trae un `rol` utilizable: una
 * autenticacion sin authorities satisface `anyRequest().authenticated()` y daria paso
 * a todo endpoint sin `@PreAuthorize`, asi que nunca debe llegar a la cadena.
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
            val principal = jwtService.validate(token, TipoToken.ACCESS)
            val rol = principal?.rol?.takeIf { it.isNotBlank() }
            if (principal != null && rol != null) {
                // La autoridad extra (sin prefijo ROLE_, no participa en hasRole) es
                // como CambioContrasenaPendienteFilter sabe que hay un cambio de
                // contraseña pendiente sin volver a parsear el token.
                val authorities =
                    buildList {
                        add(SimpleGrantedAuthority("ROLE_$rol"))
                        if (principal.requiereCambioContrasena) {
                            add(SimpleGrantedAuthority(CAMBIO_CONTRASENA_PENDIENTE_AUTHORITY))
                        }
                    }
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
