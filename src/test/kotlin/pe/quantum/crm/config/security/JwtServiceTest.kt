package pe.quantum.crm.config.security

import io.jsonwebtoken.Jwts
import io.jsonwebtoken.security.Keys
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.Date

/**
 * Tests unitarios de generacion/validacion de JWT (B0.7, criterio de aceptacion).
 * No requiere Spring ni Docker: instancia el servicio con propiedades de prueba.
 */
class JwtServiceTest {
    private val secret = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
    private val props =
        JwtProperties(
            secret = secret,
            accessExpirationMs = Duration.ofHours(1).toMillis(),
            refreshExpirationMs = Duration.ofDays(7).toMillis(),
        )
    private val service = JwtService(props)

    /** Token legado: firmado con el mismo secreto pero sin el claim `typ`. */
    private fun tokenSinClaimDeTipo(empleadoId: Long): String =
        Jwts.builder()
            .subject(empleadoId.toString())
            .claim("rol", "admin")
            .issuedAt(Date.from(Instant.now()))
            .expiration(Date.from(Instant.now().plusMillis(props.accessExpirationMs)))
            .signWith(Keys.hmacShaKeyFor(secret.toByteArray()))
            .compact()

    @Test
    fun `un access token valido devuelve el id y rol del empleado`() {
        val token = service.generateAccessToken(empleadoId = 42, rol = "jdv")

        val principal = service.validate(token, TipoToken.ACCESS)

        assertThat(principal).isNotNull
        assertThat(principal!!.empleadoId).isEqualTo(42)
        assertThat(principal.rol).isEqualTo("jdv")
    }

    @Test
    fun `un refresh token valido devuelve el id del empleado`() {
        val token = service.generateRefreshToken(empleadoId = 7)

        val principal = service.validate(token, TipoToken.REFRESH)

        assertThat(principal).isNotNull
        assertThat(principal!!.empleadoId).isEqualTo(7)
    }

    @Test
    fun `un refresh token no sirve como access token`() {
        val refresh = service.generateRefreshToken(empleadoId = 7)

        assertThat(service.validate(refresh, TipoToken.ACCESS)).isNull()
    }

    @Test
    fun `un access token no sirve como refresh token`() {
        val access = service.generateAccessToken(empleadoId = 7, rol = "admin")

        assertThat(service.validate(access, TipoToken.REFRESH)).isNull()
    }

    @Test
    fun `un token sin claim de tipo es rechazado como access y como refresh`() {
        val legado = tokenSinClaimDeTipo(empleadoId = 3)

        assertThat(service.validate(legado, TipoToken.ACCESS)).isNull()
        assertThat(service.validate(legado, TipoToken.REFRESH)).isNull()
    }

    @Test
    fun `un token firmado con otro secreto es rechazado`() {
        val otro =
            JwtService(
                props.copy(secret = "ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff"),
            )
        val token = otro.generateAccessToken(empleadoId = 1, rol = "admin")

        assertThat(service.validate(token, TipoToken.ACCESS)).isNull()
    }

    @Test
    fun `un token expirado es rechazado`() {
        val relojEnElPasado = Clock.fixed(Instant.now().minus(Duration.ofHours(2)), ZoneOffset.UTC)
        val servicioPasado = JwtService(props, relojEnElPasado)
        val tokenExpirado = servicioPasado.generateAccessToken(empleadoId = 1, rol = "admin")

        assertThat(service.validate(tokenExpirado, TipoToken.ACCESS)).isNull()
    }

    @Test
    fun `un token manipulado es rechazado`() {
        val token = service.generateAccessToken(empleadoId = 1, rol = "admin")
        val manipulado = token.dropLast(4) + "AAAA"

        assertThat(service.validate(manipulado, TipoToken.ACCESS)).isNull()
    }

    @Test
    fun `un texto que no es un JWT es rechazado`() {
        assertThat(service.validate("no-es-un-token", TipoToken.ACCESS)).isNull()
    }

    @Test
    fun `el access token expira segun la configuracion`() {
        val antes = Instant.now()
        val token = service.generateAccessToken(empleadoId = 1, rol = "jdv")

        val expiracion = service.expiresAt(token)

        assertThat(expiracion)
            .isBetween(antes.plusMillis(props.accessExpirationMs - 5000), antes.plusMillis(props.accessExpirationMs + 5000))
    }
}
