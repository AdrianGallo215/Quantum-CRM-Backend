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
}
