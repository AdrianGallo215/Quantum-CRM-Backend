package pe.quantum.crm.shared

import java.time.ZoneId

/**
 * Calendario del negocio. Las columnas `DATE` (`fecha_estimada`,
 * `fecha_seguimiento`, `fecha_cierre_estimado`) son dias del calendario de Lima, no
 * instantes: ver el KDoc de `TiempoUtc.kt`, que explica la asimetria.
 *
 * Compararlas contra `LocalDate.now()` en una JVM con TZ=UTC adelanta el dia a
 * partir de las 19:00 de Lima. El vendedor recibia "evento vencido" con cinco horas
 * de dia habil por delante y, por el dedup permanente de recordatorios, no volvia a
 * recibirlo nunca.
 */
val ZONA_PERU: ZoneId = ZoneId.of("America/Lima")
