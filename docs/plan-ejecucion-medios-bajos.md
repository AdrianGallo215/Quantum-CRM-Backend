# Plan de ejecución — hallazgos [Medio] y [Bajo] del code review

> **Para quien ejecuta:** este documento contiene **tareas atómicas listas para ejecutar**. Cada una trae el test RED literal, el cambio de producción literal y su comando de verificación. **No hay que decidir nada: hay que ejecutar.** Si algo no encaja con lo que ves en el código, **para y repórtalo**; no improvises.

**Origen:** `docs/code-review-pendientes.md` (registro de hallazgos) y `docs/plan-correccion-code-review.md` (estrategia e historia de la ronda anterior).
**Escrito el:** 2026-08-11, con el repo en `main`, commit base `9ec40aa`, árbol limpio.

**Goal:** cerrar los 27 hallazgos accionables [Medio]/[Bajo] que quedan del code-review de corrección, con TDD estricto y sin tocar el esquema.

**Arquitectura del ataque:** 5 subagentes en paralelo sobre worktrees aislados y conjuntos de archivos **disjuntos** (A, C, D, E, F), más la sesión principal que se queda con dos grupos pequeños (empresas y transversales) porque dependen de decisiones de producto y porque el hallazgo transversal `G1` toca ficheros de todos los módulos. Al final, una verificación end-to-end contra Postgres real.

**Stack:** Kotlin 1.9 · Spring Boot 3.2 · Spring Data JPA · MockK · JUnit 5 · AssertJ · Gradle (Kotlin DSL) · JDK 21.

---

## 0. Estado base verificado (2026-08-11, antes de tocar nada)

| Comprobación | Resultado |
|---|---|
| Rama / commit | `main`, `9ec40aa` |
| `git status --porcelain` | **Limpio**, sin ficheros sin trackear |
| `./gradlew test ktlintCheck detekt --rerun-tasks` | ✅ **verde**, `15 actionable tasks: 15 executed`, `BUILD SUCCESSFUL in 3m 11s` |

> ⚠️ **La primera pasada salió `UP-TO-DATE` y no probaba nada.** Es la misma trampa que documentó la ronda anterior. Cuando quieras un baseline de verdad, usa `--rerun-tasks`. Para las verificaciones **durante** el trabajo no hace falta: tus cambios invalidan la caché de Gradle por sí solos.

**Aviso preexistente, NO es un hallazgo y NO se corrige aquí:** el compilador emite
`EmpresaServiceImpl.kt:538:22 Unnecessary safe call on a non-null receiver of type CriteriaQuery<*>`.
Es un warning, no un error; el build pasa. Si te lo encuentras, ignóralo.

---

## 1. Reglas invariables (violarlas invalida la tarea)

1. **TDD estricto.** Escribe el test, **ejecútalo y compruébalo en ROJO**, y solo entonces escribe el código de producción. Si el test pasa a la primera, está mal escrito: arréglalo hasta que falle por la razón correcta. Hay **dos tareas de solo-test** (`D.2` y `C.3`) donde esto no aplica; están marcadas explícitamente y llevan su propia instrucción.
2. **Verificación tras cada tarea:** `./gradlew test ktlintCheck detekt` en verde. No pases a la tarea siguiente con el build en rojo.
3. **Testcontainers está roto en esta máquina** (Docker 29, incompatibilidad real de versión). Los tests `@Tag("integration")` **NO se pueden ejecutar aquí**. Puedes escribirlos —y en varias tareas debes—, pero **declara explícitamente en tu informe que no los has ejecutado** y **nunca digas que pasan**. Solo corren en CI.
4. **Nada de `git commit` ni `git push`.** El dueño revisa y commitea.
5. **No toques el esquema** (`src/main/resources/db/migration/`). Los cambios de esquema los aplica el dueño a mano en Supabase.
6. **No reduzcas el alcance de una tarea.** Si algo se bloquea, termina el resto y repórtalo explícitamente.
7. Si detekt se queja de `LongMethod` o `CyclomaticComplexMethod` tras tu cambio, **extrae un método privado**; no añadas `@Suppress`. (Sí puedes usar `@Suppress` cuando esta guía te lo indique de forma explícita.)
8. **Comentarios en español y sin tildes en el código fuente**, siguiendo el estilo existente del repo (los KDoc del proyecto no llevan tildes). Los mensajes de error de cara al usuario **sí** llevan tildes.
9. **No toques `docs/contrato_api.md` ni `docs/code-review-pendientes.md`.** Son de la sesión principal. Si tu cambio afecta al contrato, escríbelo en tu informe final con el texto exacto que propones.

---

## 2. Global Constraints (aplican a TODA tarea)

Copiadas literalmente de `CLAUDE.md`; cada tarea las hereda:

- `monto_total` se calcula, **nunca** se acepta como input.
- `estado_cartera` solo se modifica vía `actualizarEstadoCartera()`.
- Los eventos **no** cambian el estado automáticamente: devuelven una sugerencia.
- `motivo_cierre` obligatorio cuando `estado = 'cerrado'`.
- El paso a `facturado` solo para `admin`, `gerencia`, `analista`.
- **No existe** el estado `perdido`. El enum `EstadoOportunidad` tiene 4 valores: `evaluacion_calidda`, `documentos_legales`, `facturado`, `cerrado`.
- Inyección por constructor (`private val`), nunca `@Autowired` en campos.
- Relaciones JPA siempre `LAZY`; nunca exponer entidades en controllers.
- `@Transactional(readOnly = true)` en lecturas, `@Transactional` en escrituras.
- Queries parametrizadas siempre.
- Un módulo nunca accede a tablas ni entidades de otro módulo (lo verifica ArchUnit en `./gradlew test`).
- IDOR: recurso ajeno → **404**, no 403.

---

## 3. Decisiones de producto ya tomadas (2026-08-11)

**No las vuelvas a plantear.** El dueño del proyecto respondió:

| Hallazgo | Decisión |
|---|---|
| **Eliminar empresa no borra la carpeta de Drive** | **Mover a papelera** (`trashed = true`), no borrado permanente. Reversible ~30 días. → tarea `B.3`, la hace la sesión principal. |
| **Guarda de último admin inalcanzable** | **Mantener + documentar.** Se conserva como defensa en profundidad con un comentario que explique por qué hoy no salta. → tarea `D.4`. |
| **Garantía de UTC en columnas TIMESTAMP** | **Guard de arranque que falle rápido** si la JVM no está en UTC. → tarea `G.3`, sesión principal. |
| **Verificación final** | **Postgres + backend, smoke test completo** con `curl` sobre los endpoints tocados, al final de todo. Lo hace la sesión principal. |

---

## 4. Mapa de reparto

```
OLA 1 (5 subagentes en paralelo, worktrees aislados, archivos disjuntos)
  ├── Agente A — oportunidades + N+1 de contactos     → 4 hallazgos
  ├── Agente C — tareas, eventos y jobs               → 4 hallazgos
  ├── Agente D — empleados, security y paginación     → 4 hallazgos
  ├── Agente E — reportes, prospección y metas        → 4 hallazgos
  └── Agente F — catálogos e import CSV               → 5 hallazgos
                    ↓ (los 5 en verde y fusionados al árbol principal)
OLA 2 (sesión principal, secuencial)
  ├── Grupo B — empresas                              → 3 hallazgos
  ├── Grupo G — transversales                         → 3 hallazgos
  ├── Actualización de contrato_api.md y del registro
  └── Smoke test end-to-end contra Postgres real
```

**Por qué 5 y no 7:** ya no hay [Alto] ni [Crítico]. Los grupos B (3 ítems, 1 fichero) y G (3 ítems, transversales) no justifican un agente: B depende de dos decisiones de producto y G **toca ficheros de test de todos los módulos**, así que lanzarlo en paralelo garantizaría colisiones. Van en la sesión principal, después de fusionar.

### Propiedad de archivos — CRÍTICO para el paralelismo

Cada fichero tiene **un solo dueño**. Si tu tarea te empuja a editar un fichero que no está en tu lista, **para y repórtalo**; no lo edites.

| Agente | Ficheros de los que es **dueño exclusivo** |
|---|---|
| **A** | `domain/oportunidades/OportunidadServiceImpl.kt`, `domain/oportunidades/OportunidadRepository.kt`, `domain/oportunidades/OportunidadesDeContacto.kt`, `domain/contactos/ContactoController.kt` · tests: `oportunidades/OportunidadListadoSpecificationTest.kt`, `oportunidades/OportunidadContactosTest.kt`, `oportunidades/OportunidadCambiarEstadoInvariantesTest.kt`, `oportunidades/OportunidadesDeContactoImplTest.kt`, `oportunidades/OportunidadServiceImplTest.kt`, `contactos/ContactoControllerWebMvcTest.kt` |
| **C** | `domain/tareas/TareaServiceImpl.kt`, `domain/tareas/TareaRepository.kt`, `domain/notificaciones/jobs/RecordatorioJob.kt`, `domain/notificaciones/jobs/LimpiezaNotificacionesJob.kt`, **nuevo** `shared/ZonaHoraria.kt` · tests: `notificaciones/jobs/RecordatorioJobTest.kt`, `notificaciones/jobs/LimpiezaNotificacionesJobTest.kt`, `eventos/EventoServiceImplTest.kt`, `tareas/TareaServiceImplTest.kt`, **nuevo** `notificaciones/NotificacionRepositoryPurgaIntegrationTest.kt`, **nuevo** `tareas/TareaRepositoryVentanaIntegrationTest.kt` |
| **D** | `domain/empleados/EmpleadoServiceImpl.kt`, `domain/empleados/AuthController.kt`, `config/security/LoginRateLimiter.kt` · tests: `empleados/EmpleadoServiceTest.kt`, `empleados/AuthControllerWebMvcTest.kt`, `config/security/LoginRateLimiterTest.kt`, `shared/PaginacionTest.kt` |
| **E** | `domain/reportes/ReporteService.kt`, `domain/prospeccion/ProspeccionDao.kt`, `domain/metasventa/MetaVentaServiceImpl.kt` · tests: `reportes/ReporteServiceSqlIntegrationTest.kt`, `reportes/ReporteAritmeticaTest.kt`, `prospeccion/ProspeccionDaoTest.kt`, `metasventa/MetaVentaServiceImplTest.kt` |
| **F** | `importcsvtemp/ImportCsvTempServiceImpl.kt`, `domain/modelos/ModeloServiceImpl.kt`, `domain/modelos/ModeloRepository.kt`, `domain/catalogoeventos/CatalogoEventoServiceImpl.kt`, `domain/catalogoeventos/CatalogoEventoRepository.kt`, `domain/financiadoras/FinanciadoraServiceImpl.kt`, `domain/financiadoras/FinanciadoraRepository.kt`, `shared/exception/NegocioExceptions.kt` · tests: `importcsvtemp/ImportCsvTempServiceImplTest.kt`, `modelos/ModeloServiceImplTest.kt`, `catalogoeventos/CatalogoEventoServiceImplTest.kt`, `financiadoras/FinanciadoraServiceImplTest.kt` |
| **Sesión principal (ola 2)** | `domain/empresas/EmpresaServiceImpl.kt`, `integracion/drive/DriveStorageService.kt`, `integracion/drive/DriveStorageServiceImpl.kt`, `shared/GlobalExceptionHandler.kt`, **nuevo** `shared/NombresDeCampo.kt`, **nuevo** `config/ZonaHorariaGuard.kt` · tests: `empresas/EmpresaServiceImplTest.kt`, `empresas/EmpresaBusquedaSpecificationTest.kt`, `empresas/EmpresaDriveControllerTest.kt`, `eventos/EventoControllerWebMvcTest.kt`, `integracion/drive/DriveStorageServiceImplTest.kt` · docs: `contrato_api.md`, `code-review-pendientes.md` |

**Ningún agente de la ola 1 toca `docs/`, `build.gradle.kts` ni ficheros de migración.**

---

## 5. Prompt de arranque para cada subagente

Cada agente recibe este preámbulo, más su sección:

> Trabajas en un worktree aislado del backend del CRM de Quantum (Kotlin + Spring Boot). Lee `CLAUDE.md` y las secciones **1, 2 y 3** de `docs/plan-ejecucion-medios-bajos.md` antes de tocar nada, y luego ejecuta **solo tu sección**, tarea por tarea y **en el orden en que están numeradas**.
> Reglas que no puedes romper: TDD estricto (test en ROJO antes del código), `./gradlew test ktlintCheck detekt` en verde tras cada tarea, sin `git commit`, sin tocar ficheros que no sean tuyos, y sin afirmar que un test `@Tag("integration")` pasa (no se pueden ejecutar aquí).
> Al terminar, entrega un informe con: qué tarea hiciste, qué test viste en rojo y por qué, qué tests `@Tag("integration")` escribiste **sin ejecutar**, qué ficheros tocaste, y qué deltas propones para `docs/contrato_api.md` (texto exacto, no lo edites tú).

**Dependencias de orden dentro de una sección** (no las reordenes):

| Agente | Dependencia |
|---|---|
| **C** | `C.2` introduce el `Clock` inyectable en `RecordatorioJob`; los tests de `C.4` lo usan. **C.2 antes que C.4.** |
| **E** | `E.3` y `E.4` añaden funciones top-level al mismo sitio de `ReporteService.kt`. **E.3 antes que E.4.** |
| **F** | `F.2` añade el parámetro `field` a `ConflictoException`; `F.3` lo usa. **F.2 antes que F.3.** |

En A y D las cuatro tareas son independientes entre sí, pero hazlas igualmente en orden para que cada `./gradlew test` acote qué acabas de romper.

---

# OLA 1

---

## Agente A — oportunidades (4 hallazgos)

> **Contexto que no debes re-derivar.** Estos cuatro hallazgos viven en el núcleo del pipeline. Tres son de comportamiento HTTP visible para el frontend y uno es de rendimiento. `OportunidadServiceImpl.kt` tiene 690 líneas y ya lleva `@Suppress("TooManyFunctions", "LongParameterList")` a nivel de clase: no lo quites.

### Tarea A.1 — `?estado=` inválido en `GET /oportunidades` debe dar 400, no 200 con todo

**Hallazgo:** [Medio] `OportunidadServiceImpl.kt:626-633`. `?estado=perdido` (estado que no existe) devuelve 200 con **todas** las oportunidades, **cerradas incluidas**, porque el `runCatching{}.getOrNull()?.let{}` se traga el valor y además desactiva la exclusión automática de cerradas. Inconsistente con `cambiarEstado`, que sí valida el mismo enum.

**Ficheros:**
- Modificar: `src/main/kotlin/pe/quantum/crm/domain/oportunidades/OportunidadServiceImpl.kt`
- Modificar (test): `src/test/kotlin/pe/quantum/crm/domain/oportunidades/OportunidadListadoSpecificationTest.kt`

> ⚠️ **Aviso crítico.** En ese fichero de test ya existe un test que **fija el bug** como si fuera el comportamiento deseado:
> ```kotlin
> /** Un estado que no existe en el enum se ignora: ni filtra ni reactiva la exclusion. */
> @Test
> fun `un estado desconocido no anade ningun predicado`() {
>     val compilada = listar(OportunidadFiltros(estado = "perdido"), admin)
>     assertThat(compilada.predicados).isZero()
> }
> ```
> **Ese test hay que sustituirlo**, no añadir otro al lado. Es exactamente el tipo de test que el review llamó "falso": documenta el defecto.

- [ ] **Paso 1: escribir el test que falla** — sustituye el test citado arriba por estos dos:

```kotlin
    /**
     * Un `?estado=` fuera del enum es un typo del cliente. Antes se ignoraba en
     * silencio y la respuesta salia 200 con TODO el pipeline, cerradas incluidas.
     */
    @Test
    fun `un estado desconocido devuelve 400 en vez de ignorarse`() {
        val ex = assertThrows<ValidacionException> { listar(OportunidadFiltros(estado = "perdido"), admin) }

        assertThat(ex.field).isEqualTo("estado")
        assertThat(ex.message).contains("cerrado")
    }

    /** Un valor en blanco no es un typo: no filtra, pero conserva la exclusion de cerradas. */
    @Test
    fun `un estado en blanco no filtra y conserva la exclusion de cerradas`() {
        val compilada = listar(OportunidadFiltros(estado = "   "), admin)

        assertThat(compilada.predicados).isEqualTo(1)
    }
```

Añade el import `org.junit.jupiter.api.assertThrows` (los demás ya están en el fichero).

- [ ] **Paso 2: verlo en ROJO**

Ejecuta: `./gradlew test --tests "*OportunidadListadoSpecificationTest*"`
Esperado: FALLA. `un estado desconocido...` con `Expected ValidacionException to be thrown, but nothing was thrown`, y `un estado en blanco...` con `expected: 1 but was: 0`.

- [ ] **Paso 3: implementar**

En `OportunidadServiceImpl.kt`, dentro de `listar(...)`, sustituye la línea del `findAll`:

```kotlin
        val pageRequest = Paginacion.pageRequest(page, perPage, sort, dir, CAMPOS_ORDENABLES)
        val estado = estadoFiltro(filtros.estado)
        val resultado = oportunidadRepository.findAll(especificacion(filtros, estado, usuario), pageRequest)
```

Cambia la firma y el cuerpo de `especificacion` (los dos bloques del `estado` son lo único que cambia):

```kotlin
    private fun especificacion(
        filtros: OportunidadFiltros,
        estado: EstadoOportunidad?,
        usuario: UsuarioActual,
    ): Specification<Oportunidad> =
        Specification { root, _, cb ->
            val predicados = mutableListOf<Predicate>()
            if (usuario.visibilidadRestringida) {
                predicados += cb.equal(root.get<Long>("idVendedor"), usuario.id)
            } else if (filtros.idVendedor != null) {
                predicados += cb.equal(root.get<Long>("idVendedor"), filtros.idVendedor)
            }
            estado?.let { predicados += cb.equal(root.get<EstadoOportunidad>("estado"), it) }
            if (estado == null && !filtros.incluirCerradas) {
                predicados += cb.notEqual(root.get<EstadoOportunidad>("estado"), EstadoOportunidad.cerrado)
            }
            filtros.idEmpresa?.let { predicados += cb.equal(root.get<Long>("idEmpresa"), it) }
            filtros.idFinanciadora?.let { predicados += cb.equal(root.get<Long>("idFinanciadora"), it) }
            cb.and(*predicados.toTypedArray())
        }
```

Y añade este privado justo encima de `especificacion`:

```kotlin
    /**
     * `?estado=` fuera del enum es un error del cliente (400), no un filtro que se
     * ignora: responder 200 con TODAS las oportunidades —cerradas incluidas— ante un
     * typo es peor que fallar. Mismo criterio que `cambiarEstado`, que ya valida
     * este mismo enum. Un valor en blanco se trata como ausencia de filtro.
     */
    private fun estadoFiltro(estado: String?): EstadoOportunidad? {
        val pedido = estado?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return runCatching { EstadoOportunidad.valueOf(pedido) }.getOrNull()
            ?: throw ValidacionException(
                "El estado '$pedido' no es válido. Estados permitidos: " +
                    EstadoOportunidad.values().joinToString(", ") { it.name },
                field = "estado",
            )
    }
```

`ValidacionException` ya está importado en el fichero.

- [ ] **Paso 4: verlo en VERDE**

`./gradlew test --tests "*OportunidadListadoSpecificationTest*"` → PASA.
Después `./gradlew test ktlintCheck detekt` → verde.

- [ ] **Paso 5: anotar el delta de contrato**

Para tu informe (no edites docs): *"`GET /oportunidades` con `estado` fuera del enum pasa de 200 (ignorado) a `400 VALIDACION` con `field: "estado"`."*

---

### Tarea A.2 — `cambiarEstado` debe bloquear la fila

**Hallazgo:** [Medio] `OportunidadServiceImpl.kt:229,261-273`. Dos `PATCH` concurrentes sobre la misma oportunidad leen el mismo `estado_anterior` y escriben **dos filas de log con el mismo origen**, corrompiendo el historial del que sale la pronta facturación.

**Ficheros:**
- Modificar: `domain/oportunidades/OportunidadRepository.kt`, `domain/oportunidades/OportunidadServiceImpl.kt`
- Modificar (test): `test/.../oportunidades/OportunidadCambiarEstadoInvariantesTest.kt`

> ⚠️ **Aviso crítico.** `cambiarEstado` usa hoy `visible(id, usuario)` → `entidad(id)` → `oportunidadRepository.findById(id)`. Al cambiarlo, **todos los tests que stubean `findById` para ejercitar `cambiarEstado` dejan de casar**. Antes de empezar ejecuta:
> ```bash
> grep -rn "cambiarEstado" src/test/kotlin/pe/quantum/crm/domain/oportunidades/
> ```
> y actualiza **todos** los stubs afectados de `findById` a `findByIdBloqueando` (que devuelve `Oportunidad?`, no `Optional`, así que el stub pasa de `returns java.util.Optional.of(op)` a `returns op`). Es mecánico pero obligatorio.

- [ ] **Paso 1: escribir el test que falla** — añade a `OportunidadCambiarEstadoInvariantesTest.kt`:

```kotlin
    /**
     * El bloqueo es lo que impide que dos PATCH simultaneos lean el mismo
     * `estado_anterior` y escriban dos filas de log con el mismo origen. No se puede
     * demostrar la concurrencia sin base de datos, pero si se puede fijar que el
     * camino de escritura pasa por el finder que bloquea y no por el que no.
     */
    @Test
    fun `cambiarEstado lee la oportunidad con el finder que bloquea la fila`() {
        val oportunidad = oportunidadEnEstado(EstadoOportunidad.evaluacion_calidda)
        every { oportunidadRepository.findByIdBloqueando(1) } returns oportunidad

        service.cambiarEstado(1, CambiarEstadoRequest(estado = "documentos_legales"), admin)

        verify(exactly = 1) { oportunidadRepository.findByIdBloqueando(1) }
        verify(exactly = 0) { oportunidadRepository.findById(any()) }
    }

    /** La anotacion ES el fix: sin ella la query no bloquea nada aunque el nombre lo sugiera. */
    @Test
    fun `el finder de cambiarEstado declara bloqueo pesimista de escritura`() {
        val metodo = OportunidadRepository::class.java.getMethod("findByIdBloqueando", Long::class.java)

        val lock = metodo.getAnnotation(org.springframework.data.jpa.repository.Lock::class.java)

        assertThat(lock).isNotNull
        assertThat(lock.value).isEqualTo(LockModeType.PESSIMISTIC_WRITE)
    }
```

Imports nuevos: `jakarta.persistence.LockModeType`.
`oportunidadEnEstado(...)` y `admin` son los helpers que **ya existen** en ese fichero; si se llaman de otra forma, usa los suyos — **no** inventes helpers nuevos.

- [ ] **Paso 2: verlo en ROJO**

`./gradlew test --tests "*OportunidadCambiarEstadoInvariantesTest*"`
Esperado: FALLA a nivel de **compilación** (`findByIdBloqueando` no existe). Eso cuenta como rojo.

- [ ] **Paso 3: implementar** — añade a `OportunidadRepository` (dentro de `interface OportunidadRepository`):

```kotlin
    /**
     * Oportunidad bloqueada para escritura (`SELECT ... FOR UPDATE`). La usa
     * `cambiarEstado`: dos PATCH concurrentes sobre la misma fila leian el mismo
     * `estado_anterior` y escribian dos filas de log con el mismo origen,
     * corrompiendo el historial del que sale la pronta facturacion.
     *
     * El bloqueo es corto y no envuelve ninguna llamada de red: la transaccion de
     * `cambiarEstado` no habla con Drive (a diferencia de `asegurarCarpetaDrive`,
     * que por eso resuelve la exclusion con un UPDATE condicional en vez de un lock).
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from Oportunidad o where o.id = :id")
    fun findByIdBloqueando(
        @Param("id") id: Long,
    ): Oportunidad?
```

Imports nuevos en ese fichero: `jakarta.persistence.LockModeType`, `org.springframework.data.jpa.repository.Lock`.

En `OportunidadServiceImpl.kt`, primera línea de `cambiarEstado`:

```kotlin
        val oportunidad = visibleBloqueando(id, usuario)
```

Y añade este privado justo debajo de `private fun visible(...)`:

```kotlin
    /**
     * Igual que [visible] pero tomando el lock de la fila. Cambiar de estado es la
     * unica operacion que lee el estado actual y escribe una fila de log derivada de
     * el: necesita serializarse contra otro PATCH simultaneo. La regla de visibilidad
     * se repite tal cual (IDOR: ajena → 404, no 403).
     */
    private fun visibleBloqueando(
        id: Long,
        usuario: UsuarioActual,
    ): Oportunidad {
        val oportunidad =
            oportunidadRepository.findByIdBloqueando(id)
                ?: throw NoEncontradoException("La oportunidad no existe")
        if (usuario.visibilidadRestringida && oportunidad.idVendedor != usuario.id) {
            throw NoEncontradoException("La oportunidad no existe")
        }
        return oportunidad
    }
```

- [ ] **Paso 4: arreglar los stubs existentes** — aplica el `grep` del aviso y migra cada stub de `cambiarEstado` a `findByIdBloqueando`.

- [ ] **Paso 5: verlo en VERDE** — `./gradlew test ktlintCheck detekt` → verde.

---

### Tarea A.3 — vincular dos veces el mismo contacto debe dar 409, no 201

**Hallazgo:** [Medio] `OportunidadServiceImpl.kt:369-374`. `POST /oportunidades/:id/contactos` sobre un vínculo existente hace un `save` sobre la misma clave compuesta → **UPDATE silencioso** que sobreescribe el rol anterior, y responde `201 Created` como si fuera nuevo.

**Ficheros:**
- Modificar: `domain/oportunidades/OportunidadServiceImpl.kt`
- Modificar (test): `test/.../oportunidades/OportunidadContactosTest.kt`

- [ ] **Paso 1: escribir el test que falla**

```kotlin
    /**
     * `save` sobre una clave compuesta que ya existe es un UPDATE: vincular dos veces
     * sobreescribia el rol anterior sin avisar y respondia 201 como si fuera un
     * vinculo nuevo. Cambiar el rol tiene su propio endpoint (PUT).
     */
    @Test
    fun `vincular un contacto ya vinculado devuelve 409 sin sobreescribir el rol`() {
        every { oportunidadRepository.findById(1) } returns java.util.Optional.of(oportunidad())
        every { contactoService.existe(7) } returns true
        every {
            contactoOportunidadRepository.existsById(OportunidadContactoId(idOportunidad = 1, idContacto = 7))
        } returns true

        val ex =
            assertThrows<ConflictoException> {
                service.vincularContacto(1, ContactoVinculoRequest(idContacto = 7, rolEnOportunidad = "Aprobador"), admin)
            }

        assertThat(ex.code).isEqualTo("CONTACTO_YA_VINCULADO")
        verify(exactly = 0) { contactoOportunidadRepository.save(any()) }
    }
```

`oportunidad()` y `admin` son los helpers que ya existen en ese fichero; usa los suyos. Imports que quizá falten: `pe.quantum.crm.shared.exception.ConflictoException`, `org.junit.jupiter.api.assertThrows`.

- [ ] **Paso 2: verlo en ROJO**

`./gradlew test --tests "*OportunidadContactosTest*"`
Esperado: FALLA con `Expected ConflictoException to be thrown, but nothing was thrown`.

- [ ] **Paso 3: implementar** — sustituye el cuerpo de `vincularContacto`:

```kotlin
    @Transactional
    override fun vincularContacto(
        id: Long,
        request: ContactoVinculoRequest,
        usuario: UsuarioActual,
    ): ContactoVinculoRequest {
        visible(id, usuario)
        if (!contactoService.existe(request.idContacto)) {
            throw NoEncontradoException("El contacto no existe")
        }
        val clave = OportunidadContactoId(idOportunidad = id, idContacto = request.idContacto)
        // `save` sobre una clave existente es un UPDATE: vincular dos veces
        // sobreescribia el rol anterior en silencio y devolvia 201 como si fuera un
        // vinculo nuevo. Cambiar el rol es otra operacion, con su propio endpoint.
        if (contactoOportunidadRepository.existsById(clave)) {
            throw ConflictoException(
                "CONTACTO_YA_VINCULADO",
                "El contacto ya está vinculado a esta oportunidad; usa PUT para cambiar su rol",
            )
        }
        contactoOportunidadRepository.save(
            OportunidadContacto(id = clave, rolEnOportunidad = request.rolEnOportunidad),
        )
        return request
    }
```

`ConflictoException` ya está importado en el fichero.

- [ ] **Paso 4: verlo en VERDE** — `./gradlew test ktlintCheck detekt` → verde.

- [ ] **Paso 5: anotar el delta de contrato**

*"`POST /oportunidades/:id/contactos` sobre un contacto ya vinculado pasa de `201` (con UPDATE silencioso del rol) a `409 CONTACTO_YA_VINCULADO`."*

---

### Tarea A.4 — quitar el N+1 de `GET /contactos`

**Hallazgo:** [Medio] `ContactoController.kt:47`. `oportunidadesDeContacto.contar(...)` se llama **una vez por fila** del listado: hasta ~101 consultas por página con `per_page=100`.

**Ficheros:**
- Modificar: `domain/oportunidades/OportunidadRepository.kt`, `domain/oportunidades/OportunidadesDeContacto.kt`, `domain/contactos/ContactoController.kt`
- Modificar (test): `test/.../oportunidades/OportunidadesDeContactoImplTest.kt`

- [ ] **Paso 1: escribir el test que falla** — añade a `OportunidadesDeContactoImplTest.kt`:

```kotlin
    /**
     * El listado de contactos pedia el conteo fila a fila (~101 consultas con
     * per_page=100). El conteo por lote conserva exactamente la misma regla de
     * visibilidad: `filtroVendedor` null = supervisor, cuenta todas.
     */
    @Test
    fun `contarPorContactos resuelve toda la pagina en una sola consulta`() {
        every { contactoOportunidadRepository.contarVisiblesPorContactos(setOf(7L, 8L, 9L), null) } returns
            listOf(conteo(7, 2), conteo(9, 5))

        val conteos = service.contarPorContactos(listOf(7, 8, 9), UsuarioActual(id = 1, rol = "admin"))

        assertThat(conteos).containsExactlyInAnyOrderEntriesOf(mapOf(7L to 2, 9L to 5))
        verify(exactly = 1) { contactoOportunidadRepository.contarVisiblesPorContactos(any(), any()) }
    }

    @Test
    fun `contarPorContactos con la lista vacia no consulta nada`() {
        val conteos = service.contarPorContactos(emptyList(), UsuarioActual(id = 1, rol = "admin"))

        assertThat(conteos).isEmpty()
        verify(exactly = 0) { contactoOportunidadRepository.contarVisiblesPorContactos(any(), any()) }
    }

    @Test
    fun `un vendedor solo cuenta las oportunidades que alcanza`() {
        every { contactoOportunidadRepository.contarVisiblesPorContactos(setOf(7L), 3L) } returns listOf(conteo(7, 1))

        val conteos = service.contarPorContactos(listOf(7), UsuarioActual(id = 3, rol = "vendedor"))

        assertThat(conteos).isEqualTo(mapOf(7L to 1))
    }

    private fun conteo(
        idContacto: Long,
        total: Long,
    ) = object : ConteoPorContacto {
        override val idContacto = idContacto
        override val total = total
    }
```

`contactoOportunidadRepository` y `service` son los que ya existen en ese fichero.

- [ ] **Paso 2: verlo en ROJO** — `./gradlew test --tests "*OportunidadesDeContactoImplTest*"` → falla al compilar (`ConteoPorContacto` y `contarVisiblesPorContactos` no existen).

- [ ] **Paso 3: implementar** — en `OportunidadRepository.kt`, añade a nivel de fichero (fuera de las interfaces):

```kotlin
/** Proyeccion del conteo por lote del listado de contactos. */
interface ConteoPorContacto {
    val idContacto: Long
    val total: Long
}
```

y dentro de `interface OportunidadContactoRepository`:

```kotlin
    /**
     * Conteo por lote para el listado de contactos: misma regla de visibilidad que
     * [countVisiblesPorContacto], pero UNA consulta para toda la pagina en vez de una
     * por fila. Los contactos sin ninguna oportunidad visible no salen en el
     * resultado; el llamador los resuelve a 0.
     */
    @Query(
        "select oc.id.idContacto as idContacto, count(oc) as total " +
            "from OportunidadContacto oc, Oportunidad o " +
            "where o.id = oc.id.idOportunidad and oc.id.idContacto in :idsContacto " +
            "and (:idVendedor is null or o.idVendedor = :idVendedor) " +
            "group by oc.id.idContacto",
    )
    fun contarVisiblesPorContactos(
        @Param("idsContacto") idsContacto: Collection<Long>,
        @Param("idVendedor") idVendedor: Long?,
    ): List<ConteoPorContacto>
```

En `OportunidadesDeContacto.kt`, añade a la **interfaz**:

```kotlin
    /** Conteo por lote del listado de contactos: una consulta para toda la pagina. */
    fun contarPorContactos(
        idsContacto: Collection<Long>,
        usuario: UsuarioActual,
    ): Map<Long, Int>
```

y al **impl**:

```kotlin
    @Transactional(readOnly = true)
    override fun contarPorContactos(
        idsContacto: Collection<Long>,
        usuario: UsuarioActual,
    ): Map<Long, Int> {
        if (idsContacto.isEmpty()) {
            return emptyMap()
        }
        return contactoOportunidadRepository
            .contarVisiblesPorContactos(idsContacto.toSet(), usuario.filtroVendedor)
            .associate { it.idContacto to it.total.toInt() }
    }
```

En `ContactoController.buscar`, sustituye la línea del `map`:

```kotlin
        val resultado = contactoService.buscar(q, idEmpresa, usuario, page, perPage, null, null)
        val conteos = oportunidadesDeContacto.contarPorContactos(resultado.items.map { it.id }, usuario)
        val conConteo = resultado.items.map { it.copy(oportunidadesCount = conteos[it.id] ?: 0) }
```

**No borres** `contar(...)`: la sigue usando el detalle. Solo el listado cambia.

- [ ] **Paso 4: verlo en VERDE** — `./gradlew test ktlintCheck detekt` → verde. Si `ContactoControllerWebMvcTest.kt` stubea `oportunidadesDeContacto.contar(...)` para el listado, actualízalo a `contarPorContactos(...)`.

---

## Agente C — tareas, eventos y jobs (4 hallazgos)

### Tarea C.1 — acotar en el tiempo el escaneo del job de recordatorios

**Hallazgo:** [Medio] `TareaServiceImpl.kt:309`. La query no tiene cota temporal: cada tarea vencida genera un `exists` por hora **indefinidamente**, aunque su recordatorio se enviara hace meses.

**Ficheros:**
- Modificar: `domain/tareas/TareaRepository.kt`, `domain/tareas/TareaServiceImpl.kt`
- Modificar (test): `test/.../tareas/TareaServiceImplTest.kt`
- Crear (test): `test/.../tareas/TareaRepositoryVentanaIntegrationTest.kt`

- [ ] **Paso 1: escribir el test que falla** — añade a `TareaServiceImplTest.kt`:

```kotlin
    /**
     * El job corre cada hora. Sin cota temporal escaneaba la tabla entera y lanzaba
     * un `exists` por cada tarea vencida indefinidamente. La ventana es [ahora-30d,
     * ahora+24h]: superconjunto estricto de lo que `RecordatorioJob.umbralTarea`
     * puede notificar, asi que no pierde ningun recordatorio real.
     */
    @Test
    fun `pendientesParaRecordatorio acota la consulta a la ventana notificable`() {
        val desde = slot<LocalDateTime>()
        val hasta = slot<LocalDateTime>()
        every {
            tareaRepository.findByEstadoAccionAndIdAsignadoIsNotNullAndFechaEjecucionBetween(
                EstadoAccion.pendiente,
                capture(desde),
                capture(hasta),
            )
        } returns emptyList()
        val ahora = LocalDateTime.now()

        service.pendientesParaRecordatorio()

        assertThat(Duration.between(desde.captured, ahora.minusDays(30)).abs()).isLessThan(Duration.ofMinutes(1))
        assertThat(Duration.between(hasta.captured, ahora.plusHours(24)).abs()).isLessThan(Duration.ofMinutes(1))
    }
```

Imports: `io.mockk.slot`, `java.time.Duration`, `java.time.LocalDateTime`, `pe.quantum.crm.shared.enums.EstadoAccion`.

- [ ] **Paso 2: verlo en ROJO** — `./gradlew test --tests "*TareaServiceImplTest*"` → falla al compilar (el método del repositorio no existe).

- [ ] **Paso 3: implementar** — en `TareaRepository`, **sustituye** `findByEstadoAccionAndIdAsignadoIsNotNullAndFechaEjecucionIsNotNull` por:

```kotlin
    /**
     * Tareas pendientes cuya fecha cae dentro de la ventana que el job puede
     * notificar. `Between` ya excluye los nulos, asi que sustituye tambien al
     * `FechaEjecucionIsNotNull` anterior.
     */
    fun findByEstadoAccionAndIdAsignadoIsNotNullAndFechaEjecucionBetween(
        estadoAccion: EstadoAccion,
        desde: LocalDateTime,
        hasta: LocalDateTime,
    ): List<Tarea>
```

Import nuevo: `java.time.LocalDateTime`.

En `TareaServiceImpl`, sustituye `pendientesParaRecordatorio`:

```kotlin
    @Transactional(readOnly = true)
    override fun pendientesParaRecordatorio(): List<TareaRecordatorioProyeccion> {
        val ahora = LocalDateTime.now()
        return tareaRepository
            .findByEstadoAccionAndIdAsignadoIsNotNullAndFechaEjecucionBetween(
                EstadoAccion.pendiente,
                ahora.minusDays(DIAS_VENCIDO_NOTIFICABLE),
                ahora.plusHours(HORAS_VENTANA_PROXIMO),
            ).map {
                TareaRecordatorioProyeccion(
                    id = requireNotNull(it.id),
                    idAsignado = requireNotNull(it.idAsignado),
                    idEmpresa = it.idEmpresa,
                    idOportunidad = it.idOportunidad,
                    fechaEjecucion = requireNotNull(it.fechaEjecucion),
                )
            }
    }
```

Y en el `private companion object` de `TareaServiceImpl` (créalo al final de la clase si no existe):

```kotlin
        /**
         * Mas alla de 30 dias vencida, el recordatorio ya se envio hace mucho y el
         * dedup permanente impide reenviarlo: seguir escaneandola es puro coste.
         */
        const val DIAS_VENCIDO_NOTIFICABLE = 30L

        /** Superconjunto del umbral `proximo` de `RecordatorioJob` (24 h). */
        const val HORAS_VENTANA_PROXIMO = 24L
```

- [ ] **Paso 4: verlo en VERDE** — `./gradlew test ktlintCheck detekt` → verde.

- [ ] **Paso 5: escribir el test de integración (NO ejecutable aquí)**

Crea `src/test/kotlin/pe/quantum/crm/domain/tareas/TareaRepositoryVentanaIntegrationTest.kt` siguiendo **exactamente** el patrón de `src/test/kotlin/pe/quantum/crm/domain/empleados/EmpleadoRepositoryIntegrationTest.kt` (léelo primero: `@Tag("integration")`, extiende `IntegrationTestBase`, usa `SeedFixtures`). Debe cubrir:
- una tarea pendiente con `fecha_ejecucion` de hace 2 días → **sí** sale;
- una tarea pendiente con `fecha_ejecucion` de hace 400 días → **no** sale;
- una tarea pendiente a 48 h vista → **no** sale;
- una tarea pendiente sin `fecha_ejecucion` → **no** sale (comprueba que `Between` excluye nulos).

En tu informe: **"escrito, NO ejecutado — Testcontainers roto en esta máquina; pendiente de CI"**.

---

### Tarea C.2 — los recordatorios de eventos deben usar el calendario de Lima

**Hallazgo:** [Medio] `RecordatorioJob.kt:65`. `LocalDate.now()` corre en UTC, pero `fecha_estimada` es un día del **calendario peruano**. A las 19:00 de Lima la fecha UTC ya es la del día siguiente: el vendedor recibe "evento vencido" con 5 horas de día hábil por delante y, por el dedup permanente, **nunca** se repite.

**Ficheros:**
- Crear: `src/main/kotlin/pe/quantum/crm/shared/ZonaHoraria.kt`
- Modificar: `domain/notificaciones/jobs/RecordatorioJob.kt`
- Modificar (test): `test/.../notificaciones/jobs/RecordatorioJobTest.kt`

- [ ] **Paso 1: escribir el test que falla** — añade a `RecordatorioJobTest.kt`:

```kotlin
    /**
     * Instante elegido a proposito: 2026-08-12T02:00Z son las 21:00 del 2026-08-11 en
     * Lima. Con el reloj en UTC el evento del dia 11 parecia vencido; en el
     * calendario real del vendedor aun le quedaban tres horas de ese dia.
     */
    @Test
    fun `un evento de hoy en Lima no se notifica como vencido aunque en UTC ya sea manana`() {
        val reloj = Clock.fixed(Instant.parse("2026-08-12T02:00:00Z"), ZoneOffset.UTC)
        val job = RecordatorioJob(tareaService, eventoService, oportunidadService, empresaService, envioRecordatorio, reloj)
        every { tareaService.pendientesParaRecordatorio() } returns emptyList()
        every { eventoService.pendientesParaRecordatorio() } returns
            listOf(
                EventoRecordatorioProyeccion(
                    id = 4,
                    idOportunidad = 50,
                    idEmpresa = null,
                    fechaEstimada = LocalDate.of(2026, 8, 11),
                ),
            )

        job.ejecutar()

        verify(exactly = 0) { notificacionService.notificar(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `un evento de manana en Lima si se notifica como proximo`() {
        val reloj = Clock.fixed(Instant.parse("2026-08-12T02:00:00Z"), ZoneOffset.UTC)
        val job = RecordatorioJob(tareaService, eventoService, oportunidadService, empresaService, envioRecordatorio, reloj)
        every { tareaService.pendientesParaRecordatorio() } returns emptyList()
        every { eventoService.pendientesParaRecordatorio() } returns
            listOf(
                EventoRecordatorioProyeccion(
                    id = 4,
                    idOportunidad = 50,
                    idEmpresa = null,
                    fechaEstimada = LocalDate.of(2026, 8, 12),
                ),
            )
        every {
            recordatorioEnviadoRepository.existsByOrigenAndIdOrigenAndUmbral(OrigenRecordatorio.evento, 4, UmbralRecordatorio.proximo)
        } returns false
        every { oportunidadService.datosRecordatorio(50) } returns OportunidadRecordatorioDatos(idEmpresa = 10, idVendedor = 3)
        every { empresaService.resumenPorIds(listOf(10)) } returns
            mapOf(10L to EmpresaResumen(id = 10, razonSocial = "Kincar S.A.C.", distrito = null))
        every { recordatorioEnviadoRepository.save(any()) } returns
            RecordatorioEnviado(origen = OrigenRecordatorio.evento, idOrigen = 4, umbral = UmbralRecordatorio.proximo)

        job.ejecutar()

        verify(exactly = 1) { notificacionService.notificar(any(), any(), any(), any(), any(), any()) }
    }
```

Imports nuevos: `java.time.Clock`, `java.time.Instant`, `java.time.ZoneOffset`.

- [ ] **Paso 2: verlo en ROJO** — `./gradlew test --tests "*RecordatorioJobTest*"` → falla al compilar (el constructor no acepta 6 argumentos).

- [ ] **Paso 3: implementar** — crea `src/main/kotlin/pe/quantum/crm/shared/ZonaHoraria.kt`:

```kotlin
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
```

En `RecordatorioJob`, añade el parámetro de reloj al constructor (mismo patrón que `LoginRateLimiter`, que ya inyecta un `Clock` con valor por defecto):

```kotlin
class RecordatorioJob(
    private val tareaService: TareaService,
    private val eventoService: EventoService,
    private val oportunidadService: OportunidadService,
    private val empresaService: EmpresaService,
    private val envioRecordatorio: EnvioRecordatorio,
    private val clock: Clock = Clock.systemUTC(),
) {
```

y sustituye las dos lecturas de la hora:

```kotlin
    private fun procesarTareas() {
        val ahora = LocalDateTime.now(clock)
```

```kotlin
    private fun procesarEventos() {
        // Dia del calendario del vendedor, no del servidor (ver ZONA_PERU).
        val hoy = LocalDate.now(clock.withZone(ZONA_PERU))
```

Imports nuevos: `java.time.Clock`, `pe.quantum.crm.shared.ZONA_PERU`.

- [ ] **Paso 4: verlo en VERDE** — `./gradlew test ktlintCheck detekt` → verde.

- [ ] **Paso 5: reportar el hueco adyacente que NO arreglas**

Al probar los umbrales verás que un evento con `fecha_estimada == hoy` **no genera ningún recordatorio** (`isBefore(hoy)` es falso y `== hoy.plusDays(1)` también). Es el comportamiento actual y **no lo cambies**: el aviso `proximo` salió ayer. Pero un evento creado hoy para hoy nunca recibe ninguno. **Anótalo en tu informe como hallazgo nuevo, no lo corrijas.**

---

### Tarea C.3 — test positivo de la invariante #4 (los eventos no cambian el estado)

**Hallazgo:** [Medio] `EventoServiceImplTest.kt:138`. Solo existe el test negativo (evento que **no** dispara). No hay ninguno que verifique el camino positivo (`sugerencia.dispara = true`) ni que afirme `verify(exactly = 0) { oportunidadService.cambiarEstado(...) }`. Hoy se puede borrar el guard y nada se pone rojo.

> ⚠️ **Tarea de SOLO TEST.** El código de producción ya es correcto. Aquí el entregable **es** el test. No hay fase roja: escríbelo y **debe pasar a la primera**. Si falla, has encontrado un bug real: **para y repórtalo antes de tocar producción.**

**Ficheros:** modificar `test/.../eventos/EventoServiceImplTest.kt`.

- [ ] **Paso 1: leer `private fun visible(...)` en `EventoServiceImpl.kt`**

Necesitas saber qué colaborador consulta para un evento **de oportunidad**. El test existente de la línea 141 (`marcar ocurrido un hito de empresa...`) stubea `empresaService.vinculoVisible`; para un evento con `idOportunidad` el stub correcto es `oportunidadService.vinculoVisible`. Confirma cuál antes de escribir.

- [ ] **Paso 2: escribir el test** — añade a `EventoServiceImplTest.kt`:

```kotlin
    /**
     * Invariante #4 de CLAUDE.md: marcar un evento como ocurrido devuelve la
     * SUGERENCIA y NO toca la oportunidad; el cambio es una segunda llamada
     * confirmada del usuario. Solo existia el test negativo (evento que no dispara),
     * asi que borrar el guard no ponia nada en rojo.
     */
    @Test
    fun `marcar ocurrido un evento que dispara cambio de estado sugiere sin cambiar la oportunidad`() {
        val evento =
            Evento(
                id = 7,
                idOportunidad = 50,
                idCatalogoEvento = 5,
                disparaCambioEstado = true,
                estadoDestino = EstadoOportunidad.documentos_legales,
                createdBy = 1,
                updatedBy = 1,
            )
        every { eventoRepository.findById(7) } returns java.util.Optional.of(evento)
        every { oportunidadService.vinculoVisible(50, usuario) } returns
            OportunidadVinculo(id = 50, idEmpresa = 10, idVendedor = 3, estado = "evaluacion_calidda")
        every { eventoRepository.save(evento) } returns evento

        val resultado = service.marcarOcurrido(7, MarcarOcurridoRequest(), usuario)

        assertThat(resultado.sugerencia).isNotNull
        assertThat(resultado.sugerencia?.dispara).isTrue()
        assertThat(resultado.sugerencia?.estadoDestino).isEqualTo("documentos_legales")
        verify(exactly = 0) { oportunidadService.cambiarEstado(any(), any(), any()) }
    }
```

Ajusta el stub de visibilidad a lo que hayas confirmado en el paso 1. `usuario` es el que ya existe en el fichero.

- [ ] **Paso 3: verlo en VERDE a la primera** — `./gradlew test ktlintCheck detekt` → verde.

---

### Tarea C.4 — bordes de los umbrales de recordatorio y criterio real de purga

**Hallazgo:** [Medio] `RecordatorioJobTest.kt:39` y `LimpiezaNotificacionesJobTest.kt:18`. Los umbrales solo están probados en el caso trivial (sin los bordes reales) y el test de limpieza afirma sobre el **argumento capturado del mock**, no sobre el criterio de borrado.

**Ficheros:**
- Modificar: `domain/notificaciones/jobs/LimpiezaNotificacionesJob.kt`
- Modificar (test): `test/.../notificaciones/jobs/RecordatorioJobTest.kt`, `test/.../notificaciones/jobs/LimpiezaNotificacionesJobTest.kt`
- Crear (test): `test/.../notificaciones/NotificacionRepositoryPurgaIntegrationTest.kt`

- [ ] **Paso 1: bordes del umbral de tareas (solo test, debe pasar a la primera)**

Añade a `RecordatorioJobTest.kt`. Usa el reloj fijo que ya introdujo la tarea C.2:

```kotlin
    private val instanteFijo = Instant.parse("2026-08-11T12:00:00Z")

    private fun jobCon(reloj: Clock) =
        RecordatorioJob(tareaService, eventoService, oportunidadService, empresaService, envioRecordatorio, reloj)

    /** Justo dentro de las 24 h: `!isAfter` hace que el borde exacto cuente como proximo. */
    @Test
    fun `una tarea exactamente a 24 horas cuenta como proxima`() {
        assertThat(umbralDeTareaA(Duration.ofHours(24))).isEqualTo(UmbralRecordatorio.proximo)
    }

    @Test
    fun `una tarea a 23h59m cuenta como proxima`() {
        assertThat(umbralDeTareaA(Duration.ofHours(23).plusMinutes(59))).isEqualTo(UmbralRecordatorio.proximo)
    }

    /** Un minuto mas alla del umbral no genera ningun recordatorio todavia. */
    @Test
    fun `una tarea a 24h01m no genera recordatorio`() {
        assertThat(umbralDeTareaA(Duration.ofHours(24).plusMinutes(1))).isNull()
    }

    @Test
    fun `una tarea vencida por un minuto cuenta como vencida`() {
        assertThat(umbralDeTareaA(Duration.ofMinutes(-1))).isEqualTo(UmbralRecordatorio.vencido)
    }

    /**
     * Ejercita el job completo y devuelve el umbral con el que se registro el
     * recordatorio, o null si no se envio ninguno. Afirmar sobre el umbral
     * persistido —y no sobre un calculo repetido en el test— es lo que hace que
     * estos bordes prueben el job y no a si mismos.
     */
    private fun umbralDeTareaA(desfase: Duration): UmbralRecordatorio? {
        val reloj = Clock.fixed(instanteFijo, ZoneOffset.UTC)
        val fecha = LocalDateTime.ofInstant(instanteFijo, ZoneOffset.UTC).plus(desfase)
        every { tareaService.pendientesParaRecordatorio() } returns
            listOf(TareaRecordatorioProyeccion(id = 1, idAsignado = 3, idEmpresa = 10, idOportunidad = null, fechaEjecucion = fecha))
        every { eventoService.pendientesParaRecordatorio() } returns emptyList()
        every { recordatorioEnviadoRepository.existsByOrigenAndIdOrigenAndUmbral(any(), any(), any()) } returns false
        every { empresaService.resumenPorIds(listOf(10)) } returns
            mapOf(10L to EmpresaResumen(id = 10, razonSocial = "Kincar S.A.C.", distrito = null))
        val guardado = slot<RecordatorioEnviado>()
        every { recordatorioEnviadoRepository.save(capture(guardado)) } answers { firstArg() }

        jobCon(reloj).ejecutar()

        return if (guardado.isCaptured) guardado.captured.umbral else null
    }
```

Imports: `io.mockk.slot`, `java.time.Duration`, `java.time.ZoneOffset`.

- [ ] **Paso 2: borde del destino nulo (solo test)**

```kotlin
    /**
     * La oportunidad del evento ya no existe (borrada mientras el evento seguia
     * pendiente). `destinoDe` devuelve null: el job debe saltarselo en silencio, sin
     * notificar y sin reventar el resto del barrido.
     */
    @Test
    fun `un evento cuya oportunidad ya no existe se ignora sin notificar`() {
        every { tareaService.pendientesParaRecordatorio() } returns emptyList()
        every { eventoService.pendientesParaRecordatorio() } returns
            listOf(EventoRecordatorioProyeccion(id = 4, idOportunidad = 50, idEmpresa = null, fechaEstimada = LocalDate.now().plusDays(1)))
        every { recordatorioEnviadoRepository.existsByOrigenAndIdOrigenAndUmbral(any(), any(), any()) } returns false
        every { oportunidadService.datosRecordatorio(50) } returns null

        job.ejecutar()

        verify(exactly = 0) { notificacionService.notificar(any(), any(), any(), any(), any(), any()) }
        verify(exactly = 0) { recordatorioEnviadoRepository.save(any()) }
    }
```

Ejecuta: `./gradlew test --tests "*RecordatorioJobTest*"` → **deben pasar todos a la primera**. Si alguno falla, es un bug real: **para y repórtalo**.

- [ ] **Paso 3: reloj inyectable en `LimpiezaNotificacionesJob`** (test en ROJO primero)

Sustituye el test único de `LimpiezaNotificacionesJobTest.kt` por:

```kotlin
    /**
     * El corte ES el criterio de purga. Con reloj fijo se puede afirmar el valor
     * exacto en vez de una franja de un minuto alrededor de `now()`, que pasaba
     * verde aunque la aritmetica se desviara.
     */
    @Test
    fun `el corte de purga son exactamente 30 dias antes de la ejecucion`() {
        val reloj = Clock.fixed(Instant.parse("2026-08-11T12:00:00Z"), ZoneOffset.UTC)
        val corte = slot<LocalDateTime>()
        every { notificacionRepository.purgarLeidasAntesDe(capture(corte)) } returns 3

        LimpiezaNotificacionesJob(notificacionRepository, reloj).ejecutar()

        assertThat(corte.captured).isEqualTo(LocalDateTime.of(2026, 7, 12, 12, 0, 0))
    }
```

Ejecuta `./gradlew test --tests "*LimpiezaNotificacionesJobTest*"` → **ROJO** (el constructor no acepta reloj).

Implementa en `LimpiezaNotificacionesJob.kt`: añade `private val clock: Clock = Clock.systemUTC()` como último parámetro del constructor y sustituye el `LocalDateTime.now()` del cálculo del corte por `LocalDateTime.now(clock)`. No cambies nada más.

Vuelve a ejecutar → VERDE.

- [ ] **Paso 4: test de integración del criterio real (NO ejecutable aquí)**

Crea `src/test/kotlin/pe/quantum/crm/domain/notificaciones/NotificacionRepositoryPurgaIntegrationTest.kt` siguiendo el patrón de `NotificacionRepositoryIntegrationTest.kt` (léelo primero). `purgarLeidasAntesDe(corte)` debe borrar **solo** las notificaciones leídas y anteriores al corte:
- leída y anterior al corte → **se borra**;
- leída y posterior al corte → **se conserva**;
- **no** leída y anterior al corte → **se conserva** (es el caso que el test de mock nunca podía ver);
- devuelve el número de filas borradas.

En tu informe: **"escrito, NO ejecutado — Testcontainers roto; pendiente de CI"**.

- [ ] **Paso 5:** `./gradlew test ktlintCheck detekt` → verde.

---

## Agente D — empleados, security y paginación (4 hallazgos)

### Tarea D.1 — `POST /auth/refresh` debe dar 401, no 404, si el empleado fue borrado

**Hallazgo:** [Medio] `AuthController.kt:80`. `empleadoService.porId(...)` lanza `NoEncontradoException` → 404. Un refresh token válido que apunta a un empleado borrado es una **credencial que ya no sirve** (401), no un recurso ausente; además el 404 le confirma al portador que ese id existió.

**Ficheros:** modificar `domain/empleados/AuthController.kt`; test en `test/.../empleados/AuthControllerWebMvcTest.kt`.

- [ ] **Paso 1: escribir el test que falla** — añade a `AuthControllerWebMvcTest.kt`:

```kotlin
    /**
     * Un refresh token valido cuyo empleado ya no existe es una credencial muerta
     * (401), no un recurso ausente (404). El 404 ademas confirmaba al portador del
     * token que ese id llego a existir.
     */
    @Test
    fun `refresh con un empleado ya borrado responde 401 y no 404`() {
        every { jwtService.validate("token-valido", TipoToken.REFRESH) } returns principalDe(99)
        every { empleadoService.porId(99) } throws NoEncontradoException("El empleado no existe")

        mockMvc
            .post("/api/v1/auth/refresh") {
                cookie(Cookie(AuthCookieFactory.REFRESH_TOKEN_COOKIE, "token-valido"))
            }.andExpect {
                status { isUnauthorized() }
                jsonPath("$.error.code") { value("CREDENCIALES_INVALIDAS") }
            }
    }
```

**Adapta la sintaxis al estilo del fichero**: si usa `mockMvc.perform(post(...))` en vez del DSL de Kotlin, escríbelo así. `principalDe(...)` es el helper que ya exista allí para construir el principal del JWT; si no existe, replica el stub del test de refresh que ya haya. **No inventes clases nuevas.**

- [ ] **Paso 2: verlo en ROJO** — `./gradlew test --tests "*AuthControllerWebMvcTest*"` → falla con `expected 401 but was 404`.

- [ ] **Paso 3: implementar** — en `AuthController.refresh`, sustituye la línea `val empleado = empleadoService.porId(principal.empleadoId)` por:

```kotlin
        val empleado =
            try {
                empleadoService.porId(principal.empleadoId)
            } catch (ex: NoEncontradoException) {
                // Token valido apuntando a un empleado que ya no existe: credencial
                // muerta (401), no recurso ausente (404). El 404 filtraba ademas que
                // ese id existio alguna vez.
                throw CredencialesInvalidasException()
            }
```

y anota el método con `@Suppress("SwallowedException")` (con el comentario de arriba justificándolo; es el mismo patrón que ya usa `ImportCsvTempServiceImpl`). Import nuevo: `pe.quantum.crm.shared.exception.NoEncontradoException`.

- [ ] **Paso 4: verlo en VERDE** — `./gradlew test ktlintCheck detekt` → verde.

- [ ] **Paso 5: delta de contrato** — *"`POST /auth/refresh` con token válido de un empleado borrado pasa de 404 a `401 CREDENCIALES_INVALIDAS`."*

---

### Tarea D.2 — `Paginacion.meta` necesita su propio test

**Hallazgo:** [Medio] `Paginacion.kt:86-93`. Fabrica el `total_pages` de **toda** la API paginada (6+ módulos) y ningún test la ejercita como sujeto. `PaginacionTest.kt` cubre el parseo de `sort`/`page`/`per_page`, nunca `meta`.

> ⚠️ **Tarea de SOLO TEST.** La aritmética se verificó correcta a mano. El entregable es la red de seguridad. **Debe pasar a la primera.** Si algún caso falla, has encontrado un off-by-one real: **para y repórtalo antes de tocar `Paginacion.kt`.**

**Ficheros:** modificar `test/.../shared/PaginacionTest.kt`.

- [ ] **Paso 1: escribir los tests**

```kotlin
    /**
     * `Paginacion.meta` fabrica el `total_pages` de toda la API paginada. Un
     * off-by-one aqui rompe la paginacion de seis modulos a la vez y en silencio: el
     * cliente deja de pedir la ultima pagina, o pide una que no existe.
     */
    @Test
    fun `sin resultados no hay ninguna pagina`() {
        assertThat(Paginacion.meta(page = 1, perPage = 20, total = 0).totalPages).isZero()
    }

    @Test
    fun `un solo resultado ocupa una pagina`() {
        assertThat(Paginacion.meta(page = 1, perPage = 20, total = 1).totalPages).isEqualTo(1)
    }

    /** Borde exacto: 20 de 20 caben en una pagina, no en dos. */
    @Test
    fun `un total que llena la pagina justo no abre una pagina de mas`() {
        assertThat(Paginacion.meta(page = 1, perPage = 20, total = 20).totalPages).isEqualTo(1)
    }

    /** Borde exacto por el otro lado: uno mas obliga a una segunda pagina. */
    @Test
    fun `un resultado por encima del tamano de pagina abre la segunda`() {
        assertThat(Paginacion.meta(page = 1, perPage = 20, total = 21).totalPages).isEqualTo(2)
    }

    @Test
    fun `el maximo de per_page tambien reparte bien`() {
        assertThat(Paginacion.meta(page = 1, perPage = 100, total = 100).totalPages).isEqualTo(1)
        assertThat(Paginacion.meta(page = 1, perPage = 100, total = 101).totalPages).isEqualTo(2)
    }

    @Test
    fun `page per_page y total se devuelven tal cual se recibieron`() {
        val meta = Paginacion.meta(page = 3, perPage = 25, total = 57)

        assertThat(meta.page).isEqualTo(3)
        assertThat(meta.perPage).isEqualTo(25)
        assertThat(meta.total).isEqualTo(57)
        assertThat(meta.totalPages).isEqualTo(3)
    }
```

- [ ] **Paso 2: verlos en VERDE a la primera** — `./gradlew test --tests "*PaginacionTest*"`. Si alguno falla → **para y reporta**.

- [ ] **Paso 3:** `./gradlew test ktlintCheck detekt` → verde.

---

### Tarea D.3 — el bloqueo de login no debe evaporarse por desalojo LRU

**Hallazgo:** [Medio] `LoginRateLimiter.kt:44`. El mapa es un LRU acotado a 10 000 entradas. Un flood con 10 000 emails distintos puede **desalojar la clave realmente atacada** antes de que expire su ventana de 15 min, levantándole el bloqueo. El desalojo mira la antigüedad de acceso, no si la clave está bloqueada.

**Ficheros:** modificar `config/security/LoginRateLimiter.kt`; test en `test/.../config/security/LoginRateLimiterTest.kt`.

- [ ] **Paso 1: escribir el test que falla** — añade a `LoginRateLimiterTest.kt`:

```kotlin
    /**
     * El LRU puro desalojaba por antiguedad de acceso sin mirar si la clave estaba
     * bloqueada: un flood de emails inventados expulsaba a la victima real y le
     * levantaba el bloqueo antes de que expirara su ventana.
     */
    @Test
    fun `un flood de claves nuevas no levanta el bloqueo de la clave atacada`() {
        val limiter = LoginRateLimiter(clock = reloj, maxEntries = 10)
        repeat(5) { limiter.recordFailure("victima@quantum.pe") }
        assertThat(limiter.isBlocked("victima@quantum.pe")).isTrue()

        repeat(200) { limiter.recordFailure("relleno-$it@quantum.pe") }

        assertThat(limiter.isBlocked("victima@quantum.pe")).isTrue()
    }

    /** La cota de memoria sigue siendo dura: proteger a las bloqueadas no puede volver el mapa ilimitado. */
    @Test
    fun `el mapa sigue acotado durante el flood`() {
        val limiter = LoginRateLimiter(clock = reloj, maxEntries = 10)

        repeat(200) { limiter.recordFailure("relleno-$it@quantum.pe") }

        assertThat(limiter.clavesEnSeguimiento()).isLessThanOrEqualTo(10)
    }

    /** Caso patologico: si TODAS las claves vigentes estan bloqueadas, la cota manda igual. */
    @Test
    fun `con todas las claves bloqueadas la cota de memoria sigue mandando`() {
        val limiter = LoginRateLimiter(clock = reloj, maxEntries = 10)

        repeat(50) { i -> repeat(5) { limiter.recordFailure("atacada-$i@quantum.pe") } }

        assertThat(limiter.clavesEnSeguimiento()).isLessThanOrEqualTo(10)
    }
```

`reloj` es el `Clock` de prueba que **ya existe** en ese fichero; usa su nombre real.

- [ ] **Paso 2: verlo en ROJO** — `./gradlew test --tests "*LoginRateLimiterTest*"` → el primer test falla (`expected true but was false`).

- [ ] **Paso 3: implementar** — en `LoginRateLimiter.kt`, sustituye la declaración del mapa:

```kotlin
    // Sin sincronizar por si mismo: todo acceso va dentro de `synchronized(byKey)`.
    // Modo acceso para que `acotar` desaloje por orden de uso; la cota se aplica a
    // mano en vez de con `removeEldestEntry` porque hay que respetar los bloqueos.
    private val byKey = LinkedHashMap<String, Attempts>(INITIAL_CAPACITY, LOAD_FACTOR, true)
```

Sustituye `recordFailure`:

```kotlin
    fun recordFailure(key: String) {
        val now = clock.instant()
        synchronized(byKey) {
            val existing = byKey[key]
            byKey[key] =
                if (existing == null || windowExpired(existing, now)) {
                    Attempts(count = 1, windowStart = now)
                } else {
                    existing.copy(count = existing.count + 1)
                }
            acotar(now)
        }
    }
```

Y añade este privado (junto a `windowExpired`):

```kotlin
    /**
     * Cota de memoria consciente del bloqueo. Se sacrifican primero las claves
     * caducadas y las que aun no alcanzaron el limite —las que no protegen nada— y
     * solo si todas las vigentes estan bloqueadas se cae al orden LRU, para que el
     * mapa siga acotado pase lo que pase. Se llama siempre dentro de
     * `synchronized(byKey)`.
     */
    private fun acotar(now: Instant) {
        if (byKey.size <= maxEntries) {
            return
        }
        val iterador = byKey.entries.iterator()
        while (byKey.size > maxEntries && iterador.hasNext()) {
            val entrada = iterador.next()
            if (windowExpired(entrada.value, now) || entrada.value.count < maxAttempts) {
                iterador.remove()
            }
        }
        while (byKey.size > maxEntries) {
            byKey.remove(byKey.keys.first())
        }
    }
```

Actualiza también el KDoc de la clase: el punto 2 ya no dice "se descarta la menos usada recientemente" sin matices. Sustituye ese punto por:

```
 * 2. Cota dura: el mapa es un LRU (`LinkedHashMap` en modo acceso) de `maxEntries`
 *    entradas. Al superarla se descartan primero las claves caducadas y las que aun
 *    no alcanzaron `maxAttempts`; una clave BLOQUEADA solo se desaloja si no queda
 *    ninguna otra candidata, porque desalojarla equivale a levantarle el bloqueo al
 *    atacante.
```

- [ ] **Paso 4: verlo en VERDE** — `./gradlew test ktlintCheck detekt` → verde.

---

### Tarea D.4 — documentar por qué la guarda de último admin no salta

**Hallazgo:** [Medio] `EmpleadoServiceImpl.kt:188-192`. No es un bug: es código inalcanzable tras el fix de revocación. **Decisión del dueño: mantener + documentar.**

**Ficheros:** modificar `domain/empleados/EmpleadoServiceImpl.kt`; test en `test/.../empleados/EmpleadoServiceTest.kt`.

- [ ] **Paso 1: escribir el test (solo test; debe pasar a la primera)**

```kotlin
    /**
     * Documenta por que `verificarNoUltimoAdmin` no salta hoy: `verificarSolicitanteVigente`
     * ya exige que quien pide la operacion sea OTRO admin activo, asi que al contar
     * los admins activos distintos del objetivo siempre queda al menos el
     * solicitante. La guarda se conserva como defensa en profundidad; si algun dia se
     * relaja esa revalidacion, este test es el aviso de que la regla B1.4 vuelve a
     * estar en juego.
     */
    @Test
    fun `desactivar al unico otro admin no salta ULTIMO_ADMIN porque el solicitante ya es admin activo`() {
        val solicitante = empleado(id = 1, rol = RolEmpleado.admin, activo = true)
        val objetivo = empleado(id = 2, rol = RolEmpleado.admin, activo = true)
        every { empleadoRepository.findById(1) } returns java.util.Optional.of(solicitante)
        every { empleadoRepository.findById(2) } returns java.util.Optional.of(objetivo)
        every { empleadoRepository.countByRolAndActivoTrueAndIdNot(RolEmpleado.admin, 2) } returns 1
        every { empleadoRepository.save(any()) } answers { firstArg() }

        val dto = service.cambiarActivo(id = 2, activo = false, idSolicitante = 1)

        assertThat(dto.activo).isFalse()
    }
```

Usa el helper `empleado(...)` que ya exista en el fichero; si tiene otra firma, adáptala. **No crees helpers nuevos si ya hay uno.**

- [ ] **Paso 2: verlo en VERDE a la primera.** Si falla, para y reporta.

- [ ] **Paso 3: documentar en producción** — sustituye el KDoc de `verificarNoUltimoAdmin` en `EmpleadoServiceImpl.kt`:

```kotlin
    /**
     * Regla B1.4: el sistema nunca se queda sin un admin activo.
     *
     * HOY ESTA GUARDA NO PUEDE SALTAR, y es a proposito: `verificarSolicitanteVigente`
     * corre antes en las tres operaciones que la invocan y ya exige que el solicitante
     * sea un admin activo distinto del objetivo (`actualizar` y `cambiarActivo`
     * prohiben ademas operar sobre uno mismo). Al contar admins activos con id
     * distinto del objetivo, el propio solicitante siempre cuenta: el contador nunca
     * llega a 0.
     *
     * No se retira porque es la ultima linea de la regla B1.4: si algun dia se relaja
     * la revalidacion del solicitante —o aparece una cuarta via de degradar admins—
     * esta guarda es lo unico que impide dejar el CRM sin administrador. Ver
     * `EmpleadoServiceTest`, que fija por que hoy no salta.
     */
```

- [ ] **Paso 4:** `./gradlew test ktlintCheck detekt` → verde.

---

## Agente E — reportes, prospección y metas (4 hallazgos)

> **Contexto que no debes re-derivar.** Tres de tus cuatro hallazgos viven en **SQL nativo agregado**, que no se puede cubrir con tests unitarios: mockear el `JdbcTemplate` solo probaría el mock. Van con `@Tag("integration")`, que **no puedes ejecutar aquí**. Extrae a funciones puras todo lo que se pueda probar en Kotlin — es lo que hace verificable tu trabajo en local.

### Tarea E.1 — `POST /metas-venta` de gerencia sobre una meta nueva dice "modificó"

**Hallazgo:** [Medio] `MetaVentaServiceImpl.kt:64`. Cuando gerencia/admin crea una meta que **no existía**, se notifica con `TipoNotificacion.meta_modificada` y el verbo "modificó". No corrompe números, pero envía un mensaje factualmente falso al vendedor y a gerencia.

**Ficheros:** modificar `domain/metasventa/MetaVentaServiceImpl.kt`; test en `test/.../metasventa/MetaVentaServiceImplTest.kt`.

- [ ] **Paso 1: escribir el test que falla**

```kotlin
    /**
     * Gerencia creando una meta que no existia no "modifico" nada. El mensaje llega
     * al vendedor y a quien la propuso: tiene que decir lo que de verdad paso.
     */
    @Test
    fun `gerencia creando una meta nueva notifica que la establecio, no que la modifico`() {
        every { metaVentaRepository.findByIdEmpleadoAndAnio(5, 2026) } returns null
        every { empleadoService.esAsignableComoVendedor(5) } returns true
        every { metaVentaRepository.save(any()) } answers { firstArg<MetaVenta>().conId(1) }
        every { empleadoService.resumenPorIds(any()) } returns
            mapOf(
                9L to EmpleadoResumen(id = 9, nombres = "Ana", apellidos = "Torres"),
                5L to EmpleadoResumen(id = 5, nombres = "Luis", apellidos = "Paz"),
            )

        service.crear(solicitudDeMeta(idEmpleado = 5, anio = 2026), UsuarioActual(id = 9, rol = "gerencia"))

        verify {
            notificacionService.notificar(
                destinatarios = any(),
                idActor = 9L,
                tipo = TipoNotificacion.meta_aprobada,
                mensaje = match { it.contains("estableció") && !it.contains("modificó") },
                entidadTipo = any(),
                entidadId = any(),
            )
        }
    }

    /** Sobre una meta que ya existia, "modificó" sigue siendo la palabra correcta. */
    @Test
    fun `gerencia sobre una meta existente sigue notificando que la modifico`() {
        every { metaVentaRepository.findByIdEmpleadoAndAnio(5, 2026) } returns metaExistente(idEmpleado = 5, anio = 2026)
        every { empleadoService.esAsignableComoVendedor(5) } returns true
        every { metaVentaRepository.save(any()) } answers { firstArg() }
        every { empleadoService.resumenPorIds(any()) } returns
            mapOf(
                9L to EmpleadoResumen(id = 9, nombres = "Ana", apellidos = "Torres"),
                5L to EmpleadoResumen(id = 5, nombres = "Luis", apellidos = "Paz"),
            )

        service.crear(solicitudDeMeta(idEmpleado = 5, anio = 2026), UsuarioActual(id = 9, rol = "gerencia"))

        verify {
            notificacionService.notificar(
                destinatarios = any(),
                idActor = 9L,
                tipo = TipoNotificacion.meta_modificada,
                mensaje = match { it.contains("modificó") },
                entidadTipo = any(),
                entidadId = any(),
            )
        }
    }
```

`solicitudDeMeta(...)`, `metaExistente(...)` y `conId(...)` son helpers: **reutiliza los que ya haya** en `MetaVentaServiceImplTest.kt` y adapta los nombres. Si no existen, créalos mínimos siguiendo el estilo del fichero.

- [ ] **Paso 2: verlo en ROJO** — `./gradlew test --tests "*MetaVentaServiceImplTest*"` → el primer test falla (se invoca con `meta_modificada` y "modificó").

- [ ] **Paso 3: implementar** — en `MetaVentaServiceImpl.crear`, sustituye el bloque `if (esGerenciaOAdmin) { ... }`:

```kotlin
        // Se resuelve ANTES de guardar: despues, `existente` y `meta` son la misma fila.
        val esNueva = existente == null
        if (esGerenciaOAdmin) {
            aprobarDirecto(meta, usuario)
            val guardada = metaVentaRepository.save(meta)
            // Una meta que no existia no se "modifico". El mensaje va al vendedor y a
            // quien la propuso: decir lo que de verdad paso no es cosmetica.
            if (esNueva) {
                notificarResolucion(guardada, usuario, TipoNotificacion.meta_aprobada, "estableció")
            } else {
                notificarResolucion(guardada, usuario, TipoNotificacion.meta_modificada, "modificó")
            }
            return toDtos(listOf(guardada)).first()
        }
```

- [ ] **Paso 4: verlo en VERDE** — `./gradlew test ktlintCheck detekt` → verde.

---

### Tarea E.2 — `dias_sin_actividad` no puede alimentarse de fechas futuras

**Hallazgo:** [Medio] `ProspeccionDao.kt:88`. `ultimaActividad` usa `COALESCE(fecha_ejecucion, updated_at)` sin cota superior. Una tarea completada con `fecha_ejecucion` **futura** da `dias_sin_actividad = 0` durante meses y **oculta empresas abandonadas** del indicador `requieren_atencion`. Los eventos tienen el mismo problema con `fecha_ocurrencia`.

**Ficheros:** modificar `domain/prospeccion/ProspeccionDao.kt`; tests en `test/.../prospeccion/ProspeccionDaoTest.kt`.

- [ ] **Paso 1: implementar el SQL** — sustituye el cuerpo de `ultimaActividad`:

```kotlin
    /**
     * Ultima actividad por empresa: MAX entre tareas completadas y eventos ocurridos
     * sin oportunidad (contrato §16).
     *
     * Dos cotas que antes no existian:
     * - Para las tareas, `LEAST(..., updated_at)`: `fecha_ejecucion` es la fecha
     *   AGENDADA y puede ser futura. La actividad real es, como muy tarde, el momento
     *   en que la tarea se marco completada (`updated_at`).
     * - Para todo, `f <= :ahora`: un `fecha_ocurrencia` futuro daba
     *   `dias_sin_actividad = 0` durante meses y sacaba a empresas abandonadas del
     *   indicador `requieren_atencion`, que es justo para lo que existe.
     */
    fun ultimaActividad(idsEmpresa: Collection<Long>): Map<Long, LocalDateTime> {
        if (idsEmpresa.isEmpty()) {
            return emptyMap()
        }
        val resultado = mutableMapOf<Long, LocalDateTime>()
        jdbc.query(
            """
            SELECT id_empresa, MAX(f) AS ultima
            FROM (
                SELECT id_empresa, LEAST(COALESCE(fecha_ejecucion, updated_at), updated_at) AS f
                FROM tareas
                WHERE id_empresa IN (:ids) AND id_oportunidad IS NULL AND estado_accion = 'completada'
                UNION ALL
                SELECT id_empresa, fecha_ocurrencia AS f
                FROM eventos
                WHERE id_empresa IN (:ids) AND id_oportunidad IS NULL AND estado = 'ocurrido'
            ) actividad
            WHERE f IS NOT NULL AND f <= :ahora
            GROUP BY id_empresa
            """.trimIndent(),
            MapSqlParameterSource("ids", idsEmpresa).addValue("ahora", LocalDateTime.now()),
        ) { rs ->
            rs.getTimestamp("ultima")?.let { resultado[rs.getLong("id_empresa")] = it.toLocalDateTime() }
        }
        return resultado
    }
```

- [ ] **Paso 2: test de mapeo en `ProspeccionDaoTest.kt`**

Ese fichero ya mockea en la frontera del driver. Añade un test que fije **los dos parámetros** de la consulta (`ids` y el nuevo `ahora`), porque el `ahora` **es** la cota:

```kotlin
    /** El parametro `ahora` ES la cota que impide que una fecha futura cuente como actividad. */
    @Test
    fun `ultimaActividad acota la consulta al instante actual`() {
        val params = slot<MapSqlParameterSource>()
        every { jdbc.query(any<String>(), capture(params), any<RowCallbackHandler>()) } returns Unit

        dao.ultimaActividad(listOf(1, 2))

        val ahora = params.captured.getValue("ahora") as LocalDateTime
        assertThat(Duration.between(ahora, LocalDateTime.now()).abs()).isLessThan(Duration.ofMinutes(1))
    }
```

**Adapta la firma del stub de `jdbc.query` a la que ya use ese fichero** (mira cómo stubea las demás consultas; la sobrecarga con `RowCallbackHandler` puede llamarse distinto en el mock existente).

- [ ] **Paso 3: verificar** — `./gradlew test ktlintCheck detekt` → verde.

- [ ] **Paso 4: test de integración (NO ejecutable aquí)**

Añade a `ReporteServiceSqlIntegrationTest.kt` —o crea `ProspeccionDaoSqlIntegrationTest.kt` con el mismo patrón— casos con Postgres real:
- tarea completada con `fecha_ejecucion` **futura** → no cuenta como actividad; la empresa aparece en `requieren_atencion`;
- tarea completada con `fecha_ejecucion` de hace 3 días → cuenta, y `dias_sin_actividad` = 3;
- evento ocurrido con `fecha_ocurrencia` futura → no cuenta.

En tu informe: **"escrito, NO ejecutado — Testcontainers roto; pendiente de CI"**.

---

### Tarea E.3 — los índices de hito del embudo no pueden salir de `ROW_NUMBER()`

**Hallazgo:** [Medio] `ReporteService.kt:425`. La posición del hito se calcula con `ROW_NUMBER() OVER (ORDER BY id)` sobre `catalogo_eventos`. Un cuarto hito nunca aparecería en el reporte, y **desactivar el hito 2 cambiaría retroactivamente el significado de `hito_2_completado`**: el 3 pasaría a numerarse 2 y los informes históricos dejarían de ser comparables.

**Ficheros:** modificar `domain/reportes/ReporteService.kt`; tests en `test/.../reportes/ReporteAritmeticaTest.kt`.

- [ ] **Paso 1: escribir el test que falla** — añade a `ReporteAritmeticaTest.kt`:

```kotlin
    /**
     * `ROW_NUMBER()` renumeraba: quitar un hito del catalogo movia a los siguientes y
     * cambiaba el significado de `hito_2_completado` en los informes ya emitidos.
     * Anclar la posicion al id del catalogo hace que quitar el hito 2 deje ese hueco
     * vacio en vez de rellenarlo con el 3.
     */
    @Test
    fun `las posiciones de hito se anclan a los tres primeros ids del catalogo`() {
        val posiciones = posicionesDeHito(listOf(10L, 20L, 30L, 40L))

        assertThat(posiciones).isEqualTo(mapOf(10L to 1, 20L to 2, 30L to 3))
    }

    /** Un cuarto hito existe en el catalogo pero el DTO solo tiene tres huecos: se ignora, no desplaza. */
    @Test
    fun `un cuarto hito no desplaza a los tres primeros`() {
        val posiciones = posicionesDeHito(listOf(10L, 20L, 30L, 40L))

        assertThat(posiciones).doesNotContainKey(40L)
    }

    @Test
    fun `con menos de tres hitos las posiciones sobrantes simplemente no existen`() {
        val posiciones = posicionesDeHito(listOf(10L, 20L))

        assertThat(posiciones).isEqualTo(mapOf(10L to 1, 20L to 2))
    }
```

- [ ] **Paso 2: verlo en ROJO** — `./gradlew test --tests "*ReporteAritmeticaTest*"` → falla al compilar (`posicionesDeHito` no existe).

- [ ] **Paso 3: implementar**

> **Dónde exactamente.** `ReporteService.kt` **ya tiene** el patrón que debes seguir: `internal fun promedio(valores: List<BigDecimal>): BigDecimal?` es una función **top-level** al final del fichero (línea ~555), fuera de la clase, y es así como `ReporteAritmeticaTest` la prueba hoy. El `companion object` de la clase es **privado** — **no lo toques ni lo hagas público.** Pon las funciones nuevas junto a `promedio`, como top-level `internal`.

Al final de `ReporteService.kt`, junto a `promedio`:

```kotlin
/** Huecos de hito que expone `ReporteProspeccionDto`: hito_1, hito_2, hito_3. */
private const val HITOS_EN_EL_EMBUDO = 3

/**
 * Posicion de cada hito en el embudo, anclada a su id de catalogo y no a su orden
 * relativo. `ROW_NUMBER()` renumeraba al quitar un hito: el 3 pasaba a ser el 2 y
 * `hito_2_completado` cambiaba de significado retroactivamente, con lo que los
 * informes ya emitidos dejaban de ser comparables. El DTO solo tiene tres huecos,
 * asi que un cuarto hito se ignora en vez de desplazar a los anteriores.
 */
internal fun posicionesDeHito(idsHitoOrdenados: List<Long>): Map<Long, Int> =
    idsHitoOrdenados.take(HITOS_EN_EL_EMBUDO).withIndex().associate { (indice, id) -> id to indice + 1 }
```

En `prospeccion(...)`, sustituye la segunda consulta (la del `ROW_NUMBER()`) por dos pasos: primero los ids ordenados del catálogo, luego los eventos crudos.

```kotlin
        // Hitos completados por empresa, en el orden del catalogo (1, 2, 3).
        val hitosPorEmpresa = mutableMapOf<Long, MutableSet<Int>>()
        if (rows.isNotEmpty()) {
            val idsHito =
                jdbc.query(
                    "SELECT id FROM catalogo_eventos WHERE es_hito_prospeccion = true ORDER BY id",
                    MapSqlParameterSource(),
                ) { rs, _ -> rs.getLong("id") }
            val posiciones = posicionesDeHito(idsHito)
            jdbc.query(
                """
                SELECT DISTINCT e.id_empresa, e.id_catalogo_evento
                FROM eventos e
                WHERE e.id_empresa IN (:ids) AND e.id_oportunidad IS NULL AND e.estado = 'ocurrido'
                  AND e.id_catalogo_evento IN (:idsHito)
                """.trimIndent(),
                MapSqlParameterSource("ids", rows.map { it.idEmpresa }).addValue("idsHito", posiciones.keys),
            ) { rs ->
                posiciones[rs.getLong("id_catalogo_evento")]?.let {
                    hitosPorEmpresa.getOrPut(rs.getLong("id_empresa")) { mutableSetOf() }.add(it)
                }
            }
        }
```

> ⚠️ Si `posiciones` está vacío (catálogo sin hitos), `IN (:idsHito)` con una colección vacía **revienta en Postgres**. Añade el guard: si `posiciones.isEmpty()`, sáltate la segunda consulta entera.

- [ ] **Paso 4: verlo en VERDE** — `./gradlew test ktlintCheck detekt` → verde.

- [ ] **Paso 5: test de integración (NO ejecutable aquí)**

Añade a `ReporteServiceSqlIntegrationTest.kt`: con 3 hitos en catálogo y una empresa que completó el 1 y el 3, el reporte da `hito1Completado = 1`, `hito2Completado = 0`, `hito3Completado = 1`. Declara que no lo ejecutaste.

---

### Tarea E.4 — `/reportes/descuentos` no puede contar "sin descuento" como 0 %

**Hallazgo:** [Medio] `ReporteService.kt:474,481`. Dos problemas: (a) `dcto` **NULL** entra en el promedio como `BigDecimal.ZERO`, es decir, "no sabemos" se cuenta como "0 %" y hunde la media; (b) el criterio `estado != 'cerrado'` descarta las cerradas, mientras `/reportes/ventas` no lo hace: dos endpoints dan dos respuestas distintas a "cuál es el descuento promedio".

> **Supuesto explícito que asumo yo (el planificador), no tú.** `NULL` = dato ausente, no descuento de cero → **se excluye del promedio**, pero sigue contando en `operacionesSinDcto`. Y se **quita** la exclusión de `cerrado`: un descuento concedido en una oportunidad que luego se cerró sí ocurrió. Ejecuta esto tal cual y **anótalo en tu informe** para que el dueño pueda vetarlo.

**Ficheros:** modificar `domain/reportes/ReporteService.kt`; tests en `test/.../reportes/ReporteAritmeticaTest.kt`.

- [ ] **Paso 1: escribir el test que falla** — añade a `ReporteAritmeticaTest.kt`:

```kotlin
    /**
     * NULL en `dcto` significa "no se registro descuento", no "descuento del 0 %".
     * Contarlo como cero hundia la media: con una operacion al 10 % y otra sin dato,
     * el informe decia 5 %.
     */
    @Test
    fun `el promedio de descuento ignora las operaciones sin dato`() {
        assertThat(promedioDeDescuentos(listOf(BigDecimal("10.00"), null))).isEqualByComparingTo(BigDecimal("10.00"))
    }

    /** Un 0 % explicito SI es un dato: se promedia. */
    @Test
    fun `un descuento de cero explicito si entra en el promedio`() {
        assertThat(promedioDeDescuentos(listOf(BigDecimal("10.00"), BigDecimal.ZERO))).isEqualByComparingTo(BigDecimal("5.00"))
    }

    @Test
    fun `sin ninguna operacion con dato el promedio es nulo, no cero`() {
        assertThat(promedioDeDescuentos(listOf(null, null))).isNull()
    }
```

- [ ] **Paso 2: verlo en ROJO** — falla al compilar (`promedioDeDescuentos` no existe).

- [ ] **Paso 3: implementar** — top-level `internal`, junto a `promedio` y a `posicionesDeHito` (mismo sitio que la tarea E.3). **No toques `promedio`**: sus tests actuales deben seguir pasando tal cual.

```kotlin
/**
 * Promedio de descuentos sobre las operaciones que TIENEN dato. `dcto` NULL es "no
 * se registro", no "0 %": contarlo como cero hundia la media y hacia que
 * `/reportes/descuentos` contradijera a `/reportes/ventas`. Un 0 % explicito si es
 * un dato y si promedia.
 */
internal fun promedioDeDescuentos(dctos: List<BigDecimal?>): BigDecimal? = promedio(dctos.filterNotNull())
```

Sustituye el cuerpo de `descuentos(...)`:

```kotlin
    @Transactional(readOnly = true)
    fun descuentos(periodo: PeriodoReporte): ReporteDescuentosDto {
        val rows =
            jdbc.query(
                """
                SELECT CONCAT(e.nombres, ' ', e.apellidos) AS vendedor, o.dcto
                FROM oportunidades o
                LEFT JOIN empleados e ON e.id = o.id_vendedor
                WHERE o.created_at >= :desde AND o.created_at < :hasta
                """.trimIndent(),
                parametros(periodo),
            ) { rs, _ ->
                DescuentoRow(vendedor = rs.getString("vendedor") ?: "Sin vendedor", dcto = rs.getBigDecimal("dcto"))
            }
        return ReporteDescuentosDto(
            dctoPromedioGlobal = promedioDeDescuentos(rows.map { it.dcto })?.toPlainString(),
            porVendedor =
                rows.groupBy { it.vendedor }.map { (vendedor, grupo) ->
                    val conDcto = grupo.filter { (it.dcto ?: BigDecimal.ZERO).signum() > 0 }
                    DescuentosPorVendedorDto(
                        vendedor = vendedor,
                        dctoPromedio = promedioDeDescuentos(grupo.map { it.dcto })?.toPlainString(),
                        operacionesSinDcto = grupo.size - conDcto.size,
                        operacionesConDcto = conDcto.size,
                        dctoMaximoAplicado = grupo.mapNotNull { it.dcto }.maxOrNull()?.toPlainString(),
                    )
                },
        )
    }
```

(El cambio en el SQL es **quitar** `o.estado != 'cerrado' AND`.)

- [ ] **Paso 4: verlo en VERDE** — `./gradlew test ktlintCheck detekt` → verde.

- [ ] **Paso 5: actualizar el test de integración existente**

`ReporteServiceSqlIntegrationTest.kt` tiene un test que **documenta el comportamiento actual frente a NULL** (lo dice su propio nombre). Reescríbelo para que afirme el comportamiento nuevo, y añade uno que compruebe que una oportunidad **cerrada** con descuento ya cuenta. No lo ejecutes; decláralo.

- [ ] **Paso 6: delta de contrato** — *"`GET /reportes/descuentos`: `dcto_promedio` pasa a calcularse solo sobre operaciones con `dcto` no nulo, y la población incluye ahora las oportunidades cerradas."*

---

## Agente F — catálogos e import CSV (5 hallazgos)

### Tarea F.1 — el parser CSV: saltos de línea, cabecera y números de fila

**Hallazgos (los tres se arreglan juntos porque son la misma etapa de lectura):**
- [Medio] `ImportCsvTempServiceImpl.kt:35,106-129` — un campo entrecomillado con salto de línea interno (lo que exporta Excel) produce **dos filas fantasma**, y con una columna de más **corrompe la razón social en silencio**.
- [Medio] `ImportCsvTempServiceImpl.kt:44` — la primera línea se descarta **siempre** como cabecera: un archivo exportado sin fila de títulos **pierde su primera empresa en silencio**.
- [Medio] `ImportCsvTempServiceImpl.kt:37,51` — los números de fila del reporte **no coinciden con el archivo** si hay líneas en blanco, lo que inutiliza el único entregable útil del import.

**Ficheros:** modificar `importcsvtemp/ImportCsvTempServiceImpl.kt`; tests en `test/.../importcsvtemp/ImportCsvTempServiceImplTest.kt`.

> ⚠️ **Aviso crítico.** `ImportCsvTempServiceImplTest.kt` es grande y **muchos de sus tests pasan un CSV con cabecera** contando con que la primera línea se descarte siempre. Tras el cambio, la cabecera solo se descarta si **no parece un dato** (su primera columna no es un RUC de 11 dígitos). Los tests cuya cabecera sea `ruc;razon_social;segmento` siguen funcionando. Ejecútalos todos y arregla los que no; **no relajes el criterio para que pasen**.

- [ ] **Paso 1: escribir los tests que fallan** — añade a `ImportCsvTempServiceImplTest.kt`:

```kotlin
    /**
     * Excel exporta asi en cuanto una razon social lleva un salto de linea. Antes el
     * registro se partia en dos filas fantasma con errores incomprensibles.
     */
    @Test
    fun `un salto de linea dentro de un campo entrecomillado no parte el registro`() {
        val slot = slot<CrearEmpresaRequest>()
        every { empresaService.crearSinCarpetaDrive(capture(slot), any()) } returns empresaDetalle()

        val resultado =
            service.importarEmpresas(
                csv("ruc;razon_social;segmento\n20999999999;\"Transportes\nUnidos S.A.C.\";urbano"),
                usuario,
            )

        assertThat(resultado.totalFilas).isEqualTo(1)
        assertThat(resultado.creadas).isEqualTo(1)
        assertThat(slot.captured.razonSocial).isEqualTo("Transportes\nUnidos S.A.C.")
    }

    /**
     * Un archivo exportado sin fila de titulos perdia su primera empresa en silencio.
     * La cabecera se reconoce por no ser un dato: su primera columna no es un RUC.
     */
    @Test
    fun `un archivo sin cabecera no pierde su primera empresa`() {
        every { empresaService.crearSinCarpetaDrive(any(), any()) } returns empresaDetalle()

        val resultado = service.importarEmpresas(csv("20999999999;Kincar S.A.C.;urbano\n20888888888;Otra S.A.;urbano"), usuario)

        assertThat(resultado.totalFilas).isEqualTo(2)
        assertThat(resultado.creadas).isEqualTo(2)
    }

    @Test
    fun `una cabecera de verdad se sigue descartando`() {
        every { empresaService.crearSinCarpetaDrive(any(), any()) } returns empresaDetalle()

        val resultado = service.importarEmpresas(csv("ruc;razon_social;segmento\n20999999999;Kincar S.A.C.;urbano"), usuario)

        assertThat(resultado.totalFilas).isEqualTo(1)
    }

    /**
     * El reporte de errores es el unico entregable util del import: si sus numeros de
     * fila no casan con lo que el usuario ve en Excel, no sirve para corregir nada.
     */
    @Test
    fun `los numeros de fila del reporte cuentan las lineas en blanco`() {
        every { empresaService.crearSinCarpetaDrive(any(), any()) } returns empresaDetalle()

        val resultado = service.importarEmpresas(csv("ruc;razon_social;segmento\n\n\n20999999999;Kincar S.A.C.;urbano\n\n123;Mala S.A.;urbano"), usuario)

        assertThat(resultado.detalle.map { it.fila }).containsExactly(4, 6)
    }
```

`csv(...)`, `empresaDetalle()` y `usuario` son los helpers que ya existen en el fichero; usa los suyos.

- [ ] **Paso 2: verlos en ROJO** — `./gradlew test --tests "*ImportCsvTempServiceImplTest*"` → los cuatro fallan.

- [ ] **Paso 3: implementar** — en `ImportCsvTempServiceImpl.kt`, sustituye la lectura y el troceo:

```kotlin
        val texto =
            try {
                archivo.inputStream.bufferedReader(Charsets.UTF_8).readText()
            } catch (ex: IOException) {
                throw ValidacionException("No se pudo leer el archivo CSV")
            }
        val registros = parsearRegistros(texto)
        if (registros.isEmpty()) {
            throw ValidacionException("El archivo CSV está vacío")
        }
        val filasDatos = if (esCabecera(registros.first())) registros.drop(1) else registros
        if (filasDatos.isEmpty()) {
            throw ValidacionException("El archivo CSV no tiene filas de datos, solo cabecera")
        }
        if (filasDatos.size > MAX_FILAS_DATOS) {
            throw ValidacionException("El archivo excede el máximo de $MAX_FILAS_DATOS filas de datos")
        }

        val detalle = filasDatos.map { procesarFila(fila = it.linea, campos = it.campos, usuario = usuario) }
```

Cambia la firma de `procesarFila` de `linea: String` a `campos: List<String>` y **borra** su primera línea (`val campos = parseCsvLine(linea)`). El resto del método no cambia.

**Sustituye** `parseCsvLine` por estas tres piezas:

```kotlin
    /** Un registro CSV con la linea FISICA (1-based) donde empieza: la que el usuario ve en Excel. */
    private data class FilaCsv(
        val linea: Int,
        val campos: List<String>,
    )

    /**
     * La primera fila es cabecera solo si NO parece un dato. Descartarla siempre
     * hacia que un archivo exportado sin fila de titulos perdiera su primera empresa
     * sin decir nada.
     */
    private fun esCabecera(fila: FilaCsv): Boolean = fila.campos.firstOrNull()?.trim()?.let { !RUC_REGEX.matches(it) } ?: false

    /**
     * Parte el texto en registros CSV. Delimitador `;`, comillas dobles escapadas por
     * duplicacion (`""`), y —esto es lo nuevo— un salto de linea DENTRO de un campo
     * entrecomillado NO separa registros: Excel exporta asi en cuanto una razon
     * social lleva un salto, y antes eso producia dos filas fantasma. Las lineas en
     * blanco se saltan pero SI se cuentan, para que los numeros del reporte de
     * errores casen con el archivo.
     */
    @Suppress("NestedBlockDepth", "CyclomaticComplexMethod") // Automata de un solo paso; partirlo dispersaria el estado.
    private fun parsearRegistros(texto: String): List<FilaCsv> {
        val filas = mutableListOf<FilaCsv>()
        val campos = mutableListOf<String>()
        val actual = StringBuilder()
        var dentroComillas = false
        var linea = 1
        var lineaInicio = 1
        var i = 0

        fun cerrarRegistro() {
            campos.add(actual.toString())
            actual.clear()
            if (campos.any { it.isNotBlank() }) {
                filas.add(FilaCsv(lineaInicio, campos.toList()))
            }
            campos.clear()
        }

        while (i < texto.length) {
            val c = texto[i]
            when {
                c == '"' && dentroComillas && i + 1 < texto.length && texto[i + 1] == '"' -> {
                    actual.append('"')
                    i++
                }
                c == '"' -> dentroComillas = !dentroComillas
                c == ';' && !dentroComillas -> {
                    campos.add(actual.toString())
                    actual.clear()
                }
                (c == '\n' || c == '\r') && !dentroComillas -> {
                    cerrarRegistro()
                    if (c == '\r' && i + 1 < texto.length && texto[i + 1] == '\n') {
                        i++
                    }
                    linea++
                    lineaInicio = linea
                }
                c == '\r' && dentroComillas -> Unit // `\r\n` interno se normaliza a `\n`
                else -> {
                    if (c == '\n') {
                        linea++
                    }
                    actual.append(c)
                }
            }
            i++
        }
        if (actual.isNotEmpty() || campos.isNotEmpty()) {
            cerrarRegistro()
        }
        return filas
    }
```

- [ ] **Paso 4: verlo en VERDE** — `./gradlew test --tests "*ImportCsvTempServiceImplTest*"` y luego `./gradlew test ktlintCheck detekt` → verde. Arregla los tests preexistentes que se rompan **sin relajar el criterio nuevo**.

---

### Tarea F.2 — código/nombre duplicado en `actualizar` debe dar el mismo error que en `crear`

**Hallazgo:** [Medio] `ModeloServiceImpl.kt:51`, `CatalogoEventoServiceImpl.kt:53`. La unicidad se valida en `crear` pero **no** en `actualizar`: cambiar el código de un modelo (o el nombre de un evento) a uno existente revienta contra la constraint y el frontend recibe `CONFLICTO_DATOS` genérico, con un código distinto al de creación y sin `field`.

**Ficheros:** modificar `shared/exception/NegocioExceptions.kt`, `domain/modelos/ModeloServiceImpl.kt`, `domain/modelos/ModeloRepository.kt`, `domain/catalogoeventos/CatalogoEventoServiceImpl.kt`, `domain/catalogoeventos/CatalogoEventoRepository.kt`; tests en `test/.../modelos/ModeloServiceImplTest.kt`, `test/.../catalogoeventos/CatalogoEventoServiceImplTest.kt`.

- [ ] **Paso 1: escribir los tests que fallan**

En `ModeloServiceImplTest.kt`:

```kotlin
    /**
     * La unicidad se validaba solo al crear. Al actualizar, el choque llegaba a la
     * constraint y salia como `CONFLICTO_DATOS` generico: otro codigo de error para
     * el mismo problema, y sin `field` con el que el frontend pueda marcar el input.
     */
    @Test
    fun `cambiar el codigo a uno que ya existe devuelve CODIGO_DUPLICADO`() {
        every { modeloRepository.findById(1) } returns java.util.Optional.of(modelo(id = 1, codigo = "KW-8"))
        every { modeloRepository.existsByCodigoAndIdNot("KW-12", 1) } returns true

        val ex = assertThrows<ConflictoException> { service.actualizar(1, ActualizarModeloRequest(codigo = "KW-12")) }

        assertThat(ex.code).isEqualTo("CODIGO_DUPLICADO")
        assertThat(ex.field).isEqualTo("codigo")
    }

    /** Reenviar su propio codigo no es un duplicado: es una actualizacion parcial normal. */
    @Test
    fun `reenviar el mismo codigo no dispara el conflicto`() {
        every { modeloRepository.findById(1) } returns java.util.Optional.of(modelo(id = 1, codigo = "KW-8"))
        every { modeloRepository.save(any()) } answers { firstArg() }

        service.actualizar(1, ActualizarModeloRequest(codigo = "KW-8"))

        verify(exactly = 0) { modeloRepository.existsByCodigoAndIdNot(any(), any()) }
    }
```

En `CatalogoEventoServiceImplTest.kt`, el equivalente con `NOMBRE_DUPLICADO`, `field = "nombre"` y `existsByNombreAndIdNot`.

`modelo(...)` es el helper que ya exista en el fichero; usa el suyo.

- [ ] **Paso 2: verlos en ROJO** — falla al compilar (los métodos del repositorio no existen).

- [ ] **Paso 3: implementar**

En `shared/exception/NegocioExceptions.kt`, añade el parámetro opcional a `ConflictoException` (compatible hacia atrás: todas las llamadas actuales siguen valiendo):

```kotlin
/** Conflicto de negocio generico (409) con codigo especifico y, opcionalmente, el campo que lo provoca. */
class ConflictoException(
    code: String,
    message: String,
    field: String? = null,
) : ApiException(code = code, message = message, status = HttpStatus.CONFLICT, field = field)
```

En `ModeloRepository`: `fun existsByCodigoAndIdNot(codigo: String, id: Long): Boolean`.
En `CatalogoEventoRepository`: `fun existsByNombreAndIdNot(nombre: String, id: Long): Boolean`.

En `ModeloServiceImpl.actualizar`, sustituye `request.codigo?.let { modelo.codigo = it }` por:

```kotlin
        request.codigo?.let {
            // Se valida en backend igual que en `crear`: dejarlo a la constraint
            // devolvia otro codigo de error para el mismo problema.
            if (it != modelo.codigo && modeloRepository.existsByCodigoAndIdNot(it, id)) {
                throw ConflictoException("CODIGO_DUPLICADO", "Ya existe un modelo con ese código", field = "codigo")
            }
            modelo.codigo = it
        }
```

En `CatalogoEventoServiceImpl.actualizar`, lo análogo para `request.nombre`:

```kotlin
        request.nombre?.let {
            if (it != evento.nombre && repository.existsByNombreAndIdNot(it, id)) {
                throw ConflictoException("NOMBRE_DUPLICADO", "Ya existe un evento del catálogo con ese nombre", field = "nombre")
            }
            evento.nombre = it
        }
```

Añade también `field = "codigo"` / `field = "nombre"` a los `ConflictoException` que ya lanza `crear` en ambos servicios, para que creación y actualización devuelvan exactamente lo mismo.

- [ ] **Paso 4: verlo en VERDE** — `./gradlew test ktlintCheck detekt` → verde.

- [ ] **Paso 5: delta de contrato** — *"`PUT /modelos/:id` y `PUT /catalogo-eventos/:id` devuelven ahora `409 CODIGO_DUPLICADO` / `NOMBRE_DUPLICADO` con `field`, en vez de `CONFLICTO_DATOS` sin campo."*

---

### Tarea F.3 — no se puede dejar el sistema sin financiadora default

**Hallazgo:** [Medio] `FinanciadoraServiceImpl.kt:59`. El caso "más de una default" está bien cubierto; el caso "pasar de una a cero" **no**. Desmarcar la única default rompe la creación de oportunidades sin `id_financiadora` explícito (lanza `FINANCIADORA_DEFAULT_INEXISTENTE`, un 500) hasta que alguien lo note.

> ⚠️ **Aviso crítico.** `FinanciadoraServiceImplTest.kt` tiene un test que **documenta el bug** como comportamiento actual ("desmarcar la default deja el sistema sin ninguna"). **Hay que reescribirlo**, no añadir otro al lado.

**Ficheros:** modificar `domain/financiadoras/FinanciadoraServiceImpl.kt`; test en `test/.../financiadoras/FinanciadoraServiceImplTest.kt`.

- [ ] **Paso 1: escribir el test que falla** — sustituye el test que documenta el bug por:

```kotlin
    /**
     * Quedarse sin default no da error en el momento: rompe DESPUES, en la creacion
     * de cualquier oportunidad sin `id_financiadora` explicito, y como un 500. Se
     * corta aqui, donde todavia se puede explicar.
     */
    @Test
    fun `desmarcar la unica default se rechaza`() {
        every { financiadoraRepository.findById(1) } returns java.util.Optional.of(financiadora(id = 1, esDefault = true))
        every { financiadoraRepository.existsByEsDefaultTrueAndIdNot(1) } returns false

        val ex = assertThrows<ConflictoException> { service.actualizar(1, ActualizarFinanciadoraRequest(esDefault = false)) }

        assertThat(ex.code).isEqualTo("FINANCIADORA_DEFAULT_REQUERIDA")
        assertThat(ex.field).isEqualTo("es_default")
        verify(exactly = 0) { financiadoraRepository.save(any()) }
    }

    /** Con otra ya marcada, desmarcar esta es legitimo: el sistema no se queda huerfano. */
    @Test
    fun `desmarcar la default cuando ya hay otra si se permite`() {
        every { financiadoraRepository.findById(1) } returns java.util.Optional.of(financiadora(id = 1, esDefault = true))
        every { financiadoraRepository.existsByEsDefaultTrueAndIdNot(1) } returns true
        every { financiadoraRepository.save(any()) } answers { firstArg() }

        val dto = service.actualizar(1, ActualizarFinanciadoraRequest(esDefault = false))

        assertThat(dto.esDefault).isFalse()
    }

    /** Desmarcar una que ya NO era default no puede dejar al sistema sin ninguna. */
    @Test
    fun `desmarcar una financiadora que no era default no comprueba nada`() {
        every { financiadoraRepository.findById(2) } returns java.util.Optional.of(financiadora(id = 2, esDefault = false))
        every { financiadoraRepository.save(any()) } answers { firstArg() }

        service.actualizar(2, ActualizarFinanciadoraRequest(esDefault = false))

        verify(exactly = 0) { financiadoraRepository.existsByEsDefaultTrueAndIdNot(any()) }
    }
```

`financiadora(...)` es el helper que ya exista; usa el suyo.

> Ojo con el orden de los stubs: `actualizar` ya llama a `existsByEsDefaultTrueAndIdNot(id)` en la rama `request.esDefault == true`. Aquí `esDefault` es `false`, así que esa rama no entra.

- [ ] **Paso 2: verlo en ROJO** — el primer test falla (`Expected ConflictoException ... but nothing was thrown`).

- [ ] **Paso 3: implementar** — en `FinanciadoraServiceImpl.actualizar`, sustituye `request.esDefault?.let { financiadora.esDefault = it }` por:

```kotlin
        request.esDefault?.let {
            // Pasar de una default a ninguna no falla aqui: falla despues, al crear
            // cualquier oportunidad sin `id_financiadora`, y como 500. Se corta donde
            // todavia se puede explicar el porque.
            if (!it && financiadora.esDefault && !financiadoraRepository.existsByEsDefaultTrueAndIdNot(id)) {
                throw ConflictoException(
                    "FINANCIADORA_DEFAULT_REQUERIDA",
                    "No se puede dejar el sistema sin financiadora default; marca otra antes de desmarcar esta",
                    field = "es_default",
                )
            }
            financiadora.esDefault = it
        }
```

Depende del `field` añadido en la tarea F.2: **haz F.2 antes que F.3.**

- [ ] **Paso 4: verlo en VERDE** — `./gradlew test ktlintCheck detekt` → verde.

- [ ] **Paso 5: delta de contrato** — *"`PUT /financiadoras/:id` con `es_default: false` sobre la única default devuelve `409 FINANCIADORA_DEFAULT_REQUERIDA`."*

---

# OLA 2 — sesión principal

> Estas tareas las ejecuta la sesión principal **después** de fusionar los cinco worktrees y comprobar `./gradlew test ktlintCheck detekt` en verde sobre el árbol integrado. No se reparten.

## Grupo B — empresas (3 hallazgos)

### Tarea B.1 — reasignar vendedor de una empresa en Cartera Maestra

**Hallazgo:** [Medio] `EmpresaServiceImpl.kt:303`. `PATCH .../vendedor` sobre una empresa con `en_cartera_maestra = true` viola el CHECK `chk_cartera_maestra_sin_vendedor` de V27 (`CHECK (NOT en_cartera_maestra OR id_vendedor IS NULL)`) → `DataIntegrityViolationException` → 409 genérico sin explicar por qué. Además falta fijar `updatedBy`.

Test RED en `EmpresaServiceImplTest.kt`: reasignar sobre una empresa con `enCarteraMaestra = true` lanza `ConflictoException` con código `EMPRESA_EN_CARTERA_MAESTRA`, y `save` no se invoca. Segundo test: la reasignación normal fija `updatedBy = usuario.id`.

Fix en `reasignarVendedor`, justo tras `val empresa = entidad(id)`:

```kotlin
        // El CHECK de V27 (chk_cartera_maestra_sin_vendedor) prohibe esta combinacion.
        // Dejarlo caer en la constraint daba un 409 generico que no decia que hacer:
        // hay que liberarla primero desde la cartera maestra.
        if (empresa.enCarteraMaestra) {
            throw ConflictoException(
                "EMPRESA_EN_CARTERA_MAESTRA",
                "La empresa está reservada en la cartera maestra; libérala antes de asignarle un vendedor",
            )
        }
```

y añadir `empresa.updatedBy = usuario.id` junto al `updatedAt` ya existente.

### Tarea B.2 — `estado_cartera` inválido en el filtro debe dar 400

**Hallazgo:** [Medio] `EmpresaServiceImpl.kt:463`. Mismo patrón que la tarea A.1: `?estado_cartera=perdido` devuelve todo sin filtrar en vez de 400.

Mismo tratamiento: resolver el enum en `listar(...)` con un privado `estadoCarteraFiltro(...)` que lance `ValidacionException(field = "estado_cartera")`, y pasar el valor ya resuelto a `especificacion`. Test RED en `EmpresaBusquedaSpecificationTest.kt`, que ya tiene el arnés de metamodelo real.

### Tarea B.3 — eliminar empresa manda su carpeta de Drive a la papelera

**Hallazgo:** [Medio]. **Decisión tomada: papelera, no borrado permanente.**

- `DriveStorageService`: método nuevo `fun enviarCarpetaAPapelera(folderId: String)`.
- `DriveStorageServiceImpl`: `drive.files().update(folderId, File().setTrashed(true)).setSupportsAllDrives(true).execute()`, envuelto en el helper `ejecutar(...)` que ya usa el resto de la clase.
- `EmpresaServiceImpl.eliminar`: capturar `driveFolderId` **antes** del `delete`, y enviar a papelera **después** de que la transacción confirme. Un fallo de Drive **no** debe revertir el borrado de la empresa: se loguea y sigue.

Tests: `DriveStorageServiceImplTest.kt` (se llama a `update` con `trashed = true`) y `EmpresaServiceImplTest.kt` (se envía a papelera la carpeta correcta; una empresa sin carpeta no llama a Drive; un fallo de Drive no propaga).

## Grupo G — transversales (3 hallazgos)

### Tarea G.1 — `error.field` en snake_case

**Hallazgo:** [Medio] `GlobalExceptionHandler.kt:60` (y el mismo patrón en la línea 112). `fieldError.field` es el nombre de la propiedad Kotlin y no pasa por la estrategia `SNAKE_CASE` de Jackson: el frontend no puede casar el `field` que recibe con el que envió. Afecta a todo 400 `VALIDACION` con campo compuesto.

Fichero nuevo `shared/NombresDeCampo.kt` con la conversión (segmento a segmento, para que `contactos[0].idContacto` → `contactos[0].id_contacto`), aplicada en `handleValidation` y en `handleConstraintViolation`.

**Radio de impacto medido:** solo un test existente afirma hoy un campo compuesto en camelCase — `EventoControllerWebMvcTest.kt:82` (`nombrePersonalizado` → pasa a `nombre_personalizado`). El resto (`email`, `nombres`, `dcto`, `cantidad`, `descripcion`, `id`, `sort`) son de una sola palabra y no cambian.

### Tarea G.2 — `EmpresaDriveControllerTest` es un falso positivo

**Hallazgo:** [Bajo] `EmpresaDriveControllerTest.kt:105`. Usa `MockMvcBuilders.standaloneSetup`, que **no** carga el `ObjectMapper` de la app: el test afirma `$.data.driveFolderId` en camelCase cuando el contrato real exige `drive_folder_id`. Pasaría igual si la serialización real se rompiera.

Fix: registrar en el `standaloneSetup` un `MappingJackson2HttpMessageConverter` con el `ObjectMapper` configurado igual que el de la app (`PropertyNamingStrategies.SNAKE_CASE` + `JavaTimeModule`), y corregir las aserciones a `drive_folder_id`. **Ese cambio de aserción es el test RED**: debe fallar antes de tocar el `setup`.

### Tarea G.3 — guard de arranque que exige UTC

**Hallazgo:** [Medio]. **Decisión tomada: fallar rápido.**

`config/ZonaHorariaGuard.kt`: componente que en `@PostConstruct` comprueba `ZoneId.systemDefault().rules.getOffset(Instant.now()) == ZoneOffset.UTC` y lanza `IllegalStateException` con un mensaje accionable si no. Se puede desactivar con una propiedad (`app.exigir-utc=false`) para no romper el arranque local de quien no quiera `-Duser.timezone=UTC`; el default es exigirlo.

Test: el guard pasa con `UTC` y lanza con `America/Lima`.

## Cierre

- Actualizar `docs/contrato_api.md` con los deltas de A.1, A.3, D.1, E.4, F.2, F.3, B.1, B.2 y G.1, y comunicarlos al equipo de frontend.
- Actualizar el estado de los 27 hallazgos en `docs/code-review-pendientes.md`.
- **Smoke test end-to-end** (aprobado por el dueño): `docker start quantum-crm-postgres`, `./gradlew bootRun`, y un script `curl` idempotente que ejercite los endpoints con comportamiento nuevo. **Avisar antes de arrancar nada.** Es lo que cazó el `LazyInitializationException` de la ronda anterior, invisible a los tests mockeados.

---

## Anexo — trazabilidad: los 27 hallazgos

| # | Hallazgo | Sev. | Tarea |
|---|---|---|---|
| 1 | `estado` inválido en `GET /oportunidades` | Medio | A.1 |
| 2 | `cambiarEstado` no bloquea la fila | Medio | A.2 |
| 3 | `POST` contacto duplicado → UPDATE + 201 | Medio | A.3 |
| 4 | N+1 en `GET /contactos` | Medio | A.4 |
| 5 | Job de recordatorios sin cota temporal | Medio | C.1 |
| 6 | `LocalDate.now()` UTC vs calendario peruano | Medio | C.2 |
| 7 | Invariante #4 sin test positivo | Medio | C.3 |
| 8 | Umbrales de `RecordatorioJob` sin bordes | Medio | C.4 |
| 9 | `LimpiezaNotificacionesJobTest` afirma sobre el mock | Medio | C.4 |
| 10 | `POST /auth/refresh` 404 en vez de 401 | Medio | D.1 |
| 11 | `Paginacion.meta` sin test propio | Medio | D.2 |
| 12 | `LoginRateLimiter` se evapora por desalojo LRU | Medio | D.3 |
| 13 | Guarda de último admin inalcanzable | Medio | D.4 |
| 14 | Meta nueva de gerencia notifica "modificó" | Medio | E.1 |
| 15 | `dias_sin_actividad` con fechas futuras | Medio | E.2 |
| 16 | Índices de hito por `ROW_NUMBER()` | Medio | E.3 |
| 17 | `/reportes/descuentos` cuenta NULL como 0 % | Medio | E.4 |
| 18 | Parser CSV sin saltos de línea entrecomillados | Medio | F.1 |
| 19 | Primera línea siempre descartada | Medio | F.1 |
| 20 | Números de fila desalineados | Medio | F.1 |
| 21 | Código/nombre duplicado en `actualizar` | Medio | F.2 |
| 22 | Sistema sin financiadora default | Medio | F.3 |
| 23 | Reasignar vendedor en Cartera Maestra | Medio | B.1 |
| 24 | `estado_cartera` inválido en el filtro | Medio | B.2 |
| 25 | Eliminar empresa no toca la carpeta de Drive | Medio | B.3 |
| 26 | `error.field` en camelCase | Medio | G.1 |
| 27 | `EmpresaDriveControllerTest` falso positivo | **Bajo** | G.2 |
| 28 | Garantía de UTC solo por `ENV TZ=UTC` | Medio | G.3 |

> Son 28 filas para 27 hallazgos porque `C.4` cierra dos (#8 y #9) y `F.1` cierra tres (#18, #19, #20). El 29º del registro —`POST /empresas` con columnas `NOT NULL`— es 🗄️, bloqueado por Supabase, y **no** entra en esta tanda.
