package pe.quantum.crm.config.security

import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * Rate limiting de login en memoria (SECURITY-backend.md §8): hasta `maxAttempts`
 * intentos fallidos por `window`; al alcanzarlos la clave queda bloqueada hasta que
 * la ventana expira. Un login exitoso limpia el contador (`reset`).
 *
 * La clave es el email (o IP) del intento. Almacen en memoria: suficiente para el
 * MVP; escalar a Redis si se distribuye. `Clock` inyectable para tests.
 */
@Component
class LoginRateLimiter(
    private val maxAttempts: Int = MAX_ATTEMPTS,
    private val window: Duration = Duration.ofMinutes(WINDOW_MINUTES),
    private val clock: Clock = Clock.systemUTC(),
) {
    private data class Attempts(
        val count: Int,
        val windowStart: Instant,
    )

    private val byKey = ConcurrentHashMap<String, Attempts>()

    fun isBlocked(key: String): Boolean = (activeAttempts(key)?.count ?: 0) >= maxAttempts

    fun recordFailure(key: String) {
        val now = clock.instant()
        byKey.compute(key) { _, existing ->
            if (existing == null || windowExpired(existing, now)) {
                Attempts(count = 1, windowStart = now)
            } else {
                existing.copy(count = existing.count + 1)
            }
        }
    }

    fun reset(key: String) {
        byKey.remove(key)
    }

    fun retryAfterSeconds(key: String): Long {
        val attempts = activeAttempts(key)
        return if (attempts == null || attempts.count < maxAttempts) {
            0
        } else {
            val remaining = window - Duration.between(attempts.windowStart, clock.instant())
            remaining.seconds.coerceAtLeast(0)
        }
    }

    /** Intentos vigentes (dentro de la ventana), o null si expiraron. */
    private fun activeAttempts(key: String): Attempts? {
        val attempts = byKey[key] ?: return null
        return if (windowExpired(attempts, clock.instant())) null else attempts
    }

    private fun windowExpired(
        attempts: Attempts,
        now: Instant,
    ): Boolean = now.isAfter(attempts.windowStart.plus(window))

    private companion object {
        const val MAX_ATTEMPTS = 5
        const val WINDOW_MINUTES = 15L
    }
}
