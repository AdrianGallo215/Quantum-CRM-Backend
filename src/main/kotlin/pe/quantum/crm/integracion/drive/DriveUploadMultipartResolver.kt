package pe.quantum.crm.integracion.drive

import jakarta.servlet.http.HttpServletRequest
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.multipart.MultipartResolver
import org.springframework.web.multipart.support.StandardServletMultipartResolver

/**
 * Resolver de multipart que se aparta en las rutas de subida a Drive.
 *
 * El motivo es concreto: si Spring resuelve el multipart, Tomcat parsea el cuerpo y
 * vuelca cada parte a un archivo temporal en disco ANTES de que el controller vea
 * nada (`file-size-threshold` es 0 por defecto). Declarando "esto no es multipart"
 * para esas rutas, el cuerpo llega intacto al controller, que lo parsea en streaming
 * y lo enchufa directo al upload de Drive: ni disco ni RAM proporcional al archivo.
 *
 * El resto de la aplicacion (p. ej. `import-csv-temp`) sigue usando `MultipartFile`
 * con el comportamiento estandar.
 */
class DriveUploadMultipartResolver : StandardServletMultipartResolver() {
    override fun isMultipart(request: HttpServletRequest): Boolean =
        if (esRutaDeSubidaEnStreaming(request)) false else super.isMultipart(request)

    private fun esRutaDeSubidaEnStreaming(request: HttpServletRequest): Boolean {
        val ruta = request.servletPath.ifEmpty { request.requestURI.orEmpty() }
        return RUTAS_EN_STREAMING.any { it.matches(ruta) }
    }

    private companion object {
        /** `POST /api/v1/{empresas,oportunidades}/{id}/archivos`. */
        val RUTAS_EN_STREAMING =
            listOf(
                Regex("^/api/v1/empresas/\\d+/archivos/?$"),
                Regex("^/api/v1/oportunidades/\\d+/archivos/?$"),
            )
    }
}

@Configuration
class MultipartStreamingConfig {
    /**
     * El nombre del bean debe ser exactamente `multipartResolver`: es el que busca
     * `DispatcherServlet`. Reemplaza al que registra la autoconfiguracion de Spring
     * Boot, que es condicional a que no exista otro.
     */
    @Bean(name = ["multipartResolver"])
    fun multipartResolver(): MultipartResolver = DriveUploadMultipartResolver()
}
