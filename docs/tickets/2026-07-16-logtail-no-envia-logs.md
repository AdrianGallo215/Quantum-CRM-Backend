# Logtail (Better Stack) no entrega logs pese a configuración aparentemente correcta

**Estado:** abierto — pendiente de retomar.
**Prioridad:** baja (no bloquea desarrollo local; `!production` sigue logueando a consola con normalidad).

## Síntoma

Con `SPRING_PROFILES_ACTIVE=production` y `LOGTAIL_SOURCE_TOKEN`/`LOGTAIL_INGEST_HOST` reales en `.env`, la app corre y loguea normalmente a consola, pero **ningún log llega al dashboard de Better Stack** (Live tail vacío).

## Confirmado

- La dependencia `com.logtail:logback-logtail:0.3.4` resuelve correctamente (`./gradlew dependencies`) y el JAR contiene `com.logtail.logback.LogtailAppender` (verificado inspeccionando el `.jar` directamente).
- `MdcLoggingFilter` se ejecuta en cada request, en el orden correcto (`addFilterAfter(MdcLoggingFilter(), JwtAuthenticationFilter::class.java)` — confirmado viendo el stack trace real de una request).
- El appender `Console` funciona (logs visibles en consola con el patrón configurado).
- El usuario probó el `curl` de prueba que da Better Stack directamente contra el mismo Source (mismo token/host) desde su terminal, **y sí llega** — descarta token inválido, host incorrecto o bloqueo de red/firewall a nivel de esa máquina.
- Se agregó un `statusListener` (`OnConsoleStatusListener`) a `logback-spring.xml` para que Logback deje de fallar en silencio si el appender `Logtail` no puede conectar. **No confirmado si la app ya se reinició con este cambio** — pendiente verificar qué imprime, si algo, la próxima vez que se pruebe.

## Sin confirmar / hipótesis a investigar

1. **¿Se reinició la app después de agregar el `statusListener`?** Si no, no hay forma de haber visto su salida todavía. Es el primer paso a repetir.
2. `LogtailAppender` podría tener un envío asíncrono/por lotes con un intervalo de flush — si el flush no se dispara por poco volumen de logs o por timeout de conexión, podría no verse nada sin que sea un error explícito.
3. El formato de `ingestUrl` en `logback-spring.xml` es `https://${LOGTAIL_INGEST_HOST}` — confirmar contra la documentación real de `logback-logtail` 0.3.4 si esa librería espera la URL completa con el prefijo `https://` ya incluido en la variable, o si lo antepone ella misma (podría estar duplicando o interpretando mal el esquema).
4. Confirmar si `LogtailAppender` requiere un `<encoder>` explícito (no se configuró ninguno, asumiendo que serializa el `ILoggingEvent` internamente) — revisar el código fuente/README real de la librería, no solo el nombre de sus setters.
5. Comparar exactamente el `curl` de prueba que Better Stack sugiere (headers, endpoint, body) contra lo que realmente hace `LogtailAppender` internamente — puede que la librería use un endpoint o formato distinto al que arma el curl de onboarding.

## Próximo paso sugerido

Repetir la prueba con la app reiniciada (para que tome el `statusListener`), generar una request, y revisar la consola completa (no solo el log de negocio) buscando cualquier línea con `Logtail`, `WARN`/`ERROR` de Logback, o excepciones de red. Si sigue sin haber ninguna pista, considerar instrumentar `LogtailAppender` con un debugger o revisar su código fuente (es una librería pequeña, 0.3.4) para ver dónde podría estar fallando silenciosamente el envío.
