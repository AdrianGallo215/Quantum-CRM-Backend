
# Plan E — Historial, restauración, bifurcación y Calculadora Financiera

> **Destinatario: agentes ejecutores, no humanos.** Cada tarea es autocontenida.
> Ejecutar en orden estricto: cada una asume que la anterior está cerrada,
> compilando y con `./gradlew test` en verde.
>
> **Regla para el ejecutor:** si algo de tu tarea es ambiguo, contradice a otra
> tarea, o el repo no coincide con lo que la tarea describe — **detente y
> consulta al arquitecto**. No infieras, no inventes, no "arregles" de paso nada
> que la tarea no te pida. Esto es producción con usuarios reales.
>
> **Sobre los tests `@Tag("integration")`:** Docker Desktop 29 rompe el cliente
> de Testcontainers en local. **Ninguna tarea puede confirmarlos en verde**;
> solo lo hace CI. La tarea que toque uno debe decir explícitamente *"no
> ejecutable en local, verificado por lectura cuidadosa"*, nunca reportar un
> falso verde.
>
> **Infraestructura (vale para todas las tareas):** si Gradle falla con
> `CorruptedException`, "Could not delete" o "Failed to clean up output files"
> (locks de Windows), mata los `java.exe` colgados, `./gradlew --stop`, borra
> `build/` y reintenta con `--no-daemon`. **Nunca confíes en el exit code de un
> pipeline con `| tail`**: redirige a archivo y comprueba `$?` aparte.
>
> **MSYS/Git-Bash, problema conocido de este repo:** `--tests '*Simulacion*'`
> puede expandirse contra `Instrucciones_simulaciones.md` del directorio de
> trabajo y hacer que Gradle reporte "No tests found" en vez de correr nada.
> Usa siempre patrones específicos (`--tests '*SimulacionServiceImpl*'`,
> `--tests '*Calculadora*'`, `--tests '*Arquitectura*'`).

---

## Fase de investigación (leer antes de la Task E1)

### Documentos que gobiernan este plan

| Documento | Qué manda |
|---|---|
| `docs/planes/plan-11-mapa-historial-calculadora.md` | **Léelo entero primero.** Hallazgos K22-K30, decisiones D43-D53 |
| `docs/reglas_simulaciones.md` §6.3, §7, §8.1, §9, §13 | Comportamiento exacto de esta fase |
| `docs/planes/plan-09-mapa-simulaciones-modulo.md` | Contexto de Plan D (K10-K21, D30-D42), vigente sin cambios |
| `src/main/resources/db/migration/V43__create_simulaciones.sql` | El schema real. **Este plan no añade migraciones** |
| `CLAUDE.md` | Reglas 1, 8, 9, 10, 11, 12, 14 |

### Las cuatro trampas de este plan

1. **Dos eventos de la misma transacción pueden compartir `created_at`** (K29).
   Toda consulta de historial desempata por `(created_at, id)`. Ordenar o
   comparar solo por fecha es un bug de corrección, no un detalle de estilo.
2. **El diff necesita el evento anterior aunque caiga fuera de la ventana de 7
   días** (K23). No lo calcules contra `null` solo porque está en el borde.
3. **`restaurar` recalcula `cuotaFinal` con el motor y revalida §13** (D48).
   Nunca copia `log.cuotaFinal`, y no asume que un estado antiguo sigue siendo
   válido: si hubo corrección de fórmula, puede no serlo.
4. **`marcada_principal`/`enlazada_a_item` NO llevan snapshot completo** (K24).
   Van por `registrarEventoDeEnlace`, nunca por `registrarEvento`.

### Alcance — lista cerrada de archivos

Ver `plan-11-mapa-historial-calculadora.md` §4. Cualquier archivo fuera de esa
lista: **detente y consulta**.

---

## Tabla de tareas

| ID | Tarea | Modelo | Esfuerzo |
|---|---|---|---|
| E1 | Extraer `ValidacionesSimulacion` y `DefaultsSimulacion` (D51) | Sonnet 5 | Medium |
| E2 | `DiffSimulacion`: cálculo puro del diff (D44) | Sonnet 5 | Medium |
| E3 | Queries de historial en `SimulacionLogRepository` (D43) | Opus 5 | High |
| E4 | `SimulacionService.historial` + DTOs (K23, K30) | Opus 5 | High |
| E5 | Registro liviano + evento de enlace en `actualizar` (D45, D47) | Opus 5 | High |
| E6 | `SimulacionServiceImpl.restaurar` (D48) | Opus 5 | Extra High |
| E7 | `SimulacionServiceImpl.marcarPrincipal` (D46) | Sonnet 5 | Medium |
| E8 | `SimulacionServiceImpl.bifurcar` (D49) | Opus 5 | Extra High |
| E9 | 4 endpoints nuevos en `SimulacionController` + WebMvc | Sonnet 5 | High |
| E10 | `CalculadoraFinancieraService`/Impl (D50) | Opus 5 | High |
| E11 | `CalculadoraFinancieraController` + WebMvc | Sonnet 5 | Medium |
| E12 | Verificación de build completa (local, sin `integrationTest`) | Sonnet 5 | Low |
| E13 | Auditoría final del diff contra los documentos citados | Opus 5 | High |

**El orden importa en un punto**: E1 (el refactor) va primero **a propósito**,
antes de que E6/E8/E10 añadan llamadores nuevos a las validaciones y defaults.
Hacerlo al final obligaría a reescribir código recién puesto.

**Fuera de este plan** (Plan F): cuota agregada en `/oportunidades` (§6.2),
migración de enums de notificaciones, jobs de purga y aviso, documentación de
`contrato_api.md`/`matriz_permisos.md`/`CLAUDE.md`.

---

## E1 · Extraer `ValidacionesSimulacion` y `DefaultsSimulacion`

**Modelo:** Sonnet 5 · **Esfuerzo:** Medium

Lee `plan-11-mapa-historial-calculadora.md` decisión **D51**. Refactor
mecánico, **sin cambio de comportamiento**, que va primero porque las tareas
E6/E8/E10 van a sumar llamadores a estas piezas.

Abre `src/main/kotlin/pe/quantum/crm/domain/simulaciones/SimulacionServiceImpl.kt`
completo — localiza los métodos privados
`exigirCuotaInicialMenorQuePrecioEfectivo` y
`exigirValorResidualMenorQuePrincipal`, y el `private companion object` con los
cuatro defaults y la constante `CIEN`.

### `ValidacionesSimulacion.kt`

```kotlin
/**
 * Validaciones de negocio §13 de reglas_simulaciones.md, compartidas entre
 * `SimulacionServiceImpl` (que persiste) y `CalculadoraFinancieraServiceImpl`
 * (que no persiste, pero corre las mismas reglas sobre el mismo motor).
 * Funciones puras: reciben los `BigDecimal` y lanzan `ValidacionException`.
 */
object ValidacionesSimulacion {
    fun exigirCuotaInicialMenorQuePrecioEfectivo(
        precioVenta: BigDecimal,
        descuento: BigDecimal,
        cuotaInicial: BigDecimal,
    ) { /* cuerpo movido TAL CUAL */ }

    fun exigirValorResidualMenorQuePrincipal(
        valorResidual: BigDecimal,
        resultado: ResultadoSimulacion,
    ) { /* cuerpo movido TAL CUAL */ }
}
```

Mueve también la constante privada `CIEN` (la base porcentual del descuento),
que solo usa la primera validación.

### `DefaultsSimulacion.kt`

```kotlin
/**
 * Valores por defecto al crear una simulacion. Replican el `DEFAULT` de
 * columna de V43, NO la tabla de reglas_simulaciones.md §6.1 —cuyo
 * `valor_residual` es 25 000 y pertenece a la cuota efimera del item, un
 * calculo distinto que no crea ninguna simulacion (Plan F).
 *
 * Compartidos entre `SimulacionServiceImpl` y la Calculadora Financiera (D51).
 */
object DefaultsSimulacion {
    val DESCUENTO: BigDecimal = BigDecimal.ZERO
    val VALOR_RESIDUAL: BigDecimal = BigDecimal.ZERO
    const val DIAS_TRABAJADOS = 22
    val COMISION_ESTRUCTURACION: BigDecimal = BigDecimal("1180")
}
```

Conserva el razonamiento del comentario que hoy vive en el `companion object`
de `SimulacionServiceImpl` (el que explica por qué los defaults de §6.1 de
`plazo_meses`/`tea`/`cuota_inicial` **no** están aquí). Ese comentario es la
corrección de un hallazgo de la auditoría de Plan D — no lo pierdas.

### Actualizar los llamadores

En `SimulacionServiceImpl`, sustituye las llamadas a los métodos privados por
`ValidacionesSimulacion.…` y las referencias a las constantes por
`DefaultsSimulacion.…`, y borra los privados/constantes que quedan huérfanos.
`CAMPOS_ORDENABLES` y `CORRELATIVO_INICIAL` **se quedan** donde están: son de
`SimulacionServiceImpl`, no compartidos.

**No cambies ningún mensaje de error ni ningún `field` de las excepciones.**

**Restricciones:** solo estos tres archivos (dos nuevos + `SimulacionServiceImpl`).
**No toques ningún archivo de test**: que los tests de Plan D pasen sin
modificarlos es la prueba de que el refactor no cambió comportamiento. Si algún
test falla, es que cambiaste algo — arréglalo en el código de producción, no en
el test.

**Criterio de aceptación:**
```bash
./gradlew compileKotlin compileTestKotlin ktlintCheck detekt --console=plain -q --no-daemon > /tmp/e1a.log 2>&1; echo "EXIT:$?"
./gradlew test --tests '*SimulacionServiceImpl*' --tests '*SimulacionCronograma*' --console=plain -q --no-daemon > /tmp/e1b.log 2>&1; echo "EXIT:$?"
```
ambos en EXIT:0, **con `git status --short` mostrando cero archivos de test
modificados**.

---

## E2 · `DiffSimulacion`: cálculo puro del diff

**Modelo:** Sonnet 5 · **Esfuerzo:** Medium

Lee `plan-11` decisión **D44** y `reglas_simulaciones.md` §7.1. Abre
`NombreSimulacion.kt` como referencia de estilo (función pura en un `object`) y
`SimulacionLog.kt` (los campos y su nulabilidad).

Crea `src/main/kotlin/pe/quantum/crm/domain/simulaciones/DiffSimulacion.kt`:

```kotlin
object DiffSimulacion {
    fun calcular(anterior: SimulacionLog?, actual: SimulacionLog): List<CampoDiffDto>
}
```

Y `CampoDiffDto` en `dto/SimulacionDtos.kt`:

```kotlin
/** Un campo que cambio entre dos eventos consecutivos del historial (§7.1). */
data class CampoDiffDto(
    val campo: String,
    val valorAnterior: String?,
    val valorNuevo: String?,
)
```

Compara estos 10 campos, en este orden, incluyendo en el resultado **solo los
que cambiaron**: `modo`, `precioVenta`, `descuento`, `cuotaInicial`,
`plazoMeses`, `tea`, `valorResidual`, `diasTrabajados`,
`comisionEstructuracion`, `cuotaFinal`. El nombre de `campo` va en camelCase
(`"precioVenta"`, no `"precio_venta"`).

Reglas:
- **`BigDecimal` se compara por VALOR** (`compareTo(...) != 0`, nunca
  `equals`): `100.00` y `100.0` son el mismo importe y NO deben salir como
  cambio. Este es el punto que más fácil se hace mal.
- `Int` y el enum `modo`: comparación directa.
- `anterior == null` → lista vacía (primer evento de todos, K23).
- Formato de los valores: `toPlainString()` para `BigDecimal`, `toString()`
  para `Int`, `.name` para `modo` (el valor del enum, no una etiqueta
  traducida). `null` si el campo es null en ese snapshot — `SimulacionLog` los
  declara todos nullable a nivel de tipo, así que la función tiene que ser
  defensiva aunque los eventos con snapshot completo nunca los traigan vacíos.
- Si un campo pasa de un valor a `null` o al revés, **también es un cambio** y
  debe aparecer, con el lado correspondiente en `null`.

### Test: `DiffSimulacionTest.kt`

TDD: primero los tests. Sin mockk. Construye `SimulacionLog` directamente.

1. `anterior = null` → diff vacío.
2. Dos snapshots idénticos → diff vacío.
3. Solo `tea` cambia → exactamente un `CampoDiffDto`, con `campo == "tea"` y
   ambos valores correctos.
4. Cambian 3 campos → salen los 3, ninguno de más, en el orden declarado.
5. **`BigDecimal("100.00")` vs `BigDecimal("100.0")` → NO aparece.**
6. `modo` cambia → aparece con `"leasing"`/`"credito_directo"`.
7. Un campo pasa de valor a `null` → aparece, con `valorNuevo = null`.

**Criterio de aceptación:**
```bash
./gradlew compileKotlin compileTestKotlin ktlintCheck detekt --console=plain -q --no-daemon > /tmp/e2a.log 2>&1; echo "EXIT:$?"
./gradlew test --tests '*DiffSimulacion*' --console=plain -q --no-daemon > /tmp/e2b.log 2>&1; echo "EXIT:$?"
```
ambos en EXIT:0.

---

## E3 · Queries de historial en `SimulacionLogRepository`

**Modelo:** Opus 5 · **Esfuerzo:** High

Lee `plan-11` decisión **D43 completa** y hallazgos **K23** y **K29**. Abre
`SimulacionLogRepository.kt` (sin métodos hoy) y `SimulacionRepository.kt`
(referencia: la `@Query` nativa de `correlativos`, que ya desempata por
`(created_at, id)` por esta misma razón).

Añade los **dos** métodos de D43, con el SQL literal que trae esa decisión
—cópialo, no lo reescribas de memoria—:

- `historial(idSimulacion): List<SimulacionLog>` — los eventos con snapshot de
  los últimos 7 días, hasta 15, `ORDER BY created_at DESC, id DESC`.
- `eventoAnteriorA(idSimulacion, momento, id): SimulacionLog?` — el evento con
  snapshot inmediatamente anterior al par dado, **sin filtro de ventana**,
  comparando `(created_at < :momento OR (created_at = :momento AND id < :id))`.

**Por qué el par y no solo la fecha** (K29): `restaurar` y el PATCH que
reenlaza escriben dos filas de log en la misma transacción, y nada garantiza
que `LocalDateTime.now()` devuelva valores distintos en dos llamadas
consecutivas. Comparar solo por `created_at` se saltaría el evento de `id`
menor. Escribe esa razón en el KDoc del método.

Queries parametrizadas (regla 11): nunca concatenación con el id.

### Test

Añade el caso a `SimulacionRepositoryTest.kt` (existente, `@Tag("integration")`).
Siembra para una misma simulación, con `createdAt` explícitos:

- Un evento `creada` de hace **20 días** (fuera de ventana).
- Tres eventos (`editada`) de los últimos 3 días.
- **Dos de esos tres con el MISMO `createdAt`** — es el caso de K29, y es el
  que justifica el desempate; sin él el test no prueba lo que importa.

Verifica por lectura cuidadosa:
- `historial(...)` devuelve los 3 recientes, sin el de 20 días, ordenados
  descendente y con los dos empatados en orden estable por `id` descendente.
- `eventoAnteriorA(...)` con el par del más antiguo de esos 3 devuelve **el de
  20 días** (cruza la ventana, K23).
- `eventoAnteriorA(...)` con el par del más nuevo de los dos empatados devuelve
  **el otro empatado**, no se lo salta.

**Este test es `@Tag("integration")` y NO lo puedes ejecutar en local** (Docker
29). Repórtalo explícitamente como *"no ejecutable en local, verificado por
lectura"*.

**Restricciones:** solo `SimulacionLogRepository.kt` y `SimulacionRepositoryTest.kt`.
No toques el Service ni el Controller.

**Criterio de aceptación:**
```bash
./gradlew compileKotlin compileTestKotlin ktlintCheck detekt --console=plain -q --no-daemon > /tmp/e3.log 2>&1; echo "EXIT:$?"
```
en EXIT:0 (solo compila).

---

## E4 · `SimulacionService.historial` + DTOs

**Modelo:** Opus 5 · **Esfuerzo:** High

Lee `plan-11` hallazgos **K22, K23, K30**. Abre `SimulacionService.kt`,
`SimulacionServiceImpl.kt`, `DiffSimulacion.kt` (E2) y los métodos de E3.

Añade a `SimulacionService`:

```kotlin
/** Historial con diff, ventana de 7 dias / 15 versiones (§7.2). */
fun historial(id: Long, usuario: UsuarioActual): List<EventoHistorialDto>
```

`EventoHistorialDto` en `dto/SimulacionDtos.kt`:

```kotlin
data class EventoHistorialDto(
    val idEventoLog: Long,
    val tipoEvento: String,
    val createdAt: Instant,
    /** Null cuando el evento lo genero un job sin actor humano. */
    val createdBy: Long?,
    /**
     * Campos que cambiaron respecto del evento anterior. Vacio si es el primer
     * evento de la simulacion, y tambien cuando la escritura no toco ninguno de
     * los 10 parametros del snapshot —p. ej. un PATCH que solo reenlaza a otro
     * item, o un PATCH vacio— (K30). Un diff vacio es informacion honesta, no
     * un error.
     */
    val diff: List<CampoDiffDto>,
)
```

Implementación de `historial`, `@Transactional(readOnly = true)`:

1. `permisos.exigirAcceso(usuario)`; `entidad(id)`; `itemDe(...)`;
   `permisos.exigirAlcance(...)` → 404.
2. `simulacionLogRepository.historial(id)` (más recientes primero).
3. Si la lista está vacía, devuelve lista vacía sin más consultas.
4. Una sola llamada a `eventoAnteriorA(id, masAntiguo.createdAt, masAntiguo.id)`
   con el par de la ÚLTIMA fila de la lista (la más antigua). Dos consultas
   fijas por petición, nunca una por evento.
5. Calcula el diff recorriendo la lista **de más antiguo a más nuevo**: el
   predecesor del más antiguo es lo que devolvió `eventoAnteriorA` (o `null`);
   el de cada uno de los demás es el que lo precede en la lista.
6. Devuelve en orden **descendente** por `createdAt` (más reciente primero,
   como pide §7.2). El orden interno de cálculo es ascendente; el de salida no
   tiene por qué coincidir.

**No implementes el endpoint HTTP aquí** — es E9.

### Tests (añadir a `SimulacionServiceImplTest.kt`, sin tocar los de Plan D)

1. Tres eventos en ventana + uno anterior fuera de ella → el diff del más
   antiguo devuelto **no** es vacío: refleja el cambio contra ese predecesor.
2. Un solo evento, sin predecesor (`eventoAnteriorA` devuelve `null`) → su diff
   es vacío.
3. `eventoAnteriorA` se llama **exactamente una vez** para toda la página, no
   una por evento (`verify(exactly = 1)`).
4. El orden de salida es `createdAt` descendente.
5. `vendedor` sobre simulación ajena → 404.
6. `analista` sobre simulación ajena → funciona (K12).
7. Historial vacío → lista vacía y **no** se llama a `eventoAnteriorA`.

**Criterio de aceptación:**
```bash
./gradlew compileKotlin compileTestKotlin ktlintCheck detekt --console=plain -q --no-daemon > /tmp/e4a.log 2>&1; echo "EXIT:$?"
./gradlew test --tests '*SimulacionServiceImpl*' --console=plain -q --no-daemon > /tmp/e4b.log 2>&1; echo "EXIT:$?"
```
ambos en EXIT:0.

---

## E5 · Registro liviano + evento de enlace en `actualizar`

**Modelo:** Opus 5 · **Esfuerzo:** High

Lee `plan-11` decisiones **D45, D47** y hallazgos **K24, K30**. Abre
`SimulacionServiceImpl.kt` — en particular `actualizar` y el privado
`registrarEvento`.

### Método privado nuevo

```kotlin
/**
 * Eventos SIN snapshot (marcada_principal, enlazada_a_item): el CHECK
 * `chk_simulacion_log_snapshot` solo exige `id_oportunidad_item` para estos dos
 * tipos (K24). Meter aqui el snapshot completo seria semanticamente incorrecto
 * —el evento no representa una edicion de parametros— aunque el CHECK lo
 * dejara pasar.
 */
private fun registrarEventoDeEnlace(
    idSimulacion: Long,
    idOportunidadItem: Long,
    usuario: UsuarioActual,
    tipoEvento: TipoEventoSimulacion,
)
```

Construye un `SimulacionLog` con **solo** `idSimulacion`, `tipoEvento`,
`idOportunidadItem`, `createdAt = LocalDateTime.now()` y `createdBy`. El resto
de campos, en su default `null`.

### Ajuste de `actualizar` (D45)

`actualizar` ya calcula si hay reenlace: la condición
`idItemPedido != null && idItemPedido != simulacion.idOportunidadItem` existe
en el código (decide si llamar a `resolverItem`) y además ya se guarda en la
variable `reenlazando`, que el fix de la auditoría de Plan D introdujo para
bajar `esPrincipal`. **Reutilízala, no la recalcules.**

Después del `save(simulacion)` y junto al `registrarEvento(... editada ...)`
que ya existe: si `reenlazando`, registra además
`registrarEventoDeEnlace(actualizada.id, idItemPedido, usuario,
TipoEventoSimulacion.enlazada_a_item)`.

**El `editada` se sigue registrando incondicionalmente**, como hoy. Un PATCH
que solo reenlaza deja dos filas de log, y el `editada` tendrá diff vacío — eso
es correcto (K30), no lo "optimices".

**No toques** `crear`, `detalle`, `listar`, `eliminar`, `cronograma`,
`historial`.

### Tests (añadir a `SimulacionServiceImplTest.kt`)

Para capturar dos invocaciones del mismo mock, usa una lista en vez de un
`slot`: `val logs = mutableListOf<SimulacionLog>()` con
`every { simulacionLogRepository.save(capture(logs)) } answers { logs.last() }`.

1. PATCH que reenlaza (de un ítem a otro) → **dos** eventos:
   `enlazada_a_item` y `editada`. `verify(exactly = 2) { simulacionLogRepository.save(any()) }`.
2. El `enlazada_a_item` capturado tiene `modo`, `precioVenta`, `cuotaInicial`,
   `plazoMeses`, `tea`, `valorResidual`, `cuotaFinal` **todos en `null`**
   (snapshot mínimo, K24) y `idOportunidadItem` con el ítem NUEVO.
3. PATCH que reenlaza desde `null` (simulación sin ítem que se enlaza por
   primera vez) → también registra `enlazada_a_item`.
4. PATCH que **no** toca `idOportunidadItem` → un solo evento (`editada`);
   `verify(exactly = 1) { simulacionLogRepository.save(any()) }`.

**Criterio de aceptación:**
```bash
./gradlew compileKotlin compileTestKotlin ktlintCheck detekt --console=plain -q --no-daemon > /tmp/e5a.log 2>&1; echo "EXIT:$?"
./gradlew test --tests '*SimulacionServiceImpl*' --console=plain -q --no-daemon > /tmp/e5b.log 2>&1; echo "EXIT:$?"
```
ambos en EXIT:0.

---

## E6 · `SimulacionServiceImpl.restaurar`

**Modelo:** Opus 5 · **Esfuerzo:** Extra High

Lee `plan-11` decisión **D48 completa** y `reglas_simulaciones.md` §7.2 entero.
Abre `SimulacionServiceImpl.kt` — reutilizarás `entidad`, `itemDe`,
`registrarEvento`, y las validaciones de `ValidacionesSimulacion` (E1).

Añade a `SimulacionService`:

```kotlin
/** Restaura una version de la ventana de 7 dias (§7.2). Recalcula `cuota_final`. */
fun restaurar(id: Long, idEventoLog: Long, usuario: UsuarioActual): SimulacionDto
```

`@Transactional` (escribe simulación **y** dos filas de log). Orden literal:

1. `exigirAcceso` + `entidad(id)` + `itemDe` + `exigirAlcance` → 404.
2. Cargar el `SimulacionLog` por `idEventoLog`. **404 `NoEncontradoException`**
   si falla cualquiera de estas: no existe · `log.idSimulacion != id` ·
   `log.tipoEvento` fuera de `{creada, editada, restaurada}` · `log.createdAt`
   anterior a `LocalDateTime.now().minusDays(7)`.
   **No valides el límite de 15 versiones**: §7.2 lo define como filtro de
   lectura del historial, no como regla de escritura. Si te parece que hace
   falta, detente y consulta — no lo implementes por tu cuenta.
3. Registrar el estado ACTUAL (antes de mutar nada) como evento `editada`, con
   **`momento = LocalDateTime.now()`**, no el `updatedAt` viejo de la entidad.
   El log es un diario cronológico: este evento ocurre ahora. Fecharlo en el
   pasado insertaría una fila fuera de orden y rompería el diff, que asume
   orden cronológico.
4. Copiar del snapshot a la entidad: `precioVenta`, `descuento`, `cuotaInicial`,
   `plazoMeses`, `tea`, `valorResidual`, `diasTrabajados`,
   `comisionEstructuracion`.
   **`modo` no se copia** (es `val`; además no pudo cambiar).
   **`esPrincipal` e `idOportunidadItem` tampoco se tocan**: no están en el
   snapshot y no son parámetros de cálculo. Restaurar parámetros no mueve la
   simulación de ítem ni cambia quién es principal.
5. Validar y recalcular, en el mismo orden que `crear`:
   `ValidacionesSimulacion.exigirCuotaInicialMenorQuePrecioEfectivo(...)` →
   `MotorSimulacion.calcular(...)` **una vez**, con `simulacion.modo` →
   `ValidacionesSimulacion.exigirValorResidualMenorQuePrincipal(...)`.
   `cuotaFinal = resultado.cuotaFinal`. **Nunca `log.cuotaFinal`** (§7.2 paso
   3: puede haber habido una corrección de fórmula entre medio — y esa es
   exactamente la razón por la que aquí sí se revalida §13, D48).
6. `updatedAt = now()`, `updatedBy = usuario.id`. `save`.
7. Registrar `restaurada` con el snapshot COMPLETO posterior (`registrarEvento`).
8. Devolver el DTO.

**No implementes el endpoint HTTP** (E9). No toques los otros métodos.

### Tests (añadir a `SimulacionServiceImplTest.kt`)

1. Restauración válida: `verifyOrder` confirma que el `editada` del estado
   previo se registra ANTES del `save`, y el `restaurada` después. Ambos con
   snapshot completo.
2. **`cuotaFinal` se recalcula, no se copia**: siembra un `SimulacionLog` cuyo
   `cuotaFinal` sea un valor imposible para sus parámetros (p. ej.
   `BigDecimal("1.00")` con los parámetros del caso dorado leasing) y verifica
   que la entidad guardada queda con **`1548.86`**, el valor del motor, no con
   `1.00`. Este test es el que impide que alguien "simplifique" copiando.
3. `idEventoLog` de OTRA simulación → 404, sin escribir nada.
4. `idEventoLog` de un evento `marcada_principal` → 404 (tipo no restaurable).
5. `idEventoLog` de hace más de 7 días → 404.
6. `modo` de la entidad no cambia tras restaurar.
7. **`esPrincipal` e `idOportunidadItem` no cambian** tras restaurar.
8. Snapshot que ya no pasa §13 (p. ej. `valorResidual` mayor que el `principal`
   que da el motor hoy) → `ValidacionException`, sin guardar ni registrar
   `restaurada`. Este es el escenario de la corrección de fórmula.
9. `vendedor` sobre simulación ajena → 404, sin escribir nada.

**Criterio de aceptación:**
```bash
./gradlew compileKotlin compileTestKotlin ktlintCheck detekt --console=plain -q --no-daemon > /tmp/e6a.log 2>&1; echo "EXIT:$?"
./gradlew test --tests '*SimulacionServiceImpl*' --console=plain -q --no-daemon > /tmp/e6b.log 2>&1; echo "EXIT:$?"
```
ambos en EXIT:0.

---

## E7 · `SimulacionServiceImpl.marcarPrincipal`

**Modelo:** Sonnet 5 · **Esfuerzo:** Medium

Lee `plan-11` decisión **D46** y hallazgo **K28**. Reutiliza `entidad`,
`itemDe`, `registrarEventoDeEnlace` (E5) y
`simulacionRepository.desmarcarPrincipalDe` (Plan D).

```kotlin
/** §6.3: cambia manualmente cual es la simulacion principal del item. */
fun marcarPrincipal(id: Long, usuario: UsuarioActual): SimulacionDto
```

`@Transactional`. Pasos:

1. `exigirAcceso` + `entidad(id)` + `itemDe` + `exigirAlcance` → 404.
2. `simulacion.idOportunidadItem == null` →
   `ConflictoException(code = "SIMULACION_SIN_ITEM", message = …)` (409),
   **antes de tocar la base**. El mensaje debe decir que primero hay que
   enlazar la simulación a un ítem. Es 409 y no 400 porque lo que impide la
   operación es el estado del recurso, no la forma de la petición.
3. `simulacion.esPrincipal == true` → **no-op exitoso**: devuelve el DTO sin
   llamar a `desmarcarPrincipalDe`, sin `save` y sin registrar evento.
4. Si no: `desmarcarPrincipalDe(idItem)` → `esPrincipal = true` →
   `updatedAt`/`updatedBy` → `save` → `registrarEventoDeEnlace(...,
   TipoEventoSimulacion.marcada_principal)`.
5. Devolver el DTO.

### Tests

1. Sin ítem → `ConflictoException` con `code == "SIMULACION_SIN_ITEM"`, sin
   escribir nada (`verify(exactly = 0)` sobre `save`, `desmarcarPrincipalDe` y
   `simulacionLogRepository.save`).
2. Ya principal → no-op: los mismos tres `verify(exactly = 0)`, y el DTO
   devuelto sigue teniendo `esPrincipal = true`.
3. Con ítem, no principal → `verifyOrder` confirma `desmarcarPrincipalDe`
   ANTES del `save`; el evento registrado es `marcada_principal` con snapshot
   mínimo (los 7 campos del CHECK en `null`, `idOportunidadItem` poblado).
4. `vendedor` sobre simulación ajena → 404.

**Criterio de aceptación:**
```bash
./gradlew compileKotlin compileTestKotlin ktlintCheck detekt --console=plain -q --no-daemon > /tmp/e7a.log 2>&1; echo "EXIT:$?"
./gradlew test --tests '*SimulacionServiceImpl*' --console=plain -q --no-daemon > /tmp/e7b.log 2>&1; echo "EXIT:$?"
```
ambos en EXIT:0.

---

## E8 · `SimulacionServiceImpl.bifurcar`

**Modelo:** Opus 5 · **Esfuerzo:** Extra High

Lee `plan-11` decisión **D49 completa** y hallazgo **K27**. Abre
`SimulacionServiceImpl.kt` — `bifurcar` combina el patrón de fusión de
`actualizar` con el de creación de `crear`; ábrelos ambos.

### `BifurcarSimulacionRequest` (en `dto/SimulacionDtos.kt`)

Mismos campos y anotaciones de validación que `ActualizarSimulacionRequest`
—cópialas—, **incluido `modo: String? = null`**, que aquí, si viene, se
resuelve con `resolverModo` y se usa: **NO pasa por `exigirModoInmutable`**.
Esta es la vía autorizada para cambiar de modo (K27); dilo en el KDoc del DTO.

### El método

```kotlin
/** §7.3 "Guardar como Nueva Simulacion": fila nueva con `id_simulacion_origen`. */
fun bifurcar(id: Long, request: BifurcarSimulacionRequest, usuario: UsuarioActual): SimulacionDto
```

`@Transactional`. Pasos:

1. `exigirAcceso` + `entidad(id)` (el ORIGEN) + `itemDe` del origen +
   `exigirAlcance` sobre el origen → 404.
2. `modo` = `request.modo?.let { resolverModo(it) } ?: origen.modo`.
3. **Ítem**: si `request.idOportunidadItem` viene y difiere del
   `origen.idOportunidadItem`, resuélvelo con `resolverItem` (valida alcance
   sobre el ítem NUEVO). Si no viene, hereda el del origen vía
   `itemDe(origen.idOportunidadItem)`.
4. Resto de campos: el del request si viene, si no el del origen.
5. Validaciones §13 + motor **una vez**, mismo orden que `crear`
   (`ValidacionesSimulacion.…`, E1).
6. **Relevo de principal** (D38): si hay ítem, `desmarcarPrincipalDe(idItem)`
   ANTES de insertar; la nueva nace `esPrincipal = true`. Sin ítem,
   `esPrincipal = false`.
7. Persistir una `Simulacion` **NUEVA** con `idSimulacionOrigen = origen.id`,
   `createdBy = updatedBy = usuario.id`, `createdAt = updatedAt = ahora`, y
   `nombre` **solo si el request lo trae** (mismo trato de blanco→`null` que
   `crear`). **No heredes el `nombre` del origen**: dejaría dos simulaciones
   con idéntico título; sin él, la bifurcada autogenera el suyo y el
   correlativo las distingue (§8.1).
8. Evento `creada` para la fila NUEVA **con `idSimulacionOrigen` poblado en el
   log** (K27). Extiende `registrarEvento` con un parámetro
   `idSimulacionOrigen: Long? = null` que se propague al `SimulacionLog`; los
   llamadores existentes no cambian.
9. Devolver el DTO de la simulación NUEVA.

**Sobre el origen:** no lo mutes ni lo guardes desde el Service. Sí puede
cambiar su fila en la base por el `desmarcarPrincipalDe` del paso 6, si la
bifurcada hereda su ítem y le quita el rol de principal — eso es correcto y
esperado (D49).

**No implementes el endpoint HTTP** (E9).

### Tests (añadir a `SimulacionServiceImplTest.kt`)

1. Bifurcar sin cambios → fila NUEVA con los valores del origen y
   `idSimulacionOrigen == origen.id`. **No** se llama `save` con la entidad del
   origen ni `delete`.
2. Bifurcar cambiando `modo` → la nueva tiene el modo nuevo y una `cuotaFinal`
   consistente con él (usa los dos casos dorados: bifurcar un leasing a crédito
   directo con los parámetros del caso dorado de §3.6 debe dar **`697.67`**).
   **No** lanza `ConflictoException` — a diferencia de `actualizar`.
3. Bifurcada que hereda el ítem del origen → nace `esPrincipal = true` y
   `desmarcarPrincipalDe` se llama con ese mismo ítem, ANTES del `save`
   (`verifyOrder`).
4. Bifurcar reenlazando a un ítem de otro vendedor, siendo `vendedor` → 404,
   sin crear nada.
5. Bifurcar una simulación sin ítem y sin pedir uno → la nueva también sin
   ítem, `esPrincipal = false`, y **no** se llama `desmarcarPrincipalDe`.
6. El log de la nueva tiene `tipoEvento = creada` **y `idSimulacionOrigen`
   poblado**.
7. Fusión que rompe §13 → `ValidacionException`, sin crear nada.
8. `nombre` no se hereda: bifurcar un origen con `nombre = "Mi simulación"`
   sin `nombre` en el request → la nueva queda con `nombre = null`.
9. `vendedor` sobre simulación ajena → 404.

**Criterio de aceptación:**
```bash
./gradlew compileKotlin compileTestKotlin ktlintCheck detekt --console=plain -q --no-daemon > /tmp/e8a.log 2>&1; echo "EXIT:$?"
./gradlew test --tests '*SimulacionServiceImpl*' --console=plain -q --no-daemon > /tmp/e8b.log 2>&1; echo "EXIT:$?"
```
ambos en EXIT:0.

---

## E9 · Endpoints nuevos en `SimulacionController` + WebMvc

**Modelo:** Sonnet 5 · **Esfuerzo:** High

Abre `SimulacionController.kt` y `SimulacionControllerWebMvcTest.kt` completos.
`SimulacionService` ya debe declarar `historial`, `restaurar`, `marcarPrincipal`
y `bifurcar` (E4, E6, E7, E8).

| Método | Ruta | Servicio | Status |
|---|---|---|---|
| `GET` | `/{id}/historial` | `historial` | 200 |
| `POST` | `/{id}/restaurar` | `restaurar` | 200 |
| `POST` | `/{id}/bifurcar` | `bifurcar` | **201 CREATED** (crea un recurso) |
| `PATCH` | `/{id}/principal` | `marcarPrincipal` | 200 |

`restaurar` recibe el id del evento **en el body**, con un DTO propio en
`dto/SimulacionDtos.kt`:

```kotlin
/** Body de `POST /simulaciones/:id/restaurar`. */
data class RestaurarSimulacionRequest(
    @field:Positive(message = "id_evento_log debe ser un identificador valido")
    val idEventoLog: Long,
)
```

Es la forma consistente con el resto del controller, que ya usa `POST` +
`@Valid @RequestBody` para `crear`. `bifurcar` usa
`@Valid @RequestBody request: BifurcarSimulacionRequest`. `marcarPrincipal` no
lleva body.

**Sin `@PreAuthorize`** en ninguno: la nota del KDoc de la clase ya lo explica
(D30 de Plan D, toda la autorización vive en `SimulacionPermisos`). No la
toques ni añadas anotaciones de rol.

### Tests (añadir a `SimulacionControllerWebMvcTest.kt`)

1. `GET /{id}/historial` → 200 con la lista.
2. `POST /{id}/restaurar` con `{"id_evento_log": N}` → 200.
3. `POST /{id}/bifurcar` → **201** con el DTO de la nueva.
4. `PATCH /{id}/principal` → 200.
5. `POST /{id}/bifurcar` con `precio_venta` negativo → 400 (Bean Validation).
6. `POST /{id}/restaurar` sin `id_evento_log` en el body → 400.

**Restricciones:** no toques los 6 endpoints existentes ni sus tests. No
implementes el controller de la Calculadora (E11).

**Criterio de aceptación:**
```bash
./gradlew compileKotlin compileTestKotlin ktlintCheck detekt --console=plain -q --no-daemon > /tmp/e9a.log 2>&1; echo "EXIT:$?"
./gradlew test --tests '*SimulacionController*' --console=plain -q --no-daemon > /tmp/e9b.log 2>&1; echo "EXIT:$?"
```
ambos en EXIT:0.

---

## E10 · `CalculadoraFinancieraService`/Impl

**Modelo:** Opus 5 · **Esfuerzo:** High

Lee `plan-11` decisión **D50 completa**, hallazgo **K26**, y
`reglas_simulaciones.md` §9 entero.

Abre, como referencia de lo que hay que **reutilizar tal cual**:
`SimulacionPermisos.kt` · `ValidacionesSimulacion.kt` y `DefaultsSimulacion.kt`
(E1) · `shared/simulacion/MotorSimulacion.kt` (no se toca) ·
`SimulacionServiceImpl.crear` (el orden exacto: modo → defaults → validación
previa → motor → validación posterior) y `SimulacionServiceImpl.cronograma`
(el mapeo `ResultadoSimulacion` → `CronogramaDto`).

`ModeloService.resumen(id)` resuelve un modelo y ya lanza `NoEncontradoException`
si no existe. **`EmpresaService` solo expone `resumenPorIds(ids: Collection<Long>)`,
en lotes — no hay variante para un solo id**: resuélvelo con
`empresaService.resumenPorIds(listOf(idEmpresa))[idEmpresa]`, igual que hace
`SimulacionServiceImpl.toDto`; si la clave no está en el mapa, lanza tú
`NoEncontradoException`.

### DTOs (en `dto/SimulacionDtos.kt`)

```kotlin
data class CalculadoraRequest(
    val modo: String,
    val idEmpresa: Long? = null,
    val idModelo: Long? = null,
    val precioVenta: BigDecimal,
    val descuento: BigDecimal? = null,
    val cuotaInicial: BigDecimal,
    val plazoMeses: Int,
    val tea: BigDecimal,
    val valorResidual: BigDecimal? = null,
    val diasTrabajados: Int? = null,
    val comisionEstructuracion: BigDecimal? = null,
) // MISMAS anotaciones de validacion que CrearSimulacionRequest — copialas exacto

/** Salida de POST /calculadora. Nada de esto se persiste (§9). */
data class CalculadoraDto(
    val empresa: EmpresaResumen?,
    val modelo: ModeloEnSimulacionDto?,
    val cronograma: CronogramaDto,
)
```

`EmpresaResumen` ya existe en `domain/empresas/dto/`: impórtalo, no lo
redeclares (y es API pública de ese módulo, así que cruzar hacia él es legal
según la regla 12 — ya lo hace `SimulacionServiceImpl`).

### `CalculadoraFinancieraServiceImpl`

`@Service`, constructor con **exactamente** `SimulacionPermisos`,
`ModeloService`, `EmpresaService`. **No inyectes `SimulacionRepository` ni
`SimulacionLogRepository`** — que el constructor ni los reciba es la garantía
estructural de §9 (D50). Escríbelo en el KDoc: si alguien propone añadir
auditoría aquí, contradice §9 explícitamente.

`calcular`, **sin `@Transactional`** (no toca la base):

1. `permisos.exigirAcceso(usuario)` (K26: es exactamente la columna
   "Calculadora Financiera" de §10 — `jdv`/`otro` fuera, el resto dentro).
2. Resolver `modo`; fuera del enum → `ValidacionException(field = "modo")`,
   nunca un 500. Duplica aquí el `resolverModo` como privado: es una expresión
   de una línea y extraerla a un tercer objeto compartido no compensa.
3. Defaults desde `DefaultsSimulacion` (E1). No repitas los literales.
4. `ValidacionesSimulacion.exigirCuotaInicialMenorQuePrecioEfectivo(...)`.
5. `MotorSimulacion.calcular(...)` **una vez**.
6. `ValidacionesSimulacion.exigirValorResidualMenorQuePrincipal(...)`.
7. Resolver `empresa`/`modelo` **solo si** vinieron sus ids (404 si no existen).
8. Mapear a `CronogramaDto` — mismo mapeo que `SimulacionServiceImpl.cronograma`,
   incluida la `tasaNominalMensual` **sin redondear** (§3.1).
9. Devolver `CalculadoraDto`. **Ningún `save`, en ninguna rama, ni en fallo.**

### Test: `CalculadoraFinancieraServiceImplTest.kt`

Archivo nuevo. Usa `SimulacionPermisos` **real** (no tiene dependencias y así
el test cubre de verdad la regla de acceso); mockea `ModeloService` y
`EmpresaService`.

1. **Caso dorado leasing §3.6** → `cronograma.cuotaFinal == "1548.86"`.
2. **Caso dorado crédito directo §3.6** → `cronograma.cuotaFinal == "697.67"`.
3. Sin `idEmpresa` ni `idModelo` → ambos `null` en el DTO, y **no** se llama a
   `ModeloService` ni a `EmpresaService` (`verify(exactly = 0)`).
4. Con ambos ids válidos → ambos resueltos en el DTO.
5. `idModelo` inexistente → `NoEncontradoException`.
6. `idEmpresa` inexistente → `NoEncontradoException`.
7. `jdv` → `PermisoInsuficienteException` (403). `vendedor` → funciona.
8. `cuota_inicial >= PV_efectivo` → `ValidacionException`.
9. `modo` fuera del enum → `ValidacionException` con `field == "modo"`.

No hace falta un test de "no escribe": el constructor no recibe repositorios,
así que es imposible por tipo. **Si te encuentras con que sí los necesita,
detente y consulta** — contradice D50.

**Restricciones:** no toques `SimulacionServiceImpl.kt` ni sus tests. No
implementes el controller (E11).

**Criterio de aceptación:**
```bash
./gradlew compileKotlin compileTestKotlin ktlintCheck detekt --console=plain -q --no-daemon > /tmp/e10a.log 2>&1; echo "EXIT:$?"
./gradlew test --tests '*Calculadora*' --console=plain -q --no-daemon > /tmp/e10b.log 2>&1; echo "EXIT:$?"
```
ambos en EXIT:0.

---

## E11 · `CalculadoraFinancieraController` + WebMvc

**Modelo:** Sonnet 5 · **Esfuerzo:** Medium

Abre `SimulacionController.kt` como referencia de estilo — pero esta tarea crea
un controller **separado**, no le añade rutas al de simulaciones (D50: §9 lo
llama "módulo aparte", y colgarlo de `/simulaciones` sugeriría que comparte su
ciclo de vida cuando la premisa es que no persiste nada).

```kotlin
@RestController
@RequestMapping("/api/v1/calculadora")
class CalculadoraFinancieraController(
    private val calculadoraFinancieraService: CalculadoraFinancieraService,
    private val usuarioProvider: UsuarioActualProvider,
) {
    @PostMapping
    fun calcular(
        @Valid @RequestBody request: CalculadoraRequest,
    ): ApiResponse<CalculadoraDto> =
        ApiResponse.ok(calculadoraFinancieraService.calcular(request, usuarioProvider.actual()))
}
```

KDoc citando §9: módulo aparte, cero persistencia, mismo motor. **Sin
`@PreAuthorize`**, por la misma razón que el otro controller.

### Test: `CalculadoraFinancieraControllerWebMvcTest.kt`

Mismo patrón que `SimulacionControllerWebMvcTest.kt` (mockea el servicio).

1. `POST /api/v1/calculadora` válido → 200 con el DTO.
2. `precio_venta` negativo → 400 (Bean Validation).
3. El servicio lanza `ValidacionException` → 400 traducido por
   `GlobalExceptionHandler` (`shared/GlobalExceptionHandler.kt`).
4. Sin token → 401, si el archivo plantilla ya cubre ese caso para el otro
   controller.

**Restricciones:** no toques `SimulacionController.kt` ni su test.

**Criterio de aceptación:**
```bash
./gradlew compileKotlin compileTestKotlin ktlintCheck detekt --console=plain -q --no-daemon > /tmp/e11a.log 2>&1; echo "EXIT:$?"
./gradlew test --tests '*Calculadora*' --console=plain -q --no-daemon > /tmp/e11b.log 2>&1; echo "EXIT:$?"
```
ambos en EXIT:0.

---

## E12 · Verificación de build completa (local, sin `integrationTest`)

**Modelo:** Sonnet 5 · **Esfuerzo:** Low

No edites código. Ejecuta, cada uno redirigido a archivo y con `$?` comprobado
en un comando separado:

```bash
./gradlew ktlintCheck --console=plain -q --no-daemon > /tmp/e12_lint.log 2>&1; echo "EXIT:$?"
./gradlew detekt      --console=plain -q --no-daemon > /tmp/e12_detekt.log 2>&1; echo "EXIT:$?"
./gradlew test        --console=plain -q --no-daemon > /tmp/e12_test.log 2>&1; echo "EXIT:$?"
```

**No ejecutes `integrationTest` ni `koverVerify`** (Docker 29).

Esta tarea **no puede** confirmar en verde:
- El caso de historial de `SimulacionRepositoryTest` (E3, `@Tag("integration")`),
  que es justamente el que valida el desempate de K29.
- El trinquete de cobertura Kover (85 % global / 84 % dominio).

**Repórtalo como limitación de primer orden**, no como nota al pie. Si alguno
de los tres comandos falla, **no lo arregles**: reporta el fallo exacto con el
fragmento del log y detente.

**Criterio de aceptación:** los tres en EXIT:0 y `git status --short` sin
archivos modificados por ti. Reporta además el total de archivos nuevos y
modificados del módulo `simulaciones` (Plan D + Plan E), para que E13 tenga la
foto del tamaño real.

---

## E13 · Auditoría final del diff contra los documentos citados

**Modelo:** Opus 5 · **Esfuerzo:** High

Tarea exigida por `CLAUDE.md`. **Auditoría del diff completo de E1-E12, no un
resumen. No arregles nada. No hagas commit.**

Contrasta contra: `plan-11-mapa-historial-calculadora.md` (K22-K30, D43-D53) ·
`plan-09-mapa-simulaciones-modulo.md` (vigente) · `reglas_simulaciones.md`
§6.3, §7, §8.1, §9, §13 · `CLAUDE.md` reglas 1, 8, 9, 10, 11, 12, 14 ·
`V43__create_simulaciones.sql`.

Verifica, uno por uno:

1. **Contradicciones con documentación ya vigente y correcta.**
2. **K29 — desempate por `(created_at, id)`**: ambas queries de
   `SimulacionLogRepository` ordenan por `created_at DESC, id DESC`, y
   `eventoAnteriorA` compara el **par**, no solo la fecha. Además, el test de
   integración de E3 siembra dos eventos con el MISMO `created_at` — si no lo
   hace, el test no prueba lo que dice probar y es un hallazgo.
3. **K23 — el diff cruza la ventana**: el test de E4 siembra un evento a más de
   7 días y verifica que el diff del más antiguo devuelto **no** sale vacío.
4. **D48 — `cuotaFinal` recalculada, nunca copiada**:
   `grep -n "cuotaFinal = log\.\|cuotaFinal = evento\." src/main/kotlin/pe/quantum/crm/domain/simulaciones/SimulacionServiceImpl.kt`
   debe salir **vacío**. Y el test 2 de E6 (snapshot con `cuotaFinal` imposible)
   debe existir: es el que impide la regresión.
5. **D48 — se revalida §13 al restaurar**: `restaurar` llama a las dos
   validaciones de `ValidacionesSimulacion`. Si alguien las omitió "porque el
   estado ya era válido", es un hallazgo **bloqueante**: rompe justo el
   escenario de corrección de fórmula que §7.2 anticipa, y convierte un 400 en
   un 500 del motor.
6. **D48 — `esPrincipal` e `idOportunidadItem` no se restauran**: confírmalo
   leyendo el método y su test 7.
7. **K24/D47 — eventos de enlace sin snapshot**: cada aparición de
   `TipoEventoSimulacion.marcada_principal` y `.enlazada_a_item` en
   `SimulacionServiceImpl.kt` debe estar dentro de una llamada a
   `registrarEventoDeEnlace`, **nunca** a `registrarEvento`.
8. **D45 sin regresión**: un PATCH que solo cambia `tea` sigue registrando
   **un solo** evento. Relee el test 4 de E5.
9. **K30 no se "arregló" por su cuenta**: el filtro de tipos del historial
   sigue siendo `creada|editada|restaurada`, y el diff sigue comparando los 10
   campos del snapshot — nadie añadió `idOportunidadItem` al diff ni cambió el
   filtro. Si alguien lo hizo, es un hallazgo: contradice la spec vigente.
10. **D49 — `bifurcar` no muta el origen**: ningún `save`/`delete` sobre la
    entidad del origen. Ojo: `desmarcarPrincipalDe` **sí** actualiza su fila en
    la base y eso es correcto (D49) — no lo reportes como hallazgo.
11. **D49 — `idSimulacionOrigen` viaja al log**: el evento `creada` de una
    bifurcación lo lleva poblado en el `SimulacionLog`, no solo en la entidad.
12. **D49 — `nombre` no se hereda**: test 8 de E8 presente.
13. **D46 — idempotencia**: marcar principal algo que ya lo es no llama a
    `save`, ni a `desmarcarPrincipalDe`, ni registra evento.
14. **D50 — Calculadora sin persistencia**:
    `grep -n "SimulacionRepository\|SimulacionLogRepository" src/main/kotlin/pe/quantum/crm/domain/simulaciones/CalculadoraFinanciera*.kt`
    debe salir **vacío**, ni siquiera como import.
15. **K26 — Calculadora no reimplementó permisos**: usa
    `SimulacionPermisos.exigirAcceso`, no una lógica de roles propia.
16. **D51 — el refactor no cambió comportamiento**: los mensajes y los `field`
    de `ValidacionException` en `ValidacionesSimulacion.kt` son idénticos,
    carácter por carácter, a los que tenía `SimulacionServiceImpl` antes
    (compáralos con `git show` del commit de Plan D). Y **ningún archivo de
    test de Plan D fue modificado por E1** — si lo fue, el refactor cambió algo.
17. **Frontera de módulos** (regla 12): `./gradlew test --tests '*Arquitectura*'`
    pasa, y no hay imports nuevos hacia `domain/oportunidades/` desde la
    Calculadora (K26 predijo que no harían falta).
18. **Transaccionalidad** (regla 10): `restaurar`, `bifurcar` y
    `marcarPrincipal` son `@Transactional` (escriben simulación + log);
    `historial` es `readOnly = true`; `CalculadoraFinancieraServiceImpl.calcular`
    **no** lleva `@Transactional` (no toca la base).
19. **Ninguna migración nueva** (D53): `git status --short -- src/main/resources/db/migration/`
    vacío y `SchemaMigrationIntegrationTest.kt` sin modificar.
20. **Honestidad sobre `integrationTest`**: ningún comentario ni reporte de
    E1-E12 afirma que el test de integración de E3 o el trinquete de cobertura
    se verificaron en verde localmente.
21. **TDD (regla 1)**: `DiffSimulacionTest`,
    `CalculadoraFinancieraServiceImplTest` y las adiciones a
    `SimulacionServiceImplTest` existen y cubren los casos exigidos. Los dos
    casos dorados de §3.6 aparecen también en el test de la Calculadora.

**Entregable:** informe con hallazgos en *bloqueante / menor / ninguno*, cada
uno con archivo, línea y la regla o sección concreta que contradice.

---

## Cierre del plan

Al terminar E13, **para y resume**. **No abras PR ni hagas commit** — eso lo
decide el arquitecto.

Estado esperado al cerrar Plan E: el módulo cubre historial con diff, ventana
de restauración, bifurcación, marcar principal y una Calculadora Financiera
stateless con controller propio — toda la parte de §7 que faltaba de la Fase 2,
y la Fase 4 completa. Queda Plan F: cuota agregada en `/oportunidades` (§6.2),
jobs de purga y aviso (Fase 5), y documentación de contrato (Fase 6).
