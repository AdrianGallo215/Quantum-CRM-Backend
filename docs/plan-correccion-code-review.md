# Plan de corrección — hallazgos [Alto]/[Crítico] del code review

Plan de trabajo derivado de `docs/code-review-pendientes.md`. Ese documento es el **registro de hallazgos** (qué está mal y su estado); este es el **plan de ataque** (en qué orden se corrige y por qué).

> **Para ejecutar:** las Tandas 2, 3 y 4 están desglosadas en tareas atómicas, con test RED y código literal, en **`docs/plan-ejecucion-subagentes.md`**. Ese documento está escrito para que lo ejecute una sesión de Sonnet 5 repartiendo el trabajo en 7 subagentes. Este de aquí es el porqué; aquel es el cómo.

Creado el 2026-08-07. Si esta sesión se corta, retomar desde aquí.

---

## Estado base verificado (2026-08-07, antes de tocar nada)

| Comprobación | Resultado |
|---|---|
| Rama | `main`, último commit `c97f138 Critical bugs fixed` |
| `git status` | Limpio. Solo sin trackear: `docs/code-review-pendientes.md`, `scripts/` |
| `./gradlew test` | ✅ verde — forzado con `--rerun` (la primera pasada salió `UP-TO-DATE` y no probaba nada) |
| `./gradlew ktlintCheck` | ✅ verde |
| `./gradlew detekt` | ✅ verde |

**Contraste del documento contra el código:** se verificaron dos ficheros de test que parecían contradecir el registro. No lo contradicen:

- `PaginacionTest.kt` (9 tests) cubre el parseo de `sort`/`page`/`per_page`, **nunca** `meta`/`total_pages` → el hallazgo [Medio] sigue vivo.
- `EmpleadoCrudControllerWebMvcTest.kt` (4 tests) cubre solo la validación del `PUT` (el ítem ya ✅), sin `listar()`, `crear()`, `EMAIL_DUPLICADO` ni `@PreAuthorize` → el hallazgo [Alto] sigue vivo.

El registro es fiable.

---

## Reglas de trabajo (fijas, para toda esta tanda)

1. **TDD obligatorio** (CLAUDE.md regla 1). El test que falla se escribe y se ve fallar **antes** del código de producción. Sin excepción, en cada fix.
2. **Verificación estándar tras cada fix:** `./gradlew test ktlintCheck detekt` en verde.
3. **Testcontainers está roto en esta máquina** (Docker 29). Los tests `@Tag("integration")` **no se pueden ejecutar aquí**, solo en CI. Si se escribe uno: decirlo explícitamente y **nunca** afirmar que pasa sin haberlo visto pasar.
4. **Los ítems 🗄️ (Supabase)** son cambios de esquema que aplica a mano el dueño del proyecto. No se tocan ni se proponen salvo petición explícita.
5. **Sin `git commit` ni `git push`** salvo petición explícita.
6. Al cerrar cada ítem, actualizar su estado en `docs/code-review-pendientes.md`.

---

## Panorama

**Críticos pendientes: ninguno.** Los dos están ✅ (reportes de ventas/equipo, `OrigenLead.otro`).

Quedan **10 ítems [Alto]**. Al agruparlos aparece el patrón que determina el orden: solo 2 son bugs de comportamiento, 3 están bloqueados por una decisión de producto, y 5 son deuda de cobertura.

---

## Tanda 1 — Bugs reales, fix acotado ← ✅ **COMPLETADA** (2026-08-07)

Ambos corregidos con TDD (RED verificado antes de cada fix) y `./gradlew test ktlintCheck detekt` en verde. Detalle en `code-review-pendientes.md`. Resumen:

- **C1** → `NotificacionService.reiniciarRecordatorios()`, llamada desde `actualizar` de tareas y eventos solo cuando la fecha cambia. 6 tests unitarios + 1 `@Tag("integration")` **no ejecutado aquí** (pendiente de CI).
- **E1** → `hitosOcurridos` devuelve `LocalDateTime?` y el avance se mide por `containsKey`. 6 tests nuevos en un módulo que tenía cobertura cero.
- Refactor colateral: `TareaServiceImpl.actualizar` cruzó los umbrales de detekt al añadir el reinicio; se extrajo `reemplazarColaboradores()` en vez de suprimir la regla.


Dos fixes pequeños sobre archivos disjuntos. Se hacen en la sesión principal, **sin subagentes**: lanzarlos costaría más de lo que ahorra.

### C1 · Recordatorios huérfanos al reprogramar
`TareaServiceImpl.kt:190`, `EventoServiceImpl.kt:166` — [Alto]

La clave de dedup de recordatorios es `(origen, id_origen, umbral)` y **no incluye la fecha**; nada borra las filas viejas al reprogramar. Resultado: reprogramar una tarea o evento la deja **sin recordatorios para siempre**.

Impacto real: reprogramar es la edición más común sobre una tarea pendiente, así que esto está silenciando recordatorios en producción hoy.

### E1 · NPE en `hitosOcurridos`
`ProspeccionDao.kt:70` — [Alto]

Un hito con `estado = 'ocurrido'` pero `fecha_ocurrencia` nula provoca NPE. El CHECK de V14 **permite** esa combinación, así que el dato que lo dispara es alcanzable.

Impacto real: tumba la respuesta **completa** de `GET /prospeccion` y `GET /inicio`, no solo la empresa afectada.

---

## Tanda 2 — ✅ Decisiones tomadas (2026-08-07)

Desbloqueada. El dueño del proyecto resolvió las tres:

| Ítem | Archivo | Decisión |
|---|---|---|
| **B1** · RUC del mismo vendedor devuelve 409 en vez de 200 | `EmpresaServiceImpl.kt:118` | **Manda `reglas_negocio.md §2.1`.** RUC de **otro** vendedor → error soportado (409) con mensaje que **no culpe al usuario**. RUC del **mismo** vendedor → devolver la empresa existente con **200**. Se actualiza `contrato_api.md` para alinearlo. |
| **D1** · `requiere_cambio_contrasena` no se puede apagar | `EmpleadoServiceImpl.kt:83` | **Sí, implementar el cambio de contraseña.** Es un cambio autenticado, distinto de *"olvidé mi contraseña"*, que sigue fuera del MVP. |
| **F1** · `id_modelo` `NOT NULL` en tabla, nullable en entidad | `Oportunidad.kt:38-39` | **`nullable = false` en la entidad** (lado código). El esquema no se toca. |

### Restricción descubierta al planificar B1

El import CSV depende de que `crearSinCarpetaDrive` **lance** `RucDuplicadoException` ante cualquier duplicado — así construye su reporte de errores (`ImportCsvTempServiceImplTest.kt:141`). Por eso el comportamiento nuevo se aplica **solo a `crear`** (el camino HTTP); `crearSinCarpetaDrive` conserva el suyo.

### Riesgo de seguridad detectado en D1

`SecurityConfig.kt:47` hace `permitAll()` sobre `/api/v1/auth/**`. Un endpoint nuevo ahí nacería **público**. El plan de ejecución lo blinda con un matcher explícito colocado antes del `permitAll` **y** con un test que exige 401 sin autenticación — de modo que el error, si se comete, sale en rojo en vez de en producción.

---

## Tanda 3 — Deuda de cobertura

El grueso del volumen. **Aquí sí se justifica paralelizar con subagentes**: se reparte en 4 grupos de archivos disjuntos, igual que se hizo en la sesión anterior del review.

| Grupo | Ítem | Alcance |
|---|---|---|
| **a** | **A1** [Alto] | Invariantes del pipeline de oportunidades: `motivo_cierre` obligatorio al cerrar, guard de rol en `facturado`, `es_retroceso`, `MONTO_NO_EDITABLE`, `EstadoCarteraService`. Además **reescribir el test falso** de `OportunidadServiceImplTest.kt:176`, que afirma sobre los argumentos del mock en vez del comportamiento. |
| **b** | **D2** [Alto] | CRUD de empleados a nivel HTTP + regla B1.4: `listar()`, happy path de `crear()`, `EMAIL_DUPLICADO`, los `@PreAuthorize` de los tres endpoints de escritura. |
| **c** | **F2** (parte) [Alto] | Módulos sin un solo test: `modelos`, `financiadoras`, `catalogoeventos`. |
| **d** | **F2** (parte) + **E2** [Alto/🟡] | `reportes` y `prospeccion`. El SQL crudo agregado (~900 líneas) solo se cubre con `@Tag("integration")` → **no verificable en esta máquina**, solo en CI. |

**A1 es el de mayor valor**: es el núcleo del negocio y hoy no tiene una sola aserción.

---

## Tanda 4 — `F3` · Trinquete de Kover

`build.gradle.kts` — [Alto] 🟡 (la desinformación ya se corrigió; la brecha de cobertura no)

Subir el gate de `63/58` hacia el `75/90` que exige `TESTING-backend.md`.

**Va al final, obligatoriamente.** No es una tarea propia: es la consecuencia mecánica de la Tanda 3. Intentarlo antes solo pondría el build en rojo.

---

## Ejecución — 2026-08-07, sesión de Sonnet 5 vía subagentes

Las Tandas 2, 3 y 4 se ejecutaron en su totalidad siguiendo `docs/plan-ejecucion-subagentes.md`, en 2 olas:

- **Ola 1** (5 agentes en paralelo, worktrees aislados, archivos disjuntos): Agente A (empresas/RUC → B1), Agente B (auth/empleados → D1, D2), Agente C (oportunidades → F1, A1), Agente D (catálogos → F2 parte c), Agente E (reportes → F2 parte d, E2). Los 5 terminaron en verde. Al fusionar los worktrees al árbol principal se corrigió una única incompatibilidad esperable: el test nuevo del Agente C instanciaba `NotificacionServiceImpl` con el constructor de 2 parámetros porque su worktree partió de antes de la Tanda 1 (que le añadió `recordatorioEnviadoRepository`); se actualizó la llamada al constructor de 3 parámetros.
- **Ola 2** (2 agentes en paralelo, directo sobre el árbol ya fusionado): Agente F (documentación de contrato — aplicó los deltas de A y B a `contrato_api.md` §6/§8, reconcilió la contradicción con `reglas_negocio.md §2.1`, sincronizó `contrato_api.md §19` con los 16 valores reales de `TipoNotificacion`, y actualizó `code-review-pendientes.md`) y Agente G (trinquete de Kover — midió la cobertura real tras toda la Ola 1: 72.5% global / 68.3% dominio, y subió `minBound` a 71/67 con 1 punto de margen).

**Resultado:** `./gradlew test ktlintCheck detekt` en verde con todo integrado. Quedan abiertos únicamente: el hallazgo 🗄️ (bloqueado por Supabase, fuera de alcance) y el propio F3 como 🟡 — el trinquete subió pero la brecha real hasta 75/90 de dominio (23 puntos) sigue siendo la deuda de cobertura más grande del proyecto.

### Verificación adicional: smoke test end-to-end real (2026-08-10)

Los tests automatizados de B1 y D1 (Agentes A y B) pasaban en verde, pero mockeaban el repositorio — no ejercitan una sesión de Hibernate real. Antes del commit se levantó Postgres (`docker start quantum-crm-postgres`) y el backend (`./gradlew bootRun`), y se corrió `scripts/smoke-test-b1-d1.sh`: 16 escenarios HTTP reales con `curl` contra los endpoints nuevos/modificados.

**Encontró un bug real que ningún test unitario detectó:** la rama "RUC del mismo vendedor → 200" de B1 leía `segmentos` (colección `LAZY`) fuera de transacción y reventaba con `LazyInitializationException` → 500. Corregido en `EmpresaRepository.findByRuc` (fetch explícito de `segmentos`). Detalle completo en `code-review-pendientes.md`, sección B1. Reverificado: 16/16 en verde, y `./gradlew test ktlintCheck detekt` sigue en verde tras el fix.

El script es idempotente y limpia sus propios datos (empleados y empresa de prueba) al terminar; deja constancia de la carpeta de Drive real creada (no se borra automáticamente — hallazgo abierto, no de esta ronda).

Sin `git commit` en ningún punto de la ejecución previo, conforme a las reglas fijas del plan.

---

## Orden de ejecución

```
Tanda 1 (bugs)  →  Tanda 2 + Tanda 3 (Ola 1, en paralelo)  →  Tanda 4 (Ola 2)
   ✅ hecho              ✅ hecho — 2026-08-07                  ✅ hecho — 2026-08-07
```
