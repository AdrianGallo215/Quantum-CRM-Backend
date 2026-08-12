package pe.quantum.crm.config.security

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

private class MutableClock(var current: Instant) : Clock() {
    override fun instant(): Instant = current

    override fun getZone(): ZoneId = ZoneOffset.UTC

    override fun withZone(zone: ZoneId?): Clock = this
}

/**
 * Tests del rate limiting de login (B0.8, SECURITY-backend.md §8): maximo de
 * intentos fallidos por ventana, luego bloqueo temporal.
 */
class LoginRateLimiterTest {
    private val clock = MutableClock(Instant.parse("2026-07-01T00:00:00Z"))
    private val limiter = LoginRateLimiter(maxAttempts = 5, window = Duration.ofMinutes(15), clock = clock)

    @Test
    fun `permite hasta el maximo menos uno sin bloquear`() {
        repeat(4) { limiter.recordFailure("ana@quantum.pe") }

        assertThat(limiter.isBlocked("ana@quantum.pe")).isFalse()
    }

    @Test
    fun `bloquea al alcanzar el maximo de intentos fallidos`() {
        repeat(5) { limiter.recordFailure("ana@quantum.pe") }

        assertThat(limiter.isBlocked("ana@quantum.pe")).isTrue()
    }

    @Test
    fun `un reset por login exitoso limpia el bloqueo`() {
        repeat(5) { limiter.recordFailure("ana@quantum.pe") }

        limiter.reset("ana@quantum.pe")

        assertThat(limiter.isBlocked("ana@quantum.pe")).isFalse()
    }

    @Test
    fun `el bloqueo expira al pasar la ventana`() {
        repeat(5) { limiter.recordFailure("ana@quantum.pe") }
        assertThat(limiter.isBlocked("ana@quantum.pe")).isTrue()

        clock.current = clock.current.plus(Duration.ofMinutes(16))

        assertThat(limiter.isBlocked("ana@quantum.pe")).isFalse()
    }

    @Test
    fun `retryAfterSeconds es positivo mientras esta bloqueado`() {
        repeat(5) { limiter.recordFailure("ana@quantum.pe") }

        val retry = limiter.retryAfterSeconds("ana@quantum.pe")

        assertThat(retry).isGreaterThan(0).isLessThanOrEqualTo(Duration.ofMinutes(15).seconds)
    }

    @Test
    fun `claves distintas no interfieren`() {
        repeat(5) { limiter.recordFailure("ana@quantum.pe") }

        assertThat(limiter.isBlocked("otro@quantum.pe")).isFalse()
    }

    @Test
    fun `una entrada caducada se purga al consultarla`() {
        limiter.recordFailure("ana@quantum.pe")
        assertThat(limiter.clavesEnSeguimiento()).isEqualTo(1)

        clock.current = clock.current.plus(Duration.ofMinutes(16))
        limiter.isBlocked("ana@quantum.pe")

        assertThat(limiter.clavesEnSeguimiento()).isZero()
    }

    @Test
    fun `el mapa no crece de forma ilimitada ante emails aleatorios`() {
        val acotado = LoginRateLimiter(maxAttempts = 5, window = Duration.ofMinutes(15), clock = clock, maxEntries = 100)

        repeat(10_000) { i -> acotado.recordFailure("atacante-$i@example.com") }

        assertThat(acotado.clavesEnSeguimiento()).isLessThanOrEqualTo(100)
    }

    @Test
    fun `una clave bajo ataque sostenido conserva su bloqueo pese al flood`() {
        val acotado = LoginRateLimiter(maxAttempts = 5, window = Duration.ofMinutes(15), clock = clock, maxEntries = 100)
        repeat(5) { acotado.recordFailure("victima@quantum.pe") }

        repeat(1_000) { i ->
            acotado.recordFailure("ruido-$i@example.com")
            // La victima sigue siendo consultada en cada intento real contra ella.
            acotado.isBlocked("victima@quantum.pe")
        }

        assertThat(acotado.isBlocked("victima@quantum.pe")).isTrue()
    }

    /**
     * El LRU puro desalojaba por antiguedad de acceso sin mirar si la clave estaba
     * bloqueada: un flood de emails inventados expulsaba a la victima real y le
     * levantaba el bloqueo antes de que expirara su ventana. A diferencia del test de
     * arriba, aqui la victima NO se vuelve a consultar durante el flood: es el caso
     * real de un atacante que solo escribe, sin refrescar el acceso de la victima.
     */
    @Test
    fun `un flood de claves nuevas no levanta el bloqueo de la clave atacada aunque no se le vuelva a consultar`() {
        val limiter = LoginRateLimiter(clock = clock, maxEntries = 10)
        repeat(5) { limiter.recordFailure("victima@quantum.pe") }
        assertThat(limiter.isBlocked("victima@quantum.pe")).isTrue()

        repeat(200) { i -> limiter.recordFailure("relleno-$i@quantum.pe") }

        assertThat(limiter.isBlocked("victima@quantum.pe")).isTrue()
    }

    /** La cota de memoria sigue siendo dura: proteger a las bloqueadas no puede volver el mapa ilimitado. */
    @Test
    fun `el mapa sigue acotado durante el flood`() {
        val limiter = LoginRateLimiter(clock = clock, maxEntries = 10)

        repeat(200) { i -> limiter.recordFailure("relleno-$i@quantum.pe") }

        assertThat(limiter.clavesEnSeguimiento()).isLessThanOrEqualTo(10)
    }

    /** Caso patologico: si TODAS las claves vigentes estan bloqueadas, la cota manda igual. */
    @Test
    fun `con todas las claves bloqueadas la cota de memoria sigue mandando`() {
        val limiter = LoginRateLimiter(clock = clock, maxEntries = 10)

        repeat(50) { i -> repeat(5) { limiter.recordFailure("atacada-$i@quantum.pe") } }

        assertThat(limiter.clavesEnSeguimiento()).isLessThanOrEqualTo(10)
    }
}
