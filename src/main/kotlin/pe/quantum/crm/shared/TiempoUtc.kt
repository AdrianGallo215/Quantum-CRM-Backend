package pe.quantum.crm.shared

import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset

/**
 * Convierte un `LocalDateTime` de una columna `TIMESTAMP` al instante que
 * representa, para exponerlo en la API.
 *
 * Las columnas `TIMESTAMP` del schema guardan hora UTC: se escriben con
 * `LocalDateTime.now()` sobre una JVM con `TZ=UTC` (Dockerfile), y lo que llega
 * del cliente como `Instant` se normaliza con `LocalDateTime.ofInstant(it,
 * ZoneOffset.UTC)` antes de persistirse. Esta funcion es la lectura simetrica de
 * esa escritura.
 *
 * Por que existe y por que es por campo: un `LocalDateTime` NO tiene zona.
 * Serializarlo tal cual produce `"2026-06-19T14:00:00"`, que el navegador
 * interpreta como hora LOCAL (spec de ECMAScript para `new Date`) y en Lima
 * (UTC-5) pinta cinco horas de mas. Configurar Jackson para estampar una `Z`
 * global sobre todo `LocalDateTime` arreglaria el sintoma mintiendo: afirmaria
 * UTC en cualquier campo futuro que no lo fuese. Convertir explicitamente el
 * campo que si es UTC es la unica version honesta.
 *
 * NO usar sobre columnas `DATE` (`fecha_estimada`, `fecha_seguimiento`,
 * `fecha_cierre_estimado`): son dias del calendario de Lima, no instantes, y
 * darles una hora reintroduce el mismo desfase por el otro lado.
 */
fun LocalDateTime.comoInstanteUtc(): Instant = toInstant(ZoneOffset.UTC)
