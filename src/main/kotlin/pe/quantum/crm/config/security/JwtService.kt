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
 * Tipo de token, en el claim `typ`. Los dos tipos se firman con el mismo secreto,
 * asi que la firma por si sola no los distingue: sin este claim un refresh token
 * (7 dias de vida) se podia usar como access token contra cualquier endpoint.
 */
enum class TipoToken(val claim: String) {
    ACCESS("access"),
    REFRESH("refresh"),
}

/**
 * Genera y valida JWT firmados con HS256 (SECURITY-backend.md §2.2).
 * El secreto viene de `JwtProperties` (env, nunca hardcodeado). El access token
 * lleva `sub` (id del empleado), `rol` y `typ=access`; el refresh token lleva
 * `sub` y `typ=refresh`.
 *
 * `validate` exige el tipo esperado y es fail-safe: ante cualquier token invalido
 * (firma incorrecta, expirado, manipulado, malformado o de otro tipo) devuelve
 * `null`, nunca lanza.
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
    ): String = buildToken(empleadoId, rol, TipoToken.ACCESS, props.accessExpirationMs)

    fun generateRefreshToken(empleadoId: Long): String = buildToken(empleadoId, null, TipoToken.REFRESH, props.refreshExpirationMs)

    private fun buildToken(
        empleadoId: Long,
        rol: String?,
        tipo: TipoToken,
        expirationMs: Long,
    ): String {
        val now = clock.instant()
        return Jwts.builder()
            .subject(empleadoId.toString())
            .claim(TYPE_CLAIM, tipo.claim)
            .apply { if (rol != null) claim(ROLE_CLAIM, rol) }
            .issuedAt(Date.from(now))
            .expiration(Date.from(now.plusMillis(expirationMs)))
            .signWith(key)
            .compact()
    }

    /**
     * Valida firma, expiracion y tipo. Un token de un tipo distinto al esperado se
     * rechaza como si fuera invalido.
     *
     * Compatibilidad: los tokens emitidos antes de este claim no lo llevan y se
     * rechazan (fail-safe, SECURITY-backend.md §1.4). Aceptarlos "por compatibilidad"
     * dejaria abierta justo la brecha que se cierra: bastaria omitir `typ` para que un
     * refresh valiese como access. El coste es que las sesiones vivas al desplegar
     * tienen que volver a loguearse, asumible en un despliegue inicial.
     */
    fun validate(
        token: String,
        tipoEsperado: TipoToken,
    ): JwtPrincipal? =
        try {
            val claims = parseClaims(token)
            if (claims[TYPE_CLAIM] != tipoEsperado.claim) {
                null
            } else {
                JwtPrincipal(empleadoId = claims.subject.toLong(), rol = claims[ROLE_CLAIM] as String?)
            }
        } catch (_: JwtException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }

    fun expiresAt(token: String): Instant = parseClaims(token).expiration.toInstant()

    private fun parseClaims(token: String) = Jwts.parser().verifyWith(key).build().parseSignedClaims(token).payload

    private companion object {
        const val TYPE_CLAIM = "typ"
        const val ROLE_CLAIM = "rol"
    }
}
