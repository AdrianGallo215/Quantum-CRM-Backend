# Mapa maestro — Módulo Simulaciones Financieras

> Documento de coordinación. No contiene tareas ejecutables: reparte el encargo de
> `Instrucciones_simulaciones.md` en planes, declara qué está desbloqueado y qué no,
> y fija las decisiones de arquitectura que los planes dan por cerradas.
>
> Redactado: 2026-09-01. Baseline verificado: `./gradlew test ktlintCheck` → exit 0.

---

## 1. Hallazgos de la investigación previa

Verificados contra el repo **y contra Supabase producción** (proyecto
`fmkqomwyakxeblfinkuy`), no inferidos.

| # | Hallazgo | Evidencia | Consecuencia |
|---|---|---|---|
| H1 | `oportunidad_items` **no existe** | Sin coincidencias en `src/main/resources/db/migration/` ni en `src/main/kotlin/`. En prod: `to_regclass('public.oportunidad_items')` → `null` | **Fases 2–6 bloqueadas.** Es la dependencia bloqueante que `Instrucciones_simulaciones.md` anticipa |
| H2 | Prod está en **V39**, igual que local | `flyway_schema_history` → `installed_rank` máx. 39, todas `success` | Sin drift. La numeración parte de V40 |
| H3 | Las tablas y enums de simulaciones **no existen en prod** | `to_regclass` de `simulaciones`, `simulacion_log` y `to_regtype` de `modo_simulacion_enum` → todos `null` | V40 está sin desplegar |
| H4 | V40 vive en `docs/migrations/`, **no** en la carpeta de Flyway | `docs/migrations/V40__create_simulaciones.sql` | Correcto y así se queda. Ver D3 |
| H5 | `spring.flyway.out-of-order` **no está configurado** ⇒ `false` | `application.properties:13-14` | Flyway **falla** si aparece una versión menor después de una mayor ya aplicada. Numerar migraciones es una operación coordinada, no libre. Ver D4 |
| H6 | **No existe** almacén para el tipo de cambio (§12) | Sin tabla de configuración global en ninguna migración; V40 no crea ninguna | Vacío real de la especificación. Resuelto por decisión del dueño del producto: se diseña migración nueva. Ver Plan 2 |
| H7 | Los **dos casos dorados de §3.6 son matemáticamente correctos** | Reproducidos al centavo, valor por valor, con `decimal` de precisión 50 y con `BigDecimal` en Java | El fixture de aceptación es fiable. No hay que renegociarlo |
| H8 | El motor exige raíz 12-ésima sobre `BigDecimal`; Java **no** tiene `pow(BigDecimal)` | `BigDecimal.pow` solo acepta `Int` | Riesgo técnico #1 de la fase 1. **Resuelto y probado**: ver D2 y Plan 1 T3 |
| H9 | El proyecto **no** trae librería de matemática decimal | `build.gradle.kts` sin `big-math` ni equivalente | No hace falta añadir dependencia. D2 lo resuelve con ~15 líneas propias |
| H10 | ArchUnit prohíbe que un módulo dependa de una **clase** de otro módulo | `ArquitecturaModulosTest.esApiPublica()`: solo `dto`, interfaces, enums y `*Event` | Si el motor viviera en `domain/simulaciones/`, §6.1 (cuota efímera desde `oportunidades`) rompería el build. Ver D1 |

---

## 2. Decisiones de arquitectura (cerradas — los planes no las re-discuten)

### D1 · El motor vive en `shared/`, no en `domain/simulaciones/`

**Paquete:** `pe.quantum.crm.shared.simulacion`

Tres exigencias convergen en esto:

1. `Instrucciones_simulaciones.md` §1: *"no puede quedar acoplado al Service de `simulaciones`"* — lo consumen dos flujos, uno que persiste y otro que no.
2. `reglas_simulaciones.md` §6.1: `oportunidades` debe calcular una cuota **efímera** cuando el ítem no tiene simulación principal. Eso obliga a `oportunidades` a invocar el motor.
3. H10: ArchUnit tumbaría exactamente esa llamada si el motor fuese una clase dentro de `domain/simulaciones/`.

`ArquitecturaModulosTest.moduloDe()` devuelve `null` para todo lo que no cuelga de
`pe.quantum.crm.domain.` ⇒ un motor en `shared/` queda fuera del alcance de las reglas
de frontera, sin necesidad de excepciones ni de envolverlo en una interfaz artificial.

**Precedente en el repo:** `shared/PoliticaDescuento.kt` — política de negocio pura,
en `shared`, consumida por varios módulos de dominio. El motor es el mismo caso.

### D2 · Raíz 12-ésima: Newton-Raphson propio, sin dependencia nueva

`TNM = (1 + tea/100)^(1/12) − 1` necesita exponente fraccionario sobre `BigDecimal`.

**Algoritmo fijado (ya probado contra los dos casos dorados):** Newton-Raphson para
la raíz n-ésima, con semilla `1 + (a−1)/n`.

> **Trampa que el plan evita explícitamente.** La semilla ingenua `a/n` **diverge**:
> para `a=1.18, n=12` arranca en 0.098, `x^11 ≈ 8e−12`, y `a/x^11` explota a ~1.5e11;
> converge tan lento que a 200 iteraciones todavía devuelve ~356 en vez de ~1.0139.
> Esto se comprobó fallando de verdad durante la investigación. La semilla
> `1 + (a−1)/n` es la aproximación de primer orden de `a^(1/n)` para `a` cercano a 1
> —y `a = 1 + tea/100` siempre lo está, con `tea ∈ (0, 200)` por CHECK de BD—
> y converge en pocas iteraciones.

Precisión verificada con `MathContext(34, HALF_EVEN)` y trabajo interno a 50 dígitos:

| Comprobación | Resultado obtenido | Esperado (§3.6) |
|---|---|---|
| `TNM(18)` | `0.013888430348410033338673230028230` | `0.013888430348410033…` ✔ |
| `TNM(13)` | `0.010236844358176363360835031780333` | `0.010236844358176363…` ✔ |
| `(1+TNM(18))^12` | `1.179999999999999999999999999999999` | `1.18` ✔ |
| Leasing `|SaldoFinal(48) − 0|` | `8.58e−30` | `< 0.01` ✔ |
| C. Directo `|SaldoFinal(48) − 35000|` | `2.6e−30` | `< 0.01` ✔ |

Todos los valores de las tablas de §3.6 (cuotas, intereses, amortizaciones, saldos e
IGV de los meses 0, 1, 2 y 48 de ambos modos) se reprodujeron **exactos al centavo**.

> **Nota de actualización (2026-09-03):** todas las referencias a "V40" en este
> documento son registro histórico de la investigación original. Esa migración
> se renumeró a **V43** en `plan-04-fundacion-items.md` tarea O6, porque V41
> (tipo de cambio) ya estaba aplicada en producción cuando `oportunidad_items`
> quedó listo, y Flyway no admite migraciones fuera de orden. La decisión D3 de
> abajo sigue vigente en su contenido — solo cambió el número de archivo.

### D3 · V40 no entra a `src/main/resources/db/migration/` todavía

V40 referencia `oportunidad_items(id)`, que no existe (H1). Copiarla a la carpeta de
Flyway haría **fallar el arranque de la aplicación en producción**: Flyway corre al
inicio y aborta el contexto de Spring si una migración falla.

Se queda en `docs/migrations/` hasta que `oportunidad_items` exista. La fase 1 no
necesita base de datos en absoluto —el motor es una función pura sin JPA— así que
esto no bloquea nada del trabajo desbloqueado.

### D4 · La numeración de migraciones se asigna al desplegar, no al escribir

Por H5, Flyway rechaza migraciones fuera de orden. `oportunidad_items` es un cambio
**separado y en vuelo**, cuyo número de versión no controlamos. Cualquier número que
quememos ahora puede colisionar.

Regla para todo plan que traiga migración: el archivo se escribe con número
**provisional**, y una tarea de despliegue —última del plan— vuelve a consultar
`flyway_schema_history` en prod inmediatamente antes de aplicar y renumera si hace
falta. Ningún plan aplica una migración a producción sin esa comprobación.

### D5 · Convenciones del repo que los planes heredan sin repetirlas

- Dinero: `setScale(2, RoundingMode.HALF_UP)` — convención establecida en
  `domain/oportunidades/MontoTotal.kt`. El redondeo se aplica **solo al exponer**.
- Inyección por constructor (`private val`). Nunca `@Autowired` en campo.
- Tests: MockK + `@ExtendWith(MockKExtension::class)`; nombres en backticks que
  describen comportamiento (`TESTING-backend.md` §9).
- Enums de dominio en minúscula, con `@Suppress("ktlint:standard:enum-entry-name-case", "EnumNaming", "EnumEntryName")`,
  para que coincidan con las etiquetas de los enums nativos de PostgreSQL.

---

## 3. Reparto en planes

| Plan | Alcance | Estado | Migración | Documento |
|---|---|---|---|---|
| **1** | Motor de cálculo puro (fase 1 del encargo) | ✅ **Ejecutable ya** | Ninguna | `plan-01-motor-calculo.md` |
| **2** | Tipo de cambio SUNAT (§12) | ✅ **Ejecutable ya** | Sí (provisional) | `plan-02-tipo-cambio.md` |
| **3** | Persistencia y dominio (fase 2) | ⛔ Bloqueado por H1 | V40 | Se redacta al desbloquear |
| **4** | Endpoints, permisos y Calculadora (fases 3–4) | ⛔ Bloqueado por H1 | — | Se redacta al desbloquear |
| **5** | Jobs de purga y aviso (fase 5, parcial) | ⛔ Bloqueado por H1 | Enums notif. | Se redacta al desbloquear |
| **6** | Documentación de contrato y permisos (fase 6) | ⛔ Bloqueado por H1 | — | Se redacta al desbloquear |

Planes 1 y 2 son **independientes entre sí**: pueden ejecutarse en paralelo o en
cualquier orden. Ninguno toca tabla viva ni el arranque de la app.

### Por qué los planes 3–6 no se detallan todavía

Sus tareas dependen de la forma exacta de `oportunidad_items` — nombres de columnas,
si `cuota_financiadora` nace ahí o llega después, cómo se expone la cadena
`ítem → oportunidad → empresa` que necesitan el nombre autogenerado (§8.1) y la
propuesta (§11). Escribirlas ahora contra una tabla imaginada produciría tareas que
habría que reescribir enteras, que es justo lo contrario de lo que un plan para
subagentes debe ser.

Lo que sí queda fijado desde ahora, para que el desbloqueo no reabra discusiones:

- **API pública que `simulaciones` necesitará de `oportunidades`** (regla 12 + ArchUnit):
  una interfaz en `domain/oportunidades/` que, dado un `id_oportunidad_item`, devuelva
  un DTO con `id_oportunidad`, `id_empresa`, `razon_social`, `id_modelo`, `cantidad`,
  `precio_venta`, `descuento`, `cuota_financiadora` e `id_vendedor`. Con ese único DTO
  se resuelven §5, §6.2, §8.1, §9 (validación de vendedor propio) y §11.
- **`simulaciones` no lee `financiadoras`** en ningún caso (§1.2). Sin FK, sin import.
- **Escala de `tea`**: 1–100 en `simulaciones`, fraccionaria en `financiadoras`. No se
  comparan ni se copian sin convertir.

---

## 4. Contradicción documental detectada (a resolver en el Plan 6)

`CLAUDE.md` regla 6 y `matriz_permisos.md §1` describen a `analista` como
**rol de apoyo de solo lectura**. `reglas_simulaciones.md` §10 le da **escritura
completa** en simulaciones y lo llama *"el rol dueño de este módulo"*.

No es un choque lógico —son módulos distintos— pero sí una trampa de lectura: quien
consulte `matriz_permisos.md` para simulaciones concluirá lo contrario de lo correcto.
El Plan 6 debe dejar la excepción escrita **de forma explícita** en `matriz_permisos.md`,
no darla por deducible.

Se registra aquí, y no solo en el Plan 6, porque es exactamente el caso que el
apartado *"Cómo escribir un plan de implementación en este repo"* de `CLAUDE.md`
manda cazar: documentación ya vigente que un cambio posterior pisa sin darse cuenta.

---

## 5. Estado de despliegue en producción

Nada de los planes 1 y 2 toca datos ni tablas existentes.

| Elemento | Acción | Cuándo |
|---|---|---|
| V40 (`simulaciones`, `simulacion_log`, enums, trigger, RLS) | Aplicar por MCP de Supabase | Solo cuando exista `oportunidad_items` |
| Migración de tipo de cambio | Aplicar por MCP, previa relectura de `flyway_schema_history` (D4) | Al cerrar el Plan 2 |
| Enums de notificaciones (`simulacion_purga_proxima`, entidad `simulacion`) | Aplicar por MCP | Con el Plan 5 |

Ninguna migración se aplica sin confirmación explícita del dueño del producto.
