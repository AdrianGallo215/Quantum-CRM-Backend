# Mapa — Plan E: historial, restauración, bifurcación y Calculadora Financiera

> **Documento de investigación y decisiones.** No es una lista de tareas: las
> tareas viven en `plan-12-historial-calculadora-tareas.md`.
>
> Continúa la numeración de hallazgos y decisiones de los planes 00-10:
> hallazgos **K22+**, decisiones **D43+**.

---

## 0. Punto de partida (Plan D, cerrado y en CI verde — PR #13)

`domain/simulaciones/` tiene: entidades, repositorios, `SimulacionPermisos`,
`NombreSimulacion`, y `SimulacionServiceImpl` con los 6 métodos del CRUD
(`crear`, `detalle`, `listar`, `actualizar`, `eliminar`, `cronograma`).
`SimulacionLogRepository` es un `JpaRepository` sin métodos propios —
deliberadamente, su KDoc dice *"el historial con diff y la ventana de
restauración son de Plan E"*.

Este plan cierra la parte de **Fase 2** que quedaba abierta (historial, §7) y
toda la **Fase 4** del encargo (Calculadora Financiera, §9). No toca Fase 5
(jobs) ni Fase 6 (documentación de contrato) — esas son Plan F.

---

## 1. Documentos que gobiernan este plan

| Documento | Qué manda aquí |
|---|---|
| `docs/reglas_simulaciones.md` §6.3, §7, §8.1, §9, §13 | Fuente de verdad de este plan completo |
| `docs/planes/plan-09-mapa-simulaciones-modulo.md` | Contexto ya cerrado: K10-K21, D30-D42. Sigue vigente sin cambios |
| `src/main/resources/db/migration/V43__create_simulaciones.sql` | `simulacion_log`, `chk_simulacion_log_snapshot`, `uq_simulacion_principal`, `chk_simulacion_principal_requiere_item` |
| `CLAUDE.md` | Reglas 1, 8, 9, 10, 11, 12, 14 |

### Reglas de `CLAUDE.md` en los puntos nuevos de este plan

| Regla | Cómo aplica aquí específicamente |
|---|---|
| **1. TDD** | El diff (§7.1) es lógica pura verificable sin mocks — TDD estricto, como `NombreSimulacion` en Plan D |
| **10. `@Transactional`** | `restaurar` y `bifurcar` escriben en `simulaciones` **y** en `simulacion_log` — la transacción cubre ambas, o un fallo parcial deja la bitácora mintiendo |
| **11. Queries parametrizadas** | La consulta de historial va con parámetros nombrados de Spring Data, nunca concatenación |
| **12. Frontera de módulos** | La Calculadora **no** amplía la superficie con `oportunidades`: no necesita nada nuevo (K26) |
| **14. IDOR** | `restaurar`/`bifurcar`/`marcarPrincipal` sobre una simulación ajena → 404, vía `SimulacionPermisos.exigirAlcance` |

---

## 2. Hallazgos

### K22 — El historial (§7.2) solo lista tres tipos de evento, por diseño explícito

La query literal de §7.2 filtra `tipo_evento IN ('creada', 'editada',
'restaurada')`. **Excluye a propósito** `marcada_principal`, `enlazada_a_item` y
`eliminada`: los dos primeros no llevan snapshot completo (el CHECK no se lo
exige), y `eliminada` es el fin de la simulación, no un estado al que quepa
volver mientras la fila sigue viva.

El endpoint de historial devuelve **como máximo 15 filas**, de esos tres tipos,
de los últimos 7 días — no es un log completo. Un "ver bitácora completa" no
está pedido por el encargo; si hiciera falta, es una tarea nueva.

### K23 — El diff necesita el evento anterior aunque caiga fuera de la ventana

§7.1: *"comparando los snapshots de dos eventos **consecutivos**"* —
consecutivos en la bitácora completa, no dentro de los 7 días/15 filas que
devuelve el historial. Si la fila más antigua devuelta no es el primer evento
que existe, su diff se calcula contra **ese** predecesor real, aunque esté
fuera de la ventana. Si sí es el primero de todos, su diff es vacío.

### K24 — `marcada_principal` y `enlazada_a_item` no llevan snapshot

`chk_simulacion_log_snapshot` para estos dos tipos solo exige
`id_oportunidad_item IS NOT NULL`. Si se registraran con `registrarEvento` (que
arma el snapshot completo) el INSERT pasaría igual —los campos de más no violan
nada—, pero sería semánticamente falso: un evento de solo-enlace fingiría que
hubo edición de parámetros. Hace falta una vía de registro más liviana.

### K25 — El PATCH ya reenlaza ítems, pero nunca registra `enlazada_a_item`

Plan D lo dejó explícito en el propio código: *"El evento `enlazada_a_item` NO
se registra aquí (es Plan E): en Plan D toda edición es un solo `editada`"*.
Deuda intencional que este plan salda (D45).

### K26 — La Calculadora no necesita ni permisos ni endpoints nuevos

§9: el botón "Enlazar a Oportunidad" convierte el cálculo efímero en una
simulación real — eso **es literalmente `POST /simulaciones`** (`crear`, ya
implementado): mismos parámetros, mismas validaciones §13, mismo
`exigirAlcance` sobre el ítem. No hay lógica nueva de enlace que escribir.

Y la columna "Calculadora Financiera" de la tabla de permisos §10
(`admin`/`gerencia`/`analista`/`vendedor` sí, `jdv`/`otro` no) es **exactamente**
`SimulacionPermisos.exigirAcceso`, ya existente. Cero permiso nuevo.

Lo genuinamente nuevo es solo el cálculo efímero: sin `idOportunidadItem`, sin
persistencia ni en éxito ni en fallo (§9: "no deja rastro de auditoría").

### K27 — Bifurcar es "crear a partir de una plantilla", no "editar con otro id"

§7.3: fila nueva con `id_simulacion_origen`, hereda el ítem si lo tenía. Es la
única vía autorizada para cambiar de `modo` (§2). La fila nueva pasa por las
mismas validaciones §13 y el mismo motor que `crear` (D35), y nace con su
propio evento `creada` — el enum no tiene tipo "bifurcada" — pero ese log lleva
`idSimulacionOrigen` poblado (columna existente desde Plan D, hoy sin usar).

### K28 — `marcarPrincipal` exige tener ítem, verificado antes de tocar la BD

§6.3: *"Puede cambiarse manualmente"*. Sin ítem no hay principal posible
(`chk_simulacion_principal_requiere_item`). El Service debe rechazarlo con un
error de negocio limpio antes de intentar el UPDATE — mismo criterio que
K16/D36 aplicó a `modo`.

### K29 — **Dos eventos de la misma transacción pueden compartir `created_at`**

Hallazgo de la revisión, no obvio al escribir la primera versión de este plan.

Varias operaciones de este plan escriben **dos filas de log en una sola
transacción**: `restaurar` (el `editada` del estado previo y luego el
`restaurada`), y el PATCH que reenlaza (`enlazada_a_item` + `editada`, D45).
`registrarEvento` recibe el `momento` como parámetro explícito, y nada impide
que dos llamadas consecutivas reciban el mismo `LocalDateTime.now()` si la
resolución del reloj es más gruesa que el intervalo entre ambas.

**Consecuencia:** cualquier consulta de historial que ordene o compare **solo
por `created_at`** puede devolver dos eventos empatados en orden indefinido, o
—peor— saltarse uno al buscar "el evento anterior a este". El desempate por
`id` no es cosmético: es requisito de corrección. Ya hay precedente en el repo:
la query `correlativos` de Plan D ordena por `(created_at, id)` exactamente por
esto.

### K30 — El diff no puede mostrar cambios de enlace, y eso es correcto por spec

Los campos que el diff compara son los 10 del snapshot; **`id_oportunidad_item`
no está entre ellos**, y el evento que sí lo lleva (`enlazada_a_item`) está
excluido del historial por el filtro de §7.2 (K22).

Consecuencia: un PATCH que solo reenlaza produce un `editada` cuyo **diff sale
vacío**. No es un bug: Plan D ya registra `editada` en todo `actualizar`,
incluido el PATCH vacío, y su test de D11 lo fija así explícitamente. Un
`editada` de diff vacío significa "hubo una escritura que no cambió ninguno de
los 10 parámetros", que es exactamente lo que pasó.

**No lo arregles en este plan.** Cambiar el filtro de §7.2 o los campos del
diff sería contradecir la spec vigente. Queda documentado como limitación
conocida; si el frontend necesita ver los reenlaces, es una decisión de
producto nueva, no una corrección.

---

## 3. Decisiones

### D43 — Historial en dos queries, con desempate por `(created_at, id)`

**Corrige la primera versión de esta decisión**, que proponía un CTE con
`UNION ALL` y comparaba solo por `created_at`: eso arrastra el bug de K29 y
además complica el mapeo de Spring Data sin ganar nada.

Se añaden a `SimulacionLogRepository` dos métodos:

```kotlin
/**
 * Los eventos con snapshot (creada/editada/restaurada) de los ultimos 7 dias,
 * hasta 15, mas recientes primero (§7.2). El desempate por `id` no es
 * cosmetico: dos eventos de la misma transaccion pueden compartir `created_at`
 * (K29), y sin el, el orden entre ellos queda indefinido.
 */
@Query(
    value = """
        SELECT * FROM simulacion_log
        WHERE id_simulacion = :idSimulacion
          AND tipo_evento IN ('creada', 'editada', 'restaurada')
          AND created_at > now() - interval '7 days'
        ORDER BY created_at DESC, id DESC
        LIMIT 15
    """,
    nativeQuery = true,
)
fun historial(idSimulacion: Long): List<SimulacionLog>

/**
 * El evento con snapshot inmediatamente ANTERIOR al par (`momento`, `id`) dado,
 * sin filtro de ventana: el diff del evento mas antiguo del historial se
 * calcula contra su predecesor real aunque caiga fuera de los 7 dias (K23).
 *
 * Compara el par completo, no solo `created_at`: con dos eventos empatados en
 * timestamp (K29), comparar solo por fecha se saltaria el de `id` menor.
 */
@Query(
    value = """
        SELECT * FROM simulacion_log
        WHERE id_simulacion = :idSimulacion
          AND tipo_evento IN ('creada', 'editada', 'restaurada')
          AND (created_at < :momento OR (created_at = :momento AND id < :id))
        ORDER BY created_at DESC, id DESC
        LIMIT 1
    """,
    nativeQuery = true,
)
fun eventoAnteriorA(idSimulacion: Long, momento: LocalDateTime, id: Long): SimulacionLog?
```

El Service llama `historial(...)` y, si la lista no está vacía, una sola vez
`eventoAnteriorA(...)` con el par de la fila más antigua devuelta. Dos
consultas fijas por petición, sin N+1.

### D44 — El diff se computa en memoria, campo por campo, sobre los 10 del snapshot

`DiffSimulacion.kt`, función pura en un `object`, mismo estilo que
`NombreSimulacion`:

```kotlin
fun calcular(anterior: SimulacionLog?, actual: SimulacionLog): List<CampoDiffDto>
```

Compara `modo`, `precioVenta`, `descuento`, `cuotaInicial`, `plazoMeses`, `tea`,
`valorResidual`, `diasTrabajados`, `comisionEstructuracion`, `cuotaFinal`. Un
campo aparece **solo si cambió**; los `BigDecimal` se comparan por valor
(`compareTo`, nunca `equals` — `100.00` y `100.0` son el mismo importe). Si
`anterior == null` (primer evento de todos, K23), la lista es vacía.

### D45 — `actualizar` gana el evento `enlazada_a_item`, sin cambiar su firma

Salda K25. Cuando el PATCH cambia `idOportunidadItem` (de null a un valor, o de
un valor a otro), además del `editada` que ya se registra, se registra un
`enlazada_a_item` con snapshot mínimo.

Un PATCH que solo reenlaza produce **dos** filas de log; uno que reenlaza y
además cambia `tea`, también dos. El enum no tiene un tipo combinado, así que
dos filas es la única forma de no perder ninguna de las dos señales.

**El `editada` se sigue registrando incondicionalmente**, igual que en Plan D —
no compares campo por campo para decidir si "vale la pena". Su diff saldrá
vacío en el caso de solo-reenlace, y eso es correcto (K30).

### D46 — `marcarPrincipal` es una operación propia, no un campo de `actualizar`

```kotlin
fun marcarPrincipal(id: Long, usuario: UsuarioActual): SimulacionDto
```

1. `exigirAcceso` + `exigirAlcance`, como el resto.
2. `idOportunidadItem == null` → `ConflictoException(code = "SIMULACION_SIN_ITEM")`
   (409) **antes de tocar la base** (K28). Es 409 y no 400 porque el problema
   es el estado actual del recurso, no la forma de la petición.
3. Ya principal → **no-op exitoso**: devuelve el DTO sin escribir ni registrar
   evento. Evita un `marcada_principal` vacío de significado.
4. Si no: `desmarcarPrincipalDe(idItem)` → `esPrincipal = true` → `save` →
   registrar `marcada_principal` (snapshot mínimo, D47).

Endpoint `PATCH /simulaciones/:id/principal`, sin body. §6.3 habla de
"cambiarse", no de "editarse": es una ruta propia, no un campo del PATCH
general.

### D47 — Registro liviano, separado de `registrarEvento`

```kotlin
/**
 * Eventos SIN snapshot (marcada_principal, enlazada_a_item): el CHECK
 * `chk_simulacion_log_snapshot` solo exige `id_oportunidad_item` para estos
 * dos tipos (K24). Meter aqui el snapshot completo seria semanticamente
 * incorrecto —el evento no representa una edicion de parametros— aunque el
 * CHECK lo dejara pasar.
 */
private fun registrarEventoDeEnlace(
    idSimulacion: Long,
    idOportunidadItem: Long,
    usuario: UsuarioActual,
    tipoEvento: TipoEventoSimulacion,
)
```

### D48 — `restaurar`: recalcular y **revalidar**, nunca copiar

**Corrige la primera versión de esta decisión**, que proponía saltarse las
validaciones §13 "porque un estado que ya fue válido sigue siéndolo". Ese
razonamiento es falso justo en el escenario que §7.2 anticipa: si hubo una
corrección de fórmula entre medio, `principal` cambia, y un
`valor_residual < principal` que se cumplía puede dejar de cumplirse. Peor: sin
la validación, el motor podría lanzar `CronogramaInconsistenteException`, que
es **500**, en vez de un 400 limpio. Restaurar corre las mismas validaciones
que `crear`, y punto.

```kotlin
fun restaurar(id: Long, idEventoLog: Long, usuario: UsuarioActual): SimulacionDto
```

Orden literal de §7.2:

1. `exigirAcceso` + cargar simulación + `itemDe` + `exigirAlcance`.
2. Cargar el `SimulacionLog` por `idEventoLog`. **404** (`NoEncontradoException`)
   si: no existe · `log.idSimulacion != id` · su `tipoEvento` no está en
   `{creada, editada, restaurada}` · su `createdAt` es anterior a hace 7 días.
   Se trata como "esa versión no es restaurable" en vez de 409, para no filtrar
   por status code si la simulación además es ajena.
   El límite de 15 versiones **no se valida aquí**: lo aplica la query del
   historial al construir la lista visible, y §7.2 lo define como filtro de
   lectura, no como regla de escritura.
3. Registrar el estado ACTUAL (antes de mutar) como evento `editada`, con
   `createdAt = ahora` — **no** el `updatedAt` viejo de la entidad. El log es
   un diario cronológico: el evento "se guardó el estado previo" ocurre ahora.
   Fecharlo en el pasado insertaría una fila fuera de orden y rompería el
   cálculo del diff, que asume orden cronológico.
4. Copiar del snapshot a la entidad: `precioVenta`, `descuento`, `cuotaInicial`,
   `plazoMeses`, `tea`, `valorResidual`, `diasTrabajados`,
   `comisionEstructuracion`. **`modo` no se copia** (es `val`, y de todas
   formas no pudo cambiar: es la misma simulación).
   **`esPrincipal` e `idOportunidadItem` tampoco se restauran**: no están en el
   snapshot y no son parámetros de cálculo. Restaurar parámetros no debe mover
   la simulación de ítem ni cambiar quién es principal.
5. Validar §13 y recalcular, en el mismo orden que `crear`:
   `exigirCuotaInicialMenorQuePrecioEfectivo` → motor **una vez** →
   `exigirValorResidualMenorQuePrincipal`. `cuotaFinal` sale del motor;
   **nunca** se lee `log.cuotaFinal` (§7.2 paso 3).
6. `updatedAt`/`updatedBy` al momento y usuario actuales. `save`.
7. Registrar `restaurada` con el snapshot COMPLETO posterior (`registrarEvento`).
8. Devolver el DTO.

### D49 — `bifurcar` construye una fila nueva; el origen solo pierde el rol de principal

```kotlin
fun bifurcar(id: Long, request: BifurcarSimulacionRequest, usuario: UsuarioActual): SimulacionDto
```

`BifurcarSimulacionRequest`: mismos campos y validaciones que
`ActualizarSimulacionRequest`, **incluido `modo`** — que aquí NO pasa por
`exigirModoInmutable` (K27: esta es la vía autorizada para cambiarlo).

1. `exigirAcceso` + cargar el ORIGEN + `itemDe` del origen + `exigirAlcance`.
2. `modo` = el del request si viene, si no el del origen.
3. **Ítem**: hereda el del origen (§7.3); si el request pide otro distinto, se
   resuelve con `resolverItem` (misma validación de alcance sobre el ítem
   nuevo que en `actualizar`).
4. Resto de campos: el del request si viene, si no el del origen.
5. Validaciones §13 + motor una vez (D35), igual que `crear`.
6. **Relevo de principal** (D38): si hay ítem, `desmarcarPrincipalDe(idItem)`
   antes de insertar, y la nueva nace `esPrincipal = true`. Sin ítem,
   `esPrincipal = false`.
7. Persistir la fila NUEVA con `idSimulacionOrigen = origen.id`,
   `createdBy = updatedBy = usuario.id`, `createdAt = updatedAt = ahora`.
8. Evento `creada` para la fila NUEVA con `idSimulacionOrigen` poblado en el
   log (K27). `registrarEvento` gana un parámetro
   `idSimulacionOrigen: Long? = null`.
9. Devolver el DTO de la simulación NUEVA.

**Sobre el origen** (precisión que la primera versión de esta decisión decía
mal, afirmando que "no se toca"): la entidad del origen no se muta ni se
guarda desde el Service. Pero si la bifurcada hereda su mismo ítem y pasa a ser
principal, el `desmarcarPrincipalDe` del paso 6 **sí actualiza la fila del
origen** en la base (un `UPDATE` masivo por ítem, no un `save(origen)`). Es el
mismo relevo que hace `crear` y es correcto — §6.3 dice que la principal es la
última creada para ese ítem, y una bifurcación es una creación.

**`nombre` no se hereda**: sale solo del request. Heredar un nombre manual
dejaría dos simulaciones con el mismo título; sin nombre, la bifurcada
autogenera el suyo y el correlativo `#{n}` ya las distingue (§8.1).

### D50 — Calculadora Financiera: servicio y controller propios, sin persistencia

```kotlin
interface CalculadoraFinancieraService {
    fun calcular(request: CalculadoraRequest, usuario: UsuarioActual): CalculadoraDto
}
```

- **Permisos**: `SimulacionPermisos.exigirAcceso` (K26). Nada nuevo.
- **Sin ítem, nunca**: `CalculadoraRequest` no declara `idOportunidadItem`.
  Puede traer `idEmpresa`/`idModelo` opcionales, solo para mostrarlos.
- **Validación §13**: las mismas dos que `crear`, vía el objeto compartido de
  D51.
- **Cero escritura**: no inyecta `SimulacionRepository` ni
  `SimulacionLogRepository`. Que el constructor ni siquiera los reciba es la
  garantía estructural de §9 — mejor que un test de `verify(exactly = 0)`.
- **Endpoint**: `POST /api/v1/calculadora`, controller propio. §9 lo llama
  "módulo aparte"; colgarlo de `/simulaciones` sugeriría que comparte su ciclo
  de vida, cuando la premisa entera es que no persiste.
- **"Enlazar a Oportunidad" no es un endpoint nuevo** (K26): es el frontend
  llamando a `POST /simulaciones` con los mismos parámetros más el
  `idOportunidadItem` elegido. Ninguna tarea debe crear un endpoint de
  "enlazar" — sería una segunda vía de creación, divergente de la primera.

### D51 — Lo compartido entre el flujo que persiste y el que no, a dos objetos propios

La Calculadora necesita exactamente las mismas validaciones §13 y los mismos
valores por defecto que `crear`. Duplicarlos sería una segunda copia de reglas
de negocio — el error que el Plan C tuvo que limpiar con `MontoTotal`.

Se extraen de `SimulacionServiceImpl` a dos `object` puros, **antes** de que
existan más llamadores (por eso es la primera tarea del plan, no la novena):

- `ValidacionesSimulacion.kt`: `exigirCuotaInicialMenorQuePrecioEfectivo` y
  `exigirValorResidualMenorQuePrincipal`, movidas **tal cual** — mismos
  mensajes, mismos `field`. Es un refactor de ubicación, no de comportamiento.
- `DefaultsSimulacion.kt`: los cuatro defaults hoy en el `companion object` de
  `SimulacionServiceImpl` (`descuento` 0, `valorResidual` 0, `diasTrabajados`
  22, `comisionEstructuracion` 1180), con el comentario que ya llevan
  explicando que replican el `DEFAULT` de columna de V43 y **no** la tabla de
  §6.1 (cuyo `valor_residual` es 25 000 y pertenece a la cuota efímera del
  ítem, que es Plan F).

Los tests existentes de `crear`/`actualizar` que verifican estos valores y
estos mensajes deben seguir pasando **sin tocarlos**: es la prueba de que el
refactor no cambió comportamiento.

### D52 — Un solo documento de tareas

El volumen es menor que Plan D y las piezas están desacopladas (historial no
interactúa con Calculadora). Un solo documento,
`plan-12-historial-calculadora-tareas.md`, en tres frentes: refactor previo y
ciclo de vida (E1-E8), Calculadora (E9-E10), cierre (E11-E12).

### D53 — Lo que este plan deliberadamente NO hace

- No toca `oportunidades` en absoluto (K26: no hace falta nada nuevo).
- No implementa la cuota agregada en `/oportunidades` (§6.2) — Plan F.
- No implementa jobs de purga/aviso — Plan F.
- No genera PDF ni Excel (K20 del mapa de Plan D sigue vigente).
- **No escribe ninguna migración**: `simulacion_log` y todas sus columnas
  (`id_simulacion_origen` incluida) existen desde V43. Si una tarea cree que
  necesita una, es señal de que algo se está diseñando mal — **detente y
  consulta**.
- No toca `contrato_api.md`/`matriz_permisos.md` — Plan F los salda de una vez
  para todo el módulo (la auditoría de Plan D ya señaló esa deuda acumulada).
- **No arregla K30**: los reenlaces siguen sin verse en el diff del historial.
  Es lo que la spec vigente define.

---

## 4. Alcance — lista cerrada de archivos

### Nuevos

```
src/main/kotlin/pe/quantum/crm/domain/simulaciones/ValidacionesSimulacion.kt
src/main/kotlin/pe/quantum/crm/domain/simulaciones/DefaultsSimulacion.kt
src/main/kotlin/pe/quantum/crm/domain/simulaciones/DiffSimulacion.kt
src/main/kotlin/pe/quantum/crm/domain/simulaciones/CalculadoraFinancieraService.kt
src/main/kotlin/pe/quantum/crm/domain/simulaciones/CalculadoraFinancieraServiceImpl.kt
src/main/kotlin/pe/quantum/crm/domain/simulaciones/CalculadoraFinancieraController.kt
src/test/kotlin/pe/quantum/crm/domain/simulaciones/DiffSimulacionTest.kt
src/test/kotlin/pe/quantum/crm/domain/simulaciones/CalculadoraFinancieraServiceImplTest.kt
src/test/kotlin/pe/quantum/crm/domain/simulaciones/CalculadoraFinancieraControllerWebMvcTest.kt
```

### Modificados

```
src/main/kotlin/pe/quantum/crm/domain/simulaciones/SimulacionLogRepository.kt      (+ historial, eventoAnteriorA — D43)
src/main/kotlin/pe/quantum/crm/domain/simulaciones/SimulacionService.kt            (+ historial, restaurar, bifurcar, marcarPrincipal)
src/main/kotlin/pe/quantum/crm/domain/simulaciones/SimulacionServiceImpl.kt        (+ 4 metodos, evento de enlace en actualizar, extraccion D51)
src/main/kotlin/pe/quantum/crm/domain/simulaciones/SimulacionController.kt         (+ 4 endpoints)
src/main/kotlin/pe/quantum/crm/domain/simulaciones/dto/SimulacionDtos.kt           (+ 6 DTOs)
src/test/kotlin/pe/quantum/crm/domain/simulaciones/SimulacionServiceImplTest.kt
src/test/kotlin/pe/quantum/crm/domain/simulaciones/SimulacionControllerWebMvcTest.kt
src/test/kotlin/pe/quantum/crm/domain/simulaciones/SimulacionRepositoryTest.kt     [@Tag("integration")]
```

`SchemaMigrationIntegrationTest.kt` **no se toca** (D53, sin migraciones).
Cualquier archivo fuera de esta lista: detente y consulta.

---

## 5. Riesgos conocidos y cómo los ataja el plan

| Riesgo | Mitigación |
|---|---|
| Empate de `created_at` entre eventos de una misma transacción (K29) | D43 desempata por `id` en las dos queries; la tarea trae el SQL literal y un test de integración que siembra el caso |
| Confundir la ventana de lectura (§7.2) con política de borrado | D48 paso 2 solo filtra qué se puede restaurar; el log nunca se toca. Repetido en la tarea |
| Restaurar un estado que dejó de ser válido tras una corrección de fórmula | D48 paso 5: se revalida §13, no se asume validez heredada. Sin esto sería un 500 |
| Un evento de enlace con snapshot completo (semánticamente falso) | D47 separa el método; la auditoría verifica que ningún `marcada_principal`/`enlazada_a_item` pase por `registrarEvento` |
| Duplicar validaciones o defaults en la Calculadora | D51 los extrae **antes** de sumar llamadores; los tests de Plan D no se tocan y sirven de red |
| La Calculadora terminando con una escritura "solo para logging" | D50: los repositorios no entran al constructor; es imposible por tipo, no por convención |
| Interpretar el `editada` de diff vacío como bug (K30) | Documentado como comportamiento correcto y heredado de Plan D; la auditoría lo verifica en vez de "arreglarlo" |
