package pe.quantum.crm.config.security

import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Duration
import java.time.Instant

/**
 * Rate limiting de login en memoria (SECURITY-backend.md §8): hasta `maxAttempts`
 * intentos fallidos por `window`; al alcanzarlos la clave queda bloqueada hasta que
 * la ventana expira. Un login exitoso limpia el contador (`reset`).
 *
 * La clave es el email (o IP) del intento. Almacen en memoria: suficiente para el
 * MVP; escalar a Redis si se distribuye. `Clock` inyectable para tests.
 *
 * El almacen esta acotado en memoria por dos mecanismos, porque `/auth/login` es
 * publico y un atacante puede inventar emails distintos sin limite:
 *
 * 1. Purga perezosa: al consultar una clave cuya ventana ya expiro, se elimina.
 * 2. Cota dura: el mapa es un LRU (`LinkedHashMap` en modo acceso) de `maxEntries`
 *    entradas; al superarla se descarta la menos usada recientemente.
 *
 * Se prefiere la cota LRU a un barrido periodico porque no necesita hilo ni
 * dependencia extra y acota la memoria pase lo que pase: un barrido solo acota a
 * "intentos de la ultima ventana", que ante un flood sostenido sigue siendo
 * ilimitado. El modo acceso hace que la clave realmente atacada se refresque en
 * cada intento contra ella y no sea la candidata a desalojo.
 */
@Component
class LoginRateLimiter(
    private val maxAttempts: Int = MAX_ATTEMPTS,
    private val window: Duration = Duration.ofMinutes(WINDOW_MINUTES),
    private val clock: Clock = Clock.systemUTC(),
    maxEntries: Int = MAX_ENTRIES,
) {
    private data class Attempts(
        val count: Int,
        val windowStart: Instant,
    )

    // Sin sincronizar por si mismo: todo acceso va dentro de `synchronized(byKey)`.
    private val byKey =
        object : LinkedHashMap<String, Attempts>(INITIAL_CAPACITY, LOAD_FACTOR, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Attempts>): Boolean = size > maxEntries
        }

    fun isBlocked(key: String): Boolean = (activeAttempts(key)?.count ?: 0) >= maxAttempts

    fun recordFailure(key: String) {
        val now = clock.instant()
        synchronized(byKey) {
            val existing = byKey[key]
            byKey[key] =
                if (existing == null || windowExpired(existing, now)) {
                    Attempts(count = 1, windowStart = now)
                } else {
                    existing.copy(count = existing.count + 1)
                }
        }
    }

    fun reset(key: String) {
        synchronized(byKey) { byKey.remove(key) }
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

    /** Numero de claves en seguimiento. Solo para tests: verifica la cota del mapa. */
    internal fun clavesEnSeguimiento(): Int = synchronized(byKey) { byKey.size }

    /** Intentos vigentes (dentro de la ventana), o null si expiraron; en tal caso purga la entrada. */
    private fun activeAttempts(key: String): Attempts? {
        val now = clock.instant()
        return synchronized(byKey) {
            val attempts = byKey[key]
            when {
                attempts == null -> null
                windowExpired(attempts, now) -> {
                    byKey.remove(key)
                    null
                }
                else -> attempts
            }
        }
    }

    private fun windowExpired(
        attempts: Attempts,
        now: Instant,
    ): Boolean = now.isAfter(attempts.windowStart.plus(window))

    private companion object {
        const val MAX_ATTEMPTS = 5
        const val WINDOW_MINUTES = 15L

        /**
         * Techo de claves vigiladas. Con 10 000 entradas el mapa ocupa del orden de
         * un MB: suficiente para el trafico legitimo del CRM y despreciable para el
         * proceso.
         */
        const val MAX_ENTRIES = 10_000
        const val INITIAL_CAPACITY = 256
        const val LOAD_FACTOR = 0.75f
    }
}
