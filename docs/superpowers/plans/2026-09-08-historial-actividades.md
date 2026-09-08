# Historial de Actividades (tareas + eventos) — Implementation Plan

> **Para el ejecutor (Google Antigravity / modelos ligeros):** este plan está escrito para ejecutarse tarea por tarea, en orden estricto. Cada tarea termina con tests pasando y un commit. **No saltes tareas. No reordenes. No "optimices" el diseño.** Si algo no compila, el problema es tuyo, no del plan: vuelve a leer el paso.
>
> Los pasos usan checkbox (`- [ ]`) para seguimiento.

**Goal:** Dar a admin, gerencia y jdv una vista única de "historial de actividades" (tareas + eventos) de un empleado concreto, con comentarios de seguimiento y auditoría de quién editó qué.

**Architecture:** Se crea un módulo nuevo `domain/actividades/` que **compone** los módulos `tareas` y `eventos` a través de sus interfaces de servicio públicas (nunca sus entidades ni repositorios — lo verifica ArchUnit). Ese módulo aporta además dos tablas nuevas propias: `actividad_comentarios` (notas append-only) y `actividad_auditoria` (diff campo a campo de cada edición). Los módulos `tareas` y `eventos` solo se tocan para (a) exponer un método de listado por empleado y (b) llamar al servicio de auditoría al editar.

**Tech Stack:** Kotlin 1.9 · Spring Boot 3.2 · Spring Data JPA · Flyway · PostgreSQL 16 · JUnit 5 + MockK + AssertJ · Gradle Kotlin DSL · JDK 21.

**Spec:** `docs/requerimientos/2026-09-07-historial-actividades-jerarquico.json` (ticket) + el triage de esa misma sesión, cuyas 5 respuestas de producto quedan recogidas en "Decisiones de producto ya cerradas", abajo.

---

## Global Constraints

Estas reglas aplican a **todas** las tareas de este plan. No se repiten en cada una.

- **TDD obligatorio.** El test que falla se escribe ANTES del código. Ninguna tarea termina sin tests pasando (`CLAUDE.md` regla 1).
- **`./gradlew test` debe pasar antes de cada commit.** Si falla, arréglalo antes de commitear.
- **Hibernate NUNCA toca el schema.** `spring.jpa.hibernate.ddl-auto=validate`. Solo Flyway. Toda columna nueva va en una migración `V<n>__*.sql`.
- **Migraciones forward-only.** Nunca edites una migración ya existente. La última del repo es **V47**; las nuevas de este plan son **V48** y **V49**.
- **Inyección por constructor** (`private val`), nunca `@Autowired` en campos (regla 8).
- **Relaciones JPA siempre `LAZY`; nunca exponer entidades en controllers — siempre DTOs** (regla 9).
- **`@Transactional(readOnly = true)` en lecturas**, `@Transactional` en escrituras (regla 10).
- **Queries parametrizadas siempre.** Nunca SQL por concatenación (regla 11).
- **Un módulo nunca accede a entidades ni repositorios de otro módulo** (regla 12). Solo interfaces de servicio, DTOs (subpaquete `dto`), enums y eventos. Lo verifica `ArquitecturaModulosTest` dentro de `./gradlew test`.
- **IDOR: recurso ajeno → 404, no 403** (regla 14). Ojo al matiz de este plan, explicado abajo en "Permisos".
- **Formato:** `./gradlew ktlintFormat` antes de commitear; `./gradlew detekt` debe pasar.
- **Idioma del código:** comentarios y mensajes de error en español, sin tildes en los comentarios de código (el repo escribe `migracion`, `visibilidad`, no `migración`). Los mensajes de error de cara al usuario SÍ llevan tildes.
- **Tests de integración (`@Tag("integration")`) NO corren en local** — Testcontainers está roto con Docker 29 en esta máquina. Corren solo en CI. **Todos los tests que escribe este plan son unitarios y no necesitan Docker.**

---

## Decisiones de producto ya cerradas

Estas cinco respuestas vienen de producto y **no se re-discuten durante la implementación**:

| # | Pregunta | Respuesta cerrada |
|---|---|---|
| 1 | ¿Restringir a gerencia para que no vea vendedores? | **NO.** No se toca ningún permiso existente. Se agrega una vista filtrada sobre datos que estos roles ya pueden ver hoy. |
| 2 | ¿Qué campos son editables sobre actividad ajena? | **Lo mismo que hoy.** No se acota nada nuevo. La edición sigue funcionando exactamente igual. |
| 3 | ¿Comentarios: campo o tabla? | **Tabla aparte**, append-only. Nunca se sobrescribe `descripcion`. |
| 4 | ¿Auditoría de quién editó? | **Sí.** Tabla nueva, diff campo a campo. |
| 5 | ¿Filtros en la vista? | **Sí.** Por empleado (obligatorio), rango de fechas, empresa, oportunidad y tipo. |

---

## Fase de investigación — documentos y reglas que tocan este cambio

**Exigido por `CLAUDE.md` § "Cómo escribir un plan de implementación en este repo".** El ejecutor debe leer esta sección entera antes de la Task 1.

### Documentos de referencia que aplican

| Documento | Qué dice que afecta a este cambio |
|---|---|
| `docs/matriz_permisos.md` | §1 (tabla de visibilidad): **Tareas** — admin/gerencia/jdv ven *todas*; vendedor solo dueño/colaborador; analista/otro igual que vendedor (por colaboración). **Eventos** — admin/gerencia/jdv ven *todos*; vendedor solo los de sus oportunidades; analista/otro solo donde colaboran. §2.6 confirma que **solo admin/gerencia/jdv pueden asignar una tarea a otro empleado**. **Consecuencia para este plan:** la visibilidad amplia de los supervisores ya existe; este plan NO la amplía ni la reduce, solo agrega una forma de filtrarla. Hay que documentar el endpoint nuevo en este archivo. |
| `docs/contrato_api.md` | §12 `GET /tareas` ya expone `id_asignado` como filtro "solo admin/gerencia/jdv". §11 Eventos **no tiene listado global** — los eventos cuelgan de `/oportunidades/:id/eventos` y `/empresas/:id/eventos`. §28 es el changelog obligatorio. **Consecuencia:** el patrón de `id_asignado` ya existe y este plan lo replica en eventos en vez de inventar otro; y hay que agregar entrada a §28 en el mismo PR. |
| `docs/reglas_negocio.md` | §5.1 evento personalizado nunca dispara cambio de estado. §5.3 marcar un evento como ocurrido NUNCA cambia el estado de la oportunidad, solo devuelve una sugerencia. §10.2 tarea con `id_oportunidad NULL` es de prospección. **Consecuencia:** este plan es de solo-lectura + comentarios + auditoría; **no toca ninguna de estas reglas** y no debe introducir ningún camino que las eluda. |
| `CLAUDE.md` | Las 14 reglas, resumidas arriba en Global Constraints. |
| `docs/DEVOPS-backend.md` | §2 rama propia + TDD + gates + PR, nunca commit directo a `main`. §7/§9.2 el paso de restaurar dump de producción aplica **solo** a migraciones que alteran o borran datos existentes. |
| `src/main/resources/db/migration/` | **La verdad del schema.** Última migración: **V47**. `docs/schema.sql` y `docs/migrations/` están desactualizados (llegan a V19) — **no los uses como referencia**. |

### Reglas de `CLAUDE.md` que este cambio roza directamente

- **Regla 3 — `estado_cartera` solo vía `actualizarEstadoCartera()`.** Este plan no lo toca. Si al editar un evento aparece algún camino a `estado_cartera`, **para y pregunta**; no lo implementes.
- **Regla 4 — los eventos no cambian el estado automáticamente.** El historial es de lectura; no debe disparar ninguna transición.
- **Regla 9 — nunca exponer entidades en controllers.** Los DTOs nuevos son obligatorios.
- **Regla 12 — fronteras de módulo.** Es la restricción de diseño más fuerte de este plan y la razón de que exista un módulo `actividades` separado en lugar de meter la lógica dentro de `tareas`.
- **Regla 14 — IDOR → 404.** Ver el matiz en "Permisos", abajo.

---

## Permisos — la regla exacta de este plan

El endpoint nuevo `GET /api/v1/actividades`:

- Si `id_empleado == usuario.id` → **permitido para cualquier rol autenticado** (ver tu propio historial).
- Si `id_empleado != usuario.id` → **exige `usuario.esSupervisor`** (`admin`, `gerencia`, `jdv`). Si no lo es → `403 PERMISO_INSUFICIENTE`.

**Por qué 403 y no 404 aquí, si la regla 14 dice 404:** la regla 14 protege contra IDOR, es decir, contra revelar la *existencia de un recurso ajeno*. Aquí el recurso no es una actividad, es **el propio empleado**, cuya existencia ya es pública para todo usuario autenticado vía `GET /empleados` (selector de asignación, `contrato_api.md §7`). No hay nada que ocultar, y el patrón `403 PERMISO_INSUFICIENTE` es exactamente el que ya usa `validarPermisoAsignacion` en `TareaServiceImpl` para el caso gemelo ("no puedes operar sobre el empleado X"). **Usa 403. No cambies esto.**

---

## Las tres trampas de este repo que te van a morder

Léelas ahora; están citadas otra vez en la tarea donde aplican.

### Trampa 1 — `comoInstanteUtc()` está PROHIBIDO en columnas `DATE`

`src/main/kotlin/pe/quantum/crm/shared/TiempoUtc.kt:25-27` lo dice literalmente:

> NO usar sobre columnas `DATE` (`fecha_estimada`, `fecha_seguimiento`, `fecha_cierre_estimado`): son días del calendario de Lima, no instantes, y darles una hora reintroduce el mismo desfase por el otro lado.

`Evento.fechaEstimada` y `Evento.fechaSeguimiento` son `LocalDate` (columnas `DATE`). `Tarea.fechaEjecucion`, `Evento.fechaOcurrencia`, `createdAt` son `LocalDateTime` (columnas `TIMESTAMP`).

**Consecuencia de diseño, ya resuelta en este plan:** el DTO unificado NO colapsa las fechas en un solo campo `Instant`. Expone dos campos separados: `fechaHora: Instant?` (solo TIMESTAMP) y `fechaDia: LocalDate?` (solo DATE). Y **ordena y filtra por `createdAt`**, que siempre es TIMESTAMP y nunca es null en ninguna de las dos entidades.

### Trampa 2 — todo repositorio nuevo rompe los tests WebMvc si no lo registras

`src/test/kotlin/pe/quantum/crm/support/SinBaseDeDatosMocks.kt` declara un `@Bean` mock por cada repositorio JPA del proyecto. Los tests `@SpringBootTest` que excluyen DataSource/JPA/Flyway levantan el contexto completo, y si un servicio pide un repositorio que no está en esa clase, **el contexto no arranca y fallan todos los WebMvc tests del repo, no solo los tuyos**.

**Cada tarea que crea un repositorio incluye el paso de registrarlo ahí.** No lo saltes.

### Trampa 3 — `Evento` no tiene `idAsignado`

`Tarea` tiene `idAsignado` (el dueño). `Evento` **no**. Solo tiene `createdBy` (quién creó el registro) y `registradoPor` (quién lo marcó como ocurrido).

**Decisión de este plan, ya tomada:** "las actividades de un empleado" usa **`createdBy`** para eventos. Es el que corresponde a "esta persona registró esta actividad". **No uses `registradoPor`** — es null en la mayoría de eventos (solo se llena al marcar ocurrido) y dejaría el historial casi vacío.

---

## File Structure

### Se crean

| Archivo | Responsabilidad |
|---|---|
| `src/main/resources/db/migration/V48__create_actividad_comentarios.sql` | Tabla `actividad_comentarios`. |
| `src/main/resources/db/migration/V49__create_actividad_auditoria.sql` | Tabla `actividad_auditoria`. |
| `src/main/kotlin/pe/quantum/crm/domain/actividades/TipoActividad.kt` | Enum Kotlin `tarea | evento`. API pública del módulo. |
| `src/main/kotlin/pe/quantum/crm/domain/actividades/ActividadComentario.kt` | Entidad JPA de comentarios. |
| `src/main/kotlin/pe/quantum/crm/domain/actividades/ActividadComentarioRepository.kt` | Repositorio de comentarios. |
| `src/main/kotlin/pe/quantum/crm/domain/actividades/ActividadAuditoria.kt` | Entidad JPA de auditoría. |
| `src/main/kotlin/pe/quantum/crm/domain/actividades/ActividadAuditoriaRepository.kt` | Repositorio de auditoría. |
| `src/main/kotlin/pe/quantum/crm/domain/actividades/dto/ActividadDtos.kt` | Todos los DTOs del módulo. |
| `src/main/kotlin/pe/quantum/crm/domain/actividades/ComentarioActividadService.kt` | Interfaz pública de comentarios. |
| `src/main/kotlin/pe/quantum/crm/domain/actividades/ComentarioActividadServiceImpl.kt` | Implementación. |
| `src/main/kotlin/pe/quantum/crm/domain/actividades/AuditoriaActividadService.kt` | Interfaz pública de auditoría (la consumen `tareas` y `eventos`). |
| `src/main/kotlin/pe/quantum/crm/domain/actividades/AuditoriaActividadServiceImpl.kt` | Implementación. |
| `src/main/kotlin/pe/quantum/crm/domain/actividades/HistorialActividadService.kt` | Interfaz del historial unificado. |
| `src/main/kotlin/pe/quantum/crm/domain/actividades/HistorialActividadServiceImpl.kt` | Composición tareas+eventos, guard de permisos, merge, paginación. |
| `src/main/kotlin/pe/quantum/crm/domain/actividades/ActividadController.kt` | Los 4 endpoints nuevos. |

### Se modifican

| Archivo | Cambio |
|---|---|
| `src/main/kotlin/pe/quantum/crm/domain/tareas/TareaService.kt` | + `listarPorEmpleado(...)`. |
| `src/main/kotlin/pe/quantum/crm/domain/tareas/TareaServiceImpl.kt` | Implementa `listarPorEmpleado`; registra auditoría en `actualizar`. |
| `src/main/kotlin/pe/quantum/crm/domain/tareas/TareaRepository.kt` | + query por asignado y rango. |
| `src/main/kotlin/pe/quantum/crm/domain/eventos/EventoService.kt` | + `listarPorEmpleado(...)`. |
| `src/main/kotlin/pe/quantum/crm/domain/eventos/EventoServiceImpl.kt` | Implementa `listarPorEmpleado`; registra auditoría en `actualizar`. |
| `src/main/kotlin/pe/quantum/crm/domain/eventos/EventoRepository.kt` | + query por creador y rango. |
| `src/test/kotlin/pe/quantum/crm/support/SinBaseDeDatosMocks.kt` | + 2 beans mock (Trampa 2). |
| `docs/matriz_permisos.md` | Documenta el endpoint nuevo. |
| `docs/contrato_api.md` | Sección nueva + entrada en §28. |

### Tests que se crean

- `src/test/kotlin/pe/quantum/crm/domain/actividades/ComentarioActividadServiceImplTest.kt`
- `src/test/kotlin/pe/quantum/crm/domain/actividades/AuditoriaActividadServiceImplTest.kt`
- `src/test/kotlin/pe/quantum/crm/domain/actividades/HistorialActividadServiceImplTest.kt`
- `src/test/kotlin/pe/quantum/crm/domain/actividades/ActividadControllerWebMvcTest.kt`
- `src/test/kotlin/pe/quantum/crm/domain/actividades/ActividadMetamodeloTest.kt`

---

## Preparación (antes de la Task 1)

- [ ] **Paso 0.1: crear la rama**

```bash
git checkout main
git pull
git checkout -b feat/historial-actividades
```

- [ ] **Paso 0.2: confirmar que el árbol está limpio y los tests pasan de partida**

```bash
git status
./gradlew test
```

Esperado: `BUILD SUCCESSFUL`. Si ya falla algo aquí, **para** — no es culpa tuya y no debes taparlo con tus cambios.

- [ ] **Paso 0.3: confirmar que V47 es la última migración**

```bash
ls src/main/resources/db/migration/ | sort -V | tail -3
```

Esperado: la mayor es `V47__notificaciones_simulacion.sql`. Si ves un número mayor, **usa los dos siguientes libres** en vez de V48/V49 y ajusta los nombres en todo el plan.

---

## Task 1: Tabla y entidad de comentarios

**Files:**
- Create: `src/main/resources/db/migration/V48__create_actividad_comentarios.sql`
- Create: `src/main/kotlin/pe/quantum/crm/domain/actividades/TipoActividad.kt`
- Create: `src/main/kotlin/pe/quantum/crm/domain/actividades/ActividadComentario.kt`
- Create: `src/main/kotlin/pe/quantum/crm/domain/actividades/ActividadComentarioRepository.kt`
- Modify: `src/test/kotlin/pe/quantum/crm/support/SinBaseDeDatosMocks.kt`
- Test: `src/test/kotlin/pe/quantum/crm/domain/actividades/ActividadMetamodeloTest.kt`

**Interfaces:**
- Consumes: nada de tareas anteriores.
- Produces:
  - `enum class TipoActividad { tarea, evento }`
  - `class ActividadComentario(id: Long?, idTarea: Long?, idEvento: Long?, texto: String, createdAt: LocalDateTime, createdBy: Long)`
  - `interface ActividadComentarioRepository : JpaRepository<ActividadComentario, Long>` con los métodos `findByIdTareaOrderByCreatedAtAsc`, `findByIdEventoOrderByCreatedAtAsc`, `findByIdTareaInOrderByCreatedAtAsc`, `findByIdEventoInOrderByCreatedAtAsc`

- [ ] **Step 1: Escribir el test que falla (metamodelo)**

Este test compila las entidades nuevas contra un metamodelo real de Hibernate **sin base de datos** (mismo truco que `TareaListadoSpecificationTest`, ver su comentario de cabecera). Detecta nombres de atributo mal escritos, que si no aparecerían solo en producción.

Crea `src/test/kotlin/pe/quantum/crm/domain/actividades/ActividadMetamodeloTest.kt`:

```kotlin
package pe.quantum.crm.domain.actividades

import jakarta.persistence.EntityManagerFactory
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatCode
import org.hibernate.boot.MetadataSources
import org.hibernate.boot.registry.StandardServiceRegistryBuilder
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import java.time.LocalDateTime

/**
 * Las entidades del modulo actividades resuelven contra un metamodelo real de
 * Hibernate. No hace falta base de datos (Testcontainers esta roto en local):
 * con el dialecto fijado, Hibernate arranca sin DataSource y eso basta para
 * resolver atributos. Un nombre de atributo mal escrito reventaria en cada
 * lectura en produccion; aqui revienta en el build.
 */
class ActividadMetamodeloTest {
    @Test
    fun `los atributos de ActividadComentario existen en el metamodelo`() {
        val cb = emf.criteriaBuilder
        val query = cb.createQuery(ActividadComentario::class.java)
        val root = query.from(ActividadComentario::class.java)

        assertThatCode {
            root.get<Long>("idTarea")
            root.get<Long>("idEvento")
            root.get<String>("texto")
            root.get<LocalDateTime>("createdAt")
            root.get<Long>("createdBy")
        }.doesNotThrowAnyException()
    }

    @Test
    fun `el tipo de actividad tiene exactamente los dos valores del contrato`() {
        assertThat(TipoActividad.entries.map { it.name })
            .containsExactly("tarea", "evento")
    }

    companion object {
        private val emf: EntityManagerFactory =
            MetadataSources(
                StandardServiceRegistryBuilder()
                    .applySetting("hibernate.dialect", "org.hibernate.dialect.PostgreSQLDialect")
                    .applySetting("hibernate.boot.allow_jdbc_metadata_access", "false")
                    .applySetting("hibernate.temp.use_jdbc_metadata_defaults", "false")
                    .build(),
            ).addAnnotatedClass(ActividadComentario::class.java)
                .buildMetadata()
                .buildSessionFactory()

        @JvmStatic
        @AfterAll
        fun cerrarEmf() {
            emf.close()
        }
    }
}
```

- [ ] **Step 2: Correr el test y verificar que falla**

```bash
./gradlew test --tests "pe.quantum.crm.domain.actividades.ActividadMetamodeloTest"
```

Esperado: **FALLA en compilación**, con `Unresolved reference: ActividadComentario` y `Unresolved reference: TipoActividad`. Eso es correcto: aún no existen.

- [ ] **Step 3: Escribir la migración V48**

Crea `src/main/resources/db/migration/V48__create_actividad_comentarios.sql`:

```sql
-- =============================================================================
-- V48 - Comentarios de seguimiento sobre actividades (tareas y eventos).
--
-- Tabla append-only: un comentario NUNCA sobrescribe otro ni pisa la
-- `descripcion` de la tarea/evento. Antes de esta tabla, la unica forma de
-- dejar una nota era editar `descripcion`, que borraba la anterior y hacia
-- imposible el historial que esta vista existe para mostrar.
--
-- Origen mutuamente excluyente (mismo patron que `eventos`, V14/V21): un
-- comentario cuelga de UNA tarea o de UN evento, nunca de ambos ni de ninguno.
-- Dos FK nullable + CHECK en vez de una columna polimorfica (tipo, id) porque
-- asi la base garantiza la integridad referencial en los dos casos.
-- =============================================================================

CREATE TABLE actividad_comentarios (
    id              BIGSERIAL   PRIMARY KEY,

    id_tarea        BIGINT      REFERENCES tareas(id)   ON DELETE CASCADE,
    id_evento       BIGINT      REFERENCES eventos(id)  ON DELETE CASCADE,

    texto           TEXT        NOT NULL,

    created_at      TIMESTAMP   NOT NULL DEFAULT NOW(),
    created_by      BIGINT      NOT NULL REFERENCES empleados(id),

    CONSTRAINT chk_comentario_origen CHECK (
        (id_tarea IS NOT NULL AND id_evento IS NULL) OR
        (id_tarea IS NULL     AND id_evento IS NOT NULL)
    ),
    CONSTRAINT chk_comentario_texto_no_vacio CHECK (length(btrim(texto)) > 0)
);

-- Indices parciales: cada fila solo puebla una de las dos columnas.
CREATE INDEX idx_actividad_comentarios_tarea
    ON actividad_comentarios (id_tarea, created_at)
    WHERE id_tarea IS NOT NULL;

CREATE INDEX idx_actividad_comentarios_evento
    ON actividad_comentarios (id_evento, created_at)
    WHERE id_evento IS NOT NULL;
```

- [ ] **Step 4: Escribir el enum**

Crea `src/main/kotlin/pe/quantum/crm/domain/actividades/TipoActividad.kt`:

```kotlin
package pe.quantum.crm.domain.actividades

/**
 * Discriminador de actividad en la API del modulo. Es un enum de Kotlin puro,
 * NO un tipo de Postgres: en base, el origen de un comentario o de una entrada
 * de auditoria se guarda como dos FK nullable + CHECK (ver V48/V49), no como
 * una columna de tipo.
 *
 * Es API publica del modulo (CLAUDE.md regla 12): `tareas` y `eventos` lo
 * consumen al registrar auditoria.
 */
enum class TipoActividad {
    tarea,
    evento,
}
```

- [ ] **Step 5: Escribir la entidad**

Crea `src/main/kotlin/pe/quantum/crm/domain/actividades/ActividadComentario.kt`:

```kotlin
package pe.quantum.crm.domain.actividades

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.LocalDateTime

/**
 * Comentario de seguimiento sobre una tarea o un evento (tabla
 * `actividad_comentarios`, migracion V48).
 *
 * Append-only por diseno: no hay `updated_at` ni operacion de edicion. Un
 * comentario es un hecho fechado, no un campo mutable.
 *
 * `idTarea` e `idEvento` son mutuamente excluyentes (CHECK
 * `chk_comentario_origen`): exactamente uno de los dos es NOT NULL.
 */
@Entity
@Table(name = "actividad_comentarios")
class ActividadComentario(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,
    @Column(name = "id_tarea")
    val idTarea: Long? = null,
    @Column(name = "id_evento")
    val idEvento: Long? = null,
    @Column(nullable = false)
    val texto: String,
    @Column(name = "created_at", nullable = false)
    val createdAt: LocalDateTime = LocalDateTime.now(),
    @Column(name = "created_by", nullable = false)
    val createdBy: Long,
)
```

- [ ] **Step 6: Escribir el repositorio**

Crea `src/main/kotlin/pe/quantum/crm/domain/actividades/ActividadComentarioRepository.kt`:

```kotlin
package pe.quantum.crm.domain.actividades

import org.springframework.data.jpa.repository.JpaRepository

interface ActividadComentarioRepository : JpaRepository<ActividadComentario, Long> {
    fun findByIdTareaOrderByCreatedAtAsc(idTarea: Long): List<ActividadComentario>

    fun findByIdEventoOrderByCreatedAtAsc(idEvento: Long): List<ActividadComentario>

    /** Para contar comentarios de muchas actividades de una vez (listado del historial). */
    fun findByIdTareaInOrderByCreatedAtAsc(idsTarea: Collection<Long>): List<ActividadComentario>

    fun findByIdEventoInOrderByCreatedAtAsc(idsEvento: Collection<Long>): List<ActividadComentario>
}
```

- [ ] **Step 7: Registrar el repositorio en los mocks (Trampa 2 — NO saltar)**

En `src/test/kotlin/pe/quantum/crm/support/SinBaseDeDatosMocks.kt`.

Agrega este import junto a los demás (queda el primero de los `pe.quantum.crm.domain.*`, por orden alfabético):

```kotlin
import pe.quantum.crm.domain.actividades.ActividadComentarioRepository
```

Y agrega este bean dentro de la clase `SinBaseDeDatosMocks`, justo antes de `fun empleadoRepository()`:

```kotlin
    @Bean
    fun actividadComentarioRepository(): ActividadComentarioRepository = mockk(relaxed = true)
```

- [ ] **Step 8: Correr el test y verificar que pasa**

```bash
./gradlew test --tests "pe.quantum.crm.domain.actividades.ActividadMetamodeloTest"
```

Esperado: **PASS**, 2 tests.

- [ ] **Step 9: Correr la suite completa**

```bash
./gradlew ktlintFormat
./gradlew test
```

Esperado: `BUILD SUCCESSFUL`. Si fallan tests WebMvc de OTROS módulos con un error de contexto de Spring, es que hiciste mal el Step 7.

- [ ] **Step 10: Commit**

```bash
git add -A
git commit -m "feat(actividades): tabla y entidad de comentarios de actividad (V48)"
```

---

## Task 2: Tabla y entidad de auditoría

**Files:**
- Create: `src/main/resources/db/migration/V49__create_actividad_auditoria.sql`
- Create: `src/main/kotlin/pe/quantum/crm/domain/actividades/ActividadAuditoria.kt`
- Create: `src/main/kotlin/pe/quantum/crm/domain/actividades/ActividadAuditoriaRepository.kt`
- Modify: `src/test/kotlin/pe/quantum/crm/support/SinBaseDeDatosMocks.kt`
- Modify (test): `src/test/kotlin/pe/quantum/crm/domain/actividades/ActividadMetamodeloTest.kt`

**Interfaces:**
- Consumes: `TipoActividad` (Task 1).
- Produces:
  - `class ActividadAuditoria(id: Long?, idTarea: Long?, idEvento: Long?, campo: String, valorAnterior: String?, valorNuevo: String?, changedAt: LocalDateTime, changedBy: Long)`
  - `interface ActividadAuditoriaRepository : JpaRepository<ActividadAuditoria, Long>` con `findByIdTareaOrderByChangedAtDesc(idTarea: Long): List<ActividadAuditoria>` y `findByIdEventoOrderByChangedAtDesc(idEvento: Long): List<ActividadAuditoria>`

- [ ] **Step 1: Ampliar el test del metamodelo (falla primero)**

En `ActividadMetamodeloTest.kt`, agrega este test dentro de la clase, después del test `los atributos de ActividadComentario existen en el metamodelo`:

```kotlin
    @Test
    fun `los atributos de ActividadAuditoria existen en el metamodelo`() {
        val cb = emf.criteriaBuilder
        val query = cb.createQuery(ActividadAuditoria::class.java)
        val root = query.from(ActividadAuditoria::class.java)

        assertThatCode {
            root.get<Long>("idTarea")
            root.get<Long>("idEvento")
            root.get<String>("campo")
            root.get<String>("valorAnterior")
            root.get<String>("valorNuevo")
            root.get<LocalDateTime>("changedAt")
            root.get<Long>("changedBy")
        }.doesNotThrowAnyException()
    }
```

Y en el `companion object`, agrega la entidad nueva al `MetadataSources`. El bloque queda exactamente así:

```kotlin
            ).addAnnotatedClass(ActividadComentario::class.java)
                .addAnnotatedClass(ActividadAuditoria::class.java)
                .buildMetadata()
                .buildSessionFactory()
```

- [ ] **Step 2: Correr el test y verificar que falla**

```bash
./gradlew test --tests "pe.quantum.crm.domain.actividades.ActividadMetamodeloTest"
```

Esperado: **FALLA en compilación**, `Unresolved reference: ActividadAuditoria`.

- [ ] **Step 3: Escribir la migración V49**

Crea `src/main/resources/db/migration/V49__create_actividad_auditoria.sql`:

```sql
-- =============================================================================
-- V49 - Auditoria de ediciones sobre actividades (tareas y eventos).
--
-- Una fila por CAMPO modificado, no una por edicion: asi el historial responde
-- "quien cambio que", que es justo lo que la vista de supervision necesita.
-- Hasta ahora `tareas` y `eventos` solo guardaban `updated_by`/`updated_at` en
-- la propia fila: eso es "ultima modificacion", no historial - no dice que
-- cambio ni conserva el valor anterior.
--
-- Se registran TODAS las ediciones, tambien las del propio dueno. Registrar
-- solo las de terceros produciria un historial con huecos, imposible de leer.
--
-- Append-only: sin UPDATE ni DELETE propios. Las filas mueren con su actividad
-- (ON DELETE CASCADE).
--
-- Mismo patron de origen excluyente que V48.
-- =============================================================================

CREATE TABLE actividad_auditoria (
    id              BIGSERIAL   PRIMARY KEY,

    id_tarea        BIGINT      REFERENCES tareas(id)   ON DELETE CASCADE,
    id_evento       BIGINT      REFERENCES eventos(id)  ON DELETE CASCADE,

    -- Nombre del campo tal y como lo expone el contrato (snake_case):
    -- 'descripcion', 'fecha_ejecucion', 'tipo_accion', 'id_asignado', ...
    campo           TEXT        NOT NULL,
    valor_anterior  TEXT,
    valor_nuevo     TEXT,

    changed_at      TIMESTAMP   NOT NULL DEFAULT NOW(),
    changed_by      BIGINT      NOT NULL REFERENCES empleados(id),

    CONSTRAINT chk_auditoria_origen CHECK (
        (id_tarea IS NOT NULL AND id_evento IS NULL) OR
        (id_tarea IS NULL     AND id_evento IS NOT NULL)
    )
);

CREATE INDEX idx_actividad_auditoria_tarea
    ON actividad_auditoria (id_tarea, changed_at DESC)
    WHERE id_tarea IS NOT NULL;

CREATE INDEX idx_actividad_auditoria_evento
    ON actividad_auditoria (id_evento, changed_at DESC)
    WHERE id_evento IS NOT NULL;
```

- [ ] **Step 4: Escribir la entidad**

Crea `src/main/kotlin/pe/quantum/crm/domain/actividades/ActividadAuditoria.kt`:

```kotlin
package pe.quantum.crm.domain.actividades

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.LocalDateTime

/**
 * Una modificacion de UN campo de una tarea o un evento (tabla
 * `actividad_auditoria`, migracion V49).
 *
 * `campo` guarda el nombre publico en snake_case (`fecha_ejecucion`), no el de
 * la propiedad Kotlin: quien lee esta tabla es la vista de supervision, que
 * habla el idioma del contrato de API.
 */
@Entity
@Table(name = "actividad_auditoria")
@Suppress("LongParameterList") // Una entidad JPA refleja las columnas de su tabla.
class ActividadAuditoria(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,
    @Column(name = "id_tarea")
    val idTarea: Long? = null,
    @Column(name = "id_evento")
    val idEvento: Long? = null,
    @Column(nullable = false)
    val campo: String,
    @Column(name = "valor_anterior")
    val valorAnterior: String? = null,
    @Column(name = "valor_nuevo")
    val valorNuevo: String? = null,
    @Column(name = "changed_at", nullable = false)
    val changedAt: LocalDateTime = LocalDateTime.now(),
    @Column(name = "changed_by", nullable = false)
    val changedBy: Long,
)
```

- [ ] **Step 5: Escribir el repositorio**

Crea `src/main/kotlin/pe/quantum/crm/domain/actividades/ActividadAuditoriaRepository.kt`:

```kotlin
package pe.quantum.crm.domain.actividades

import org.springframework.data.jpa.repository.JpaRepository

interface ActividadAuditoriaRepository : JpaRepository<ActividadAuditoria, Long> {
    fun findByIdTareaOrderByChangedAtDesc(idTarea: Long): List<ActividadAuditoria>

    fun findByIdEventoOrderByChangedAtDesc(idEvento: Long): List<ActividadAuditoria>
}
```

- [ ] **Step 6: Registrar el repositorio en los mocks (Trampa 2 — NO saltar)**

En `src/test/kotlin/pe/quantum/crm/support/SinBaseDeDatosMocks.kt`, agrega el import:

```kotlin
import pe.quantum.crm.domain.actividades.ActividadAuditoriaRepository
```

Y el bean, junto al de comentarios:

```kotlin
    @Bean
    fun actividadAuditoriaRepository(): ActividadAuditoriaRepository = mockk(relaxed = true)
```

- [ ] **Step 7: Correr el test y verificar que pasa**

```bash
./gradlew test --tests "pe.quantum.crm.domain.actividades.ActividadMetamodeloTest"
```

Esperado: **PASS**, 3 tests.

- [ ] **Step 8: Suite completa y commit**

```bash
./gradlew ktlintFormat
./gradlew test
git add -A
git commit -m "feat(actividades): tabla y entidad de auditoria de ediciones (V49)"
```

---

## Task 3: DTOs del módulo actividades

Un solo archivo con todos los DTOs. Son datos puros, sin dependencias: existen antes que los servicios para que las tareas siguientes puedan referenciarlos.

**Files:**
- Create: `src/main/kotlin/pe/quantum/crm/domain/actividades/dto/ActividadDtos.kt`
- Test: `src/test/kotlin/pe/quantum/crm/domain/actividades/ActividadDtosTest.kt`

**Interfaces:**
- Consumes: `EmpleadoResumen` (de `domain/empleados/dto`, campos `id`, `nombres`, `apellidos`), `EmpresaResumen` (de `domain/empresas/dto`, campos `id`, `razonSocial`, `distrito`).
- Produces: `ActividadDto`, `HistorialFiltros`, `ComentarioDto`, `CrearComentarioRequest`, `CambioCampo`, `CambioAuditoriaDto`. Las tareas 4 a 11 dependen de estos nombres exactos.

- [ ] **Step 1: Escribir el test que falla**

Crea `src/test/kotlin/pe/quantum/crm/domain/actividades/ActividadDtosTest.kt`:

```kotlin
package pe.quantum.crm.domain.actividades

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import pe.quantum.crm.domain.actividades.dto.ActividadDto
import pe.quantum.crm.domain.actividades.dto.CambioCampo
import java.time.Instant
import java.time.LocalDate

/**
 * El DTO unificado NO colapsa las fechas en un solo campo: `fechaHora` es para
 * columnas TIMESTAMP y `fechaDia` para columnas DATE. Mezclarlas obligaria a dar
 * una hora a un dia del calendario de Lima, que es justo lo que
 * `shared/TiempoUtc.kt` prohibe. Este test fija esa separacion para que nadie la
 * deshaga "simplificando" el DTO mas adelante.
 */
class ActividadDtosTest {
    @Test
    fun `una actividad de tipo tarea nunca lleva fecha de dia`() {
        val tarea = actividad(tipo = "tarea", fechaHora = Instant.parse("2026-09-08T15:00:00Z"), fechaDia = null)

        assertThat(tarea.fechaDia).isNull()
        assertThat(tarea.fechaHora).isNotNull()
    }

    @Test
    fun `una actividad de tipo evento puede llevar solo fecha de dia`() {
        val evento = actividad(tipo = "evento", fechaHora = null, fechaDia = LocalDate.of(2026, 9, 8))

        assertThat(evento.fechaHora).isNull()
        assertThat(evento.fechaDia).isEqualTo(LocalDate.of(2026, 9, 8))
    }

    @Test
    fun `un cambio de campo conserva el valor anterior y el nuevo por separado`() {
        val cambio = CambioCampo(campo = "descripcion", valorAnterior = "vieja", valorNuevo = "nueva")

        assertThat(cambio.valorAnterior).isEqualTo("vieja")
        assertThat(cambio.valorNuevo).isEqualTo("nueva")
    }

    private fun actividad(
        tipo: String,
        fechaHora: Instant?,
        fechaDia: LocalDate?,
    ) = ActividadDto(
        tipo = tipo,
        id = 1,
        titulo = "llamada",
        descripcion = null,
        estado = "pendiente",
        fechaHora = fechaHora,
        fechaDia = fechaDia,
        idEmpresa = 3,
        empresa = null,
        idOportunidad = null,
        idEmpleado = 7,
        empleado = null,
        comentarios = 0,
        createdAt = Instant.parse("2026-09-01T10:00:00Z"),
    )
}
```

- [ ] **Step 2: Correr el test y verificar que falla**

```bash
./gradlew test --tests "pe.quantum.crm.domain.actividades.ActividadDtosTest"
```

Esperado: **FALLA en compilación**, `Unresolved reference: dto`.

- [ ] **Step 3: Escribir los DTOs**

Crea `src/main/kotlin/pe/quantum/crm/domain/actividades/dto/ActividadDtos.kt`:

```kotlin
package pe.quantum.crm.domain.actividades.dto

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import pe.quantum.crm.domain.empleados.dto.EmpleadoResumen
import pe.quantum.crm.domain.empresas.dto.EmpresaResumen
import java.time.Instant
import java.time.LocalDate

/** Longitud maxima del texto de un comentario (columna TEXT, sin tope en BD). */
private const val MAX_TEXTO_COMENTARIO = 5000

/**
 * Una actividad (tarea o evento) en el historial unificado.
 *
 * DOS campos de fecha, a proposito:
 *  - `fechaHora`  (TIMESTAMP): `fecha_ejecucion` de una tarea, `fecha_ocurrencia`
 *    de un evento. Son instantes reales.
 *  - `fechaDia`   (DATE): `fecha_estimada` de un evento. Es un dia del calendario
 *    de Lima, NO un instante. Darle hora lo desplazaria (ver shared/TiempoUtc.kt).
 *
 * Nunca los unifiques. En una tarea, `fechaDia` es siempre null.
 *
 * `createdAt` es el eje del historial: siempre TIMESTAMP, nunca null en ninguna
 * de las dos entidades, y por eso es la clave de orden y de filtro por rango.
 */
@Suppress("LongParameterList") // Un DTO de composicion refleja dos entidades a la vez.
data class ActividadDto(
    /** `tarea` o `evento` — el valor de `TipoActividad`. */
    val tipo: String,
    val id: Long,
    /** `tipo_accion` de la tarea, o el nombre del evento (catalogo o personalizado). */
    val titulo: String,
    val descripcion: String?,
    val estado: String,
    val fechaHora: Instant?,
    val fechaDia: LocalDate?,
    val idEmpresa: Long?,
    val empresa: EmpresaResumen?,
    val idOportunidad: Long?,
    /** Asignado de la tarea, o creador del evento (un evento no tiene asignado). */
    val idEmpleado: Long?,
    val empleado: EmpleadoResumen?,
    /** Cuantos comentarios de seguimiento tiene esta actividad. */
    val comentarios: Int,
    val createdAt: Instant,
)

/**
 * Filtros de `GET /actividades`. `idEmpleado` es obligatorio: la vista es "el
 * historial de UNA persona", y exigirlo acota el volumen que se une en memoria.
 */
data class HistorialFiltros(
    val idEmpleado: Long,
    /** Filtran por `created_at`, no por la fecha planificada. Ambos inclusive. */
    val desde: Instant? = null,
    val hasta: Instant? = null,
    /** `tarea`, `evento`, o null para ambos. */
    val tipo: String? = null,
    val idEmpresa: Long? = null,
    val idOportunidad: Long? = null,
)

/** Comentario de seguimiento expuesto en respuestas. */
data class ComentarioDto(
    val id: Long,
    val tipo: String,
    val idActividad: Long,
    val texto: String,
    val createdAt: Instant,
    val createdBy: Long,
    val autor: EmpleadoResumen?,
)

/** Body de `POST /actividades/{tipo}/{id}/comentarios`. */
data class CrearComentarioRequest(
    @field:NotBlank(message = "El comentario no puede estar vacío")
    @field:Size(max = MAX_TEXTO_COMENTARIO, message = "texto supera la longitud maxima")
    val texto: String,
)

/**
 * Un campo que cambio en una edicion. Lo producen `tareas` y `eventos` al
 * editar, y lo consume `AuditoriaActividadService`. Los valores van como texto
 * porque la tabla guarda cualquier campo con la misma forma.
 */
data class CambioCampo(
    /** Nombre publico del campo, en snake_case: `fecha_ejecucion`, no `fechaEjecucion`. */
    val campo: String,
    val valorAnterior: String?,
    val valorNuevo: String?,
)

/** Entrada de auditoria expuesta en respuestas. */
data class CambioAuditoriaDto(
    val id: Long,
    val campo: String,
    val valorAnterior: String?,
    val valorNuevo: String?,
    val changedAt: Instant,
    val changedBy: Long,
    val autor: EmpleadoResumen?,
)
```

- [ ] **Step 4: Correr el test y verificar que pasa**

```bash
./gradlew test --tests "pe.quantum.crm.domain.actividades.ActividadDtosTest"
```

Esperado: **PASS**, 3 tests.

- [ ] **Step 5: Suite completa y commit**

```bash
./gradlew ktlintFormat
./gradlew test
git add -A
git commit -m "feat(actividades): DTOs del historial, comentarios y auditoria"
```

---

## Task 4: `tareas` expone listado por empleado y visibilidad puntual

Dos métodos nuevos en la API pública de `tareas`. **No se toca ninguna regla de permisos existente:** `listarPorEmpleado` reutiliza el mismo criterio de visibilidad que ya aplica `especificacion()`, y `vinculoVisible` reutiliza el mismo `visible()` privado que ya existe.

**Files:**
- Modify: `src/main/kotlin/pe/quantum/crm/domain/tareas/TareaRepository.kt`
- Modify: `src/main/kotlin/pe/quantum/crm/domain/tareas/TareaService.kt`
- Modify: `src/main/kotlin/pe/quantum/crm/domain/tareas/TareaServiceImpl.kt`
- Modify: `src/main/kotlin/pe/quantum/crm/domain/tareas/dto/TareaDtos.kt`
- Test: `src/test/kotlin/pe/quantum/crm/domain/tareas/TareaHistorialTest.kt`

**Interfaces:**
- Consumes: `TareaDto` (ya existe, `domain/tareas/dto/TareaDtos.kt:13`).
- Produces:
  - `data class TareaVinculo(val id: Long, val idEmpresa: Long, val idOportunidad: Long?, val idAsignado: Long?)` en `domain/tareas/dto`
  - `TareaService.listarPorEmpleado(idEmpleado: Long, desde: Instant?, hasta: Instant?, usuario: UsuarioActual): List<TareaDto>`
  - `TareaService.vinculoVisible(id: Long, usuario: UsuarioActual): TareaVinculo` — lanza `NoEncontradoException` si la tarea no existe o queda fuera de la visibilidad del usuario.

- [ ] **Step 1: Escribir el test que falla**

Crea `src/test/kotlin/pe/quantum/crm/domain/tareas/TareaHistorialTest.kt`:

```kotlin
package pe.quantum.crm.domain.tareas

import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import pe.quantum.crm.domain.contactos.ContactoService
import pe.quantum.crm.domain.empleados.EmpleadoService
import pe.quantum.crm.domain.empresas.EmpresaService
import pe.quantum.crm.domain.notificaciones.NotificacionService
import pe.quantum.crm.domain.oportunidades.OportunidadService
import pe.quantum.crm.shared.enums.EstadoAccion
import pe.quantum.crm.shared.enums.TipoAccion
import pe.quantum.crm.shared.exception.NoEncontradoException
import pe.quantum.crm.shared.security.UsuarioActual
import java.time.Instant
import java.time.LocalDateTime
import java.util.Optional

/**
 * `listarPorEmpleado` y `vinculoVisible`: las dos puertas que el modulo
 * actividades usa para componer el historial sin tocar la entidad Tarea
 * (CLAUDE.md regla 12).
 */
class TareaHistorialTest {
    private val tareaRepository = mockk<TareaRepository>()
    private val tareaResponsableRepository = mockk<TareaResponsableRepository>(relaxed = true)
    private val empresaService = mockk<EmpresaService>(relaxed = true)
    private val oportunidadService = mockk<OportunidadService>(relaxed = true)
    private val contactoService = mockk<ContactoService>(relaxed = true)
    private val empleadoService = mockk<EmpleadoService>(relaxed = true)
    private val notificacionService = mockk<NotificacionService>(relaxed = true)
    private val service =
        TareaServiceImpl(
            tareaRepository,
            tareaResponsableRepository,
            empresaService,
            oportunidadService,
            contactoService,
            empleadoService,
            notificacionService,
        )

    private val supervisor = UsuarioActual(id = 1, rol = "gerencia")
    private val vendedor = UsuarioActual(id = 9, rol = "vendedor")

    private fun tarea(
        id: Long = 5,
        idAsignado: Long? = 7,
    ) = Tarea(
        id = id,
        idEmpresa = 3,
        idOportunidad = null,
        idAsignado = idAsignado,
        tipoAccion = TipoAccion.llamada,
        estadoAccion = EstadoAccion.pendiente,
        createdAt = LocalDateTime.of(2026, 9, 1, 10, 0),
        createdBy = 7,
        updatedAt = LocalDateTime.of(2026, 9, 1, 10, 0),
        updatedBy = 7,
    )

    @Test
    fun `listarPorEmpleado sin rango consulta desde el inicio hasta el fin de los tiempos`() {
        every {
            tareaRepository.findByIdAsignadoAndCreatedAtBetweenOrderByCreatedAtDesc(7, any(), any())
        } returns listOf(tarea())

        val resultado = service.listarPorEmpleado(7, null, null, supervisor)

        assertThat(resultado).hasSize(1)
        assertThat(resultado.first().id).isEqualTo(5)
    }

    @Test
    fun `listarPorEmpleado respeta el rango de fechas recibido`() {
        val desde = Instant.parse("2026-09-01T00:00:00Z")
        val hasta = Instant.parse("2026-09-30T23:59:59Z")
        every {
            tareaRepository.findByIdAsignadoAndCreatedAtBetweenOrderByCreatedAtDesc(
                7,
                LocalDateTime.of(2026, 9, 1, 0, 0),
                LocalDateTime.of(2026, 9, 30, 23, 59, 59),
            )
        } returns listOf(tarea())

        val resultado = service.listarPorEmpleado(7, desde, hasta, supervisor)

        assertThat(resultado).hasSize(1)
    }

    @Test
    fun `un vendedor no obtiene las tareas de otro empleado`() {
        // El vendedor pide el historial del empleado 7, que no es el suyo (9).
        every {
            tareaRepository.findByIdAsignadoAndCreatedAtBetweenOrderByCreatedAtDesc(7, any(), any())
        } returns listOf(tarea(idAsignado = 7))
        every { tareaResponsableRepository.findByIdIdTareaIn(any()) } returns emptyList()

        val resultado = service.listarPorEmpleado(7, null, null, vendedor)

        assertThat(resultado).isEmpty()
    }

    @Test
    fun `vinculoVisible devuelve los datos minimos de la tarea`() {
        every { tareaRepository.findById(5) } returns Optional.of(tarea())

        val vinculo = service.vinculoVisible(5, supervisor)

        assertThat(vinculo.id).isEqualTo(5)
        assertThat(vinculo.idEmpresa).isEqualTo(3)
        assertThat(vinculo.idAsignado).isEqualTo(7)
    }

    @Test
    fun `vinculoVisible responde 404 sobre una tarea ajena de un rol restringido`() {
        every { tareaRepository.findById(5) } returns Optional.of(tarea(idAsignado = 7))
        every { tareaResponsableRepository.existsByIdIdTareaAndIdIdEmpleado(5, 9) } returns false

        assertThatThrownBy { service.vinculoVisible(5, vendedor) }
            .isInstanceOf(NoEncontradoException::class.java)
    }
}
```

- [ ] **Step 2: Correr el test y verificar que falla**

```bash
./gradlew test --tests "pe.quantum.crm.domain.tareas.TareaHistorialTest"
```

Esperado: **FALLA en compilación**, `Unresolved reference: listarPorEmpleado` y `vinculoVisible`.

- [ ] **Step 3: Agregar el DTO `TareaVinculo`**

Al final de `src/main/kotlin/pe/quantum/crm/domain/tareas/dto/TareaDtos.kt`, agrega:

```kotlin
/** Datos minimos de una tarea para otros modulos (actividades). */
data class TareaVinculo(
    val id: Long,
    val idEmpresa: Long,
    val idOportunidad: Long?,
    val idAsignado: Long?,
)
```

- [ ] **Step 4: Agregar la query al repositorio**

En `src/main/kotlin/pe/quantum/crm/domain/tareas/TareaRepository.kt`, agrega dentro de la interfaz:

```kotlin
    /** Historial de un empleado por rango de `created_at` (modulo actividades). */
    fun findByIdAsignadoAndCreatedAtBetweenOrderByCreatedAtDesc(
        idAsignado: Long,
        desde: java.time.LocalDateTime,
        hasta: java.time.LocalDateTime,
    ): List<Tarea>
```

> Si el archivo ya importa `java.time.LocalDateTime`, usa `LocalDateTime` a secas en vez del nombre completo.

- [ ] **Step 5: Declarar los métodos en la interfaz de servicio**

En `src/main/kotlin/pe/quantum/crm/domain/tareas/TareaService.kt`, agrega los imports que falten:

```kotlin
import pe.quantum.crm.domain.tareas.dto.TareaVinculo
import java.time.Instant
```

Y dentro de la interfaz:

```kotlin
    /**
     * Tareas asignadas a un empleado, filtradas por rango de `created_at`
     * (ambos extremos opcionales). Aplica el MISMO filtro de visibilidad que
     * `listar`: un rol restringido solo recibe aquellas de las que es dueño o
     * colaborador, aunque pida el id de otro empleado.
     */
    fun listarPorEmpleado(
        idEmpleado: Long,
        desde: Instant?,
        hasta: Instant?,
        usuario: UsuarioActual,
    ): List<TareaDto>

    /**
     * Datos minimos de una tarea, comprobando visibilidad. `NoEncontradoException`
     * (404, nunca 403) si no existe o queda fuera del alcance del usuario.
     */
    fun vinculoVisible(
        id: Long,
        usuario: UsuarioActual,
    ): TareaVinculo
```

- [ ] **Step 6: Implementar en `TareaServiceImpl`**

Agrega los imports que falten al principio de `src/main/kotlin/pe/quantum/crm/domain/tareas/TareaServiceImpl.kt`:

```kotlin
import pe.quantum.crm.domain.tareas.dto.TareaVinculo
import java.time.Instant
```

Agrega estos dos métodos públicos, justo **antes** del comentario `// ── privados ───`:

```kotlin
    @Transactional(readOnly = true)
    override fun listarPorEmpleado(
        idEmpleado: Long,
        desde: Instant?,
        hasta: Instant?,
        usuario: UsuarioActual,
    ): List<TareaDto> {
        val tareas =
            tareaRepository.findByIdAsignadoAndCreatedAtBetweenOrderByCreatedAtDesc(
                idEmpleado,
                desde?.let { LocalDateTime.ofInstant(it, ZoneOffset.UTC) } ?: INICIO_DE_LOS_TIEMPOS,
                hasta?.let { LocalDateTime.ofInstant(it, ZoneOffset.UTC) } ?: FIN_DE_LOS_TIEMPOS,
            )
        // Un rol restringido no puede leer la agenda de otro por esta puerta:
        // se le recorta a lo suyo, igual que hace `especificacion()` en `listar`.
        val visibles =
            if (usuario.visibilidadRestringida) {
                val idsColaborador =
                    tareaResponsableRepository
                        .findByIdIdTareaIn(tareas.mapNotNull { it.id })
                        .filter { it.id.idEmpleado == usuario.id }
                        .map { it.id.idTarea }
                        .toSet()
                tareas.filter { it.idAsignado == usuario.id || it.id in idsColaborador }
            } else {
                tareas
            }
        return toDtos(visibles)
    }

    @Transactional(readOnly = true)
    override fun vinculoVisible(
        id: Long,
        usuario: UsuarioActual,
    ): TareaVinculo {
        val tarea = visible(id, usuario)
        return TareaVinculo(
            id = requireNotNull(tarea.id),
            idEmpresa = tarea.idEmpresa,
            idOportunidad = tarea.idOportunidad,
            idAsignado = tarea.idAsignado,
        )
    }
```

Y en el `private companion object` que ya existe al final de la clase (`TareaServiceImpl.kt:450`, el que tiene `CAMPOS_ORDENABLES`), agrega estas dos constantes. **Sin `private`**: el companion ya lo es, y el estilo del archivo declara sus miembros con `val` a secas.

```kotlin
        /**
         * Limites del rango cuando el cliente no manda `desde`/`hasta`.
         * `LocalDateTime.MIN/MAX` NO sirven: se salen del rango de un TIMESTAMP
         * de Postgres y la query revienta.
         */
        val INICIO_DE_LOS_TIEMPOS: LocalDateTime = LocalDateTime.of(1970, 1, 1, 0, 0)
        val FIN_DE_LOS_TIEMPOS: LocalDateTime = LocalDateTime.of(2999, 12, 31, 23, 59, 59)
```

- [ ] **Step 7: Correr el test y verificar que pasa**

```bash
./gradlew test --tests "pe.quantum.crm.domain.tareas.TareaHistorialTest"
```

Esperado: **PASS**, 5 tests.

- [ ] **Step 8: Suite completa y commit**

```bash
./gradlew ktlintFormat
./gradlew test
git add -A
git commit -m "feat(tareas): listado por empleado y vinculo visible para el historial"
```

---

## Task 5: `eventos` expone listado por empleado y visibilidad puntual

Espejo de la Task 4 en el módulo `eventos`. **Atención a la Trampa 3:** `Evento` no tiene `idAsignado`; se usa **`createdBy`**.

**Files:**
- Modify: `src/main/kotlin/pe/quantum/crm/domain/eventos/EventoRepository.kt`
- Modify: `src/main/kotlin/pe/quantum/crm/domain/eventos/EventoService.kt`
- Modify: `src/main/kotlin/pe/quantum/crm/domain/eventos/EventoServiceImpl.kt`
- Modify: `src/main/kotlin/pe/quantum/crm/domain/eventos/dto/EventoDtos.kt`
- Test: `src/test/kotlin/pe/quantum/crm/domain/eventos/EventoHistorialTest.kt`

**Interfaces:**
- Consumes: `EventoDto` (ya existe, `domain/eventos/dto/EventoDtos.kt:9`).
- Produces:
  - `data class EventoVinculo(val id: Long, val idEmpresa: Long?, val idOportunidad: Long?, val createdBy: Long)` en `domain/eventos/dto`
  - `EventoService.listarPorEmpleado(idEmpleado: Long, desde: Instant?, hasta: Instant?, usuario: UsuarioActual): List<EventoDto>`
  - `EventoService.vinculoVisible(id: Long, usuario: UsuarioActual): EventoVinculo`
  - `EventoDto` gana dos campos nuevos: `createdBy: Long` y `createdAt: Instant`. **Son aditivos y opcionales para el frontend** (non-breaking), pero el módulo actividades los necesita para ordenar y atribuir.

- [ ] **Step 1: Escribir el test que falla**

Crea `src/test/kotlin/pe/quantum/crm/domain/eventos/EventoHistorialTest.kt`:

```kotlin
package pe.quantum.crm.domain.eventos

import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import pe.quantum.crm.domain.catalogoeventos.CatalogoEventoService
import pe.quantum.crm.domain.empleados.EmpleadoService
import pe.quantum.crm.domain.empresas.EmpresaService
import pe.quantum.crm.domain.notificaciones.NotificacionService
import pe.quantum.crm.domain.oportunidades.OportunidadService
import pe.quantum.crm.domain.oportunidades.dto.OportunidadVinculo
import pe.quantum.crm.shared.enums.EstadoEvento
import pe.quantum.crm.shared.exception.NoEncontradoException
import pe.quantum.crm.shared.security.UsuarioActual
import java.time.Instant
import java.time.LocalDateTime
import java.util.Optional

/**
 * Historial de eventos de un empleado. Un evento NO tiene asignado: la
 * atribucion es por `created_by`, quien lo registro (ver Evento.kt).
 */
class EventoHistorialTest {
    private val eventoRepository = mockk<EventoRepository>()
    private val catalogoEventoService = mockk<CatalogoEventoService>(relaxed = true)
    private val oportunidadService = mockk<OportunidadService>(relaxed = true)
    private val empresaService = mockk<EmpresaService>(relaxed = true)
    private val empleadoService = mockk<EmpleadoService>(relaxed = true)
    private val notificacionService = mockk<NotificacionService>(relaxed = true)
    private val service =
        EventoServiceImpl(
            eventoRepository,
            catalogoEventoService,
            oportunidadService,
            empresaService,
            empleadoService,
            notificacionService,
        )

    private val supervisor = UsuarioActual(id = 1, rol = "gerencia")
    private val vendedor = UsuarioActual(id = 9, rol = "vendedor")

    private fun evento(
        id: Long = 4,
        creador: Long = 7,
    ) = Evento(
        id = id,
        idOportunidad = 20,
        idEmpresa = null,
        idCatalogoEvento = null,
        esPersonalizado = true,
        nombrePersonalizado = "Visita a planta",
        estado = EstadoEvento.pendiente,
        createdAt = LocalDateTime.of(2026, 9, 1, 10, 0),
        createdBy = creador,
        updatedAt = LocalDateTime.of(2026, 9, 1, 10, 0),
        updatedBy = creador,
    )

    @Test
    fun `listarPorEmpleado atribuye el evento a quien lo creo`() {
        every {
            eventoRepository.findByCreatedByAndCreatedAtBetweenOrderByCreatedAtDesc(7, any(), any())
        } returns listOf(evento())
        every { oportunidadService.vinculoVisible(20, supervisor) } returns
            OportunidadVinculo(id = 20, idEmpresa = 3, idVendedor = 7, estado = "abierto")

        val resultado = service.listarPorEmpleado(7, null, null, supervisor)

        assertThat(resultado).hasSize(1)
        assertThat(resultado.first().createdBy).isEqualTo(7)
    }

    @Test
    fun `listarPorEmpleado descarta los eventos cuya oportunidad no es visible`() {
        every {
            eventoRepository.findByCreatedByAndCreatedAtBetweenOrderByCreatedAtDesc(7, any(), any())
        } returns listOf(evento())
        every { oportunidadService.vinculoVisible(20, vendedor) } throws NoEncontradoException("La oportunidad no existe")

        val resultado = service.listarPorEmpleado(7, null, null, vendedor)

        assertThat(resultado).isEmpty()
    }

    @Test
    fun `listarPorEmpleado respeta el rango de fechas recibido`() {
        every {
            eventoRepository.findByCreatedByAndCreatedAtBetweenOrderByCreatedAtDesc(
                7,
                LocalDateTime.of(2026, 9, 1, 0, 0),
                LocalDateTime.of(2026, 9, 30, 23, 59, 59),
            )
        } returns emptyList()

        val resultado =
            service.listarPorEmpleado(
                7,
                Instant.parse("2026-09-01T00:00:00Z"),
                Instant.parse("2026-09-30T23:59:59Z"),
                supervisor,
            )

        assertThat(resultado).isEmpty()
    }

    @Test
    fun `vinculoVisible devuelve los datos minimos del evento`() {
        every { eventoRepository.findById(4) } returns Optional.of(evento())
        every { oportunidadService.vinculoVisible(20, supervisor) } returns
            OportunidadVinculo(id = 20, idEmpresa = 3, idVendedor = 7, estado = "abierto")

        val vinculo = service.vinculoVisible(4, supervisor)

        assertThat(vinculo.id).isEqualTo(4)
        assertThat(vinculo.idOportunidad).isEqualTo(20)
        assertThat(vinculo.createdBy).isEqualTo(7)
    }

    @Test
    fun `vinculoVisible responde 404 sobre un evento fuera de alcance`() {
        every { eventoRepository.findById(4) } returns Optional.of(evento())
        every { oportunidadService.vinculoVisible(20, vendedor) } throws NoEncontradoException("La oportunidad no existe")

        assertThatThrownBy { service.vinculoVisible(4, vendedor) }
            .isInstanceOf(NoEncontradoException::class.java)
    }
}
```

- [ ] **Step 2: Correr el test y verificar que falla**

```bash
./gradlew test --tests "pe.quantum.crm.domain.eventos.EventoHistorialTest"
```

Esperado: **FALLA en compilación**.

- [ ] **Step 3: Ampliar `EventoDto` y agregar `EventoVinculo`**

En `src/main/kotlin/pe/quantum/crm/domain/eventos/dto/EventoDtos.kt`:

Agrega el import si falta:

```kotlin
import java.time.Instant
```

Agrega DOS campos al final de `data class EventoDto` (después de `esHitoProspeccion`):

```kotlin
    val createdBy: Long,
    val createdAt: Instant,
```

Y agrega al final del archivo:

```kotlin
/** Datos minimos de un evento para otros modulos (actividades). */
data class EventoVinculo(
    val id: Long,
    val idEmpresa: Long?,
    val idOportunidad: Long?,
    /** Un evento no tiene asignado: se atribuye a quien lo registro. */
    val createdBy: Long,
)
```

> **Aviso:** al agregar campos obligatorios a `EventoDto` **se romperán en compilación todos los sitios que lo construyen**. Son dos: el `Evento.toDto()` privado de `EventoServiceImpl` (Step 5) y los tests que fabrican un `EventoDto` a mano. El compilador te dirá exactamente dónde. Arréglalos todos antes de seguir.

- [ ] **Step 4: Agregar la query al repositorio**

En `src/main/kotlin/pe/quantum/crm/domain/eventos/EventoRepository.kt`, agrega el import y el método:

```kotlin
import java.time.LocalDateTime
```

```kotlin
    /** Historial de un empleado por rango de `created_at` (modulo actividades). */
    fun findByCreatedByAndCreatedAtBetweenOrderByCreatedAtDesc(
        createdBy: Long,
        desde: LocalDateTime,
        hasta: LocalDateTime,
    ): List<Evento>
```

- [ ] **Step 5: Declarar e implementar los métodos**

En `src/main/kotlin/pe/quantum/crm/domain/eventos/EventoService.kt`, agrega los imports:

```kotlin
import pe.quantum.crm.domain.eventos.dto.EventoVinculo
import java.time.Instant
```

Y dentro de la interfaz:

```kotlin
    /**
     * Eventos REGISTRADOS por un empleado (`created_by`), filtrados por rango de
     * `created_at`. Un evento no tiene asignado, por eso la atribucion es por
     * creador. Descarta silenciosamente los que queden fuera de la visibilidad
     * del usuario que consulta.
     */
    fun listarPorEmpleado(
        idEmpleado: Long,
        desde: Instant?,
        hasta: Instant?,
        usuario: UsuarioActual,
    ): List<EventoDto>

    /**
     * Datos minimos de un evento, comprobando visibilidad via su oportunidad o
     * su empresa. `NoEncontradoException` (404, nunca 403) si queda fuera.
     */
    fun vinculoVisible(
        id: Long,
        usuario: UsuarioActual,
    ): EventoVinculo
```

En `src/main/kotlin/pe/quantum/crm/domain/eventos/EventoServiceImpl.kt`, agrega los imports:

```kotlin
import pe.quantum.crm.domain.eventos.dto.EventoVinculo
import java.time.Instant
```

Agrega los dos métodos justo **antes** del comentario `// ── privados ───`:

```kotlin
    @Transactional(readOnly = true)
    override fun listarPorEmpleado(
        idEmpleado: Long,
        desde: Instant?,
        hasta: Instant?,
        usuario: UsuarioActual,
    ): List<EventoDto> {
        val eventos =
            eventoRepository.findByCreatedByAndCreatedAtBetweenOrderByCreatedAtDesc(
                idEmpleado,
                desde?.let { LocalDateTime.ofInstant(it, ZoneOffset.UTC) } ?: INICIO_DE_LOS_TIEMPOS,
                hasta?.let { LocalDateTime.ofInstant(it, ZoneOffset.UTC) } ?: FIN_DE_LOS_TIEMPOS,
            )
        // La visibilidad de un evento se hereda de su oportunidad o su empresa
        // (mismo criterio que `visible()`). Lo que no alcanza, se descarta en
        // silencio: es un listado, no un acceso puntual.
        val visibles = eventos.filter { alcanzable(it, usuario) }
        return toDtos(visibles)
    }

    @Transactional(readOnly = true)
    override fun vinculoVisible(
        id: Long,
        usuario: UsuarioActual,
    ): EventoVinculo {
        val evento = visible(id, usuario)
        return EventoVinculo(
            id = requireNotNull(evento.id),
            idEmpresa = evento.idEmpresa,
            idOportunidad = evento.idOportunidad,
            createdBy = evento.createdBy,
        )
    }
```

Y agrega este helper privado, después de `private fun visible(...)`:

```kotlin
    /** Version no lanzante de `visible()`, para filtrar listados. */
    private fun alcanzable(
        evento: Evento,
        usuario: UsuarioActual,
    ): Boolean {
        val idOportunidad = evento.idOportunidad
        val idEmpresa = evento.idEmpresa
        return runCatching {
            when {
                idOportunidad != null -> oportunidadService.vinculoVisible(idOportunidad, usuario)
                idEmpresa != null -> empresaService.vinculoVisible(idEmpresa, usuario)
                else -> return false
            }
        }.isSuccess
    }
```

Añade al final de la clase (si no existe `companion object`, créalo):

```kotlin
    private companion object {
        /**
         * Limites del rango cuando el cliente no manda `desde`/`hasta`.
         * `LocalDateTime.MIN/MAX` se salen del rango de un TIMESTAMP de Postgres.
         */
        val INICIO_DE_LOS_TIEMPOS: LocalDateTime = LocalDateTime.of(1970, 1, 1, 0, 0)
        val FIN_DE_LOS_TIEMPOS: LocalDateTime = LocalDateTime.of(2999, 12, 31, 23, 59, 59)
    }
```

- [ ] **Step 6: Rellenar los dos campos nuevos en `Evento.toDto()`**

En el `private fun Evento.toDto(...)` de `EventoServiceImpl`, agrega al final del constructor de `EventoDto` (junto a `esHitoProspeccion`):

```kotlin
            createdBy = createdBy,
            createdAt = createdAt.comoInstanteUtc(),
```

> `createdAt` es columna TIMESTAMP, así que `comoInstanteUtc()` **sí** aplica aquí. NO lo uses para `fechaEstimada`/`fechaSeguimiento`, que son DATE (Trampa 1).

- [ ] **Step 7: Correr el test y verificar que pasa**

```bash
./gradlew test --tests "pe.quantum.crm.domain.eventos.EventoHistorialTest"
```

Esperado: **PASS**, 5 tests. Si otros tests de eventos fallan al compilar, es por los dos campos nuevos de `EventoDto`: complétalos donde el compilador señale.

- [ ] **Step 8: Suite completa y commit**

```bash
./gradlew ktlintFormat
./gradlew test
git add -A
git commit -m "feat(eventos): listado por empleado, vinculo visible y atribucion por creador"
```

---

## Trampa 4 — la dependencia circular que Spring rechazaría

Léela antes de las Tasks 6 y 7. Se resuelve con una regla simple que ya está aplicada en el diseño; solo tienes que **no romperla**.

`TareaServiceImpl` y `EventoServiceImpl` van a llamar a `AuditoriaActividadService.registrar(...)` cuando editen. Es decir: **`tareas` → `auditoria`**.

Si `AuditoriaActividadServiceImpl` pidiera a su vez `TareaService` en el constructor (por ejemplo para comprobar visibilidad al leer el historial), Spring tendría `tareas → auditoria → tareas` y el contexto **no arrancaría**: `BeanCurrentlyInCreationException`.

**Regla que no debes romper:**

> `AuditoriaActividadServiceImpl` depende **únicamente** de `ActividadAuditoriaRepository` y `EmpleadoService`. **Nunca** de `TareaService` ni `EventoService`.
>
> Por eso `AuditoriaActividadService.historial(...)` **no comprueba visibilidad**: la comprueba quien lo llama (`HistorialActividadServiceImpl`, Task 10), que sí puede depender de `TareaService`/`EventoService` porque nadie depende de él.

`ComentarioActividadServiceImpl` **sí** puede depender de `TareaService`/`EventoService`, porque ningún módulo depende de los comentarios. No hay ciclo ahí.

Grafo final, sin ciclos:

```
tareas ──┐
eventos ─┴──> auditoria ──> (repo, empleados)

comentarios ──> tareas, eventos, empleados, (repo)

historial ──> tareas, eventos, comentarios, auditoria, empleados, empresas
```

---

## Task 6: Servicio de comentarios

**Files:**
- Create: `src/main/kotlin/pe/quantum/crm/domain/actividades/ComentarioActividadService.kt`
- Create: `src/main/kotlin/pe/quantum/crm/domain/actividades/ComentarioActividadServiceImpl.kt`
- Test: `src/test/kotlin/pe/quantum/crm/domain/actividades/ComentarioActividadServiceImplTest.kt`

**Interfaces:**
- Consumes: `TipoActividad`, `ActividadComentario`, `ActividadComentarioRepository` (Task 1); `ComentarioDto` (Task 3); `TareaService.vinculoVisible` (Task 4); `EventoService.vinculoVisible` (Task 5); `EmpleadoService.resumenPorIds(ids: Collection<Long>): Map<Long, EmpleadoResumen>`.
- Produces:
  - `ComentarioActividadService.crear(tipo, idActividad, texto, usuario): ComentarioDto`
  - `ComentarioActividadService.listar(tipo, idActividad, usuario): List<ComentarioDto>`
  - `ComentarioActividadService.contar(tipo, idsActividad): Map<Long, Int>` — **sin** chequeo de visibilidad; solo lo llama el historial, que ya filtró.

- [ ] **Step 1: Escribir el test que falla**

Crea `src/test/kotlin/pe/quantum/crm/domain/actividades/ComentarioActividadServiceImplTest.kt`:

```kotlin
package pe.quantum.crm.domain.actividades

import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import pe.quantum.crm.domain.empleados.EmpleadoService
import pe.quantum.crm.domain.empleados.dto.EmpleadoResumen
import pe.quantum.crm.domain.eventos.EventoService
import pe.quantum.crm.domain.eventos.dto.EventoVinculo
import pe.quantum.crm.domain.tareas.TareaService
import pe.quantum.crm.domain.tareas.dto.TareaVinculo
import pe.quantum.crm.shared.exception.NoEncontradoException
import pe.quantum.crm.shared.exception.ValidacionException
import pe.quantum.crm.shared.security.UsuarioActual
import java.time.LocalDateTime

/**
 * Comentarios de seguimiento. Lo que estos tests protegen:
 *  1. Un comentario NUNCA pisa otro (append-only): es la razon de que exista la
 *     tabla en vez de reusar `descripcion`.
 *  2. No se puede comentar una actividad que no ves (IDOR -> 404).
 */
class ComentarioActividadServiceImplTest {
    private val repository = mockk<ActividadComentarioRepository>()
    private val tareaService = mockk<TareaService>()
    private val eventoService = mockk<EventoService>()
    private val empleadoService = mockk<EmpleadoService>(relaxed = true)
    private val service = ComentarioActividadServiceImpl(repository, tareaService, eventoService, empleadoService)

    private val supervisor = UsuarioActual(id = 1, rol = "gerencia")
    private val vendedor = UsuarioActual(id = 9, rol = "vendedor")

    private fun comentario(
        id: Long,
        texto: String,
        idTarea: Long? = 5,
    ) = ActividadComentario(
        id = id,
        idTarea = idTarea,
        idEvento = null,
        texto = texto,
        createdAt = LocalDateTime.of(2026, 9, 1, 10, 0),
        createdBy = 1,
    )

    @Test
    fun `crear un comentario sobre una tarea visible lo persiste`() {
        every { tareaService.vinculoVisible(5, supervisor) } returns
            TareaVinculo(id = 5, idEmpresa = 3, idOportunidad = null, idAsignado = 7)
        val guardado = slot<ActividadComentario>()
        every { repository.save(capture(guardado)) } answers { comentario(id = 1, texto = guardado.captured.texto) }
        every { empleadoService.resumenPorIds(listOf(1L)) } returns
            mapOf(1L to EmpleadoResumen(id = 1, nombres = "Ana", apellidos = "Ruiz"))

        val dto = service.crear(TipoActividad.tarea, 5, "Llame y no contesto", supervisor)

        assertThat(guardado.captured.idTarea).isEqualTo(5)
        assertThat(guardado.captured.idEvento).isNull()
        assertThat(guardado.captured.texto).isEqualTo("Llame y no contesto")
        assertThat(guardado.captured.createdBy).isEqualTo(1)
        assertThat(dto.autor?.nombres).isEqualTo("Ana")
    }

    @Test
    fun `crear un comentario sobre un evento visible usa la columna de evento`() {
        every { eventoService.vinculoVisible(4, supervisor) } returns
            EventoVinculo(id = 4, idEmpresa = null, idOportunidad = 20, createdBy = 7)
        val guardado = slot<ActividadComentario>()
        every { repository.save(capture(guardado)) } answers {
            ActividadComentario(id = 1, idTarea = null, idEvento = 4, texto = "ok", createdBy = 1)
        }

        service.crear(TipoActividad.evento, 4, "ok", supervisor)

        assertThat(guardado.captured.idEvento).isEqualTo(4)
        assertThat(guardado.captured.idTarea).isNull()
    }

    @Test
    fun `no se puede comentar una tarea que el usuario no ve`() {
        every { tareaService.vinculoVisible(5, vendedor) } throws NoEncontradoException("La tarea no existe")

        assertThatThrownBy { service.crear(TipoActividad.tarea, 5, "hola", vendedor) }
            .isInstanceOf(NoEncontradoException::class.java)

        verify(exactly = 0) { repository.save(any()) }
    }

    @Test
    fun `un comentario en blanco se rechaza antes de tocar la base`() {
        every { tareaService.vinculoVisible(5, supervisor) } returns
            TareaVinculo(id = 5, idEmpresa = 3, idOportunidad = null, idAsignado = 7)

        assertThatThrownBy { service.crear(TipoActividad.tarea, 5, "   ", supervisor) }
            .isInstanceOf(ValidacionException::class.java)

        verify(exactly = 0) { repository.save(any()) }
    }

    @Test
    fun `listar devuelve los comentarios en orden cronologico sin perder ninguno`() {
        every { tareaService.vinculoVisible(5, supervisor) } returns
            TareaVinculo(id = 5, idEmpresa = 3, idOportunidad = null, idAsignado = 7)
        every { repository.findByIdTareaOrderByCreatedAtAsc(5) } returns
            listOf(comentario(1, "primero"), comentario(2, "segundo"))
        every { empleadoService.resumenPorIds(any()) } returns emptyMap()

        val comentarios = service.listar(TipoActividad.tarea, 5, supervisor)

        // El segundo comentario NO reemplaza al primero: los dos siguen ahi.
        assertThat(comentarios.map { it.texto }).containsExactly("primero", "segundo")
    }

    @Test
    fun `contar agrupa los comentarios por actividad`() {
        every { repository.findByIdTareaInOrderByCreatedAtAsc(listOf(5L, 6L)) } returns
            listOf(comentario(1, "a", idTarea = 5), comentario(2, "b", idTarea = 5), comentario(3, "c", idTarea = 6))

        val conteo = service.contar(TipoActividad.tarea, listOf(5L, 6L))

        assertThat(conteo[5L]).isEqualTo(2)
        assertThat(conteo[6L]).isEqualTo(1)
    }

    @Test
    fun `contar sobre una lista vacia no consulta la base`() {
        val conteo = service.contar(TipoActividad.tarea, emptyList())

        assertThat(conteo).isEmpty()
        verify(exactly = 0) { repository.findByIdTareaInOrderByCreatedAtAsc(any()) }
    }
}
```

- [ ] **Step 2: Correr el test y verificar que falla**

```bash
./gradlew test --tests "pe.quantum.crm.domain.actividades.ComentarioActividadServiceImplTest"
```

Esperado: **FALLA en compilación**, `Unresolved reference: ComentarioActividadServiceImpl`.

- [ ] **Step 3: Escribir la interfaz**

Crea `src/main/kotlin/pe/quantum/crm/domain/actividades/ComentarioActividadService.kt`:

```kotlin
package pe.quantum.crm.domain.actividades

import pe.quantum.crm.domain.actividades.dto.ComentarioDto
import pe.quantum.crm.shared.security.UsuarioActual

/**
 * Comentarios de seguimiento sobre tareas y eventos.
 *
 * Append-only: no hay editar ni borrar. Un comentario es un hecho fechado.
 */
interface ComentarioActividadService {
    /**
     * Agrega un comentario. Comprueba que el usuario alcance la actividad:
     * `NoEncontradoException` (404) si no, nunca 403 — es un recurso ajeno.
     */
    fun crear(
        tipo: TipoActividad,
        idActividad: Long,
        texto: String,
        usuario: UsuarioActual,
    ): ComentarioDto

    /** Comentarios de una actividad, del mas antiguo al mas reciente. */
    fun listar(
        tipo: TipoActividad,
        idActividad: Long,
        usuario: UsuarioActual,
    ): List<ComentarioDto>

    /**
     * Cuantos comentarios tiene cada actividad. SIN chequeo de visibilidad: solo
     * lo llama el historial, que ya filtro lo que el usuario puede ver.
     */
    fun contar(
        tipo: TipoActividad,
        idsActividad: Collection<Long>,
    ): Map<Long, Int>
}
```

- [ ] **Step 4: Escribir la implementación**

Crea `src/main/kotlin/pe/quantum/crm/domain/actividades/ComentarioActividadServiceImpl.kt`:

```kotlin
package pe.quantum.crm.domain.actividades

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pe.quantum.crm.domain.actividades.dto.ComentarioDto
import pe.quantum.crm.domain.empleados.EmpleadoService
import pe.quantum.crm.domain.eventos.EventoService
import pe.quantum.crm.domain.tareas.TareaService
import pe.quantum.crm.shared.comoInstanteUtc
import pe.quantum.crm.shared.exception.ValidacionException
import pe.quantum.crm.shared.security.UsuarioActual
import java.time.LocalDateTime

@Service
class ComentarioActividadServiceImpl(
    private val comentarioRepository: ActividadComentarioRepository,
    private val tareaService: TareaService,
    private val eventoService: EventoService,
    private val empleadoService: EmpleadoService,
) : ComentarioActividadService {
    @Transactional
    override fun crear(
        tipo: TipoActividad,
        idActividad: Long,
        texto: String,
        usuario: UsuarioActual,
    ): ComentarioDto {
        // Primero visibilidad (404 si no alcanza), despues validacion del texto:
        // asi un usuario sin acceso no distingue "no existe" de "texto invalido".
        exigirVisible(tipo, idActividad, usuario)
        val limpio = texto.trim()
        if (limpio.isEmpty()) {
            throw ValidacionException("El comentario no puede estar vacío", field = "texto")
        }
        val guardado =
            comentarioRepository.save(
                ActividadComentario(
                    idTarea = idActividad.takeIf { tipo == TipoActividad.tarea },
                    idEvento = idActividad.takeIf { tipo == TipoActividad.evento },
                    texto = limpio,
                    createdAt = LocalDateTime.now(),
                    createdBy = usuario.id,
                ),
            )
        return toDtos(listOf(guardado)).first()
    }

    @Transactional(readOnly = true)
    override fun listar(
        tipo: TipoActividad,
        idActividad: Long,
        usuario: UsuarioActual,
    ): List<ComentarioDto> {
        exigirVisible(tipo, idActividad, usuario)
        val comentarios =
            when (tipo) {
                TipoActividad.tarea -> comentarioRepository.findByIdTareaOrderByCreatedAtAsc(idActividad)
                TipoActividad.evento -> comentarioRepository.findByIdEventoOrderByCreatedAtAsc(idActividad)
            }
        return toDtos(comentarios)
    }

    @Transactional(readOnly = true)
    override fun contar(
        tipo: TipoActividad,
        idsActividad: Collection<Long>,
    ): Map<Long, Int> {
        if (idsActividad.isEmpty()) {
            return emptyMap()
        }
        return when (tipo) {
            TipoActividad.tarea ->
                comentarioRepository
                    .findByIdTareaInOrderByCreatedAtAsc(idsActividad)
                    .groupingBy { requireNotNull(it.idTarea) }
                    .eachCount()

            TipoActividad.evento ->
                comentarioRepository
                    .findByIdEventoInOrderByCreatedAtAsc(idsActividad)
                    .groupingBy { requireNotNull(it.idEvento) }
                    .eachCount()
        }
    }

    // ── privados ───────────────────────────────────────────────

    /** Delega en el modulo dueño de la actividad; lanza 404 si no la alcanza. */
    private fun exigirVisible(
        tipo: TipoActividad,
        idActividad: Long,
        usuario: UsuarioActual,
    ) {
        when (tipo) {
            TipoActividad.tarea -> tareaService.vinculoVisible(idActividad, usuario)
            TipoActividad.evento -> eventoService.vinculoVisible(idActividad, usuario)
        }
    }

    private fun toDtos(comentarios: List<ActividadComentario>): List<ComentarioDto> {
        if (comentarios.isEmpty()) {
            return emptyList()
        }
        val autores = empleadoService.resumenPorIds(comentarios.map { it.createdBy }.distinct())
        return comentarios.map {
            val esTarea = it.idTarea != null
            ComentarioDto(
                id = requireNotNull(it.id),
                tipo = if (esTarea) TipoActividad.tarea.name else TipoActividad.evento.name,
                idActividad = requireNotNull(it.idTarea ?: it.idEvento),
                texto = it.texto,
                createdAt = it.createdAt.comoInstanteUtc(),
                createdBy = it.createdBy,
                autor = autores[it.createdBy],
            )
        }
    }
}
```

- [ ] **Step 5: Correr el test y verificar que pasa**

```bash
./gradlew test --tests "pe.quantum.crm.domain.actividades.ComentarioActividadServiceImplTest"
```

Esperado: **PASS**, 7 tests.

- [ ] **Step 6: Suite completa y commit**

```bash
./gradlew ktlintFormat
./gradlew test
git add -A
git commit -m "feat(actividades): servicio de comentarios de seguimiento append-only"
```

---

## Task 7: Servicio de auditoría

**Files:**
- Create: `src/main/kotlin/pe/quantum/crm/domain/actividades/AuditoriaActividadService.kt`
- Create: `src/main/kotlin/pe/quantum/crm/domain/actividades/AuditoriaActividadServiceImpl.kt`
- Test: `src/test/kotlin/pe/quantum/crm/domain/actividades/AuditoriaActividadServiceImplTest.kt`

**Interfaces:**
- Consumes: `TipoActividad`, `ActividadAuditoria`, `ActividadAuditoriaRepository` (Tasks 1-2); `CambioCampo`, `CambioAuditoriaDto` (Task 3); `EmpleadoService`.
- Produces:
  - `AuditoriaActividadService.registrar(tipo, idActividad, cambios: List<CambioCampo>, idActor: Long)` — no devuelve nada.
  - `AuditoriaActividadService.historial(tipo, idActividad): List<CambioAuditoriaDto>` — **sin chequeo de visibilidad** (Trampa 4).

> **RECUERDA LA TRAMPA 4:** este `Impl` recibe en el constructor **solo** `ActividadAuditoriaRepository` y `EmpleadoService`. Si añades `TareaService` o `EventoService`, Spring no arranca.

- [ ] **Step 1: Escribir el test que falla**

Crea `src/test/kotlin/pe/quantum/crm/domain/actividades/AuditoriaActividadServiceImplTest.kt`:

```kotlin
package pe.quantum.crm.domain.actividades

import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import pe.quantum.crm.domain.actividades.dto.CambioCampo
import pe.quantum.crm.domain.empleados.EmpleadoService
import pe.quantum.crm.domain.empleados.dto.EmpleadoResumen
import java.time.LocalDateTime

/**
 * Auditoria de ediciones. Lo que estos tests protegen:
 *  1. Se guarda UNA fila por campo cambiado, con valor anterior Y nuevo: sin eso
 *     el historial no responde "que cambio", solo "algo cambio".
 *  2. Una edicion que no cambia nada no ensucia la auditoria.
 */
class AuditoriaActividadServiceImplTest {
    private val repository = mockk<ActividadAuditoriaRepository>(relaxed = true)
    private val empleadoService = mockk<EmpleadoService>(relaxed = true)
    private val service = AuditoriaActividadServiceImpl(repository, empleadoService)

    @Test
    fun `registrar guarda una fila por campo cambiado`() {
        val guardadas = slot<List<ActividadAuditoria>>()
        every { repository.saveAll(capture(guardadas)) } answers { guardadas.captured }

        service.registrar(
            tipo = TipoActividad.tarea,
            idActividad = 5,
            cambios =
                listOf(
                    CambioCampo("descripcion", "vieja", "nueva"),
                    CambioCampo("fecha_ejecucion", "2026-09-01T10:00:00Z", "2026-09-05T10:00:00Z"),
                ),
            idActor = 1,
        )

        assertThat(guardadas.captured).hasSize(2)
        assertThat(guardadas.captured.map { it.campo }).containsExactly("descripcion", "fecha_ejecucion")
        assertThat(guardadas.captured.first().valorAnterior).isEqualTo("vieja")
        assertThat(guardadas.captured.first().valorNuevo).isEqualTo("nueva")
        assertThat(guardadas.captured).allSatisfy {
            assertThat(it.idTarea).isEqualTo(5)
            assertThat(it.idEvento).isNull()
            assertThat(it.changedBy).isEqualTo(1)
        }
    }

    @Test
    fun `registrar sobre un evento usa la columna de evento`() {
        val guardadas = slot<List<ActividadAuditoria>>()
        every { repository.saveAll(capture(guardadas)) } answers { guardadas.captured }

        service.registrar(TipoActividad.evento, 4, listOf(CambioCampo("descripcion", null, "x")), idActor = 1)

        assertThat(guardadas.captured.first().idEvento).isEqualTo(4)
        assertThat(guardadas.captured.first().idTarea).isNull()
    }

    @Test
    fun `una edicion sin cambios no escribe nada`() {
        service.registrar(TipoActividad.tarea, 5, emptyList(), idActor = 1)

        verify(exactly = 0) { repository.saveAll(any<List<ActividadAuditoria>>()) }
    }

    @Test
    fun `el historial devuelve el autor resuelto`() {
        every { repository.findByIdTareaOrderByChangedAtDesc(5) } returns
            listOf(
                ActividadAuditoria(
                    id = 1,
                    idTarea = 5,
                    campo = "descripcion",
                    valorAnterior = "vieja",
                    valorNuevo = "nueva",
                    changedAt = LocalDateTime.of(2026, 9, 5, 12, 0),
                    changedBy = 1,
                ),
            )
        every { empleadoService.resumenPorIds(listOf(1L)) } returns
            mapOf(1L to EmpleadoResumen(id = 1, nombres = "Ana", apellidos = "Ruiz"))

        val historial = service.historial(TipoActividad.tarea, 5)

        assertThat(historial).hasSize(1)
        assertThat(historial.first().campo).isEqualTo("descripcion")
        assertThat(historial.first().valorAnterior).isEqualTo("vieja")
        assertThat(historial.first().autor?.nombres).isEqualTo("Ana")
    }
}
```

- [ ] **Step 2: Correr el test y verificar que falla**

```bash
./gradlew test --tests "pe.quantum.crm.domain.actividades.AuditoriaActividadServiceImplTest"
```

Esperado: **FALLA en compilación**.

- [ ] **Step 3: Escribir la interfaz**

Crea `src/main/kotlin/pe/quantum/crm/domain/actividades/AuditoriaActividadService.kt`:

```kotlin
package pe.quantum.crm.domain.actividades

import pe.quantum.crm.domain.actividades.dto.CambioAuditoriaDto
import pe.quantum.crm.domain.actividades.dto.CambioCampo

/**
 * Auditoria de ediciones sobre tareas y eventos.
 *
 * IMPORTANTE (dependencia circular): la implementacion de esta interfaz NO puede
 * depender de `TareaService` ni de `EventoService`, porque esos dos modulos
 * dependen de ESTA para registrar sus ediciones. Por eso `historial` no
 * comprueba visibilidad: la comprueba quien lo llama.
 */
interface AuditoriaActividadService {
    /**
     * Registra una fila por cambio. Lista vacia = no-op (una edicion que no
     * modifico nada no debe ensuciar el historial).
     *
     * Se llama DENTRO de la transaccion de la edicion: si esta se revierte, la
     * auditoria se revierte con ella.
     */
    fun registrar(
        tipo: TipoActividad,
        idActividad: Long,
        cambios: List<CambioCampo>,
        idActor: Long,
    )

    /** Cambios de una actividad, del mas reciente al mas antiguo. Sin guard de visibilidad. */
    fun historial(
        tipo: TipoActividad,
        idActividad: Long,
    ): List<CambioAuditoriaDto>
}
```

- [ ] **Step 4: Escribir la implementación**

Crea `src/main/kotlin/pe/quantum/crm/domain/actividades/AuditoriaActividadServiceImpl.kt`:

```kotlin
package pe.quantum.crm.domain.actividades

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pe.quantum.crm.domain.actividades.dto.CambioAuditoriaDto
import pe.quantum.crm.domain.actividades.dto.CambioCampo
import pe.quantum.crm.domain.empleados.EmpleadoService
import pe.quantum.crm.shared.comoInstanteUtc
import java.time.LocalDateTime

/**
 * Solo dos dependencias, y es a proposito: ver la nota de la interfaz sobre la
 * dependencia circular con `tareas` y `eventos`. NO agregues `TareaService`
 * ni `EventoService` aqui.
 */
@Service
class AuditoriaActividadServiceImpl(
    private val auditoriaRepository: ActividadAuditoriaRepository,
    private val empleadoService: EmpleadoService,
) : AuditoriaActividadService {
    @Transactional
    override fun registrar(
        tipo: TipoActividad,
        idActividad: Long,
        cambios: List<CambioCampo>,
        idActor: Long,
    ) {
        if (cambios.isEmpty()) {
            return
        }
        val ahora = LocalDateTime.now()
        auditoriaRepository.saveAll(
            cambios.map {
                ActividadAuditoria(
                    idTarea = idActividad.takeIf { _ -> tipo == TipoActividad.tarea },
                    idEvento = idActividad.takeIf { _ -> tipo == TipoActividad.evento },
                    campo = it.campo,
                    valorAnterior = it.valorAnterior,
                    valorNuevo = it.valorNuevo,
                    changedAt = ahora,
                    changedBy = idActor,
                )
            },
        )
    }

    @Transactional(readOnly = true)
    override fun historial(
        tipo: TipoActividad,
        idActividad: Long,
    ): List<CambioAuditoriaDto> {
        val filas =
            when (tipo) {
                TipoActividad.tarea -> auditoriaRepository.findByIdTareaOrderByChangedAtDesc(idActividad)
                TipoActividad.evento -> auditoriaRepository.findByIdEventoOrderByChangedAtDesc(idActividad)
            }
        if (filas.isEmpty()) {
            return emptyList()
        }
        val autores = empleadoService.resumenPorIds(filas.map { it.changedBy }.distinct())
        return filas.map {
            CambioAuditoriaDto(
                id = requireNotNull(it.id),
                campo = it.campo,
                valorAnterior = it.valorAnterior,
                valorNuevo = it.valorNuevo,
                changedAt = it.changedAt.comoInstanteUtc(),
                changedBy = it.changedBy,
                autor = autores[it.changedBy],
            )
        }
    }
}
```

- [ ] **Step 5: Correr el test y verificar que pasa**

```bash
./gradlew test --tests "pe.quantum.crm.domain.actividades.AuditoriaActividadServiceImplTest"
```

Esperado: **PASS**, 4 tests.

- [ ] **Step 6: Suite completa y commit**

```bash
./gradlew ktlintFormat
./gradlew test
git add -A
git commit -m "feat(actividades): servicio de auditoria de ediciones campo a campo"
```

---

## Task 8: `tareas` registra auditoría al editar

Se añade una dependencia al constructor de `TareaServiceImpl`. **Eso rompe en compilación todos los tests que lo construyen** — es esperado y el Step 5 los arregla uno a uno.

**Files:**
- Modify: `src/main/kotlin/pe/quantum/crm/domain/tareas/TareaServiceImpl.kt`
- Modify (tests existentes, solo el constructor): `TareaListadoSpecificationTest.kt`, `TareaColaboracionTest.kt`, `TareaServiceImplTest.kt`, `TareaServiceImplCicloVidaTest.kt`, `TareaHistorialTest.kt`
- Test: `src/test/kotlin/pe/quantum/crm/domain/tareas/TareaAuditoriaTest.kt`

**Interfaces:**
- Consumes: `AuditoriaActividadService.registrar(...)`, `TipoActividad`, `CambioCampo` (Tasks 3 y 7).
- Produces: nada nuevo hacia fuera. `TareaServiceImpl` gana un 8.º parámetro de constructor: `private val auditoriaService: AuditoriaActividadService`, **el último de la lista**.

- [ ] **Step 1: Escribir el test que falla**

Crea `src/test/kotlin/pe/quantum/crm/domain/tareas/TareaAuditoriaTest.kt`:

```kotlin
package pe.quantum.crm.domain.tareas

import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import pe.quantum.crm.domain.actividades.AuditoriaActividadService
import pe.quantum.crm.domain.actividades.TipoActividad
import pe.quantum.crm.domain.actividades.dto.CambioCampo
import pe.quantum.crm.domain.contactos.ContactoService
import pe.quantum.crm.domain.empleados.EmpleadoService
import pe.quantum.crm.domain.empresas.EmpresaService
import pe.quantum.crm.domain.notificaciones.NotificacionService
import pe.quantum.crm.domain.oportunidades.OportunidadService
import pe.quantum.crm.domain.tareas.dto.ActualizarTareaRequest
import pe.quantum.crm.shared.enums.EstadoAccion
import pe.quantum.crm.shared.enums.TipoAccion
import pe.quantum.crm.shared.security.UsuarioActual
import java.time.Instant
import java.time.LocalDateTime
import java.util.Optional

/**
 * Editar una tarea deja rastro de QUE cambio, no solo de que algo cambio.
 * Se auditan TODAS las ediciones, tambien las del propio dueno: un historial
 * con huecos no se puede leer.
 */
class TareaAuditoriaTest {
    private val tareaRepository = mockk<TareaRepository>()
    private val tareaResponsableRepository = mockk<TareaResponsableRepository>(relaxed = true)
    private val empresaService = mockk<EmpresaService>(relaxed = true)
    private val oportunidadService = mockk<OportunidadService>(relaxed = true)
    private val contactoService = mockk<ContactoService>(relaxed = true)
    private val empleadoService = mockk<EmpleadoService>(relaxed = true)
    private val notificacionService = mockk<NotificacionService>(relaxed = true)
    private val auditoriaService = mockk<AuditoriaActividadService>(relaxed = true)
    private val service =
        TareaServiceImpl(
            tareaRepository,
            tareaResponsableRepository,
            empresaService,
            oportunidadService,
            contactoService,
            empleadoService,
            notificacionService,
            auditoriaService,
        )

    private val supervisor = UsuarioActual(id = 1, rol = "gerencia")

    private fun tarea() =
        Tarea(
            id = 5,
            idEmpresa = 3,
            idOportunidad = null,
            idAsignado = 7,
            tipoAccion = TipoAccion.llamada,
            estadoAccion = EstadoAccion.pendiente,
            descripcion = "descripcion vieja",
            fechaEjecucion = LocalDateTime.of(2026, 9, 1, 10, 0),
            createdAt = LocalDateTime.of(2026, 8, 1, 10, 0),
            createdBy = 7,
            updatedAt = LocalDateTime.of(2026, 8, 1, 10, 0),
            updatedBy = 7,
        )

    private fun prepararEdicion() {
        every { tareaRepository.findById(5) } returns Optional.of(tarea())
        every { tareaRepository.save(any()) } answers { firstArg() }
        every { empresaService.resumenPorIds(any()) } returns emptyMap()
        every { contactoService.resumenPorIds(any()) } returns emptyMap()
        every { empleadoService.resumenPorIds(any()) } returns emptyMap()
        every { tareaResponsableRepository.findByIdIdTareaIn(any()) } returns emptyList()
    }

    @Test
    fun `cambiar la descripcion registra el valor anterior y el nuevo`() {
        prepararEdicion()
        val cambios = slot<List<CambioCampo>>()
        every { auditoriaService.registrar(TipoActividad.tarea, 5, capture(cambios), 1) } returns Unit

        service.actualizar(5, ActualizarTareaRequest(descripcion = "descripcion nueva"), supervisor)

        val descripcion = cambios.captured.single { it.campo == "descripcion" }
        assertThat(descripcion.valorAnterior).isEqualTo("descripcion vieja")
        assertThat(descripcion.valorNuevo).isEqualTo("descripcion nueva")
    }

    @Test
    fun `reprogramar registra el cambio de fecha_ejecucion`() {
        prepararEdicion()
        val cambios = slot<List<CambioCampo>>()
        every { auditoriaService.registrar(TipoActividad.tarea, 5, capture(cambios), 1) } returns Unit

        service.actualizar(
            5,
            ActualizarTareaRequest(fechaEjecucion = Instant.parse("2026-09-10T15:00:00Z")),
            supervisor,
        )

        assertThat(cambios.captured.map { it.campo }).contains("fecha_ejecucion")
    }

    @Test
    fun `una edicion que no cambia nada no registra auditoria`() {
        prepararEdicion()
        val cambios = slot<List<CambioCampo>>()
        every { auditoriaService.registrar(TipoActividad.tarea, 5, capture(cambios), 1) } returns Unit

        // Se manda exactamente el mismo valor que ya tenia.
        service.actualizar(5, ActualizarTareaRequest(descripcion = "descripcion vieja"), supervisor)

        assertThat(cambios.captured).isEmpty()
    }

    @Test
    fun `la auditoria se registra con el id del actor, no del dueno de la tarea`() {
        prepararEdicion()

        service.actualizar(5, ActualizarTareaRequest(descripcion = "otra"), supervisor)

        // La tarea es del empleado 7; quien edita es el 1. Se guarda el 1.
        verify { auditoriaService.registrar(TipoActividad.tarea, 5, any(), 1) }
    }
}
```

- [ ] **Step 2: Correr el test y verificar que falla**

```bash
./gradlew test --tests "pe.quantum.crm.domain.tareas.TareaAuditoriaTest"
```

Esperado: **FALLA en compilación** — `TareaServiceImpl` aún acepta 7 parámetros, no 8.

- [ ] **Step 3: Agregar la dependencia al constructor**

En `src/main/kotlin/pe/quantum/crm/domain/tareas/TareaServiceImpl.kt`, agrega los imports:

```kotlin
import pe.quantum.crm.domain.actividades.AuditoriaActividadService
import pe.quantum.crm.domain.actividades.TipoActividad
import pe.quantum.crm.domain.actividades.dto.CambioCampo
```

Y agrega el parámetro **al final** de la lista del constructor, después de `notificacionService`:

```kotlin
    private val notificacionService: NotificacionService,
    private val auditoriaService: AuditoriaActividadService,
) : TareaService {
```

- [ ] **Step 4: Capturar el snapshot y registrar el diff en `actualizar`**

En el método `actualizar`, haz **tres** ediciones puntuales.

**(a)** Justo después del guard de estado pendiente, agrega la línea del snapshot:

```kotlin
        if (tarea.estadoAccion != EstadoAccion.pendiente) {
            throw EstadoInvalidoException("Solo se pueden editar tareas pendientes")
        }
        // Snapshot ANTES de mutar: la auditoria compara contra estos valores.
        val antes = InstantaneaTarea(tarea)
```

**(b)** Justo después de `val actualizada = tareaRepository.save(tarea)`, agrega el registro. Va **dentro** de la misma transacción, así que si la edición se revierte, la auditoría se revierte con ella:

```kotlin
        val actualizada = tareaRepository.save(tarea)
        auditoriaService.registrar(TipoActividad.tarea, id, antes.diffContra(actualizada), usuario.id)
```

**(c)** Agrega esta clase privada al final del archivo, **fuera** de `TareaServiceImpl` (nivel superior del archivo, después de la llave de cierre de la clase):

```kotlin
/**
 * Valores de una tarea antes de editarla. Existe porque `actualizar` muta la
 * entidad en sitio: sin copiar antes, no hay con que comparar despues.
 *
 * Todo se guarda como texto porque `actividad_auditoria` almacena cualquier
 * campo con la misma forma (ver V49).
 */
private class InstantaneaTarea(
    tarea: Tarea,
) {
    private val tipoAccion: String = tarea.tipoAccion.name
    private val descripcion: String? = tarea.descripcion
    private val fechaEjecucion: String? = tarea.fechaEjecucion?.toString()
    private val idContacto: String? = tarea.idContacto?.toString()
    private val idAsignado: String? = tarea.idAsignado?.toString()

    /** Un `CambioCampo` por campo que de verdad cambio; lista vacia si no cambio nada. */
    fun diffContra(tarea: Tarea): List<CambioCampo> =
        listOfNotNull(
            cambio("tipo_accion", tipoAccion, tarea.tipoAccion.name),
            cambio("descripcion", descripcion, tarea.descripcion),
            cambio("fecha_ejecucion", fechaEjecucion, tarea.fechaEjecucion?.toString()),
            cambio("id_contacto", idContacto, tarea.idContacto?.toString()),
            cambio("id_asignado", idAsignado, tarea.idAsignado?.toString()),
        )

    private fun cambio(
        campo: String,
        anterior: String?,
        nuevo: String?,
    ): CambioCampo? = if (anterior == nuevo) null else CambioCampo(campo, anterior, nuevo)
}
```

- [ ] **Step 5: Arreglar los tests existentes que construyen `TareaServiceImpl`**

El constructor tiene un parámetro más, así que **cinco** archivos de test dejan de compilar. En **cada uno**:

1. Agrega el import:

```kotlin
import pe.quantum.crm.domain.actividades.AuditoriaActividadService
```

2. Agrega el mock junto a los demás:

```kotlin
    private val auditoriaService = mockk<AuditoriaActividadService>(relaxed = true)
```

3. Agrega `auditoriaService` como **último argumento** de la llamada a `TareaServiceImpl(...)`.

Archivos a arreglar:

- `src/test/kotlin/pe/quantum/crm/domain/tareas/TareaListadoSpecificationTest.kt`
- `src/test/kotlin/pe/quantum/crm/domain/tareas/TareaColaboracionTest.kt`
- `src/test/kotlin/pe/quantum/crm/domain/tareas/TareaServiceImplTest.kt`
- `src/test/kotlin/pe/quantum/crm/domain/tareas/TareaServiceImplCicloVidaTest.kt`
- `src/test/kotlin/pe/quantum/crm/domain/tareas/TareaHistorialTest.kt` (el que creaste en la Task 4)

> Compila después de cada archivo si te ayuda: `./gradlew compileTestKotlin`. El compilador te dice exactamente cuál falta.

- [ ] **Step 6: Correr el test y verificar que pasa**

```bash
./gradlew test --tests "pe.quantum.crm.domain.tareas.TareaAuditoriaTest"
```

Esperado: **PASS**, 4 tests.

- [ ] **Step 7: Suite completa y commit**

```bash
./gradlew ktlintFormat
./gradlew test
git add -A
git commit -m "feat(tareas): registrar auditoria de cambios al editar una tarea"
```

---

## Task 9: `eventos` registra auditoría al editar

Espejo de la Task 8. `ActualizarEventoRequest` solo permite tres campos: `fechaEstimada`, `fechaSeguimiento`, `descripcion`. Esos son los que se auditan.

**Files:**
- Modify: `src/main/kotlin/pe/quantum/crm/domain/eventos/EventoServiceImpl.kt`
- Modify (tests existentes, solo el constructor): `EventoServiceImplTest.kt`, `EventoServiceImplCicloVidaTest.kt`, `EventoHistorialTest.kt`
- Test: `src/test/kotlin/pe/quantum/crm/domain/eventos/EventoAuditoriaTest.kt`

**Interfaces:**
- Consumes: `AuditoriaActividadService`, `TipoActividad`, `CambioCampo`.
- Produces: `EventoServiceImpl` gana un 7.º parámetro de constructor: `private val auditoriaService: AuditoriaActividadService`, **el último**.

- [ ] **Step 1: Escribir el test que falla**

Crea `src/test/kotlin/pe/quantum/crm/domain/eventos/EventoAuditoriaTest.kt`:

```kotlin
package pe.quantum.crm.domain.eventos

import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import pe.quantum.crm.domain.actividades.AuditoriaActividadService
import pe.quantum.crm.domain.actividades.TipoActividad
import pe.quantum.crm.domain.actividades.dto.CambioCampo
import pe.quantum.crm.domain.catalogoeventos.CatalogoEventoService
import pe.quantum.crm.domain.empleados.EmpleadoService
import pe.quantum.crm.domain.empresas.EmpresaService
import pe.quantum.crm.domain.eventos.dto.ActualizarEventoRequest
import pe.quantum.crm.domain.notificaciones.NotificacionService
import pe.quantum.crm.domain.oportunidades.OportunidadService
import pe.quantum.crm.domain.oportunidades.dto.OportunidadVinculo
import pe.quantum.crm.shared.enums.EstadoEvento
import pe.quantum.crm.shared.security.UsuarioActual
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.Optional

/** Editar un evento deja rastro de que campo cambio y quien lo cambio. */
class EventoAuditoriaTest {
    private val eventoRepository = mockk<EventoRepository>()
    private val catalogoEventoService = mockk<CatalogoEventoService>(relaxed = true)
    private val oportunidadService = mockk<OportunidadService>(relaxed = true)
    private val empresaService = mockk<EmpresaService>(relaxed = true)
    private val empleadoService = mockk<EmpleadoService>(relaxed = true)
    private val notificacionService = mockk<NotificacionService>(relaxed = true)
    private val auditoriaService = mockk<AuditoriaActividadService>(relaxed = true)
    private val service =
        EventoServiceImpl(
            eventoRepository,
            catalogoEventoService,
            oportunidadService,
            empresaService,
            empleadoService,
            notificacionService,
            auditoriaService,
        )

    private val supervisor = UsuarioActual(id = 1, rol = "gerencia")

    private fun evento() =
        Evento(
            id = 4,
            idOportunidad = 20,
            idEmpresa = null,
            idCatalogoEvento = null,
            esPersonalizado = true,
            nombrePersonalizado = "Visita a planta",
            descripcion = "nota vieja",
            estado = EstadoEvento.pendiente,
            fechaEstimada = LocalDate.of(2026, 9, 10),
            createdAt = LocalDateTime.of(2026, 8, 1, 10, 0),
            createdBy = 7,
            updatedAt = LocalDateTime.of(2026, 8, 1, 10, 0),
            updatedBy = 7,
        )

    private fun prepararEdicion() {
        every { eventoRepository.findById(4) } returns Optional.of(evento())
        every { eventoRepository.save(any()) } answers { firstArg() }
        every { oportunidadService.vinculoVisible(20, supervisor) } returns
            OportunidadVinculo(id = 20, idEmpresa = 3, idVendedor = 7, estado = "abierto")
        every { catalogoEventoService.todosPorId() } returns emptyMap()
    }

    @Test
    fun `cambiar la descripcion registra el valor anterior y el nuevo`() {
        prepararEdicion()
        val cambios = slot<List<CambioCampo>>()
        every { auditoriaService.registrar(TipoActividad.evento, 4, capture(cambios), 1) } returns Unit

        service.actualizar(4, ActualizarEventoRequest(descripcion = "nota nueva"), supervisor)

        val descripcion = cambios.captured.single { it.campo == "descripcion" }
        assertThat(descripcion.valorAnterior).isEqualTo("nota vieja")
        assertThat(descripcion.valorNuevo).isEqualTo("nota nueva")
    }

    @Test
    fun `cambiar la fecha estimada se registra como dia, sin hora`() {
        prepararEdicion()
        val cambios = slot<List<CambioCampo>>()
        every { auditoriaService.registrar(TipoActividad.evento, 4, capture(cambios), 1) } returns Unit

        service.actualizar(4, ActualizarEventoRequest(fechaEstimada = LocalDate.of(2026, 9, 20)), supervisor)

        val fecha = cambios.captured.single { it.campo == "fecha_estimada" }
        // `fecha_estimada` es columna DATE: se audita el dia tal cual, sin
        // convertirlo a instante (ver shared/TiempoUtc.kt).
        assertThat(fecha.valorAnterior).isEqualTo("2026-09-10")
        assertThat(fecha.valorNuevo).isEqualTo("2026-09-20")
    }

    @Test
    fun `una edicion que no cambia nada no registra auditoria`() {
        prepararEdicion()
        val cambios = slot<List<CambioCampo>>()
        every { auditoriaService.registrar(TipoActividad.evento, 4, capture(cambios), 1) } returns Unit

        service.actualizar(4, ActualizarEventoRequest(descripcion = "nota vieja"), supervisor)

        assertThat(cambios.captured).isEmpty()
    }

    @Test
    fun `la auditoria se registra con el id del actor`() {
        prepararEdicion()

        service.actualizar(4, ActualizarEventoRequest(descripcion = "otra"), supervisor)

        verify { auditoriaService.registrar(TipoActividad.evento, 4, any(), 1) }
    }
}
```

- [ ] **Step 2: Correr el test y verificar que falla**

```bash
./gradlew test --tests "pe.quantum.crm.domain.eventos.EventoAuditoriaTest"
```

Esperado: **FALLA en compilación** — el constructor aún acepta 6 parámetros.

- [ ] **Step 3: Agregar la dependencia al constructor**

En `src/main/kotlin/pe/quantum/crm/domain/eventos/EventoServiceImpl.kt`, agrega los imports:

```kotlin
import pe.quantum.crm.domain.actividades.AuditoriaActividadService
import pe.quantum.crm.domain.actividades.TipoActividad
import pe.quantum.crm.domain.actividades.dto.CambioCampo
```

Y el parámetro **al final** del constructor, después de `notificacionService`:

```kotlin
    private val notificacionService: NotificacionService,
    private val auditoriaService: AuditoriaActividadService,
) : EventoService {
```

- [ ] **Step 4: Capturar el snapshot y registrar el diff en `actualizar`**

**(a)** Justo después del guard de estado pendiente:

```kotlin
        if (evento.estado != EstadoEvento.pendiente) {
            throw EstadoInvalidoException("Solo se pueden editar eventos pendientes")
        }
        // Snapshot ANTES de mutar: la auditoria compara contra estos valores.
        val antes = InstantaneaEvento(evento)
```

**(b)** Cambia el `return` final del método. Antes era:

```kotlin
        return eventoRepository.save(evento).toDto()
```

Ahora:

```kotlin
        val actualizado = eventoRepository.save(evento)
        auditoriaService.registrar(TipoActividad.evento, id, antes.diffContra(actualizado), usuario.id)
        return actualizado.toDto()
```

> Ojo: `actualizar` es el ÚNICO método que se toca. `marcarOcurrido` y `marcarDescartado` **no** se auditan en este plan: son transiciones de estado, no ediciones, y ya quedan registradas en el propio `estado` del evento.

**(c)** Agrega esta clase privada al final del archivo, **fuera** de `EventoServiceImpl`:

```kotlin
/**
 * Valores de un evento antes de editarlo. Solo los tres campos que
 * `ActualizarEventoRequest` permite tocar.
 *
 * `fechaEstimada` y `fechaSeguimiento` son columnas DATE: se auditan como el dia
 * que son (`toString()` da `2026-09-20`), sin convertirlos a instante — ver la
 * advertencia de `shared/TiempoUtc.kt`.
 */
private class InstantaneaEvento(
    evento: Evento,
) {
    private val descripcion: String? = evento.descripcion
    private val fechaEstimada: String? = evento.fechaEstimada?.toString()
    private val fechaSeguimiento: String? = evento.fechaSeguimiento?.toString()

    fun diffContra(evento: Evento): List<CambioCampo> =
        listOfNotNull(
            cambio("descripcion", descripcion, evento.descripcion),
            cambio("fecha_estimada", fechaEstimada, evento.fechaEstimada?.toString()),
            cambio("fecha_seguimiento", fechaSeguimiento, evento.fechaSeguimiento?.toString()),
        )

    private fun cambio(
        campo: String,
        anterior: String?,
        nuevo: String?,
    ): CambioCampo? = if (anterior == nuevo) null else CambioCampo(campo, anterior, nuevo)
}
```

- [ ] **Step 5: Arreglar los tests existentes que construyen `EventoServiceImpl`**

En **cada uno** de estos archivos: agrega el import `pe.quantum.crm.domain.actividades.AuditoriaActividadService`, el mock `private val auditoriaService = mockk<AuditoriaActividadService>(relaxed = true)`, y pásalo como **último argumento** de `EventoServiceImpl(...)`.

- `src/test/kotlin/pe/quantum/crm/domain/eventos/EventoServiceImplTest.kt`
- `src/test/kotlin/pe/quantum/crm/domain/eventos/EventoServiceImplCicloVidaTest.kt`
- `src/test/kotlin/pe/quantum/crm/domain/eventos/EventoHistorialTest.kt` (el de la Task 5)

- [ ] **Step 6: Correr el test y verificar que pasa**

```bash
./gradlew test --tests "pe.quantum.crm.domain.eventos.EventoAuditoriaTest"
```

Esperado: **PASS**, 4 tests.

- [ ] **Step 7: Verificar que ArchUnit sigue contento**

Este es el momento de verdad de la regla 12: `tareas` y `eventos` ahora dependen del módulo `actividades`. Debe pasar porque solo tocan una **interfaz** (`AuditoriaActividadService`), un **enum** (`TipoActividad`) y un **DTO** (`CambioCampo`).

```bash
./gradlew test --tests "pe.quantum.crm.arquitectura.ArquitecturaModulosTest"
```

Esperado: **PASS**, 5 tests. Si falla, has importado una entidad, un repositorio o un `*Impl` de `actividades` — quita ese import; la dependencia va siempre a la interfaz.

- [ ] **Step 8: Suite completa y commit**

```bash
./gradlew ktlintFormat
./gradlew test
git add -A
git commit -m "feat(eventos): registrar auditoria de cambios al editar un evento"
```

---

## Task 10: Servicio del historial unificado

El corazón del plan. Compone tareas + eventos, aplica el guard de permisos, une, ordena y pagina.

**Files:**
- Create: `src/main/kotlin/pe/quantum/crm/domain/actividades/HistorialActividadService.kt`
- Create: `src/main/kotlin/pe/quantum/crm/domain/actividades/HistorialActividadServiceImpl.kt`
- Test: `src/test/kotlin/pe/quantum/crm/domain/actividades/HistorialActividadServiceImplTest.kt`

**Interfaces:**
- Consumes: `TareaService.listarPorEmpleado`/`vinculoVisible` (Task 4); `EventoService.listarPorEmpleado`/`vinculoVisible` (Task 5); `ComentarioActividadService.contar` (Task 6); `AuditoriaActividadService.historial` (Task 7); `EmpresaService.resumenPorIds`; `EmpleadoService.resumenPorIds`; `Paginacion`/`Paginado`/`PageMeta` de `shared`.
- Produces:
  - `HistorialActividadService.historial(filtros: HistorialFiltros, usuario: UsuarioActual, page: Int?, perPage: Int?): Paginado<ActividadDto>`
  - `HistorialActividadService.auditoria(tipo: TipoActividad, idActividad: Long, usuario: UsuarioActual): List<CambioAuditoriaDto>`

**Reglas de mapeo, exactas — no improvises:**

| Campo de `ActividadDto` | Desde una tarea | Desde un evento |
|---|---|---|
| `tipo` | `"tarea"` | `"evento"` |
| `titulo` | `tipoAccion` | `nombre` |
| `estado` | `estadoAccion` | `estado` |
| `fechaHora` | `fechaEjecucion` | `fechaOcurrencia` |
| `fechaDia` | **siempre `null`** | `fechaEstimada` |
| `idEmpleado` | `idAsignado` | `createdBy` (Trampa 3) |
| `createdAt` | `createdAt` | `createdAt` |

> Un evento vinculado a una oportunidad tiene `idEmpresa = null` (cuelga de la oportunidad, no de la empresa). En ese caso `empresa` viene `null` y es correcto — el frontend puede resolver la empresa por la oportunidad.

- [ ] **Step 1: Escribir el test que falla**

Crea `src/test/kotlin/pe/quantum/crm/domain/actividades/HistorialActividadServiceImplTest.kt`:

```kotlin
package pe.quantum.crm.domain.actividades

import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import pe.quantum.crm.domain.actividades.dto.HistorialFiltros
import pe.quantum.crm.domain.empleados.EmpleadoService
import pe.quantum.crm.domain.empresas.EmpresaService
import pe.quantum.crm.domain.eventos.EventoService
import pe.quantum.crm.domain.eventos.dto.EventoDto
import pe.quantum.crm.domain.tareas.TareaService
import pe.quantum.crm.domain.tareas.dto.TareaDto
import pe.quantum.crm.shared.exception.PermisoInsuficienteException
import pe.quantum.crm.shared.security.UsuarioActual
import java.time.Instant
import java.time.LocalDate

/**
 * Historial unificado. Lo que estos tests protegen:
 *  1. Un rol NO supervisor no puede leer la agenda de otro (403).
 *  2. Tareas y eventos se mezclan y se ordenan por `created_at`, no por lista.
 *  3. Las fechas DATE y TIMESTAMP no se cruzan (Trampa 1).
 */
class HistorialActividadServiceImplTest {
    private val tareaService = mockk<TareaService>(relaxed = true)
    private val eventoService = mockk<EventoService>(relaxed = true)
    private val comentarioService = mockk<ComentarioActividadService>(relaxed = true)
    private val auditoriaService = mockk<AuditoriaActividadService>(relaxed = true)
    private val empresaService = mockk<EmpresaService>(relaxed = true)
    private val empleadoService = mockk<EmpleadoService>(relaxed = true)
    private val service =
        HistorialActividadServiceImpl(
            tareaService,
            eventoService,
            comentarioService,
            auditoriaService,
            empresaService,
            empleadoService,
        )

    private val gerencia = UsuarioActual(id = 1, rol = "gerencia")
    private val jdv = UsuarioActual(id = 2, rol = "jdv")
    private val vendedor = UsuarioActual(id = 9, rol = "vendedor")

    private fun tareaDto(
        id: Long,
        createdAt: Instant,
    ) = TareaDto(
        id = id, idEmpresa = 3, empresa = null, idOportunidad = null, idContacto = null,
        contacto = null, idAsignado = 7, asignado = null, idsColaboradores = emptyList(),
        colaboradores = emptyList(), tipoAccion = "llamada", estadoAccion = "pendiente",
        descripcion = null, fechaEjecucion = Instant.parse("2026-09-15T15:00:00Z"), createdAt = createdAt,
    )

    private fun eventoDto(
        id: Long,
        createdAt: Instant,
    ) = EventoDto(
        id = id, idOportunidad = 20, idEmpresa = null, idCatalogoEvento = null,
        nombre = "Visita a planta", esPersonalizado = true, descripcion = null,
        estado = "pendiente", fechaEstimada = LocalDate.of(2026, 9, 20), fechaSeguimiento = null,
        fechaOcurrencia = null, disparaCambioEstado = false, estadoDestino = null,
        esRecomendado = false, etapaAsociada = null, esHitoProspeccion = false,
        createdBy = 7, createdAt = createdAt,
    )

    @Test
    fun `un vendedor no puede ver el historial de otro empleado`() {
        assertThatThrownBy {
            service.historial(HistorialFiltros(idEmpleado = 7), vendedor, null, null)
        }.isInstanceOf(PermisoInsuficienteException::class.java)
    }

    @Test
    fun `un vendedor si puede ver su propio historial`() {
        every { tareaService.listarPorEmpleado(9, any(), any(), vendedor) } returns emptyList()
        every { eventoService.listarPorEmpleado(9, any(), any(), vendedor) } returns emptyList()

        val resultado = service.historial(HistorialFiltros(idEmpleado = 9), vendedor, null, null)

        assertThat(resultado.items).isEmpty()
    }

    @Test
    fun `gerencia y jdv pueden ver el historial de cualquier empleado`() {
        every { tareaService.listarPorEmpleado(7, any(), any(), any()) } returns emptyList()
        every { eventoService.listarPorEmpleado(7, any(), any(), any()) } returns emptyList()

        assertThat(service.historial(HistorialFiltros(idEmpleado = 7), gerencia, null, null).items).isEmpty()
        assertThat(service.historial(HistorialFiltros(idEmpleado = 7), jdv, null, null).items).isEmpty()
    }

    @Test
    fun `tareas y eventos se mezclan ordenados por created_at descendente`() {
        every { tareaService.listarPorEmpleado(7, any(), any(), gerencia) } returns
            listOf(tareaDto(1, Instant.parse("2026-09-01T10:00:00Z")))
        every { eventoService.listarPorEmpleado(7, any(), any(), gerencia) } returns
            listOf(eventoDto(2, Instant.parse("2026-09-05T10:00:00Z")))

        val resultado = service.historial(HistorialFiltros(idEmpleado = 7), gerencia, null, null)

        // El evento es mas reciente, va primero, aunque las tareas se pidieran antes.
        assertThat(resultado.items.map { it.tipo }).containsExactly("evento", "tarea")
        assertThat(resultado.meta.total).isEqualTo(2)
    }

    @Test
    fun `una tarea nunca lleva fecha de dia y un evento conserva la suya`() {
        every { tareaService.listarPorEmpleado(7, any(), any(), gerencia) } returns
            listOf(tareaDto(1, Instant.parse("2026-09-01T10:00:00Z")))
        every { eventoService.listarPorEmpleado(7, any(), any(), gerencia) } returns
            listOf(eventoDto(2, Instant.parse("2026-09-05T10:00:00Z")))

        val resultado = service.historial(HistorialFiltros(idEmpleado = 7), gerencia, null, null)

        val tarea = resultado.items.single { it.tipo == "tarea" }
        val evento = resultado.items.single { it.tipo == "evento" }
        assertThat(tarea.fechaDia).isNull()
        assertThat(tarea.fechaHora).isEqualTo(Instant.parse("2026-09-15T15:00:00Z"))
        assertThat(evento.fechaDia).isEqualTo(LocalDate.of(2026, 9, 20))
        assertThat(evento.fechaHora).isNull()
    }

    @Test
    fun `el filtro de tipo devuelve solo esa clase de actividad`() {
        every { tareaService.listarPorEmpleado(7, any(), any(), gerencia) } returns
            listOf(tareaDto(1, Instant.parse("2026-09-01T10:00:00Z")))
        every { eventoService.listarPorEmpleado(7, any(), any(), gerencia) } returns
            listOf(eventoDto(2, Instant.parse("2026-09-05T10:00:00Z")))

        val soloTareas = service.historial(HistorialFiltros(idEmpleado = 7, tipo = "tarea"), gerencia, null, null)

        assertThat(soloTareas.items.map { it.tipo }).containsExactly("tarea")
    }

    @Test
    fun `el filtro de oportunidad descarta lo que no cuelga de ella`() {
        every { tareaService.listarPorEmpleado(7, any(), any(), gerencia) } returns
            listOf(tareaDto(1, Instant.parse("2026-09-01T10:00:00Z")))
        every { eventoService.listarPorEmpleado(7, any(), any(), gerencia) } returns
            listOf(eventoDto(2, Instant.parse("2026-09-05T10:00:00Z")))

        // La tarea tiene idOportunidad null; el evento cuelga de la 20.
        val resultado = service.historial(HistorialFiltros(idEmpleado = 7, idOportunidad = 20), gerencia, null, null)

        assertThat(resultado.items.map { it.id }).containsExactly(2L)
    }

    @Test
    fun `la paginacion en memoria corta la pagina y calcula el total`() {
        every { tareaService.listarPorEmpleado(7, any(), any(), gerencia) } returns
            (1L..5L).map { tareaDto(it, Instant.parse("2026-09-0${it}T10:00:00Z")) }
        every { eventoService.listarPorEmpleado(7, any(), any(), gerencia) } returns emptyList()

        val pagina2 = service.historial(HistorialFiltros(idEmpleado = 7), gerencia, page = 2, perPage = 2)

        assertThat(pagina2.items).hasSize(2)
        assertThat(pagina2.meta.page).isEqualTo(2)
        assertThat(pagina2.meta.perPage).isEqualTo(2)
        assertThat(pagina2.meta.total).isEqualTo(5)
        assertThat(pagina2.meta.totalPages).isEqualTo(3)
    }

    @Test
    fun `el conteo de comentarios llega a cada actividad`() {
        every { tareaService.listarPorEmpleado(7, any(), any(), gerencia) } returns
            listOf(tareaDto(1, Instant.parse("2026-09-01T10:00:00Z")))
        every { eventoService.listarPorEmpleado(7, any(), any(), gerencia) } returns emptyList()
        every { comentarioService.contar(TipoActividad.tarea, listOf(1L)) } returns mapOf(1L to 3)

        val resultado = service.historial(HistorialFiltros(idEmpleado = 7), gerencia, null, null)

        assertThat(resultado.items.first().comentarios).isEqualTo(3)
    }

    @Test
    fun `la auditoria exige que la actividad sea visible antes de devolverla`() {
        every { tareaService.vinculoVisible(5, gerencia) } returns
            pe.quantum.crm.domain.tareas.dto.TareaVinculo(id = 5, idEmpresa = 3, idOportunidad = null, idAsignado = 7)
        every { auditoriaService.historial(TipoActividad.tarea, 5) } returns emptyList()

        val resultado = service.auditoria(TipoActividad.tarea, 5, gerencia)

        assertThat(resultado).isEmpty()
    }
}
```

- [ ] **Step 2: Correr el test y verificar que falla**

```bash
./gradlew test --tests "pe.quantum.crm.domain.actividades.HistorialActividadServiceImplTest"
```

Esperado: **FALLA en compilación**.

- [ ] **Step 3: Escribir la interfaz**

Crea `src/main/kotlin/pe/quantum/crm/domain/actividades/HistorialActividadService.kt`:

```kotlin
package pe.quantum.crm.domain.actividades

import pe.quantum.crm.domain.actividades.dto.ActividadDto
import pe.quantum.crm.domain.actividades.dto.CambioAuditoriaDto
import pe.quantum.crm.domain.actividades.dto.HistorialFiltros
import pe.quantum.crm.shared.Paginado
import pe.quantum.crm.shared.security.UsuarioActual

/**
 * Vista unificada de las actividades (tareas + eventos) de un empleado.
 *
 * NO amplia ni reduce ninguna visibilidad existente: admin, gerencia y jdv ya
 * veian todas las tareas y eventos (matriz_permisos.md §1). Lo que esto agrega
 * es la forma de filtrar esa vision por empleado y de leerla en un solo sitio.
 */
interface HistorialActividadService {
    /**
     * Historial de `filtros.idEmpleado`. `403 PERMISO_INSUFICIENTE` si el usuario
     * pide el historial de otro y no es supervisor.
     */
    fun historial(
        filtros: HistorialFiltros,
        usuario: UsuarioActual,
        page: Int?,
        perPage: Int?,
    ): Paginado<ActividadDto>

    /** Cambios auditados de una actividad. 404 si el usuario no la alcanza. */
    fun auditoria(
        tipo: TipoActividad,
        idActividad: Long,
        usuario: UsuarioActual,
    ): List<CambioAuditoriaDto>
}
```

- [ ] **Step 4: Escribir la implementación**

Crea `src/main/kotlin/pe/quantum/crm/domain/actividades/HistorialActividadServiceImpl.kt`:

```kotlin
package pe.quantum.crm.domain.actividades

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pe.quantum.crm.domain.actividades.dto.ActividadDto
import pe.quantum.crm.domain.actividades.dto.CambioAuditoriaDto
import pe.quantum.crm.domain.actividades.dto.HistorialFiltros
import pe.quantum.crm.domain.empleados.EmpleadoService
import pe.quantum.crm.domain.empresas.EmpresaService
import pe.quantum.crm.domain.eventos.EventoService
import pe.quantum.crm.domain.eventos.dto.EventoDto
import pe.quantum.crm.domain.tareas.TareaService
import pe.quantum.crm.domain.tareas.dto.TareaDto
import pe.quantum.crm.shared.PageMeta
import pe.quantum.crm.shared.Paginacion
import pe.quantum.crm.shared.Paginado
import pe.quantum.crm.shared.exception.PermisoInsuficienteException
import pe.quantum.crm.shared.security.UsuarioActual

/**
 * Une dos modulos por sus interfaces publicas (CLAUDE.md regla 12): nunca toca
 * las entidades `Tarea` ni `Evento`, solo sus DTOs.
 *
 * La union y la paginacion son EN MEMORIA, a proposito: son dos tablas
 * distintas y no hay forma de paginarlas juntas en SQL sin una vista. Es
 * aceptable porque `idEmpleado` es obligatorio, lo que acota el resultado al
 * historial de UNA persona. Si algun dia una sola persona acumula decenas de
 * miles de actividades, esto hay que revisarlo.
 */
@Service
@Suppress("LongParameterList") // Seis interfaces de servicio publicas: es un modulo de composicion.
class HistorialActividadServiceImpl(
    private val tareaService: TareaService,
    private val eventoService: EventoService,
    private val comentarioService: ComentarioActividadService,
    private val auditoriaService: AuditoriaActividadService,
    private val empresaService: EmpresaService,
    private val empleadoService: EmpleadoService,
) : HistorialActividadService {
    @Transactional(readOnly = true)
    override fun historial(
        filtros: HistorialFiltros,
        usuario: UsuarioActual,
        page: Int?,
        perPage: Int?,
    ): Paginado<ActividadDto> {
        exigirPermiso(filtros.idEmpleado, usuario)

        val tareas =
            if (filtros.tipo == TipoActividad.evento.name) {
                emptyList()
            } else {
                tareaService.listarPorEmpleado(filtros.idEmpleado, filtros.desde, filtros.hasta, usuario)
            }
        val eventos =
            if (filtros.tipo == TipoActividad.tarea.name) {
                emptyList()
            } else {
                eventoService.listarPorEmpleado(filtros.idEmpleado, filtros.desde, filtros.hasta, usuario)
            }

        val comentariosTarea = comentarioService.contar(TipoActividad.tarea, tareas.map { it.id })
        val comentariosEvento = comentarioService.contar(TipoActividad.evento, eventos.map { it.id })
        // Los eventos solo traen ids; sus resumenes se resuelven aqui.
        val empresas = empresaService.resumenPorIds(eventos.mapNotNull { it.idEmpresa })
        val empleados = empleadoService.resumenPorIds(eventos.map { it.createdBy }.distinct())

        val todas =
            (
                tareas.map { it.aActividad(comentariosTarea[it.id] ?: 0) } +
                    eventos.map { it.aActividad(comentariosEvento[it.id] ?: 0, empresas, empleados) }
            ).filter { coincide(it, filtros) }
                .sortedByDescending { it.createdAt }

        return paginar(todas, page, perPage)
    }

    @Transactional(readOnly = true)
    override fun auditoria(
        tipo: TipoActividad,
        idActividad: Long,
        usuario: UsuarioActual,
    ): List<CambioAuditoriaDto> {
        // El guard va aqui y no dentro de AuditoriaActividadService: ese servicio
        // no puede depender de tareas/eventos sin crear un ciclo de beans.
        when (tipo) {
            TipoActividad.tarea -> tareaService.vinculoVisible(idActividad, usuario)
            TipoActividad.evento -> eventoService.vinculoVisible(idActividad, usuario)
        }
        return auditoriaService.historial(tipo, idActividad)
    }

    // ── privados ───────────────────────────────────────────────

    /**
     * Ver el historial ajeno exige ser supervisor. Es 403 y no 404 porque el
     * recurso protegido es el EMPLEADO, cuya existencia ya es publica via
     * `GET /empleados` — no hay nada que ocultar (ver el plan, seccion Permisos).
     */
    private fun exigirPermiso(
        idEmpleado: Long,
        usuario: UsuarioActual,
    ) {
        if (idEmpleado != usuario.id && !usuario.esSupervisor) {
            throw PermisoInsuficienteException(
                "Solo admin, gerencia o jdv pueden ver el historial de actividades de otro empleado",
            )
        }
    }

    private fun coincide(
        actividad: ActividadDto,
        filtros: HistorialFiltros,
    ): Boolean {
        if (filtros.idEmpresa != null && actividad.idEmpresa != filtros.idEmpresa) {
            return false
        }
        if (filtros.idOportunidad != null && actividad.idOportunidad != filtros.idOportunidad) {
            return false
        }
        return true
    }

    private fun paginar(
        todas: List<ActividadDto>,
        page: Int?,
        perPage: Int?,
    ): Paginado<ActividadDto> {
        val pagina = (page ?: 1).coerceAtLeast(1)
        val tamano = (perPage ?: Paginacion.PER_PAGE_DEFAULT).coerceIn(1, Paginacion.PER_PAGE_MAX)
        val items = todas.drop((pagina - 1) * tamano).take(tamano)
        val meta: PageMeta = Paginacion.meta(pagina, tamano, todas.size.toLong())
        return Paginado(items, meta)
    }

    /** Una tarea NUNCA tiene `fechaDia`: `fecha_ejecucion` es TIMESTAMP (Trampa 1). */
    private fun TareaDto.aActividad(comentarios: Int) =
        ActividadDto(
            tipo = TipoActividad.tarea.name,
            id = id,
            titulo = tipoAccion,
            descripcion = descripcion,
            estado = estadoAccion,
            fechaHora = fechaEjecucion,
            fechaDia = null,
            idEmpresa = idEmpresa,
            empresa = empresa,
            idOportunidad = idOportunidad,
            idEmpleado = idAsignado,
            empleado = asignado,
            comentarios = comentarios,
            createdAt = createdAt,
        )

    /**
     * Un evento se atribuye a `createdBy`: no tiene asignado (Trampa 3).
     * `fechaEstimada` es DATE y va en `fechaDia`; `fechaOcurrencia` es TIMESTAMP
     * y va en `fechaHora`. No los cruces.
     */
    private fun EventoDto.aActividad(
        comentarios: Int,
        empresas: Map<Long, pe.quantum.crm.domain.empresas.dto.EmpresaResumen>,
        empleados: Map<Long, pe.quantum.crm.domain.empleados.dto.EmpleadoResumen>,
    ) = ActividadDto(
        tipo = TipoActividad.evento.name,
        id = id,
        titulo = nombre,
        descripcion = descripcion,
        estado = estado,
        fechaHora = fechaOcurrencia,
        fechaDia = fechaEstimada,
        idEmpresa = idEmpresa,
        empresa = idEmpresa?.let { empresas[it] },
        idOportunidad = idOportunidad,
        idEmpleado = createdBy,
        empleado = empleados[createdBy],
        comentarios = comentarios,
        createdAt = createdAt,
    )
}
```

- [ ] **Step 5: Correr el test y verificar que pasa**

```bash
./gradlew test --tests "pe.quantum.crm.domain.actividades.HistorialActividadServiceImplTest"
```

Esperado: **PASS**, 10 tests.

- [ ] **Step 6: Suite completa y commit**

```bash
./gradlew ktlintFormat
./gradlew test
git add -A
git commit -m "feat(actividades): historial unificado de tareas y eventos por empleado"
```

---

## Task 11: Controller y endpoints HTTP

Cuatro endpoints. El envelope y la paginación siguen el patrón exacto de `TareaController`.

**Files:**
- Create: `src/main/kotlin/pe/quantum/crm/domain/actividades/ActividadController.kt`
- Test: `src/test/kotlin/pe/quantum/crm/domain/actividades/ActividadControllerWebMvcTest.kt`

**Interfaces:**
- Consumes: `HistorialActividadService`, `ComentarioActividadService`, `UsuarioActualProvider`, `ApiResponse`.
- Produces: los 4 endpoints de la tabla de abajo.

**Contrato de los endpoints (esto es lo que se documenta después en la Task 12):**

| Método | Ruta | Roles | Notas |
|---|---|---|---|
| `GET` | `/api/v1/actividades` | todos (ajeno: solo admin/gerencia/jdv) | `id_empleado` **requerido**. Otros params: `desde`, `hasta` (ISO-8601), `tipo` (`tarea`\|`evento`), `id_empresa`, `id_oportunidad`, `page`, `per_page`. |
| `GET` | `/api/v1/actividades/{tipo}/{id}/comentarios` | todos (con visibilidad) | `{tipo}` = `tarea` \| `evento`. |
| `POST` | `/api/v1/actividades/{tipo}/{id}/comentarios` | todos (con visibilidad) | `201`. Body: `{"texto": "..."}`. |
| `GET` | `/api/v1/actividades/{tipo}/{id}/auditoria` | todos (con visibilidad) | Cambios de la actividad, más reciente primero. |

Códigos de error: `400 VALIDACION` (tipo desconocido, texto vacío o desmedido), `403 PERMISO_INSUFICIENTE` (historial ajeno sin ser supervisor), `404 NO_ENCONTRADO` (actividad fuera de alcance).

- [ ] **Step 1: Escribir el test que falla**

Crea `src/test/kotlin/pe/quantum/crm/domain/actividades/ActividadControllerWebMvcTest.kt`:

```kotlin
package pe.quantum.crm.domain.actividades

import com.ninjasquad.springmockk.MockkBean
import io.mockk.every
import io.mockk.verify
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import pe.quantum.crm.config.security.JwtService
import pe.quantum.crm.domain.actividades.dto.ActividadDto
import pe.quantum.crm.domain.actividades.dto.ComentarioDto
import pe.quantum.crm.domain.actividades.dto.HistorialFiltros
import pe.quantum.crm.shared.PageMeta
import pe.quantum.crm.shared.Paginado
import pe.quantum.crm.support.SinBaseDeDatosMocks
import java.time.Instant

/**
 * Borde HTTP del historial de actividades. Comprueba que los query params y el
 * path llegan al servicio tal y como los define el contrato, y que el tipo de
 * actividad desconocido se rechaza con 400 antes de tocar el servicio.
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
class ActividadControllerWebMvcTest {
    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var jwtService: JwtService

    @MockkBean
    lateinit var historialService: HistorialActividadService

    @MockkBean
    lateinit var comentarioService: ComentarioActividadService

    private fun token(rol: String = "gerencia") = jwtService.generateAccessToken(empleadoId = 1, rol = rol)

    private fun paginaVacia() = Paginado<ActividadDto>(emptyList(), PageMeta(1, 20, 0, 0))

    private fun comentarioDto() =
        ComentarioDto(
            id = 1, tipo = "tarea", idActividad = 5, texto = "Llame y no contesto",
            createdAt = Instant.now(), createdBy = 1, autor = null,
        )

    @Test
    fun `GET actividades pasa los filtros al servicio tal como llegan`() {
        every { historialService.historial(any(), any(), any(), any()) } returns paginaVacia()

        mockMvc
            .get("/api/v1/actividades") {
                header(HttpHeaders.AUTHORIZATION, "Bearer ${token()}")
                param("id_empleado", "7")
                param("tipo", "tarea")
                param("id_empresa", "3")
                param("per_page", "50")
            }.andExpect {
                status { isOk() }
                jsonPath("$.error") { value(null) }
            }

        verify {
            historialService.historial(
                HistorialFiltros(idEmpleado = 7, tipo = "tarea", idEmpresa = 3),
                any(),
                null,
                50,
            )
        }
    }

    @Test
    fun `GET actividades sin id_empleado devuelve 400`() {
        mockMvc
            .get("/api/v1/actividades") {
                header(HttpHeaders.AUTHORIZATION, "Bearer ${token()}")
            }.andExpect {
                status { isBadRequest() }
            }

        verify(exactly = 0) { historialService.historial(any(), any(), any(), any()) }
    }

    @Test
    fun `un tipo de actividad desconocido es 400 VALIDACION y no llega al servicio`() {
        mockMvc
            .get("/api/v1/actividades/inventado/5/comentarios") {
                header(HttpHeaders.AUTHORIZATION, "Bearer ${token()}")
            }.andExpect {
                status { isBadRequest() }
                jsonPath("$.error.code") { value("VALIDACION") }
                jsonPath("$.error.field") { value("tipo") }
            }

        verify(exactly = 0) { comentarioService.listar(any(), any(), any()) }
    }

    @Test
    fun `POST comentario devuelve 201 y traduce el tipo del path`() {
        every { comentarioService.crear(TipoActividad.tarea, 5, "Llame y no contesto", any()) } returns comentarioDto()

        mockMvc
            .post("/api/v1/actividades/tarea/5/comentarios") {
                header(HttpHeaders.AUTHORIZATION, "Bearer ${token()}")
                contentType = MediaType.APPLICATION_JSON
                content = """{"texto":"Llame y no contesto"}"""
            }.andExpect {
                status { isCreated() }
                jsonPath("$.data.texto") { value("Llame y no contesto") }
            }
    }

    @Test
    fun `POST comentario con texto vacio devuelve 400 VALIDACION`() {
        mockMvc
            .post("/api/v1/actividades/tarea/5/comentarios") {
                header(HttpHeaders.AUTHORIZATION, "Bearer ${token()}")
                contentType = MediaType.APPLICATION_JSON
                content = """{"texto":"   "}"""
            }.andExpect {
                status { isBadRequest() }
                jsonPath("$.error.code") { value("VALIDACION") }
            }

        verify(exactly = 0) { comentarioService.crear(any(), any(), any(), any()) }
    }

    @Test
    fun `POST comentario con texto desmedido devuelve 400 VALIDACION`() {
        mockMvc
            .post("/api/v1/actividades/evento/4/comentarios") {
                header(HttpHeaders.AUTHORIZATION, "Bearer ${token()}")
                contentType = MediaType.APPLICATION_JSON
                content = """{"texto":"${"x".repeat(5001)}"}"""
            }.andExpect {
                status { isBadRequest() }
                jsonPath("$.error.code") { value("VALIDACION") }
                jsonPath("$.error.field") { value("texto") }
            }

        verify(exactly = 0) { comentarioService.crear(any(), any(), any(), any()) }
    }

    @Test
    fun `GET auditoria traduce el tipo evento del path`() {
        every { historialService.auditoria(TipoActividad.evento, 4, any()) } returns emptyList()

        mockMvc
            .get("/api/v1/actividades/evento/4/auditoria") {
                header(HttpHeaders.AUTHORIZATION, "Bearer ${token()}")
            }.andExpect {
                status { isOk() }
            }

        verify { historialService.auditoria(TipoActividad.evento, 4, any()) }
    }

    @Test
    fun `sin token la peticion es 401`() {
        mockMvc.get("/api/v1/actividades") { param("id_empleado", "7") }.andExpect {
            status { isUnauthorized() }
        }
    }
}
```

- [ ] **Step 2: Correr el test y verificar que falla**

```bash
./gradlew test --tests "pe.quantum.crm.domain.actividades.ActividadControllerWebMvcTest"
```

Esperado: **FALLA**. Los endpoints todavía no existen (404 en vez de 200/400).

- [ ] **Step 3: Escribir el controller**

Crea `src/main/kotlin/pe/quantum/crm/domain/actividades/ActividadController.kt`:

```kotlin
package pe.quantum.crm.domain.actividades

import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import pe.quantum.crm.domain.actividades.dto.ActividadDto
import pe.quantum.crm.domain.actividades.dto.CambioAuditoriaDto
import pe.quantum.crm.domain.actividades.dto.ComentarioDto
import pe.quantum.crm.domain.actividades.dto.CrearComentarioRequest
import pe.quantum.crm.domain.actividades.dto.HistorialFiltros
import pe.quantum.crm.shared.ApiResponse
import pe.quantum.crm.shared.exception.ValidacionException
import pe.quantum.crm.shared.security.UsuarioActualProvider
import java.time.Instant

/**
 * Historial de actividades: la vista de supervision que une tareas y eventos
 * (contrato_api.md §29).
 *
 * `id_empleado` es obligatorio: la vista es "el historial de UNA persona".
 * Exigirlo acota el volumen que se une en memoria y hace el guard de permisos
 * explicito en cada llamada.
 */
@RestController
@RequestMapping("/api/v1/actividades")
class ActividadController(
    private val historialService: HistorialActividadService,
    private val comentarioService: ComentarioActividadService,
    private val usuarioProvider: UsuarioActualProvider,
) {
    @GetMapping
    @Suppress("LongParameterList") // Query params del contrato §29.
    fun historial(
        @RequestParam(name = "id_empleado") idEmpleado: Long,
        @RequestParam(required = false) desde: Instant?,
        @RequestParam(required = false) hasta: Instant?,
        @RequestParam(required = false) tipo: String?,
        @RequestParam(name = "id_empresa", required = false) idEmpresa: Long?,
        @RequestParam(name = "id_oportunidad", required = false) idOportunidad: Long?,
        @RequestParam(required = false) page: Int?,
        @RequestParam(name = "per_page", required = false) perPage: Int?,
    ): ApiResponse<List<ActividadDto>> {
        // Un `tipo` desconocido es error del cliente, no un filtro que se ignora:
        // devolver todo cuando pidieron "solo tareas" seria una fuga silenciosa.
        tipo?.let { aTipo(it) }
        val filtros =
            HistorialFiltros(
                idEmpleado = idEmpleado,
                desde = desde,
                hasta = hasta,
                tipo = tipo,
                idEmpresa = idEmpresa,
                idOportunidad = idOportunidad,
            )
        val resultado = historialService.historial(filtros, usuarioProvider.actual(), page, perPage)
        return ApiResponse.ok(resultado.items, resultado.meta)
    }

    @GetMapping("/{tipo}/{id}/comentarios")
    fun comentarios(
        @PathVariable tipo: String,
        @PathVariable id: Long,
    ): ApiResponse<List<ComentarioDto>> =
        ApiResponse.ok(comentarioService.listar(aTipo(tipo), id, usuarioProvider.actual()))

    @PostMapping("/{tipo}/{id}/comentarios")
    @ResponseStatus(HttpStatus.CREATED)
    fun comentar(
        @PathVariable tipo: String,
        @PathVariable id: Long,
        @Valid @RequestBody request: CrearComentarioRequest,
    ): ApiResponse<ComentarioDto> =
        ApiResponse.ok(comentarioService.crear(aTipo(tipo), id, request.texto, usuarioProvider.actual()))

    @GetMapping("/{tipo}/{id}/auditoria")
    fun auditoria(
        @PathVariable tipo: String,
        @PathVariable id: Long,
    ): ApiResponse<List<CambioAuditoriaDto>> =
        ApiResponse.ok(historialService.auditoria(aTipo(tipo), id, usuarioProvider.actual()))

    /** `tarea` o `evento`; cualquier otra cosa es 400, no un 500 por `valueOf`. */
    private fun aTipo(tipo: String): TipoActividad =
        runCatching { TipoActividad.valueOf(tipo) }.getOrElse {
            throw ValidacionException(
                "El tipo de actividad '$tipo' no es válido. Valores permitidos: tarea, evento",
                field = "tipo",
            )
        }
}
```

- [ ] **Step 4: Correr el test y verificar que pasa**

```bash
./gradlew test --tests "pe.quantum.crm.domain.actividades.ActividadControllerWebMvcTest"
```

Esperado: **PASS**, 8 tests.

> **Si `GET /actividades` con `desde`/`hasta` falla al parsear el `Instant`:** el proyecto ya recibe `Instant` en bodies JSON, pero un query param se convierte por otro camino. Si el test lo destapa, añade `@DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)` a los dos parámetros. Los tests de esta tarea no mandan fechas, así que puede no salir aquí — verifícalo a mano con la app levantada en la Task 13.

- [ ] **Step 5: Suite completa y commit**

```bash
./gradlew ktlintFormat
./gradlew detekt
./gradlew test
git add -A
git commit -m "feat(actividades): endpoints de historial, comentarios y auditoria"
```

---

## Task 12: Documentación (contrato y matriz de permisos)

`CLAUDE.md` lo dice sin ambigüedad: *"Todo cambio a un endpoint documentado (breaking o no) se registra en `contrato_api.md §28 Changelog del contrato`, en el mismo PR que lo hace. Sin esa entrada, el cambio de contrato no está terminado."*

**Files:**
- Modify: `docs/contrato_api.md`
- Modify: `docs/matriz_permisos.md`

> **Sobre la numeración:** la sección nueva es **§29** y va **después** del §28 Changelog, justo antes del "Apéndice — Endpoints no implementados en MVP". No renumeres nada: `CLAUDE.md` y el proceso del repo referencian "§28 Changelog" por número, y moverlo rompería todas esas referencias.

- [ ] **Step 1: Agregar la sección §29 al contrato**

En `docs/contrato_api.md`, localiza la línea `## Apéndice — Endpoints no implementados en MVP` e inserta **justo antes** (dejando el `---` que la precede en su sitio):

````markdown
## 29. Actividades (historial de supervisión)

> Vista unificada de tareas y eventos de un empleado. **No amplía ni reduce ninguna visibilidad existente:** `admin`, `gerencia` y `jdv` ya veían todas las tareas y todos los eventos (ver `matriz_permisos.md §1`). Lo que estos endpoints agregan es la forma de filtrar esa visión por empleado, leerla en un solo sitio, y dejar comentarios y rastro de ediciones.

### GET /actividades

Historial de actividades (tareas + eventos) de un empleado, ordenado por `created_at` descendente.

**Roles:** cualquier usuario autenticado para su propio historial. Para el de otro empleado: solo `admin`, `gerencia`, `jdv` — el resto recibe `403 PERMISO_INSUFICIENTE`.

**Query params:**

| Param | Tipo | Req. | Descripción |
|---|---|---|---|
| `id_empleado` | long | **sí** | De quién es el historial. Sin él, `400`. |
| `desde` | ISO-8601 | no | Filtra por `created_at` >= valor. |
| `hasta` | ISO-8601 | no | Filtra por `created_at` <= valor. |
| `tipo` | enum | no | `tarea` o `evento`. Ausente ⇒ ambos. Valor desconocido ⇒ `400 VALIDACION`. |
| `id_empresa` | long | no | Solo actividades de esa empresa. |
| `id_oportunidad` | long | no | Solo actividades de esa oportunidad. |
| `page`, `per_page` | int | no | Paginación estándar (§4). `per_page` máx. 100. |

**Notas sobre el rango de fechas:** `desde`/`hasta` filtran por **cuándo se registró** la actividad (`created_at`), no por cuándo está planificada. Es el único campo TIMESTAMP presente y no nulo en ambas entidades.

**Response `data[]` — `ActividadDto`:**

| Campo | Tipo | Descripción |
|---|---|---|
| `tipo` | string | `tarea` o `evento`. |
| `id` | long | Id dentro de su propia tabla. |
| `titulo` | string | `tipo_accion` de la tarea, o el nombre del evento. |
| `descripcion` | string? | |
| `estado` | string | `estado_accion` de la tarea, o `estado` del evento. |
| `fecha_hora` | Instant? | Columnas TIMESTAMP: `fecha_ejecucion` (tarea) o `fecha_ocurrencia` (evento). |
| `fecha_dia` | date? | Columna DATE: `fecha_estimada` del evento. **Siempre `null` en una tarea.** |
| `id_empresa` | long? | `null` en un evento que cuelga de una oportunidad. |
| `empresa` | object? | `{id, razon_social, distrito}`. `null` si `id_empresa` es null. |
| `id_oportunidad` | long? | |
| `id_empleado` | long? | Asignado de la tarea, o **creador** del evento (un evento no tiene asignado). |
| `empleado` | object? | `{id, nombres, apellidos}`. |
| `comentarios` | int | Cuántos comentarios de seguimiento tiene. |
| `created_at` | Instant | Eje de orden del historial. |

> **`fecha_hora` y `fecha_dia` son dos campos y no uno a propósito.** `fecha_estimada` de un evento es un día del calendario de Lima (columna `DATE`), no un instante; darle una hora la desplaza. No los unifiques en el cliente: muestra `fecha_dia` como fecha suelta y `fecha_hora` como fecha y hora.

### GET /actividades/{tipo}/{id}/comentarios

Comentarios de seguimiento de una actividad, del más antiguo al más reciente.

**Roles:** todos, con el filtro de visibilidad del recurso — una actividad fuera de alcance responde `404 NO_ENCONTRADO`.

`{tipo}` es `tarea` o `evento`; cualquier otro valor ⇒ `400 VALIDACION` con `field: "tipo"`.

**Response `data[]`:** `{id, tipo, id_actividad, texto, created_at, created_by, autor: {id, nombres, apellidos}?}`

### POST /actividades/{tipo}/{id}/comentarios

Agrega un comentario. **`201 Created`.**

**Body:** `{"texto": "..."}` — obligatorio, no vacío tras recortar espacios, máximo 5000 caracteres.

**Errores:** `400 VALIDACION` (texto vacío o desmedido, tipo desconocido), `404 NO_ENCONTRADO` (actividad fuera de alcance).

> Los comentarios son **append-only**: no existe editar ni borrar, y un comentario nuevo nunca sobrescribe la `descripcion` de la tarea o el evento.

### GET /actividades/{tipo}/{id}/auditoria

Cambios registrados sobre una actividad, del más reciente al más antiguo. Una fila **por campo modificado**.

**Roles:** todos, con el filtro de visibilidad del recurso (`404` si queda fuera).

**Response `data[]`:** `{id, campo, valor_anterior, valor_nuevo, changed_at, changed_by, autor: {id, nombres, apellidos}?}`

`campo` viene en snake_case, con el mismo nombre que expone este contrato: `descripcion`, `fecha_ejecucion`, `tipo_accion`, `id_contacto`, `id_asignado` (tareas); `descripcion`, `fecha_estimada`, `fecha_seguimiento` (eventos).

> Se auditan **todas** las ediciones, también las que hace el propio dueño de la actividad. Las transiciones de estado (`completada`, `cancelada`, `ocurrido`, `descartado`) **no** se auditan aquí: ya quedan reflejadas en el `estado` de la propia actividad.
````

- [ ] **Step 2: Agregar la entrada al changelog §28**

En `docs/contrato_api.md`, en la tabla del `## 28. Changelog del contrato`, agrega esta fila **al final** (después de la del `2026-09-07`):

```markdown
| 2026-09-08 | `GET /actividades`, `GET /actividades/{tipo}/{id}/comentarios`, `POST /actividades/{tipo}/{id}/comentarios`, `GET /actividades/{tipo}/{id}/auditoria`, `GET /oportunidades/:id/eventos`, `GET /empresas/:id/eventos`, `PUT /eventos/:id` | Non-breaking | Nueva vista de supervisión "historial de actividades" (§29). (1) Cuatro endpoints nuevos bajo `/actividades`: el historial unificado de tareas + eventos de un empleado (`id_empleado` obligatorio; filtros por rango de `created_at`, tipo, empresa y oportunidad), los comentarios de seguimiento de una actividad (listar y crear, append-only en tabla propia — nunca pisan `descripcion`) y la auditoría de ediciones (una fila por campo cambiado, con valor anterior y nuevo). (2) **Ningún permiso cambia:** `admin`, `gerencia` y `jdv` ya veían todas las tareas y eventos; lo nuevo es poder filtrar esa visión por empleado. Un rol no supervisor solo puede pedir su propio historial (`403 PERMISO_INSUFICIENTE` si pide el de otro). (3) `EventoDto` gana dos campos opcionales, `created_by` y `created_at`, que aparecen en todos los endpoints que ya devolvían eventos — aditivos, ningún campo se quita ni se renombra. Son necesarios porque un evento no tiene asignado y se atribuye a quien lo registró. Ver `matriz_permisos.md §1`. | Construir la vista nueva del side bar consumiendo `GET /actividades`. **Tratar `fecha_hora` y `fecha_dia` como dos campos distintos y no unificarlos**: `fecha_dia` es un día del calendario (columna `DATE`) y darle hora lo desplaza. Los dos campos nuevos de `EventoDto` se pueden ignorar hasta que se necesiten. |
```

- [ ] **Step 3: Documentar en la matriz de permisos**

En `docs/matriz_permisos.md`, agrega una sección nueva al final del archivo:

```markdown
## Historial de actividades (`/actividades`)

Vista de supervisión que une tareas y eventos de un empleado (`contrato_api.md §29`).

**No introduce ninguna visibilidad nueva.** `admin`, `gerencia` y `jdv` ya veían todas las tareas y todos los eventos (§1 de este documento); estos endpoints solo agregan la forma de filtrar esa visión por empleado.

| Operación | admin | gerencia | jdv | vendedor | analista | otro |
|---|---|---|---|---|---|---|
| Ver el historial propio (`id_empleado` = uno mismo) | Sí | Sí | Sí | Sí | Sí | Sí |
| Ver el historial de **otro** empleado | Sí | Sí | Sí | No (403) | No (403) | No (403) |
| Leer comentarios de una actividad | \*  | \* | \* | \* | \* | \* |
| Crear un comentario | \* | \* | \* | \* | \* | \* |
| Leer la auditoría de una actividad | \* | \* | \* | \* | \* | \* |

\* Sujeto al filtro de visibilidad de la propia actividad: la tarea o el evento que el rol no alcanza responde `404 NO_ENCONTRADO`, igual que en `§2.5` (eventos) y `§2.6` (tareas). Un rol de apoyo (`analista`, `otro`) solo alcanza aquello donde colabora vía tarea.

**Nota sobre el 403 del historial ajeno.** La regla general del repo es responder `404` ante un recurso ajeno (CLAUDE.md regla 14, contra IDOR). Aquí es `403` porque el recurso protegido no es una actividad sino **el empleado**, cuya existencia ya es pública para cualquier usuario autenticado vía `GET /empleados` (§7 del contrato). No hay existencia que ocultar, y el mensaje explícito le dice al usuario por qué no puede, en vez de fingir que la persona no existe.

**Auditoría.** Toda edición de una tarea o un evento (`PUT /tareas/:id`, `PUT /eventos/:id`) queda registrada campo a campo con el id de quien la hizo, sea o no el dueño de la actividad. Ningún rol puede editar una actividad ajena sin dejar rastro.
```

- [ ] **Step 4: Verificar que no rompiste el markdown y commitear**

```bash
git diff --stat docs/
git add docs/
git commit -m "docs(contrato): seccion 29 actividades, entrada de changelog y matriz de permisos"
```

---

## Task 13: Revisión final del diff contra la documentación

**Exigido por `CLAUDE.md` § "Cómo escribir un plan de implementación en este repo":** una tarea dedicada a releer el diff completo de la rama contra los documentos citados en la fase de investigación — **no solo contra lo que el plan pedía implementar**. Se busca específicamente que no se haya pisado documentación que ya estaba escrita y correcta antes de empezar.

**Files:** ninguno a priori. Si esta tarea encuentra algo, se arregla aquí y se commitea.

- [ ] **Step 1: Leer el diff completo de la rama**

```bash
git diff main...HEAD --stat
git diff main...HEAD
```

- [ ] **Step 2: Contrastar contra `docs/matriz_permisos.md`**

Recorre el diff y responde por escrito, en el cuerpo del PR:

1. ¿Algún cambio hace que un rol vea **más** de lo que la matriz dice que ve hoy? **Debe ser NO.** El plan solo agrega filtros sobre visibilidad existente. Si la respuesta es SÍ, has introducido una fuga: párate y repórtalo.
2. ¿Algún cambio hace que un rol vea **menos**? **Debe ser NO.** Producto decidió explícitamente no restringir nada (Decisión 1).
3. ¿La sección nueva de la matriz describe lo que el código hace de verdad, o lo que el plan decía que haría? Comprueba `HistorialActividadServiceImpl.exigirPermiso` y `ComentarioActividadServiceImpl.exigirVisible` línea a línea.

- [ ] **Step 3: Contrastar contra `docs/contrato_api.md`**

1. ¿Todo endpoint nuevo está en §29 con su tabla de params y su response?
2. ¿La fila del §28 existe y clasifica bien breaking/non-breaking? Los dos campos nuevos de `EventoDto` son **aditivos** en un response ⇒ non-breaking según la definición del propio §28. Verifica que ningún campo se haya quitado ni renombrado en ningún DTO existente:

```bash
git diff main...HEAD -- src/main/kotlin/pe/quantum/crm/domain/eventos/dto/ src/main/kotlin/pe/quantum/crm/domain/tareas/dto/
```

3. ¿Algún endpoint **ya documentado** cambió de forma sin entrada propia en §28? Presta atención a `GET /oportunidades/:id/eventos` y `GET /empresas/:id/eventos`: devuelven `EventoDto`, que ganó dos campos. Ya está cubierto por la fila del Step 2 de la Task 12 — confirma que los nombra.

- [ ] **Step 4: Contrastar contra `CLAUDE.md` y `docs/reglas_negocio.md`**

Verifica una por una:

- **Regla 3** — ¿el diff toca `estado_cartera` en algún sitio? **Debe ser NO.** `git diff main...HEAD | grep -i estado_cartera` no debe devolver nada.
- **Regla 4 / reglas §5.3** — ¿algo del diff cambia el estado de una oportunidad automáticamente? **Debe ser NO.** El historial es de lectura; la auditoría solo escribe en su propia tabla.
- **Regla 9** — ¿algún controller devuelve una entidad JPA en vez de un DTO? Revisa `ActividadController`: los cuatro métodos devuelven `ApiResponse<...Dto>`.
- **Regla 10** — ¿toda lectura lleva `@Transactional(readOnly = true)` y toda escritura `@Transactional`? Revisa los tres `*ServiceImpl` nuevos.
- **Regla 12** — lo verifica ArchUnit, pero confírmalo a ojo: ningún archivo de `tareas`/`eventos` importa una entidad, un repositorio o un `*Impl` de `actividades`.
- **Regla 11** — ¿alguna query nueva concatena SQL? **Debe ser NO**: las dos son métodos derivados de Spring Data.

- [ ] **Step 5: Verificar la Trampa 1 en todo el diff**

`comoInstanteUtc()` **nunca** debe aplicarse a `fechaEstimada`, `fechaSeguimiento` ni `fechaCierreEstimado`:

```bash
git diff main...HEAD | grep -n "comoInstanteUtc"
```

Cada aparición debe estar sobre un `LocalDateTime` (`createdAt`, `changedAt`, `fechaOcurrencia`, `fechaEjecucion`). Si alguna está sobre un `LocalDate`, **arréglala aquí**.

- [ ] **Step 6: Correr todos los gates**

```bash
./gradlew ktlintCheck
./gradlew detekt
./gradlew test
./gradlew koverVerify
```

Los cuatro deben pasar. `integrationTest` **no** se corre en local (Testcontainers roto con Docker 29); corre en CI.

- [ ] **Step 7: Comprobación manual contra la app levantada**

Las migraciones V48/V49 y la consistencia entidad↔schema (`ddl-auto=validate`) **no se verifican con tests unitarios**. Esta es la única forma de comprobarlas antes del PR:

```bash
docker-compose up -d
./gradlew bootRun
```

La app debe **arrancar sin errores**. Si `validate` se queja, hay una discrepancia entre una entidad nueva y su migración: corrígela **en una migración nueva (V50)**, nunca editando V48 o V49.

Con la app arriba, prueba a mano el camino que los tests unitarios no cubren — el parseo de `Instant` en query params (ver el aviso de la Task 11, Step 4):

```bash
# Sustituye <TOKEN> por un JWT de un usuario con rol gerencia.
curl -s -H "Authorization: Bearer <TOKEN>" \
  "http://localhost:8080/api/v1/actividades?id_empleado=1&desde=2026-01-01T00:00:00Z&hasta=2026-12-31T23:59:59Z"
```

Esperado: HTTP 200 con el envelope `{data, meta, error}`. Si devuelve `400` por el formato de fecha, aplica el arreglo de `@DateTimeFormat` que indica la Task 11.

- [ ] **Step 8: Abrir el PR**

```bash
git push -u origin feat/historial-actividades
gh pr create --base main --title "feat: historial de actividades (tareas + eventos) con comentarios y auditoria" --body "$(cat <<'CUERPO'
## Qué hace

Nueva vista de supervisión que une tareas y eventos de un empleado, con comentarios de seguimiento y auditoría de ediciones.

Ticket: `docs/requerimientos/2026-09-07-historial-actividades-jerarquico.json`
Plan: `docs/superpowers/plans/2026-09-08-historial-actividades.md`

## Lo que NO cambia

Ningún permiso existente. `admin`, `gerencia` y `jdv` ya veían todas las tareas y eventos; esto solo agrega la forma de filtrar esa visión por empleado. El triage confirmó que la premisa original del ticket (que gerencia no veía a los vendedores) era falsa.

## Migraciones

- `V48` — `actividad_comentarios` (tabla nueva, append-only)
- `V49` — `actividad_auditoria` (tabla nueva, append-only)

Ambas son puramente aditivas: **no alteran ni borran datos existentes**, así que no aplica el paso de restaurar dump de producción de `DEVOPS-backend.md §7`.

## Contrato

Non-breaking. Sección `§29` nueva + entrada en `§28 Changelog`. `EventoDto` gana dos campos opcionales (`created_by`, `created_at`).

## Revisión final contra docs

Completada según la Task 13 del plan: matriz de permisos, contrato, reglas 3/4/9/10/11/12 de CLAUDE.md y la advertencia de `shared/TiempoUtc.kt` sobre columnas DATE.
CUERPO
)"
```

---

## Autorrevisión del plan (hecha al redactarlo)

**Cobertura del ticket:** los 6 requisitos cerrados y las 5 respuestas de producto tienen tarea asignada.

| Requisito | Dónde se cumple |
|---|---|
| R1/R2 — vista de gerencia sobre JDV, de JDV sobre vendedores | Tasks 10-11. El triage estableció que la visibilidad ya existía; se implementa como filtro, no como permiso nuevo. |
| R3 — pasado y futuro, sin recorte temporal | Tasks 4-5: sin `desde`/`hasta`, el rango va de 1970 a 2999. |
| R4 — todos los datos relacionados | Task 10: `ActividadDto` lleva empresa, oportunidad, empleado, fechas y estado. |
| R5 — editar y comentar | Editar ya funcionaba (no se toca, Decisión 2). Comentar: Tasks 1, 6, 11. |
| R6 — admin accede | Task 10: `esSupervisor` ya incluye `admin`. |
| P3 — comentarios en tabla aparte | Task 1 (V48). |
| P4 — auditoría | Tasks 2, 7, 8, 9 (V49). |
| P5 — filtros | Tasks 10-11: empleado, rango, tipo, empresa, oportunidad. |

**Riesgos conocidos que el plan asume conscientemente:**

1. **Unión y paginación en memoria.** Aceptable porque `id_empleado` es obligatorio. Documentado en el KDoc de `HistorialActividadServiceImpl`. Si un solo empleado acumulara decenas de miles de actividades, habría que pasar a una vista SQL.
2. **Los eventos se atribuyen por `created_by`.** Es la única columna con esa semántica (Trampa 3). Si producto esperaba otra cosa, sale aquí y es un cambio pequeño y localizado.
3. **`ddl-auto=validate` solo se verifica levantando la app** (Task 13, Step 7), no con tests unitarios. Es la limitación de no poder correr Testcontainers en local.

---

## Ejecución

Este plan está pensado para ejecutarse en **Google Antigravity con modelos ligeros**, tarea por tarea y en orden. Reglas para el ejecutor:

1. Una tarea a la vez. No empieces la siguiente hasta que `./gradlew test` pase y hayas commiteado.
2. Los bloques de código son **literales**: cópialos, no los reinterpretes.
3. Si un paso no funciona como dice el "Esperado", **para y reporta**. No improvises un arreglo distinto al plan.
4. Las cuatro Trampas están numeradas y citadas en la tarea donde muerden. Léelas cuando el plan te lo diga.
