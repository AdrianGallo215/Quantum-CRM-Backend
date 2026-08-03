# Notificaciones in-app Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add an in-app notification system (persistence, 4 REST endpoints, and 2 scheduled jobs) so users are notified of actions relevant to them but not performed by them, plus task/event reminders.

**Architecture:** New self-contained module `domain/notificaciones/` exposing `NotificacionService.notificar(...)` as the single write path other modules call as a side effect inside their existing transactions. No new business logic is introduced in `empresas`/`oportunidades`/`eventos`/`tareas` — only calls to this new service, plus two narrow signature changes (`aplicarEstadoDerivado`, `reasignarVendedor`, `traspasar`) needed to know the actor and detect the `empresa_convertida` transition. Two `@Scheduled` jobs live inside the notifications module and pull read-only projections from `tareas`/`eventos`/`oportunidades`/`empresas` through their existing public service interfaces (module isolation, CLAUDE.md regla 12).

**Tech Stack:** Kotlin 1.9 / Spring Boot 3.2 / Spring Data JPA / Flyway / PostgreSQL 16 / JUnit 5 + MockK + AssertJ / Testcontainers.

## Global Constraints

- Envelope de respuesta `{ data, meta, error }` en los 4 endpoints (CLAUDE.md, `contrato_api.md` §2).
- `spring.jpa.hibernate.ddl-auto=validate` — toda columna nueva necesita su migración Flyway antes de que la entidad JPA la referencie.
- Próxima migración es `V22` (el repo está en V21, confirmado por `SeedFixtures.MIGRACIONES_TOTAL = 21`; `CLAUDE.md` dice V19 pero está desactualizado).
- Inyección por constructor (`private val`), nunca `@Autowired` en campos.
- Relaciones JPA / referencias entre módulos siempre a través de la interfaz pública de servicio (`XxxService`), nunca repositorio ni entidad de otro módulo.
- `@Transactional(readOnly = true)` en lecturas, `@Transactional` en escrituras cubriendo toda la operación.
- IDOR: recurso ajeno o inexistente → `404 NO_ENCONTRADO` vía `NoEncontradoException` (ya manejada por `GlobalExceptionHandler`, sin código nuevo).
- Nadie se notifica de su propia acción — esta regla se aplica **una sola vez**, dentro de `NotificacionServiceImpl.notificar`, no en cada punto de enganche.
- Broadcast a supervisores = todos los empleados activos con rol `admin`, `gerente` o `jdv` (no existe jerarquía jdv→vendedor en el esquema — confirmado, decisión aprobada en `docs/superpowers/specs/2026-07-09-notificaciones-in-app-design.md`).
- `./gradlew test` (unitarios) debe pasar antes de cada commit. Los tests de integración (`@Tag("integration")`) corren con `./gradlew integrationTest` — no bloquean el flujo TDD local si Docker no está disponible, pero deben quedar escritos.
- Ajuste de diseño encontrado durante la planificación: `id_actor` en `notificaciones` es **nullable** (no `NOT NULL` como decía el spec inicial) porque los recordatorios del job programado no tienen actor humano (catálogo: "Actor: sistema"). `NotificacionService.notificar` recibe `idActor: Long?`.

---

### Task 1: Migración V22 y actualización de los tests de esquema

**Files:**
- Create: `src/main/resources/db/migration/V22__create_notificaciones.sql`
- Modify: `src/test/kotlin/pe/quantum/crm/support/SeedFixtures.kt`
- Modify: `src/test/kotlin/pe/quantum/crm/db/SchemaMigrationIntegrationTest.kt`

**Interfaces:**
- Produces: tablas `notificaciones`, `recordatorios_enviados`; tipos `tipo_notificacion_enum`, `entidad_notificacion_enum`, `origen_recordatorio_enum`, `umbral_recordatorio_enum`.

- [ ] **Step 1: Escribir el test que falla (actualizar aserciones de esquema)**

En `SchemaMigrationIntegrationTest.kt`, actualizar las dos aserciones que van a dejar de cumplirse:

```kotlin
    @Test
    fun `el schema crea las 15 tablas de dominio`() {
        val tablas = strList("SELECT tablename FROM pg_tables WHERE schemaname = 'public' AND tablename <> 'flyway_schema_history'")
        assertThat(tablas).containsExactlyInAnyOrder(
            "empleados",
            "modelos",
            "modelo_aplicaciones",
            "financiadoras",
            "empresas",
            "empresa_segmentos",
            "contactos",
            "empresa_contactos",
            "oportunidades",
            "oportunidad_estados_log",
            "oportunidad_contactos",
            "catalogo_eventos",
            "eventos",
            "tareas",
            "buses_entregados",
            "notificaciones",
            "recordatorios_enviados",
        )
    }
```

(Renombrar el test a `` `el schema crea las 17 tablas de dominio` `` para que el nombre siga describiendo el conteo real.)

```kotlin
    @Test
    fun `los 14 enums de dominio existen`() {
        val enums = strList("SELECT typname FROM pg_type WHERE typtype = 'e'")
        assertThat(enums).containsExactlyInAnyOrder(
            "rol_empleado",
            "aplicacion_enum",
            "segmento_enum",
            "origen_lead_enum",
            "estado_cartera_enum",
            "estado_op_enum",
            "estado_evento_enum",
            "tipo_accion_enum",
            "estado_accion_enum",
            "estado_entrega_enum",
            "tipo_notificacion_enum",
            "entidad_notificacion_enum",
            "origen_recordatorio_enum",
            "umbral_recordatorio_enum",
        )
    }
```

En `SeedFixtures.kt`:

```kotlin
    /** Total de migraciones aplicadas (V1..V22). Actualizar al agregar migraciones. */
    const val MIGRACIONES_TOTAL = 22
```

- [ ] **Step 2: Confirmar que el test de integración (aún) no puede correr / documentar por qué no lo ejecutamos ahora**

Este test está `@Tag("integration")` y requiere Docker (Testcontainers) — en este entorno local (Docker Desktop 29) no corre, igual que el resto de la suite de integración (ver comentario en `IntegrationTestBase`). No se ejecuta en este paso; se retoma en CI. Continuar igual con la migración, que es lo que hace que compile y (en CI) pase.

- [ ] **Step 3: Escribir la migración**

```sql
-- =============================================================================
-- V22 — Notificaciones in-app
-- Notifica a un usuario cuando ocurre una accion relevante para el, generada
-- por otra persona (o por un job programado, sin actor humano — id_actor NULL).
-- entidad_tipo solo cubre oportunidad|empresa: el frontend navega a esas dos,
-- nunca a una tarea/evento suelto (para tareas/eventos se referencia su
-- oportunidad si tiene una, si no su empresa).
-- =============================================================================

CREATE TYPE tipo_notificacion_enum AS ENUM (
    'oportunidad_cambio_estado',
    'empresa_convertida',
    'evento_creado',
    'tarea_creada',
    'empresa_asignada',
    'oportunidad_traspasada',
    'tarea_recordatorio',
    'evento_recordatorio'
);

CREATE TYPE entidad_notificacion_enum AS ENUM ('oportunidad', 'empresa');

CREATE TABLE notificaciones (
    id                          BIGSERIAL                   PRIMARY KEY,
    id_empleado_destinatario   BIGINT                      NOT NULL REFERENCES empleados(id),
    id_actor                    BIGINT                      REFERENCES empleados(id),
    tipo                        tipo_notificacion_enum      NOT NULL,
    mensaje                     TEXT                        NOT NULL,
    entidad_tipo                entidad_notificacion_enum   NOT NULL,
    entidad_id                  BIGINT                      NOT NULL,
    leida                       BOOLEAN                     NOT NULL DEFAULT false,
    created_at                  TIMESTAMP                   NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_notificaciones_destinatario ON notificaciones(id_empleado_destinatario, created_at DESC);

COMMENT ON COLUMN notificaciones.id_actor IS 'NULL para recordatorios generados por un job programado (sin actor humano).';

-- Dedup del job de recordatorios. Tabla separada de `notificaciones` para que
-- el job de limpieza (purga leida=true y >30 dias) nunca pueda reabrir una
-- ventana de duplicado.
CREATE TYPE origen_recordatorio_enum AS ENUM ('tarea', 'evento');
CREATE TYPE umbral_recordatorio_enum AS ENUM ('proximo', 'vencido');

CREATE TABLE recordatorios_enviados (
    id          BIGSERIAL                   PRIMARY KEY,
    origen      origen_recordatorio_enum    NOT NULL,
    id_origen   BIGINT                      NOT NULL,
    umbral      umbral_recordatorio_enum    NOT NULL,
    created_at  TIMESTAMP                   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_recordatorio UNIQUE (origen, id_origen, umbral)
);
```

- [ ] **Step 4: Verificar que el proyecto sigue compilando**

Run: `./gradlew compileKotlin compileTestKotlin`
Expected: BUILD SUCCESSFUL (la migración no rompe nada porque aún no hay entidad JPA que la use).

- [ ] **Step 5: Commit**

```bash
git add src/main/resources/db/migration/V22__create_notificaciones.sql src/test/kotlin/pe/quantum/crm/support/SeedFixtures.kt src/test/kotlin/pe/quantum/crm/db/SchemaMigrationIntegrationTest.kt
git commit -m "feat(notificaciones): migracion V22 (tablas notificaciones y recordatorios_enviados)"
```

---

### Task 2: Entidad `Notificacion`, enums y repositorio

**Files:**
- Create: `src/main/kotlin/pe/quantum/crm/domain/notificaciones/NotificacionEnums.kt`
- Create: `src/main/kotlin/pe/quantum/crm/domain/notificaciones/Notificacion.kt`
- Create: `src/main/kotlin/pe/quantum/crm/domain/notificaciones/NotificacionRepository.kt`
- Test: `src/test/kotlin/pe/quantum/crm/domain/notificaciones/NotificacionRepositoryIntegrationTest.kt`

**Interfaces:**
- Produces: `TipoNotificacion`, `EntidadNotificacion` enums; `Notificacion` entity (`id`, `idEmpleadoDestinatario: Long`, `idActor: Long?`, `tipo: TipoNotificacion`, `mensaje: String`, `entidadTipo: EntidadNotificacion`, `entidadId: Long`, `leida: Boolean` (var), `createdAt: LocalDateTime`); `NotificacionRepository`.

- [ ] **Step 1: Escribir el test de integración que falla**

```kotlin
package pe.quantum.crm.domain.notificaciones

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import pe.quantum.crm.support.IntegrationTestBase
import java.time.LocalDateTime

@Tag("integration")
@SpringBootTest
class NotificacionRepositoryIntegrationTest : IntegrationTestBase() {
    @Autowired
    lateinit var repository: NotificacionRepository

    @Test
    fun `guarda y recupera una notificacion con id_actor nulo`() {
        val guardada =
            repository.save(
                Notificacion(
                    idEmpleadoDestinatario = 1,
                    idActor = null,
                    tipo = TipoNotificacion.tarea_recordatorio,
                    mensaje = "Recordatorio de prueba",
                    entidadTipo = EntidadNotificacion.empresa,
                    entidadId = 1,
                    createdAt = LocalDateTime.now(),
                ),
            )

        val recuperada = repository.findById(requireNotNull(guardada.id)).orElseThrow()
        assertThat(recuperada.idActor).isNull()
        assertThat(recuperada.tipo).isEqualTo(TipoNotificacion.tarea_recordatorio)
        assertThat(recuperada.leida).isFalse()
    }
}
```

- [ ] **Step 2: Confirmar que no compila (las clases no existen aún)**

Run: `./gradlew compileTestKotlin`
Expected: FAIL — `unresolved reference: Notificacion` / `TipoNotificacion` / `EntidadNotificacion` / `NotificacionRepository`.

- [ ] **Step 3: Crear los enums**

```kotlin
package pe.quantum.crm.domain.notificaciones

/** Valores de `tipo_notificacion_enum` (migracion V22). */
enum class TipoNotificacion {
    oportunidad_cambio_estado,
    empresa_convertida,
    evento_creado,
    tarea_creada,
    empresa_asignada,
    oportunidad_traspasada,
    tarea_recordatorio,
    evento_recordatorio,
}

/** Valores de `entidad_notificacion_enum` (migracion V22). */
enum class EntidadNotificacion {
    oportunidad,
    empresa,
}
```

- [ ] **Step 4: Crear la entidad**

```kotlin
package pe.quantum.crm.domain.notificaciones

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.time.LocalDateTime

/**
 * Notificacion in-app (tabla `notificaciones`, migracion V22). `idActor` es
 * nullable: los recordatorios generados por un job programado no tienen
 * actor humano.
 */
@Entity
@Table(name = "notificaciones")
@Suppress("LongParameterList") // Una entidad JPA refleja las columnas de su tabla.
class Notificacion(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,
    @Column(name = "id_empleado_destinatario", nullable = false)
    val idEmpleadoDestinatario: Long,
    @Column(name = "id_actor")
    val idActor: Long?,
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(nullable = false, columnDefinition = "tipo_notificacion_enum")
    val tipo: TipoNotificacion,
    @Column(nullable = false)
    val mensaje: String,
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(name = "entidad_tipo", nullable = false, columnDefinition = "entidad_notificacion_enum")
    val entidadTipo: EntidadNotificacion,
    @Column(name = "entidad_id", nullable = false)
    val entidadId: Long,
    @Column(nullable = false)
    var leida: Boolean = false,
    @Column(name = "created_at", nullable = false)
    val createdAt: LocalDateTime = LocalDateTime.now(),
)
```

- [ ] **Step 5: Crear el repositorio**

```kotlin
package pe.quantum.crm.domain.notificaciones

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.LocalDateTime

interface NotificacionRepository : JpaRepository<Notificacion, Long> {
    fun findTop20ByIdEmpleadoDestinatarioOrderByCreatedAtDesc(idEmpleadoDestinatario: Long): List<Notificacion>

    fun countByIdEmpleadoDestinatarioAndLeidaFalse(idEmpleadoDestinatario: Long): Long

    fun findByIdAndIdEmpleadoDestinatario(
        id: Long,
        idEmpleadoDestinatario: Long,
    ): Notificacion?

    fun findByIdEmpleadoDestinatarioAndLeidaFalse(idEmpleadoDestinatario: Long): List<Notificacion>

    @Modifying
    @Query("DELETE FROM Notificacion n WHERE n.leida = true AND n.createdAt < :umbral")
    fun purgarLeidasAntesDe(
        @Param("umbral") umbral: LocalDateTime,
    ): Int
}
```

- [ ] **Step 6: Verificar que compila (el test de integración no corre localmente, pero debe compilar)**

Run: `./gradlew compileTestKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 7: Commit**

```bash
git add src/main/kotlin/pe/quantum/crm/domain/notificaciones/NotificacionEnums.kt src/main/kotlin/pe/quantum/crm/domain/notificaciones/Notificacion.kt src/main/kotlin/pe/quantum/crm/domain/notificaciones/NotificacionRepository.kt src/test/kotlin/pe/quantum/crm/domain/notificaciones/NotificacionRepositoryIntegrationTest.kt
git commit -m "feat(notificaciones): entidad Notificacion, enums y repositorio"
```

---

### Task 3: Entidad `RecordatorioEnviado` y repositorio

**Files:**
- Create: `src/main/kotlin/pe/quantum/crm/domain/notificaciones/RecordatorioEnviado.kt`
- Create: `src/main/kotlin/pe/quantum/crm/domain/notificaciones/RecordatorioEnviadoRepository.kt`
- Test: `src/test/kotlin/pe/quantum/crm/domain/notificaciones/RecordatorioEnviadoRepositoryIntegrationTest.kt`

**Interfaces:**
- Produces: `OrigenRecordatorio` (`tarea`, `evento`), `UmbralRecordatorio` (`proximo`, `vencido`) enums; `RecordatorioEnviado` entity; `RecordatorioEnviadoRepository.existsByOrigenAndIdOrigenAndUmbral(...): Boolean`.
- Consumes: nada (tabla propia del módulo).

- [ ] **Step 1: Escribir el test de integración que falla**

```kotlin
package pe.quantum.crm.domain.notificaciones

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.dao.DataIntegrityViolationException
import pe.quantum.crm.support.IntegrationTestBase

@Tag("integration")
@SpringBootTest
class RecordatorioEnviadoRepositoryIntegrationTest : IntegrationTestBase() {
    @Autowired
    lateinit var repository: RecordatorioEnviadoRepository

    @Test
    fun `existsBy detecta un recordatorio ya registrado`() {
        repository.save(RecordatorioEnviado(origen = OrigenRecordatorio.tarea, idOrigen = 1, umbral = UmbralRecordatorio.proximo))

        assertThat(repository.existsByOrigenAndIdOrigenAndUmbral(OrigenRecordatorio.tarea, 1, UmbralRecordatorio.proximo)).isTrue()
        assertThat(repository.existsByOrigenAndIdOrigenAndUmbral(OrigenRecordatorio.tarea, 1, UmbralRecordatorio.vencido)).isFalse()
        assertThat(repository.existsByOrigenAndIdOrigenAndUmbral(OrigenRecordatorio.evento, 1, UmbralRecordatorio.proximo)).isFalse()
    }

    @Test
    fun `el constraint unico rechaza un duplicado exacto`() {
        repository.saveAndFlush(RecordatorioEnviado(origen = OrigenRecordatorio.evento, idOrigen = 5, umbral = UmbralRecordatorio.vencido))

        org.junit.jupiter.api.assertThrows<DataIntegrityViolationException> {
            repository.saveAndFlush(RecordatorioEnviado(origen = OrigenRecordatorio.evento, idOrigen = 5, umbral = UmbralRecordatorio.vencido))
        }
    }
}
```

- [ ] **Step 2: Confirmar que no compila**

Run: `./gradlew compileTestKotlin`
Expected: FAIL — clases no existen.

- [ ] **Step 3: Implementar entidad + enums**

```kotlin
package pe.quantum.crm.domain.notificaciones

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.time.LocalDateTime

enum class OrigenRecordatorio { tarea, evento }

enum class UmbralRecordatorio { proximo, vencido }

/** Dedup del job de recordatorios (tabla `recordatorios_enviados`, migracion V22). */
@Entity
@Table(name = "recordatorios_enviados")
class RecordatorioEnviado(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(nullable = false, columnDefinition = "origen_recordatorio_enum")
    val origen: OrigenRecordatorio,
    @Column(name = "id_origen", nullable = false)
    val idOrigen: Long,
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(nullable = false, columnDefinition = "umbral_recordatorio_enum")
    val umbral: UmbralRecordatorio,
    @Column(name = "created_at", nullable = false)
    val createdAt: LocalDateTime = LocalDateTime.now(),
)
```

- [ ] **Step 4: Implementar repositorio**

```kotlin
package pe.quantum.crm.domain.notificaciones

import org.springframework.data.jpa.repository.JpaRepository

interface RecordatorioEnviadoRepository : JpaRepository<RecordatorioEnviado, Long> {
    fun existsByOrigenAndIdOrigenAndUmbral(
        origen: OrigenRecordatorio,
        idOrigen: Long,
        umbral: UmbralRecordatorio,
    ): Boolean
}
```

- [ ] **Step 5: Verificar que compila**

Run: `./gradlew compileTestKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 6: Commit**

```bash
git add src/main/kotlin/pe/quantum/crm/domain/notificaciones/RecordatorioEnviado.kt src/main/kotlin/pe/quantum/crm/domain/notificaciones/RecordatorioEnviadoRepository.kt src/test/kotlin/pe/quantum/crm/domain/notificaciones/RecordatorioEnviadoRepositoryIntegrationTest.kt
git commit -m "feat(notificaciones): entidad RecordatorioEnviado y repositorio (dedup de recordatorios)"
```

---

### Task 4: `EmpleadoResumen.nombreCompleto()` y `EmpleadoService.idsSupervisoresActivos()`

**Files:**
- Modify: `src/main/kotlin/pe/quantum/crm/domain/empleados/dto/EmpleadoCrudDtos.kt`
- Modify: `src/main/kotlin/pe/quantum/crm/domain/empleados/EmpleadoService.kt`
- Modify: `src/main/kotlin/pe/quantum/crm/domain/empleados/EmpleadoServiceImpl.kt`
- Modify: `src/main/kotlin/pe/quantum/crm/domain/empleados/EmpleadoRepository.kt`
- Test: `src/test/kotlin/pe/quantum/crm/domain/empleados/EmpleadoServiceTest.kt`

**Interfaces:**
- Produces: `EmpleadoResumen.nombreCompleto(): String`; `EmpleadoService.idsSupervisoresActivos(): List<Long>`.
- Consumes: `RolEmpleado.admin/gerente/jdv` (existente).

- [ ] **Step 1: Escribir el test que falla**

Agregar al final de la clase `EmpleadoServiceTest` (mismo archivo, mismo estilo `@MockK`/`@InjectMockKs` ya usado ahí):

```kotlin
    @Test
    fun `idsSupervisoresActivos devuelve los ids de admin, gerente y jdv activos`() {
        every {
            repository.findByActivoTrueAndRolIn(listOf(RolEmpleado.admin, RolEmpleado.gerente, RolEmpleado.jdv))
        } returns
            listOf(
                empleado().let { Empleado(id = 1, nombres = it.nombres, apellidos = it.apellidos, email = "a@quantum.pe", rol = RolEmpleado.admin) },
                empleado().let { Empleado(id = 2, nombres = it.nombres, apellidos = it.apellidos, email = "b@quantum.pe", rol = RolEmpleado.jdv) },
            )

        val resultado = service.idsSupervisoresActivos()

        assertThat(resultado).containsExactlyInAnyOrder(1, 2)
    }
```

- [ ] **Step 2: Ejecutar y confirmar que falla**

Run: `./gradlew test --tests "pe.quantum.crm.domain.empleados.EmpleadoServiceTest"`
Expected: FAIL — `unresolved reference: idsSupervisoresActivos` / `findByActivoTrueAndRolIn`.

- [ ] **Step 3: Agregar el método al repositorio**

En `EmpleadoRepository.kt`, agregar:

```kotlin
    /** Broadcast de supervisores (sin jerarquia jdv->vendedor en el esquema; ver docs/superpowers/specs/2026-07-09-notificaciones-in-app-design.md). */
    fun findByActivoTrueAndRolIn(roles: Collection<RolEmpleado>): List<Empleado>
```

- [ ] **Step 4: Agregar el método a la interfaz y a la extensión**

En `EmpleadoService.kt`, agregar al final de la interfaz:

```kotlin
    /** Empleados activos con rol admin, gerente o jdv (broadcast de notificaciones). */
    fun idsSupervisoresActivos(): List<Long>
```

En `EmpleadoCrudDtos.kt`, agregar junto a `toResumen`:

```kotlin
fun EmpleadoResumen.nombreCompleto(): String = "$nombres $apellidos"
```

- [ ] **Step 5: Implementar en `EmpleadoServiceImpl`**

```kotlin
    @Transactional(readOnly = true)
    override fun idsSupervisoresActivos(): List<Long> =
        empleadoRepository
            .findByActivoTrueAndRolIn(listOf(RolEmpleado.admin, RolEmpleado.gerente, RolEmpleado.jdv))
            .map { requireNotNull(it.id) }
```

- [ ] **Step 6: Ejecutar y confirmar que pasa**

Run: `./gradlew test --tests "pe.quantum.crm.domain.empleados.EmpleadoServiceTest"`
Expected: PASS (todos los tests de la clase, incluido el nuevo)

- [ ] **Step 7: Commit**

```bash
git add src/main/kotlin/pe/quantum/crm/domain/empleados/dto/EmpleadoCrudDtos.kt src/main/kotlin/pe/quantum/crm/domain/empleados/EmpleadoService.kt src/main/kotlin/pe/quantum/crm/domain/empleados/EmpleadoServiceImpl.kt src/main/kotlin/pe/quantum/crm/domain/empleados/EmpleadoRepository.kt src/test/kotlin/pe/quantum/crm/domain/empleados/EmpleadoServiceTest.kt
git commit -m "feat(empleados): idsSupervisoresActivos y EmpleadoResumen.nombreCompleto para notificaciones"
```

---

### Task 5: `NotificacionService` / `NotificacionServiceImpl` — núcleo (notificar, listar, marcar leídas, contar)

**Files:**
- Create: `src/main/kotlin/pe/quantum/crm/domain/notificaciones/dto/NotificacionDtos.kt`
- Create: `src/main/kotlin/pe/quantum/crm/domain/notificaciones/NotificacionService.kt`
- Create: `src/main/kotlin/pe/quantum/crm/domain/notificaciones/NotificacionServiceImpl.kt`
- Test: `src/test/kotlin/pe/quantum/crm/domain/notificaciones/NotificacionServiceImplTest.kt`

**Interfaces:**
- Consumes: `EmpleadoService.resumenPorIds(ids): Map<Long, EmpleadoResumen>` (existente); `NotificacionRepository`, `Notificacion`, `TipoNotificacion`, `EntidadNotificacion` (Task 2).
- Produces: `NotificacionService` — usado por `empresas`, `oportunidades`, `eventos`, `tareas` (Tasks 8-13) y por el job de recordatorios (Task 15):
  ```kotlin
  interface NotificacionService {
      fun notificar(destinatarios: Set<Long>, idActor: Long?, tipo: TipoNotificacion, mensaje: String, entidadTipo: EntidadNotificacion, entidadId: Long)
      fun contarNoLeidas(usuario: UsuarioActual): Long
      fun listar(usuario: UsuarioActual): List<NotificacionDto>
      fun marcarLeida(id: Long, usuario: UsuarioActual)
      fun marcarTodasLeidas(usuario: UsuarioActual)
  }
  ```
  `NotificacionDto(id: Long, tipo: String, mensaje: String, entidadTipo: String, entidadId: Long, leida: Boolean, createdAt: LocalDateTime, actor: EmpleadoResumen?)`.

- [ ] **Step 1: Escribir los tests que fallan**

```kotlin
package pe.quantum.crm.domain.notificaciones

import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import pe.quantum.crm.domain.empleados.EmpleadoService
import pe.quantum.crm.domain.empleados.dto.EmpleadoResumen
import pe.quantum.crm.shared.exception.NoEncontradoException
import pe.quantum.crm.shared.security.UsuarioActual
import java.time.LocalDateTime
import java.util.Optional

class NotificacionServiceImplTest {
    private val notificacionRepository = mockk<NotificacionRepository>()
    private val empleadoService = mockk<EmpleadoService>()
    private val service = NotificacionServiceImpl(notificacionRepository, empleadoService)

    private val usuario = UsuarioActual(id = 1, rol = "vendedor")

    @Test
    fun `notificar excluye al actor del set de destinatarios`() {
        val slots = mutableListOf<Notificacion>()
        every { notificacionRepository.save(capture(slots)) } answers { firstArg() }

        service.notificar(
            destinatarios = setOf(1, 2, 3),
            idActor = 1,
            tipo = TipoNotificacion.tarea_creada,
            mensaje = "msg",
            entidadTipo = EntidadNotificacion.empresa,
            entidadId = 10,
        )

        assertThat(slots.map { it.idEmpleadoDestinatario }).containsExactlyInAnyOrder(2, 3)
        assertThat(slots).allMatch { it.idActor == 1L }
    }

    @Test
    fun `notificar con id_actor nulo no excluye a nadie`() {
        val slots = mutableListOf<Notificacion>()
        every { notificacionRepository.save(capture(slots)) } answers { firstArg() }

        service.notificar(
            destinatarios = setOf(5, 6),
            idActor = null,
            tipo = TipoNotificacion.tarea_recordatorio,
            mensaje = "msg",
            entidadTipo = EntidadNotificacion.empresa,
            entidadId = 10,
        )

        assertThat(slots.map { it.idEmpleadoDestinatario }).containsExactlyInAnyOrder(5, 6)
        assertThat(slots).allMatch { it.idActor == null }
    }

    @Test
    fun `notificar con set vacio tras excluir al actor no guarda nada`() {
        service.notificar(
            destinatarios = setOf(1),
            idActor = 1,
            tipo = TipoNotificacion.tarea_creada,
            mensaje = "msg",
            entidadTipo = EntidadNotificacion.empresa,
            entidadId = 10,
        )

        verify(exactly = 0) { notificacionRepository.save(any()) }
    }

    @Test
    fun `listar devuelve las notificaciones con el resumen del actor resuelto`() {
        val notificacion =
            Notificacion(
                id = 1,
                idEmpleadoDestinatario = 1,
                idActor = 2,
                tipo = TipoNotificacion.tarea_creada,
                mensaje = "Carlos te asignó una tarea",
                entidadTipo = EntidadNotificacion.empresa,
                entidadId = 10,
                createdAt = LocalDateTime.now(),
            )
        every { notificacionRepository.findTop20ByIdEmpleadoDestinatarioOrderByCreatedAtDesc(1) } returns listOf(notificacion)
        every { empleadoService.resumenPorIds(listOf(2)) } returns mapOf(2L to EmpleadoResumen(id = 2, nombres = "Carlos", apellidos = "Ruiz"))

        val resultado = service.listar(usuario)

        assertThat(resultado).hasSize(1)
        assertThat(resultado.first().actor?.nombres).isEqualTo("Carlos")
    }

    @Test
    fun `marcarLeida sobre una notificacion ajena o inexistente lanza NoEncontradoException`() {
        every { notificacionRepository.findByIdAndIdEmpleadoDestinatario(99, 1) } returns null

        assertThrows<NoEncontradoException> { service.marcarLeida(99, usuario) }
    }

    @Test
    fun `marcarLeida marca la notificacion propia como leida`() {
        val notificacion =
            Notificacion(
                id = 7,
                idEmpleadoDestinatario = 1,
                idActor = 2,
                tipo = TipoNotificacion.tarea_creada,
                mensaje = "msg",
                entidadTipo = EntidadNotificacion.empresa,
                entidadId = 10,
                createdAt = LocalDateTime.now(),
            )
        every { notificacionRepository.findByIdAndIdEmpleadoDestinatario(7, 1) } returns notificacion
        every { notificacionRepository.save(notificacion) } returns notificacion

        service.marcarLeida(7, usuario)

        assertThat(notificacion.leida).isTrue()
    }

    @Test
    fun `marcarTodasLeidas marca todas las pendientes del usuario`() {
        val pendientes =
            listOf(
                Notificacion(id = 1, idEmpleadoDestinatario = 1, idActor = null, tipo = TipoNotificacion.tarea_recordatorio, mensaje = "a", entidadTipo = EntidadNotificacion.empresa, entidadId = 1, createdAt = LocalDateTime.now()),
                Notificacion(id = 2, idEmpleadoDestinatario = 1, idActor = null, tipo = TipoNotificacion.tarea_recordatorio, mensaje = "b", entidadTipo = EntidadNotificacion.empresa, entidadId = 2, createdAt = LocalDateTime.now()),
            )
        every { notificacionRepository.findByIdEmpleadoDestinatarioAndLeidaFalse(1) } returns pendientes
        every { notificacionRepository.saveAll(pendientes) } returns pendientes

        service.marcarTodasLeidas(usuario)

        assertThat(pendientes).allMatch { it.leida }
    }

    @Test
    fun `contarNoLeidas delega en el repositorio`() {
        every { notificacionRepository.countByIdEmpleadoDestinatarioAndLeidaFalse(1) } returns 5L

        assertThat(service.contarNoLeidas(usuario)).isEqualTo(5L)
    }
}
```

- [ ] **Step 2: Confirmar que no compila**

Run: `./gradlew compileTestKotlin`
Expected: FAIL — `NotificacionServiceImpl`, `NotificacionDto` no existen.

- [ ] **Step 3: Crear los DTOs**

```kotlin
package pe.quantum.crm.domain.notificaciones.dto

import pe.quantum.crm.domain.empleados.dto.EmpleadoResumen
import java.time.LocalDateTime

data class NotificacionDto(
    val id: Long,
    val tipo: String,
    val mensaje: String,
    val entidadTipo: String,
    val entidadId: Long,
    val leida: Boolean,
    val createdAt: LocalDateTime,
    val actor: EmpleadoResumen?,
)

data class ContadorNoLeidasDto(
    val count: Long,
)
```

- [ ] **Step 4: Crear la interfaz**

```kotlin
package pe.quantum.crm.domain.notificaciones

import pe.quantum.crm.domain.notificaciones.dto.NotificacionDto
import pe.quantum.crm.shared.security.UsuarioActual

/**
 * Interfaz publica del modulo notificaciones. `notificar` es el UNICO efecto
 * secundario que otros modulos invocan (dentro de su propia transaccion) para
 * generar notificaciones (docs/superpowers/specs/2026-07-09-notificaciones-in-app-design.md).
 */
interface NotificacionService {
    /**
     * Crea una notificacion por cada destinatario. Excluye `idActor` del set
     * (nadie se notifica de su propia accion); si `idActor` es null (job de
     * sistema) no excluye a nadie. No hace nada si el set resultante queda vacio.
     */
    fun notificar(
        destinatarios: Set<Long>,
        idActor: Long?,
        tipo: TipoNotificacion,
        mensaje: String,
        entidadTipo: EntidadNotificacion,
        entidadId: Long,
    )

    fun contarNoLeidas(usuario: UsuarioActual): Long

    /** Ultimas 20 notificaciones (leidas + no leidas) del usuario, mas recientes primero. */
    fun listar(usuario: UsuarioActual): List<NotificacionDto>

    /** 404 NO_ENCONTRADO si no existe o no pertenece al usuario. */
    fun marcarLeida(
        id: Long,
        usuario: UsuarioActual,
    )

    fun marcarTodasLeidas(usuario: UsuarioActual)
}
```

- [ ] **Step 5: Implementar el servicio**

```kotlin
package pe.quantum.crm.domain.notificaciones

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pe.quantum.crm.domain.empleados.EmpleadoService
import pe.quantum.crm.domain.notificaciones.dto.NotificacionDto
import pe.quantum.crm.shared.exception.NoEncontradoException
import pe.quantum.crm.shared.security.UsuarioActual
import java.time.LocalDateTime

@Service
class NotificacionServiceImpl(
    private val notificacionRepository: NotificacionRepository,
    private val empleadoService: EmpleadoService,
) : NotificacionService {
    @Transactional
    override fun notificar(
        destinatarios: Set<Long>,
        idActor: Long?,
        tipo: TipoNotificacion,
        mensaje: String,
        entidadTipo: EntidadNotificacion,
        entidadId: Long,
    ) {
        val destinatariosFinal = if (idActor != null) destinatarios - idActor else destinatarios
        if (destinatariosFinal.isEmpty()) {
            return
        }
        val ahora = LocalDateTime.now()
        destinatariosFinal.forEach { idDestinatario ->
            notificacionRepository.save(
                Notificacion(
                    idEmpleadoDestinatario = idDestinatario,
                    idActor = idActor,
                    tipo = tipo,
                    mensaje = mensaje,
                    entidadTipo = entidadTipo,
                    entidadId = entidadId,
                    createdAt = ahora,
                ),
            )
        }
    }

    @Transactional(readOnly = true)
    override fun contarNoLeidas(usuario: UsuarioActual): Long = notificacionRepository.countByIdEmpleadoDestinatarioAndLeidaFalse(usuario.id)

    @Transactional(readOnly = true)
    override fun listar(usuario: UsuarioActual): List<NotificacionDto> {
        val notificaciones = notificacionRepository.findTop20ByIdEmpleadoDestinatarioOrderByCreatedAtDesc(usuario.id)
        val actores = empleadoService.resumenPorIds(notificaciones.mapNotNull { it.idActor })
        return notificaciones.map { notificacion ->
            NotificacionDto(
                id = requireNotNull(notificacion.id),
                tipo = notificacion.tipo.name,
                mensaje = notificacion.mensaje,
                entidadTipo = notificacion.entidadTipo.name,
                entidadId = notificacion.entidadId,
                leida = notificacion.leida,
                createdAt = notificacion.createdAt,
                actor = notificacion.idActor?.let { actores[it] },
            )
        }
    }

    @Transactional
    override fun marcarLeida(
        id: Long,
        usuario: UsuarioActual,
    ) {
        val notificacion =
            notificacionRepository.findByIdAndIdEmpleadoDestinatario(id, usuario.id)
                ?: throw NoEncontradoException("La notificación no existe")
        notificacion.leida = true
        notificacionRepository.save(notificacion)
    }

    @Transactional
    override fun marcarTodasLeidas(usuario: UsuarioActual) {
        val pendientes = notificacionRepository.findByIdEmpleadoDestinatarioAndLeidaFalse(usuario.id)
        pendientes.forEach { it.leida = true }
        notificacionRepository.saveAll(pendientes)
    }
}
```

- [ ] **Step 6: Ejecutar los tests y confirmar que pasan**

Run: `./gradlew test --tests "pe.quantum.crm.domain.notificaciones.NotificacionServiceImplTest"`
Expected: PASS (8 tests)

- [ ] **Step 7: Commit**

```bash
git add src/main/kotlin/pe/quantum/crm/domain/notificaciones/dto/NotificacionDtos.kt src/main/kotlin/pe/quantum/crm/domain/notificaciones/NotificacionService.kt src/main/kotlin/pe/quantum/crm/domain/notificaciones/NotificacionServiceImpl.kt src/test/kotlin/pe/quantum/crm/domain/notificaciones/NotificacionServiceImplTest.kt
git commit -m "feat(notificaciones): NotificacionService — notificar, listar, marcar leidas, contar"
```

---

### Task 6: `NotificacionController` — 4 endpoints

**Files:**
- Modify: `src/test/kotlin/pe/quantum/crm/support/SinBaseDeDatosMocks.kt`
- Create: `src/main/kotlin/pe/quantum/crm/domain/notificaciones/NotificacionController.kt`
- Test: `src/test/kotlin/pe/quantum/crm/domain/notificaciones/NotificacionControllerWebMvcTest.kt`

**Interfaces:**
- Consumes: `NotificacionService` (Task 5), `UsuarioActualProvider` (existente).

Este proyecto **no usa `@WebMvcTest`** en ningún lado (confirmado por búsqueda en todo el repo). El patrón real para tests de controller es un `@SpringBootTest` de contexto completo con `DataSource`/`Hibernate`/`Flyway` excluidos, `@Import(SinBaseDeDatosMocks::class)` para no necesitar base de datos, `@MockkBean` (de `springmockk`) para el servicio del controller bajo prueba, y un JWT real generado con `JwtService` (no `@WithMockUser`) — exactamente como `AuthControllerWebMvcTest.kt` y `EmpleadoMeControllerTest.kt`. Se sigue ese mismo patrón aquí, letra por letra.

**IMPORTANTE — riesgo de romper TODA la suite:** `SinBaseDeDatosMocks` (`src/test/kotlin/pe/quantum/crm/support/SinBaseDeDatosMocks.kt`) provee un `@Bean` mockk por cada repositorio JPA existente, porque con `DataSourceAutoConfiguration`/`HibernateJpaAutoConfiguration` excluidos Spring Data JPA no puede crear NINGÚN repositorio real — sin el mock explícito, el `ApplicationContext` no arranca. Esta clase la usan `CrmApplicationTests`, `SecurityHeadersAndCorsTest`, `AuthControllerWebMvcTest` y `EmpleadoMeControllerTest`. Como `NotificacionRepository` y `RecordatorioEnviadoRepository` ya existen desde los Tasks 2-3, **si no se les agrega su mock aquí, esos 4 tests existentes empiezan a fallar** con `NoSuchBeanDefinitionException` en cuanto este test (o cualquiera) cargue el contexto completo. Este task agrega esos 2 beans que faltan.

- [ ] **Step 1: Agregar los mocks de los 2 repositorios nuevos a `SinBaseDeDatosMocks`**

En `SinBaseDeDatosMocks.kt`, agregar los imports y los 2 beans (junto a los demás, cualquier posición):

```kotlin
import pe.quantum.crm.domain.notificaciones.NotificacionRepository
import pe.quantum.crm.domain.notificaciones.RecordatorioEnviadoRepository
```

```kotlin
    @Bean
    fun notificacionRepository(): NotificacionRepository = mockk(relaxed = true)

    @Bean
    fun recordatorioEnviadoRepository(): RecordatorioEnviadoRepository = mockk(relaxed = true)
```

- [ ] **Step 2: Correr la suite existente para confirmar que sigue en verde con este cambio aislado**

Run: `./gradlew test --tests "pe.quantum.crm.CrmApplicationTests" --tests "pe.quantum.crm.config.security.SecurityHeadersAndCorsTest" --tests "pe.quantum.crm.domain.empleados.AuthControllerWebMvcTest" --tests "pe.quantum.crm.domain.empleados.EmpleadoMeControllerTest"`
Expected: PASS (este paso es puramente preventivo: agrega los beans faltantes ANTES de que el Task 6 introduzca otro `@SpringBootTest` que expondría el problema).

- [ ] **Step 3: Commit de este arreglo preventivo, separado del resto del task**

```bash
git add src/test/kotlin/pe/quantum/crm/support/SinBaseDeDatosMocks.kt
git commit -m "test(support): agregar mocks de NotificacionRepository/RecordatorioEnviadoRepository a SinBaseDeDatosMocks"
```

- [ ] **Step 4: Escribir el test del controller que falla**

```kotlin
package pe.quantum.crm.domain.notificaciones

import com.ninjasquad.springmockk.MockkBean
import io.mockk.every
import io.mockk.verify
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.http.HttpHeaders
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.patch
import pe.quantum.crm.config.security.JwtService
import pe.quantum.crm.domain.notificaciones.dto.NotificacionDto
import pe.quantum.crm.shared.exception.NoEncontradoException
import pe.quantum.crm.support.SinBaseDeDatosMocks
import java.time.LocalDateTime

/**
 * Tests de los 4 endpoints de notificaciones (contrato_api.md §19) via MockMvc,
 * sin base de datos: se mockea NotificacionService. Mismo patron que
 * AuthControllerWebMvcTest.kt/EmpleadoMeControllerTest.kt.
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
class NotificacionControllerWebMvcTest {
    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var jwtService: JwtService

    @MockkBean
    lateinit var notificacionService: NotificacionService

    private fun bearer(): String = "Bearer " + jwtService.generateAccessToken(empleadoId = 1, rol = "vendedor")

    @Test
    fun `GET no-leidas-count devuelve el envelope estandar`() {
        every { notificacionService.contarNoLeidas(any()) } returns 5L

        mockMvc.get("/api/v1/notificaciones/no-leidas/count") {
            header(HttpHeaders.AUTHORIZATION, bearer())
        }.andExpect {
            status { isOk() }
            jsonPath("$.data.count") { value(5) }
            jsonPath("$.error") { isEmpty() }
        }
    }

    @Test
    fun `GET notificaciones devuelve la lista`() {
        every { notificacionService.listar(any()) } returns
            listOf(
                NotificacionDto(
                    id = 1,
                    tipo = "tarea_creada",
                    mensaje = "msg",
                    entidadTipo = "empresa",
                    entidadId = 1,
                    leida = false,
                    createdAt = LocalDateTime.now(),
                    actor = null,
                ),
            )

        mockMvc.get("/api/v1/notificaciones") {
            header(HttpHeaders.AUTHORIZATION, bearer())
        }.andExpect {
            status { isOk() }
            jsonPath("$.data.length()") { value(1) }
        }
    }

    @Test
    fun `PATCH notificaciones-id-leida sobre una ajena o inexistente devuelve 404 NO_ENCONTRADO`() {
        every { notificacionService.marcarLeida(99, any()) } throws NoEncontradoException("La notificación no existe")

        mockMvc.patch("/api/v1/notificaciones/99/leida") {
            header(HttpHeaders.AUTHORIZATION, bearer())
        }.andExpect {
            status { isNotFound() }
            jsonPath("$.error.code") { value("NO_ENCONTRADO") }
        }
    }

    @Test
    fun `PATCH notificaciones-leidas marca todas como leidas`() {
        every { notificacionService.marcarTodasLeidas(any()) } returns Unit

        mockMvc.patch("/api/v1/notificaciones/leidas") {
            header(HttpHeaders.AUTHORIZATION, bearer())
        }.andExpect {
            status { isOk() }
        }

        verify { notificacionService.marcarTodasLeidas(any()) }
    }

    @Test
    fun `sin token devuelve 401`() {
        mockMvc.get("/api/v1/notificaciones/no-leidas/count").andExpect {
            status { isUnauthorized() }
        }
    }
}
```

- [ ] **Step 5: Confirmar que no compila / falla**

Run: `./gradlew test --tests "pe.quantum.crm.domain.notificaciones.NotificacionControllerWebMvcTest"`
Expected: FAIL — `NotificacionController` no existe.

- [ ] **Step 6: Implementar el controller**

```kotlin
package pe.quantum.crm.domain.notificaciones

import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import pe.quantum.crm.domain.notificaciones.dto.ContadorNoLeidasDto
import pe.quantum.crm.domain.notificaciones.dto.NotificacionDto
import pe.quantum.crm.shared.ApiResponse
import pe.quantum.crm.shared.security.UsuarioActualProvider

/** Endpoints de notificaciones (contrato_api.md §19). */
@RestController
@RequestMapping("/api/v1/notificaciones")
class NotificacionController(
    private val notificacionService: NotificacionService,
    private val usuarioProvider: UsuarioActualProvider,
) {
    @GetMapping("/no-leidas/count")
    fun contarNoLeidas(): ApiResponse<ContadorNoLeidasDto> =
        ApiResponse.ok(ContadorNoLeidasDto(notificacionService.contarNoLeidas(usuarioProvider.actual())))

    @GetMapping
    fun listar(): ApiResponse<List<NotificacionDto>> = ApiResponse.ok(notificacionService.listar(usuarioProvider.actual()))

    @PatchMapping("/{id}/leida")
    fun marcarLeida(
        @PathVariable id: Long,
    ): ApiResponse<Map<String, Boolean>> {
        notificacionService.marcarLeida(id, usuarioProvider.actual())
        return ApiResponse.ok(mapOf("leida" to true))
    }

    @PatchMapping("/leidas")
    fun marcarTodasLeidas(): ApiResponse<Map<String, Boolean>> {
        notificacionService.marcarTodasLeidas(usuarioProvider.actual())
        return ApiResponse.ok(mapOf("leida" to true))
    }
}
```

- [ ] **Step 7: Ejecutar y confirmar que pasa**

Run: `./gradlew test --tests "pe.quantum.crm.domain.notificaciones.NotificacionControllerWebMvcTest"`
Expected: PASS (5 tests)

- [ ] **Step 8: Commit**

```bash
git add src/main/kotlin/pe/quantum/crm/domain/notificaciones/NotificacionController.kt src/test/kotlin/pe/quantum/crm/domain/notificaciones/NotificacionControllerWebMvcTest.kt
git commit -m "feat(notificaciones): endpoints GET/PATCH de notificaciones"
```

---

### Task 7: `aplicarEstadoDerivado` devuelve el cambio de estado (`CambioEstadoCartera`)

**Files:**
- Modify: `src/main/kotlin/pe/quantum/crm/domain/empresas/dto/EmpresaDtos.kt`
- Modify: `src/main/kotlin/pe/quantum/crm/domain/empresas/EmpresaService.kt`
- Modify: `src/main/kotlin/pe/quantum/crm/domain/empresas/EmpresaServiceImpl.kt`
- Modify: `src/main/kotlin/pe/quantum/crm/domain/oportunidades/EstadoCarteraService.kt`

**Interfaces:**
- Produces: `CambioEstadoCartera(anterior: EstadoCartera, nuevo: EstadoCartera)`; `EmpresaService.aplicarEstadoDerivado(idEmpresa, derivado): CambioEstadoCartera?` (antes `Unit`); `EstadoCarteraService.actualizar(idEmpresa): CambioEstadoCartera?` (antes `Unit`).
- No hay tests unitarios existentes de `EmpresaServiceImpl` ni de `EstadoCarteraService` que romper (confirmado: no existen en `src/test`). Este task no tiene test previo que actualizar, así que se agrega uno nuevo directamente para fijar el comportamiento.

- [ ] **Step 1: Escribir el test que falla**

```kotlin
package pe.quantum.crm.domain.empresas

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import pe.quantum.crm.domain.empleados.EmpleadoService
import pe.quantum.crm.shared.enums.EstadoCartera
import java.time.LocalDateTime
import java.util.Optional

class EmpresaServiceImplTest {
    private val empresaRepository = mockk<EmpresaRepository>()
    private val empleadoService = mockk<EmpleadoService>()
    private val service = EmpresaServiceImpl(empresaRepository, empleadoService)

    private fun empresa(estadoCartera: EstadoCartera = EstadoCartera.prospeccion) =
        Empresa(
            id = 1,
            ruc = "20123456789",
            razonSocial = "Transportes ABC",
            estadoCartera = estadoCartera,
            createdAt = LocalDateTime.now(),
            createdBy = 1,
            updatedAt = LocalDateTime.now(),
            updatedBy = 1,
        )

    @Test
    fun `aplicarEstadoDerivado devuelve el cambio cuando prospeccion pasa a oportunidad_activa`() {
        val entidad = empresa(estadoCartera = EstadoCartera.prospeccion)
        every { empresaRepository.findById(1) } returns Optional.of(entidad)
        every { empresaRepository.save(entidad) } returns entidad

        val resultado = service.aplicarEstadoDerivado(1, EstadoCartera.oportunidad_activa)

        assertThat(resultado?.anterior).isEqualTo(EstadoCartera.prospeccion)
        assertThat(resultado?.nuevo).isEqualTo(EstadoCartera.oportunidad_activa)
        assertThat(entidad.estadoCartera).isEqualTo(EstadoCartera.oportunidad_activa)
    }

    @Test
    fun `aplicarEstadoDerivado devuelve null cuando no hay cambio real`() {
        val entidad = empresa(estadoCartera = EstadoCartera.oportunidad_activa)
        every { empresaRepository.findById(1) } returns Optional.of(entidad)

        val resultado = service.aplicarEstadoDerivado(1, EstadoCartera.oportunidad_activa)

        assertThat(resultado).isNull()
        verify(exactly = 0) { empresaRepository.save(any()) }
    }
}
```

- [ ] **Step 2: Ejecutar y confirmar que falla**

Run: `./gradlew test --tests "pe.quantum.crm.domain.empresas.EmpresaServiceImplTest"`
Expected: FAIL — el tipo de retorno actual es `Unit`, `resultado?.anterior` no compila.

- [ ] **Step 3: Agregar `CambioEstadoCartera` al DTO compartido**

En `EmpresaDtos.kt`, junto a `EmpresaVinculo`/`EmpresaResumen`:

```kotlin
/** Resultado de `aplicarEstadoDerivado`: null si no hubo escritura (regla §3.2 paso 3). */
data class CambioEstadoCartera(
    val anterior: EstadoCartera,
    val nuevo: EstadoCartera,
)
```

(Import ya existente `pe.quantum.crm.shared.enums.EstadoCartera` en ese archivo.)

- [ ] **Step 4: Cambiar la firma en la interfaz**

En `EmpresaService.kt`:

```kotlin
    fun aplicarEstadoDerivado(
        idEmpresa: Long,
        derivado: EstadoCartera?,
    ): CambioEstadoCartera?
```

(Agregar import `pe.quantum.crm.domain.empresas.dto.CambioEstadoCartera`.)

- [ ] **Step 5: Cambiar la implementación**

En `EmpresaServiceImpl.kt`, reemplazar el cuerpo de `aplicarEstadoDerivado`:

```kotlin
    @Transactional
    override fun aplicarEstadoDerivado(
        idEmpresa: Long,
        derivado: EstadoCartera?,
    ): CambioEstadoCartera? {
        val empresa = entidad(idEmpresa)
        val actual = empresa.estadoCartera
        // Guarda de entrada (reglas §3.2 paso 3): sin cambio real no hay write.
        if (derivado == actual) {
            return null
        }
        if (derivado == null && actual.esManual) {
            return null
        }
        // Sin derivado y con estado actual derivado: la empresa vuelve al estado
        // manual base. Sin historial de estados manuales, se baja a prospeccion
        // (la empresa fue trabajada: tuvo oportunidades).
        val nuevo = derivado ?: EstadoCartera.prospeccion
        empresa.estadoCartera = nuevo
        empresa.updatedAt = LocalDateTime.now()
        empresaRepository.save(empresa)
        return CambioEstadoCartera(anterior = actual, nuevo = nuevo)
    }
```

Agregar import `pe.quantum.crm.domain.empresas.dto.CambioEstadoCartera`.

- [ ] **Step 6: Propagar el cambio en `EstadoCarteraService`**

En `EstadoCarteraService.kt`:

```kotlin
import pe.quantum.crm.domain.empresas.dto.CambioEstadoCartera
// ...
    @Transactional
    fun actualizar(idEmpresa: Long): CambioEstadoCartera? {
        val derivado =
            when {
                oportunidadRepository.existsByIdEmpresaAndEstado(idEmpresa, EstadoOportunidad.facturado) ->
                    EstadoCartera.cliente
                oportunidadRepository.existsByIdEmpresaAndEstadoIn(idEmpresa, ESTADOS_ACTIVOS) ->
                    EstadoCartera.oportunidad_activa
                else -> null
            }
        return empresaService.aplicarEstadoDerivado(idEmpresa, derivado)
    }
```

- [ ] **Step 7: Arreglar los 2 call sites en `OportunidadServiceImpl` (por ahora solo para que compile — la lógica de notificación llega en el Task 11)**

En `crear` (línea con `estadoCarteraService.actualizar(empresa.id)`) y en `cambiarEstado` (línea con `estadoCarteraService.actualizar(oportunidad.idEmpresa)`), el valor de retorno ahora es `CambioEstadoCartera?` en vez de `Unit`. Como Kotlin no obliga a usar el valor de retorno, el código compila igual sin cambios — no se toca nada más en este task.

- [ ] **Step 8: Ejecutar los tests y confirmar que pasan; confirmar que el resto del proyecto sigue compilando**

Run: `./gradlew test`
Expected: PASS (incluye el nuevo `EmpresaServiceImplTest` y toda la suite existente sin regresiones)

- [ ] **Step 9: Commit**

```bash
git add src/main/kotlin/pe/quantum/crm/domain/empresas/dto/EmpresaDtos.kt src/main/kotlin/pe/quantum/crm/domain/empresas/EmpresaService.kt src/main/kotlin/pe/quantum/crm/domain/empresas/EmpresaServiceImpl.kt src/main/kotlin/pe/quantum/crm/domain/oportunidades/EstadoCarteraService.kt src/test/kotlin/pe/quantum/crm/domain/empresas/EmpresaServiceImplTest.kt
git commit -m "refactor(empresas): aplicarEstadoDerivado devuelve el cambio de estado (para el hook empresa_convertida)"
```

---

### Task 8: `empresa_asignada` — hook en `EmpresaServiceImpl.reasignarVendedor`

**Files:**
- Modify: `src/main/kotlin/pe/quantum/crm/domain/empresas/EmpresaService.kt`
- Modify: `src/main/kotlin/pe/quantum/crm/domain/empresas/EmpresaServiceImpl.kt`
- Modify: `src/main/kotlin/pe/quantum/crm/domain/empresas/EmpresaController.kt`
- Modify: `src/test/kotlin/pe/quantum/crm/domain/empresas/EmpresaServiceImplTest.kt`

**Interfaces:**
- Consumes: `NotificacionService.notificar(...)` (Task 5).
- Produces: `EmpresaService.reasignarVendedor(id, idVendedor, usuario: UsuarioActual): Long` (firma cambiada, antes sin `usuario`).

- [ ] **Step 1: Escribir el test que falla**

Agregar a `EmpresaServiceImplTest.kt` (actualizar el constructor de `service` para incluir `notificacionService`):

```kotlin
    private val notificacionService = mockk<NotificacionService>(relaxed = true)
    private val service = EmpresaServiceImpl(empresaRepository, empleadoService, notificacionService)

    @Test
    fun `reasignarVendedor notifica al vendedor destino con el nombre del actor y de la empresa`() {
        val entidad = empresa()
        every { empresaRepository.findById(1) } returns Optional.of(entidad)
        every { empleadoService.existeActivo(2) } returns true
        every { empresaRepository.save(entidad) } returns entidad
        every { empleadoService.resumenPorIds(listOf(9)) } returns mapOf(9L to EmpleadoResumen(id = 9, nombres = "Ana", apellidos = "Diaz"))

        service.reasignarVendedor(1, 2, UsuarioActual(id = 9, rol = "jdv"))

        verify {
            notificacionService.notificar(
                destinatarios = setOf(2L),
                idActor = 9L,
                tipo = TipoNotificacion.empresa_asignada,
                mensaje = "Ana Diaz te asignó la empresa Transportes ABC",
                entidadTipo = EntidadNotificacion.empresa,
                entidadId = 1L,
            )
        }
    }
```

Agregar imports: `io.mockk.verify`, `pe.quantum.crm.domain.empleados.dto.EmpleadoResumen`, `pe.quantum.crm.domain.notificaciones.EntidadNotificacion`, `pe.quantum.crm.domain.notificaciones.NotificacionService`, `pe.quantum.crm.domain.notificaciones.TipoNotificacion`, `pe.quantum.crm.shared.security.UsuarioActual`.

- [ ] **Step 2: Ejecutar y confirmar que falla**

Run: `./gradlew test --tests "pe.quantum.crm.domain.empresas.EmpresaServiceImplTest"`
Expected: FAIL — no compila (constructor de 2 args, `reasignarVendedor` sin `usuario`).

- [ ] **Step 3: Cambiar la firma en la interfaz**

```kotlin
    /** Reasignacion de vendedor (solo admin/gerente/jdv — verificado en controller). Notifica al vendedor destino. */
    fun reasignarVendedor(
        id: Long,
        idVendedor: Long,
        usuario: UsuarioActual,
    ): Long
```

- [ ] **Step 4: Implementar en `EmpresaServiceImpl`**

Agregar `private val notificacionService: NotificacionService` al constructor y los imports (`pe.quantum.crm.domain.notificaciones.EntidadNotificacion`, `NotificacionService`, `TipoNotificacion`, `pe.quantum.crm.domain.empleados.dto.nombreCompleto`). Reemplazar el método:

```kotlin
    @Transactional
    override fun reasignarVendedor(
        id: Long,
        idVendedor: Long,
        usuario: UsuarioActual,
    ): Long {
        val empresa = entidad(id)
        if (!empleadoService.existeActivo(idVendedor)) {
            throw NoEncontradoException("El vendedor no existe o está inactivo")
        }
        empresa.idVendedor = idVendedor
        empresa.updatedAt = LocalDateTime.now()
        empresaRepository.save(empresa)
        val actor = empleadoService.resumenPorIds(listOf(usuario.id))[usuario.id]
        notificacionService.notificar(
            destinatarios = setOf(idVendedor),
            idActor = usuario.id,
            tipo = TipoNotificacion.empresa_asignada,
            mensaje = "${actor?.nombreCompleto()} te asignó la empresa ${empresa.razonSocial}",
            entidadTipo = EntidadNotificacion.empresa,
            entidadId = id,
        )
        return idVendedor
    }
```

- [ ] **Step 5: Actualizar el controller**

```kotlin
    @PatchMapping("/{id}/vendedor")
    @PreAuthorize("hasAnyRole('admin', 'gerente', 'jdv')")
    fun reasignarVendedor(
        @PathVariable id: Long,
        @RequestBody request: ReasignarVendedorRequest,
    ): ApiResponse<Map<String, Long>> {
        val idVendedor = empresaService.reasignarVendedor(id, request.idVendedor, usuarioProvider.actual())
        return ApiResponse.ok(mapOf("id_vendedor" to idVendedor))
    }
```

- [ ] **Step 6: Ejecutar los tests y confirmar que pasan**

Run: `./gradlew test --tests "pe.quantum.crm.domain.empresas.EmpresaServiceImplTest"`
Expected: PASS

- [ ] **Step 7: Commit**

```bash
git add src/main/kotlin/pe/quantum/crm/domain/empresas/EmpresaService.kt src/main/kotlin/pe/quantum/crm/domain/empresas/EmpresaServiceImpl.kt src/main/kotlin/pe/quantum/crm/domain/empresas/EmpresaController.kt src/test/kotlin/pe/quantum/crm/domain/empresas/EmpresaServiceImplTest.kt
git commit -m "feat(empresas): notificar empresa_asignada al reasignar vendedor"
```

---

### Task 9: `oportunidad_traspasada` — hook en `OportunidadServiceImpl.traspasar`

**Files:**
- Modify: `src/main/kotlin/pe/quantum/crm/domain/oportunidades/OportunidadService.kt`
- Modify: `src/main/kotlin/pe/quantum/crm/domain/oportunidades/OportunidadServiceImpl.kt`
- Modify: `src/main/kotlin/pe/quantum/crm/domain/oportunidades/OportunidadController.kt`
- Test: `src/test/kotlin/pe/quantum/crm/domain/oportunidades/OportunidadServiceImplTest.kt`

**Interfaces:**
- Consumes: `NotificacionService.notificar(...)`, `EmpresaService.resumenPorIds(...)` (para el nombre de la empresa).
- Produces: `OportunidadService.traspasar(id, idVendedor, usuario: UsuarioActual): Long` (firma cambiada).

No existe `OportunidadServiceImplTest.kt` — se crea en este task (con el subconjunto de dependencias que necesita este primer test; las demás pruebas de `OportunidadServiceImpl` de los Tasks 10-11 se agregan al mismo archivo).

- [ ] **Step 1: Escribir el test que falla**

```kotlin
package pe.quantum.crm.domain.oportunidades

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import pe.quantum.crm.domain.contactos.ContactoService
import pe.quantum.crm.domain.empleados.EmpleadoService
import pe.quantum.crm.domain.empleados.dto.EmpleadoResumen
import pe.quantum.crm.domain.empresas.EmpresaService
import pe.quantum.crm.domain.empresas.dto.EmpresaResumen
import pe.quantum.crm.domain.financiadoras.FinanciadoraService
import pe.quantum.crm.domain.modelos.ModeloService
import pe.quantum.crm.domain.notificaciones.EntidadNotificacion
import pe.quantum.crm.domain.notificaciones.NotificacionService
import pe.quantum.crm.domain.notificaciones.TipoNotificacion
import pe.quantum.crm.shared.security.UsuarioActual
import java.time.LocalDateTime
import java.util.Optional

class OportunidadServiceImplTest {
    private val oportunidadRepository = mockk<OportunidadRepository>()
    private val logRepository = mockk<OportunidadEstadoLogRepository>()
    private val contactoOportunidadRepository = mockk<OportunidadContactoRepository>()
    private val estadoCarteraService = mockk<EstadoCarteraService>()
    private val empresaService = mockk<EmpresaService>()
    private val empleadoService = mockk<EmpleadoService>()
    private val financiadoraService = mockk<FinanciadoraService>()
    private val modeloService = mockk<ModeloService>()
    private val contactoService = mockk<ContactoService>()
    private val consultas = mockk<OportunidadConsultas>()
    private val notificacionService = mockk<NotificacionService>(relaxed = true)
    private val service =
        OportunidadServiceImpl(
            oportunidadRepository,
            logRepository,
            contactoOportunidadRepository,
            estadoCarteraService,
            empresaService,
            empleadoService,
            financiadoraService,
            modeloService,
            contactoService,
            consultas,
            notificacionService,
        )

    private fun oportunidad(idVendedor: Long = 1) =
        Oportunidad(
            id = 100,
            idEmpresa = 10,
            idVendedor = idVendedor,
            idFinanciadora = 1,
            idModelo = 1,
            estado = pe.quantum.crm.shared.enums.EstadoOportunidad.evaluacion_calidda,
            cantidad = 1,
            precioUnitario = java.math.BigDecimal.TEN,
            dcto = java.math.BigDecimal.ZERO,
            montoTotal = java.math.BigDecimal.TEN,
            createdAt = LocalDateTime.now(),
            createdBy = 1,
            updatedAt = LocalDateTime.now(),
            updatedBy = 1,
        )

    @Test
    fun `traspasar notifica al vendedor destino`() {
        val entidad = oportunidad(idVendedor = 1)
        every { oportunidadRepository.findById(100) } returns Optional.of(entidad)
        every { empleadoService.existeActivo(2) } returns true
        every { oportunidadRepository.save(entidad) } returns entidad
        every { empleadoService.resumenPorIds(listOf(9)) } returns mapOf(9L to EmpleadoResumen(id = 9, nombres = "Luis", apellidos = "Soto"))
        every { empresaService.resumenPorIds(listOf(10)) } returns mapOf(10L to EmpresaResumen(id = 10, razonSocial = "Kincar S.A.C.", distrito = null))

        service.traspasar(100, 2, UsuarioActual(id = 9, rol = "jdv"))

        verify {
            notificacionService.notificar(
                destinatarios = setOf(2L),
                idActor = 9L,
                tipo = TipoNotificacion.oportunidad_traspasada,
                mensaje = "Luis Soto te traspasó la oportunidad de Kincar S.A.C.",
                entidadTipo = EntidadNotificacion.oportunidad,
                entidadId = 100L,
            )
        }
        assertThat(entidad.idVendedor).isEqualTo(2)
    }
}
```

- [ ] **Step 2: Ejecutar y confirmar que falla**

Run: `./gradlew test --tests "pe.quantum.crm.domain.oportunidades.OportunidadServiceImplTest"`
Expected: FAIL — no compila (constructor de 10 args real vs 11 esperados por el test; `traspasar` sin `usuario`).

- [ ] **Step 3: Cambiar la firma en la interfaz**

```kotlin
    /** Traspaso de vendedor. Notifica al vendedor destino (oportunidad_traspasada). */
    fun traspasar(
        id: Long,
        idVendedor: Long,
        usuario: UsuarioActual,
    ): Long
```

- [ ] **Step 4: Implementar en `OportunidadServiceImpl`**

Agregar `private val notificacionService: NotificacionService` al constructor (la clase ya tiene `@Suppress("TooManyFunctions", "LongParameterList")`, no requiere ajuste de supresión). Reemplazar `traspasar`:

```kotlin
    @Transactional
    override fun traspasar(
        id: Long,
        idVendedor: Long,
        usuario: UsuarioActual,
    ): Long {
        val oportunidad = entidad(id)
        if (!empleadoService.existeActivo(idVendedor)) {
            throw NoEncontradoException("El vendedor no existe o está inactivo")
        }
        oportunidad.idVendedor = idVendedor
        oportunidad.updatedAt = LocalDateTime.now()
        oportunidadRepository.save(oportunidad)
        val actor = empleadoService.resumenPorIds(listOf(usuario.id))[usuario.id]
        val empresa = empresaService.resumenPorIds(listOf(oportunidad.idEmpresa))[oportunidad.idEmpresa]
        notificacionService.notificar(
            destinatarios = setOf(idVendedor),
            idActor = usuario.id,
            tipo = TipoNotificacion.oportunidad_traspasada,
            mensaje = "${actor?.nombreCompleto()} te traspasó la oportunidad de ${empresa?.razonSocial}",
            entidadTipo = EntidadNotificacion.oportunidad,
            entidadId = id,
        )
        return idVendedor
    }
```

Agregar imports: `pe.quantum.crm.domain.empleados.dto.nombreCompleto`, `pe.quantum.crm.domain.notificaciones.EntidadNotificacion`, `pe.quantum.crm.domain.notificaciones.NotificacionService`, `pe.quantum.crm.domain.notificaciones.TipoNotificacion`.

- [ ] **Step 5: Actualizar el controller**

```kotlin
    @PatchMapping("/{id}/vendedor")
    @PreAuthorize("hasAnyRole('admin', 'gerente', 'jdv')")
    fun traspasar(
        @PathVariable id: Long,
        @RequestBody request: TraspasarVendedorRequest,
    ): ApiResponse<Map<String, Long>> {
        val idVendedor = oportunidadService.traspasar(id, request.idVendedor, usuarioProvider.actual())
        return ApiResponse.ok(mapOf("id_vendedor" to idVendedor))
    }
```

- [ ] **Step 6: Ejecutar y confirmar que pasa**

Run: `./gradlew test --tests "pe.quantum.crm.domain.oportunidades.OportunidadServiceImplTest"`
Expected: PASS

- [ ] **Step 7: Commit**

```bash
git add src/main/kotlin/pe/quantum/crm/domain/oportunidades/OportunidadService.kt src/main/kotlin/pe/quantum/crm/domain/oportunidades/OportunidadServiceImpl.kt src/main/kotlin/pe/quantum/crm/domain/oportunidades/OportunidadController.kt src/test/kotlin/pe/quantum/crm/domain/oportunidades/OportunidadServiceImplTest.kt
git commit -m "feat(oportunidades): notificar oportunidad_traspasada al traspasar vendedor"
```

---

### Task 10: `oportunidad_cambio_estado` — hook en `OportunidadServiceImpl.cambiarEstado`

**Files:**
- Modify: `src/main/kotlin/pe/quantum/crm/domain/oportunidades/OportunidadServiceImpl.kt`
- Modify: `src/test/kotlin/pe/quantum/crm/domain/oportunidades/OportunidadServiceImplTest.kt`

**Interfaces:**
- Consumes: `EmpleadoService.idsSupervisoresActivos()` (Task 4), `NotificacionService.notificar(...)`.

- [ ] **Step 1: Escribir el test que falla**

Agregar a `OportunidadServiceImplTest.kt`:

```kotlin
    @Test
    fun `cambiarEstado notifica a los supervisores activos, excluyendo al actor si es supervisor`() {
        val entidad = oportunidad(idVendedor = 1)
        every { oportunidadRepository.findById(100) } returns Optional.of(entidad)
        every { oportunidadRepository.save(entidad) } returns entidad
        every { logRepository.save(any()) } returns mockk()
        every { consultas.eventosRecomendadosSinRegistrar(100, pe.quantum.crm.shared.enums.EstadoOportunidad.evaluacion_calidda) } returns emptyList()
        every { estadoCarteraService.actualizar(10) } returns null
        every { empresaService.resumenPorIds(listOf(10)) } returns mapOf(10L to EmpresaResumen(id = 10, razonSocial = "Kincar S.A.C.", distrito = null))
        every { empleadoService.resumenPorIds(listOf(1)) } returns mapOf(1L to EmpleadoResumen(id = 1, nombres = "Ana", apellidos = "Diaz"))
        every { empleadoService.idsSupervisoresActivos() } returns listOf(1, 5, 6)

        service.cambiarEstado(
            100,
            pe.quantum.crm.domain.oportunidades.dto.CambiarEstadoRequest(estado = "documentos_legales"),
            UsuarioActual(id = 1, rol = "vendedor"),
        )

        verify {
            notificacionService.notificar(
                destinatarios = setOf(1L, 5L, 6L),
                idActor = 1L,
                tipo = TipoNotificacion.oportunidad_cambio_estado,
                mensaje = "Ana Diaz cambió el estado de Kincar S.A.C. a Documentos legales",
                entidadTipo = EntidadNotificacion.oportunidad,
                entidadId = 100L,
            )
        }
    }
```

- [ ] **Step 2: Ejecutar y confirmar que falla**

Run: `./gradlew test --tests "pe.quantum.crm.domain.oportunidades.OportunidadServiceImplTest"`
Expected: FAIL — `notificar` no se invoca todavía / `etiquetaEstado` no existe.

- [ ] **Step 3: Implementar el hook y el helper de etiqueta**

En `OportunidadServiceImpl.cambiarEstado`, después de `estadoCarteraService.actualizar(oportunidad.idEmpresa)` y antes del `return`:

```kotlin
        // Misma transaccion (reglas §3.3, §13.3).
        val cambioCartera = estadoCarteraService.actualizar(oportunidad.idEmpresa)
        notificarConversionSiAplica(cambioCartera, oportunidad.idEmpresa, id, usuario)
        notificarCambioEstado(oportunidad.idEmpresa, id, nuevo, usuario)
        return CambioEstadoDto(estado = nuevo.name, esRetroceso = esRetroceso, advertencias = advertencias)
```

Agregar los privados (junto a los demás métodos privados, cerca de `esRetroceso`):

```kotlin
    private fun notificarCambioEstado(
        idEmpresa: Long,
        idOportunidad: Long,
        nuevo: EstadoOportunidad,
        usuario: UsuarioActual,
    ) {
        val empresa = empresaService.resumenPorIds(listOf(idEmpresa))[idEmpresa] ?: return
        val actor = empleadoService.resumenPorIds(listOf(usuario.id))[usuario.id] ?: return
        notificacionService.notificar(
            destinatarios = empleadoService.idsSupervisoresActivos().toSet(),
            idActor = usuario.id,
            tipo = TipoNotificacion.oportunidad_cambio_estado,
            mensaje = "${actor.nombreCompleto()} cambió el estado de ${empresa.razonSocial} a ${etiquetaEstado(nuevo)}",
            entidadTipo = EntidadNotificacion.oportunidad,
            entidadId = idOportunidad,
        )
    }

    /**
     * `empresa_convertida`: solo cuando `estadoCarteraService.actualizar` reporta
     * la transicion prospeccion -> oportunidad_activa. Se llama tanto desde
     * `crear` (Task 11) como desde `cambiarEstado` (el retroceso de reglas §13.1
     * puede en teoria producir la misma transicion desde `cambiarEstado`).
     */
    private fun notificarConversionSiAplica(
        cambio: CambioEstadoCartera?,
        idEmpresa: Long,
        idOportunidad: Long,
        usuario: UsuarioActual,
    ) {
        if (cambio?.anterior != EstadoCartera.prospeccion || cambio.nuevo != EstadoCartera.oportunidad_activa) {
            return
        }
        val empresa = empresaService.resumenPorIds(listOf(idEmpresa))[idEmpresa] ?: return
        val actor = empleadoService.resumenPorIds(listOf(usuario.id))[usuario.id] ?: return
        notificacionService.notificar(
            destinatarios = empleadoService.idsSupervisoresActivos().toSet(),
            idActor = usuario.id,
            tipo = TipoNotificacion.empresa_convertida,
            mensaje = "${actor.nombreCompleto()} convirtió ${empresa.razonSocial} de prospección a oportunidad",
            entidadTipo = EntidadNotificacion.oportunidad,
            entidadId = idOportunidad,
        )
    }

    private fun etiquetaEstado(estado: EstadoOportunidad): String =
        when (estado) {
            EstadoOportunidad.evaluacion_calidda -> "Evaluación Calidda"
            EstadoOportunidad.documentos_legales -> "Documentos legales"
            EstadoOportunidad.facturado -> "Facturado"
            EstadoOportunidad.cerrado -> "Cerrado"
        }
```

Agregar imports: `pe.quantum.crm.domain.empleados.dto.nombreCompleto`, `pe.quantum.crm.domain.empresas.dto.CambioEstadoCartera`, `pe.quantum.crm.shared.enums.EstadoCartera`.

(`notificarConversionSiAplica` se deja implementada ya en este task porque comparte código con `notificarCambioEstado`; el Task 11 solo la conecta también desde `crear`.)

- [ ] **Step 4: Ejecutar y confirmar que pasa**

Run: `./gradlew test --tests "pe.quantum.crm.domain.oportunidades.OportunidadServiceImplTest"`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/pe/quantum/crm/domain/oportunidades/OportunidadServiceImpl.kt src/test/kotlin/pe/quantum/crm/domain/oportunidades/OportunidadServiceImplTest.kt
git commit -m "feat(oportunidades): notificar oportunidad_cambio_estado a los supervisores"
```

---

### Task 11: `empresa_convertida` — enganche en `OportunidadServiceImpl.crear`

**Files:**
- Modify: `src/main/kotlin/pe/quantum/crm/domain/oportunidades/OportunidadServiceImpl.kt`
- Modify: `src/test/kotlin/pe/quantum/crm/domain/oportunidades/OportunidadServiceImplTest.kt`

**Interfaces:**
- Consumes: `notificarConversionSiAplica` (privado, ya implementado en Task 10).

- [ ] **Step 1: Escribir el test que falla**

```kotlin
    @Test
    fun `crear notifica empresa_convertida cuando la empresa pasa de prospeccion a oportunidad_activa`() {
        every { empresaService.vinculoVisible(10, any()) } returns
            pe.quantum.crm.domain.empresas.dto.EmpresaVinculo(id = 10, razonSocial = "Kincar S.A.C.", idVendedor = 3, estadoCartera = "prospeccion")
        every { modeloService.resumen(1) } returns
            pe.quantum.crm.domain.modelos.dto.ModeloResumen(id = 1, codigo = "BUS-X", precioBase = java.math.BigDecimal.TEN)
        every { financiadoraService.default() } returns
            pe.quantum.crm.domain.financiadoras.dto.FinanciadoraDto(
                id = 1,
                nombre = "Calidda",
                montoPorUnidad = null,
                plazoMeses = null,
                tea = null,
                cuotaPorUnidad = null,
                esDefault = true,
                notas = null,
            )
        val guardada = slot<Oportunidad>()
        every { oportunidadRepository.save(capture(guardada)) } answers { guardada.captured.also { } }
        every { logRepository.save(any()) } returns mockk()
        every {
            estadoCarteraService.actualizar(10)
        } returns CambioEstadoCartera(anterior = EstadoCartera.prospeccion, nuevo = EstadoCartera.oportunidad_activa)
        every { empresaService.resumenPorIds(listOf(10)) } returns mapOf(10L to EmpresaResumen(id = 10, razonSocial = "Kincar S.A.C.", distrito = null))
        every { empleadoService.resumenPorIds(listOf(3)) } returns mapOf(3L to EmpleadoResumen(id = 3, nombres = "Jose", apellidos = "Lima"))
        every { empleadoService.idsSupervisoresActivos() } returns listOf(9)

        service.crear(
            pe.quantum.crm.domain.oportunidades.dto.CrearOportunidadRequest(idEmpresa = 10, idModelo = 1, cantidad = 1, dcto = java.math.BigDecimal.ZERO),
            UsuarioActual(id = 3, rol = "vendedor"),
        )

        verify {
            notificacionService.notificar(
                destinatarios = setOf(9L),
                idActor = 3L,
                tipo = TipoNotificacion.empresa_convertida,
                mensaje = "Jose Lima convirtió Kincar S.A.C. de prospección a oportunidad",
                entidadTipo = EntidadNotificacion.oportunidad,
                entidadId = any(),
            )
        }
    }
```

- [ ] **Step 2: Ejecutar y confirmar que falla**

Run: `./gradlew test --tests "pe.quantum.crm.domain.oportunidades.OportunidadServiceImplTest"`
Expected: FAIL — `crear` no invoca `notificarConversionSiAplica` todavía.

- [ ] **Step 3: Conectar el hook en `crear`**

Reemplazar la línea `estadoCarteraService.actualizar(empresa.id)` (justo antes de `return toDto(oportunidad, detalle = true)`) por:

```kotlin
        // Misma transaccion (reglas §3.3): la empresa sube a oportunidad_activa.
        val cambioCartera = estadoCarteraService.actualizar(empresa.id)
        notificarConversionSiAplica(cambioCartera, empresa.id, idOportunidad, usuario)
        return toDto(oportunidad, detalle = true)
```

- [ ] **Step 4: Ejecutar y confirmar que pasa**

Run: `./gradlew test --tests "pe.quantum.crm.domain.oportunidades.OportunidadServiceImplTest"`
Expected: PASS

- [ ] **Step 5: Correr toda la suite unitaria (no solo este archivo)**

Run: `./gradlew test`
Expected: PASS — confirma que los cambios de Tasks 7-11 no rompieron nada en otros módulos.

- [ ] **Step 6: Commit**

```bash
git add src/main/kotlin/pe/quantum/crm/domain/oportunidades/OportunidadServiceImpl.kt src/test/kotlin/pe/quantum/crm/domain/oportunidades/OportunidadServiceImplTest.kt
git commit -m "feat(oportunidades): notificar empresa_convertida al crear la primera oportunidad activa"
```

---

### Task 12: `evento_creado` — hook en `EventoServiceImpl`

**Files:**
- Modify: `src/main/kotlin/pe/quantum/crm/domain/eventos/EventoServiceImpl.kt`
- Modify: `src/test/kotlin/pe/quantum/crm/domain/eventos/EventoServiceImplTest.kt`

**Interfaces:**
- Consumes: `EmpleadoService`, `NotificacionService` (nuevas dependencias del constructor).
- Produces: mensaje `"{actor} creó un evento en {empresa.razonSocial}"`.

**IMPORTANTE:** `EventoServiceImplTest.kt` ya existe con 5 tests que construyen `EventoServiceImpl(eventoRepository, catalogoEventoService, oportunidadService, empresaService)` (4 args) y usan mocks **estrictos** (`mockk<T>()`, no relajados). Agregar 2 dependencias nuevas al constructor rompe la compilación de ese archivo. La estrategia: los mocks de `empresaService`/`oportunidadService` pasan a ser relajados (para que las llamadas nuevas del hook — `resumenPorIds`, etc. — no exploten en los tests existentes que no las stubean), y se agregan `empleadoService`/`notificacionService` como mocks relajados nuevos. Los tests existentes siguen verificando exactamente lo mismo que antes (no se tocan sus aserciones).

- [ ] **Step 1: Escribir el test nuevo que falla (y ajustar el constructor compartido)**

En `EventoServiceImplTest.kt`, cambiar las líneas 27-31 de:

```kotlin
    private val eventoRepository = mockk<EventoRepository>()
    private val catalogoEventoService = mockk<CatalogoEventoService>()
    private val oportunidadService = mockk<OportunidadService>()
    private val empresaService = mockk<EmpresaService>()
    private val service = EventoServiceImpl(eventoRepository, catalogoEventoService, oportunidadService, empresaService)
```

a:

```kotlin
    private val eventoRepository = mockk<EventoRepository>()
    private val catalogoEventoService = mockk<CatalogoEventoService>()
    private val oportunidadService = mockk<OportunidadService>(relaxed = true)
    private val empresaService = mockk<EmpresaService>(relaxed = true)
    private val empleadoService = mockk<EmpleadoService>(relaxed = true)
    private val notificacionService = mockk<NotificacionService>(relaxed = true)
    private val service =
        EventoServiceImpl(eventoRepository, catalogoEventoService, oportunidadService, empresaService, empleadoService, notificacionService)
```

Agregar imports: `io.mockk.verify`, `pe.quantum.crm.domain.empleados.EmpleadoService`, `pe.quantum.crm.domain.empleados.dto.EmpleadoResumen`, `pe.quantum.crm.domain.notificaciones.EntidadNotificacion`, `pe.quantum.crm.domain.notificaciones.NotificacionService`, `pe.quantum.crm.domain.notificaciones.TipoNotificacion`, `pe.quantum.crm.domain.empresas.dto.EmpresaResumen`.

Correr la suite existente primero para confirmar que sigue en verde con el cambio de mocks relajados (sin el nuevo test todavía):

Run: `./gradlew test --tests "pe.quantum.crm.domain.eventos.EventoServiceImplTest"`
Expected: FAIL en este punto porque `EventoServiceImpl` aún no tiene el constructor de 6 args — es la señal esperada para pasar al siguiente step.

Ahora agregar el test nuevo al final de la clase:

```kotlin
    @Test
    fun `crear evento en una oportunidad notifica al vendedor asignado cuando el actor no es el`() {
        every { oportunidadService.vinculoVisible(50, any()) } returns
            pe.quantum.crm.domain.oportunidades.dto.OportunidadVinculo(id = 50, idEmpresa = 10, idVendedor = 3, estado = "evaluacion_calidda")
        every { catalogoEventoService.porId(5) } returns catalogo()
        every { eventoRepository.save(any()) } answers { (firstArg<Evento>()).conId(1) }
        every { empresaService.resumenPorIds(listOf(10)) } returns mapOf(10L to EmpresaResumen(id = 10, razonSocial = "Kincar S.A.C.", distrito = null))
        every { empleadoService.resumenPorIds(listOf(7)) } returns mapOf(7L to EmpleadoResumen(id = 7, nombres = "Rosa", apellidos = "Vega"))

        service.crearEnOportunidad(50, CrearEventoRequest(idCatalogoEvento = 5), UsuarioActual(id = 7, rol = "analista"))

        verify {
            notificacionService.notificar(
                destinatarios = setOf(3L),
                idActor = 7L,
                tipo = TipoNotificacion.evento_creado,
                mensaje = "Rosa Vega creó un evento en Kincar S.A.C.",
                entidadTipo = pe.quantum.crm.domain.notificaciones.EntidadNotificacion.oportunidad,
                entidadId = 50L,
            )
        }
    }

    @Test
    fun `crear evento cuando el actor es el propio vendedor notifica a supervisores en vez de a si mismo`() {
        every { oportunidadService.vinculoVisible(50, any()) } returns
            pe.quantum.crm.domain.oportunidades.dto.OportunidadVinculo(id = 50, idEmpresa = 10, idVendedor = 7, estado = "evaluacion_calidda")
        every { catalogoEventoService.porId(5) } returns catalogo()
        every { eventoRepository.save(any()) } answers { (firstArg<Evento>()).conId(1) }
        every { empresaService.resumenPorIds(listOf(10)) } returns mapOf(10L to EmpresaResumen(id = 10, razonSocial = "Kincar S.A.C.", distrito = null))
        every { empleadoService.resumenPorIds(listOf(7)) } returns mapOf(7L to EmpleadoResumen(id = 7, nombres = "Rosa", apellidos = "Vega"))
        every { empleadoService.idsSupervisoresActivos() } returns listOf(1, 2)

        service.crearEnOportunidad(50, CrearEventoRequest(idCatalogoEvento = 5), UsuarioActual(id = 7, rol = "vendedor"))

        verify {
            notificacionService.notificar(
                destinatarios = setOf(1L, 2L),
                idActor = 7L,
                tipo = TipoNotificacion.evento_creado,
                mensaje = "Rosa Vega creó un evento en Kincar S.A.C.",
                entidadTipo = pe.quantum.crm.domain.notificaciones.EntidadNotificacion.oportunidad,
                entidadId = 50L,
            )
        }
    }
```

- [ ] **Step 2: Ejecutar y confirmar que falla**

Run: `./gradlew test --tests "pe.quantum.crm.domain.eventos.EventoServiceImplTest"`
Expected: FAIL — no compila (constructor de 4 args real).

- [ ] **Step 3: Cambiar el constructor y conectar el hook**

En `EventoServiceImpl.kt`:

```kotlin
@Service
@Suppress("LongParameterList") // Cruza 3 modulos + notificaciones (evento_creado).
class EventoServiceImpl(
    private val eventoRepository: EventoRepository,
    private val catalogoEventoService: CatalogoEventoService,
    private val oportunidadService: OportunidadService,
    private val empresaService: EmpresaService,
    private val empleadoService: EmpleadoService,
    private val notificacionService: NotificacionService,
) : EventoService {
```

Modificar `crearEnOportunidad` y `crearEnEmpresa`:

```kotlin
    @Transactional
    override fun crearEnOportunidad(
        idOportunidad: Long,
        request: CrearEventoRequest,
        usuario: UsuarioActual,
    ): EventoDto {
        val oportunidad = oportunidadService.vinculoVisible(idOportunidad, usuario)
        val evento = crear(request, idOportunidad = oportunidad.id, idEmpresa = null, usuario = usuario)
        notificarEventoCreado(
            idVendedor = oportunidad.idVendedor,
            idEmpresaParaNombre = oportunidad.idEmpresa,
            entidadTipo = EntidadNotificacion.oportunidad,
            entidadId = oportunidad.id,
            usuario = usuario,
        )
        return evento
    }

    @Transactional
    override fun crearEnEmpresa(
        idEmpresa: Long,
        request: CrearEventoRequest,
        usuario: UsuarioActual,
    ): EventoDto {
        val empresa = empresaService.vinculoVisible(idEmpresa, usuario)
        val evento = crear(request, idOportunidad = null, idEmpresa = empresa.id, usuario = usuario)
        notificarEventoCreado(
            idVendedor = empresa.idVendedor,
            idEmpresaParaNombre = empresa.id,
            entidadTipo = EntidadNotificacion.empresa,
            entidadId = empresa.id,
            usuario = usuario,
        )
        return evento
    }
```

Agregar el privado (junto a los demás métodos privados):

```kotlin
    /**
     * Vendedor asignado (si el actor no es el); si el actor ES el vendedor,
     * notifica a los supervisores en su lugar (docs/superpowers/specs/2026-07-09-notificaciones-in-app-design.md).
     */
    private fun notificarEventoCreado(
        idVendedor: Long?,
        idEmpresaParaNombre: Long,
        entidadTipo: EntidadNotificacion,
        entidadId: Long,
        usuario: UsuarioActual,
    ) {
        val empresa = empresaService.resumenPorIds(listOf(idEmpresaParaNombre))[idEmpresaParaNombre] ?: return
        val actor = empleadoService.resumenPorIds(listOf(usuario.id))[usuario.id] ?: return
        val destinatarios =
            if (idVendedor != null && idVendedor != usuario.id) {
                setOf(idVendedor)
            } else {
                empleadoService.idsSupervisoresActivos().toSet()
            }
        notificacionService.notificar(
            destinatarios = destinatarios,
            idActor = usuario.id,
            tipo = TipoNotificacion.evento_creado,
            mensaje = "${actor.nombreCompleto()} creó un evento en ${empresa.razonSocial}",
            entidadTipo = entidadTipo,
            entidadId = entidadId,
        )
    }
```

Agregar imports: `pe.quantum.crm.domain.empleados.EmpleadoService`, `pe.quantum.crm.domain.empleados.dto.nombreCompleto`, `pe.quantum.crm.domain.notificaciones.EntidadNotificacion`, `pe.quantum.crm.domain.notificaciones.NotificacionService`, `pe.quantum.crm.domain.notificaciones.TipoNotificacion`.

- [ ] **Step 4: Ejecutar y confirmar que pasa**

Run: `./gradlew test --tests "pe.quantum.crm.domain.eventos.EventoServiceImplTest"`
Expected: PASS (7 tests: los 5 originales + los 2 nuevos)

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/pe/quantum/crm/domain/eventos/EventoServiceImpl.kt src/test/kotlin/pe/quantum/crm/domain/eventos/EventoServiceImplTest.kt
git commit -m "feat(eventos): notificar evento_creado al vendedor asignado (o a supervisores si el actor es el vendedor)"
```

---

### Task 13: `tarea_creada` — hook en `TareaServiceImpl.crear`

**Files:**
- Modify: `src/main/kotlin/pe/quantum/crm/domain/tareas/TareaServiceImpl.kt`
- Test: `src/test/kotlin/pe/quantum/crm/domain/tareas/TareaServiceImplTest.kt`

**Interfaces:**
- Consumes: `NotificacionService.notificar(...)`.

No existe `TareaServiceImplTest.kt` — se crea en este task.

- [ ] **Step 1: Escribir el test que falla**

```kotlin
package pe.quantum.crm.domain.tareas

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import pe.quantum.crm.domain.contactos.ContactoService
import pe.quantum.crm.domain.empleados.EmpleadoService
import pe.quantum.crm.domain.empleados.dto.EmpleadoResumen
import pe.quantum.crm.domain.empresas.EmpresaService
import pe.quantum.crm.domain.empresas.dto.EmpresaResumen
import pe.quantum.crm.domain.empresas.dto.EmpresaVinculo
import pe.quantum.crm.domain.notificaciones.EntidadNotificacion
import pe.quantum.crm.domain.notificaciones.NotificacionService
import pe.quantum.crm.domain.notificaciones.TipoNotificacion
import pe.quantum.crm.domain.oportunidades.OportunidadService
import pe.quantum.crm.domain.tareas.dto.CrearTareaRequest
import pe.quantum.crm.shared.enums.TipoAccion
import pe.quantum.crm.shared.security.UsuarioActual

class TareaServiceImplTest {
    private val tareaRepository = mockk<TareaRepository>()
    private val empresaService = mockk<EmpresaService>()
    private val oportunidadService = mockk<OportunidadService>()
    private val contactoService = mockk<ContactoService>()
    private val empleadoService = mockk<EmpleadoService>()
    private val notificacionService = mockk<NotificacionService>(relaxed = true)
    private val service =
        TareaServiceImpl(tareaRepository, empresaService, oportunidadService, contactoService, empleadoService, notificacionService)

    /** `Tarea.id` es `val` (autogenerado): se reconstruye con un id real, simulando lo que hace JPA al guardar. */
    private fun Tarea.conId(nuevoId: Long) =
        Tarea(
            id = nuevoId,
            idEmpresa = idEmpresa,
            idOportunidad = idOportunidad,
            idContacto = idContacto,
            idAsignado = idAsignado,
            tipoAccion = tipoAccion,
            estadoAccion = estadoAccion,
            descripcion = descripcion,
            fechaEjecucion = fechaEjecucion,
            createdAt = createdAt,
            createdBy = createdBy,
            updatedAt = updatedAt,
            updatedBy = updatedBy,
        )

    @Test
    fun `crear tarea con id_asignado distinto al actor notifica tarea_creada`() {
        every { empresaService.vinculoVisible(10, any()) } returns
            EmpresaVinculo(id = 10, razonSocial = "Kincar S.A.C.", idVendedor = 3, estadoCartera = "prospeccion")
        every { oportunidadService.tieneOportunidadesActivas(10) } returns false
        every { empleadoService.existeActivo(3) } returns true
        every { tareaRepository.save(any()) } answers { firstArg<Tarea>().conId(1) }
        every { empresaService.resumenPorIds(listOf(10)) } returns mapOf(10L to EmpresaResumen(id = 10, razonSocial = "Kincar S.A.C.", distrito = null))
        every { empleadoService.resumenPorIds(any()) } returns emptyMap()
        every { empleadoService.resumenPorIds(listOf(9)) } returns mapOf(9L to EmpleadoResumen(id = 9, nombres = "Diego", apellidos = "Reyes"))

        service.crear(
            CrearTareaRequest(idEmpresa = 10, idAsignado = 3, tipoAccion = TipoAccion.llamada, descripcion = "Llamar"),
            UsuarioActual(id = 9, rol = "vendedor"),
        )

        verify {
            notificacionService.notificar(
                destinatarios = setOf(3L),
                idActor = 9L,
                tipo = TipoNotificacion.tarea_creada,
                mensaje = "Diego Reyes te asignó una tarea en Kincar S.A.C.",
                entidadTipo = EntidadNotificacion.empresa,
                entidadId = 10L,
            )
        }
    }
}
```

- [ ] **Step 2: Ejecutar y confirmar que falla**

Run: `./gradlew test --tests "pe.quantum.crm.domain.tareas.TareaServiceImplTest"`
Expected: FAIL — constructor de 5 args real, `notificar` no invocado.

- [ ] **Step 3: Implementar**

En `TareaServiceImpl.kt`, agregar `private val notificacionService: NotificacionService` al constructor. Modificar `crear`:

```kotlin
    @Transactional
    override fun crear(
        request: CrearTareaRequest,
        usuario: UsuarioActual,
    ): TareaDto {
        val empresa = empresaService.vinculoVisible(request.idEmpresa, usuario)
        val idOportunidad = request.idOportunidad
        if (idOportunidad != null) {
            val oportunidad = oportunidadService.vinculoVisible(idOportunidad, usuario)
            if (oportunidad.idEmpresa != empresa.id) {
                throw ValidacionException("La oportunidad no pertenece a la empresa indicada", field = "id_oportunidad")
            }
        } else if (oportunidadService.tieneOportunidadesActivas(empresa.id)) {
            throw ValidacionException(
                "Las tareas de empresas con oportunidades activas deben vincularse a una oportunidad",
                field = "id_oportunidad",
            )
        }
        request.idContacto?.let {
            if (!contactoService.existe(it)) {
                throw NoEncontradoException("El contacto no existe")
            }
        }
        val idAsignado = request.idAsignado ?: usuario.id
        if (!empleadoService.existeActivo(idAsignado)) {
            throw NoEncontradoException("El empleado asignado no existe o está inactivo")
        }
        val ahora = LocalDateTime.now()
        val tarea =
            tareaRepository.save(
                Tarea(
                    idEmpresa = empresa.id,
                    idOportunidad = idOportunidad,
                    idContacto = request.idContacto,
                    idAsignado = idAsignado,
                    tipoAccion = request.tipoAccion,
                    estadoAccion = EstadoAccion.pendiente,
                    descripcion = request.descripcion,
                    fechaEjecucion = request.fechaEjecucion?.let { LocalDateTime.ofInstant(it, ZoneOffset.UTC) },
                    createdAt = ahora,
                    createdBy = usuario.id,
                    updatedAt = ahora,
                    updatedBy = usuario.id,
                ),
            )
        val entidadTipo = if (idOportunidad != null) EntidadNotificacion.oportunidad else EntidadNotificacion.empresa
        val entidadId = idOportunidad ?: empresa.id
        val actor = empleadoService.resumenPorIds(listOf(usuario.id))[usuario.id]
        notificacionService.notificar(
            destinatarios = setOf(idAsignado),
            idActor = usuario.id,
            tipo = TipoNotificacion.tarea_creada,
            mensaje = "${actor?.nombreCompleto()} te asignó una tarea en ${empresa.razonSocial}",
            entidadTipo = entidadTipo,
            entidadId = entidadId,
        )
        return toDtos(listOf(tarea)).first()
    }
```

Agregar imports: `pe.quantum.crm.domain.empleados.dto.nombreCompleto`, `pe.quantum.crm.domain.notificaciones.EntidadNotificacion`, `pe.quantum.crm.domain.notificaciones.NotificacionService`, `pe.quantum.crm.domain.notificaciones.TipoNotificacion`.

- [ ] **Step 4: Ejecutar y confirmar que pasa**

Run: `./gradlew test --tests "pe.quantum.crm.domain.tareas.TareaServiceImplTest"`
Expected: PASS

- [ ] **Step 5: Correr toda la suite unitaria**

Run: `./gradlew test`
Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add src/main/kotlin/pe/quantum/crm/domain/tareas/TareaServiceImpl.kt src/test/kotlin/pe/quantum/crm/domain/tareas/TareaServiceImplTest.kt
git commit -m "feat(tareas): notificar tarea_creada al asignado"
```

---

### Task 14: Proyecciones para el job de recordatorios (tareas, eventos, oportunidades, empresas)

**Files:**
- Modify: `src/main/kotlin/pe/quantum/crm/domain/tareas/dto/TareaDtos.kt`
- Modify: `src/main/kotlin/pe/quantum/crm/domain/tareas/TareaRepository.kt`
- Modify: `src/main/kotlin/pe/quantum/crm/domain/tareas/TareaService.kt`
- Modify: `src/main/kotlin/pe/quantum/crm/domain/tareas/TareaServiceImpl.kt`
- Modify: `src/main/kotlin/pe/quantum/crm/domain/eventos/dto/EventoDtos.kt`
- Modify: `src/main/kotlin/pe/quantum/crm/domain/eventos/EventoRepository.kt`
- Modify: `src/main/kotlin/pe/quantum/crm/domain/eventos/EventoService.kt`
- Modify: `src/main/kotlin/pe/quantum/crm/domain/eventos/EventoServiceImpl.kt`
- Modify: `src/main/kotlin/pe/quantum/crm/domain/oportunidades/dto/OportunidadDtos.kt`
- Modify: `src/main/kotlin/pe/quantum/crm/domain/oportunidades/OportunidadService.kt`
- Modify: `src/main/kotlin/pe/quantum/crm/domain/oportunidades/OportunidadServiceImpl.kt`
- Modify: `src/main/kotlin/pe/quantum/crm/domain/empresas/EmpresaService.kt`
- Modify: `src/main/kotlin/pe/quantum/crm/domain/empresas/EmpresaServiceImpl.kt`
- Modify: `src/test/kotlin/pe/quantum/crm/domain/tareas/TareaServiceImplTest.kt`
- Modify: `src/test/kotlin/pe/quantum/crm/domain/eventos/EventoServiceImplTest.kt`

**Interfaces:**
- Produces:
  - `TareaService.pendientesParaRecordatorio(): List<TareaRecordatorioProyeccion>` — `TareaRecordatorioProyeccion(id: Long, idAsignado: Long, idEmpresa: Long, idOportunidad: Long?, fechaEjecucion: LocalDateTime)`.
  - `EventoService.pendientesParaRecordatorio(): List<EventoRecordatorioProyeccion>` — `EventoRecordatorioProyeccion(id: Long, idOportunidad: Long?, idEmpresa: Long?, fechaEstimada: LocalDate)`.
  - `OportunidadService.datosRecordatorio(id: Long): OportunidadRecordatorioDatos?` — `OportunidadRecordatorioDatos(idEmpresa: Long, idVendedor: Long)`.
  - `EmpresaService.vendedorAsignado(id: Long): Long?`.
- Consumidos por el job del Task 15.

- [ ] **Step 1: Escribir los tests que fallan**

Agregar a `TareaServiceImplTest.kt`:

```kotlin
    @Test
    fun `pendientesParaRecordatorio proyecta solo tareas pendientes con asignado y fecha`() {
        every { tareaRepository.findByEstadoAccionAndIdAsignadoIsNotNullAndFechaEjecucionIsNotNull(EstadoAccion.pendiente) } returns
            listOf(
                Tarea(
                    id = 1,
                    idEmpresa = 10,
                    idOportunidad = null,
                    idAsignado = 3,
                    tipoAccion = TipoAccion.llamada,
                    estadoAccion = EstadoAccion.pendiente,
                    fechaEjecucion = java.time.LocalDateTime.of(2026, 7, 10, 9, 0),
                    createdAt = java.time.LocalDateTime.now(),
                    createdBy = 1,
                    updatedAt = java.time.LocalDateTime.now(),
                    updatedBy = 1,
                ),
            )

        val resultado = service.pendientesParaRecordatorio()

        assertThat(resultado).hasSize(1)
        assertThat(resultado.first().idAsignado).isEqualTo(3)
    }
```

(agregar `import org.assertj.core.api.Assertions.assertThat` si no está.)

Agregar a `EventoServiceImplTest.kt`:

```kotlin
    @Test
    fun `pendientesParaRecordatorio proyecta solo eventos pendientes con fecha_estimada`() {
        every { eventoRepository.findByEstadoAndFechaEstimadaIsNotNull(EstadoEvento.pendiente) } returns
            listOf(
                Evento(
                    id = 1,
                    idOportunidad = 50,
                    idEmpresa = null,
                    idCatalogoEvento = 5,
                    fechaEstimada = java.time.LocalDate.of(2026, 7, 10),
                    createdBy = 1,
                    updatedBy = 1,
                ),
            )

        val resultado = service.pendientesParaRecordatorio()

        assertThat(resultado).hasSize(1)
        assertThat(resultado.first().idOportunidad).isEqualTo(50)
    }
```

- [ ] **Step 2: Ejecutar y confirmar que fallan**

Run: `./gradlew test --tests "pe.quantum.crm.domain.tareas.TareaServiceImplTest" --tests "pe.quantum.crm.domain.eventos.EventoServiceImplTest"`
Expected: FAIL — métodos/proyecciones no existen.

- [ ] **Step 3: Tareas — DTO, repositorio, interfaz, impl**

En `TareaDtos.kt`, agregar:

```kotlin
/** Proyeccion de solo lectura para el job de recordatorios (notificaciones). */
data class TareaRecordatorioProyeccion(
    val id: Long,
    val idAsignado: Long,
    val idEmpresa: Long,
    val idOportunidad: Long?,
    val fechaEjecucion: LocalDateTime,
)
```

En `TareaRepository.kt`:

```kotlin
    fun findByEstadoAccionAndIdAsignadoIsNotNullAndFechaEjecucionIsNotNull(estadoAccion: EstadoAccion): List<Tarea>
```

En `TareaService.kt`:

```kotlin
    /** Para el job de recordatorios (notificaciones): tareas pendientes, asignadas, con fecha. */
    fun pendientesParaRecordatorio(): List<TareaRecordatorioProyeccion>
```

En `TareaServiceImpl.kt`:

```kotlin
    @Transactional(readOnly = true)
    override fun pendientesParaRecordatorio(): List<TareaRecordatorioProyeccion> =
        tareaRepository.findByEstadoAccionAndIdAsignadoIsNotNullAndFechaEjecucionIsNotNull(EstadoAccion.pendiente).map {
            TareaRecordatorioProyeccion(
                id = requireNotNull(it.id),
                idAsignado = requireNotNull(it.idAsignado),
                idEmpresa = it.idEmpresa,
                idOportunidad = it.idOportunidad,
                fechaEjecucion = requireNotNull(it.fechaEjecucion),
            )
        }
```

- [ ] **Step 4: Eventos — DTO, repositorio, interfaz, impl**

En `EventoDtos.kt`, agregar:

```kotlin
/** Proyeccion de solo lectura para el job de recordatorios (notificaciones). */
data class EventoRecordatorioProyeccion(
    val id: Long,
    val idOportunidad: Long?,
    val idEmpresa: Long?,
    val fechaEstimada: LocalDate,
)
```

En `EventoRepository.kt`:

```kotlin
    fun findByEstadoAndFechaEstimadaIsNotNull(estado: EstadoEvento): List<Evento>
```

En `EventoService.kt`:

```kotlin
    /** Para el job de recordatorios (notificaciones): eventos pendientes con fecha_estimada. */
    fun pendientesParaRecordatorio(): List<EventoRecordatorioProyeccion>
```

En `EventoServiceImpl.kt`:

```kotlin
    @Transactional(readOnly = true)
    override fun pendientesParaRecordatorio(): List<EventoRecordatorioProyeccion> =
        eventoRepository.findByEstadoAndFechaEstimadaIsNotNull(EstadoEvento.pendiente).map {
            EventoRecordatorioProyeccion(
                id = requireNotNull(it.id),
                idOportunidad = it.idOportunidad,
                idEmpresa = it.idEmpresa,
                fechaEstimada = requireNotNull(it.fechaEstimada),
            )
        }
```

- [ ] **Step 5: Oportunidades y Empresas — lookups sin chequeo de visibilidad (uso exclusivo del job de sistema)**

En `OportunidadDtos.kt`, agregar:

```kotlin
/** Para el job de recordatorios (notificaciones): sin chequeo de visibilidad — lo usa un job de sistema, no un usuario. */
data class OportunidadRecordatorioDatos(
    val idEmpresa: Long,
    val idVendedor: Long,
)
```

En `OportunidadService.kt`:

```kotlin
    /** Sin chequeo de visibilidad (job de sistema). Null si la oportunidad no existe. */
    fun datosRecordatorio(id: Long): OportunidadRecordatorioDatos?
```

En `OportunidadServiceImpl.kt`:

```kotlin
    @Transactional(readOnly = true)
    override fun datosRecordatorio(id: Long): OportunidadRecordatorioDatos? =
        oportunidadRepository.findById(id).map { OportunidadRecordatorioDatos(idEmpresa = it.idEmpresa, idVendedor = it.idVendedor) }.orElse(null)
```

En `EmpresaService.kt`:

```kotlin
    /** Sin chequeo de visibilidad (job de sistema). Null si la empresa no existe o no tiene vendedor. */
    fun vendedorAsignado(id: Long): Long?
```

En `EmpresaServiceImpl.kt`:

```kotlin
    @Transactional(readOnly = true)
    override fun vendedorAsignado(id: Long): Long? = empresaRepository.findById(id).map { it.idVendedor }.orElse(null)
```

- [ ] **Step 6: Ejecutar y confirmar que pasan**

Run: `./gradlew test`
Expected: PASS (toda la suite unitaria — confirma que no se rompió ningún módulo con las 4 interfaces ampliadas)

- [ ] **Step 7: Commit**

```bash
git add src/main/kotlin/pe/quantum/crm/domain/tareas/dto/TareaDtos.kt src/main/kotlin/pe/quantum/crm/domain/tareas/TareaRepository.kt src/main/kotlin/pe/quantum/crm/domain/tareas/TareaService.kt src/main/kotlin/pe/quantum/crm/domain/tareas/TareaServiceImpl.kt src/main/kotlin/pe/quantum/crm/domain/eventos/dto/EventoDtos.kt src/main/kotlin/pe/quantum/crm/domain/eventos/EventoRepository.kt src/main/kotlin/pe/quantum/crm/domain/eventos/EventoService.kt src/main/kotlin/pe/quantum/crm/domain/eventos/EventoServiceImpl.kt src/main/kotlin/pe/quantum/crm/domain/oportunidades/dto/OportunidadDtos.kt src/main/kotlin/pe/quantum/crm/domain/oportunidades/OportunidadService.kt src/main/kotlin/pe/quantum/crm/domain/oportunidades/OportunidadServiceImpl.kt src/main/kotlin/pe/quantum/crm/domain/empresas/EmpresaService.kt src/main/kotlin/pe/quantum/crm/domain/empresas/EmpresaServiceImpl.kt src/test/kotlin/pe/quantum/crm/domain/tareas/TareaServiceImplTest.kt src/test/kotlin/pe/quantum/crm/domain/eventos/EventoServiceImplTest.kt
git commit -m "feat(notificaciones): proyecciones y lookups de solo lectura para el job de recordatorios"
```

---

### Task 15: `RecordatorioJob` (recordatorios de tareas y eventos, hourly)

**Files:**
- Create: `src/main/kotlin/pe/quantum/crm/domain/notificaciones/jobs/RecordatorioJob.kt`
- Modify: `src/main/kotlin/pe/quantum/crm/CrmApplication.kt`
- Test: `src/test/kotlin/pe/quantum/crm/domain/notificaciones/jobs/RecordatorioJobTest.kt`

**Interfaces:**
- Consumes: `TareaService.pendientesParaRecordatorio()`, `EventoService.pendientesParaRecordatorio()`, `OportunidadService.datosRecordatorio()`, `EmpresaService.vendedorAsignado()`, `EmpresaService.resumenPorIds()`, `NotificacionService.notificar()`, `RecordatorioEnviadoRepository` (todas de Tasks 5, 14).

- [ ] **Step 1: Escribir los tests que fallan**

```kotlin
package pe.quantum.crm.domain.notificaciones.jobs

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import pe.quantum.crm.domain.empresas.EmpresaService
import pe.quantum.crm.domain.empresas.dto.EmpresaResumen
import pe.quantum.crm.domain.eventos.EventoService
import pe.quantum.crm.domain.eventos.dto.EventoRecordatorioProyeccion
import pe.quantum.crm.domain.notificaciones.EntidadNotificacion
import pe.quantum.crm.domain.notificaciones.NotificacionService
import pe.quantum.crm.domain.notificaciones.OrigenRecordatorio
import pe.quantum.crm.domain.notificaciones.RecordatorioEnviadoRepository
import pe.quantum.crm.domain.notificaciones.TipoNotificacion
import pe.quantum.crm.domain.notificaciones.UmbralRecordatorio
import pe.quantum.crm.domain.oportunidades.OportunidadService
import pe.quantum.crm.domain.oportunidades.dto.OportunidadRecordatorioDatos
import pe.quantum.crm.domain.tareas.TareaService
import pe.quantum.crm.domain.tareas.dto.TareaRecordatorioProyeccion
import java.time.LocalDate
import java.time.LocalDateTime

class RecordatorioJobTest {
    private val tareaService = mockk<TareaService>()
    private val eventoService = mockk<EventoService>()
    private val oportunidadService = mockk<OportunidadService>()
    private val empresaService = mockk<EmpresaService>()
    private val notificacionService = mockk<NotificacionService>(relaxed = true)
    private val recordatorioEnviadoRepository = mockk<RecordatorioEnviadoRepository>(relaxed = true)
    private val job =
        RecordatorioJob(tareaService, eventoService, oportunidadService, empresaService, notificacionService, recordatorioEnviadoRepository)

    @Test
    fun `tarea vencida (fecha ya paso) notifica umbral vencido una sola vez`() {
        every { tareaService.pendientesParaRecordatorio() } returns
            listOf(
                TareaRecordatorioProyeccion(
                    id = 1,
                    idAsignado = 3,
                    idEmpresa = 10,
                    idOportunidad = null,
                    fechaEjecucion = LocalDateTime.now().minusHours(2),
                ),
            )
        every { eventoService.pendientesParaRecordatorio() } returns emptyList()
        every { recordatorioEnviadoRepository.existsByOrigenAndIdOrigenAndUmbral(OrigenRecordatorio.tarea, 1, UmbralRecordatorio.vencido) } returns false
        every { empresaService.resumenPorIds(listOf(10)) } returns mapOf(10L to EmpresaResumen(id = 10, razonSocial = "Kincar S.A.C.", distrito = null))

        job.ejecutar()

        verify {
            notificacionService.notificar(
                destinatarios = setOf(3L),
                idActor = null,
                tipo = TipoNotificacion.tarea_recordatorio,
                mensaje = "Recordatorio: tienes una tarea vencida en Kincar S.A.C.",
                entidadTipo = EntidadNotificacion.empresa,
                entidadId = 10L,
            )
        }
    }

    @Test
    fun `tarea con recordatorio ya enviado para ese umbral no notifica de nuevo`() {
        every { tareaService.pendientesParaRecordatorio() } returns
            listOf(
                TareaRecordatorioProyeccion(id = 1, idAsignado = 3, idEmpresa = 10, idOportunidad = null, fechaEjecucion = LocalDateTime.now().minusHours(2)),
            )
        every { eventoService.pendientesParaRecordatorio() } returns emptyList()
        every { recordatorioEnviadoRepository.existsByOrigenAndIdOrigenAndUmbral(OrigenRecordatorio.tarea, 1, UmbralRecordatorio.vencido) } returns true

        job.ejecutar()

        verify(exactly = 0) { notificacionService.notificar(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `evento con fecha_estimada manana notifica umbral proximo via oportunidad`() {
        every { tareaService.pendientesParaRecordatorio() } returns emptyList()
        every { eventoService.pendientesParaRecordatorio() } returns
            listOf(EventoRecordatorioProyeccion(id = 4, idOportunidad = 50, idEmpresa = null, fechaEstimada = LocalDate.now().plusDays(1)))
        every { recordatorioEnviadoRepository.existsByOrigenAndIdOrigenAndUmbral(OrigenRecordatorio.evento, 4, UmbralRecordatorio.proximo) } returns false
        every { oportunidadService.datosRecordatorio(50) } returns OportunidadRecordatorioDatos(idEmpresa = 10, idVendedor = 3)
        every { empresaService.resumenPorIds(listOf(10)) } returns mapOf(10L to EmpresaResumen(id = 10, razonSocial = "Kincar S.A.C.", distrito = null))

        job.ejecutar()

        verify {
            notificacionService.notificar(
                destinatarios = setOf(3L),
                idActor = null,
                tipo = TipoNotificacion.evento_recordatorio,
                mensaje = "Recordatorio: hay un evento próximo a vencer en Kincar S.A.C.",
                entidadTipo = EntidadNotificacion.oportunidad,
                entidadId = 50L,
            )
        }
    }
}
```

- [ ] **Step 2: Ejecutar y confirmar que falla**

Run: `./gradlew test --tests "pe.quantum.crm.domain.notificaciones.jobs.RecordatorioJobTest"`
Expected: FAIL — `RecordatorioJob` no existe.

- [ ] **Step 3: Implementar el job**

```kotlin
package pe.quantum.crm.domain.notificaciones.jobs

import org.springframework.dao.DataIntegrityViolationException
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import pe.quantum.crm.domain.empresas.EmpresaService
import pe.quantum.crm.domain.eventos.EventoService
import pe.quantum.crm.domain.eventos.dto.EventoRecordatorioProyeccion
import pe.quantum.crm.domain.notificaciones.EntidadNotificacion
import pe.quantum.crm.domain.notificaciones.NotificacionService
import pe.quantum.crm.domain.notificaciones.OrigenRecordatorio
import pe.quantum.crm.domain.notificaciones.RecordatorioEnviado
import pe.quantum.crm.domain.notificaciones.RecordatorioEnviadoRepository
import pe.quantum.crm.domain.notificaciones.TipoNotificacion
import pe.quantum.crm.domain.notificaciones.UmbralRecordatorio
import pe.quantum.crm.domain.oportunidades.OportunidadService
import pe.quantum.crm.domain.tareas.TareaService
import pe.quantum.crm.domain.tareas.dto.TareaRecordatorioProyeccion
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * Recordatorios de tareas/eventos por vencer o vencidos (docs/superpowers/specs/2026-07-09-notificaciones-in-app-design.md).
 * Corre cada hora; cada (origen, id, umbral) notifica como maximo una vez,
 * registrado en `recordatorios_enviados`.
 */
@Component
@Suppress("LongParameterList") // Cruza 4 modulos de dominio + notificaciones.
class RecordatorioJob(
    private val tareaService: TareaService,
    private val eventoService: EventoService,
    private val oportunidadService: OportunidadService,
    private val empresaService: EmpresaService,
    private val notificacionService: NotificacionService,
    private val recordatorioEnviadoRepository: RecordatorioEnviadoRepository,
) {
    @Scheduled(cron = "0 0 * * * *")
    @Transactional
    fun ejecutar() {
        procesarTareas()
        procesarEventos()
    }

    private fun procesarTareas() {
        val ahora = LocalDateTime.now()
        tareaService.pendientesParaRecordatorio().forEach { tarea ->
            val umbral = umbralTarea(tarea.fechaEjecucion, ahora) ?: return@forEach
            procesarRecordatorio(
                origen = OrigenRecordatorio.tarea,
                idOrigen = tarea.id,
                umbral = umbral,
                idEmpresaNombre = tarea.idEmpresa,
                destinatario = tarea.idAsignado,
                entidadTipo = if (tarea.idOportunidad != null) EntidadNotificacion.oportunidad else EntidadNotificacion.empresa,
                entidadId = tarea.idOportunidad ?: tarea.idEmpresa,
                tipo = TipoNotificacion.tarea_recordatorio,
                mensaje = { razonSocial -> mensajeTarea(umbral, razonSocial) },
            )
        }
    }

    private fun procesarEventos() {
        val hoy = LocalDate.now()
        eventoService.pendientesParaRecordatorio().forEach { evento ->
            val umbral = umbralEvento(evento.fechaEstimada, hoy) ?: return@forEach
            val destino = destinoDe(evento) ?: return@forEach
            procesarRecordatorio(
                origen = OrigenRecordatorio.evento,
                idOrigen = evento.id,
                umbral = umbral,
                idEmpresaNombre = destino.idEmpresa,
                destinatario = destino.idVendedor,
                entidadTipo = destino.entidadTipo,
                entidadId = destino.entidadId,
                tipo = TipoNotificacion.evento_recordatorio,
                mensaje = { razonSocial -> mensajeEvento(umbral, razonSocial) },
            )
        }
    }

    @Suppress("LongParameterList")
    private fun procesarRecordatorio(
        origen: OrigenRecordatorio,
        idOrigen: Long,
        umbral: UmbralRecordatorio,
        idEmpresaNombre: Long,
        destinatario: Long,
        entidadTipo: EntidadNotificacion,
        entidadId: Long,
        tipo: TipoNotificacion,
        mensaje: (String) -> String,
    ) {
        if (recordatorioEnviadoRepository.existsByOrigenAndIdOrigenAndUmbral(origen, idOrigen, umbral)) {
            return
        }
        val empresa = empresaService.resumenPorIds(listOf(idEmpresaNombre))[idEmpresaNombre] ?: return
        notificacionService.notificar(
            destinatarios = setOf(destinatario),
            idActor = null,
            tipo = tipo,
            mensaje = mensaje(empresa.razonSocial),
            entidadTipo = entidadTipo,
            entidadId = entidadId,
        )
        registrarEnviado(origen, idOrigen, umbral)
    }

    @Suppress("SwallowedException") // Carrera entre 2 corridas del job; uq_recordatorio ya lo cubrio.
    private fun registrarEnviado(
        origen: OrigenRecordatorio,
        idOrigen: Long,
        umbral: UmbralRecordatorio,
    ) {
        try {
            recordatorioEnviadoRepository.save(RecordatorioEnviado(origen = origen, idOrigen = idOrigen, umbral = umbral))
        } catch (ex: DataIntegrityViolationException) {
            // uq_recordatorio: otra corrida del job ya registro este mismo umbral.
        }
    }

    private data class Destino(
        val entidadTipo: EntidadNotificacion,
        val entidadId: Long,
        val idEmpresa: Long,
        val idVendedor: Long,
    )

    private fun destinoDe(evento: EventoRecordatorioProyeccion): Destino? {
        val idOportunidad = evento.idOportunidad
        if (idOportunidad != null) {
            val datos = oportunidadService.datosRecordatorio(idOportunidad) ?: return null
            return Destino(EntidadNotificacion.oportunidad, idOportunidad, datos.idEmpresa, datos.idVendedor)
        }
        val idEmpresa = evento.idEmpresa ?: return null
        val idVendedor = empresaService.vendedorAsignado(idEmpresa) ?: return null
        return Destino(EntidadNotificacion.empresa, idEmpresa, idEmpresa, idVendedor)
    }

    private fun umbralTarea(
        fechaEjecucion: LocalDateTime,
        ahora: LocalDateTime,
    ): UmbralRecordatorio? =
        when {
            fechaEjecucion.isBefore(ahora) -> UmbralRecordatorio.vencido
            !fechaEjecucion.isAfter(ahora.plusHours(HORAS_PROXIMO)) -> UmbralRecordatorio.proximo
            else -> null
        }

    private fun umbralEvento(
        fechaEstimada: LocalDate,
        hoy: LocalDate,
    ): UmbralRecordatorio? =
        when {
            fechaEstimada.isBefore(hoy) -> UmbralRecordatorio.vencido
            fechaEstimada == hoy.plusDays(1) -> UmbralRecordatorio.proximo
            else -> null
        }

    private fun mensajeTarea(
        umbral: UmbralRecordatorio,
        razonSocial: String,
    ): String =
        when (umbral) {
            UmbralRecordatorio.proximo -> "Recordatorio: tienes una tarea próxima a vencer en $razonSocial"
            UmbralRecordatorio.vencido -> "Recordatorio: tienes una tarea vencida en $razonSocial"
        }

    private fun mensajeEvento(
        umbral: UmbralRecordatorio,
        razonSocial: String,
    ): String =
        when (umbral) {
            UmbralRecordatorio.proximo -> "Recordatorio: hay un evento próximo a vencer en $razonSocial"
            UmbralRecordatorio.vencido -> "Recordatorio: hay un evento vencido sin registrar en $razonSocial"
        }

    private companion object {
        const val HORAS_PROXIMO = 24L
    }
}
```

- [ ] **Step 4: Habilitar `@Scheduled` en la aplicación**

```kotlin
package pe.quantum.crm

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication
import org.springframework.scheduling.annotation.EnableScheduling

@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
class CrmApplication

fun main(args: Array<String>) {
    runApplication<CrmApplication>(*args)
}
```

- [ ] **Step 5: Ejecutar y confirmar que pasa**

Run: `./gradlew test --tests "pe.quantum.crm.domain.notificaciones.jobs.RecordatorioJobTest"`
Expected: PASS (3 tests)

- [ ] **Step 6: Correr toda la suite unitaria**

Run: `./gradlew test`
Expected: PASS

- [ ] **Step 7: Commit**

```bash
git add src/main/kotlin/pe/quantum/crm/domain/notificaciones/jobs/RecordatorioJob.kt src/main/kotlin/pe/quantum/crm/CrmApplication.kt src/test/kotlin/pe/quantum/crm/domain/notificaciones/jobs/RecordatorioJobTest.kt
git commit -m "feat(notificaciones): job de recordatorios de tareas y eventos (hourly)"
```

---

### Task 16: `LimpiezaNotificacionesJob` (purga diaria)

**Files:**
- Create: `src/main/kotlin/pe/quantum/crm/domain/notificaciones/jobs/LimpiezaNotificacionesJob.kt`
- Test: `src/test/kotlin/pe/quantum/crm/domain/notificaciones/jobs/LimpiezaNotificacionesJobTest.kt`

**Interfaces:**
- Consumes: `NotificacionRepository.purgarLeidasAntesDe(...)` (Task 2).

- [ ] **Step 1: Escribir el test que falla**

```kotlin
package pe.quantum.crm.domain.notificaciones.jobs

import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import pe.quantum.crm.domain.notificaciones.NotificacionRepository
import java.time.Duration
import java.time.LocalDateTime

class LimpiezaNotificacionesJobTest {
    private val notificacionRepository = mockk<NotificacionRepository>()
    private val job = LimpiezaNotificacionesJob(notificacionRepository)

    @Test
    fun `purga notificaciones leidas con mas de 30 dias`() {
        val slot = slot<LocalDateTime>()
        every { notificacionRepository.purgarLeidasAntesDe(capture(slot)) } returns 3

        job.ejecutar()

        verify { notificacionRepository.purgarLeidasAntesDe(any()) }
        val diferencia = Duration.between(slot.captured, LocalDateTime.now().minusDays(30))
        assertThat(diferencia.abs()).isLessThan(Duration.ofMinutes(1))
    }
}
```

- [ ] **Step 2: Ejecutar y confirmar que falla**

Run: `./gradlew test --tests "pe.quantum.crm.domain.notificaciones.jobs.LimpiezaNotificacionesJobTest"`
Expected: FAIL — `LimpiezaNotificacionesJob` no existe.

- [ ] **Step 3: Implementar el job**

```kotlin
package pe.quantum.crm.domain.notificaciones.jobs

import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import pe.quantum.crm.domain.notificaciones.NotificacionRepository
import java.time.LocalDateTime

/** Purga diaria de notificaciones leidas con mas de 30 dias (docs/superpowers/specs/2026-07-09-notificaciones-in-app-design.md). */
@Component
class LimpiezaNotificacionesJob(
    private val notificacionRepository: NotificacionRepository,
) {
    @Scheduled(cron = "0 0 3 * * *")
    @Transactional
    fun ejecutar() {
        notificacionRepository.purgarLeidasAntesDe(LocalDateTime.now().minusDays(DIAS_RETENCION))
    }

    private companion object {
        const val DIAS_RETENCION = 30L
    }
}
```

- [ ] **Step 4: Ejecutar y confirmar que pasa**

Run: `./gradlew test --tests "pe.quantum.crm.domain.notificaciones.jobs.LimpiezaNotificacionesJobTest"`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/pe/quantum/crm/domain/notificaciones/jobs/LimpiezaNotificacionesJob.kt src/test/kotlin/pe/quantum/crm/domain/notificaciones/jobs/LimpiezaNotificacionesJobTest.kt
git commit -m "feat(notificaciones): job de limpieza diaria de notificaciones leidas (>30 dias)"
```

---

### Task 17: Documentar `contrato_api.md` §19 — Notificaciones

**Files:**
- Modify: `docs/contrato_api.md`

**Interfaces:**
- No produce código; documenta los 4 endpoints ya implementados (Tasks 5-6).

- [ ] **Step 1: Agregar la entrada al índice**

En la sección `## Índice`, después de `18. [Reportes](#18-reportes)`:

```markdown
19. [Notificaciones](#19-notificaciones)
```

- [ ] **Step 2: Agregar la sección al final (antes del Apéndice)**

Insertar antes de `## Apéndice — Endpoints no implementados en MVP`:

```markdown
## 19. Notificaciones

Notifica a un usuario cuando ocurre una acción relacionada con él pero no accionada por él mismo. También cubre recordatorios de tareas y eventos (job programado, sin actor humano).

**Tipo (`tipo`):** `oportunidad_cambio_estado`, `empresa_convertida`, `evento_creado`, `tarea_creada`, `empresa_asignada`, `oportunidad_traspasada`, `tarea_recordatorio`, `evento_recordatorio`.

**Entidad referenciada (`entidad_tipo`):** `oportunidad` | `empresa` — nunca una tarea/evento suelto; para tareas/eventos se referencia su oportunidad si tiene una, si no su empresa.

**DTO `Notificacion`:**
```json
{
  "id": 1,
  "tipo": "oportunidad_cambio_estado",
  "mensaje": "Carlos Pérez cambió el estado de Transportes ABC a Documentos legales",
  "entidad_tipo": "oportunidad",
  "entidad_id": 101,
  "leida": false,
  "created_at": "2026-07-09T14:30:00Z",
  "actor": { "id": 5, "nombres": "Carlos", "apellidos": "Pérez" }
}
```
`actor` es `null` para recordatorios generados por el sistema (job programado, sin actor humano).

---

### GET /notificaciones/no-leidas/count
> Cuenta las notificaciones no leídas del usuario autenticado.

**Roles:** todos

**Respuesta 200:**
```json
{ "data": { "count": 5 }, "meta": null, "error": null }
```

---

### GET /notificaciones
> Últimas 20 notificaciones (leídas + no leídas) del usuario autenticado, más recientes primero. Sin paginación.

**Roles:** todos

**Respuesta 200:** `{ "data": [ /* NotificacionDto[] */ ], "meta": null, "error": null }`

---

### PATCH /notificaciones/:id/leida
> Marca una notificación propia como leída.

**Roles:** todos (solo notificaciones propias)

**Respuesta 200:** `{ "data": { "leida": true } }`

**Errores:**

| Código | HTTP | Cuándo |
|---|---|---|
| `NO_ENCONTRADO` | 404 | La notificación no existe o no pertenece al usuario autenticado |

---

### PATCH /notificaciones/leidas
> Marca todas las notificaciones no leídas del usuario autenticado como leídas.

**Roles:** todos

**Respuesta 200:** `{ "data": { "leida": true } }`

---
```

- [ ] **Step 3: Verificar manualmente que los anclas de markdown coinciden**

Confirmar que el enlace `#19-notificaciones` del índice coincide con el header generado por GitHub/el visor de markdown para `## 19. Notificaciones` (mismo patrón que las demás entradas del índice, p. ej. `#18-reportes` para `## 18. Reportes`).

- [ ] **Step 4: Commit**

```bash
git add docs/contrato_api.md
git commit -m "docs(contrato): agregar seccion 19 - Notificaciones"
```

---

### Task 18: Verificación final de la suite completa

**Files:** ninguno (solo verificación).

**Interfaces:** N/A.

- [ ] **Step 1: Correr la suite unitaria completa**

Run: `./gradlew test`
Expected: BUILD SUCCESSFUL, 0 fallos.

- [ ] **Step 2: Correr ktlint**

Run: `./gradlew ktlintCheck`
Expected: BUILD SUCCESSFUL. Si falla, correr `./gradlew ktlintFormat` y revisar el diff antes de continuar.

- [ ] **Step 3: Correr detekt**

Run: `./gradlew detekt`
Expected: BUILD SUCCESSFUL. Prestar atención a `LongParameterList` en `RecordatorioJob`, `EventoServiceImpl`, `OportunidadServiceImpl` — ya llevan `@Suppress` puestos en los tasks anteriores; si detekt igual se queja de un umbral distinto, ajustar el suppress correspondiente (no bajar la calidad del código para acallarlo).

- [ ] **Step 4: Correr kover (cobertura)**

Run: `./gradlew koverVerify`
Expected: BUILD SUCCESSFUL (75% global, 90% en `pe.quantum.crm.domain`). Si el módulo `notificaciones` queda bajo, agregar los casos de borde que falten (branch de `idActor == null`, branch de destinatarios vacíos, etc. — ya cubiertos en el Task 5, pero revisar el reporte HTML en `build/reports/kover/`).

- [ ] **Step 5: Confirmar que los tests de integración compilan (aunque no corran localmente)**

Run: `./gradlew compileIntegrationTestKotlin` (o el nombre real de la tarea de compilación de test; si no existe como tarea independiente, usar `./gradlew compileTestKotlin` que ya cubre el mismo source set)
Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Reportar el estado final**

No hay commit en este task — es solo verificación. Si todo pasa, el feature está listo para `./gradlew integrationTest` en CI y para revisión.

---

## Notas para quien ejecute el plan

- **No existen tests unitarios previos** de `EmpresaServiceImpl`, `OportunidadServiceImpl` ni `TareaServiceImpl` — se crean desde cero en este plan (Tasks 7, 9, 13). Sí existe `EventoServiceImplTest.kt`, que se modifica con cuidado (mocks relajados nuevos, sin tocar las aserciones existentes).
- Los tests de integración (`@Tag("integration")`) no corren en este entorno local (Docker Desktop 29 rompe Testcontainers — ver `IntegrationTestBase`). Se escriben igual porque corren en CI; no bloquean el flujo TDD local, que se apoya en `./gradlew test` (unitarios).
- Este proyecto no usa `@WebMvcTest` en ningún lado; los tests de controller son `@SpringBootTest` de contexto completo con `SinBaseDeDatosMocks` (ver Task 6, que ya sigue exactamente el patrón de `AuthControllerWebMvcTest.kt`/`EmpleadoMeControllerTest.kt`). Cualquier repositorio JPA nuevo que se agregue en el futuro necesita su propio mock en `SinBaseDeDatosMocks`, o rompe `CrmApplicationTests`/`SecurityHeadersAndCorsTest`/`AuthControllerWebMvcTest`/`EmpleadoMeControllerTest`/`NotificacionControllerWebMvcTest` a la vez (ya cubierto para los 2 repos nuevos en el Task 6, Steps 1-3).
