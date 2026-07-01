package pe.quantum.crm.config.security

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Configuracion del JWT, desde variables de entorno (SECURITY-backend.md §2.2).
 * El secreto NUNCA se hardcodea: viene de `JWT_SECRET` (minimo 256 bits).
 */
@ConfigurationProperties(prefix = "app.security.jwt")
data class JwtProperties(
    val secret: String,
    val accessExpirationMs: Long,
    val refreshExpirationMs: Long,
)
