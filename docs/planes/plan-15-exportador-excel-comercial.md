# Plan 15 — Exportador Excel de gestión comercial

> **Para el agente ejecutor (Google Antigravity):** este plan se ejecuta **tarea por tarea, en orden**. No saltes tareas. No agrupes tareas. Cada paso con `- [ ]` es una acción única; márcalo al terminarlo. **No improvises código que no esté escrito literalmente en este documento.** Todo el SQL, todo el Kotlin y todos los tests están escritos aquí completos: cópialos textualmente. Si algo no compila o un test falla de forma que este plan no anticipa, **detente y reporta**; no inventes una solución alternativa.

**Goal:** Un endpoint `GET /api/v1/reportes/exportar-comercial` que devuelve un archivo `.xlsx` descargable, de **una sola hoja**, con prospectos, pipeline y el histórico completo de actividades comerciales, restringido a los roles `admin`, `gerencia` y `jdv`.

**Architecture:** Se añade al módulo `reportes` existente, siguiendo exactamente su patrón: SQL nativo parametrizado sobre `NamedParameterJdbcTemplate`, sin entidades JPA y sin cruzar la frontera de otros módulos por clases (el SQL nativo no crea dependencias de bytecode, que es lo único que ArchUnit vigila). La lógica se parte en tres piezas con responsabilidades separadas: un servicio que sólo consulta (`ExportComercialService`), un traductor puro de etiquetas y nulos (`EtiquetasExport`) y un constructor puro del libro Excel (`LibroExportComercial`). Las dos piezas puras concentran toda la lógica testeable sin base de datos, que es lo que sostiene los gates de cobertura.

**Tech Stack:** Kotlin 1.9.25 · Spring Boot 3.2.5 · Spring JDBC (`NamedParameterJdbcTemplate`) · PostgreSQL 16 · Apache POI 5.4.1 (nuevo) · JUnit 5 + AssertJ + MockK/springmockk · Gradle Kotlin DSL · JDK 21.

**Spec:** `docs/requerimientos/2026-09-08-exportador-excel-pipeline-comercial.json` (ticket original) más las seis respuestas de producto recogidas en la sección "Decisiones de producto ya cerradas" de este documento.

---

## Fase de investigación — documentos y reglas que gobiernan este cambio

> Esta sección existe porque `CLAUDE.md` lo exige explícitamente ("Cómo escribir un plan de implementación en este repo"). **Léela completa antes de la Tarea 1.** No basta con saber que estos documentos existen: abajo está citado lo que dicen y cómo aplica.

### Documentos de referencia consultados

| Documento | Qué dice que aplica a este cambio |
|---|---|
| `docs/contrato_api.md` §18 (línea 1934) | Los reportes exigen rol `admin`, `gerente` o `jdv` y aceptan `fecha_desde`/`fecha_hasta` ISO-8601, con default "mes calendario actual". **Ojo:** el texto dice `gerente`, un rol que ya no existe (V25 lo renombró a `gerencia`). Esa deriva se corrige en la Tarea 7. |
| `docs/contrato_api.md` §28 (línea 2857) | "Todo PR que modifique la forma de un request/response... o agregue/quite un endpoint documentado aquí, agrega una entrada a esta tabla en el mismo PR. Sin entrada, el PR no se considera completo aunque el código y los tests pasen." Un endpoint nuevo es **non-breaking**. Aplica en la Tarea 7. |
| `docs/contrato_api.md` §29 (línea 2879) | El historial de actividades une tareas y eventos. Documenta la trampa de las dos fechas: `fecha_hora` (TIMESTAMP) y `fecha_dia` (DATE) **no se unifican** porque darle hora a un DATE lo desplaza de día. Aplica al exportar fechas de actividad. |
| `docs/matriz_permisos.md` §2.10 (líneas 191-202) | Los 6 reportes existentes son visibles para `admin`, `gerencia`, `jdv` y para nadie más: "Ningún rol `vendedor`, `analista` ni `otro` tiene acceso a reportes en el MVP." El export nuevo se suma a esa tabla con los mismos tres roles. Aplica en la Tarea 7. |
| `docs/reglas_negocio.md` §4.1 (líneas 137-149) | El pipeline tiene 4 estados: `evaluacion_calidda` → `documentos_legales` → `facturado`, con `cerrado` como salida negativa recuperable. "No existe un estado `perdido`." Aplica al mapear la columna "Etapa / Estado actual". |
| `docs/reglas_negocio.md` §4.4 (líneas 177-179) | `motivo_cierre` es obligatorio sólo cuando `estado = 'cerrado'`, con CHECK en base y validación en backend. Es la columna que explica por qué se cayó una operación. |
| `docs/reglas_negocio.md` §10 (líneas 371-410) | Prospección **no es una etapa de `oportunidades`**: es una empresa con `estado_cartera = 'prospeccion'` sin oportunidades activas. Por eso el export arranca de `empresas`, no de `oportunidades`. |
| `docs/TESTING-backend.md` §9 y §11 | Nombres de test como especificación en backticks; estructura Arrange-Act-Assert. Reglas finales: nunca código de producción sin un test que falle antes; nunca commit en rojo; un test que nunca falló no es confiable. |
| `src/main/resources/db/migration/` (V1…V49) | **La verdad del schema.** `docs/schema.sql` y `docs/migrations/` están desactualizados (llegan a V19; el repo va por V49) — no los consultes para nada en este plan. |

### Reglas de `CLAUDE.md` que tocan este cambio

| Regla | Qué exige aquí |
|---|---|
| **1 — TDD siempre** | Cada tarea de código de este plan empieza por un test que falla. No escribas implementación antes de ver el rojo. Ninguna tarea termina sin `./gradlew test` en verde. |
| **2 — `monto_total` se calcula, nunca se acepta como input** | El export **calcula** el monto con la fórmula `cantidad × precio_venta × (1 − descuento/100)` sobre `oportunidad_items`. No existe ya una columna `monto_total` en `oportunidades` (la retiró V46): si intentas leerla, el SQL falla. |
| **3 — `estado_cartera` sólo vía `actualizarEstadoCartera()`** | Este cambio es **de solo lectura**. No escribe en ninguna tabla. Si te ves escribiendo un `INSERT`, `UPDATE` o `DELETE` fuera de un test, te saliste del plan. |
| **6 — el paso a `facturado` sólo para admin/gerencia** | No aplica directamente (no hay cambio de estado), pero confirma que `gerencia` es el nombre del rol, **nunca `gerente`**. |
| **7 — no existe estado `perdido`** | El enum tiene exactamente 4 valores. La columna "Etapa / Estado actual" usa esos 4 y ninguno más. No inventes un quinto. |
| **8 — inyección por constructor** (`private val`), nunca `@Autowired` en campos | `ExportComercialService` recibe `NamedParameterJdbcTemplate` por constructor. |
| **9 — relaciones JPA siempre LAZY; nunca exponer entidades en controllers** | Este cambio no usa JPA en absoluto. El controller devuelve un `ByteArray`, nunca una entidad. |
| **10 — `@Transactional(readOnly = true)` en lecturas** | El método del servicio lleva `@Transactional(readOnly = true)`, igual que todos los de `ReporteService`. |
| **11 — queries parametrizadas siempre; nunca SQL por concatenación** | Las fechas van como parámetros nombrados (`:desde`, `:hasta`). La única concatenación permitida es añadir un fragmento `AND ...` **fijo, sin datos del usuario dentro**, exactamente como ya hace `ReporteService.ventas()`. Nunca interpoles un valor en el string SQL. |
| **12 — un módulo nunca accede a tablas ni entidades de otro módulo** | Verificado por ArchUnit sobre bytecode. El SQL nativo no crea dependencias de clase, así que consultar `empresas`/`tareas`/`eventos` desde `reportes` es legal y es exactamente lo que `ReporteService` ya hace hoy. **Lo que sí rompería la regla es importar `Empresa`, `Tarea`, `Evento`, `EmpresaRepository` o cualquier `*Impl` de otro módulo. No importes ninguna de esas clases.** |
| **13 — nunca secretos en código** | No aplica: no hay credenciales nuevas. |
| **14 — IDOR: recurso ajeno → 404, no 403** | No aplica: el export no recibe id de ningún recurso; devuelve todo el universo para roles que ya lo ven completo. |

### Decisiones de producto ya cerradas (respondidas por el solicitante el 2026-09-08)

Estas seis respuestas cierran las preguntas abiertas del ticket. **Son requisitos, no sugerencias.**

1. **Una sola hoja.** No varias pestañas.
2. **Sin fecha límite concreta.** No afecta al diseño.
3. **Las columnas sin dato real llevan `"-"`**, no se dejan vacías ni se omiten.
4. **`jdv` se incluye**, con visión total (no acotada a su equipo) — consistente con el acceso que ya tiene a los otros 6 reportes.
5. **Histórico de actividades completo** por oportunidad, no un resumen ni un conteo.
6. **Cualquier oportunidad cuenta como "operación avanzada".** No hay que filtrar por un criterio de "avance": se exportan todas.

---

## Global Constraints

Estas restricciones aplican a **todas** las tareas. Cópialas mentalmente a cada una.

- **Rama de trabajo:** `feature/export-excel-comercial`. Se crea en la Tarea 1 y todo se commitea ahí. **Nunca commits directos a `main`.**
- **Idioma del código:** identificadores, nombres de test y comentarios en **español**, sin tildes en identificadores. Los comentarios del repo no llevan tildes (mira cualquier archivo existente); síguelo.
- **Longitud máxima de línea: 140 caracteres** (`config/detekt/detekt.yml`, `MaxLineLength`). Ninguna línea de Kotlin puede pasarse. El SQL va en strings `"""` con `.trimIndent()`, y cada línea de dentro también respeta el límite.
- **Formato:** 4 espacios de indentación, comas finales (trailing commas) en listas de parámetros multilínea. Si dudas, ejecuta `./gradlew ktlintFormat` y deja que él decida.
- **Versión exacta de Apache POI: `5.4.1`.** No uses otra. No uses `poi` a secas: la dependencia es `org.apache.poi:poi-ooxml:5.4.1`.
- **Nombres exactos** (no los cambies, otras tareas dependen de ellos):
  - Endpoint: `GET /api/v1/reportes/exportar-comercial`
  - Servicio: `ExportComercialService`, método `filas(desde: LocalDate?, hasta: LocalDate?): List<FilaExportComercial>`
  - DTO: `FilaExportComercial` en el paquete `pe.quantum.crm.domain.reportes.dto`
  - Objeto de etiquetas: `EtiquetasExport` en `pe.quantum.crm.domain.reportes`
  - Constructor del libro: `LibroExportComercial`, método `construir(filas: List<FilaExportComercial>): ByteArray`
- **Comandos de verificación** (desde la raíz del repo, en Windows usa `gradlew.bat` si `./gradlew` no funciona):
  - `./gradlew test` — unitarios + ArchUnit, sin Docker. **Debe pasar antes de cada commit.**
  - `./gradlew ktlintCheck` — formato.
  - `./gradlew detekt` — análisis estático.
  - `./gradlew koverVerify` — cobertura.
  - `./gradlew integrationTest` — **no se puede ejecutar en local**: Testcontainers está roto por Docker 29 en esta máquina. Los tests `@Tag("integration")` se escriben igual y se validan en CI. **No intentes arreglar Docker ni borres el test porque no corre.**
- **No se crea ninguna migración.** Este cambio no toca el schema. Si te ves creando un archivo `V50__*.sql`, te saliste del plan. Hay un test (`SchemaMigrationIntegrationTest`) que cuenta migraciones y tablas y fallaría.

---

## Estructura de archivos

Antes de empezar, ten claro qué archivo hace qué. Cinco archivos nuevos de producción, uno modificado, cuatro archivos de test nuevos, tres documentos actualizados.

| Archivo | Responsabilidad | Tarea |
|---|---|---|
| `build.gradle.kts` *(modificar)* | Añade la dependencia Apache POI. | 1 |
| `src/main/kotlin/pe/quantum/crm/domain/reportes/dto/ExportComercialDtos.kt` *(crear)* | `data class FilaExportComercial`: una fila plana del Excel. Sólo datos, sin lógica. | 2 |
| `src/main/kotlin/pe/quantum/crm/domain/reportes/EtiquetasExport.kt` *(crear)* | Funciones puras: traduce enums a etiquetas de negocio y convierte nulos/vacíos en `"-"`. Es el corazón testeable sin base de datos. | 3 |
| `src/main/kotlin/pe/quantum/crm/domain/reportes/LibroExportComercial.kt` *(crear)* | Función pura `List<FilaExportComercial> → ByteArray`: escribe el `.xlsx` de una hoja con Apache POI. | 4 |
| `src/main/kotlin/pe/quantum/crm/domain/reportes/ExportComercialService.kt` *(crear)* | El SQL nativo y el mapeo de `ResultSet` a `FilaExportComercial`. Nada más. | 5 |
| `src/main/kotlin/pe/quantum/crm/domain/reportes/ReporteController.kt` *(modificar)* | Añade el endpoint que devuelve el archivo. | 6 |
| `src/test/kotlin/pe/quantum/crm/domain/reportes/EtiquetasExportTest.kt` *(crear)* | Unitario, sin Spring, sin base de datos. | 3 |
| `src/test/kotlin/pe/quantum/crm/domain/reportes/LibroExportComercialTest.kt` *(crear)* | Unitario: construye el libro y lo vuelve a leer con POI para verificarlo. | 4 |
| `src/test/kotlin/pe/quantum/crm/domain/reportes/ExportComercialControllerWebMvcTest.kt` *(crear)* | Slice HTTP sin base de datos: cabeceras, status y permisos por rol. | 6 |
| `src/test/kotlin/pe/quantum/crm/domain/reportes/ExportComercialServiceIntegrationTest.kt` *(crear)* | `@Tag("integration")`: el SQL contra Postgres real. Se valida en CI. | 5 |
| `docs/contrato_api.md` *(modificar)* | §18 el endpoint nuevo + §28 la entrada de changelog + corregir `gerente`→`gerencia`. | 7 |
| `docs/matriz_permisos.md` *(modificar)* | §2.10 fila nueva. | 7 |

---

## Diseño de datos — léelo antes de la Tarea 2

Esta sección explica **por qué** el SQL es como es. Si no entiendes esto, las Tareas 2 y 5 te van a parecer arbitrarias y vas a "corregir" cosas que están bien.

### La forma del Excel: una tabla plana de hechos

Una sola hoja (decisión 1) con histórico completo de actividades (decisión 5) obliga a una **tabla plana desnormalizada**: una fila por actividad, repitiendo en cada fila los datos del prospecto y de la oportunidad. Es el formato que Excel sabe filtrar y pivotar. Las alternativas (una fila por oportunidad con las actividades comprimidas en una celda) pierden el detalle que gerencia pidió.

### Qué es una fila

El universo de filas se construye así:

1. **Una fila base por cada oportunidad** — pareja `(empresa, oportunidad)`.
2. **Una fila base "de prospección" por empresa** — pareja `(empresa, NULL)` — que se emite cuando la empresa **no tiene ninguna oportunidad** (es un prospecto puro) **o** cuando tiene actividades colgadas de la empresa sin oportunidad (tareas y eventos de prospección; `reglas_negocio.md` §10).
3. A cada fila base se le pegan sus actividades con `LEFT JOIN`. Una fila base **sin ninguna actividad sigue produciendo exactamente una fila** en el Excel, con las columnas de actividad en `"-"`.

Este diseño garantiza que **ningún registro desaparece**: ni el prospecto sin oportunidad, ni la oportunidad sin actividades, ni la actividad de prospección de una empresa que además ya tiene oportunidades.

> **Trampa que este diseño evita, y que no debes "simplificar":** si unieras las actividades sólo por `id_oportunidad`, las actividades de prospección (`id_oportunidad IS NULL`) de una empresa que ya tiene oportunidades se perderían en silencio. Por eso la rama 2 del universo tiene ese `OR EXISTS(...)`. No lo borres.

### Los modelos y las unidades no multiplican filas

Desde V42/V46 una oportunidad puede vender varios modelos, y viven en `oportunidad_items`. Si los unieras con `JOIN` multiplicarías las filas otra vez. En su lugar se agregan en tres columnas calculadas con subconsulta: un texto `"K12 x3, K9 x2"`, el total de unidades, y el monto total.

### El rango de fechas

`fecha_desde`/`fecha_hasta` filtran por la **fecha de ingreso de la fila base**: `oportunidades.created_at` si la fila tiene oportunidad, `empresas.created_at` si es una fila de prospección. Las actividades **no** se filtran por el rango: una vez que la oportunidad está en alcance, viene su histórico completo (decisión 5).

**Default cuando no se envían fechas: sin filtro, todo el histórico.** Esto se aparta a propósito del default "mes calendario actual" que `contrato_api.md` §18 fija para los otros reportes: un export de control que por defecto recorta a un mes sorprendería a gerencia y parecería datos faltantes. Queda documentado como diferencia explícita en la Tarea 7.

### Mapeo de conceptos de negocio al modelo real

Estas equivalencias ya están decididas. **No las renegocies durante la ejecución.**

| Lo que pidió negocio | De dónde sale realmente | Nota |
|---|---|---|
| "Origen del prospecto" (cartera propia, Cálidda, MTC, referido, prospección en calle) | `empresas.origen_lead` — enum de 5 valores: `cartera`, `visita_fria`, `referido_calidda`, `red_contactos`, `otro` | El mapeo es aproximado y es lo que hay. **"MTC" no tiene equivalente**: cae en `otro` o queda `"-"` si es nulo. |
| "Segmento" (transporte urbano, personal, turismo) | `empresa_segmentos` — enum `urbano`, `personal`, `turismo`, `interprovincial`, `otro` | Es **multi-valor**: una empresa puede tener varios. Se concatenan con `", "` en una celda. |
| "Etapa alcanzada" (contacto, reunión, cotización, evaluación, aprobación Cálidda, contrato) | `oportunidades.estado` — 4 valores: `evaluacion_calidda`, `documentos_legales`, `facturado`, `cerrado` | **La granularidad que pidió negocio no existe en el modelo.** El export da el estado real de 4 valores. No inventes etapas intermedias. |
| "Razón de no cierre / pérdida" | `oportunidades.motivo_cierre` | Sólo tiene valor cuando `estado = 'cerrado'`; en el resto va `"-"`. |
| "Fecha de primer contacto" | `MIN(created_at)` de las actividades (tareas ∪ eventos) de esa oportunidad/empresa | Derivado, no es un campo. |
| "Última gestión" | `MAX(created_at)` de esas mismas actividades | Derivado. Misma idea que el `ultima_actividad` que ya calcula `ReporteService.pipeline()`. |
| "Siguiente acción" | La **próxima tarea pendiente** (`tareas.estado_accion = 'pendiente'`), la de `fecha_ejecucion` más cercana | Dato real, con precedente: el módulo `prospeccion` ya calcula un `siguienteTarea`. |
| "Fecha estimada de cierre" | `oportunidades.fecha_cierre_estimado` | Existe tal cual. |
| "Principal bloqueo" | **No existe ningún campo** | La columna se emite siempre con `"-"`. **No la rellenes con `notas` ni con `motivo_cierre`: sería inventar contenido, y el ticket lo prohíbe (R8).** |
| "Qué ocurrió" en operaciones caídas | `oportunidades.motivo_cierre` + los comentarios de la actividad | Ya cubierto por columnas existentes. |

---

## Tarea 1: Rama de trabajo y dependencia Apache POI

**Files:**
- Modify: `build.gradle.kts` (bloque `dependencies`, después de la línea 63)

**Interfaces:**
- Consumes: nada (primera tarea).
- Produces: las clases `org.apache.poi.ss.usermodel.*` y `org.apache.poi.xssf.usermodel.XSSFWorkbook` disponibles en `main` y en `test`.

- [ ] **Paso 1: Verifica que estás en un árbol limpio y crea la rama**

Ejecuta, en este orden exacto:

```bash
git status
```

Mira la salida. Si hay archivos **modificados** (`M`) que tú no tocaste, **detente y reporta**: no sigas. Un archivo sin seguimiento (`??`) llamado `docs/requerimientos/2026-09-08-exportador-excel-pipeline-comercial.json` es esperado y no molesta.

Luego:

```bash
git checkout -b feature/export-excel-comercial
```

- [ ] **Paso 2: Añade la dependencia de Apache POI**

Abre `build.gradle.kts`. Busca esta línea (está sobre la línea 63):

```kotlin
    implementation("org.apache.commons:commons-fileupload2-jakarta-servlet6:2.0.0-M5")
```

Inmediatamente **después** de esa línea, y **antes** del comentario `// Migraciones`, inserta este bloque exacto:

```kotlin

    // Apache POI: generacion del .xlsx del export comercial (plan-15). Solo se usa
    // `poi-ooxml` (formato XLSX); no se agrega `poi-scratchpad`, que es para los
    // formatos binarios viejos y arrastra dependencias que este proyecto no necesita.
    implementation("org.apache.poi:poi-ooxml:5.4.1")
```

- [ ] **Paso 3: Verifica que el proyecto sigue compilando con la dependencia nueva**

Run: `./gradlew compileKotlin`

Expected: `BUILD SUCCESSFUL`. Si falla con un error de resolución de dependencias, revisa que escribiste `org.apache.poi:poi-ooxml:5.4.1` exactamente y que tienes red. **No cambies la versión para "probar si otra funciona".**

- [ ] **Paso 4: Verifica que los tests siguen verdes antes de tocar nada más**

Run: `./gradlew test`

Expected: `BUILD SUCCESSFUL`. Esto establece la línea base: cualquier rojo posterior es culpa tuya, no preexistente.

- [ ] **Paso 5: Commit**

```bash
git add build.gradle.kts
git commit -m "$(cat <<'EOF'
build: agregar Apache POI para el exportador Excel comercial

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
EOF
)"
```

---

## Tarea 2: El DTO de una fila del export

**Files:**
- Create: `src/main/kotlin/pe/quantum/crm/domain/reportes/dto/ExportComercialDtos.kt`

**Interfaces:**
- Consumes: nada.
- Produces: `data class FilaExportComercial` con **exactamente** los 33 campos de abajo, en ese orden. Las Tareas 3, 4, 5 y 6 dependen de estos nombres y tipos exactos. Si cambias uno, rompes las tres.

**Por qué esta tarea no tiene test propio:** es una `data class` sin lógica — un contenedor de datos. `docs/TESTING-backend.md` no pide tests para estructuras de datos, y `build.gradle.kts` excluye explícitamente los mapeos de la medición de cobertura. Los tests llegan en la Tarea 3, que sí tiene lógica. Esta tarea se verifica compilando.

- [ ] **Paso 1: Crea el archivo con el DTO completo**

Crea `src/main/kotlin/pe/quantum/crm/domain/reportes/dto/ExportComercialDtos.kt` con **exactamente** este contenido:

```kotlin
package pe.quantum.crm.domain.reportes.dto

import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * Una fila plana del export comercial (plan-15). Una hoja unica, una fila por
 * ACTIVIDAD, repitiendo los datos del prospecto y de la oportunidad.
 *
 * Los campos son nullable a proposito: aqui se guarda lo que la base devuelve,
 * crudo. La traduccion de null a "-" ocurre despues, en `EtiquetasExport`, para
 * que la conversion sea una sola y este testeada en un solo sitio.
 *
 * Tres bloques, en el orden en que salen las columnas del Excel:
 *   1. Prospecto/empresa  (7 columnas)
 *   2. Oportunidad        (17 columnas)
 *   3. Actividad          (9 columnas)
 */
@Suppress("LongParameterList") // Una fila plana de export refleja 33 columnas.
data class FilaExportComercial(
    // ── Bloque 1: prospecto / empresa ──────────────────────────
    val ruc: String?,
    val razonSocial: String?,
    val empresaCreadaEn: LocalDateTime?,
    val responsableEmpresa: String?,
    val origenLead: String?,
    val segmentos: String?,
    val estadoCartera: String?,
    // ── Bloque 2: oportunidad ──────────────────────────────────
    val idOportunidad: Long?,
    val vendedorOportunidad: String?,
    val estadoOportunidad: String?,
    val modelosYUnidades: String?,
    val unidadesTotales: Int?,
    val montoTotal: BigDecimal?,
    val financiadora: String?,
    val oportunidadCreadaEn: LocalDateTime?,
    val fechaCierreEstimado: LocalDate?,
    val facturadoEn: LocalDateTime?,
    val motivoCierre: String?,
    val notasOportunidad: String?,
    val siguienteAccion: String?,
    val principalBloqueo: String?,
    val primerContacto: LocalDateTime?,
    val ultimaGestion: LocalDateTime?,
    val diasSinGestion: Int?,
    // ── Bloque 3: actividad ────────────────────────────────────
    val actividadTipo: String?,
    val actividadTitulo: String?,
    val actividadTipoAccion: String?,
    val actividadEstado: String?,
    val actividadFecha: LocalDateTime?,
    val actividadResponsable: String?,
    val actividadDescripcion: String?,
    val actividadComentarios: String?,
    val actividadRegistradaEn: LocalDateTime?,
)
```

- [ ] **Paso 2: Verifica que compila**

Run: `./gradlew compileKotlin`

Expected: `BUILD SUCCESSFUL`.

- [ ] **Paso 3: Verifica el formato**

Run: `./gradlew ktlintCheck`

Expected: `BUILD SUCCESSFUL`. Si falla, ejecuta `./gradlew ktlintFormat` y vuelve a verificar.

- [ ] **Paso 4: Commit**

```bash
git add src/main/kotlin/pe/quantum/crm/domain/reportes/dto/ExportComercialDtos.kt
git commit -m "$(cat <<'EOF'
feat(reportes): DTO de fila del export comercial

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
EOF
)"
```

---

## Tarea 3: Etiquetas de negocio y la regla del guion

**Files:**
- Create: `src/main/kotlin/pe/quantum/crm/domain/reportes/EtiquetasExport.kt`
- Test: `src/test/kotlin/pe/quantum/crm/domain/reportes/EtiquetasExportTest.kt`

**Interfaces:**
- Consumes: nada.
- Produces, todo dentro de `object EtiquetasExport` en el paquete `pe.quantum.crm.domain.reportes`:
  - `const val SIN_DATO: String` — vale `"-"`.
  - `fun texto(valor: Any?): String` — devuelve `SIN_DATO` si es null, o si es un String en blanco; si no, `valor.toString()`.
  - `fun fecha(valor: LocalDateTime?): String` — formato `yyyy-MM-dd HH:mm`, o `SIN_DATO`.
  - `fun dia(valor: LocalDate?): String` — formato `yyyy-MM-dd`, o `SIN_DATO`.
  - `fun estado(valor: String?): String` — traduce los 4 valores de `EstadoOportunidad` a etiqueta de negocio.
  - `fun origen(valor: String?): String` — traduce los 5 valores de `OrigenLead`.
  - `fun segmento(valor: String?): String` — traduce una lista `"urbano, turismo"` a `"Transporte urbano, Turismo"`.
  - `fun cartera(valor: String?): String` — traduce los 6 valores de `EstadoCartera`.

La Tarea 4 llama a estas ocho cosas. No cambies ni un nombre.

> **Sobre las etiquetas:** el destinatario es gerencia leyendo un Excel, no un desarrollador. `evaluacion_calidda` no se lee; "Evaluación Cálidda" sí. La traducción vive aquí y no en el SQL porque aquí es una función pura que se puede testear sin Postgres — y esa cobertura es la que sostiene el gate de `koverVerify`.

- [ ] **Paso 1: Escribe el test que falla**

Crea `src/test/kotlin/pe/quantum/crm/domain/reportes/EtiquetasExportTest.kt` con **exactamente** este contenido:

```kotlin
package pe.quantum.crm.domain.reportes

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * La regla del guion y la traduccion de enums a etiquetas de negocio (plan-15).
 *
 * Gerencia pidio explicitamente que ninguna celda quede vacia: lo que no tiene
 * dato lleva "-". Un vacio en Excel se confunde con "todavia no lo cargaron";
 * un guion dice "el CRM no guarda esto".
 */
class EtiquetasExportTest {
    // ── La regla del guion ─────────────────────────────────────

    @Test
    fun `un valor nulo se exporta como guion`() {
        assertThat(EtiquetasExport.texto(null)).isEqualTo("-")
    }

    @Test
    fun `una cadena vacia se exporta como guion`() {
        assertThat(EtiquetasExport.texto("")).isEqualTo("-")
    }

    @Test
    fun `una cadena de solo espacios se exporta como guion`() {
        assertThat(EtiquetasExport.texto("   ")).isEqualTo("-")
    }

    @Test
    fun `un valor presente se exporta tal cual`() {
        assertThat(EtiquetasExport.texto("Marova Tours S.A.C.")).isEqualTo("Marova Tours S.A.C.")
    }

    @Test
    fun `un numero se exporta como su representacion decimal`() {
        assertThat(EtiquetasExport.texto(12)).isEqualTo("12")
    }

    // ── Fechas ─────────────────────────────────────────────────

    @Test
    fun `una fecha con hora se exporta en formato legible`() {
        val momento = LocalDateTime.of(2026, 9, 8, 14, 30)

        assertThat(EtiquetasExport.fecha(momento)).isEqualTo("2026-09-08 14:30")
    }

    @Test
    fun `una fecha con hora nula se exporta como guion`() {
        assertThat(EtiquetasExport.fecha(null)).isEqualTo("-")
    }

    @Test
    fun `un dia de calendario se exporta sin hora`() {
        assertThat(EtiquetasExport.dia(LocalDate.of(2026, 12, 31))).isEqualTo("2026-12-31")
    }

    @Test
    fun `un dia de calendario nulo se exporta como guion`() {
        assertThat(EtiquetasExport.dia(null)).isEqualTo("-")
    }

    // ── Estado de la oportunidad: los 4 valores del enum, ni uno mas ──

    @Test
    fun `los cuatro estados del pipeline tienen etiqueta de negocio`() {
        assertThat(EtiquetasExport.estado("evaluacion_calidda")).isEqualTo("Evaluación Cálidda")
        assertThat(EtiquetasExport.estado("documentos_legales")).isEqualTo("Documentos legales")
        assertThat(EtiquetasExport.estado("facturado")).isEqualTo("Facturado")
        assertThat(EtiquetasExport.estado("cerrado")).isEqualTo("Cerrado")
    }

    @Test
    fun `un estado desconocido se exporta crudo en vez de romper el archivo`() {
        // Si algun dia el enum gana un valor, el export sigue generandose:
        // una celda con el valor sin traducir es infinitamente mejor que un 500.
        assertThat(EtiquetasExport.estado("estado_nuevo")).isEqualTo("estado_nuevo")
    }

    @Test
    fun `un estado nulo se exporta como guion`() {
        assertThat(EtiquetasExport.estado(null)).isEqualTo("-")
    }

    // ── Origen del lead ────────────────────────────────────────

    @Test
    fun `los cinco origenes de lead tienen etiqueta de negocio`() {
        assertThat(EtiquetasExport.origen("cartera")).isEqualTo("Cartera propia")
        assertThat(EtiquetasExport.origen("visita_fria")).isEqualTo("Prospección en calle")
        assertThat(EtiquetasExport.origen("referido_calidda")).isEqualTo("Referido Cálidda")
        assertThat(EtiquetasExport.origen("red_contactos")).isEqualTo("Referido / red de contactos")
        assertThat(EtiquetasExport.origen("otro")).isEqualTo("Otro")
    }

    @Test
    fun `un origen nulo se exporta como guion`() {
        assertThat(EtiquetasExport.origen(null)).isEqualTo("-")
    }

    // ── Segmentos: multivalor ──────────────────────────────────

    @Test
    fun `un segmento unico se traduce a su etiqueta`() {
        assertThat(EtiquetasExport.segmento("urbano")).isEqualTo("Transporte urbano")
    }

    @Test
    fun `varios segmentos se traducen uno a uno conservando el separador`() {
        assertThat(EtiquetasExport.segmento("urbano, turismo")).isEqualTo("Transporte urbano, Turismo")
    }

    @Test
    fun `una empresa sin segmentos se exporta como guion`() {
        assertThat(EtiquetasExport.segmento(null)).isEqualTo("-")
    }

    // ── Estado de cartera ──────────────────────────────────────

    @Test
    fun `los seis estados de cartera tienen etiqueta de negocio`() {
        assertThat(EtiquetasExport.cartera("no_contactado")).isEqualTo("No contactado")
        assertThat(EtiquetasExport.cartera("no_aplica")).isEqualTo("No aplica")
        assertThat(EtiquetasExport.cartera("no_interesado")).isEqualTo("No interesado")
        assertThat(EtiquetasExport.cartera("prospeccion")).isEqualTo("Prospección")
        assertThat(EtiquetasExport.cartera("oportunidad_activa")).isEqualTo("Oportunidad activa")
        assertThat(EtiquetasExport.cartera("cliente")).isEqualTo("Cliente")
    }

    @Test
    fun `un estado de cartera nulo se exporta como guion`() {
        assertThat(EtiquetasExport.cartera(null)).isEqualTo("-")
    }
}
```

- [ ] **Paso 2: Ejecuta el test y comprueba que falla**

Run: `./gradlew test --tests "pe.quantum.crm.domain.reportes.EtiquetasExportTest"`

Expected: **FALLA**, con un error de compilación tipo `Unresolved reference: EtiquetasExport`. Eso es lo correcto: el objeto todavía no existe. Si por lo que sea pasara en verde, algo está mal — detente y reporta.

- [ ] **Paso 3: Escribe la implementación mínima**

Crea `src/main/kotlin/pe/quantum/crm/domain/reportes/EtiquetasExport.kt` con **exactamente** este contenido:

```kotlin
package pe.quantum.crm.domain.reportes

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Traduccion del modelo al idioma del Excel que lee gerencia (plan-15).
 *
 * Dos responsabilidades, las dos puras y sin base de datos:
 *  1. La REGLA DEL GUION: ninguna celda queda vacia. Lo que no tiene dato lleva
 *     "-". Un vacio se lee como "falta cargarlo"; el guion dice "el CRM no
 *     guarda esto". Es un requisito explicito del solicitante.
 *  2. Etiquetas de negocio: `evaluacion_calidda` no se lee en una reunion;
 *     "Evaluacion Calidda" si.
 *
 * Un valor de enum desconocido NUNCA rompe el archivo: se emite crudo. Preferir
 * una celda fea a un export que no se genera.
 */
object EtiquetasExport {
    const val SIN_DATO = "-"

    private val FORMATO_FECHA_HORA = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
    private val FORMATO_DIA = DateTimeFormatter.ofPattern("yyyy-MM-dd")

    private val ESTADOS =
        mapOf(
            "evaluacion_calidda" to "Evaluación Cálidda",
            "documentos_legales" to "Documentos legales",
            "facturado" to "Facturado",
            "cerrado" to "Cerrado",
        )

    private val ORIGENES =
        mapOf(
            "cartera" to "Cartera propia",
            "visita_fria" to "Prospección en calle",
            "referido_calidda" to "Referido Cálidda",
            "red_contactos" to "Referido / red de contactos",
            "otro" to "Otro",
        )

    private val SEGMENTOS =
        mapOf(
            "urbano" to "Transporte urbano",
            "personal" to "Transporte de personal",
            "turismo" to "Turismo",
            "interprovincial" to "Interprovincial",
            "otro" to "Otro",
        )

    private val CARTERAS =
        mapOf(
            "no_contactado" to "No contactado",
            "no_aplica" to "No aplica",
            "no_interesado" to "No interesado",
            "prospeccion" to "Prospección",
            "oportunidad_activa" to "Oportunidad activa",
            "cliente" to "Cliente",
        )

    /** La regla del guion. Todo lo que va al Excel pasa por aqui. */
    fun texto(valor: Any?): String {
        val crudo = valor?.toString()
        return if (crudo.isNullOrBlank()) SIN_DATO else crudo
    }

    fun fecha(valor: LocalDateTime?): String = valor?.format(FORMATO_FECHA_HORA) ?: SIN_DATO

    /**
     * Un dia de calendario se exporta SIN hora. Darle hora a un DATE lo desplaza
     * de dia segun la zona (contrato_api.md §29, la trampa de las dos fechas).
     */
    fun dia(valor: LocalDate?): String = valor?.format(FORMATO_DIA) ?: SIN_DATO

    fun estado(valor: String?): String = traducir(valor, ESTADOS)

    fun origen(valor: String?): String = traducir(valor, ORIGENES)

    fun cartera(valor: String?): String = traducir(valor, CARTERAS)

    /** Multivalor: `empresa_segmentos` guarda varios por empresa, ya concatenados por el SQL. */
    fun segmento(valor: String?): String {
        if (valor.isNullOrBlank()) return SIN_DATO
        return valor.split(",").joinToString(", ") { parte ->
            val clave = parte.trim()
            SEGMENTOS[clave] ?: clave
        }
    }

    private fun traducir(
        valor: String?,
        etiquetas: Map<String, String>,
    ): String {
        if (valor.isNullOrBlank()) return SIN_DATO
        return etiquetas[valor] ?: valor
    }
}
```

- [ ] **Paso 4: Ejecuta el test y comprueba que pasa**

Run: `./gradlew test --tests "pe.quantum.crm.domain.reportes.EtiquetasExportTest"`

Expected: `BUILD SUCCESSFUL`, 20 tests en verde.

- [ ] **Paso 5: Verifica formato y análisis estático**

Run: `./gradlew ktlintCheck detekt`

Expected: `BUILD SUCCESSFUL`. Si `ktlintCheck` falla, corre `./gradlew ktlintFormat` y repite.

- [ ] **Paso 6: Commit**

```bash
git add src/main/kotlin/pe/quantum/crm/domain/reportes/EtiquetasExport.kt src/test/kotlin/pe/quantum/crm/domain/reportes/EtiquetasExportTest.kt
git commit -m "$(cat <<'EOF'
feat(reportes): etiquetas de negocio y regla del guion para el export

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
EOF
)"
```

---

## Tarea 4: El constructor del libro Excel

**Files:**
- Create: `src/main/kotlin/pe/quantum/crm/domain/reportes/LibroExportComercial.kt`
- Test: `src/test/kotlin/pe/quantum/crm/domain/reportes/LibroExportComercialTest.kt`

**Interfaces:**
- Consumes: `FilaExportComercial` (Tarea 2), `EtiquetasExport` (Tarea 3).
- Produces: `object LibroExportComercial` con
  - `const val NOMBRE_HOJA: String` = `"Gestión comercial"`
  - `val CABECERAS: List<String>` — las 33 cabeceras, en orden.
  - `fun construir(filas: List<FilaExportComercial>): ByteArray`

La Tarea 6 llama a `construir`.

> **Por qué esta pieza es un `object` puro y no vive dentro del servicio:** es la única parte de la generación del Excel que se puede testear sin Postgres. Si la mezclaras con el SQL, quedaría sin cobertura y arrastraría el gate de `koverVerify` hacia abajo — el mismo problema que `build.gradle.kts` documenta en extenso para `ReporteService`. Mantenla separada.

- [ ] **Paso 1: Escribe el test que falla**

Crea `src/test/kotlin/pe/quantum/crm/domain/reportes/LibroExportComercialTest.kt` con **exactamente** este contenido:

```kotlin
package pe.quantum.crm.domain.reportes

import org.apache.poi.xssf.usermodel.XSSFWorkbook
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import pe.quantum.crm.domain.reportes.dto.FilaExportComercial
import java.io.ByteArrayInputStream
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * El libro .xlsx del export comercial (plan-15). Se construye y se vuelve a
 * abrir con POI: un test que solo comprobara que el ByteArray no esta vacio no
 * probaria que el archivo es un Excel valido.
 */
class LibroExportComercialTest {
    private fun filaCompleta() =
        FilaExportComercial(
            ruc = "20123456789",
            razonSocial = "Marova Tours S.A.C.",
            empresaCreadaEn = LocalDateTime.of(2026, 1, 15, 9, 0),
            responsableEmpresa = "Aldo Martínez",
            origenLead = "cartera",
            segmentos = "urbano, turismo",
            estadoCartera = "oportunidad_activa",
            idOportunidad = 106,
            vendedorOportunidad = "Aldo Martínez",
            estadoOportunidad = "evaluacion_calidda",
            modelosYUnidades = "KinWin K12 x3",
            unidadesTotales = 3,
            montoTotal = BigDecimal("265440.00"),
            financiadora = "Calidda – Fraccionamiento GNV",
            oportunidadCreadaEn = LocalDateTime.of(2026, 2, 1, 10, 0),
            fechaCierreEstimado = LocalDate.of(2026, 12, 31),
            facturadoEn = null,
            motivoCierre = null,
            notasOportunidad = "Cliente pidió cotización de 3 unidades",
            siguienteAccion = "llamada",
            principalBloqueo = null,
            primerContacto = LocalDateTime.of(2026, 2, 2, 8, 0),
            ultimaGestion = LocalDateTime.of(2026, 3, 1, 16, 0),
            diasSinGestion = 12,
            actividadTipo = "tarea",
            actividadTitulo = "llamada",
            actividadTipoAccion = "llamada",
            actividadEstado = "completada",
            actividadFecha = LocalDateTime.of(2026, 3, 1, 16, 0),
            actividadResponsable = "Aldo Martínez",
            actividadDescripcion = "Seguimiento de cotización",
            actividadComentarios = "No contestó | Reagendado",
            actividadRegistradaEn = LocalDateTime.of(2026, 3, 1, 15, 0),
        )

    private fun filaVacia() =
        FilaExportComercial(
            ruc = null, razonSocial = null, empresaCreadaEn = null, responsableEmpresa = null,
            origenLead = null, segmentos = null, estadoCartera = null,
            idOportunidad = null, vendedorOportunidad = null, estadoOportunidad = null,
            modelosYUnidades = null, unidadesTotales = null, montoTotal = null, financiadora = null,
            oportunidadCreadaEn = null, fechaCierreEstimado = null, facturadoEn = null,
            motivoCierre = null, notasOportunidad = null, siguienteAccion = null,
            principalBloqueo = null, primerContacto = null, ultimaGestion = null, diasSinGestion = null,
            actividadTipo = null, actividadTitulo = null, actividadTipoAccion = null,
            actividadEstado = null, actividadFecha = null, actividadResponsable = null,
            actividadDescripcion = null, actividadComentarios = null, actividadRegistradaEn = null,
        )

    /** Abre el ByteArray como libro real y devuelve el texto de una celda. */
    private fun celda(
        bytes: ByteArray,
        indiceFila: Int,
        indiceColumna: Int,
    ): String =
        XSSFWorkbook(ByteArrayInputStream(bytes)).use { libro ->
            libro.getSheetAt(0).getRow(indiceFila).getCell(indiceColumna).stringCellValue
        }

    @Test
    fun `el archivo generado es un xlsx valido con una sola hoja`() {
        val bytes = LibroExportComercial.construir(listOf(filaCompleta()))

        XSSFWorkbook(ByteArrayInputStream(bytes)).use { libro ->
            assertThat(libro.numberOfSheets).isEqualTo(1)
            assertThat(libro.getSheetAt(0).sheetName).isEqualTo("Gestión comercial")
        }
    }

    @Test
    fun `la primera fila son las cabeceras en el orden declarado`() {
        val bytes = LibroExportComercial.construir(listOf(filaCompleta()))

        XSSFWorkbook(ByteArrayInputStream(bytes)).use { libro ->
            val cabecera = libro.getSheetAt(0).getRow(0)
            assertThat(cabecera.lastCellNum.toInt()).isEqualTo(LibroExportComercial.CABECERAS.size)
            LibroExportComercial.CABECERAS.forEachIndexed { indice, esperada ->
                assertThat(cabecera.getCell(indice).stringCellValue).isEqualTo(esperada)
            }
        }
    }

    @Test
    fun `cada fila de datos ocupa una fila del Excel debajo de la cabecera`() {
        val bytes = LibroExportComercial.construir(listOf(filaCompleta(), filaVacia()))

        XSSFWorkbook(ByteArrayInputStream(bytes)).use { libro ->
            // Fila 0 = cabecera; las dos de datos van en 1 y 2.
            assertThat(libro.getSheetAt(0).lastRowNum).isEqualTo(2)
        }
    }

    @Test
    fun `los valores presentes se escriben con su etiqueta de negocio`() {
        val bytes = LibroExportComercial.construir(listOf(filaCompleta()))

        assertThat(celda(bytes, 1, 0)).isEqualTo("20123456789")
        assertThat(celda(bytes, 1, 1)).isEqualTo("Marova Tours S.A.C.")
        assertThat(celda(bytes, 1, 4)).isEqualTo("Cartera propia")
        assertThat(celda(bytes, 1, 5)).isEqualTo("Transporte urbano, Turismo")
        assertThat(celda(bytes, 1, 6)).isEqualTo("Oportunidad activa")
        assertThat(celda(bytes, 1, 9)).isEqualTo("Evaluación Cálidda")
    }

    @Test
    fun `una fila enteramente vacia se exporta con guion en todas las celdas`() {
        val bytes = LibroExportComercial.construir(listOf(filaVacia()))

        XSSFWorkbook(ByteArrayInputStream(bytes)).use { libro ->
            val fila = libro.getSheetAt(0).getRow(1)
            LibroExportComercial.CABECERAS.indices.forEach { columna ->
                assertThat(fila.getCell(columna).stringCellValue)
                    .withFailMessage("La columna %d debia llevar guion", columna)
                    .isEqualTo("-")
            }
        }
    }

    @Test
    fun `el principal bloqueo siempre sale como guion porque el CRM no lo guarda`() {
        // R8 del ticket: no se inventa contenido para una columna sin campo real.
        val bytes = LibroExportComercial.construir(listOf(filaCompleta()))
        val columna = LibroExportComercial.CABECERAS.indexOf("Principal bloqueo")

        assertThat(celda(bytes, 1, columna)).isEqualTo("-")
    }

    @Test
    fun `sin filas el archivo se genera igual y solo lleva la cabecera`() {
        // Un rango de fechas sin datos NO es un error: es un Excel con cabecera y nada mas.
        val bytes = LibroExportComercial.construir(emptyList())

        XSSFWorkbook(ByteArrayInputStream(bytes)).use { libro ->
            assertThat(libro.getSheetAt(0).lastRowNum).isEqualTo(0)
            assertThat(libro.getSheetAt(0).getRow(0).getCell(0).stringCellValue).isEqualTo("RUC")
        }
    }

    @Test
    fun `un texto mas largo que el limite de Excel se recorta en vez de romper el archivo`() {
        val larguisimo = "x".repeat(40_000)
        val fila = filaCompleta().copy(actividadComentarios = larguisimo)
        val columna = LibroExportComercial.CABECERAS.indexOf("Comentarios de seguimiento")

        val bytes = LibroExportComercial.construir(listOf(fila))

        // Excel no admite mas de 32767 caracteres por celda: POI lanzaria si no se recorta.
        assertThat(celda(bytes, 1, columna)).hasSize(32_767)
    }
}
```

- [ ] **Paso 2: Ejecuta el test y comprueba que falla**

Run: `./gradlew test --tests "pe.quantum.crm.domain.reportes.LibroExportComercialTest"`

Expected: **FALLA** con `Unresolved reference: LibroExportComercial`.

- [ ] **Paso 3: Escribe la implementación**

Crea `src/main/kotlin/pe/quantum/crm/domain/reportes/LibroExportComercial.kt` con **exactamente** este contenido:

```kotlin
package pe.quantum.crm.domain.reportes

import org.apache.poi.ss.usermodel.Sheet
import org.apache.poi.ss.util.CellRangeAddress
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import pe.quantum.crm.domain.reportes.dto.FilaExportComercial
import java.io.ByteArrayOutputStream

/**
 * Construye el .xlsx del export comercial (plan-15). Funcion pura: filas -> bytes.
 *
 * UNA SOLA HOJA por decision del solicitante. La forma es una tabla plana de
 * hechos (una fila por actividad, repitiendo prospecto y oportunidad) porque es
 * la unica que Excel sabe filtrar y pivotar sin ayuda.
 *
 * TODAS las celdas se escriben como TEXTO, incluidas fechas y montos. Es
 * deliberado: el destino es lectura y filtrado, no aritmetica sobre el archivo,
 * y el texto evita que Excel reinterprete una fecha segun la configuracion
 * regional de quien lo abre — el fallo clasico de un export dd/mm vs mm/dd.
 */
object LibroExportComercial {
    const val NOMBRE_HOJA = "Gestión comercial"

    /** Tope duro del formato XLSX. POI lanza si una celda lo supera. */
    private const val MAX_CARACTERES_CELDA = 32_767

    private const val ANCHO_COLUMNA = 4_500

    val CABECERAS: List<String> =
        listOf(
            // Bloque 1: prospecto / empresa
            "RUC",
            "Razón social",
            "Fecha de ingreso",
            "Responsable del prospecto",
            "Origen del prospecto",
            "Segmento",
            "Estado de cartera",
            // Bloque 2: oportunidad
            "ID oportunidad",
            "Vendedor de la oportunidad",
            "Etapa / Estado actual",
            "Modelos y unidades",
            "Unidades totales",
            "Monto total",
            "Financiadora",
            "Fecha de creación de la oportunidad",
            "Fecha estimada de cierre",
            "Fecha de facturación",
            "Motivo de no cierre",
            "Notas de la oportunidad",
            "Siguiente acción",
            "Principal bloqueo",
            "Fecha de primer contacto",
            "Fecha de última gestión",
            "Días sin gestión",
            // Bloque 3: actividad
            "Tipo de actividad",
            "Actividad",
            "Tipo de acción",
            "Estado de la actividad",
            "Fecha de la actividad",
            "Responsable de la actividad",
            "Descripción",
            "Comentarios de seguimiento",
            "Fecha de registro",
        )

    fun construir(filas: List<FilaExportComercial>): ByteArray {
        XSSFWorkbook().use { libro ->
            val hoja = libro.createSheet(NOMBRE_HOJA)
            escribirCabecera(libro, hoja)
            filas.forEachIndexed { indice, fila ->
                escribirFila(hoja, indice + 1, valoresDe(fila))
            }
            anchoDeColumnas(hoja)
            // El autofiltro y el panel congelado son lo que convierte la hoja en
            // algo usable: gerencia filtra por vendedor o por etapa sin prepararla.
            hoja.createFreezePane(0, 1)
            // Sin filas el rango queda 0..0: solo la cabecera, que es valido.
            hoja.setAutoFilter(CellRangeAddress(0, filas.size, 0, CABECERAS.size - 1))
            return ByteArrayOutputStream().use { salida ->
                libro.write(salida)
                salida.toByteArray()
            }
        }
    }

    /**
     * El orden de esta lista ES el orden de [CABECERAS]. Si tocas una, toca la
     * otra: el test `la primera fila son las cabeceras en el orden declarado`
     * comprueba el tamano, pero no puede comprobar que cada valor este bajo su
     * titulo.
     */
    @Suppress("LongMethod") // 33 columnas en una lista plana; partirla la haria ilegible.
    private fun valoresDe(fila: FilaExportComercial): List<String> =
        listOf(
            EtiquetasExport.texto(fila.ruc),
            EtiquetasExport.texto(fila.razonSocial),
            EtiquetasExport.fecha(fila.empresaCreadaEn),
            EtiquetasExport.texto(fila.responsableEmpresa),
            EtiquetasExport.origen(fila.origenLead),
            EtiquetasExport.segmento(fila.segmentos),
            EtiquetasExport.cartera(fila.estadoCartera),
            EtiquetasExport.texto(fila.idOportunidad),
            EtiquetasExport.texto(fila.vendedorOportunidad),
            EtiquetasExport.estado(fila.estadoOportunidad),
            EtiquetasExport.texto(fila.modelosYUnidades),
            EtiquetasExport.texto(fila.unidadesTotales),
            EtiquetasExport.texto(fila.montoTotal?.toPlainString()),
            EtiquetasExport.texto(fila.financiadora),
            EtiquetasExport.fecha(fila.oportunidadCreadaEn),
            EtiquetasExport.dia(fila.fechaCierreEstimado),
            EtiquetasExport.fecha(fila.facturadoEn),
            EtiquetasExport.texto(fila.motivoCierre),
            EtiquetasExport.texto(fila.notasOportunidad),
            EtiquetasExport.texto(fila.siguienteAccion),
            // El CRM no guarda "principal bloqueo" en ningun campo. Se emite el
            // guion a proposito: inventar contenido aqui esta prohibido (R8).
            EtiquetasExport.texto(fila.principalBloqueo),
            EtiquetasExport.fecha(fila.primerContacto),
            EtiquetasExport.fecha(fila.ultimaGestion),
            EtiquetasExport.texto(fila.diasSinGestion),
            EtiquetasExport.texto(fila.actividadTipo),
            EtiquetasExport.texto(fila.actividadTitulo),
            EtiquetasExport.texto(fila.actividadTipoAccion),
            EtiquetasExport.texto(fila.actividadEstado),
            EtiquetasExport.fecha(fila.actividadFecha),
            EtiquetasExport.texto(fila.actividadResponsable),
            EtiquetasExport.texto(fila.actividadDescripcion),
            EtiquetasExport.texto(fila.actividadComentarios),
            EtiquetasExport.fecha(fila.actividadRegistradaEn),
        )

    private fun escribirCabecera(
        libro: XSSFWorkbook,
        hoja: Sheet,
    ) {
        val negrita =
            libro.createCellStyle().apply {
                setFont(libro.createFont().apply { bold = true })
            }
        val fila = hoja.createRow(0)
        CABECERAS.forEachIndexed { columna, titulo ->
            fila.createCell(columna).apply {
                setCellValue(titulo)
                cellStyle = negrita
            }
        }
    }

    private fun escribirFila(
        hoja: Sheet,
        indice: Int,
        valores: List<String>,
    ) {
        val fila = hoja.createRow(indice)
        valores.forEachIndexed { columna, valor ->
            fila.createCell(columna).setCellValue(valor.take(MAX_CARACTERES_CELDA))
        }
    }

    /**
     * Ancho fijo en vez de `autoSizeColumn`: el autoajuste recorre todas las
     * filas por columna y en un export de miles de filas domina el tiempo de
     * respuesta entero. Un ancho generoso y constante es suficiente.
     */
    private fun anchoDeColumnas(hoja: Sheet) {
        CABECERAS.indices.forEach { columna -> hoja.setColumnWidth(columna, ANCHO_COLUMNA) }
    }
}
```

- [ ] **Paso 4: Ejecuta el test y comprueba que pasa**

Run: `./gradlew test --tests "pe.quantum.crm.domain.reportes.LibroExportComercialTest"`

Expected: `BUILD SUCCESSFUL`, 8 tests en verde.

Si falla `el principal bloqueo siempre sale como guion`, comprueba que en `filaCompleta()` el campo `principalBloqueo` sea `null`. No cambies la implementación para "arreglarlo".

- [ ] **Paso 5: Verifica formato, análisis estático y la suite completa**

Run: `./gradlew ktlintCheck detekt test`

Expected: `BUILD SUCCESSFUL`.

- [ ] **Paso 6: Commit**

```bash
git add src/main/kotlin/pe/quantum/crm/domain/reportes/LibroExportComercial.kt src/test/kotlin/pe/quantum/crm/domain/reportes/LibroExportComercialTest.kt
git commit -m "$(cat <<'EOF'
feat(reportes): constructor del libro Excel del export comercial

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
EOF
)"
```

---

## Tarea 5: El servicio de consulta

**Files:**
- Create: `src/main/kotlin/pe/quantum/crm/domain/reportes/ExportComercialService.kt`
- Test: `src/test/kotlin/pe/quantum/crm/domain/reportes/ExportComercialServiceIntegrationTest.kt`

**Interfaces:**
- Consumes: `FilaExportComercial` (Tarea 2).
- Produces: `@Service class ExportComercialService(private val jdbc: NamedParameterJdbcTemplate)` con
  `@Transactional(readOnly = true) fun filas(desde: LocalDate?, hasta: LocalDate?): List<FilaExportComercial>`.

La Tarea 6 inyecta este servicio en `ReporteController`.

> **Aviso importante sobre el orden de esta tarea:** aquí el test que se escribe primero es de integración (`@Tag("integration")`) y **no se puede ejecutar en esta máquina** — Testcontainers está roto por Docker 29. Eso significa que no vas a ver el rojo. **Escríbelo igual y escríbelo primero**: es el precedente establecido del repo para el SQL nativo (mira la cabecera de `ReporteServiceSqlIntegrationTest.kt`, que documenta exactamente esta situación). Se valida en CI. **No borres el test porque no corre. No intentes arreglar Docker.**

- [ ] **Paso 1: Escribe el test de integración**

Crea `src/test/kotlin/pe/quantum/crm/domain/reportes/ExportComercialServiceIntegrationTest.kt` con **exactamente** este contenido:

```kotlin
package pe.quantum.crm.domain.reportes

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.annotation.Transactional
import pe.quantum.crm.support.IntegrationTestBase
import java.time.LocalDate

/**
 * El SQL del export comercial contra Postgres real (plan-15).
 *
 * Mockear el `NamedParameterJdbcTemplate` solo probaria el mock: para SQL con
 * CTE, UNION y subconsultas correlacionadas la unica prueba honesta es una base
 * de verdad. De ahi `@Tag("integration")`.
 *
 * NO se ejecutaron en la maquina que escribio este archivo: Testcontainers esta
 * roto por Docker 29 (ver la memoria testcontainers-docker29-blocker). Se validan
 * en CI con la tarea `integrationTest`.
 *
 * Los cuatro escenarios que sostienen el diseno de "ninguna fila se pierde":
 *   1. Un prospecto SIN oportunidad aparece igual.
 *   2. Una oportunidad SIN actividades aparece igual, con una sola fila.
 *   3. Una oportunidad con dos actividades produce DOS filas.
 *   4. El rango de fechas recorta por fecha de ingreso.
 */
@Tag("integration")
@SpringBootTest
@Transactional
class ExportComercialServiceIntegrationTest : IntegrationTestBase() {
    @Autowired
    lateinit var jdbcTemplate: JdbcTemplate

    @Autowired
    lateinit var exportService: ExportComercialService

    private fun id(sql: String): Long = jdbcTemplate.queryForObject(sql, Long::class.java)!!

    private fun crearVendedor(sufijo: String): Long =
        id(
            "INSERT INTO empleados (nombres, apellidos, email, rol) " +
                "VALUES ('Exp', 'Test$sufijo', 'exp.test$sufijo@quantum.pe', 'vendedor') RETURNING id",
        )

    private fun crearEmpresa(
        sufijo: String,
        idVendedor: Long,
        creadaEn: String,
    ): Long =
        id(
            """
            INSERT INTO empresas
                (ruc, razon_social, actividad_econ, id_vendedor, estado_sunat, condicion_sunat,
                 direccion_fiscal, origen_lead, estado_cartera, created_at, created_by, updated_by)
            VALUES
                ('215$sufijo', 'Exp Test $sufijo S.A.C.', 'Transporte', $idVendedor, 'ACTIVO', 'HABIDO',
                 'Av. Export $sufijo', 'cartera', 'prospeccion', TIMESTAMP '$creadaEn', $idVendedor, $idVendedor)
            RETURNING id
            """.trimIndent(),
        )

    /**
     * `id_financiadora` es NOT NULL desde V10 y sigue siendolo: se toma la
     * financiadora por defecto, que las migraciones de seed ya dejan creada.
     */
    private fun crearOportunidad(
        idEmpresa: Long,
        idVendedor: Long,
        creadaEn: String,
    ): Long =
        id(
            """
            INSERT INTO oportunidades
                (id_empresa, id_vendedor, id_financiadora, estado, created_at, created_by, updated_by)
            VALUES
                ($idEmpresa, $idVendedor, (SELECT id FROM financiadoras WHERE es_default = true LIMIT 1),
                 'evaluacion_calidda', TIMESTAMP '$creadaEn', $idVendedor, $idVendedor)
            RETURNING id
            """.trimIndent(),
        )

    private fun crearTarea(
        idEmpresa: Long,
        idOportunidad: Long?,
        idVendedor: Long,
    ): Long =
        id(
            """
            INSERT INTO tareas
                (id_empresa, id_oportunidad, id_asignado, tipo_accion, estado_accion, descripcion,
                 fecha_ejecucion, created_by, updated_by)
            VALUES
                ($idEmpresa, ${idOportunidad ?: "NULL"}, $idVendedor, 'llamada', 'pendiente', 'Seguimiento',
                 CURRENT_TIMESTAMP, $idVendedor, $idVendedor)
            RETURNING id
            """.trimIndent(),
        )

    @Test
    fun `un prospecto sin oportunidad aparece en el export`() {
        val vendedor = crearVendedor("P1")
        val empresa = crearEmpresa("P1", vendedor, "2026-03-01 10:00:00")

        val filas = exportService.filas(null, null)

        val delProspecto = filas.filter { it.razonSocial == "Exp Test P1 S.A.C." }
        assertThat(delProspecto).hasSize(1)
        assertThat(delProspecto.first().idOportunidad).isNull()
        assertThat(delProspecto.first().ruc).isEqualTo("215P1")
        assertThat(empresa).isPositive()
    }

    @Test
    fun `una oportunidad sin actividades produce exactamente una fila`() {
        val vendedor = crearVendedor("O1")
        val empresa = crearEmpresa("O1", vendedor, "2026-03-01 10:00:00")
        val oportunidad = crearOportunidad(empresa, vendedor, "2026-03-05 10:00:00")

        val filas = exportService.filas(null, null)

        val deLaOportunidad = filas.filter { it.idOportunidad == oportunidad }
        assertThat(deLaOportunidad).hasSize(1)
        assertThat(deLaOportunidad.first().actividadTipo).isNull()
        assertThat(deLaOportunidad.first().estadoOportunidad).isEqualTo("evaluacion_calidda")
    }

    @Test
    fun `una oportunidad con dos actividades produce dos filas`() {
        val vendedor = crearVendedor("O2")
        val empresa = crearEmpresa("O2", vendedor, "2026-03-01 10:00:00")
        val oportunidad = crearOportunidad(empresa, vendedor, "2026-03-05 10:00:00")
        crearTarea(empresa, oportunidad, vendedor)
        crearTarea(empresa, oportunidad, vendedor)

        val filas = exportService.filas(null, null)

        val deLaOportunidad = filas.filter { it.idOportunidad == oportunidad }
        assertThat(deLaOportunidad).hasSize(2)
        assertThat(deLaOportunidad).allMatch { it.actividadTipo == "tarea" }
    }

    @Test
    fun `una actividad de prospeccion no se pierde aunque la empresa ya tenga oportunidades`() {
        // Es la trampa que el diseno del universo de filas existe para evitar:
        // sin la rama de prospeccion, esta tarea desapareceria en silencio.
        val vendedor = crearVendedor("O3")
        val empresa = crearEmpresa("O3", vendedor, "2026-03-01 10:00:00")
        crearOportunidad(empresa, vendedor, "2026-03-05 10:00:00")
        crearTarea(empresa, null, vendedor)

        val filas = exportService.filas(null, null)

        val deProspeccion =
            filas.filter { it.razonSocial == "Exp Test O3 S.A.C." && it.idOportunidad == null }
        assertThat(deProspeccion).hasSize(1)
        assertThat(deProspeccion.first().actividadTipo).isEqualTo("tarea")
    }

    @Test
    fun `el rango de fechas recorta por fecha de ingreso`() {
        val vendedor = crearVendedor("F1")
        crearEmpresa("F1", vendedor, "2026-01-10 10:00:00")
        crearEmpresa("F2", vendedor, "2026-06-10 10:00:00")

        val filas = exportService.filas(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 31))

        val razones = filas.mapNotNull { it.razonSocial }
        assertThat(razones).contains("Exp Test F1 S.A.C.")
        assertThat(razones).doesNotContain("Exp Test F2 S.A.C.")
    }

    @Test
    fun `sin rango de fechas se devuelve todo el historico`() {
        val vendedor = crearVendedor("F3")
        crearEmpresa("F3", vendedor, "2019-01-10 10:00:00")

        val filas = exportService.filas(null, null)

        assertThat(filas.mapNotNull { it.razonSocial }).contains("Exp Test F3 S.A.C.")
    }
}
```

- [ ] **Paso 2: Comprueba que el test compila (no que pasa)**

Run: `./gradlew compileTestKotlin`

Expected: **FALLA** con `Unresolved reference: ExportComercialService`. Es lo correcto: el servicio aún no existe.

- [ ] **Paso 3: Escribe la implementación**

Crea `src/main/kotlin/pe/quantum/crm/domain/reportes/ExportComercialService.kt` con **exactamente** este contenido.

> **AVISO AL EJECUTOR: el SQL de abajo está terminado y verificado contra las migraciones V1–V49. Cópialo carácter por carácter. No lo "simplifiques", no reordenes los `JOIN`, no conviertas los `LEFT JOIN` en `JOIN`, no quites el `OR EXISTS` del universo de prospección. Cada pieza está ahí por una razón que la sección "Diseño de datos" explica.**

```kotlin
package pe.quantum.crm.domain.reportes

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pe.quantum.crm.domain.reportes.dto.FilaExportComercial
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * Consulta plana de la gestion comercial para el export en Excel (plan-15).
 *
 * Solo lectura, SQL nativo parametrizado, mismo patron que `ReporteService`.
 * No importa NI UNA clase de otro modulo de dominio: consultar `empresas`,
 * `tareas` o `eventos` por SQL no cruza la frontera que vigila ArchUnit
 * (CLAUDE.md regla 12) — lo que la cruzaria es importar sus entidades.
 *
 * LA FORMA DEL RESULTADO: una fila por ACTIVIDAD, repitiendo prospecto y
 * oportunidad. El universo de filas base se arma con un UNION de dos ramas para
 * que no se pierda ningun registro:
 *   · una fila por oportunidad;
 *   · una fila "de prospeccion" (sin oportunidad) por empresa que no tiene
 *     ninguna oportunidad O que tiene actividades colgadas de la empresa.
 * Sin esa segunda condicion, las tareas y eventos de prospeccion de una empresa
 * que YA tiene oportunidades desaparecerian en silencio.
 *
 * La formula de dinero esta duplicada aqui a proposito, igual que en
 * `ReporteService`: la FUENTE DE VERDAD es `MontoTotal.calcular`
 * (domain.oportunidades), y este modulo es SQL nativo y no puede cruzar de
 * modulo. Si cambia alla, cambia aqui.
 */
@Service
class ExportComercialService(
    private val jdbc: NamedParameterJdbcTemplate,
) {
    /**
     * @param desde inclusive; null = sin limite inferior.
     * @param hasta inclusive (se convierte a limite exclusivo sumando un dia);
     *   null = sin limite superior.
     */
    @Transactional(readOnly = true)
    fun filas(
        desde: LocalDate?,
        hasta: LocalDate?,
    ): List<FilaExportComercial> {
        val parametros = MapSqlParameterSource()
        val sql =
            buildString {
                append(CONSULTA_BASE)
                // Fragmentos FIJOS, sin datos del usuario dentro: los valores
                // viajan como parametros nombrados (CLAUDE.md regla 11).
                if (desde != null) {
                    append(" AND COALESCE(o.created_at, emp.created_at) >= :desde")
                    parametros.addValue("desde", desde.atStartOfDay())
                }
                if (hasta != null) {
                    append(" AND COALESCE(o.created_at, emp.created_at) < :hasta")
                    parametros.addValue("hasta", hasta.plusDays(1).atStartOfDay())
                }
                append(ORDEN)
            }
        return jdbc.query(sql, parametros) { rs, _ -> aFila(rs) }
    }

    private fun aFila(rs: ResultSet): FilaExportComercial =
        FilaExportComercial(
            ruc = rs.getString("ruc"),
            razonSocial = rs.getString("razon_social"),
            empresaCreadaEn = rs.momento("empresa_creada_en"),
            responsableEmpresa = rs.getString("responsable_empresa"),
            origenLead = rs.getString("origen_lead"),
            segmentos = rs.getString("segmentos"),
            estadoCartera = rs.getString("estado_cartera"),
            idOportunidad = rs.getObject("id_oportunidad") as? Long,
            vendedorOportunidad = rs.getString("vendedor_oportunidad"),
            estadoOportunidad = rs.getString("estado_oportunidad"),
            modelosYUnidades = rs.getString("modelos_y_unidades"),
            unidadesTotales = rs.getObject("unidades_totales")?.let { rs.getInt("unidades_totales") },
            montoTotal = rs.getBigDecimal("monto_total"),
            financiadora = rs.getString("financiadora"),
            oportunidadCreadaEn = rs.momento("oportunidad_creada_en"),
            fechaCierreEstimado = rs.getDate("fecha_cierre_estimado")?.toLocalDate(),
            facturadoEn = rs.momento("facturado_en"),
            motivoCierre = rs.getString("motivo_cierre"),
            notasOportunidad = rs.getString("notas_oportunidad"),
            siguienteAccion = rs.getString("siguiente_accion"),
            // El CRM no guarda "principal bloqueo" en ninguna columna. Siempre null,
            // que el Excel convierte en "-". Rellenarlo con notas o motivo_cierre
            // seria inventar contenido: el ticket lo prohibe (R8).
            principalBloqueo = null,
            primerContacto = rs.momento("primer_contacto"),
            ultimaGestion = rs.momento("ultima_gestion"),
            diasSinGestion = rs.getObject("dias_sin_gestion")?.let { rs.getInt("dias_sin_gestion") },
            actividadTipo = rs.getString("actividad_tipo"),
            actividadTitulo = rs.getString("actividad_titulo"),
            actividadTipoAccion = rs.getString("actividad_tipo_accion"),
            actividadEstado = rs.getString("actividad_estado"),
            actividadFecha = rs.momento("actividad_fecha"),
            actividadResponsable = rs.getString("actividad_responsable"),
            actividadDescripcion = rs.getString("actividad_descripcion"),
            actividadComentarios = rs.getString("actividad_comentarios"),
            actividadRegistradaEn = rs.momento("actividad_registrada_en"),
        )

    /** `getTimestamp` devuelve null sin lanzar; el `?.` evita el NPE del unboxing. */
    private fun ResultSet.momento(columna: String): LocalDateTime? =
        (getObject(columna) as? Timestamp)?.toLocalDateTime()

    private companion object {
        val CONSULTA_BASE =
            """
            WITH actividades AS (
                SELECT t.id_empresa            AS act_id_empresa,
                       t.id_oportunidad        AS act_id_oportunidad,
                       'tarea'                 AS act_tipo,
                       t.id                    AS act_id,
                       t.tipo_accion::text     AS act_titulo,
                       t.tipo_accion::text     AS act_tipo_accion,
                       t.descripcion           AS act_descripcion,
                       t.estado_accion::text   AS act_estado,
                       t.fecha_ejecucion       AS act_fecha,
                       t.id_asignado           AS act_id_responsable,
                       t.created_at            AS act_created_at
                FROM tareas t
                UNION ALL
                SELECT ev.id_empresa,
                       ev.id_oportunidad,
                       'evento',
                       ev.id,
                       COALESCE(ce.nombre, ev.nombre_personalizado),
                       NULL,
                       ev.descripcion,
                       ev.estado::text,
                       COALESCE(ev.fecha_ocurrencia, ev.fecha_estimada::timestamp),
                       ev.created_by,
                       ev.created_at
                FROM eventos ev
                LEFT JOIN catalogo_eventos ce ON ce.id = ev.id_catalogo_evento
            ),
            universo AS (
                SELECT emp.id AS u_id_empresa, o.id AS u_id_oportunidad
                FROM empresas emp
                JOIN oportunidades o ON o.id_empresa = emp.id
                UNION ALL
                SELECT emp.id, NULL::bigint
                FROM empresas emp
                WHERE NOT EXISTS (SELECT 1 FROM oportunidades o2 WHERE o2.id_empresa = emp.id)
                   OR EXISTS (
                       SELECT 1 FROM actividades a2
                       WHERE a2.act_id_empresa = emp.id AND a2.act_id_oportunidad IS NULL
                   )
            )
            SELECT emp.ruc,
                   emp.razon_social,
                   emp.created_at                                   AS empresa_creada_en,
                   CONCAT(ev_emp.nombres, ' ', ev_emp.apellidos)    AS responsable_empresa,
                   emp.origen_lead::text                            AS origen_lead,
                   (SELECT string_agg(s.segmento::text, ', ' ORDER BY s.segmento::text)
                    FROM empresa_segmentos s WHERE s.id_empresa = emp.id) AS segmentos,
                   emp.estado_cartera::text                         AS estado_cartera,
                   o.id                                             AS id_oportunidad,
                   CONCAT(ev_op.nombres, ' ', ev_op.apellidos)      AS vendedor_oportunidad,
                   o.estado::text                                   AS estado_oportunidad,
                   (SELECT string_agg(m.codigo || ' x' || COALESCE(i.cantidad, 0)::text, ', ' ORDER BY m.codigo)
                    FROM oportunidad_items i
                    JOIN modelos m ON m.id = i.id_modelo
                    WHERE i.id_oportunidad = o.id)                  AS modelos_y_unidades,
                   (SELECT SUM(COALESCE(i.cantidad, 0))
                    FROM oportunidad_items i WHERE i.id_oportunidad = o.id) AS unidades_totales,
                   (SELECT SUM(ROUND(i.cantidad * i.precio_venta * (1 - COALESCE(i.descuento, 0) / 100), 2))
                    FROM oportunidad_items i WHERE i.id_oportunidad = o.id) AS monto_total,
                   fin.nombre                                       AS financiadora,
                   o.created_at                                     AS oportunidad_creada_en,
                   o.fecha_cierre_estimado,
                   o.facturado_en,
                   o.motivo_cierre,
                   o.notas                                          AS notas_oportunidad,
                   COALESCE(
                       (SELECT t.tipo_accion::text FROM tareas t
                        WHERE t.id_oportunidad = u.u_id_oportunidad AND t.estado_accion = 'pendiente'
                        ORDER BY t.fecha_ejecucion NULLS LAST, t.id LIMIT 1),
                       (SELECT t.tipo_accion::text FROM tareas t
                        WHERE u.u_id_oportunidad IS NULL AND t.id_empresa = u.u_id_empresa
                          AND t.id_oportunidad IS NULL AND t.estado_accion = 'pendiente'
                        ORDER BY t.fecha_ejecucion NULLS LAST, t.id LIMIT 1)
                   )                                                AS siguiente_accion,
                   (SELECT MIN(a2.act_created_at) FROM actividades a2
                    WHERE a2.act_id_oportunidad = u.u_id_oportunidad
                       OR (u.u_id_oportunidad IS NULL AND a2.act_id_oportunidad IS NULL
                           AND a2.act_id_empresa = u.u_id_empresa)) AS primer_contacto,
                   (SELECT MAX(a2.act_created_at) FROM actividades a2
                    WHERE a2.act_id_oportunidad = u.u_id_oportunidad
                       OR (u.u_id_oportunidad IS NULL AND a2.act_id_oportunidad IS NULL
                           AND a2.act_id_empresa = u.u_id_empresa)) AS ultima_gestion,
                   (SELECT EXTRACT(DAY FROM (NOW() - MAX(a2.act_created_at)))::int FROM actividades a2
                    WHERE a2.act_id_oportunidad = u.u_id_oportunidad
                       OR (u.u_id_oportunidad IS NULL AND a2.act_id_oportunidad IS NULL
                           AND a2.act_id_empresa = u.u_id_empresa)) AS dias_sin_gestion,
                   a.act_tipo                                       AS actividad_tipo,
                   a.act_titulo                                     AS actividad_titulo,
                   a.act_tipo_accion                                AS actividad_tipo_accion,
                   a.act_estado                                     AS actividad_estado,
                   a.act_fecha                                      AS actividad_fecha,
                   CONCAT(ev_act.nombres, ' ', ev_act.apellidos)    AS actividad_responsable,
                   a.act_descripcion                                AS actividad_descripcion,
                   (SELECT string_agg(c.texto, ' | ' ORDER BY c.created_at)
                    FROM actividad_comentarios c
                    WHERE (a.act_tipo = 'tarea'  AND c.id_tarea  = a.act_id)
                       OR (a.act_tipo = 'evento' AND c.id_evento = a.act_id)) AS actividad_comentarios,
                   a.act_created_at                                 AS actividad_registrada_en
            FROM universo u
            JOIN empresas emp ON emp.id = u.u_id_empresa
            LEFT JOIN oportunidades o ON o.id = u.u_id_oportunidad
            LEFT JOIN empleados ev_emp ON ev_emp.id = emp.id_vendedor
            LEFT JOIN empleados ev_op ON ev_op.id = o.id_vendedor
            LEFT JOIN financiadoras fin ON fin.id = o.id_financiadora
            LEFT JOIN actividades a
                   ON a.act_id_oportunidad = u.u_id_oportunidad
                   OR (u.u_id_oportunidad IS NULL AND a.act_id_oportunidad IS NULL
                       AND a.act_id_empresa = u.u_id_empresa)
            LEFT JOIN empleados ev_act ON ev_act.id = a.act_id_responsable
            WHERE 1 = 1
            """.trimIndent()

        // String plano y con espacio inicial a proposito: `trimIndent()` sobre un
        // """ que empieza con salto de linea BORRA ese salto, y el ORDER BY quedaria
        // pegado al ultimo parametro (`...:desdeORDER BY`), con SQL invalido.
        const val ORDEN = " ORDER BY emp.razon_social, o.id NULLS FIRST, a.act_created_at"
    }
}
```

- [ ] **Paso 4: Verifica que compila todo, producción y tests**

Run: `./gradlew compileKotlin compileTestKotlin`

Expected: `BUILD SUCCESSFUL`.

- [ ] **Paso 5: Verifica que la suite unitaria sigue verde**

Run: `./gradlew test`

Expected: `BUILD SUCCESSFUL`. Los tests de integración de esta tarea **no se ejecutan aquí** (están excluidos por tag) — es lo esperado, no un fallo.

- [ ] **Paso 6: Verifica formato y análisis estático**

Run: `./gradlew ktlintCheck detekt`

Expected: `BUILD SUCCESSFUL`. Si `detekt` se queja de `LongMethod` en `aFila`, añade `@Suppress("LongMethod")` sobre la función con el comentario `// 33 columnas: el mapeo es plano por naturaleza.` **No partas la función en dos para engañar a la métrica.**

- [ ] **Paso 7: Commit**

```bash
git add src/main/kotlin/pe/quantum/crm/domain/reportes/ExportComercialService.kt src/test/kotlin/pe/quantum/crm/domain/reportes/ExportComercialServiceIntegrationTest.kt
git commit -m "$(cat <<'EOF'
feat(reportes): consulta plana de gestion comercial para el export

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
EOF
)"
```

---

## Tarea 6: El endpoint HTTP

**Files:**
- Modify: `src/main/kotlin/pe/quantum/crm/domain/reportes/ReporteController.kt`
- Test: `src/test/kotlin/pe/quantum/crm/domain/reportes/ExportComercialControllerWebMvcTest.kt`

**Interfaces:**
- Consumes: `ExportComercialService.filas(...)` (Tarea 5), `LibroExportComercial.construir(...)` (Tarea 4).
- Produces: `GET /api/v1/reportes/exportar-comercial` → `ResponseEntity<ByteArray>` con `Content-Type: application/vnd.openxmlformats-officedocument.spreadsheetml.sheet` y `Content-Disposition: attachment; filename="gestion-comercial-<fecha>.xlsx"`.

> **Nota sobre el envelope:** todos los demás endpoints devuelven `ApiResponse<T>` (`{data, meta, error}`). Éste **no puede**: devuelve un archivo binario, y envolverlo en JSON lo haría indescargable. Es la excepción correcta y va documentada explícitamente en el contrato (Tarea 7). Los errores sí siguen usando el envelope, porque los produce `GlobalExceptionHandler`, que no cambia.

> **Nota sobre permisos:** `ReporteController` ya lleva `@PreAuthorize("hasAnyRole('admin', 'gerencia', 'jdv')")` **a nivel de clase** (línea 24). El método nuevo hereda esa restricción automáticamente. **No añadas un `@PreAuthorize` propio al método**: sería redundante y crearía dos sitios donde mantener la misma regla. El test comprueba que la herencia funciona.

- [ ] **Paso 1: Escribe el test que falla**

Crea `src/test/kotlin/pe/quantum/crm/domain/reportes/ExportComercialControllerWebMvcTest.kt` con **exactamente** este contenido:

```kotlin
package pe.quantum.crm.domain.reportes

import com.ninjasquad.springmockk.MockkBean
import io.mockk.every
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.http.HttpHeaders
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import pe.quantum.crm.config.security.JwtService
import pe.quantum.crm.support.SinBaseDeDatosMocks

/**
 * Borde HTTP del export comercial (plan-15): cabeceras de descarga, tipo de
 * contenido y el reparto de permisos que hereda de `ReporteController`.
 *
 * Sin base de datos: el servicio va mockeado. Lo que se prueba aqui es el
 * contrato HTTP, no el SQL (eso es ExportComercialServiceIntegrationTest).
 */
@SpringBootTest(
    properties = [
        "spring.autoconfigure.exclude=" +
            "org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration," +
            "org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration," +
            "org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration",
    ],
)
@AutoConfigureMockMvc
@Import(SinBaseDeDatosMocks::class)
class ExportComercialControllerWebMvcTest {
    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var jwtService: JwtService

    @MockkBean
    lateinit var exportComercialService: ExportComercialService

    @MockkBean
    lateinit var reporteService: ReporteService

    private fun token(rol: String) = jwtService.generateAccessToken(empleadoId = 1, rol = rol)

    @Test
    fun `gerencia descarga el export como archivo xlsx adjunto`() {
        every { exportComercialService.filas(any(), any()) } returns emptyList()

        mockMvc
            .get("/api/v1/reportes/exportar-comercial") {
                header(HttpHeaders.AUTHORIZATION, "Bearer ${token("gerencia")}")
            }.andExpect {
                status { isOk() }
                header {
                    string(
                        HttpHeaders.CONTENT_TYPE,
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                    )
                }
                header { string(HttpHeaders.CONTENT_DISPOSITION, org.hamcrest.Matchers.containsString("attachment")) }
                header { string(HttpHeaders.CONTENT_DISPOSITION, org.hamcrest.Matchers.containsString(".xlsx")) }
            }
    }

    @Test
    fun `admin tambien puede descargar el export`() {
        every { exportComercialService.filas(any(), any()) } returns emptyList()

        mockMvc
            .get("/api/v1/reportes/exportar-comercial") {
                header(HttpHeaders.AUTHORIZATION, "Bearer ${token("admin")}")
            }.andExpect { status { isOk() } }
    }

    @Test
    fun `jdv puede descargar el export con vision total`() {
        // Decision de producto del 2026-09-08: jdv entra, sin acotar a su equipo.
        // Es el mismo acceso que ya tiene a los otros seis reportes.
        every { exportComercialService.filas(any(), any()) } returns emptyList()

        mockMvc
            .get("/api/v1/reportes/exportar-comercial") {
                header(HttpHeaders.AUTHORIZATION, "Bearer ${token("jdv")}")
            }.andExpect { status { isOk() } }
    }

    @Test
    fun `un vendedor no puede descargar el export`() {
        mockMvc
            .get("/api/v1/reportes/exportar-comercial") {
                header(HttpHeaders.AUTHORIZATION, "Bearer ${token("vendedor")}")
            }.andExpect { status { isForbidden() } }
    }

    @Test
    fun `un analista no puede descargar el export`() {
        mockMvc
            .get("/api/v1/reportes/exportar-comercial") {
                header(HttpHeaders.AUTHORIZATION, "Bearer ${token("analista")}")
            }.andExpect { status { isForbidden() } }
    }

    @Test
    fun `sin token el export responde 401`() {
        mockMvc
            .get("/api/v1/reportes/exportar-comercial")
            .andExpect { status { isUnauthorized() } }
    }

    @Test
    fun `las fechas del query llegan al servicio tal como se envian`() {
        every { exportComercialService.filas(any(), any()) } returns emptyList()

        mockMvc
            .get("/api/v1/reportes/exportar-comercial") {
                header(HttpHeaders.AUTHORIZATION, "Bearer ${token("gerencia")}")
                param("fecha_desde", "2026-01-01")
                param("fecha_hasta", "2026-06-30")
            }.andExpect { status { isOk() } }

        io.mockk.verify {
            exportComercialService.filas(
                java.time.LocalDate.of(2026, 1, 1),
                java.time.LocalDate.of(2026, 6, 30),
            )
        }
    }

    @Test
    fun `sin fechas el servicio recibe nulos y devuelve todo el historico`() {
        // A diferencia del resto de reportes (§18, default = mes actual), este
        // export sin fechas trae TODO. Un export de control que por defecto
        // recorta a un mes se lee como datos faltantes.
        every { exportComercialService.filas(any(), any()) } returns emptyList()

        mockMvc
            .get("/api/v1/reportes/exportar-comercial") {
                header(HttpHeaders.AUTHORIZATION, "Bearer ${token("gerencia")}")
            }.andExpect { status { isOk() } }

        io.mockk.verify { exportComercialService.filas(null, null) }
    }
}
```

- [ ] **Paso 2: Ejecuta el test y comprueba que falla**

Run: `./gradlew test --tests "pe.quantum.crm.domain.reportes.ExportComercialControllerWebMvcTest"`

Expected: **FALLA**. O bien no compila (`Unresolved reference`), o los tests dan 404 porque el endpoint no existe. Cualquiera de las dos es el rojo correcto.

- [ ] **Paso 3: Añade los imports al controller**

Abre `src/main/kotlin/pe/quantum/crm/domain/reportes/ReporteController.kt`. En el bloque de imports (líneas 1-16), añade estos cinco, **respetando el orden alfabético** que ktlint exige:

- `org.springframework.http.HttpHeaders` — va después de `org.springframework.format.annotation.DateTimeFormat`
- `org.springframework.http.MediaType` — justo después del anterior
- `org.springframework.http.ResponseEntity` — justo después del anterior
- `java.time.format.DateTimeFormatter` — al final, después de `java.time.LocalDate`

El bloque de imports queda así:

```kotlin
import org.springframework.format.annotation.DateTimeFormat
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import pe.quantum.crm.domain.reportes.dto.ReporteDescuentosDto
import pe.quantum.crm.domain.reportes.dto.ReporteEquipoItemDto
import pe.quantum.crm.domain.reportes.dto.ReportePipelineDto
import pe.quantum.crm.domain.reportes.dto.ReporteProspeccionDto
import pe.quantum.crm.domain.reportes.dto.ReporteVentasDto
import pe.quantum.crm.domain.reportes.dto.VelocidadEtapaDto
import pe.quantum.crm.shared.ApiResponse
import java.time.LocalDate
import java.time.format.DateTimeFormatter
```

- [ ] **Paso 4: Inyecta el servicio nuevo en el constructor**

Localiza la declaración de la clase (líneas 25-27) y sustitúyela por esta:

```kotlin
class ReporteController(
    private val reporteService: ReporteService,
    private val exportComercialService: ExportComercialService,
) {
```

- [ ] **Paso 5: Añade el endpoint**

Al final de la clase, **después** del método `descuentos(...)` y **antes** de la llave `}` que cierra la clase, inserta esto:

```kotlin

    /**
     * Export en Excel de la gestion comercial (plan-15). Una sola hoja, una fila
     * por actividad, con prospectos, pipeline e historico completo.
     *
     * Es el UNICO endpoint del API que no devuelve el envelope `ApiResponse`:
     * el cuerpo es el archivo. Envolverlo en JSON lo haria indescargable. Los
     * errores si siguen el envelope, porque los produce GlobalExceptionHandler.
     *
     * Sin `fecha_desde`/`fecha_hasta` devuelve TODO el historico, a diferencia
     * del resto de §18, donde el default es el mes calendario actual: un export
     * de control que por defecto recorta a un mes se lee como datos faltantes.
     *
     * El permiso (admin/gerencia/jdv) lo hereda del @PreAuthorize de la clase.
     */
    @GetMapping("/exportar-comercial")
    fun exportarComercial(
        @RequestParam(name = "fecha_desde", required = false)
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) fechaDesde: LocalDate?,
        @RequestParam(name = "fecha_hasta", required = false)
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) fechaHasta: LocalDate?,
    ): ResponseEntity<ByteArray> {
        val libro = LibroExportComercial.construir(exportComercialService.filas(fechaDesde, fechaHasta))
        val nombre = "gestion-comercial-${LocalDate.now().format(DateTimeFormatter.ISO_DATE)}.xlsx"
        return ResponseEntity
            .ok()
            .contentType(MediaType.parseMediaType(TIPO_XLSX))
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"$nombre\"")
            .body(libro)
    }

    private companion object {
        const val TIPO_XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
    }
```

- [ ] **Paso 6: Ejecuta el test y comprueba que pasa**

Run: `./gradlew test --tests "pe.quantum.crm.domain.reportes.ExportComercialControllerWebMvcTest"`

Expected: `BUILD SUCCESSFUL`, 8 tests en verde.

Si los tests de 403 fallan devolviendo 200, significa que añadiste un `@PreAuthorize` propio al método que amplía el acceso — quítalo, la restricción va sólo a nivel de clase.

- [ ] **Paso 7: Ejecuta la suite completa y los gates**

Run: `./gradlew test ktlintCheck detekt`

Expected: `BUILD SUCCESSFUL`.

- [ ] **Paso 8: Verifica la cobertura**

Run: `./gradlew koverVerify`

Expected: `BUILD SUCCESSFUL`.

**Si falla** (los umbrales son 85% global y 84% de dominio), la causa casi seguro es el SQL de `ExportComercialService`, que no se cubre en local. **La solución es añadir más tests unitarios a `EtiquetasExportTest` o `LibroExportComercialTest`, nunca bajar el `minBound` de `build.gradle.kts`.** Esos números son un trinquete deliberado: el propio archivo lo documenta en extenso. Si aun así no alcanzas el umbral, **detente y reporta** con la cifra exacta que dio Kover.

- [ ] **Paso 9: Commit**

```bash
git add src/main/kotlin/pe/quantum/crm/domain/reportes/ReporteController.kt src/test/kotlin/pe/quantum/crm/domain/reportes/ExportComercialControllerWebMvcTest.kt
git commit -m "$(cat <<'EOF'
feat(reportes): endpoint de descarga del export comercial en Excel

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
EOF
)"
```

---

## Tarea 7: Documentación del contrato y de los permisos

**Files:**
- Modify: `docs/contrato_api.md` (§18 línea 1936 y sección nueva; §28 fila nueva al final de la tabla)
- Modify: `docs/matriz_permisos.md` (§2.10, tabla de líneas 193-202)

**Interfaces:**
- Consumes: el endpoint de la Tarea 6.
- Produces: documentación. Sin esto el PR **no está terminado**, aunque el código y los tests pasen — lo dice §28 literalmente.

> **Esta tarea no lleva tests.** Son documentos Markdown. La verificación es leer el resultado.

- [ ] **Paso 1: Corrige la deriva `gerente` → `gerencia` en §18**

Abre `docs/contrato_api.md`. Ve a la **línea 1936**, que dice:

```
Todos los endpoints de reportes requieren rol `admin`, `gerente` o `jdv`. Los vendedores no tienen acceso a reportes en el MVP.
```

Sustitúyela por:

```
Todos los endpoints de reportes requieren rol `admin`, `gerencia` o `jdv`. Los vendedores no tienen acceso a reportes en el MVP.
```

**Por qué:** el rol se llama `gerencia` desde la migración V25; `gerente` no existe en el enum `rol_empleado` ni en el código (`ReporteController.kt:24` usa `gerencia`). Es deriva de documentación detectada durante el triage de este ticket. Se corrige aquí porque este PR es el que toca esta sección.

- [ ] **Paso 2: Documenta el endpoint nuevo en §18**

En `docs/contrato_api.md`, ve al final de la sección 18, **justo antes** de la línea `## 19. Notificaciones` (alrededor de la línea 2105). Inserta ahí este bloque completo:

```markdown
---

### GET /reportes/exportar-comercial
> Export en Excel de la gestión comercial completa: prospectos, pipeline e histórico de actividades. Para control interno de gerencia.

**Roles:** `admin`, `gerencia`, `jdv` — con visión total, sin filtrar por dueño del registro. El resto recibe `403`.

**Query params:**

| Param | Tipo | Req. | Descripción |
|---|---|---|---|
| `fecha_desde` | ISO 8601 date | no | Filtra por fecha de ingreso, inclusive. |
| `fecha_hasta` | ISO 8601 date | no | Filtra por fecha de ingreso, inclusive. |

**Diferencia deliberada con el resto de §18:** si no se envían fechas, este endpoint devuelve **todo el histórico**, no el mes calendario actual. Un export de control que por defecto recorta a un mes se lee como datos faltantes.

La fecha de ingreso es `oportunidades.created_at` para las filas con oportunidad y `empresas.created_at` para las filas de prospección. **Las actividades no se filtran por el rango**: una vez que la oportunidad entra en alcance, viene su histórico completo.

**Respuesta 200:** el archivo `.xlsx`, no el envelope JSON.

```
Content-Type: application/vnd.openxmlformats-officedocument.spreadsheetml.sheet
Content-Disposition: attachment; filename="gestion-comercial-2026-09-08.xlsx"
```

> **Es el único endpoint del API que no devuelve `{data, meta, error}`.** El cuerpo es el archivo binario; envolverlo en JSON lo haría indescargable. Los errores (`401`, `403`) sí siguen el envelope estándar.

**Forma del archivo:** una sola hoja llamada `Gestión comercial`, 33 columnas, **una fila por actividad** repitiendo los datos del prospecto y de la oportunidad (tabla plana, pensada para filtrar y pivotar en Excel). Una oportunidad sin actividades ocupa una fila con las columnas de actividad en `-`; un prospecto sin oportunidad también.

**Ninguna celda queda vacía:** lo que el CRM no guarda se emite como `"-"`. En particular, la columna **Principal bloqueo** sale siempre `"-"` porque no existe ningún campo que la respalde — no se infiere de `notas` ni de `motivo_cierre`.

**Columnas, en orden:** RUC · Razón social · Fecha de ingreso · Responsable del prospecto · Origen del prospecto · Segmento · Estado de cartera · ID oportunidad · Vendedor de la oportunidad · Etapa / Estado actual · Modelos y unidades · Unidades totales · Monto total · Financiadora · Fecha de creación de la oportunidad · Fecha estimada de cierre · Fecha de facturación · Motivo de no cierre · Notas de la oportunidad · Siguiente acción · Principal bloqueo · Fecha de primer contacto · Fecha de última gestión · Días sin gestión · Tipo de actividad · Actividad · Tipo de acción · Estado de la actividad · Fecha de la actividad · Responsable de la actividad · Descripción · Comentarios de seguimiento · Fecha de registro.

**Mapeos que conviene conocer:** "Etapa / Estado actual" es el estado real de la oportunidad, con los **4 valores del enum** (`reglas_negocio.md` §4.1) traducidos a etiqueta de negocio — no existe la granularidad de "contacto / reunión / cotización" que se usa al hablar del embudo, ni existe un estado `perdido`. "Siguiente acción" es la próxima tarea pendiente. "Fecha de primer contacto" y "Fecha de última gestión" se derivan del `created_at` mínimo y máximo de las actividades.
```

- [ ] **Paso 3: Añade la entrada al changelog §28**

En `docs/contrato_api.md`, localiza la tabla de §28 y su **última fila**, la que empieza por `| 2026-09-08 | \`GET /actividades\`` (línea 2877). Inserta **inmediatamente después** de esa fila, y antes de la línea en blanco que precede a `## 29.`, esta fila nueva:

```
| 2026-09-08 | `GET /reportes/exportar-comercial` | Non-breaking | Endpoint nuevo: export en Excel (`.xlsx`) de la gestión comercial para control interno de gerencia — prospectos, pipeline e histórico completo de actividades en una sola hoja de 33 columnas, una fila por actividad. Roles `admin`, `gerencia`, `jdv` con visión total (el mismo reparto que los otros 6 reportes; ver `matriz_permisos.md §2.10`). **Es el único endpoint del contrato que no devuelve el envelope `{data, meta, error}`:** el cuerpo es el archivo binario, con `Content-Disposition: attachment`. Dos comportamientos que se apartan del resto de §18, a propósito: (1) sin `fecha_desde`/`fecha_hasta` devuelve todo el histórico, no el mes calendario actual; (2) el rango filtra la fecha de ingreso del prospecto/oportunidad, no las actividades, que vienen completas. Ninguna celda va vacía: lo que el CRM no guarda se emite como `"-"`, incluida la columna "Principal bloqueo", que no tiene campo que la respalde y sale siempre así. En la misma entrada se corrige una deriva de §18, que seguía diciendo `gerente` en vez de `gerencia` (el rol se renombró en V25). | Añadir el botón de descarga donde corresponda para `admin`/`gerencia`/`jdv`. **Tratar la respuesta como binario, no como JSON** — es la excepción al envelope. Si se ofrecen filtros de fecha en la UI, dejar claro que vacío significa "todo el histórico". |
```

- [ ] **Paso 4: Añade la fila a la matriz de permisos §2.10**

Abre `docs/matriz_permisos.md`. Localiza la tabla de §2.10 (líneas 193-202). Añade una fila **después** de `| Mix de descuentos | ✓ | ✓ | ✓ | — | — | — |` (línea 200):

```
| Export Excel de gestión comercial | ✓ | ✓ | ✓ | — | — | — |
```

La tabla queda con 7 filas de datos. La frase de la línea 202 ("Ningún rol `vendedor`, `analista` ni `otro` tiene acceso a reportes en el MVP") **sigue siendo cierta y no se toca**.

- [ ] **Paso 5: Relee lo que escribiste**

Abre los dos documentos y verifica con tus propios ojos:
1. En `contrato_api.md` §18 ya **no aparece la palabra `gerente`** (busca `gerente` en el archivo: si sale en otro sitio de §18, corrígelo igual; si sale en otras secciones, **déjalo**, no es el alcance de este PR).
2. La tabla de §28 sigue teniendo el formato de 5 columnas y no rompiste ninguna fila anterior.
3. La tabla de §2.10 sigue teniendo 6 columnas de roles (`admin`, `gerencia`, `jdv`, `vendedor`, `analista`, `otro`) y tu fila nueva tiene exactamente 6 marcas.

- [ ] **Paso 6: Commit**

```bash
git add docs/contrato_api.md docs/matriz_permisos.md
git commit -m "$(cat <<'EOF'
docs(contrato): endpoint de export comercial, changelog y matriz de permisos

Corrige tambien la deriva de §18, que seguia diciendo `gerente` en vez de
`gerencia` (V25 renombro el rol).

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
EOF
)"
```

---

## Tarea 8: Verificación final y relectura del diff contra la documentación

**Files:** ninguno que crear. Esta tarea **lee** y, si encuentra un problema, corrige.

> Esta tarea es obligatoria por `CLAUDE.md` ("Al final del plan, como tarea propia"). Su propósito no es repetir los tests: es cazar **contradicciones con documentación que ya estaba escrita correctamente antes de empezar**, y que una sesión larga puede haber pisado sin darse cuenta. Es un fallo distinto de "falta documentar X".

- [ ] **Paso 1: Ejecuta todos los gates que corren en local**

Run: `./gradlew test ktlintCheck detekt koverVerify`

Expected: `BUILD SUCCESSFUL` en los cuatro. Si alguno falla, **arréglalo antes de seguir**; no pases a la revisión documental con la rama en rojo.

- [ ] **Paso 2: Comprueba que ArchUnit sigue verde**

Run: `./gradlew test --tests "pe.quantum.crm.arquitectura.ArquitecturaModulosTest"`

Expected: `BUILD SUCCESSFUL`, 5 tests en verde.

Si falla el test `las dependencias entre modulos solo apuntan a la API publica`, has importado una entidad o un repositorio de otro módulo en `ExportComercialService`. Ese servicio **no debe importar nada** de `domain.empresas`, `domain.oportunidades`, `domain.tareas`, `domain.eventos` ni `domain.actividades`. Quita el import; toda la información sale del SQL.

- [ ] **Paso 3: Lee el diff completo de la rama**

Run: `git diff main...HEAD`

Léelo entero. No lo hojees.

- [ ] **Paso 4: Contrasta el diff contra cada documento citado en la fase de investigación**

Recorre esta lista y responde cada pregunta **mirando el diff**, no de memoria. Si alguna respuesta es "no", corrígelo y vuelve al Paso 1.

- [ ] `CLAUDE.md` regla 2 — ¿el monto se **calcula** con `cantidad × precio_venta × (1 − descuento/100)` sobre `oportunidad_items`, y en ningún sitio se lee una columna `monto_total` de `oportunidades`?
- [ ] `CLAUDE.md` regla 3 — ¿el diff no contiene ningún `INSERT`, `UPDATE` ni `DELETE` fuera de los archivos de test?
- [ ] `CLAUDE.md` regla 7 — ¿el mapa `ESTADOS` de `EtiquetasExport` tiene **exactamente 4 entradas** y ninguna se llama `perdido`?
- [ ] `CLAUDE.md` regla 8 — ¿toda inyección es por constructor con `private val`, sin un solo `@Autowired` sobre un campo en código de producción?
- [ ] `CLAUDE.md` regla 10 — ¿`ExportComercialService.filas` lleva `@Transactional(readOnly = true)`?
- [ ] `CLAUDE.md` regla 11 — ¿las fechas viajan como parámetros nombrados (`:desde`, `:hasta`) y no hay **ni un solo valor interpolado** dentro de un string SQL de producción?
- [ ] `CLAUDE.md` regla 12 — ¿`ExportComercialService.kt` no importa ninguna clase de otro módulo de dominio?
- [ ] `CLAUDE.md` regla 6 y `docs/matriz_permisos.md` — ¿en todo el diff el rol se escribe `gerencia` y **nunca** `gerente`? (Comprueba también los tests.)
- [ ] `docs/contrato_api.md` §28 — ¿existe la fila nueva del changelog, con la fecha `2026-09-08` y el tipo `Non-breaking`?
- [ ] `docs/contrato_api.md` §29 — ¿las fechas `DATE` (`fecha_cierre_estimado`) se exportan **sin hora**, vía `EtiquetasExport.dia`, y no vía `EtiquetasExport.fecha`?
- [ ] `docs/reglas_negocio.md` §4.4 — ¿`motivo_cierre` sale como columna propia y no se reutiliza para rellenar "Principal bloqueo"?
- [ ] `docs/reglas_negocio.md` §10 — ¿el export arranca de `empresas` (no de `oportunidades`), de modo que un prospecto sin oportunidad aparece?
- [ ] Ticket R6/R8 — ¿el diff **no** contiene ninguna migración nueva, ninguna columna nueva y ningún valor de enum nuevo?
- [ ] Decisión de producto 3 — ¿toda celda pasa por `EtiquetasExport`, de forma que ninguna puede salir vacía?
- [ ] Decisión de producto 4 — ¿hay un test que comprueba que `jdv` recibe 200, y otro que `vendedor` recibe 403?

- [ ] **Paso 5: Busca contradicciones con documentación preexistente**

Ejecuta estas búsquedas y revisa cada resultado:

```bash
git diff main...HEAD -- docs/
```

Pregúntate, para cada línea que **borraste o cambiaste** en `docs/`: ¿estaba mal antes, o estaba bien y la pisé? La única línea de documentación preexistente que este plan autoriza a cambiar es la de `gerente` → `gerencia` en §18. **Si el diff de `docs/` modifica cualquier otra línea que ya existía, deshaz ese cambio** — las adiciones sí son esperadas.

- [ ] **Paso 6: Verifica el estado final del repositorio**

Run: `git status`

Expected: la rama `feature/export-excel-comercial`, sin cambios sin commitear (salvo el `.json` del requerimiento, que estaba sin seguimiento desde antes y no forma parte de este trabajo).

- [ ] **Paso 7: Reporta el resultado**

Escribe un resumen corto con:
1. Los comandos de verificación que ejecutaste y su resultado.
2. Cualquier casilla del Paso 4 que no pudiste cerrar y por qué.
3. El recordatorio de que `./gradlew integrationTest` **no se ejecutó en local** (Testcontainers/Docker 29) y que `ExportComercialServiceIntegrationTest` se valida en CI.

**No abras el PR por tu cuenta.** Reporta y espera instrucciones.

---

## Notas para quien revise este trabajo

- **El único gate que no se pudo correr en local es `integrationTest`.** Es una limitación conocida de la máquina (Docker 29 rompe Testcontainers), no una omisión del ejecutor. El SQL de `ExportComercialService` sólo queda realmente verificado cuando CI ejecute `ExportComercialServiceIntegrationTest`. **Hasta entonces, el SQL está escrito y razonado contra las migraciones, pero no ejecutado.**
- **El riesgo más alto del cambio es el rendimiento**, no la corrección: el export no pagina y construye el libro entero en memoria. Con los volúmenes actuales del MVP es correcto y es el mismo supuesto que ya hace `ReporteService` ("los volúmenes del MVP son bajos"). Si el universo crece a decenas de miles de actividades, esto hay que revisarlo — probablemente cambiando `XSSFWorkbook` por `SXSSFWorkbook` (escritura en streaming) y añadiendo un tope de filas.
- **Lo que este cambio deliberadamente NO hace:** no agrega campos para "origen exacto", "segmento único", "siguiente acción" libre ni "principal bloqueo". Si gerencia decide que alguno de esos datos es indispensable, es un ticket distinto y más grande que un exportador — capturar un dato nuevo es un cambio de producto, de UI y de schema.
