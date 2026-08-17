package pe.quantum.crm.config.security

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.HttpMethod
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.filter.OncePerRequestFilter
import org.springframework.web.servlet.HandlerExceptionResolver
import pe.quantum.crm.shared.exception.CambioContrasenaRequeridoException

/**
 * Defensa en profundidad del cambio de contraseña inicial (B1.4,
 * SECURITY-backend.md §2.3).
 *
 * `requiere_cambio_contrasena` nace en `true` para todo empleado creado por un
 * admin. Hasta ahora era solo una sugerencia al cliente: el frontend redirigia al
 * formulario de cambio, pero nada impedia a alguien con la contraseña temporal
 * ignorar la redireccion (o recargar la pagina) y seguir usando la API. Este filtro
 * mueve la regla al backend, que es la unica frontera de seguridad real.
 *
 * Corre despues de [JwtAuthenticationFilter], que ya dejo la autoridad
 * [CAMBIO_CONTRASENA_PENDIENTE_AUTHORITY] en el contexto si el access token la
 * traia. Solo afecta a requests ya autenticadas: las rutas publicas
 * (`/auth/login`, `/auth/refresh`) no tienen contexto y pasan de largo.
 *
 * Las exenciones son las minimas para poder cumplir con el cambio: cambiar la
 * contraseña, cerrar sesion y leer el propio perfil (el frontend restaura la sesion
 * con `/empleados/me` en cada carga y necesita ver el flag para redirigir).
 *
 * El error se delega al [HandlerExceptionResolver] en vez de escribirse a mano:
 * un filtro corre fuera del DispatcherServlet, asi que sin esto el
 * `GlobalExceptionHandler` no lo veria y la respuesta no llevaria el envelope de
 * error del contrato (contrato_api.md §2).
 */
class CambioContrasenaPendienteFilter(
    private val handlerExceptionResolver: HandlerExceptionResolver,
) : OncePerRequestFilter() {
    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        if (tieneCambioPendiente() && !estaExento(request)) {
            handlerExceptionResolver.resolveException(
                request,
                response,
                null,
                CambioContrasenaRequeridoException(),
            )
            return
        }
        filterChain.doFilter(request, response)
    }

    private fun tieneCambioPendiente(): Boolean =
        SecurityContextHolder.getContext().authentication
            ?.authorities
            ?.any { it.authority == CAMBIO_CONTRASENA_PENDIENTE_AUTHORITY } == true

    private fun estaExento(request: HttpServletRequest): Boolean {
        val ruta = request.requestURI
        return EXENTAS_POST.any { request.method == HttpMethod.POST.name() && ruta == it } ||
            (request.method == HttpMethod.GET.name() && ruta == PERFIL_PROPIO)
    }

    private companion object {
        val EXENTAS_POST = setOf("/api/v1/auth/cambiar-contrasena", "/api/v1/auth/logout")
        const val PERFIL_PROPIO = "/api/v1/empleados/me"
    }
}
