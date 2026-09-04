# Plan 2 — Tipo de cambio SUNAT (§12)

> **Destinatario: agentes ejecutores.** Mismas reglas que el Plan 1: si algo es ambiguo
> o el repo no coincide con lo que la tarea describe, **detente y consulta al arquitecto**.
>
> Independiente del Plan 1 y de `oportunidad_items`. Puede ejecutarse en paralelo.

---

## Fase de investigación (leer antes de la Task 1)

### Por qué este plan existe por separado

`reglas_simulaciones.md` §12 pide un job diario que consulte SUNAT y **"actualice el
valor almacenado"**, con fallback al último valor guardado. La investigación previa
encontró que **ese almacén no existe**: ninguna migración crea tabla de configuración
global, y V40 tampoco (hallazgo H6 del mapa).

§12 describe el tipo de cambio como *"variable global del CRM, no de la simulación"* y
su visualización como *"requisito del layout global, no de este módulo"*. No lo consume
el motor de cálculo ni participa de ninguna fórmula de §3. Por eso va en su propio plan:
no comparte código con simulaciones y no está bloqueado por `oportunidad_items`.

El diseño del modelo de datos de abajo es **decisión tomada por el arquitecto a petición
del dueño del producto**, no algo especificado en los documentos originales.

### Documentos que gobiernan este plan

| Documento | Qué manda |
|---|---|
| `docs/reglas_simulaciones.md` §12 | Job diario · fallback silencioso al último valor · siempre en vivo, nunca snapshot por simulación |
| `docs/contrato_api.md` §2, §25 | Envelope `{data, meta, error}`. **Toda adición de endpoint entra al changelog en el mismo PR** |
| `docs/DEVOPS-backend.md` | Variables de entorno y despliegue |
| `CLAUDE.md` | Convenciones; reglas 8, 9, 10, 11, 13 aplican aquí |
| `docs/planes/plan-00-mapa-simulaciones.md` | Decisión D4: la numeración de migraciones se asigna al desplegar |

### Reglas de `CLAUDE.md` que tocan este cambio

| Regla | Cómo aplica |
|---|---|
| **1. TDD siempre** | Tests antes del código, en todas las tareas que producen lógica |
| **8. Inyección por constructor** | `private val` en Service, job y cliente HTTP |
| **9. Relaciones JPA `LAZY`, DTOs en controllers** | La entidad `TipoCambio` no se expone: el controller devuelve DTO |
| **10. `@Transactional`** | `readOnly = true` en la lectura; `@Transactional` en el upsert del job |
| **11. Queries parametrizadas** | Sin SQL por concatenación |
| **13. Nunca secretos en código** | La **API key de Decolecta** y la URL van por variable de entorno. La key nunca se escribe en código, tests, comentarios ni documentos versionados |

### Alcance — lo que este plan NO hace

No toca simulaciones · no toca V40 · no toca ninguna tabla existente · no añade
dependencias a `build.gradle.kts` (`RestClient` ya viene en `spring-boot-starter-web`) ·
no expone el tipo de cambio dentro de ningún DTO de simulación.

---

## Diseño del modelo de datos (cerrado)

```sql
CREATE TABLE tipo_cambio (
    fecha       DATE            PRIMARY KEY,
    compra      NUMERIC(8,3)    NOT NULL,
    venta       NUMERIC(8,3)    NOT NULL,
    fuente      TEXT            NOT NULL DEFAULT 'sunat',
    created_at  TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT chk_tipo_cambio_compra_positiva CHECK (compra > 0),
    CONSTRAINT chk_tipo_cambio_venta_positiva  CHECK (venta > 0)
);
```

**Por qué histórico y no una fila única.** El fallback de §12 (*"si SUNAT no responde,
se conserva el último valor guardado, sin error visible"*) sale gratis: el valor vigente
es `ORDER BY fecha DESC LIMIT 1`, y si el job no escribió hoy, esa consulta ya devuelve
el de ayer. Una tabla de una fila exigiría lógica extra de "no pisar con nulo" y perdería
la trazabilidad. El coste es despreciable: 365 filas al año.

`fecha` como PK hace el upsert idempotente (`ON CONFLICT (fecha) DO UPDATE`): el job
puede correr dos veces el mismo día sin duplicar.

`NUMERIC(8,3)`: SUNAT publica con 3 decimales (p. ej. `3.752`).

---

## Tabla de tareas

| ID | Tarea | Modelo | Esfuerzo |
|---|---|---|---|
| S1 | Migración `tipo_cambio` (archivo, sin aplicar) | Sonnet 5 | Low |
| S2 | Entidad, repositorio y DTO | Sonnet 5 | Medium |
| S3 | Cliente HTTP de SUNAT | Opus 5 | High |
| S4 | Service con fallback + tests | Opus 5 | High |
| S5 | Job `@Scheduled` diario + tests | Sonnet 5 | Medium |
| S6 | Endpoint `GET /tipo-cambio` + test WebMvc | Sonnet 5 | Medium |
| S7 | Documentación de contrato y changelog | Sonnet 5 | Medium |
| S8 | Verificación de build | Sonnet 5 | Low |
| S9 | Revisión final del diff contra los documentos citados | Opus 5 | High |
| S10 | Despliegue de la migración a producción | Opus 5 | High |

---

## S1 · Migración `tipo_cambio`

**Modelo:** Sonnet 5 · **Esfuerzo:** Low

Crea `src/main/resources/db/migration/V41__create_tipo_cambio.sql` con exactamente el
DDL de la sección *Diseño del modelo de datos*, más:

```sql
ALTER TABLE tipo_cambio ENABLE ROW LEVEL SECURITY;

COMMENT ON TABLE tipo_cambio IS
    'Tipo de cambio PEN/USD publicado por SUNAT. Variable global del CRM, no de la simulacion (reglas_simulaciones.md §12). Historico: el valor vigente es la fila de fecha mayor, lo que da el fallback "ultimo valor guardado" sin logica extra.';
```

Encabeza el archivo con un comentario en el estilo de las migraciones existentes
(mira `V32__create_metas_venta.sql`): qué crea y por qué.

El RLS habilitado sin políticas replica el patrón del resto del esquema: el backend
entra con `service_role` y se bloquea todo acceso público directo.

> **El número `V41` es PROVISIONAL** (decisión D4 del mapa). `oportunidad_items` es un
> cambio separado en vuelo cuyo número no controlamos, y `spring.flyway.out-of-order`
> está en `false`: si otra migración toma el 41 primero, Flyway falla. La tarea **S10**
> vuelve a comprobar el número real contra producción antes de aplicar.

**Restricciones**
- No toques ninguna otra migración, ni `docs/migrations/V40__create_simulaciones.sql`.
- **No apliques nada a producción en esta tarea.** Solo se escribe el archivo.

**Criterio de aceptación:** el archivo existe y el SQL es sintácticamente válido.
No ejecutes `bootRun` ni la migración.

---

## S2 · Entidad, repositorio y DTO

**Modelo:** Sonnet 5 · **Esfuerzo:** Medium

Crea el módulo `src/main/kotlin/pe/quantum/crm/domain/tipocambio/`:

**`TipoCambio.kt`** — entidad JPA sobre `tipo_cambio`, con `@Id` en `fecha`
(`LocalDate`, sin `@GeneratedValue`: la PK es natural). Campos `compra` y `venta` como
`BigDecimal`, `fuente` como `String`, `createdAt` como `LocalDateTime`. Sigue el estilo
de `domain/metasventa/MetaVenta.kt`.

**`TipoCambioRepository.kt`** — `JpaRepository<TipoCambio, LocalDate>` con:

```kotlin
fun findFirstByOrderByFechaDesc(): TipoCambio?
```

Ese único método resuelve a la vez "valor vigente" y "fallback al último guardado".

**`dto/TipoCambioDto.kt`** — `fecha: LocalDate`, `compra: BigDecimal`, `venta: BigDecimal`.
La entidad **nunca** sale del módulo (`CLAUDE.md` regla 9).

**Restricciones**
- Sin lógica de negocio en la entidad ni en el DTO.
- No crees el Service ni el Controller todavía: son S4 y S6.

**Criterio de aceptación:** `./gradlew compileKotlin ktlintCheck detekt` pasa.

---

## S3 · Cliente HTTP de SUNAT

**Modelo:** Opus 5 · **Esfuerzo:** High

Crea `src/main/kotlin/pe/quantum/crm/integracion/sunat/`, siguiendo el patrón de
`integracion/drive/` (interfaz + `Impl` + `*Properties`).

### Contrato del proveedor — verificado en vivo el 2026-09-01

El dato de SUNAT se obtiene vía **Decolecta**. El arquitecto probó el endpoint real
contra las cuatro rutas de respuesta; **no supongas nada más allá de esta tabla y no
inventes campos**.

- **URL:** `https://api.decolecta.com/v1/tipo-cambio/sunat`
- **Autenticación:** cabecera `Authorization: Bearer <API_KEY>`
- **Parámetro `date`** (opcional, ISO `YYYY-MM-DD`): **no lo envíes.** Sin él la API
  devuelve el valor vigente del día, y así el backend nunca tiene que calcular "qué día
  es hoy" —una pregunta con trampa, porque la app corre en UTC y Lima es UTC−5—. La
  fecha se toma **siempre del campo `date` de la respuesta**, nunca del reloj local.

| Caso | HTTP | Cuerpo | Qué debe hacer el cliente |
|---|---|---|---|
| Éxito | `200` | `{"buy_price":"3.357","sell_price":"3.367","base_currency":"USD","quote_currency":"PEN","date":"2026-09-01"}` | Mapear a `TipoCambioExterno` |
| Fin de semana o feriado | `200` | Igual que éxito (arrastra el último publicado) | Nada especial: es un 200 normal |
| API key ausente, inválida o **cuota agotada** | `401` | `{"error":"Apikey Required / Limit Exceeded"}` | `null` + `WARN` |
| Fecha sin dato publicado | `404` | `{"message":"Not found"}` | `null` + `WARN` |

Dos observaciones que cambian la implementación:

1. **`buy_price` y `sell_price` llegan como `String`, no como número.** Eso es una
   ventaja: mapéalos a `String` en el DTO de transporte y conviértelos con
   `BigDecimal(valor)`. Así no hay ninguna posibilidad de que Jackson los pase por
   `Double` y pierda precisión.
2. **El `401` mezcla "key inválida" con "límite de cuota superado".** Regístralo con un
   mensaje que diga ambas cosas, para que quien lea el log en producción no pierda
   tiempo revisando la credencial cuando lo que se agotó fue el plan.

Correspondencia de campos: `buy_price` → `compra`, `sell_price` → `venta`, `date` → `fecha`.

### Archivos

**`SunatProperties.kt`** — `@ConfigurationProperties(prefix = "app.sunat")` con
`url: String`, `apiKey: String`, `timeoutSegundos: Long = 10`. Añade a
`application.properties`:

```properties
app.sunat.url=${SUNAT_TIPO_CAMBIO_URL:https://api.decolecta.com/v1/tipo-cambio/sunat}
app.sunat.api-key=${DECOLECTA_API_KEY:}
app.sunat.timeout-segundos=${SUNAT_TIMEOUT_SEGUNDOS:10}
```

> **`CLAUDE.md` regla 13 — la más importante de esta tarea.** La API key es un secreto.
> **Nunca** la escribas en `application.properties`, en código, en un test, en un
> comentario ni en ningún documento del repo. Solo el **nombre** de la variable.
>
> - En `.env` (que está en `.gitignore`) va la línea `DECOLECTA_API_KEY=...` con el valor
>   real. **El arquitecto ya te dirá si esa línea existe; si no la encuentras, pídesela,
>   no la inventes ni la busques en el historial.**
> - En **`.env.example`** (que sí se commitea) añade `DECOLECTA_API_KEY=` **vacía**,
>   siguiendo el estilo de las claves ya presentes.
> - Si en cualquier momento ves la clave escrita dentro de un archivo versionado,
>   **detente y avisa al arquitecto de inmediato**: es un incidente de seguridad, no un
>   detalle de estilo.

Con `app.sunat.api-key` en blanco el cliente devuelve `null` sin llamar, y el sistema sigue
sirviendo el último valor guardado: exactamente el fallback de §12.

**`TipoCambioExterno.kt`** — data class de transporte: `fecha: LocalDate`,
`compra: BigDecimal`, `venta: BigDecimal`.

**`SunatTipoCambioClient.kt`** — interfaz:

```kotlin
/** Consulta el tipo de cambio publicado. Devuelve null si no hay respuesta utilizable. */
fun consultar(): TipoCambioExterno?
```

**`SunatTipoCambioClientImpl.kt`** — implementación con `org.springframework.web.client.RestClient`
(ya disponible en `spring-boot-starter-web`, **no añadas dependencias**).

Comportamiento obligatorio:
- Si `app.sunat.url` o `app.sunat.api-key` están en blanco → devuelve `null` **sin intentar la
  llamada**, y registra a nivel `INFO` una sola vez, no en cada ejecución.
- **Cualquier** fallo —timeout, 401, 404, 5xx, JSON inesperado, campo faltante, fecha no
  parseable— se captura, se registra en `WARN` y devuelve `null`. **Nunca propaga
  excepción**: §12 exige fallback *"sin error visible"*.
- Timeout de conexión y de lectura tomados de `timeoutSegundos`.
- **Nunca `Double`.** Los precios se convierten con `BigDecimal(String)`.
- **La API key jamás debe aparecer en un log**, ni siquiera en el mensaje de error del
  401. Si registras la URL o la petición, no incluyas la cabecera `Authorization`.

**Tests** — `src/test/kotlin/pe/quantum/crm/integracion/sunat/SunatTipoCambioClientImplTest.kt`.
Usa `MockRestServiceServer` (o un `RestClient` con builder mockeado): **ninguna llamada
de red real en la suite**, y en los tests la key es un literal ficticio tipo `"sk_test"`.

Casos obligatorios, uno por fila de la tabla de contrato:
- key en blanco → `null` sin llamada
- `200` con el JSON de ejemplo → `TipoCambioExterno(fecha=2026-09-01, compra=3.357, venta=3.367)`,
  comparando con `compareTo`, no con `equals`
- `401` con el cuerpo de error → `null`, sin excepción
- `404` `{"message":"Not found"}` → `null`, sin excepción
- `500` → `null`, sin excepción
- JSON con `buy_price` ausente → `null`, sin excepción
- Verifica que la petición sale **sin** parámetro `date` y **con** la cabecera
  `Authorization: Bearer sk_test`

**Criterio de aceptación:** tests en verde; ninguna llamada de red en la suite;
`grep -rn 'Double' src/main/kotlin/pe/quantum/crm/integracion/sunat/` vacío;
y `git grep -nIE 'sk_[0-9]{4,}'` **sin resultados en todo el repo** (ninguna API key
real escrita en ningun archivo versionado).

---

## S4 · Service con fallback

**Modelo:** Opus 5 · **Esfuerzo:** High

**`TipoCambioService.kt`** (interfaz, API pública del módulo):

```kotlin
/** Tipo de cambio vigente: la fila de fecha mayor. Null si nunca se guardo ninguno. */
fun vigente(): TipoCambioDto?

/** Consulta SUNAT y guarda si hay dato nuevo. Devuelve true si escribio. Lo invoca el job. */
fun actualizarDesdeSunat(): Boolean
```

**`TipoCambioServiceImpl.kt`**:
- `vigente()` → `@Transactional(readOnly = true)`, `findFirstByOrderByFechaDesc()`, mapea a DTO.
- `actualizarDesdeSunat()` → `@Transactional`. Llama al cliente; si devuelve `null`,
  registra `WARN`, **no lanza** y devuelve `false` (§12: fallback silencioso). Si trae
  dato, hace upsert por `fecha` (`save` sobre la PK natural es idempotente) y devuelve `true`.

**Tests** (`TipoCambioServiceImplTest.kt`, MockK): sin filas → `vigente()` es `null` ·
con varias fechas → devuelve la mayor · cliente `null` → `actualizarDesdeSunat()` es
`false`, no escribe y **no lanza** · cliente con dato → guarda y devuelve `true` ·
misma fecha dos veces → no duplica.

**Criterio de aceptación:** tests en verde; `./gradlew ktlintCheck detekt` pasa.

---

## S5 · Job `@Scheduled` diario

**Modelo:** Sonnet 5 · **Esfuerzo:** Medium

**`jobs/ActualizacionTipoCambioJob.kt`**, calcado del patrón de
`domain/notificaciones/jobs/LimpiezaNotificacionesJob.kt` (léelo antes de escribir).

- `@Component`, dependencia por constructor del `TipoCambioService`.
- `@Scheduled(cron = "0 30 14 * * *")` — **14:30 UTC diario = 09:30 de Lima**.
- El método llama a `actualizarDesdeSunat()` y **envuelve todo en try/catch**: una
  excepción no capturada en un `@Scheduled` mata las ejecuciones siguientes.
- **No** pongas `@Transactional` en el job: la transacción vive en el Service (S4).

> **Por qué 14:30 UTC y no la madrugada.** La app corre en UTC (`ZonaHorariaGuard`) pero
> SUNAT publica en horario de Lima, que es UTC−5. Un job a las 06:30 UTC dispararía a la
> 01:30 de la madrugada de Lima, antes de que exista el valor del día. 14:30 UTC cae a
> las 09:30 de Lima, con el tipo de cambio ya publicado.
>
> Además evita colisiones con los jobs existentes: `LimpiezaNotificacionesJob` corre a
> las 03:00 y `RecordatorioJob` cada hora **en punto**, así que el minuto 30 queda libre.

**Tests** (`ActualizacionTipoCambioJobTest.kt`): invoca al service una vez · si el
service lanza, el job **no propaga**. Verifica que el cron es exactamente
`0 30 14 * * *` leyendo la anotación por reflexión, igual que hacen los tests de jobs ya
existentes si siguen ese patrón (mira `src/test/kotlin/pe/quantum/crm/domain/notificaciones/jobs/`).

**Criterio de aceptación:** tests en verde.

---

## S6 · Endpoint `GET /api/v1/tipo-cambio`

**Modelo:** Sonnet 5 · **Esfuerzo:** Medium

**`TipoCambioController.kt`**, siguiendo `MetaVentaController.kt`:

- `@RestController`, `@RequestMapping("/api/v1/tipo-cambio")`.
- `GET` → `ApiResponse<TipoCambioDto?>`.
- **Accesible a todos los roles autenticados**: §12 lo describe como parte del layout
  global del CRM, visible de forma permanente para cualquier usuario. Sin filtro de rol
  y sin condicional de visibilidad.
- Si no hay ningún valor guardado, devuelve `data: null` con HTTP 200 — **no** 404: la
  ausencia de tipo de cambio no es un recurso inexistente, es un dato aún no poblado, y
  §12 prohíbe error visible.

Comprueba en `config/` que la ruta queda **autenticada** (no pública) y que ningún
`SecurityFilterChain` la deje fuera por accidente. Si la configuración de seguridad
exige registrar la ruta explícitamente, hazlo; si no, no la toques.

**Test** — `TipoCambioControllerWebMvcTest.kt`, siguiendo `MetaVentaControllerWebMvcTest.kt`:
200 con datos · 200 con `data: null` cuando no hay filas · 401 sin autenticación.

**Criterio de aceptación:** tests en verde.

---

## S7 · Documentación de contrato y changelog

**Modelo:** Sonnet 5 · **Esfuerzo:** Medium

`CLAUDE.md`, *Coordinación con el frontend*: *"Todo cambio a un endpoint documentado
(breaking o no) se registra en `contrato_api.md` §25 Changelog del contrato, en el mismo
PR que lo hace. Sin esa entrada, el cambio de contrato no está terminado."*

1. **`docs/contrato_api.md`** — nueva sección para `GET /api/v1/tipo-cambio`, después de
   §22 y renumerando lo que siga si hace falta. Incluye request, response de ejemplo con
   el envelope real, el caso `data: null`, y qué roles acceden (todos los autenticados).
   Añade la entrada al **Índice** del principio del archivo.
2. **§25 Changelog** — una fila nueva:
   - Fecha: la del PR
   - Endpoint: `GET /tipo-cambio`
   - Tipo: **Non-breaking** (endpoint nuevo, según la definición del propio §25)
   - Cambio: qué expone y de dónde sale el dato
   - Acción para frontend: consumirlo para el indicador permanente del layout; tolerar
     `data: null` mientras el job no haya poblado la primera fila
3. **`docs/matriz_permisos.md`** — fila nueva en la tabla de recursos: **Tipo de cambio**,
   lectura para los seis roles.

**Restricciones**
- No documentes nada de simulaciones: ese módulo no existe todavía.
- No inventes campos que el DTO de S2 no tiene.

**Criterio de aceptación:** las tres ediciones hechas y coherentes con el código real.

---

## S8 · Verificación de build

**Modelo:** Sonnet 5 · **Esfuerzo:** Low

Igual que T7 del Plan 1: `ktlintCheck` → `detekt` → `test` → `koverVerify`, en ese orden,
reportando la salida de cada uno.

Baseline conocido: verde antes de este plan (verificado el 2026-09-01).

**Atención a `koverVerify`**: este plan **sí** añade código bajo `pe.quantum.crm.domain`
(el módulo `tipocambio`), así que la métrica de dominio —trinquete 84 %— se mueve. Si
baja del trinquete, la respuesta correcta es **añadir tests**, nunca bajar el umbral en
`build.gradle.kts`. La entidad y el repositorio ya están excluidos del cómputo por los
filtros de Kover (`annotatedBy("jakarta.persistence.Entity")` y `classes("*Repository")`),
igual que `*Properties`.

**No ejecutes `integrationTest`** (Docker bloqueado en local).

---

## S9 · Revisión final del diff contra los documentos citados

**Modelo:** Opus 5 · **Esfuerzo:** High

Tarea exigida por `CLAUDE.md`. **Auditoría del diff completo, no resumen del trabajo.**

Contrasta `git diff` contra: `reglas_simulaciones.md` §12 · `contrato_api.md` §2 y §25 ·
`matriz_permisos.md` · `CLAUDE.md` reglas 8, 9, 10, 11 y 13 · las decisiones D4 y D5 del mapa.

Busca en concreto:

1. **Contradicciones con documentación ya vigente y correcta** (el caso que `CLAUDE.md`
   manda cazar por su nombre).
2. **Secretos hardcodeados** (regla 13). Obligatorio ejecutar y reportar la salida de:
   - `git grep -nIE 'sk_[0-9]{4,}'` → debe salir **vacío**. Ese es el formato de la API
     key de Decolecta; si aparece en cualquier archivo versionado, es un incidente de
     seguridad: **detente y avisa al arquitecto de inmediato**.
   - `git grep -nI 'decolecta'` → solo puede aparecer como valor por defecto de la URL en
     `application.properties`, nunca acompañado de una credencial.

   Verifica además que `.env.example` traiga `DECOLECTA_API_KEY=` **sin valor**, que
   `.env` siga en `.gitignore` y que **no** aparezca en el diff.
3. **Que ningún fallo de SUNAT pueda propagarse** hasta el usuario: recorre el camino
   cliente → service → job y confirma que cada nivel captura (§12, *"sin error visible"*).
4. **Que la entidad `TipoCambio` no se filtre** fuera del módulo:
   `grep -rn 'TipoCambio\b' src/main/kotlin/ --include=*.kt` y verifica que fuera de
   `domain/tipocambio/` solo aparezca el DTO o la interfaz del Service (regla 9).
5. **Que la entrada del changelog exista** y describa el endpoint realmente implementado.
6. **Que no se tocara ninguna tabla existente** ni `docs/migrations/V40__create_simulaciones.sql`.
7. **Que no se añadiera ninguna dependencia** a `build.gradle.kts`.

**Entregable:** informe con hallazgos clasificados en *bloqueante / menor / ninguno*,
con archivo, línea y regla incumplida. **No arregles nada**: solo reporta.

---

## S10 · Despliegue de la migración a producción

**Modelo:** Opus 5 · **Esfuerzo:** High

> **Esta es la única tarea del plan que toca producción.** La base está desplegada en
> Supabase con usuarios reales. **No la ejecutes sin confirmación explícita del dueño
> del producto**, aunque S1–S9 estén todas en verde.

Pasos, en orden estricto:

1. **Releer el estado real** (decisión D4 del mapa):
   ```sql
   SELECT version, description, success
   FROM flyway_schema_history ORDER BY installed_rank DESC LIMIT 5;
   ```
   vía el MCP de Supabase, proyecto `fmkqomwyakxeblfinkuy`.
2. **Comprobar la numeración.** Si la versión máxima ya no es 39, o si alguien tomó el
   41, **renombra el archivo** al siguiente número libre. `spring.flyway.out-of-order`
   está en `false`: una versión menor que llegue después de una mayor ya aplicada hace
   fallar el arranque de la aplicación.
3. **Confirmar que la tabla no existe:** `SELECT to_regclass('public.tipo_cambio');`
   debe devolver `null`.
4. **Presentar el DDL final al dueño del producto y esperar su visto bueno explícito.**
5. Aplicar con `apply_migration` del MCP, con el nombre en snake_case y el DDL idéntico
   al del archivo de S1 — carácter por carácter, para que la migración local y la
   aplicada no diverjan.
6. **Verificar después de aplicar:** que la tabla existe, que los dos CHECK están, que
   el RLS está habilitado, y que `flyway_schema_history` registró la fila con
   `success = true`.

Si cualquier paso no sale como se describe, **detente y reporta**. No improvises
correcciones sobre la base de producción.

---

## Cierre del plan

Al terminar, **para y resume** qué se hizo.

`GET /tipo-cambio` devolverá `data: null` hasta la primera ejecución del job (14:30 UTC
del día siguiente al deploy, salvo que el deploy caiga antes de esa hora). Es el comportamiento correcto y está documentado en S7;
avísalo igualmente al equipo de frontend para que no lo lea como un fallo.
