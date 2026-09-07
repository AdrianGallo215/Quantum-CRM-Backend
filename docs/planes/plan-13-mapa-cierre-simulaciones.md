# Mapa — Plan F: cierre completo del módulo Simulaciones

> **Documento de investigación y decisiones.** Las tareas viven en
> `plan-14-cierre-simulaciones-tareas.md`.
>
> Continúa la numeración de los planes 00-12: hallazgos **K31+**,
> decisiones **D54+**.
>
> **Plan F cierra el encargo completo.** Al terminarlo no queda ninguna fase ni
> sección de `reglas_simulaciones.md` pendiente de backend.

---

## 0. Punto de partida (Planes D y E cerrados, PR #13 mergeada, PR #14 en verde)

| Fase del encargo | Estado |
|---|---|
| 1. Motor de cálculo | Hecha (Plan 1) |
| 2. Persistencia y dominio | Hecha (Plan D: CRUD · Plan E: historial §7) |
| 3. Endpoints y permisos | Hecha (Plan D) |
| 4. Calculadora Financiera | Hecha (Plan E) |
| 5. Jobs programados | **Parcial**: tipo de cambio §12 hecho (Plan 2); faltan purga y aviso |
| 6. Documentación | **Sin empezar** |

Además quedan sueltas dos piezas de las reglas que ningún plan anterior tomó:
la **cuota en la oportunidad (§6)** y dos detalles de presentación (§5 fecha de
eliminación prevista, §8.2 agrupación).

---

## 1. Documentos que gobiernan este plan

| Documento | Qué manda aquí |
|---|---|
| `docs/reglas_simulaciones.md` §5, §6.1, §6.2, §6.3, §8.2, §10, §13 | Fuente de verdad de lo que falta |
| `Instrucciones_simulaciones.md` | Fases 5 y 6, y las restricciones no negociables |
| `docs/planes/plan-09-mapa-simulaciones-modulo.md` · `plan-11-mapa-historial-calculadora.md` | Contexto vigente (K10-K30, D30-D53) |
| `docs/contrato_api.md` · `docs/matriz_permisos.md` · `CLAUDE.md` | Los tres documentos que la Fase 6 tiene que dejar al día |
| `src/main/resources/db/migration/V22__create_notificaciones.sql` | Los cuatro enums de notificaciones y `recordatorios_enviados` |

### Reglas de `CLAUDE.md` en los puntos nuevos

| Regla | Cómo aplica exactamente aquí |
|---|---|
| **10. `@Transactional`** | La purga escribe el log **y** borra la fila: una sola transacción o la bitácora miente |
| **11. Queries parametrizadas** | Las consultas de los jobs (huérfanas por antigüedad) van con parámetros nombrados |
| **12. Frontera de módulos** | El punto más delicado del plan: `oportunidades` pasa a consumir `simulaciones`, y los jobs necesitan `notificaciones`. Ver K33 y D57 |
| **14. IDOR** | Los métodos que `oportunidades` consume **no** aplican permisos (igual que D32 en sentido inverso): quien llama ya filtró |
| **Coordinación con el frontend** | La Fase 6 salda de una vez la deuda de contrato de los Planes D y E (11 endpoints sin documentar) |

---

## 2. Hallazgos

### K31 — §6.1 no dice con qué `modo` se calcula la cuota efímera

§6.1 lista los parámetros por defecto de la cuota efímera (`plazo_meses` 48,
`tea` 14, `cuota_inicial` 45 000, `valor_residual` 25 000, `dias_trabajados`
22, `comision_estructuracion` 1 180) y dice que `precio_venta` y `descuento`
salen del ítem. **No menciona `modo`**, que el motor exige y que cambia por
completo la fórmula (§3.3 vs §3.4).

Es un hueco real de la especificación, no una omisión de lectura. Hay que
decidirlo (D54) y dejarlo escrito.

### K32 — **La cuota efímera puede reventar un `GET /oportunidades` con un 500**

El hallazgo más importante de este plan. Los defaults de §6.1 son constantes
fijas, pero `precio_venta` viene del ítem, así que **hay ítems reales para los
que esos defaults producen una simulación inválida**:

- `cuota_inicial (45 000) < PV_efectivo` (§13) falla si el ítem vale 45 000 o
  menos tras el descuento.
- `valor_residual (25 000) < Principal` (§13) falla mucho antes: en leasing,
  `Principal = precio/1.18 − 45 000/1.18`. Con un ítem de **60 000**,
  `Principal = 50 847 − 38 136 = 12 711`, y `25 000 > 12 711` → inválido.

Y peor: si se llama al motor sin validar, `MotorSimulacion.validarBalloon`
puede lanzar `CronogramaInconsistenteException`, que el
`GlobalExceptionHandler` traduce a **500**.

Consecuencia: sin una guarda explícita, **un solo ítem barato tumba el listado
de oportunidades entero** — el endpoint más usado de la aplicación. La cuota
efímera es un adorno informativo; jamás puede impedir leer la oportunidad.

### K33 — El ciclo `oportunidades` ↔ `simulaciones` se cierra en este plan

Hasta ahora la dependencia iba en un solo sentido (`simulaciones` →
`oportunidades`, vía `OportunidadItemService.datosParaSimulacion`, D32). §6.2
obliga al sentido contrario: `OportunidadServiceImpl.toDtos` necesita la cuota
de cada ítem.

Spring Boot 3 rechaza los ciclos de constructor. Precedente ya en el repo:
`OportunidadVisibilidad` inyecta `@Lazy private val tareaService: TareaService`
exactamente por esto, con el comentario que lo explica. Se repite el patrón.

### K34 — `DefaultsSimulacion` **no** es API pública: `oportunidades` no puede leerlo

`ArquitecturaModulosTest.esApiPublica` acepta solo: subpaquete `dto`,
interfaces, enums y clases `*Event`. `DefaultsSimulacion` y
`ValidacionesSimulacion` son `object` (clases) en `domain/simulaciones/` — **no
cruzan la frontera**.

Consecuencia de diseño: el cálculo efímero de §6.1 **tiene que vivir dentro de
`simulaciones`** y exponerse por su interfaz de servicio. Si se intentara
calcular desde `oportunidades` (que ya tiene el `precio_venta` del ítem a
mano), ArchUnit lo rechazaría en cuanto tocara los defaults.

### K35 — `recordatorios_enviados` es de `notificaciones` y tampoco cruza

El aviso a 3 días (§5) necesita no repetirse cada día entre el 27 y el 30. La
maquinaria de dedup que ya existe (`recordatorios_enviados`, clave
`(origen, id_origen, umbral)`) vive en `notificaciones`, y su repositorio
**no es API pública** — `NotificacionService` solo expone
`reiniciarRecordatorios(origen, idOrigen)`, que borra, no consulta.

Usarla exigiría: un valor nuevo en `origen_recordatorio_enum`, ampliar la
interfaz pública de `notificaciones` con un "¿ya se envió?", y una migración
más. Hay una alternativa sin nada de eso (D58).

### K36 — La numeración de `contrato_api.md` ya se renumeró una vez, y dejó una referencia rota

El changelog es hoy **§26**, pero:

- `CLAUDE.md:136` dice *"se registra en `contrato_api.md §25 Changelog del
  contrato`"* → **§25 es hoy "Notas operativas — Drive"**. La referencia está
  rota desde la renumeración anterior.
- `contrato_api.md:2421` (dentro del propio changelog) remite a "las notas de
  §25" — esa sí sigue siendo correcta.
- `contrato_api.md:2407` remite a "§23 Mantenimiento" — correcta hoy.

Es deriva preexistente, no introducida por este plan, pero cualquier
renumeración nueva la empeora si no se arregla a la vez. Es exactamente el tipo
de contradicción con documentación ya vigente que la auditoría final busca.

### K37 — La tabla de enums del contrato (§24) no conoce los enums de simulaciones

`modo_simulacion_enum` y `tipo_evento_simulacion_enum` existen desde V43
(aplicada en producción el 2026-09-03) y **no aparecen** en la tabla de §24,
que dice de sí misma: *"Si agregas o renombras un valor (migración nueva),
actualiza esta tabla en el mismo commit"*. Otra deuda preexistente que la
Fase 6 salda.

### K38 — `CLAUDE.md` sigue diciendo "15 módulos" y le faltan dos

El inventario lista `catalogoeventos, contactos, empleados, empresas, eventos,
financiadoras, inicio, metasventa, modelos, notificaciones, oportunidades,
prospeccion, reportes, solicitudes, tareas` — **sin `tipocambio`** (Plan 2, en
producción desde el 2026-09-01) ni **`simulaciones`** (Planes D y E). Son 17.

La tabla de documentos de referencia tampoco incluye `reglas_simulaciones.md`,
pese a ser la fuente de verdad de un módulo entero.

### K39 — Añadir valores a un enum no cambia la cuenta de tipos del schema

`SchemaMigrationIntegrationTest` verifica la **lista de tipos enum** (21) y la
**lista de tablas** (24). `ALTER TYPE ... ADD VALUE` no crea un tipo ni una
tabla: esas dos aserciones no cambian. Lo que sí cambia es la cuenta de
migraciones, que vive en `SeedFixtures` (`MIGRACIONES_TOTAL` 45,
`MIGRACION_VERSION_MAX` 46).

Dicho de otro modo: **este plan toca `SeedFixtures`, no
`SchemaMigrationIntegrationTest`.** Confundirlos costó dos rondas de CI en el
Plan B.

---

## 3. Decisiones

### D54 — La cuota efímera se calcula en **leasing**

Resuelve K31. `ModoSimulacion.leasing` es el primer valor del enum y el
producto principal de Quantum (el CRM es de buses KinWin financiados por
Quantum; el crédito directo es la variante). Es además la opción conservadora:
su cuota final (`CuotaFin × 1.18`) es la que el vendedor espera ver por
defecto.

Se declara como constante con nombre en `DefaultsSimulacion`
(`MODO_CUOTA_EFIMERA`), no como literal suelto, con un comentario que diga que
§6.1 no lo especifica y que esta es una decisión del backend, no una regla
citada. Si el negocio lo contradice, se cambia en un solo sitio.

### D55 — La cuota efímera **nunca falla**: si no se puede calcular, no hay cuota

Resuelve K32, el riesgo de 500 en `GET /oportunidades`.

El cálculo efímero es defensivo por contrato: devuelve `BigDecimal?`, y es
`null` —sin excepción, sin log de error— cuando:

1. El ítem no tiene `precio_venta` (ítem incompleto, D15).
2. `cuota_inicial (45 000) >= PV_efectivo` (§13 no se cumple).
3. `valor_residual (25 000) >= Principal` (§13 no se cumple).

Los dos chequeos de §13 se evalúan **antes** de llamar al motor, reutilizando
`ValidacionesSimulacion` pero **capturando** su `ValidacionException` en vez de
propagarla — o, mejor, comparando directamente sin lanzar. La tarea lo detalla.

Además, la llamada al motor va envuelta en un `runCatching` que degrada
cualquier `CronogramaInconsistenteException` a `null` y **registra un `warn`**
en el log de la aplicación: es una anomalía que alguien debe poder ver, pero
jamás una respuesta 500 en un listado.

**Esta decisión no aplica a `POST /simulaciones`**: cuando el usuario crea una
simulación de verdad con parámetros inválidos, sigue recibiendo su 400 limpio
(§13). Lo que se degrada es solo el cálculo automático y no solicitado.

### D56 — El cálculo efímero vive en `simulaciones` y se expone por lotes

Consecuencia de K34. Se añade a `SimulacionService`:

```kotlin
/**
 * Cuota Quantum por item (§6.2): la `cuota_final` de su simulacion principal
 * si la tiene, o el calculo efimero de §6.1 con los parametros por defecto si
 * no (sin persistir nada).
 *
 * SIN chequeo de visibilidad, igual que `OportunidadItemService.datosParaSimulacion`
 * en el sentido contrario (D32): quien llama —`oportunidades`— ya filtro que
 * el usuario alcanza esas oportunidades. Aplicar aqui la regla de §10 le
 * quitaria la cuota al `vendedor`, que SI debe verla en su propia oportunidad.
 *
 * Un item que no aparece en el mapa es un item cuya cuota no se pudo calcular
 * (incompleto, o parametros por defecto invalidos para su precio, D55). Eso
 * NO es un error: es "no hay cuota que mostrar".
 */
fun cuotaQuantumPorItems(items: Collection<ItemParaCuota>): Map<Long, BigDecimal>
```

con el DTO de entrada en `domain/simulaciones/dto/` (subpaquete `dto` → API
pública, ArchUnit lo permite):

```kotlin
data class ItemParaCuota(
    val idItem: Long,
    val precioVenta: BigDecimal?,
    val descuento: BigDecimal?,
)
```

**Se pasan los datos, no solo los ids**, a propósito: `OportunidadServiceImpl.toDtos`
ya tiene los ítems cargados en memoria. Pedirle a `simulaciones` que los vuelva
a leer con `datosParaSimulacion` sería una segunda consulta redundante y una
llamada re-entrante `oportunidades → simulaciones → oportunidades`.

### D57 — El ciclo se corta con `@Lazy`, como ya hace `OportunidadVisibilidad`

Resuelve K33. `OportunidadServiceImpl` inyecta:

```kotlin
// `@Lazy` porque `simulaciones` ya depende de `oportunidades`
// (OportunidadItemService.datosParaSimulacion, D32) y Spring Boot 3 rechaza
// los ciclos de constructor; el proxy corta el ciclo al arrancar. Mismo patron
// que `OportunidadVisibilidad.tareaService`.
@Lazy private val simulacionService: SimulacionService,
```

### D58 — El aviso a 3 días usa una **ventana de un día**, sin tabla de dedup

Resuelve K35 sin tocar `notificaciones` por dentro ni añadir enums de
recordatorio.

El job corre una vez al día y notifica solo las simulaciones huérfanas cuya
antigüedad **cruza la frontera de los 27 días en esa corrida**:

```
created_at <= ahora - 27 dias  AND  created_at > ahora - 28 dias
```

Como la ventana es de exactamente 24 h y el job corre cada 24 h, cada
simulación cae en ella **una sola vez** en toda su vida. No hace falta
recordar qué se envió.

**Limitación conocida, y aceptada:** si el job no corre un día, las
simulaciones de esa ventana se quedan sin aviso (y aun así se purgan a los 30
días). Es un aviso, no una garantía transaccional; el precio a cambio es no
ampliar la API pública de `notificaciones` ni añadir un tercer enum. Queda
escrito en el KDoc del job para que nadie lo lea como bug.

### D59 — Solo dos valores de enum nuevos, en una sola migración

De D58 se sigue que `origen_recordatorio_enum` **no** se toca. Hacen falta:

- `tipo_notificacion_enum` += `simulacion_por_expirar`
- `entidad_notificacion_enum` += `simulacion`

Ambos en una migración (`ALTER TYPE ... ADD VALUE` ×2). **No se usan en esa
misma migración**, así que no aplica la restricción que obligó a partir V44/V45
en el Plan B: el código que los consume corre después, en otra transacción.

El número (`V47` si nada se aplica antes) se asigna **al desplegar**, releyendo
`flyway_schema_history` — Flyway corre con `out-of-order = false` y ya costó
una renumeración V40→V43 en este mismo módulo (K19).

### D60 — La purga vive en `simulaciones`, con el snapshot antes del borrado

§5: hard delete de las huérfanas con más de 30 días, registrando `eliminada`
con snapshot completo **antes** de borrar (el log sobrevive: `id_simulacion`
no tiene FK, por eso mismo).

El job vive en `domain/simulaciones/jobs/` (mismo módulo → puede usar sus
repositorios directamente, igual que `LimpiezaNotificacionesJob` usa
`NotificacionRepository`), pero **la lógica de negocio se queda en el
Service**, expuesta como:

```kotlin
/** Purga de §5. Devuelve cuantas elimino. Sin `UsuarioActual`: la ejecuta un job. */
fun purgarHuerfanas(anterioresA: LocalDateTime): Int
```

Motivo: el snapshot completo (los 10 campos del CHECK, K15) ya se arma en el
privado `registrarEvento`. Duplicar esa lista en el job sería una segunda copia
de la que el CHECK depende.

**`registrarEvento` cambia de firma**: `usuario: UsuarioActual` →
`idUsuario: Long?`, porque `simulacion_log.created_by` es nullable justamente
para *"cuando el evento lo genera un job programado sin actor humano (p. ej. la
purga a 30 días)"* — el KDoc de la columna lo anticipa desde V43. Los cuatro
llamadores existentes pasan `usuario.id`; la purga pasa `null`.

### D61 — Un solo job con dos métodos `@Scheduled`

Aviso y purga son las dos mitades de la misma regla (§5) y comparten sus dos
constantes (30 días de retención, 3 de preaviso). Van en
`domain/simulaciones/jobs/PurgaSimulacionesJob.kt`, con dos `@Scheduled`.

Horarios, evitando los que ya existen (`LimpiezaNotificacionesJob` a las 03:00
UTC, tipo de cambio a las 14:30 UTC):
- Aviso: `0 0 5 * * *`
- Purga: `0 30 5 * * *`

El aviso corre **antes** que la purga en el mismo día a propósito: si alguna
vez las ventanas se rozaran por un desfase de reloj, es preferible avisar de
más que purgar sin avisar.

### D62 — §6.2 al detalle: qué se agrega y cuándo es `null`

Por ítem (`OportunidadItemDto` gana dos campos):

```
cuotaQuantum = cuota_final de su simulacion principal, o el efimero (D55)
cuotaTotal   = cuotaQuantum + cuotaFinanciadora        (null si cuotaQuantum es null)
```

A nivel de oportunidad (`OportunidadDto` gana tres campos):

```
cuotaQuantumTotal = Σ (cuotaQuantum_item × cantidad_item)
cuotaTotal        = Σ (cuotaTotal_item   × cantidad_item)
cuotaDiariaTotal  = cuotaTotal / dias_trabajados
```

**Los tres totales son `null` si CUALQUIER ítem no aporta su cuota** (por
`cuotaQuantum` nulo o por `cantidad` nula). Es deliberado y distinto del
criterio de `MontoTotal.sumarItems`, donde un ítem incompleto aporta 0: un
`monto_total` parcial se lee como "van 2 de 3 buses cargados", pero una
**cuota** parcial se lee como "esto es lo que paga al mes", y omitir un bus en
silencio subestima el pago. Mejor no mostrar nada que mostrar de menos.

**`dias_trabajados` del divisor es la constante 22** (`DefaultsSimulacion.DIAS_TRABAJADOS`),
no el de ninguna simulación: es una cifra de nivel oportunidad y sus ítems
pueden tener simulaciones con valores distintos, así que no existe un divisor
"del ítem" bien definido. Queda documentado en el KDoc del campo.

### D63 — Dos campos nuevos en `SimulacionDto` cierran §5 y §8.2

- **`eliminacionPrevistaEl: Instant?`** — `created_at + 30 días`, **solo cuando
  `id_oportunidad_item` es null**; `null` en cuanto la simulación está
  enlazada. §5 lo exige explícitamente: *"La regla debe ser **visible en la
  UI**, no solo lógica de servidor: la simulación huérfana muestra su fecha de
  eliminación prevista"*. Derivado, nunca persistido.
- **`idOportunidad: Long?`** — derivado del ítem (ya viene en
  `OportunidadItemParaSimulacion.idOportunidad`, que `toDtos` resuelve). §8.2
  dice que en el módulo las simulaciones *"se agrupan por oportunidad o por
  empresa"*, y hoy el DTO solo trae `idOportunidadItem`: el frontend no puede
  agrupar sin resolver ítems→oportunidades por su cuenta.

**No se añade un filtro `id_oportunidad` al listado**: con el campo en el DTO,
agrupar es trivial en cliente, y un filtro nuevo obligaría a `simulaciones` a
consultar `oportunidades` para traducir oportunidad→ítems en cada listado. Si
el frontend lo pide más adelante, es una tarea propia con su justificación.

**Tampoco se añade agrupación por empresa**: la cadena
`empresa → oportunidades → ítems → simulaciones` no tiene hoy un método que la
recorra, y §8.2 describe presentación, no un endpoint.

### D64 — La Fase 6 renumera `contrato_api.md` y arregla la referencia rota

Las secciones de módulo van 8-22 y las meta-secciones 23-26. El sitio natural
de un módulo nuevo es §23, así que:

| Antes | Después |
|---|---|
| — | **§23 Simulaciones** (nueva) |
| — | **§24 Calculadora Financiera** (nueva) |
| §23 Mantenimiento | §25 |
| §24 Enums | §26 |
| §25 Notas operativas — Drive | §27 |
| §26 Changelog del contrato | §28 |

Y se arreglan las tres referencias cruzadas afectadas, **incluida la que ya
estaba rota antes de este plan** (K36):

- `CLAUDE.md:136`: "§25 Changelog" → **"§28 Changelog"** *(estaba rota: §25 es
  hoy las notas de Drive)*.
- `contrato_api.md` §27 (antes §25), primera línea: "§23 Mantenimiento" → "§25 Mantenimiento".
- `contrato_api.md` §28 (antes §26): "las notas de §25" → "las notas de §27".

Renumerar es más trabajo que apilar los módulos nuevos al final, pero dejar
"Simulaciones" después del changelog rompería la estructura del documento para
siempre; y la deriva de K36 demuestra que el coste real está en no arreglar las
referencias, no en renumerar.

### D65 — Lo que este plan deliberadamente **no** hace

- **No toca el motor** ni ninguna fórmula (K10 sigue vigente).
- **No genera PDF ni Excel** (§11 es frontend, decidido desde el mapa de Plan D).
- **No añade la cuota a `OportunidadesDeContacto`**: §6.2 habla del DTO de
  oportunidad; el de contacto es otro, más pequeño, y nadie lo pidió.
- **No expone `pronta facturación`** ni ningún otro campo fuera del MVP.
- **No añade filtro por oportunidad ni por empresa** al listado (D63).
- **No toca `SchemaMigrationIntegrationTest`** (K39): añadir valores a un enum
  no cambia ni la lista de tablas ni la de tipos.

---

## 4. Alcance — lista cerrada de archivos

### Nuevos

```
src/main/resources/db/migration/V47__notificaciones_simulacion.sql   [numero final al desplegar, D59]
src/main/kotlin/pe/quantum/crm/domain/simulaciones/CuotaEfimera.kt
src/main/kotlin/pe/quantum/crm/domain/simulaciones/dto/ItemParaCuota.kt
src/main/kotlin/pe/quantum/crm/domain/simulaciones/jobs/PurgaSimulacionesJob.kt
src/test/kotlin/pe/quantum/crm/domain/simulaciones/CuotaEfimeraTest.kt
src/test/kotlin/pe/quantum/crm/domain/simulaciones/PurgaSimulacionesJobTest.kt
src/test/kotlin/pe/quantum/crm/domain/simulaciones/SimulacionCuotaPorItemsTest.kt
```

### Modificados

```
src/main/kotlin/pe/quantum/crm/domain/notificaciones/NotificacionEnums.kt        (+2 valores, D59)
src/main/kotlin/pe/quantum/crm/domain/simulaciones/DefaultsSimulacion.kt         (+ MODO_CUOTA_EFIMERA y los 3 de §6.1 que faltan)
src/main/kotlin/pe/quantum/crm/domain/simulaciones/SimulacionService.kt          (+ cuotaQuantumPorItems, purgarHuerfanas, avisarPorExpirar)
src/main/kotlin/pe/quantum/crm/domain/simulaciones/SimulacionServiceImpl.kt      (+ 3 metodos, registrarEvento cambia a idUsuario: Long?)
src/main/kotlin/pe/quantum/crm/domain/simulaciones/SimulacionRepository.kt       (+ principalesPorItems, huerfanasAnterioresA, huerfanasEnVentana)
src/main/kotlin/pe/quantum/crm/domain/simulaciones/dto/SimulacionDtos.kt         (+ 2 campos en SimulacionDto, D63)
src/main/kotlin/pe/quantum/crm/domain/oportunidades/OportunidadServiceImpl.kt    (@Lazy SimulacionService + §6.2 en toDtos)
src/main/kotlin/pe/quantum/crm/domain/oportunidades/dto/OportunidadDtos.kt       (+ 3 campos, D62)
src/main/kotlin/pe/quantum/crm/domain/oportunidades/dto/OportunidadItemDtos.kt   (+ 2 campos, D62)
src/test/kotlin/pe/quantum/crm/support/SeedFixtures.kt                           (46/47, K39)
src/test/kotlin/pe/quantum/crm/domain/simulaciones/SimulacionServiceImplTest.kt
src/test/kotlin/pe/quantum/crm/domain/oportunidades/OportunidadServiceImplTest.kt   (y los demas fixtures de oportunidades que rompan)
docs/contrato_api.md · docs/matriz_permisos.md · CLAUDE.md
```

**`SchemaMigrationIntegrationTest.kt` no se toca** (K39/D65). Cualquier archivo
fuera de esta lista: **detente y consulta**.

---

## 5. Riesgos y cómo los ataja el plan

| Riesgo | Mitigación |
|---|---|
| **Un 500 en `GET /oportunidades` por la cuota efímera (K32)** | D55: la cuota es `BigDecimal?` y degrada a `null` en las tres condiciones, con `runCatching` sobre el motor. Su tarea es Extra High y trae tests con precios que fuerzan cada condición |
| Ciclo de beans al arrancar (K33) | D57: `@Lazy`, con precedente. La verificación local incluye levantar el contexto (los `*WebMvcTest` lo hacen) |
| Violar ArchUnit al leer defaults desde `oportunidades` (K34) | D56: el cálculo vive en `simulaciones` y cruza solo por interfaz + DTO. La auditoría corre `--tests '*Arquitectura*'` |
| Totales de cuota que subestiman el pago (D62) | Regla explícita: cualquier ítem sin cuota → los tres totales `null`, con test |
| Ampliar la API de `notificaciones` sin necesidad (K35) | D58 evita el dedup con una ventana de 24 h, a cambio de una limitación documentada |
| Confundir `SeedFixtures` con `SchemaMigrationIntegrationTest` (K39) | Dicho en el mapa, en la tarea y en la auditoría; costó dos rondas de CI en Plan B |
| Renumerar el contrato y dejar referencias rotas (K36) | D64 enumera las tres, incluida la que ya estaba rota; la auditoría las verifica una por una |
| Aplicar la migración a producción sin CI verde | La tarea de despliegue queda **fuera** del plan de tareas, coordinada aparte (igual que C13 en el Plan C) |
