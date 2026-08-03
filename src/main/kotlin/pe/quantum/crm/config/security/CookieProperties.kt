package pe.quantum.crm.config.security

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Configuracion de las cookies de auth. `secure` es true por defecto (obligatorio
 * en produccion, SECURITY §2.1); solo se pone en false para desarrollo local sobre
 * http, donde una cookie Secure no viajaria.
 */
@ConfigurationProperties(prefix = "app.security.cookie")
data class CookieProperties(
    val secure: Boolean = true,
)
