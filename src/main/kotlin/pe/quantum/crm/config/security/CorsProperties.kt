package pe.quantum.crm.config.security

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Origenes permitidos por CORS, desde `CORS_ALLOWED_ORIGINS` (SECURITY-backend.md
 * §7). Restrictivo: solo el frontend. Nunca `*` con credenciales.
 */
@ConfigurationProperties(prefix = "app.security.cors")
data class CorsProperties(
    val allowedOrigins: List<String>,
)
