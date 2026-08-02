package pe.quantum.crm.config.security

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.MDC
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.filter.OncePerRequestFilter
import java.util.UUID

/**
 * Enriquece el MDC con contexto de correlacion para logs tecnicos (DEVOPS-backend.md,
 * seccion Logging). Se registra despues de [JwtAuthenticationFilter] (ver
 * `SecurityConfig`) para que el `SecurityContext` ya este poblado cuando este
 * filtro se ejecuta.
 *
 * `traceId`: identificador unico por request, para correlacionar todas las lineas
 * de log de una misma peticion.
 * `usuario`: el id del empleado autenticado (`anonimo` si la request no tiene
 * autenticacion). El principal en este proyecto es solo el id (`Long`, ver
 * [JwtAuthenticationFilter]); no hay un nombre disponible en el SecurityContext.
 * `modulo`: el segundo segmento de la URI despues de `/api/` (p. ej.
 * `/api/v1/oportunidades/123` -> "oportunidades").
 */
class MdcLoggingFilter : OncePerRequestFilter() {
    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        try {
            MDC.put(MDC_TRACE_ID, UUID.randomUUID().toString())
            MDC.put(MDC_USUARIO, usuarioActual())
            MDC.put(MDC_MODULO, moduloDe(request.requestURI))
            filterChain.doFilter(request, response)
        } finally {
            MDC.clear()
        }
    }

    private fun usuarioActual(): String {
        val authentication = SecurityContextHolder.getContext().authentication
        if (authentication == null || !authentication.isAuthenticated || authentication.principal == ANONYMOUS_PRINCIPAL) {
            return USUARIO_ANONIMO
        }
        return authentication.principal.toString()
    }

    private fun moduloDe(uri: String): String {
        val segmentos = uri.split("/").filter { it.isNotBlank() }
        val idxApi = segmentos.indexOf("api")
        return if (idxApi >= 0 && segmentos.size > idxApi + 2) segmentos[idxApi + 2] else MODULO_DESCONOCIDO
    }

    private companion object {
        const val MDC_TRACE_ID = "traceId"
        const val MDC_USUARIO = "usuario"
        const val MDC_MODULO = "modulo"
        const val USUARIO_ANONIMO = "anonimo"
        const val MODULO_DESCONOCIDO = "desconocido"
        const val ANONYMOUS_PRINCIPAL = "anonymousUser"
    }
}
