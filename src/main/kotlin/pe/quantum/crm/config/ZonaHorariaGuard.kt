package pe.quantum.crm.config

import jakarta.annotation.PostConstruct
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * La garantia de que las columnas `TIMESTAMP` contienen UTC depende solo de que la
 * JVM corra con `TZ=UTC` (Dockerfile). Cualquier escritura hecha fuera de ese
 * contenedor —`bootRun` local, un script de mantenimiento corrido en Lima— mete
 * hora local en columnas que el resto del codigo asume UTC sin comprobarlo, un
 * corruptor de datos silencioso.
 *
 * Arreglar esto de raiz (TIMESTAMPTZ o `Instant` en las entidades) toca el esquema
 * y esta fuera de alcance de esta tanda. Esta guarda convierte el sintoma en un
 * fallo de arranque inmediato: `app.exigir-utc=false` lo desactiva para quien
 * quiera correr `bootRun` local sin `-Duser.timezone=UTC` (por defecto exige UTC).
 */
@Component
class ZonaHorariaGuard(
    @Value("\${app.exigir-utc:true}") private val exigirUtc: Boolean,
) {
    private val log = LoggerFactory.getLogger(ZonaHorariaGuard::class.java)

    @PostConstruct
    fun verificar() {
        val zona = ZoneId.systemDefault()
        if (!esUtc(zona)) {
            val mensaje = mensajeError(zona)
            if (exigirUtc) {
                throw IllegalStateException(mensaje)
            }
            log.warn(mensaje)
        }
    }

    internal companion object {
        /** Funcion pura, verificable sin arrancar Spring ni depender de la zona real de la maquina. */
        fun esUtc(zona: ZoneId): Boolean = zona.rules.getOffset(Instant.now()) == ZoneOffset.UTC

        fun mensajeError(zona: ZoneId): String =
            "La JVM no esta en UTC (zona actual: $zona). Las columnas TIMESTAMP del esquema asumen UTC " +
                "(ver Dockerfile ENV TZ=UTC); arrancar fuera de UTC corrompe silenciosamente esas columnas. " +
                "Arranca con -Duser.timezone=UTC, o desactiva esta guarda explicitamente con app.exigir-utc=false " +
                "si sabes lo que haces."
    }
}
