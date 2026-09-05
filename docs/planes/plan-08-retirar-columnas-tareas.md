# Plan C — Retirar las columnas viejas de `oportunidades`

> **Destinatario: agentes ejecutores, no humanos.** Cada tarea es autocontenida.
> Ejecutar en orden estricto: cada una asume que la anterior está cerrada,
> compilando y con la suite completa en verde.
>
> **Regla para el ejecutor:** si algo de tu tarea es ambiguo, contradice a otra
> tarea, o el repo no coincide con lo que la tarea describe — **detente y
> consulta al arquitecto**. No infieras, no inventes, no "arregles" de paso
> nada que la tarea no te pida. Esto es producción con usuarios reales.
>
> **Lección del Plan B, aplicada aquí:** dos rondas de CI fallaron por tests
> `@Tag("integration")` que nadie pudo correr en local (bloqueo de Docker 29) y
> que ninguna tarea había ido a leer. Este plan ya leyó **enteros**
> `ReporteServiceIntegrationTest.kt` y `ReporteServiceSqlIntegrationTest.kt`
> antes de escribir una sola tarea — ver `plan-07-mapa-retirar-columnas.md`
> hallazgos K5-K7. Aun así, **ninguna tarea de este plan puede confirmar en
> verde `integrationTest`**: solo lo hace CI. Cada tarea que toque un archivo
> `@Tag("integration")` debe decir explícitamente "no ejecutable en local,
> verificado por lectura cuidadosa" en vez de reportar un falso verde.

---

## Fase de investigación (leer antes de la Task 1)

### Documentos que gobiernan este plan

| Documento | Qué manda |
|---|---|
| `docs/planes/plan-07-mapa-retirar-columnas.md` | **Léelo entero primero.** Hallazgos K1-K9, decisiones D22-D29 |
| `docs/planes/plan-05-mapa-migrar-items.md` | D21 (la sincronización que este plan retira), D15 (`MontoTotal.sumarItems`) |
| `docs/reglas_negocio.md` §7, §10.3, §12 | Reglas de negocio que **no cambian**: solo cambia dónde viven los datos |
| `CLAUDE.md` | Reglas 1, 9, 10, 11, 12 |
| `docs/contrato_api.md` §18 | Forma actual de los reportes — no cambia (K4) |

### Reglas de `CLAUDE.md` que tocan este plan

| Regla | Cómo aplica |
|---|---|
| **1. TDD siempre** | Los tests `@Tag("integration")` no se pueden ejecutar en rojo/verde localmente — la disciplina aquí es leer cuidadosamente antes de escribir, no "correr y ver" |
| **9. JPA `LAZY`; nunca exponer entidades** | Sin cambios de exposición en este plan |
| **10. `@Transactional`** | `ReporteService`/`InicioDao` ya son `readOnly = true`; no cambia |
| **11. Queries parametrizadas** | Las subconsultas nuevas (D22, D29) usan `NamedParameterJdbcTemplate` con parámetros nombrados, igual que el resto del módulo — nunca concatenación |
| **12. Frontera entre módulos** | La fórmula de dinero se duplica en SQL en vez de cruzar hacia `MontoTotal` (D22) — es la razón explícita de esa decisión |

### Alcance — lista cerrada de archivos

Ver `plan-07-mapa-retirar-columnas.md` §5 completa (incluye los 11 archivos de
fixtures de `domain/oportunidades/` con el arreglo mecánico). No la repito
aquí para que no haya dos copias de la misma lista que puedan desalinearse.

Cualquier archivo fuera de esa lista: **detente y consulta**.

---

## Tabla de tareas

| ID | Tarea | Modelo | Esfuerzo |
|---|---|---|---|
| C1 | Reescribir `ReporteService`: `ventas`, `pipeline`, `equipo`, `descuentos` | Opus 5 | Extra High |
| C2 | Reescribir `InicioDao`: `resumenPipeline`, `unidadesFacturadasPorVendedor` | Opus 5 | High |
| C3 | Actualizar fixtures de `ReporteServiceIntegrationTest.kt` (D26) | Sonnet 5 | Medium |
| C4 | Actualizar fixtures de `ReporteServiceSqlIntegrationTest.kt` (D26) | Sonnet 5 | Medium |
| C5 | Sort por subconsulta nativa en `OportunidadServiceImpl.listar()` (D29) | Opus 5 | Extra High |
| C6 | Retirar la sincronización D21 de `OportunidadItemServiceImpl` | Opus 5 | High |
| C7 | `Oportunidad.kt` (entidad) y `OportunidadServiceImpl.crear()`: quitar los 5 campos | Opus 5 | High |
| C8 | Arreglo mecánico de los 11 fixtures de test que instancian `Oportunidad(...)` | Sonnet 5 | Extra High |
| C9 | Migración V46: `DROP COLUMN` + `SeedFixtures` | Sonnet 5 | Low |
| C10 | Verificación de build completa (local, sin `integrationTest`) | Sonnet 5 | Low |
| C11 | Documentación: `contrato_api.md`, `reglas_negocio.md` | Sonnet 5 | Medium |
| C12 | Auditoría final del diff contra los documentos citados | Opus 5 | High |

**C13 (fuera de este plan de tareas, coordinada aparte con el arquitecto):**
aplicar V46 a producción, solo después de que el PR de C1-C12 tenga
`integrationTest` en verde en CI — este plan no puede verificar eso en local.

---

## C1 · Reescribir `ReporteService`: `ventas`, `pipeline`, `equipo`, `descuentos`

**Modelo:** Opus 5 · **Esfuerzo:** Extra High

Abre `docs/planes/plan-07-mapa-retirar-columnas.md` decisiones **D22, D23,
D24, D25** — el detalle completo vive ahí, este enunciado lo resume pero no
lo repite entero. Abre también `ReporteService.kt` completo (589 líneas) y
`domain/oportunidades/MontoTotal.kt` (para copiar la fórmula exacta, no
recordarla) antes de escribir nada.

**No toques `velocidadEtapas()` ni `prospeccion()`** — no leen las columnas
viejas (K1), fuera de alcance.

### `ventas()`

- Reemplaza el `JOIN modelos m ON m.id = o.id_modelo` y las columnas
  `o.monto_total, o.cantidad, o.dcto` por un `JOIN oportunidad_items i ON
  i.id_oportunidad = o.id` (y `JOIN modelos m ON m.id = i.id_modelo`).
- El resultado pasa a tener **una fila por ítem**, no por oportunidad. Aplica
  D23: `operacionesCount` debe seguir contando oportunidades **distintas**
  (`o.id`), no filas. La forma más simple: sigue trayendo `o.id` en el
  `SELECT`, y en Kotlin usa `rows.map { it.id }.distinct().size` para
  `operacionesCount` en vez de `rows.size`.
- `montoTotal`/`unidadesTotal`/`ticketPromedio`/`dctoPromedio`/`porMes`/`porVendedor`
  siguen sumando sobre las filas (ahora de ítem) tal como ya hace el código —
  matemáticamente correcto sin cambios ahí, porque sumar por ítem y sumar por
  oportunidad da el mismo total cuando cada oportunidad tiene un ítem (hoy) y
  el total correcto cuando tiene varios (D23).
- `porModelo` (D25): ya agrupa por `modelo` de la fila — con la query ya a
  nivel de ítem, esto sale gratis sin cambiar el código Kotlin de esa parte.
- El `monto` de cada fila (`VentaRow.monto`) se calcula en SQL con la fórmula
  de D22:
  ```sql
  ROUND(i.cantidad * i.precio_venta * (1 - COALESCE(i.descuento, 0) / 100), 2) AS monto
  ```
  con un comentario que apunte a `MontoTotal.calcular` como fuente de verdad.
  `i.cantidad`/`i.precio_venta` pueden ser `NULL` (ítem incompleto, D15) —
  usa `COALESCE(..., 0)` donde haga falta para que la fila aporte 0 en vez de
  romper el `ROUND` con un operando `NULL` (Postgres: cualquier aritmética con
  `NULL` da `NULL`, y `SUM` ya ignora los `NULL`, así que confirma con cuidado
  cuál de los dos comportamientos quieres — **si tienes dudas sobre si un ítem
  incompleto debe aportar 0 a `montoTotal` del reporte o ser excluido en
  silencio, detente y consulta**: D15 lo resuelve para el motor de cálculo
  pero este reporte es una superficie distinta).

### `pipeline()`

Reemplaza `o.monto_total` por una subconsulta correlacionada (la oportunidad
sigue siendo una fila por fila, no se aplana a ítems — `pipeline()` lista
oportunidades individuales):
```sql
COALESCE((
    SELECT SUM(ROUND(i.cantidad * i.precio_venta * (1 - COALESCE(i.descuento, 0) / 100), 2))
    FROM oportunidad_items i WHERE i.id_oportunidad = o.id
), 0) AS monto_total
```

### `equipo()`

Las dos queries agrupadas por vendedor (`activas`, `cerradas`) reemplazan
`SUM(monto_total)`/`AVG(dcto)`/`SUM(o.monto_total)` por el mismo patrón de
subconsulta o `JOIN` + `GROUP BY o.id_vendedor` con la suma a nivel de ítem
dentro. `AVG(dcto)` (para `dctoPromedio`, el promedio de descuento del
pipeline activo del vendedor) pasa a `AVG(i.descuento)` sobre el `JOIN` a
ítems — cada ítem aporta su propio descuento al promedio, coherente con D24.

### `descuentos()` (D24 — la reescritura más grande de este método)

Query base pasa de `FROM oportunidades o` a `FROM oportunidad_items i JOIN
oportunidades o ON o.id = i.id_oportunidad`, una fila por ítem. `dcto` pasa a
`i.descuento`. **Preserva K7 exacto:**
- `WHERE o.estado != 'cerrado'` se mantiene igual, ahora sobre la oportunidad
  dueña del ítem.
- `i.descuento IS NULL` sigue contando como 0 en el promedio (no se excluye).

### Tests

No escribas tests nuevos en esta tarea — `ReporteServiceIntegrationTest.kt`/
`ReporteServiceSqlIntegrationTest.kt` los cubren, y su actualización es C3/C4
(tareas separadas, después de esta). Si al escribir el SQL concluyes que
necesitas un dato de fixture que esas clases no tienen, **no las edites tú**:
repórtalo para que C3/C4 lo incorporen.

**Criterio de aceptación:** `./gradlew compileKotlin ktlintCheck detekt --console=plain -q --no-daemon > /tmp/c1.log 2>&1; echo "EXIT:$?"` en EXIT:0. `./gradlew test --tests '*ReporteAritmetica*' --console=plain -q --no-daemon` en EXIT:0 (el único test de `reportes` que SÍ corre en local, no es `@Tag("integration")`). **No corras `integrationTest`** — no está disponible en este entorno (Docker 29).

Nota de infraestructura: si Gradle falla con `CorruptedException`, "Could not delete" o similar (locks de Windows), mata los procesos `java.exe` colgados, ejecuta `./gradlew --stop`, borra `build/` y reintenta con `--no-daemon`. **No confíes en el exit code de un pipeline con `| tail`** — redirige a archivo y comprueba `$?` en un comando separado.

---

## C2 · Reescribir `InicioDao`: `resumenPipeline`, `unidadesFacturadasPorVendedor`

**Modelo:** Opus 5 · **Esfuerzo:** High

Abre `InicioDao.kt` completo. **No toques `eventosPorSeguir()`** — no lee las
columnas viejas (K2).

### `resumenPipeline(idVendedor)`

```sql
SELECT estado, COUNT(*) AS total,
       COALESCE(SUM(item_monto.monto), 0) AS valor,
       COALESCE(SUM(item_monto.cantidad), 0) AS unidades
FROM oportunidades o
LEFT JOIN LATERAL (
    SELECT SUM(ROUND(i.cantidad * i.precio_venta * (1 - COALESCE(i.descuento, 0) / 100), 2)) AS monto,
           SUM(i.cantidad) AS cantidad
    FROM oportunidad_items i WHERE i.id_oportunidad = o.id
) item_monto ON true
WHERE estado != 'cerrado'
...
GROUP BY estado
```

Ajusta la sintaxis exacta al estilo del archivo (usa `buildString`/parámetros
nombrados como ya hace `InicioDao`, no copies este SQL literal si el `LATERAL`
no calza bien con el `GROUP BY estado` existente — **piensa la forma correcta
de combinar "una fila por oportunidad con su suma de ítems" y luego "agregado
por estado"**; si no te sale limpio con `LATERAL`, una subconsulta
correlacionada como en C1 también sirve).

### `unidadesFacturadasPorVendedor(idsVendedor, anio, mes)`

Reemplaza `SUM(cantidad)` (de `oportunidades`) por un `JOIN oportunidad_items
i ON i.id_oportunidad = o.id` y `SUM(i.cantidad)`, manteniendo el filtro por
`estado = 'facturado'`, `id_vendedor IN (...)`, año y mes opcional exactamente
igual.

**Criterio de aceptación:** `./gradlew compileKotlin ktlintCheck detekt --console=plain -q --no-daemon` en EXIT:0. No hay test unitario de `InicioDao` que corra en local (verifícalo primero — si existe alguno no marcado `@Tag("integration")`, córrelo; si no, repórtalo así).

---

## C3 · Actualizar fixtures de `ReporteServiceIntegrationTest.kt` (D26)

**Modelo:** Sonnet 5 · **Esfuerzo:** Medium

**No puedes ejecutar este test en local** (`@Tag("integration")`, Docker 29).
Tu trabajo se verifica por lectura cuidadosa, no por corrida — sé más
conservador de lo normal.

Abre el archivo completo (305 líneas) y `docs/planes/plan-07-mapa-retirar-columnas.md`
D26 antes de escribir nada.

**Cambio único:** el helper privado `crearOportunidadFacturada(...)` gana un
`INSERT INTO oportunidad_items` inmediatamente después del `INSERT INTO
oportunidades`, con los mismos valores:

```kotlin
jdbcTemplate.update(
    """
    INSERT INTO oportunidad_items
        (id_oportunidad, id_modelo, cantidad, precio_venta, descuento, created_at, created_by, updated_at, updated_by)
    VALUES
        ($idOportunidad, $idModelo, $cantidad, 50000.00, 0.00, TIMESTAMP '$creadaEn', $idVendedor, TIMESTAMP '$creadaEn', $idVendedor)
    """.trimIndent(),
)
```

(`50000.00` y `0.00` son los mismos literales que ya usa el `INSERT INTO
oportunidades` para `precio_unitario`/`dcto` — cópialos exactos del `INSERT`
existente, no inventes valores nuevos.)

**No cambies ninguna aserción de los tests** — el objetivo es que sigan
verificando exactamente lo mismo (K7, y el resto de escenarios de fechas de
facturación), solo que ahora la fuente de datos que `reporteService.ventas()`/
`.equipo()` lee (tras C1) es `oportunidad_items`, no las columnas de
`oportunidades`.

**No toques `crearHito`, `crearEmpresa`, `crearVendedor`, `crearModelo`,
`crearFinanciadora`, `logFacturado`** — no crean oportunidades, no necesitan
ítem.

**Criterio de aceptación:** el archivo compila (`./gradlew compileTestKotlin --console=plain -q --no-daemon` en EXIT:0). Relee cada test uno por uno contra el nuevo `crearOportunidadFacturada` y confirma en tu respuesta que los valores de `monto_total`/`cantidad` esperados en cada aserción siguen siendo alcanzables con los datos que el fixture ahora siembra en `oportunidad_items` (K6: un ítem por oportunidad, mismos valores).

---

## C4 · Actualizar fixtures de `ReporteServiceSqlIntegrationTest.kt` (D26)

**Modelo:** Sonnet 5 · **Esfuerzo:** Medium

Mismo criterio que C3, aplicado al segundo archivo (444 líneas, helper
privado `crearOportunidad(...)` — nombre distinto al de C3, es
**deliberadamente independiente**, no compartas código entre ambos archivos).

`crearOportunidad(...)` ya recibe `dcto: String?` como parámetro (con
default `"0.00"` y casos que pasan `null` explícito — K7, el test `descuentos
trata un dcto NULL como cero`). El `INSERT INTO oportunidad_items` que
agregues debe propagar ese mismo `dcto` (renombrado a `descuento` en la
columna) tal cual, incluido el caso `null`:

```kotlin
jdbcTemplate.update(
    """
    INSERT INTO oportunidad_items
        (id_oportunidad, id_modelo, cantidad, precio_venta, descuento, created_at, created_by, updated_at, updated_by)
    VALUES
        ($idOportunidad, $idModelo, $cantidad, 50000.00, ${dcto ?: "NULL"}, TIMESTAMP '$creadaEn', $idVendedor, TIMESTAMP '$creadaEn', $idVendedor)
    """.trimIndent(),
)
```

**El test `descuentos trata un dcto NULL como cero para el promedio` es el
más delicado de esta tarea** — confirma explícitamente en tu respuesta que,
tras tu cambio, la fila de `oportunidad_items` para esa segunda oportunidad
(la de `dcto = null`) queda con `descuento = NULL`, no con `0.00` — si el
`INSERT` de alguna forma normalizara el `NULL` a cero, el test dejaría de
probar lo que dice probar.

**Restricciones:** no toques `crearHito`, `crearEmpresa`, `crearVendedor`,
`crearModelo`, `crearFinanciadora`, `logFacturado`, ni ninguna aserción.

**Criterio de aceptación:** igual que C3 — compila, y relees cada uno de los 5 tests contra el fixture actualizado, reportando explícitamente el caso del `dcto NULL`.

---

## C5 · Sort por subconsulta nativa en `OportunidadServiceImpl.listar()` (D29)

**Modelo:** Opus 5 · **Esfuerzo:** Extra High

Abre `docs/planes/plan-07-mapa-retirar-columnas.md` decisión **D29 completa**
— trae la mecánica exacta que este enunciado resume. Abre también
`OportunidadServiceImpl.kt` (`listar()`, `especificacion()`,
`CAMPOS_ORDENABLES`), `shared/Paginacion.kt` (`CamposOrdenables.resolver()`,
`Paginacion.meta()`) y `MontoTotal.kt` antes de escribir nada.

### Diseño

`listar()` se bifurca según qué campo resuelve `CAMPOS_ORDENABLES.resolver(sort)`:

- **`id`, `estado`, `fechaCierreEstimado`, `createdAt`, `updatedAt`**: camino
  actual sin cambios — `Specification` + `Paginacion.pageRequest(...)` +
  `oportunidadRepository.findAll(spec, pageRequest)`.
- **`cantidad`, `montoTotal`**: camino nuevo, vía `NamedParameterJdbcTemplate`
  (inyéctalo en el constructor, `private val`, regla 8). Pasos:
  1. Aplica los mismos filtros de visibilidad/estado que `especificacion()`
     ya construye, pero como SQL nativo parametrizado (revisa
     `OportunidadVisibilidad` para entender cómo resolver `idsColaboracion`/
     `predicadoVisibilidad` fuera de una `Specification` — si no tiene un
     método que te sirva directamente en SQL, **detente y consulta**: no
     reimplementes la lógica de visibilidad por tu cuenta sin confirmar que
     replica exactamente la misma regla).
  2. `SELECT o.id FROM oportunidades o WHERE {filtros} ORDER BY (subconsulta
     de D29) {ASC|DESC} LIMIT :size OFFSET :offset`.
  3. `SELECT COUNT(*) FROM oportunidades o WHERE {mismos filtros}` para el
     `total` de `Paginacion.meta(...)`.
  4. `oportunidadRepository.findAllById(ids)`, **reordenado según el orden de
     `ids`** (Spring Data no garantiza el orden de `findAllById` — arma un
     `Map<Long, Oportunidad>` y mapea `ids.map { mapa.getValue(it) }`).
  5. Pasa la lista reordenada a `toDtos(...)`, igual que el camino existente.

**La fórmula de dinero en la subconsulta es idéntica a la de C1/D22** —
cópiala tal cual, con el mismo comentario apuntando a `MontoTotal.calcular`.

### Tests — dos cosas distintas, no una

**Ya verificado por el arquitecto, no lo redescubras:**
`OportunidadListadoSpecificationTest.kt` valida `Specification`s de JPA
Criteria contra el metamodelo de Hibernate **offline** (sin conexión, sin
Testcontainers — así arranca sin Docker) para atrapar errores de
`root.get("propiedadQueNoExiste")` en tiempo de test. **No ejecuta SQL contra
una base real.** El camino nuevo de esta tarea (paso 2 del diseño) es SQL
nativo puro vía `NamedParameterJdbcTemplate` — no es una `Specification`, así
que este mecanismo **no tiene nada que validar ahí**, ni parcial ni
indirectamente.

**Por tanto, en dos partes:**

1. **Borra** el test `ordenar por cantidad y monto_total usa la columna
   sincronizada y respeta el agregado de los items` de
   `OportunidadListadoSpecificationTest.kt` — verificaba una columna que ya no
   existe (C7 ya la quitó) por un mecanismo (metamodelo offline) que de todas
   formas no puede probar SQL nativo. No lo "adaptes"; no hay nada que adaptar
   en ese archivo para este caso.
2. **Escribe un test nuevo, `@Tag("integration")`**, en
   `src/test/kotlin/pe/quantum/crm/domain/oportunidades/OportunidadListadoSortNativoIntegrationTest.kt`
   (o el nombre que prefieras, siguiendo el patrón de
   `ReporteServiceIntegrationTest.kt` — `@SpringBootTest`, extiende
   `IntegrationTestBase`, siembra con `JdbcTemplate` crudo). Casos: dos
   oportunidades con distinto número de ítems, `sort=cantidad` y
   `sort=monto_total` en ambas direcciones (`asc`/`dir`) devuelven el orden
   correcto vía `OportunidadService.listar(...)`. **No puedes ejecutar este
   test en local** (Docker 29) — verifícalo por lectura cuidadosa, con el
   mismo cuidado que C3/C4, y repórtalo explícitamente como "no ejecutable en
   local, verificado por lectura", nunca como si hubiera corrido en verde.

**Criterio de aceptación:** `./gradlew compileKotlin compileTestKotlin ktlintCheck detekt --console=plain -q --no-daemon` en EXIT:0 (esto sí lo puedes correr: solo compila, no ejecuta el test de integración). Reporta explícitamente que el test nuevo es `@Tag("integration")` y no corrió.

---

## C6 · Retirar la sincronización D21 de `OportunidadItemServiceImpl`

**Modelo:** Opus 5 · **Esfuerzo:** High

Ahora que `reportes` (C1), `inicio` (C2) y el sort (C5) leen `oportunidad_items`
directamente, la sincronización de columnas viejas (D21 de
`plan-05-mapa-migrar-items.md`) no tiene ningún consumidor. Retírala.

Abre `OportunidadItemServiceImpl.kt` completo. Elimina:
- El método privado `sincronizarColumnasViejas(...)`.
- Sus 4 llamadas: al final de `crear()`, `actualizar()`, `eliminar()`,
  `aplicarDescuentoAprobado()`.

**No toques ninguna otra lógica** de esos 4 métodos — solo la línea/bloque que
llama a la sincronización.

### Tests

`OportunidadItemServiceImplTest.kt` tiene tests que verificaban explícitamente
el efecto de la sincronización sobre la oportunidad dueña (por ejemplo, "crea
sincroniza id_modelo/cantidad/... cuando es el único ítem", "crear un segundo
ítem... columnas quedan null"). **Bórralos** — verificaban un comportamiento
que ya no existe, no algo que haya que preservar de otra forma. No los dejes
comentados ni los "arregles" para que sigan pasando de otro modo.

**Restricciones:** no toques `crear()`, `actualizar()`, `eliminar()`,
`aplicarDescuentoAprobado()` más allá de quitar la llamada a la
sincronización. No toques `OportunidadServiceImpl.kt` (es C7).

**Criterio de aceptación:** `./gradlew compileKotlin ktlintCheck detekt --console=plain -q --no-daemon` en EXIT:0. `./gradlew test --tests '*OportunidadItem*' --console=plain -q --no-daemon` en EXIT:0 (estos SÍ corren en local, no son `@Tag("integration")`).

---

## C7 · `Oportunidad.kt` y `OportunidadServiceImpl.crear()`: quitar los 5 campos

**Modelo:** Opus 5 · **Esfuerzo:** High

Abre `Oportunidad.kt` (la entidad JPA) y `OportunidadServiceImpl.kt`
(`crear()`) completos.

### `Oportunidad.kt`

Quita del constructor: `cantidad`, `precioUnitario`, `dcto`, `montoTotal`,
`idModelo`. Quita también sus anotaciones `@Column` y cualquier import que
quede sin uso.

### `OportunidadServiceImpl.crear()`

Ya no construye la entidad con `idModelo = modelo.id` (era necesario solo
porque la columna era `NOT NULL` — dejará de existir). El resto de `crear()`
(los 8 pasos, la llamada a `oportunidadItemService.crear(...)`) no cambia.

**Restricciones:** no toques `toDto`/`toDtos`/`actualizar()` — no referencian
estos campos desde el Plan B (B7/B8 ya los quitaron del DTO). Si al revisar
encuentras que SÍ los referencian todavía, es una señal de que algo del Plan
B quedó sin cerrar — **detente y consulta**, no lo arregles sobre la marcha
como parte de esta tarea.

**Criterio de aceptación:** no corras `./gradlew compileKotlin` esperando
verde — **va a fallar**, porque los 11 archivos de fixtures (C8, tarea
siguiente) todavía instancian `Oportunidad(...)` con los campos que acabas de
quitar. Esto es esperado. Reporta el contenido final de ambos archivos
modificados, y confirma con `grep -n "cantidad\|precioUnitario\|dcto\|montoTotal\|idModelo" src/main/kotlin/pe/quantum/crm/domain/oportunidades/Oportunidad.kt` que no quedó ninguno de los 5 campos (ten cuidado: `idModelo` podría dar falso positivo si queda en un comentario — verifica que sea el campo real, no un comentario huérfano).

---

## C8 · Arreglo mecánico de los 11 fixtures de test que instancian `Oportunidad(...)`

**Modelo:** Sonnet 5 · **Esfuerzo:** Extra High

Cierra la compilación rota por C7. Lista completa de archivos en
`plan-07-mapa-retirar-columnas.md` §5 (11 archivos, todos en
`src/test/kotlin/pe/quantum/crm/domain/oportunidades/`).

Para cada uno: localiza el fixture privado que construye `Oportunidad(...)`
(`oportunidad(...)`, `oportunidadDe(...)`, `oportunidadConVendedor(...)`,
según el archivo) y quita los 5 argumentos nombrados (`cantidad =`,
`precioUnitario =`, `dcto =`, `montoTotal =`, `idModelo =`) de la llamada al
constructor. **No toques ningún otro parámetro del fixture, ni ninguna
aserción del archivo.**

**Ojo con `OportunidadListadoSpecificationTest.kt`**: si C5 ya lo tocó (para
el test de sort nuevo), **no deshagas ese trabajo** — lee el estado actual
del archivo antes de aplicar el arreglo mecánico, y aplícalo solo sobre lo
que C5 no haya tocado ya.

Tras quitar los 5 argumentos, algunos fixtures pueden quedar con parámetros
propios (`cantidad`, `montoTotal`, etc. como parámetro del *helper*, no del
constructor de `Oportunidad`) que ya no se usan para nada — revisa caso por
caso: si el parámetro del helper solo existía para pasarlo al constructor de
`Oportunidad` y ahora no tiene destino, **detente y consulta** antes de
decidir si quitarlo del helper también (podría estar usándose en otro sitio
del mismo archivo, por ejemplo para construir el `OportunidadItem` de un test
que sí necesita esos valores).

**Criterio de aceptación:** `./gradlew compileTestKotlin --console=plain -q --no-daemon > /tmp/c8.log 2>&1; echo "EXIT:$?"` en EXIT:0 — **este es el criterio real de que la tarea está completa**, ya que C7 dejó todo sin compilar a propósito. `./gradlew test --console=plain -q --no-daemon` también en EXIT:0 para todo lo que no sea `@Tag("integration")`.

Nota de infraestructura: si Gradle falla con `CorruptedException`, "Could not delete" o similar, mata los procesos `java.exe`, `./gradlew --stop`, borra `build/`, reintenta con `--no-daemon`. No confíes en el exit code de un pipeline con `| tail`.

---

## C9 · Migración V46: `DROP COLUMN` + `SeedFixtures`

**Modelo:** Sonnet 5 · **Esfuerzo:** Low

**No apliques nada a producción.** Solo se escriben archivos locales.

Verifica primero que V45 sigue siendo el máximo en
`src/main/resources/db/migration/` (`ls` la carpeta) — si no, repórtalo sin
cambiar el número tú mismo.

**Archivo:** `src/main/resources/db/migration/V46__drop_columnas_planas_oportunidades.sql`

```sql
-- =============================================================================
-- V46 — Retira las columnas planas de `oportunidades` que el rediseno
-- multi-modelo (V42-V45) dejo como codigo puente mientras reportes/inicio no
-- migraban a leer `oportunidad_items` directamente (plan-05-mapa-migrar-items.md,
-- decision D21). Con ese codigo ya retirado (plan-07-mapa-retirar-columnas.md,
-- decision D27), estas columnas no tienen ningun lector.
--
-- Sin backfill: todo lo que hay aqui ya esta duplicado en `oportunidad_items`
-- desde el backfill de V42, y se mantuvo sincronizado hasta ahora por D21.
-- Verificado contra produccion antes de escribir esta migracion: 5
-- oportunidades, 5 items, ninguna oportunidad con mas de un item todavia.
-- =============================================================================

ALTER TABLE oportunidades
    DROP COLUMN cantidad,
    DROP COLUMN precio_unitario,
    DROP COLUMN dcto,
    DROP COLUMN monto_total,
    DROP COLUMN id_modelo;
```

### `SeedFixtures.kt`

`MIGRACIONES_TOTAL` y `MIGRACION_VERSION_MAX` pasan ambas a **46** — esta
migración es correlativa (no hay hueco nuevo), así que las dos constantes
vuelven a coincidir. Actualiza el comentario que explica el hueco de V40 para
dejar claro que ese hueco sigue existiendo (44 archivos hasta V45, ahora 45
hasta V46) pero ya no hay divergencia entre "cuenta de archivos" y "versión
máxima" porque V46 no reabre ningún hueco.

**Restricciones:** no toques `SchemaMigrationIntegrationTest.kt` — `DROP
COLUMN` no cambia la lista de tablas ni de enums (siguen siendo 24 y 21). Si
al revisar concluyes que sí hace falta un cambio ahí, **detente y consulta**
antes de tocarlo — ya nos costó dos rondas de CI no verificar esto con
cuidado en el Plan B.

**Criterio de aceptación:** el archivo de migración existe con el contenido
exacto de arriba. `SeedFixtures.kt` compila
(`./gradlew compileTestKotlin --console=plain -q --no-daemon` en EXIT:0).

---

## C10 · Verificación de build completa (local, sin `integrationTest`)

**Modelo:** Sonnet 5 · **Esfuerzo:** Low

```bash
./gradlew ktlintCheck --console=plain -q --no-daemon > /tmp/c10_lint.log 2>&1; echo "EXIT:$?"
./gradlew detekt --console=plain -q --no-daemon > /tmp/c10_detekt.log 2>&1; echo "EXIT:$?"
./gradlew test --console=plain -q --no-daemon > /tmp/c10_test.log 2>&1; echo "EXIT:$?"
```

**No ejecutes `integrationTest` ni `koverVerify`** (koverVerify arrastra
integrationTest como dependencia — mismo bloqueo de Docker 29 de toda la
sesión). Esta tarea **no puede** confirmar en verde los tests
`@Tag("integration")` que este plan modificó (C3, C4, y posiblemente C5) — de
eso se entera el arquitecto cuando abra el PR y CI corra. Repórtalo así
explícitamente, no como una limitación menor.

**Criterio de aceptación:** los tres comandos de arriba en EXIT:0, reportados
con su salida completa (redirigida a archivo, exit code comprobado por
separado — no confíes en un pipeline con `| tail`).

Nota de infraestructura: si Gradle falla con `CorruptedException`, "Could not
delete" o similar, mata los procesos `java.exe`, `./gradlew --stop`, borra
`build/`, reintenta con `--no-daemon`.

---

## C11 · Documentación: `contrato_api.md`, `reglas_negocio.md`

**Modelo:** Sonnet 5 · **Esfuerzo:** Medium

1. **`docs/contrato_api.md` §26 Changelog**: una entrada **Non-breaking**
   (verifica el número de sección actual, puede haber cambiado). El contrato
   de `/reportes/*` y `/oportunidades` no cambia de forma (K4) — la entrada
   documenta que la **fuente de datos** cambió, y que `porModelo` de
   `/reportes/ventas` y todos los reportes con descuento ahora reflejan
   items individuales cuando una oportunidad tenga varios (hoy no hay
   ninguna en producción, pero el día que la haya, los números granulares
   cambian sin que sea un bug). Sin acción requerida para el frontend salvo
   estar al tanto de ese matiz.
2. **`docs/reglas_negocio.md` §7 y §12**: quita la nota de "columna puente
   mientras reportes/inicio no migran" que el Plan B dejó (B16 la escribió
   así a propósito, anticipando este momento) — ya migraron. Deja el texto
   describiendo el estado final: los campos viven solo en `oportunidad_items`.

**Restricciones:** no toques ningún archivo `.kt`.

**Criterio de aceptación:** las tres ediciones hechas, coherentes con el código real de C1-C9 (relee el código antes de documentar).

---

## C12 · Auditoría final del diff contra los documentos citados

**Modelo:** Opus 5 · **Esfuerzo:** High

Tarea exigida por `CLAUDE.md`. **Auditoría del diff completo, no resumen.**

Contrasta contra: `plan-07-mapa-retirar-columnas.md` completo (K1-K9, D22-D29) ·
`plan-05-mapa-migrar-items.md` D21 (debe estar completamente retirada, sin
rastro) · `reglas_negocio.md` §7, §10.3, §12 · `CLAUDE.md` reglas 1, 9, 10,
11, 12.

Busca en concreto:

1. **Contradicciones con documentación ya vigente y correcta.**
2. **Que D21 no tenga ningún rastro**: `grep -rn "sincronizarColumnasViejas\|columnas viejas\|codigo puente" src/main/` — cualquier resultado en código de producción es un hallazgo (comentarios en docs históricos como `plan-05` están bien, ahí es registro).
3. **Que las 5 columnas realmente se fueron**: `grep -n "cantidad\|precioUnitario\|dcto\|montoTotal\|idModelo" src/main/kotlin/pe/quantum/crm/domain/oportunidades/Oportunidad.kt` debe salir vacío o solo con comentarios/KDoc que no sean el campo real.
4. **K7 preservado exacto**: relee los tests de `descuentos` en `ReporteServiceSqlIntegrationTest.kt` (post-C4) y confirma que `descuento NULL → 0` y `estado cerrado → excluido` siguen siendo exactamente lo que se prueba, sin relajar ninguna aserción.
5. **D23 (operacionesCount vs monto/unidades)**: confirma en `ReporteService.ventas()` que `operacionesCount` cuenta oportunidades distintas, no filas de ítem.
6. **La fórmula de dinero duplicada 3 veces** (Kotlin `MontoTotal.calcular`, SQL de `reportes`/C1, SQL de sort/C5) — confirma que las tres tienen el mismo comentario apuntando a `MontoTotal.calcular` como fuente de verdad, y que la fórmula en sí es idéntica en los tres sitios (mismo `COALESCE`, mismo `ROUND ... 2`).
7. **Que no se tocara `domain/solicitudes/`** — fuera de alcance de este plan.
8. **Que no se añadiera ninguna dependencia** a `build.gradle.kts`.
9. **`SchemaMigrationIntegrationTest.kt`**: confirma que C9 no lo tocó (D28: `DROP COLUMN` no cambia tablas ni enums) — si lo encuentras modificado sin que ninguna tarea lo pidiera, es un hallazgo.
10. **Honestidad sobre `integrationTest`**: que ninguna tarea haya reportado en verde algo que no pudo ejecutar. Revisa los reportes de C1-C5 buscando frases como "no ejecutable en local" — si algún reporte afirma verde sobre un test `@Tag("integration")`, es un hallazgo bloqueante de proceso, no solo de código.

**Entregable:** informe con hallazgos en *bloqueante / menor / ninguno*,
archivo, línea y regla o sección concreta. **No arregles nada. No hagas
commit.**

---

## Cierre del plan

Al terminar C12, **para y resume** qué se hizo. **No abras PR ni hagas commit**
— eso lo decide el arquitecto, igual que en los planes anteriores.

Estado esperado al cerrar: `oportunidades` ya no tiene columnas de ítem —
`oportunidad_items` es la única fuente, en toda la aplicación, sin excepción.
La migración V46 queda escrita y verificada localmente, pero **sin aplicar a
producción** hasta que el PR tenga `integrationTest` en verde en CI (C13,
coordinada aparte).
