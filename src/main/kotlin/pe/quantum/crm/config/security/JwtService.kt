package pe.quantum.crm.config.security

import io.jsonwebtoken.JwtException
import io.jsonwebtoken.Jwts
import io.jsonwebtoken.security.Keys
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.Instant
import java.util.Date
import javax.crypto.SecretKey

/** Identidad extraida de un JWT valido. El refresh token no lleva rol. */
data class JwtPrincipal(
    val empleadoId: Long,
    val rol: String?,
)

/**
 * Genera y valida JWT firmados con HS256 (SECURITY-backend.md §2.2).
 * El secreto viene de `JwtProperties` (env, nunca hardcodeado). El access token
 * lleva `sub` (id del empleado) y `rol`; el refresh token solo `sub`.
 *
 * `validate` es fail-safe: ante cualquier token invalido (firma incorrecta,
 * expirado, manipulado o malformado) devuelve `null`, nunca lanza.
 */
@Service
class JwtService(
    private val props: JwtProperties,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val key: SecretKey = Keys.hmacShaKeyFor(props.secret.toByteArray())

    fun generateAccessToken(
        empleadoId: Long,
        rol: String,
    ): String = buildToken(empleadoId, rol, props.accessExpirationMs)

    fun generateRefreshToken(empleadoId: Long): String = buildToken(empleadoId, null, props.refreshExpirationMs)

    private fun buildToken(
        empleadoId: Long,
        rol: String?,
        expirationMs: Long,
    ): String {
        val now = clock.instant()
        return Jwts.builder()
            .subject(empleadoId.toString())
            .apply { if (rol != null) claim("rol", rol) }
            .issuedAt(Date.from(now))
            .expiration(Date.from(now.plusMillis(expirationMs)))
            .signWith(key)
            .compact()
    }

    fun validate(token: String): JwtPrincipal? =
        try {
            val claims = parseClaims(token)
            JwtPrincipal(empleadoId = claims.subject.toLong(), rol = claims["rol"] as String?)
        } catch (_: JwtException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }

    fun expiresAt(token: String): Instant = parseClaims(token).expiration.toInstant()

    private fun parseClaims(token: String) = Jwts.parser().verifyWith(key).build().parseSignedClaims(token).payload
}
