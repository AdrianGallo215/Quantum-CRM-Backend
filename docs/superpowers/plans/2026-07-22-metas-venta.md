# Metas de Venta (unidades) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Añadir metas de venta en unidades (mensual + anual) por vendedor/JDV, con flujo de propuesta (JDV) → aprobación/rechazo/edición directa (Gerencia/admin), cómputo de cumplimiento en vivo a partir de oportunidades facturadas, y su visualización en el panel de Inicio.

**Architecture:** Módulo nuevo `domain/metasventa` (entidad, repositorio, servicio, controller) siguiendo exactamente el patrón ya establecido por `domain/solicitudes` (propuesta → resolución por rol). El cumplimiento se calcula con una suma en vivo sobre `oportunidades` (nueva columna `facturado_en`), sin contador que mantener. `domain/inicio` consume `MetaVentaService` (nunca la entidad/repositorio de `metasventa` directamente, regla CLAUDE.md §12) y extiende `InicioDao` (que ya lee `oportunidades` directo, patrón preexistente) para el agregado de unidades facturadas.

**Tech Stack:** Kotlin 1.9 · Spring Boot 3.2 · Spring Data JPA · Flyway · PostgreSQL 16 · MockK + JUnit5 + AssertJ para tests unitarios/WebMvc.

## Global Constraints

- `monto_total`-style regla: `meta_anual` es SOLO LECTURA, calculado por el backend como la suma de los 12 meses; nunca se acepta como input (CLAUDE.md regla #2, mismo patrón que `oportunidades.monto_total`).
- Inyección por constructor siempre, nunca `@Autowired` en campos (CLAUDE.md regla #8).
- Relaciones/consultas JPA vía repositorio; nunca exponer entidades en controllers, siempre DTOs (regla #9).
- `@Transactional(readOnly = true)` en lecturas, `@Transactional` en escrituras cubriendo toda la operación (regla #10).
- Un módulo nunca accede a tablas/entidades de otro módulo, solo a su interfaz de servicio pública (regla #12) — `domain/inicio` debe consumir `MetaVentaService`, no `MetaVentaRepository`/`MetaVenta` directamente.
- IDOR: recurso ajeno → 404 `NO_ENCONTRADO`, nunca 403 (regla #14).
- **TDD relajado para esta feature** (pedido explícito del usuario, MVP): cada tarea escribe implementación + test en el mismo paso (no ciclo estricto rojo→verde por cada micro-función), pero **ninguna tarea termina sin que su test corra y pase**.
- Los tests con `@Tag("integration")` (repositorio, migraciones) requieren Testcontainers/Docker y **no corren en este entorno local** (ver memoria `testcontainers-docker29-blocker`); se verifican con `./gradlew compileTestKotlin` (compilan) y quedan para CI. `./gradlew test` excluye `integration` por configuración de `build.gradle.kts:93-94` y es el comando de verificación local real.
- No tocar drift preexistente no relacionado (p. ej. la lista de tablas/enums de `SchemaMigrationIntegrationTest` ya le faltan `solicitudes` y otros — no es de esta feature, no se corrige aquí; solo se añade lo propio, mismo criterio que V31).

---

### Task 1: Migraciones y enums base

**Files:**
- Create: `src/main/resources/db/migration/V32__create_metas_venta.sql`
- Create: `src/main/resources/db/migration/V33__oportunidades_facturado_en.sql`
- Create: `src/main/resources/db/migration/V34__notificaciones_metas_venta.sql`
- Create: `src/main/kotlin/pe/quantum/crm/shared/enums/MetaVentaEnums.kt`
- Modify: `src/main/kotlin/pe/quantum/crm/domain/notificaciones/NotificacionEnums.kt`
- Modify: `src/test/kotlin/pe/quantum/crm/support/SeedFixtures.kt:27`
- Modify: `src/test/kotlin/pe/quantum/crm/db/SchemaMigrationIntegrationTest.kt`

**Interfaces:**
- Produces: tabla `metas_venta` (columnas: `id, id_empleado, anio, meta_enero..meta_diciembre, meta_anual, estado, id_propuesto_por, id_resolutor, motivo_rechazo, resolved_at, created_at, updated_at`), tipo enum Postgres `estado_meta_enum` (`propuesta|aprobada|rechazada`), columna `oportunidades.facturado_en TIMESTAMP NULL`, enum Kotlin `pe.quantum.crm.shared.enums.EstadoMeta`, valores nuevos de `TipoNotificacion` (`meta_propuesta`, `meta_aprobada`, `meta_rechazada`, `meta_modificada`) y `EntidadNotificacion.meta_venta`.

- [ ] **Step 1: Crear la migración de la tabla `metas_venta`**

```sql
-- =============================================================================
-- V32 — Metas de venta (unidades), una fila por (empleado, año) con los 12
-- meses + el total anual. El ciclo de aprobación (propuesta/aprobada/rechazada)
-- aplica al año completo: el JDV propone el año entero de una sola vez, no mes
-- a mes. Ver docs/superpowers/specs/2026-07-22-metas-venta-design.md.
-- =============================================================================

CREATE TYPE estado_meta_enum AS ENUM ('propuesta', 'aprobada', 'rechazada');

CREATE TABLE metas_venta (
    id                  BIGSERIAL           PRIMARY KEY,
    id_empleado         BIGINT              NOT NULL REFERENCES empleados(id),
    anio                INT                 NOT NULL,
    meta_enero          INT                 NOT NULL CHECK (meta_enero > 0),
    meta_febrero        INT                 NOT NULL CHECK (meta_febrero > 0),
    meta_marzo          INT                 NOT NULL CHECK (meta_marzo > 0),
    meta_abril          INT                 NOT NULL CHECK (meta_abril > 0),
    meta_mayo           INT                 NOT NULL CHECK (meta_mayo > 0),
    meta_junio          INT                 NOT NULL CHECK (meta_junio > 0),
    meta_julio          INT                 NOT NULL CHECK (meta_julio > 0),
    meta_agosto         INT                 NOT NULL CHECK (meta_agosto > 0),
    meta_septiembre     INT                 NOT NULL CHECK (meta_septiembre > 0),
    meta_octubre        INT                 NOT NULL CHECK (meta_octubre > 0),
    meta_noviembre      INT                 NOT NULL CHECK (meta_noviembre > 0),
    meta_diciembre      INT                 NOT NULL CHECK (meta_diciembre > 0),
    meta_anual          INT                 NOT NULL CHECK (meta_anual > 0),
    estado              estado_meta_enum    NOT NULL DEFAULT 'propuesta',
    id_propuesto_por    BIGINT              NOT NULL REFERENCES empleados(id),
    id_resolutor        BIGINT              REFERENCES empleados(id),
    motivo_rechazo      TEXT,
    resolved_at         TIMESTAMP,
    created_at          TIMESTAMP           NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at          TIMESTAMP           NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT uq_meta_venta_empleado_anio UNIQUE (id_empleado, anio),

    CONSTRAINT chk_meta_venta_resolucion CHECK (
        (estado = 'propuesta' AND id_resolutor IS NULL AND resolved_at IS NULL AND motivo_rechazo IS NULL)
        OR
        (estado = 'aprobada' AND id_resolutor IS NOT NULL AND resolved_at IS NOT NULL AND motivo_rechazo IS NULL)
        OR
        (estado = 'rechazada' AND id_resolutor IS NOT NULL AND resolved_at IS NOT NULL AND motivo_rechazo IS NOT NULL)
    )
);

CREATE INDEX idx_metas_venta_empleado ON metas_venta(id_empleado, anio);
CREATE INDEX idx_metas_venta_estado ON metas_venta(estado);

COMMENT ON TABLE  metas_venta            IS 'Meta de unidades vendidas por empleado (vendedor/jdv) y año; 12 meses + total anual calculado.';
COMMENT ON COLUMN metas_venta.meta_anual IS 'SOLO LECTURA. Calculado por backend: suma de meta_enero..meta_diciembre.';
```

- [ ] **Step 2: Crear la migración de `oportunidades.facturado_en`**

```sql
-- =============================================================================
-- V33 — oportunidades.facturado_en: marca cuándo una oportunidad entró en
-- estado 'facturado'. Se limpia a NULL cuando sale de 'facturado' (retrocede o
-- se cierra). Fuente del cómputo de cumplimiento de metas de venta: una suma en
-- vivo sobre esta columna, sin contador aparte que pueda desincronizarse.
-- =============================================================================

ALTER TABLE oportunidades ADD COLUMN facturado_en TIMESTAMP NULL;

CREATE INDEX idx_oportunidades_facturado_en ON oportunidades(id_vendedor, facturado_en) WHERE estado = 'facturado';

-- Backfill: oportunidades ya facturadas toman el changed_at de su transición
-- más reciente a 'facturado' en el log de estados, para no perder ventas
-- históricas del cómputo de cumplimiento.
UPDATE oportunidades o
SET facturado_en = (
    SELECT MAX(l.changed_at)
    FROM oportunidad_estados_log l
    WHERE l.id_oportunidad = o.id AND l.estado_nuevo = 'facturado'
)
WHERE o.estado = 'facturado';

COMMENT ON COLUMN oportunidades.facturado_en IS 'Momento en que la oportunidad entró en estado facturado. NULL si nunca facturó o si salió de facturado. Fuente del cómputo de metas de venta.';
```

- [ ] **Step 3: Crear la migración de notificaciones**

```sql
-- =============================================================================
-- V34 — Notificaciones del sistema de metas de venta.
-- =============================================================================

ALTER TYPE tipo_notificacion_enum ADD VALUE 'meta_propuesta';
ALTER TYPE tipo_notificacion_enum ADD VALUE 'meta_aprobada';
ALTER TYPE tipo_notificacion_enum ADD VALUE 'meta_rechazada';
ALTER TYPE tipo_notificacion_enum ADD VALUE 'meta_modificada';

ALTER TYPE entidad_notificacion_enum ADD VALUE 'meta_venta';
```

- [ ] **Step 4: Crear el enum Kotlin `EstadoMeta`**

```kotlin
package pe.quantum.crm.shared.enums

/**
 * Enum del sistema de metas de venta (migracion V32). En minuscula para
 * coincidir con las etiquetas del enum nativo `estado_meta_enum` de PostgreSQL.
 */
@Suppress("ktlint:standard:enum-entry-name-case", "EnumNaming", "EnumEntryName")
enum class EstadoMeta {
    propuesta,
    aprobada,
    rechazada,
}
```

- [ ] **Step 5: Añadir los valores nuevos a `NotificacionEnums.kt`**

En `src/main/kotlin/pe/quantum/crm/domain/notificaciones/NotificacionEnums.kt`, añade al final de `enum class TipoNotificacion`:

```kotlin
    solicitud_denegada,
    meta_propuesta,
    meta_aprobada,
    meta_rechazada,
    meta_modificada,
}
```

Y al final de `enum class EntidadNotificacion`:

```kotlin
    solicitud,
    meta_venta,
}
```

- [ ] **Step 6: Actualizar `SeedFixtures.MIGRACIONES_TOTAL`**

En `src/test/kotlin/pe/quantum/crm/support/SeedFixtures.kt:27`, cambia:

```kotlin
    const val MIGRACIONES_TOTAL = 31
```

por:

```kotlin
    const val MIGRACIONES_TOTAL = 34
```

- [ ] **Step 7: Añadir la tabla y el enum nuevos a `SchemaMigrationIntegrationTest`**

En `src/test/kotlin/pe/quantum/crm/db/SchemaMigrationIntegrationTest.kt`, dentro de `containsExactlyInAnyOrder` de la primera lista (tablas), añade `"metas_venta",` después de `"recordatorios_enviados",`. Dentro de la lista de enums, añade `"estado_meta_enum",` después de `"umbral_recordatorio_enum",`. No toques el resto de la lista (ya tenía drift preexistente con `solicitudes` — fuera de alcance, ver memoria del proyecto).

- [ ] **Step 8: Verificar que compila**

Run: `./gradlew compileKotlin compileTestKotlin`
Expected: BUILD SUCCESSFUL. (Las migraciones y `SchemaMigrationIntegrationTest` solo se ejercitan de verdad contra Postgres en CI — @Tag("integration"), no en local.)

- [ ] **Step 9: Commit**

```bash
git add src/main/resources/db/migration/V32__create_metas_venta.sql \
        src/main/resources/db/migration/V33__oportunidades_facturado_en.sql \
        src/main/resources/db/migration/V34__notificaciones_metas_venta.sql \
        src/main/kotlin/pe/quantum/crm/shared/enums/MetaVentaEnums.kt \
        src/main/kotlin/pe/quantum/crm/domain/notificaciones/NotificacionEnums.kt \
        src/test/kotlin/pe/quantum/crm/support/SeedFixtures.kt \
        src/test/kotlin/pe/quantum/crm/db/SchemaMigrationIntegrationTest.kt
git commit -m "feat(db): tabla metas_venta, oportunidades.facturado_en y notificaciones de metas"
```

---

### Task 2: Entidad `MetaVenta` y repositorio

**Files:**
- Create: `src/main/kotlin/pe/quantum/crm/domain/metasventa/MetaVenta.kt`
- Create: `src/main/kotlin/pe/quantum/crm/domain/metasventa/MetaVentaRepository.kt`
- Create: `src/test/kotlin/pe/quantum/crm/domain/metasventa/MetaVentaRepositoryTest.kt`

**Interfaces:**
- Consumes: `EstadoMeta` (Task 1).
- Produces: `class MetaVenta` con propiedades `id: Long?`, `idEmpleado: Long`, `anio: Int`, `metaEnero..metaDiciembre: Int` (var), `metaAnual: Int` (var), `estado: EstadoMeta` (var), `idPropuestoPor: Long` (var), `idResolutor: Long?` (var), `motivoRechazo: String?` (var), `resolvedAt: LocalDateTime?` (var), `createdAt/updatedAt: LocalDateTime`; funciones `fun meses(): List<Int>`, `fun valorMes(mes: Int): Int`, `fun establecerMeses(valores: List<Int>)`. Interfaz `MetaVentaRepository : JpaRepository<MetaVenta, Long>, JpaSpecificationExecutor<MetaVenta>` con `findByIdEmpleadoAndAnio(idEmpleado: Long, anio: Int): MetaVenta?`, `findByIdEmpleadoInAndAnioAndEstado(idsEmpleado: Collection<Long>, anio: Int, estado: EstadoMeta): List<MetaVenta>`, y `findByIdForUpdate(id: Long): MetaVenta?` con lock pesimista.

- [ ] **Step 1: Crear la entidad `MetaVenta`**

```kotlin
package pe.quantum.crm.domain.metasventa

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import pe.quantum.crm.shared.enums.EstadoMeta
import java.time.LocalDateTime

/**
 * Meta de venta en unidades (tabla `metas_venta`, migracion V32). Una fila por
 * `(id_empleado, anio)`: los 12 meses + el total anual, con un unico ciclo de
 * aprobacion para el año completo (el JDV propone los 12 meses de una vez).
 */
@Entity
@Table(name = "metas_venta")
@Suppress("LongParameterList") // Una entidad JPA refleja las columnas de su tabla.
class MetaVenta(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,
    @Column(name = "id_empleado", nullable = false)
    val idEmpleado: Long,
    @Column(nullable = false)
    val anio: Int,
    @Column(name = "meta_enero", nullable = false) var metaEnero: Int = 0,
    @Column(name = "meta_febrero", nullable = false) var metaFebrero: Int = 0,
    @Column(name = "meta_marzo", nullable = false) var metaMarzo: Int = 0,
    @Column(name = "meta_abril", nullable = false) var metaAbril: Int = 0,
    @Column(name = "meta_mayo", nullable = false) var metaMayo: Int = 0,
    @Column(name = "meta_junio", nullable = false) var metaJunio: Int = 0,
    @Column(name = "meta_julio", nullable = false) var metaJulio: Int = 0,
    @Column(name = "meta_agosto", nullable = false) var metaAgosto: Int = 0,
    @Column(name = "meta_septiembre", nullable = false) var metaSeptiembre: Int = 0,
    @Column(name = "meta_octubre", nullable = false) var metaOctubre: Int = 0,
    @Column(name = "meta_noviembre", nullable = false) var metaNoviembre: Int = 0,
    @Column(name = "meta_diciembre", nullable = false) var metaDiciembre: Int = 0,
    @Column(name = "meta_anual", nullable = false) var metaAnual: Int = 0,
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(nullable = false, columnDefinition = "estado_meta_enum")
    var estado: EstadoMeta = EstadoMeta.propuesta,
    @Column(name = "id_propuesto_por", nullable = false)
    var idPropuestoPor: Long,
    @Column(name = "id_resolutor")
    var idResolutor: Long? = null,
    @Column(name = "motivo_rechazo")
    var motivoRechazo: String? = null,
    @Column(name = "resolved_at")
    var resolvedAt: LocalDateTime? = null,
    @Column(name = "created_at", nullable = false)
    val createdAt: LocalDateTime = LocalDateTime.now(),
    @Column(name = "updated_at", nullable = false)
    var updatedAt: LocalDateTime = LocalDateTime.now(),
) {
    /** Los 12 meses en orden calendario (enero..diciembre). */
    fun meses(): List<Int> =
        listOf(
            metaEnero, metaFebrero, metaMarzo, metaAbril, metaMayo, metaJunio,
            metaJulio, metaAgosto, metaSeptiembre, metaOctubre, metaNoviembre, metaDiciembre,
        )

    /** Valor del mes (1=enero..12=diciembre). */
    fun valorMes(mes: Int): Int {
        require(mes in 1..12) { "Mes inválido: $mes" }
        return meses()[mes - 1]
    }

    /** Reemplaza los 12 meses y recalcula `metaAnual` (SOLO LECTURA, igual que `monto_total`). */
    fun establecerMeses(valores: List<Int>) {
        require(valores.size == 12) { "Se requieren 12 valores mensuales, se recibieron ${valores.size}" }
        metaEnero = valores[0]
        metaFebrero = valores[1]
        metaMarzo = valores[2]
        metaAbril = valores[3]
        metaMayo = valores[4]
        metaJunio = valores[5]
        metaJulio = valores[6]
        metaAgosto = valores[7]
        metaSeptiembre = valores[8]
        metaOctubre = valores[9]
        metaNoviembre = valores[10]
        metaDiciembre = valores[11]
        metaAnual = valores.sum()
    }
}
```

- [ ] **Step 2: Crear el repositorio**

```kotlin
package pe.quantum.crm.domain.metasventa

import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.JpaSpecificationExecutor
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import pe.quantum.crm.shared.enums.EstadoMeta

interface MetaVentaRepository :
    JpaRepository<MetaVenta, Long>,
    JpaSpecificationExecutor<MetaVenta> {
    fun findByIdEmpleadoAndAnio(
        idEmpleado: Long,
        anio: Int,
    ): MetaVenta?

    fun findByIdEmpleadoInAndAnioAndEstado(
        idsEmpleado: Collection<Long>,
        anio: Int,
        estado: EstadoMeta,
    ): List<MetaVenta>

    /** SELECT ... FOR UPDATE: dos resolutores en paralelo no resuelven dos veces. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select m from MetaVenta m where m.id = :id")
    fun findByIdForUpdate(id: Long): MetaVenta?
}
```

- [ ] **Step 3: Test de repositorio (integración, corre en CI)**

```kotlin
package pe.quantum.crm.domain.metasventa

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.dao.DataIntegrityViolationException
import pe.quantum.crm.shared.enums.EstadoMeta
import pe.quantum.crm.support.IntegrationTestBase

@Tag("integration")
@SpringBootTest
class MetaVentaRepositoryTest
    @Autowired
    constructor(
        private val repository: MetaVentaRepository,
    ) : IntegrationTestBase() {
        // id_empleado=1 es el admin seed de V19 (activo, referenciable por FK).
        private fun metaPropuesta(anio: Int = 2099) =
            MetaVenta(idEmpleado = 1, anio = anio, idPropuestoPor = 1).apply {
                establecerMeses(List(12) { 10 })
            }

        @Test
        fun `persiste y recupera una meta propuesta con el anual calculado`() {
            val guardada = repository.save(metaPropuesta())
            val leida = repository.findById(requireNotNull(guardada.id)).orElseThrow()
            assertThat(leida.estado).isEqualTo(EstadoMeta.propuesta)
            assertThat(leida.metaAnual).isEqualTo(120)
            repository.delete(leida)
        }

        @Test
        fun `el indice unico rechaza dos filas del mismo empleado y anio`() {
            val primera = repository.save(metaPropuesta(anio = 2098))
            assertThatThrownBy {
                repository.saveAndFlush(metaPropuesta(anio = 2098))
            }.isInstanceOf(DataIntegrityViolationException::class.java)
            repository.delete(primera)
        }

        @Test
        fun `findByIdEmpleadoAndAnio recupera la fila correcta`() {
            val guardada = repository.save(metaPropuesta(anio = 2097))
            val encontrada = repository.findByIdEmpleadoAndAnio(1, 2097)
            assertThat(encontrada?.id).isEqualTo(guardada.id)
            repository.delete(guardada)
        }
    }
```

- [ ] **Step 4: Verificar que compila**

Run: `./gradlew compileTestKotlin`
Expected: BUILD SUCCESSFUL. (Este test es `@Tag("integration")`: no corre en local, ver Global Constraints.)

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/pe/quantum/crm/domain/metasventa/MetaVenta.kt \
        src/main/kotlin/pe/quantum/crm/domain/metasventa/MetaVentaRepository.kt \
        src/test/kotlin/pe/quantum/crm/domain/metasventa/MetaVentaRepositoryTest.kt
git commit -m "feat(metas-venta): entidad MetaVenta y repositorio"
```

---

### Task 3: DTOs e interfaz de servicio

**Files:**
- Create: `src/main/kotlin/pe/quantum/crm/domain/metasventa/dto/MetaVentaDtos.kt`
- Create: `src/main/kotlin/pe/quantum/crm/domain/metasventa/MetaVentaService.kt`

**Interfaces:**
- Consumes: `MetaVenta`, `EstadoMeta` (Task 2), `EmpleadoResumen` (`pe.quantum.crm.domain.empleados.dto.EmpleadoResumen`), `Paginado` (`pe.quantum.crm.shared.Paginado`), `UsuarioActual` (`pe.quantum.crm.shared.security.UsuarioActual`).
- Produces: `CrearMetaVentaRequest`, `EditarMetaVentaRequest`, `RechazarMetaVentaRequest`, `MetaVentaDto`, `MetaVentaFiltros`, `MetaVentaResumen` (usado por `domain/inicio`, Task 8). Interfaz `MetaVentaService` con `crear`, `editar`, `aprobar`, `rechazar`, `listar`, `detalle`, `aprobadasPorEmpleadosYAnio`.

- [ ] **Step 1: Crear los DTOs**

```kotlin
package pe.quantum.crm.domain.metasventa.dto

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Positive
import pe.quantum.crm.domain.empleados.dto.EmpleadoResumen
import java.time.LocalDateTime

data class CrearMetaVentaRequest(
    @field:NotNull(message = "id_empleado es obligatorio")
    val idEmpleado: Long? = null,
    @field:NotNull(message = "anio es obligatorio")
    val anio: Int? = null,
    @field:NotNull(message = "meta_enero es obligatorio") @field:Positive(message = "meta_enero debe ser mayor a 0")
    val metaEnero: Int? = null,
    @field:NotNull(message = "meta_febrero es obligatorio") @field:Positive(message = "meta_febrero debe ser mayor a 0")
    val metaFebrero: Int? = null,
    @field:NotNull(message = "meta_marzo es obligatorio") @field:Positive(message = "meta_marzo debe ser mayor a 0")
    val metaMarzo: Int? = null,
    @field:NotNull(message = "meta_abril es obligatorio") @field:Positive(message = "meta_abril debe ser mayor a 0")
    val metaAbril: Int? = null,
    @field:NotNull(message = "meta_mayo es obligatorio") @field:Positive(message = "meta_mayo debe ser mayor a 0")
    val metaMayo: Int? = null,
    @field:NotNull(message = "meta_junio es obligatorio") @field:Positive(message = "meta_junio debe ser mayor a 0")
    val metaJunio: Int? = null,
    @field:NotNull(message = "meta_julio es obligatorio") @field:Positive(message = "meta_julio debe ser mayor a 0")
    val metaJulio: Int? = null,
    @field:NotNull(message = "meta_agosto es obligatorio") @field:Positive(message = "meta_agosto debe ser mayor a 0")
    val metaAgosto: Int? = null,
    @field:NotNull(message = "meta_septiembre es obligatorio") @field:Positive(message = "meta_septiembre debe ser mayor a 0")
    val metaSeptiembre: Int? = null,
    @field:NotNull(message = "meta_octubre es obligatorio") @field:Positive(message = "meta_octubre debe ser mayor a 0")
    val metaOctubre: Int? = null,
    @field:NotNull(message = "meta_noviembre es obligatorio") @field:Positive(message = "meta_noviembre debe ser mayor a 0")
    val metaNoviembre: Int? = null,
    @field:NotNull(message = "meta_diciembre es obligatorio") @field:Positive(message = "meta_diciembre debe ser mayor a 0")
    val metaDiciembre: Int? = null,
) {
    /** Los 12 valores en orden calendario, ya validados como no-nulos por `@NotNull`. */
    fun meses(): List<Int> =
        listOf(
            requireNotNull(metaEnero), requireNotNull(metaFebrero), requireNotNull(metaMarzo), requireNotNull(metaAbril),
            requireNotNull(metaMayo), requireNotNull(metaJunio), requireNotNull(metaJulio), requireNotNull(metaAgosto),
            requireNotNull(metaSeptiembre), requireNotNull(metaOctubre), requireNotNull(metaNoviembre), requireNotNull(metaDiciembre),
        )
}

/** Edición parcial: solo gerencia/admin, cualquier subconjunto de los 12 meses. */
data class EditarMetaVentaRequest(
    @field:Positive(message = "meta_enero debe ser mayor a 0") val metaEnero: Int? = null,
    @field:Positive(message = "meta_febrero debe ser mayor a 0") val metaFebrero: Int? = null,
    @field:Positive(message = "meta_marzo debe ser mayor a 0") val metaMarzo: Int? = null,
    @field:Positive(message = "meta_abril debe ser mayor a 0") val metaAbril: Int? = null,
    @field:Positive(message = "meta_mayo debe ser mayor a 0") val metaMayo: Int? = null,
    @field:Positive(message = "meta_junio debe ser mayor a 0") val metaJunio: Int? = null,
    @field:Positive(message = "meta_julio debe ser mayor a 0") val metaJulio: Int? = null,
    @field:Positive(message = "meta_agosto debe ser mayor a 0") val metaAgosto: Int? = null,
    @field:Positive(message = "meta_septiembre debe ser mayor a 0") val metaSeptiembre: Int? = null,
    @field:Positive(message = "meta_octubre debe ser mayor a 0") val metaOctubre: Int? = null,
    @field:Positive(message = "meta_noviembre debe ser mayor a 0") val metaNoviembre: Int? = null,
    @field:Positive(message = "meta_diciembre debe ser mayor a 0") val metaDiciembre: Int? = null,
)

data class RechazarMetaVentaRequest(
    @field:NotBlank(message = "motivo es obligatorio")
    val motivo: String? = null,
)

data class MetaVentaDto(
    val id: Long,
    val idEmpleado: Long,
    val empleado: EmpleadoResumen?,
    val anio: Int,
    val metaEnero: Int,
    val metaFebrero: Int,
    val metaMarzo: Int,
    val metaAbril: Int,
    val metaMayo: Int,
    val metaJunio: Int,
    val metaJulio: Int,
    val metaAgosto: Int,
    val metaSeptiembre: Int,
    val metaOctubre: Int,
    val metaNoviembre: Int,
    val metaDiciembre: Int,
    val metaAnual: Int,
    val estado: String,
    val propuestoPor: EmpleadoResumen?,
    val resolutor: EmpleadoResumen?,
    val motivoRechazo: String?,
    val resolvedAt: LocalDateTime?,
    val createdAt: LocalDateTime,
)

data class MetaVentaFiltros(
    val idEmpleado: Long? = null,
    val anio: Int? = null,
    val estado: String? = null,
)

/** Resumen liviano para consumo de otros módulos (usado por `domain/inicio`, regla CLAUDE.md §12). */
data class MetaVentaResumen(
    val idEmpleado: Long,
    val anio: Int,
    val metaAnual: Int,
    val metaPorMes: List<Int>,
)
```

- [ ] **Step 2: Crear la interfaz de servicio**

```kotlin
package pe.quantum.crm.domain.metasventa

import pe.quantum.crm.domain.metasventa.dto.CrearMetaVentaRequest
import pe.quantum.crm.domain.metasventa.dto.EditarMetaVentaRequest
import pe.quantum.crm.domain.metasventa.dto.MetaVentaDto
import pe.quantum.crm.domain.metasventa.dto.MetaVentaFiltros
import pe.quantum.crm.domain.metasventa.dto.MetaVentaResumen
import pe.quantum.crm.shared.Paginado
import pe.quantum.crm.shared.security.UsuarioActual

/**
 * Interfaz publica del modulo metas de venta. Otros modulos (p. ej. `inicio`)
 * usan esta interfaz, nunca `MetaVentaRepository`/`MetaVenta` directamente
 * (CLAUDE.md regla #12).
 */
interface MetaVentaService {
    /**
     * jdv: crea/re-propone (si no existe fila, o si la existente está
     * `rechazada`) en estado `propuesta`; 409 si ya hay `propuesta`/`aprobada`.
     * gerencia/admin: crea o sobreescribe directo en `aprobada` (upsert).
     */
    fun crear(
        request: CrearMetaVentaRequest,
        usuario: UsuarioActual,
    ): MetaVentaDto

    /** gerencia/admin: edita cualquier subconjunto de los 12 meses, recalcula el anual y auto-aprueba. */
    fun editar(
        id: Long,
        request: EditarMetaVentaRequest,
        usuario: UsuarioActual,
    ): MetaVentaDto

    /** gerencia/admin: aprueba una `propuesta` tal cual. */
    fun aprobar(
        id: Long,
        usuario: UsuarioActual,
    ): MetaVentaDto

    /** gerencia/admin: rechaza una `propuesta` con motivo obligatorio. */
    fun rechazar(
        id: Long,
        motivo: String,
        usuario: UsuarioActual,
    ): MetaVentaDto

    /** Visibilidad: admin/gerencia/jdv ven todas; vendedor/analista solo las propias. */
    @Suppress("LongParameterList") // Paginacion + filtros del contrato, mismo patron que SolicitudService.listar.
    fun listar(
        filtros: MetaVentaFiltros,
        usuario: UsuarioActual,
        page: Int?,
        perPage: Int?,
        sort: String?,
        dir: String?,
    ): Paginado<MetaVentaDto>

    fun detalle(
        id: Long,
        usuario: UsuarioActual,
    ): MetaVentaDto

    /** Metas `aprobada` de estos empleados para el año, indexadas por id_empleado. Usado por `inicio` para el cumplimiento. */
    fun aprobadasPorEmpleadosYAnio(
        idsEmpleado: Collection<Long>,
        anio: Int,
    ): Map<Long, MetaVentaResumen>
}
```

- [ ] **Step 3: Verificar que compila**

Run: `./gradlew compileKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add src/main/kotlin/pe/quantum/crm/domain/metasventa/dto/MetaVentaDtos.kt \
        src/main/kotlin/pe/quantum/crm/domain/metasventa/MetaVentaService.kt
git commit -m "feat(metas-venta): DTOs e interfaz de servicio"
```

---

### Task 4: `MetaVentaServiceImpl`

**Files:**
- Create: `src/main/kotlin/pe/quantum/crm/domain/metasventa/MetaVentaServiceImpl.kt`
- Create: `src/test/kotlin/pe/quantum/crm/domain/metasventa/MetaVentaServiceImplTest.kt`

**Interfaces:**
- Consumes: `MetaVentaRepository` (Task 2), `MetaVentaService`, DTOs (Task 3), `EmpleadoService.esAsignableComoVendedor/resumenPorIds/idsActivosPorRol` (`pe.quantum.crm.domain.empleados.EmpleadoService`), `RolEmpleado` (`pe.quantum.crm.domain.empleados.RolEmpleado`), `nombreCompleto()` (`pe.quantum.crm.domain.empleados.dto.nombreCompleto`), `NotificacionService.notificar` (`pe.quantum.crm.domain.notificaciones.NotificacionService`), `TipoNotificacion`/`EntidadNotificacion` (Task 1), `Paginacion`/`Paginado` (`pe.quantum.crm.shared`), excepciones `ValidacionException`, `ConflictoException`, `PermisoInsuficienteException`, `NoEncontradoException` (`pe.quantum.crm.shared.exception`).
- Produces: `class MetaVentaServiceImpl(...) : MetaVentaService` inyectable como bean `@Service`.

- [ ] **Step 1: Implementar `MetaVentaServiceImpl`**

```kotlin
package pe.quantum.crm.domain.metasventa

import jakarta.persistence.criteria.Predicate
import org.springframework.data.jpa.domain.Specification
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pe.quantum.crm.domain.empleados.EmpleadoService
import pe.quantum.crm.domain.empleados.RolEmpleado
import pe.quantum.crm.domain.empleados.dto.EmpleadoResumen
import pe.quantum.crm.domain.empleados.dto.nombreCompleto
import pe.quantum.crm.domain.metasventa.dto.CrearMetaVentaRequest
import pe.quantum.crm.domain.metasventa.dto.EditarMetaVentaRequest
import pe.quantum.crm.domain.metasventa.dto.MetaVentaDto
import pe.quantum.crm.domain.metasventa.dto.MetaVentaFiltros
import pe.quantum.crm.domain.metasventa.dto.MetaVentaResumen
import pe.quantum.crm.domain.notificaciones.EntidadNotificacion
import pe.quantum.crm.domain.notificaciones.NotificacionService
import pe.quantum.crm.domain.notificaciones.TipoNotificacion
import pe.quantum.crm.shared.Paginacion
import pe.quantum.crm.shared.Paginado
import pe.quantum.crm.shared.enums.EstadoMeta
import pe.quantum.crm.shared.exception.ConflictoException
import pe.quantum.crm.shared.exception.NoEncontradoException
import pe.quantum.crm.shared.exception.PermisoInsuficienteException
import pe.quantum.crm.shared.exception.ValidacionException
import pe.quantum.crm.shared.security.UsuarioActual
import java.time.LocalDateTime

@Service
@Suppress("TooManyFunctions") // Modulo de metas de venta: crear/editar/aprobar/rechazar/listar/detalle + privados.
class MetaVentaServiceImpl(
    private val metaVentaRepository: MetaVentaRepository,
    private val empleadoService: EmpleadoService,
    private val notificacionService: NotificacionService,
) : MetaVentaService {
    @Transactional
    override fun crear(
        request: CrearMetaVentaRequest,
        usuario: UsuarioActual,
    ): MetaVentaDto {
        if (usuario.rol !in ROLES_PROPONENTES) {
            throw PermisoInsuficienteException("Solo jdv, gerencia o admin pueden proponer metas de venta")
        }
        val idEmpleado = requireNotNull(request.idEmpleado)
        val anio = requireNotNull(request.anio)
        if (!empleadoService.esAsignableComoVendedor(idEmpleado)) {
            throw ValidacionException("id_empleado debe ser un vendedor o jdv activo", field = "id_empleado")
        }
        val esGerenciaOAdmin = usuario.rol == "gerencia" || usuario.rol == "admin"
        val existente = metaVentaRepository.findByIdEmpleadoAndAnio(idEmpleado, anio)
        if (existente != null && existente.estado != EstadoMeta.rechazada && !esGerenciaOAdmin) {
            throw ConflictoException(
                "META_YA_EXISTE",
                "Ya existe una meta ${existente.estado.name} para ese empleado y año; usa PATCH para modificarla",
            )
        }
        val meta = existente ?: MetaVenta(idEmpleado = idEmpleado, anio = anio, idPropuestoPor = usuario.id)
        meta.establecerMeses(request.meses())

        if (esGerenciaOAdmin) {
            aprobarDirecto(meta, usuario)
            metaVentaRepository.save(meta)
            notificarResolucion(meta, usuario, TipoNotificacion.meta_modificada, "modificó")
        } else {
            meta.idPropuestoPor = usuario.id
            meta.estado = EstadoMeta.propuesta
            meta.idResolutor = null
            meta.motivoRechazo = null
            meta.resolvedAt = null
            metaVentaRepository.save(meta)
            notificarPropuesta(meta, usuario)
        }
        return toDtos(listOf(meta)).first()
    }

    @Transactional
    override fun editar(
        id: Long,
        request: EditarMetaVentaRequest,
        usuario: UsuarioActual,
    ): MetaVentaDto {
        requireGerenciaOAdmin(usuario)
        val meta = entidad(id)
        if (meta.estado == EstadoMeta.rechazada) {
            throw ConflictoException("META_RECHAZADA", "No se puede editar una meta rechazada; debe volver a proponerse")
        }
        val valores = meta.meses().toMutableList()
        request.metaEnero?.let { valores[0] = it }
        request.metaFebrero?.let { valores[1] = it }
        request.metaMarzo?.let { valores[2] = it }
        request.metaAbril?.let { valores[3] = it }
        request.metaMayo?.let { valores[4] = it }
        request.metaJunio?.let { valores[5] = it }
        request.metaJulio?.let { valores[6] = it }
        request.metaAgosto?.let { valores[7] = it }
        request.metaSeptiembre?.let { valores[8] = it }
        request.metaOctubre?.let { valores[9] = it }
        request.metaNoviembre?.let { valores[10] = it }
        request.metaDiciembre?.let { valores[11] = it }
        meta.establecerMeses(valores)
        aprobarDirecto(meta, usuario)
        metaVentaRepository.save(meta)
        notificarResolucion(meta, usuario, TipoNotificacion.meta_modificada, "modificó")
        return toDtos(listOf(meta)).first()
    }

    @Transactional
    override fun aprobar(
        id: Long,
        usuario: UsuarioActual,
    ): MetaVentaDto {
        requireGerenciaOAdmin(usuario)
        val meta = pendienteParaResolver(id)
        aprobarDirecto(meta, usuario)
        metaVentaRepository.save(meta)
        notificarResolucion(meta, usuario, TipoNotificacion.meta_aprobada, "aprobó")
        return toDtos(listOf(meta)).first()
    }

    @Transactional
    override fun rechazar(
        id: Long,
        motivo: String,
        usuario: UsuarioActual,
    ): MetaVentaDto {
        if (motivo.isBlank()) {
            throw ValidacionException("El motivo del rechazo es obligatorio", field = "motivo")
        }
        requireGerenciaOAdmin(usuario)
        val meta = pendienteParaResolver(id)
        meta.estado = EstadoMeta.rechazada
        meta.idResolutor = usuario.id
        meta.motivoRechazo = motivo
        meta.resolvedAt = LocalDateTime.now()
        meta.updatedAt = LocalDateTime.now()
        metaVentaRepository.save(meta)
        notificarResolucion(meta, usuario, TipoNotificacion.meta_rechazada, "rechazó")
        return toDtos(listOf(meta)).first()
    }

    @Transactional(readOnly = true)
    override fun listar(
        filtros: MetaVentaFiltros,
        usuario: UsuarioActual,
        page: Int?,
        perPage: Int?,
        sort: String?,
        dir: String?,
    ): Paginado<MetaVentaDto> {
        val pageRequest = Paginacion.pageRequest(page, perPage, sort, dir, defaultSort = "anio")
        val resultado = metaVentaRepository.findAll(especificacion(filtros, usuario), pageRequest)
        val meta = Paginacion.meta(pageRequest.pageNumber + 1, pageRequest.pageSize, resultado.totalElements)
        return Paginado(toDtos(resultado.content), meta)
    }

    @Transactional(readOnly = true)
    override fun detalle(
        id: Long,
        usuario: UsuarioActual,
    ): MetaVentaDto = toDtos(listOf(visible(id, usuario))).first()

    @Transactional(readOnly = true)
    override fun aprobadasPorEmpleadosYAnio(
        idsEmpleado: Collection<Long>,
        anio: Int,
    ): Map<Long, MetaVentaResumen> {
        if (idsEmpleado.isEmpty()) return emptyMap()
        return metaVentaRepository
            .findByIdEmpleadoInAndAnioAndEstado(idsEmpleado, anio, EstadoMeta.aprobada)
            .associate {
                it.idEmpleado to
                    MetaVentaResumen(idEmpleado = it.idEmpleado, anio = it.anio, metaAnual = it.metaAnual, metaPorMes = it.meses())
            }
    }

    // ── privados ───────────────────────────────────

    private fun aprobarDirecto(
        meta: MetaVenta,
        usuario: UsuarioActual,
    ) {
        meta.estado = EstadoMeta.aprobada
        meta.idResolutor = usuario.id
        meta.resolvedAt = LocalDateTime.now()
        meta.motivoRechazo = null
        meta.updatedAt = LocalDateTime.now()
    }

    private fun requireGerenciaOAdmin(usuario: UsuarioActual) {
        if (usuario.rol != "gerencia" && usuario.rol != "admin") {
            throw PermisoInsuficienteException("Solo gerencia o admin pueden resolver metas de venta")
        }
    }

    private fun pendienteParaResolver(id: Long): MetaVenta {
        val meta = metaVentaRepository.findByIdForUpdate(id) ?: throw NoEncontradoException("La meta de venta no existe")
        if (meta.estado != EstadoMeta.propuesta) {
            throw ConflictoException("META_YA_RESUELTA", "La meta ya fue resuelta")
        }
        return meta
    }

    private fun entidad(id: Long): MetaVenta = metaVentaRepository.findById(id).orElseThrow { NoEncontradoException("La meta de venta no existe") }

    /** IDOR: meta ajena para vendedor/analista → 404, no 403. */
    private fun visible(
        id: Long,
        usuario: UsuarioActual,
    ): MetaVenta {
        val meta = entidad(id)
        val alcanzable =
            when (usuario.rol) {
                "admin", "gerencia", "jdv" -> true // ven todo el equipo (unico jdv, sin sub-equipos)
                else -> meta.idEmpleado == usuario.id
            }
        if (!alcanzable) throw NoEncontradoException("La meta de venta no existe")
        return meta
    }

    private fun especificacion(
        filtros: MetaVentaFiltros,
        usuario: UsuarioActual,
    ): Specification<MetaVenta> =
        Specification { root, _, cb ->
            val predicados = mutableListOf<Predicate>()
            if (usuario.rol == "vendedor" || usuario.rol == "analista") {
                predicados += cb.equal(root.get<Long>("idEmpleado"), usuario.id)
            }
            filtros.idEmpleado?.let { predicados += cb.equal(root.get<Long>("idEmpleado"), it) }
            filtros.anio?.let { predicados += cb.equal(root.get<Int>("anio"), it) }
            filtros.estado?.let { estado ->
                runCatching { EstadoMeta.valueOf(estado) }.getOrNull()?.let {
                    predicados += cb.equal(root.get<EstadoMeta>("estado"), it)
                }
            }
            cb.and(*predicados.toTypedArray())
        }

    private fun notificarPropuesta(
        meta: MetaVenta,
        usuario: UsuarioActual,
    ) {
        val (actorNombre, empleadoNombre) = nombres(usuario.id, meta.idEmpleado)
        notificacionService.notificar(
            destinatarios = empleadoService.idsActivosPorRol(RolEmpleado.gerencia).toSet(),
            idActor = usuario.id,
            tipo = TipoNotificacion.meta_propuesta,
            mensaje = "$actorNombre propuso la meta de venta ${meta.anio} de $empleadoNombre",
            entidadTipo = EntidadNotificacion.meta_venta,
            entidadId = requireNotNull(meta.id),
        )
    }

    private fun notificarResolucion(
        meta: MetaVenta,
        usuario: UsuarioActual,
        tipo: TipoNotificacion,
        verbo: String,
    ) {
        val (actorNombre, empleadoNombre) = nombres(usuario.id, meta.idEmpleado)
        val sufijo = meta.motivoRechazo?.let { ": $it" } ?: ""
        notificacionService.notificar(
            destinatarios = setOf(meta.idPropuestoPor, meta.idEmpleado),
            idActor = usuario.id,
            tipo = tipo,
            mensaje = "$actorNombre $verbo la meta de venta ${meta.anio} de $empleadoNombre$sufijo",
            entidadTipo = EntidadNotificacion.meta_venta,
            entidadId = requireNotNull(meta.id),
        )
    }

    private fun nombres(
        idActor: Long,
        idEmpleado: Long,
    ): Pair<String, String> {
        val resumenes = empleadoService.resumenPorIds(listOf(idActor, idEmpleado))
        return (resumenes[idActor]?.nombreCompleto() ?: "Alguien") to (resumenes[idEmpleado]?.nombreCompleto() ?: "un empleado")
    }

    private fun toDtos(metas: List<MetaVenta>): List<MetaVentaDto> {
        if (metas.isEmpty()) return emptyList()
        val idsEmpleados = (metas.map { it.idEmpleado } + metas.map { it.idPropuestoPor } + metas.mapNotNull { it.idResolutor }).distinct()
        val empleados = empleadoService.resumenPorIds(idsEmpleados)
        return metas.map { m -> m.toDto(empleados) }
    }

    private fun MetaVenta.toDto(empleados: Map<Long, EmpleadoResumen>) =
        MetaVentaDto(
            id = requireNotNull(id),
            idEmpleado = idEmpleado,
            empleado = empleados[idEmpleado],
            anio = anio,
            metaEnero = metaEnero,
            metaFebrero = metaFebrero,
            metaMarzo = metaMarzo,
            metaAbril = metaAbril,
            metaMayo = metaMayo,
            metaJunio = metaJunio,
            metaJulio = metaJulio,
            metaAgosto = metaAgosto,
            metaSeptiembre = metaSeptiembre,
            metaOctubre = metaOctubre,
            metaNoviembre = metaNoviembre,
            metaDiciembre = metaDiciembre,
            metaAnual = metaAnual,
            estado = estado.name,
            propuestoPor = empleados[idPropuestoPor],
            resolutor = idResolutor?.let { empleados[it] },
            motivoRechazo = motivoRechazo,
            resolvedAt = resolvedAt,
            createdAt = createdAt,
        )

    private companion object {
        val ROLES_PROPONENTES = setOf("jdv", "gerencia", "admin")
    }
}
```

- [ ] **Step 2: Escribir los tests unitarios (MockK)**

```kotlin
package pe.quantum.crm.domain.metasventa

import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import pe.quantum.crm.domain.empleados.EmpleadoService
import pe.quantum.crm.domain.empleados.RolEmpleado
import pe.quantum.crm.domain.empleados.dto.EmpleadoResumen
import pe.quantum.crm.domain.metasventa.dto.CrearMetaVentaRequest
import pe.quantum.crm.domain.metasventa.dto.EditarMetaVentaRequest
import pe.quantum.crm.domain.notificaciones.EntidadNotificacion
import pe.quantum.crm.domain.notificaciones.NotificacionService
import pe.quantum.crm.domain.notificaciones.TipoNotificacion
import pe.quantum.crm.shared.enums.EstadoMeta
import pe.quantum.crm.shared.exception.ConflictoException
import pe.quantum.crm.shared.exception.PermisoInsuficienteException
import pe.quantum.crm.shared.exception.ValidacionException
import pe.quantum.crm.shared.security.UsuarioActual

class MetaVentaServiceImplTest {
    private val metaVentaRepository = mockk<MetaVentaRepository>()
    private val empleadoService = mockk<EmpleadoService>()
    private val notificacionService = mockk<NotificacionService>(relaxed = true)
    private val service = MetaVentaServiceImpl(metaVentaRepository, empleadoService, notificacionService)

    private val jdv = UsuarioActual(id = 2, rol = "jdv")
    private val gerencia = UsuarioActual(id = 1, rol = "gerencia")
    private val vendedor = UsuarioActual(id = 5, rol = "vendedor")

    private fun requestAnioCompleto(idEmpleado: Long = 5, anio: Int = 2027, valorMes: Int = 10) =
        CrearMetaVentaRequest(
            idEmpleado = idEmpleado, anio = anio,
            metaEnero = valorMes, metaFebrero = valorMes, metaMarzo = valorMes, metaAbril = valorMes,
            metaMayo = valorMes, metaJunio = valorMes, metaJulio = valorMes, metaAgosto = valorMes,
            metaSeptiembre = valorMes, metaOctubre = valorMes, metaNoviembre = valorMes, metaDiciembre = valorMes,
        )

    /** JPA asigna el id al guardar (IDENTITY); el mock lo simula con una copia, igual que en SolicitudServiceImplTest. */
    private fun MetaVenta.conId(nuevoId: Long) =
        MetaVenta(
            id = nuevoId,
            idEmpleado = idEmpleado,
            anio = anio,
            estado = estado,
            idPropuestoPor = idPropuestoPor,
            idResolutor = idResolutor,
            motivoRechazo = motivoRechazo,
            resolvedAt = resolvedAt,
            createdAt = createdAt,
            updatedAt = updatedAt,
        ).also { it.establecerMeses(meses()) }

    @Test
    fun `jdv propone meta nueva de un vendedor - queda propuesta y notifica a gerencia`() {
        every { empleadoService.esAsignableComoVendedor(5) } returns true
        every { metaVentaRepository.findByIdEmpleadoAndAnio(5, 2027) } returns null
        val guardada = slot<MetaVenta>()
        every { metaVentaRepository.save(capture(guardada)) } answers { guardada.captured.conId(50) }
        every { empleadoService.idsActivosPorRol(RolEmpleado.gerencia) } returns listOf(1)
        every { empleadoService.resumenPorIds(any()) } returns emptyMap()

        val dto = service.crear(requestAnioCompleto(), jdv)

        assertThat(dto.estado).isEqualTo("propuesta")
        assertThat(dto.metaAnual).isEqualTo(120)
        verify {
            notificacionService.notificar(
                destinatarios = setOf(1L),
                idActor = 2,
                tipo = TipoNotificacion.meta_propuesta,
                mensaje = any(),
                entidadTipo = EntidadNotificacion.meta_venta,
                entidadId = any(),
            )
        }
    }

    @Test
    fun `jdv proponer sobre una meta ya propuesta es 409 META_YA_EXISTE`() {
        every { empleadoService.esAsignableComoVendedor(5) } returns true
        val existente = MetaVenta(idEmpleado = 5, anio = 2027, idPropuestoPor = 2).apply { establecerMeses(List(12) { 10 }) }
        every { metaVentaRepository.findByIdEmpleadoAndAnio(5, 2027) } returns existente
        assertThatThrownBy { service.crear(requestAnioCompleto(), jdv) }
            .isInstanceOf(ConflictoException::class.java)
    }

    @Test
    fun `gerencia crea meta directo y queda aprobada`() {
        every { empleadoService.esAsignableComoVendedor(5) } returns true
        every { metaVentaRepository.findByIdEmpleadoAndAnio(5, 2027) } returns null
        val guardada = slot<MetaVenta>()
        every { metaVentaRepository.save(capture(guardada)) } answers { guardada.captured.conId(51) }
        every { empleadoService.resumenPorIds(any()) } returns emptyMap()

        val dto = service.crear(requestAnioCompleto(), gerencia)

        assertThat(dto.estado).isEqualTo("aprobada")
        assertThat(dto.resolvedAt).isNotNull()
        verify {
            notificacionService.notificar(
                destinatarios = setOf(1L, 5L),
                idActor = 1,
                tipo = TipoNotificacion.meta_modificada,
                mensaje = any(),
                entidadTipo = EntidadNotificacion.meta_venta,
                entidadId = any(),
            )
        }
    }

    @Test
    fun `gerencia sobreescribe una propuesta pendiente sin 409`() {
        every { empleadoService.esAsignableComoVendedor(5) } returns true
        val existente = MetaVenta(id = 9, idEmpleado = 5, anio = 2027, idPropuestoPor = 2).apply { establecerMeses(List(12) { 10 }) }
        every { metaVentaRepository.findByIdEmpleadoAndAnio(5, 2027) } returns existente
        every { metaVentaRepository.save(existente) } returns existente
        every { empleadoService.resumenPorIds(any()) } returns emptyMap()

        val dto = service.crear(requestAnioCompleto(valorMes = 20), gerencia)

        assertThat(dto.estado).isEqualTo("aprobada")
        assertThat(dto.metaAnual).isEqualTo(240)
    }

    @Test
    fun `vendedor no puede proponer metas`() {
        assertThatThrownBy { service.crear(requestAnioCompleto(), vendedor) }
            .isInstanceOf(PermisoInsuficienteException::class.java)
    }

    @Test
    fun `editar un mes especifico recalcula el anual y auto-aprueba`() {
        val meta = MetaVenta(id = 9, idEmpleado = 5, anio = 2027, idPropuestoPor = 2).apply { establecerMeses(List(12) { 10 }) }
        every { metaVentaRepository.findById(9) } returns java.util.Optional.of(meta)
        every { metaVentaRepository.save(meta) } returns meta
        every { empleadoService.resumenPorIds(any()) } returns emptyMap()

        val dto = service.editar(9, EditarMetaVentaRequest(metaMarzo = 50), gerencia)

        assertThat(dto.metaMarzo).isEqualTo(50)
        assertThat(dto.metaAnual).isEqualTo(160) // 11*10 + 50
        assertThat(dto.estado).isEqualTo("aprobada")
    }

    @Test
    fun `aprobar una ya resuelta es 409 META_YA_RESUELTA`() {
        val meta =
            MetaVenta(id = 9, idEmpleado = 5, anio = 2027, idPropuestoPor = 2, estado = EstadoMeta.aprobada)
                .apply { establecerMeses(List(12) { 10 }) }
        every { metaVentaRepository.findByIdForUpdate(9) } returns meta
        assertThatThrownBy { service.aprobar(9, gerencia) }
            .isInstanceOf(ConflictoException::class.java)
            .hasMessageContaining("resuelta")
    }

    @Test
    fun `rechazar exige motivo y notifica al proponente y al empleado`() {
        val meta = MetaVenta(id = 9, idEmpleado = 5, anio = 2027, idPropuestoPor = 2).apply { establecerMeses(List(12) { 10 }) }
        every { metaVentaRepository.findByIdForUpdate(9) } returns meta
        every { metaVentaRepository.save(meta) } returns meta
        every { empleadoService.resumenPorIds(any()) } returns emptyMap()

        val dto = service.rechazar(9, "Marzo está muy alto respecto al histórico", gerencia)

        assertThat(dto.estado).isEqualTo("rechazada")
        assertThat(dto.motivoRechazo).isEqualTo("Marzo está muy alto respecto al histórico")
        verify {
            notificacionService.notificar(
                destinatarios = setOf(2L, 5L),
                idActor = 1,
                tipo = TipoNotificacion.meta_rechazada,
                mensaje = match { it.contains("Marzo está muy alto") },
                entidadTipo = EntidadNotificacion.meta_venta,
                entidadId = 9L,
            )
        }
    }

    @Test
    fun `rechazar con motivo en blanco es VALIDACION`() {
        assertThatThrownBy { service.rechazar(9, "  ", gerencia) }
            .isInstanceOf(ValidacionException::class.java)
    }

    @Test
    fun `detalle de meta ajena para vendedor es 404`() {
        val meta = MetaVenta(id = 9, idEmpleado = 99, anio = 2027, idPropuestoPor = 2).apply { establecerMeses(List(12) { 10 }) }
        every { metaVentaRepository.findById(9) } returns java.util.Optional.of(meta)
        assertThatThrownBy { service.detalle(9, vendedor) }
            .isInstanceOf(pe.quantum.crm.shared.exception.NoEncontradoException::class.java)
    }

    @Test
    fun `aprobadasPorEmpleadosYAnio agrega solo las aprobadas del anio`() {
        val aprobada = MetaVenta(idEmpleado = 5, anio = 2027, idPropuestoPor = 2, estado = EstadoMeta.aprobada).apply {
            establecerMeses(List(12) { 10 })
        }
        every { metaVentaRepository.findByIdEmpleadoInAndAnioAndEstado(listOf(5L, 6L), 2027, EstadoMeta.aprobada) } returns listOf(aprobada)

        val resultado = service.aprobadasPorEmpleadosYAnio(listOf(5L, 6L), 2027)

        assertThat(resultado).containsOnlyKeys(5L)
        assertThat(resultado.getValue(5L).metaAnual).isEqualTo(120)
        assertThat(resultado.getValue(5L).metaPorMes).hasSize(12)
    }
}
```

Nota: elimina el helper `conId` sin usar del bloque de arriba si tu editor lo marca — no aporta nada (a diferencia de `Solicitud`/`Oportunidad`, `MetaVenta.id` no hace falta simularlo porque ningún test de este archivo depende del id asignado por JPA salvo donde ya se construye con `id = 9` explícito).

- [ ] **Step 3: Ejecutar los tests**

Run: `./gradlew test --tests "pe.quantum.crm.domain.metasventa.MetaVentaServiceImplTest"`
Expected: BUILD SUCCESSFUL, todos los tests en verde.

- [ ] **Step 4: Commit**

```bash
git add src/main/kotlin/pe/quantum/crm/domain/metasventa/MetaVentaServiceImpl.kt \
        src/test/kotlin/pe/quantum/crm/domain/metasventa/MetaVentaServiceImplTest.kt
git commit -m "feat(metas-venta): MetaVentaServiceImpl con ciclo propuesta/aprobacion/rechazo"
```

---

### Task 5: `MetaVentaController` y mocks de infraestructura de tests

**Files:**
- Create: `src/main/kotlin/pe/quantum/crm/domain/metasventa/MetaVentaController.kt`
- Modify: `src/test/kotlin/pe/quantum/crm/support/SinBaseDeDatosMocks.kt`
- Create: `src/test/kotlin/pe/quantum/crm/domain/metasventa/MetaVentaControllerWebMvcTest.kt`

**Interfaces:**
- Consumes: `MetaVentaService` (Task 3/4), `ApiResponse` (`pe.quantum.crm.shared.ApiResponse`), `UsuarioActualProvider` (`pe.quantum.crm.shared.security.UsuarioActualProvider`).
- Produces: endpoints `POST /api/v1/metas-venta`, `PATCH /api/v1/metas-venta/:id`, `PATCH /api/v1/metas-venta/:id/aprobar`, `PATCH /api/v1/metas-venta/:id/rechazar`, `GET /api/v1/metas-venta`, `GET /api/v1/metas-venta/:id`.

- [ ] **Step 1: Crear el controller**

```kotlin
package pe.quantum.crm.domain.metasventa

import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import pe.quantum.crm.domain.metasventa.dto.CrearMetaVentaRequest
import pe.quantum.crm.domain.metasventa.dto.EditarMetaVentaRequest
import pe.quantum.crm.domain.metasventa.dto.MetaVentaDto
import pe.quantum.crm.domain.metasventa.dto.MetaVentaFiltros
import pe.quantum.crm.domain.metasventa.dto.RechazarMetaVentaRequest
import pe.quantum.crm.shared.ApiResponse
import pe.quantum.crm.shared.security.UsuarioActualProvider

/** Endpoints de metas de venta en unidades (contrato_api.md §21). */
@RestController
@RequestMapping("/api/v1/metas-venta")
class MetaVentaController(
    private val metaVentaService: MetaVentaService,
    private val usuarioProvider: UsuarioActualProvider,
) {
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun crear(
        @Valid @RequestBody request: CrearMetaVentaRequest,
    ): ApiResponse<MetaVentaDto> = ApiResponse.ok(metaVentaService.crear(request, usuarioProvider.actual()))

    @PatchMapping("/{id}")
    fun editar(
        @PathVariable id: Long,
        @Valid @RequestBody request: EditarMetaVentaRequest,
    ): ApiResponse<MetaVentaDto> = ApiResponse.ok(metaVentaService.editar(id, request, usuarioProvider.actual()))

    @PatchMapping("/{id}/aprobar")
    fun aprobar(
        @PathVariable id: Long,
    ): ApiResponse<MetaVentaDto> = ApiResponse.ok(metaVentaService.aprobar(id, usuarioProvider.actual()))

    @PatchMapping("/{id}/rechazar")
    fun rechazar(
        @PathVariable id: Long,
        @Valid @RequestBody request: RechazarMetaVentaRequest,
    ): ApiResponse<MetaVentaDto> =
        ApiResponse.ok(metaVentaService.rechazar(id, requireNotNull(request.motivo), usuarioProvider.actual()))

    @GetMapping
    @Suppress("LongParameterList") // Query params del contrato.
    fun listar(
        @RequestParam(required = false, name = "id_empleado") idEmpleado: Long?,
        @RequestParam(required = false) anio: Int?,
        @RequestParam(required = false) estado: String?,
        @RequestParam(required = false) page: Int?,
        @RequestParam(name = "per_page", required = false) perPage: Int?,
        @RequestParam(required = false) sort: String?,
        @RequestParam(required = false) dir: String?,
    ): ApiResponse<List<MetaVentaDto>> {
        val resultado =
            metaVentaService.listar(
                MetaVentaFiltros(idEmpleado = idEmpleado, anio = anio, estado = estado),
                usuarioProvider.actual(),
                page,
                perPage,
                sort,
                dir,
            )
        return ApiResponse.ok(resultado.items, resultado.meta)
    }

    @GetMapping("/{id}")
    fun detalle(
        @PathVariable id: Long,
    ): ApiResponse<MetaVentaDto> = ApiResponse.ok(metaVentaService.detalle(id, usuarioProvider.actual()))
}
```

- [ ] **Step 2: Añadir el mock del repositorio a `SinBaseDeDatosMocks`**

En `src/test/kotlin/pe/quantum/crm/support/SinBaseDeDatosMocks.kt`, añade el import `pe.quantum.crm.domain.metasventa.MetaVentaRepository` y, dentro de la clase, el bean:

```kotlin
    @Bean
    fun metaVentaRepository(): MetaVentaRepository = mockk(relaxed = true)
```

(Sin este bean, **cualquier** test `@SpringBootTest` sin base de datos deja de arrancar el contexto en cuanto exista `MetaVentaServiceImpl`, porque Spring no puede resolver su dependencia `MetaVentaRepository`.)

- [ ] **Step 3: Test WebMvc del controller**

```kotlin
package pe.quantum.crm.domain.metasventa

import com.ninjasquad.springmockk.MockkBean
import io.mockk.every
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.patch
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.MockMvc
import pe.quantum.crm.config.security.JwtService
import pe.quantum.crm.domain.metasventa.dto.MetaVentaDto
import pe.quantum.crm.shared.Paginacion
import pe.quantum.crm.shared.Paginado
import pe.quantum.crm.support.SinBaseDeDatosMocks
import java.time.LocalDateTime

/** Tests de los endpoints de metas de venta via MockMvc, sin base de datos. */
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
class MetaVentaControllerWebMvcTest {
    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var jwtService: JwtService

    @MockkBean
    lateinit var metaVentaService: MetaVentaService

    private fun tokenJdv() = jwtService.generateAccessToken(empleadoId = 2, rol = "jdv")

    private fun tokenGerencia() = jwtService.generateAccessToken(empleadoId = 1, rol = "gerencia")

    private fun metaVentaDto(estado: String = "propuesta") =
        MetaVentaDto(
            id = 9, idEmpleado = 5, empleado = null, anio = 2027,
            metaEnero = 10, metaFebrero = 10, metaMarzo = 10, metaAbril = 10, metaMayo = 10, metaJunio = 10,
            metaJulio = 10, metaAgosto = 10, metaSeptiembre = 10, metaOctubre = 10, metaNoviembre = 10, metaDiciembre = 10,
            metaAnual = 120, estado = estado, propuestoPor = null, resolutor = null, motivoRechazo = null,
            resolvedAt = null, createdAt = LocalDateTime.now(),
        )

    private fun bodyAnioCompleto() =
        """{"id_empleado":5,"anio":2027,"meta_enero":10,"meta_febrero":10,"meta_marzo":10,"meta_abril":10,
           "meta_mayo":10,"meta_junio":10,"meta_julio":10,"meta_agosto":10,"meta_septiembre":10,
           "meta_octubre":10,"meta_noviembre":10,"meta_diciembre":10}"""

    @Test
    fun `POST metas-venta responde 201 con el envelope`() {
        every { metaVentaService.crear(any(), any()) } returns metaVentaDto()
        mockMvc.post("/api/v1/metas-venta") {
            header(HttpHeaders.AUTHORIZATION, "Bearer ${tokenJdv()}")
            contentType = MediaType.APPLICATION_JSON
            content = bodyAnioCompleto()
        }.andExpect {
            status { isCreated() }
            jsonPath("$.data.id") { value(9) }
            jsonPath("$.data.meta_anual") { value(120) }
        }
    }

    @Test
    fun `POST metas-venta sin meta_marzo responde 400 VALIDACION`() {
        mockMvc.post("/api/v1/metas-venta") {
            header(HttpHeaders.AUTHORIZATION, "Bearer ${tokenJdv()}")
            contentType = MediaType.APPLICATION_JSON
            content =
                """{"id_empleado":5,"anio":2027,"meta_enero":10,"meta_febrero":10,"meta_abril":10,
                   "meta_mayo":10,"meta_junio":10,"meta_julio":10,"meta_agosto":10,"meta_septiembre":10,
                   "meta_octubre":10,"meta_noviembre":10,"meta_diciembre":10}"""
        }.andExpect {
            status { isBadRequest() }
            jsonPath("$.error.code") { value("VALIDACION") }
        }
    }

    @Test
    fun `GET metas-venta responde 200 paginado`() {
        every { metaVentaService.listar(any(), any(), any(), any(), any(), any()) } returns
            Paginado(listOf(metaVentaDto()), Paginacion.meta(1, 20, 1))
        mockMvc.get("/api/v1/metas-venta?estado=propuesta") {
            header(HttpHeaders.AUTHORIZATION, "Bearer ${tokenGerencia()}")
        }.andExpect {
            status { isOk() }
            jsonPath("$.meta.page") { value(1) }
            jsonPath("$.data[0].id") { value(9) }
        }
    }

    @Test
    fun `PATCH aprobar responde 200 con estado aprobada`() {
        every { metaVentaService.aprobar(9, any()) } returns metaVentaDto(estado = "aprobada")
        mockMvc.patch("/api/v1/metas-venta/9/aprobar") {
            header(HttpHeaders.AUTHORIZATION, "Bearer ${tokenGerencia()}")
        }.andExpect {
            status { isOk() }
            jsonPath("$.data.estado") { value("aprobada") }
        }
    }

    @Test
    fun `PATCH rechazar sin motivo responde 400`() {
        mockMvc.patch("/api/v1/metas-venta/9/rechazar") {
            header(HttpHeaders.AUTHORIZATION, "Bearer ${tokenGerencia()}")
            contentType = MediaType.APPLICATION_JSON
            content = """{"motivo":""}"""
        }.andExpect { status { isBadRequest() } }
    }

    @Test
    fun `sin token responde 401`() {
        mockMvc.get("/api/v1/metas-venta").andExpect { status { isUnauthorized() } }
    }
}
```

- [ ] **Step 4: Ejecutar los tests**

Run: `./gradlew test --tests "pe.quantum.crm.domain.metasventa.*"`
Expected: BUILD SUCCESSFUL, todos los tests en verde (incluye `MetaVentaServiceImplTest` de la Task 4).

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/pe/quantum/crm/domain/metasventa/MetaVentaController.kt \
        src/test/kotlin/pe/quantum/crm/support/SinBaseDeDatosMocks.kt \
        src/test/kotlin/pe/quantum/crm/domain/metasventa/MetaVentaControllerWebMvcTest.kt
git commit -m "feat(metas-venta): controller REST y mocks de test sin base de datos"
```

---

### Task 6: `oportunidades.facturado_en` — entidad y `cambiarEstado`

**Files:**
- Modify: `src/main/kotlin/pe/quantum/crm/domain/oportunidades/Oportunidad.kt`
- Modify: `src/main/kotlin/pe/quantum/crm/domain/oportunidades/OportunidadServiceImpl.kt`
- Modify: `src/test/kotlin/pe/quantum/crm/domain/oportunidades/OportunidadServiceImplTest.kt`

**Interfaces:**
- Produces: `Oportunidad.facturadoEn: LocalDateTime?` (var). `cambiarEstado` fija `facturadoEn = now()` al entrar a `facturado`, `null` al salir.

- [ ] **Step 1: Añadir el campo a la entidad**

En `src/main/kotlin/pe/quantum/crm/domain/oportunidades/Oportunidad.kt`, añade el campo después de `fechaCierreEstimado` (línea 58):

```kotlin
    @Column(name = "fecha_cierre_estimado")
    var fechaCierreEstimado: LocalDate? = null,
    @Column(name = "facturado_en")
    var facturadoEn: LocalDateTime? = null,
    @Column(name = "created_at", nullable = false)
```

- [ ] **Step 2: Fijar/limpiar `facturadoEn` en `cambiarEstado`**

En `src/main/kotlin/pe/quantum/crm/domain/oportunidades/OportunidadServiceImpl.kt`, dentro de `cambiarEstado` (alrededor de la línea 249), cambia:

```kotlin
        oportunidad.estado = nuevo
        oportunidad.updatedAt = LocalDateTime.now()
```

por:

```kotlin
        oportunidad.estado = nuevo
        oportunidad.facturadoEn = if (nuevo == EstadoOportunidad.facturado) LocalDateTime.now() else null
        oportunidad.updatedAt = LocalDateTime.now()
```

- [ ] **Step 3: Actualizar el helper `conId` del test para no perder el campo**

En `src/test/kotlin/pe/quantum/crm/domain/oportunidades/OportunidadServiceImplTest.kt`, dentro de `private fun Oportunidad.conId(nuevoId: Long)`, añade `facturadoEn = facturadoEn,` junto a `fechaCierreEstimado = fechaCierreEstimado,`:

```kotlin
            fechaCierreEstimado = fechaCierreEstimado,
            facturadoEn = facturadoEn,
            createdAt = createdAt,
```

- [ ] **Step 4: Añadir los tests de `facturadoEn`**

Añade estos dos tests al final de la clase `OportunidadServiceImplTest` (antes de la última llave de cierre), reutilizando el fixture `oportunidad()` y el patrón del test existente `cambiarEstado notifica a los supervisores...`:

```kotlin
    @Test
    fun `cambiarEstado a facturado fija facturado_en`() {
        val entidad = oportunidad(idVendedor = 1)
        every { oportunidadRepository.findById(100) } returns Optional.of(entidad)
        every { oportunidadRepository.save(entidad) } returns entidad
        every { logRepository.save(any()) } returns mockk()
        every {
            consultas.eventosRecomendadosSinRegistrar(100, pe.quantum.crm.shared.enums.EstadoOportunidad.evaluacion_calidda)
        } returns emptyList()
        every { estadoCarteraService.actualizar(10) } returns null
        every { empresaService.resumenPorIds(listOf(10)) } returns
            mapOf(10L to EmpresaResumen(id = 10, razonSocial = "Kincar S.A.C.", distrito = null))
        every { empleadoService.resumenPorIds(listOf(1)) } returns mapOf(1L to EmpleadoResumen(id = 1, nombres = "Ana", apellidos = "Diaz"))
        every { empleadoService.idsSupervisoresActivos() } returns listOf(1)

        service.cambiarEstado(
            100,
            pe.quantum.crm.domain.oportunidades.dto.CambiarEstadoRequest(estado = "facturado"),
            UsuarioActual(id = 1, rol = "admin"),
        )

        assertThat(entidad.facturadoEn).isNotNull()
    }

    @Test
    fun `cambiarEstado que retrocede desde facturado limpia facturado_en`() {
        val entidad =
            oportunidad(idVendedor = 1).apply {
                estado = pe.quantum.crm.shared.enums.EstadoOportunidad.facturado
                facturadoEn = LocalDateTime.now().minusDays(5)
            }
        every { oportunidadRepository.findById(100) } returns Optional.of(entidad)
        every { oportunidadRepository.save(entidad) } returns entidad
        every { logRepository.save(any()) } returns mockk()
        every {
            consultas.eventosRecomendadosSinRegistrar(100, pe.quantum.crm.shared.enums.EstadoOportunidad.facturado)
        } returns emptyList()
        every { estadoCarteraService.actualizar(10) } returns null
        every { empresaService.resumenPorIds(listOf(10)) } returns
            mapOf(10L to EmpresaResumen(id = 10, razonSocial = "Kincar S.A.C.", distrito = null))
        every { empleadoService.resumenPorIds(listOf(1)) } returns mapOf(1L to EmpleadoResumen(id = 1, nombres = "Ana", apellidos = "Diaz"))
        every { empleadoService.idsSupervisoresActivos() } returns listOf(1)

        service.cambiarEstado(
            100,
            pe.quantum.crm.domain.oportunidades.dto.CambiarEstadoRequest(estado = "documentos_legales"),
            UsuarioActual(id = 1, rol = "admin"),
        )

        assertThat(entidad.facturadoEn).isNull()
    }
```

- [ ] **Step 5: Ejecutar los tests**

Run: `./gradlew test --tests "pe.quantum.crm.domain.oportunidades.OportunidadServiceImplTest"`
Expected: BUILD SUCCESSFUL, todos los tests en verde (incluidos los preexistentes).

- [ ] **Step 6: Commit**

```bash
git add src/main/kotlin/pe/quantum/crm/domain/oportunidades/Oportunidad.kt \
        src/main/kotlin/pe/quantum/crm/domain/oportunidades/OportunidadServiceImpl.kt \
        src/test/kotlin/pe/quantum/crm/domain/oportunidades/OportunidadServiceImplTest.kt
git commit -m "feat(oportunidades): fijar/limpiar facturado_en al entrar/salir de facturado"
```

---

### Task 7: `InicioDao` — unidades facturadas por vendedor

**Files:**
- Modify: `src/main/kotlin/pe/quantum/crm/domain/inicio/InicioDao.kt`

**Interfaces:**
- Produces: `InicioDao.unidadesFacturadasPorVendedor(idsVendedor: Collection<Long>, anio: Int, mes: Int?): Map<Long, Int>`.

- [ ] **Step 1: Añadir el método al DAO**

En `src/main/kotlin/pe/quantum/crm/domain/inicio/InicioDao.kt`, añade este método dentro de `class InicioDao` (después de `resumenPipeline`):

```kotlin
    /**
     * Unidades facturadas por vendedor en un periodo: suma en vivo sobre
     * `oportunidades.facturado_en` (sin contador aparte, ver V33). `mes = null`
     * agrega todo el año.
     */
    fun unidadesFacturadasPorVendedor(
        idsVendedor: Collection<Long>,
        anio: Int,
        mes: Int?,
    ): Map<Long, Int> {
        if (idsVendedor.isEmpty()) return emptyMap()
        val sql =
            buildString {
                append("SELECT id_vendedor, COALESCE(SUM(cantidad), 0) AS unidades FROM oportunidades ")
                append("WHERE estado = 'facturado' AND id_vendedor IN (:idsVendedor) ")
                append("AND EXTRACT(YEAR FROM facturado_en) = :anio ")
                if (mes != null) append("AND EXTRACT(MONTH FROM facturado_en) = :mes ")
                append("GROUP BY id_vendedor")
            }
        val params =
            MapSqlParameterSource()
                .addValue("idsVendedor", idsVendedor)
                .addValue("anio", anio)
                .addValue("mes", mes)
        return jdbc.query(sql, params) { rs, _ -> rs.getLong("id_vendedor") to rs.getInt("unidades") }.toMap()
    }
```

`MapSqlParameterSource` ya está importado en el archivo (línea 3).

- [ ] **Step 2: Verificar que compila**

Run: `./gradlew compileKotlin`
Expected: BUILD SUCCESSFUL. (Sin test dedicado: `InicioDao` no tiene cobertura propia hoy tampoco — la lógica de agregación se cubre indirectamente en el test de `InicioServiceImpl` de la Task 8, que mockea este DAO.)

- [ ] **Step 3: Commit**

```bash
git add src/main/kotlin/pe/quantum/crm/domain/inicio/InicioDao.kt
git commit -m "feat(inicio): unidades facturadas por vendedor en InicioDao"
```

---

### Task 8: Panel de Inicio — DTOs y `InicioServiceImpl`

**Files:**
- Modify: `src/main/kotlin/pe/quantum/crm/domain/inicio/dto/InicioDtos.kt`
- Modify: `src/main/kotlin/pe/quantum/crm/domain/inicio/InicioService.kt`
- Create: `src/test/kotlin/pe/quantum/crm/domain/inicio/InicioServiceImplTest.kt`

**Interfaces:**
- Consumes: `MetaVentaService.aprobadasPorEmpleadosYAnio` (Task 4), `MetaVentaResumen` (Task 3), `InicioDao.unidadesFacturadasPorVendedor` (Task 7), `EmpleadoService.idsActivosPorRol` (`pe.quantum.crm.domain.empleados.EmpleadoService`), `RolEmpleado.vendedor`.
- Produces: `MedidorMetaDto`, `MetaVentaAgregadoDto`, `MetaVentaInicioDto`; `InicioDto.metaVentas: MetaVentaInicioDto?`.

- [ ] **Step 1: Añadir los DTOs nuevos**

En `src/main/kotlin/pe/quantum/crm/domain/inicio/dto/InicioDtos.kt`, añade antes de `InicioDto` y modifica `InicioDto`:

```kotlin
/** Un medidor de cumplimiento (mensual o anual) del panel de inicio (contrato §17). */
data class MedidorMetaDto(
    val tieneMeta: Boolean,
    val unidadesMeta: Int?,
    val unidadesLogradas: Int,
    val porcentaje: Int?,
)

/** Cumplimiento agregado del equipo (solo para jdv). */
data class MetaVentaAgregadoDto(
    val mensual: MedidorMetaDto,
    val anual: MedidorMetaDto,
)

/** Bloque de metas de venta del panel de inicio; null para roles sin meta (contrato §17). */
data class MetaVentaInicioDto(
    val mensual: MedidorMetaDto,
    val anual: MedidorMetaDto,
    val equipo: MetaVentaAgregadoDto?,
)
```

Y cambia la data class `InicioDto` de:

```kotlin
data class InicioDto(
    val tareasPendientes: List<TareaInicioDto>,
    val eventosPorSeguir: List<EventoSeguimientoDto>,
    val resumenPipeline: ResumenPipelineDto,
    val resumenProspeccion: ResumenProspeccionDto,
)
```

a:

```kotlin
data class InicioDto(
    val tareasPendientes: List<TareaInicioDto>,
    val eventosPorSeguir: List<EventoSeguimientoDto>,
    val resumenPipeline: ResumenPipelineDto,
    val resumenProspeccion: ResumenProspeccionDto,
    val metaVentas: MetaVentaInicioDto?,
)
```

- [ ] **Step 2: Wirear `InicioServiceImpl`**

En `src/main/kotlin/pe/quantum/crm/domain/inicio/InicioService.kt`, añade las dependencias nuevas al constructor y los imports:

```kotlin
import pe.quantum.crm.domain.empleados.EmpleadoService
import pe.quantum.crm.domain.empleados.RolEmpleado
import pe.quantum.crm.domain.inicio.dto.EtapaResumenDto
import pe.quantum.crm.domain.inicio.dto.InicioDto
import pe.quantum.crm.domain.inicio.dto.MedidorMetaDto
import pe.quantum.crm.domain.inicio.dto.MetaVentaAgregadoDto
import pe.quantum.crm.domain.inicio.dto.MetaVentaInicioDto
import pe.quantum.crm.domain.inicio.dto.ResumenPipelineDto
import pe.quantum.crm.domain.inicio.dto.TareaInicioDto
import pe.quantum.crm.domain.metasventa.MetaVentaService
import pe.quantum.crm.domain.metasventa.dto.MetaVentaResumen
import pe.quantum.crm.domain.prospeccion.ProspeccionService
import pe.quantum.crm.domain.tareas.TareaService
import pe.quantum.crm.domain.tareas.dto.TareaFiltros
import pe.quantum.crm.shared.enums.EstadoAccion
import pe.quantum.crm.shared.enums.EstadoOportunidad
import pe.quantum.crm.shared.security.UsuarioActual
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.math.roundToInt

@Service
class InicioService(
    private val tareaService: TareaService,
    private val prospeccionService: ProspeccionService,
    private val inicioDao: InicioDao,
    private val empleadoService: EmpleadoService,
    private val metaVentaService: MetaVentaService,
) {
    @Transactional(readOnly = true)
    fun panel(usuario: UsuarioActual): InicioDto =
        InicioDto(
            tareasPendientes = tareasPendientes(usuario),
            eventosPorSeguir = inicioDao.eventosPorSeguir(usuario.id.takeIf { usuario.visibilidadRestringida }),
            resumenPipeline = resumenPipeline(usuario),
            resumenProspeccion = prospeccionService.resumen(usuario),
            metaVentas = metaVentas(usuario),
        )
```

(mantén el resto del archivo: `tareasPendientes` y `resumenPipeline` no cambian.)

Añade estos métodos privados al final de la clase, antes del `companion object`:

```kotlin
    /** Solo vendedor/jdv tienen meta de venta; el resto no vende. */
    private fun metaVentas(usuario: UsuarioActual): MetaVentaInicioDto? {
        if (usuario.rol != "vendedor" && usuario.rol != "jdv") return null
        val hoy = LocalDate.now()
        val anio = hoy.year
        val mes = hoy.monthValue
        val metaPropia = metaVentaService.aprobadasPorEmpleadosYAnio(listOf(usuario.id), anio)[usuario.id]
        val logradasAnual = inicioDao.unidadesFacturadasPorVendedor(listOf(usuario.id), anio, null)[usuario.id] ?: 0
        val logradasMes = inicioDao.unidadesFacturadasPorVendedor(listOf(usuario.id), anio, mes)[usuario.id] ?: 0
        return MetaVentaInicioDto(
            mensual = medidor(metaPropia?.metaPorMes?.get(mes - 1), logradasMes),
            anual = medidor(metaPropia?.metaAnual, logradasAnual),
            equipo = if (usuario.rol == "jdv") equipoMetaVentas(anio, mes) else null,
        )
    }

    /** Agregado del equipo de vendedores activos: solo cuenta metas `aprobada`. */
    private fun equipoMetaVentas(
        anio: Int,
        mes: Int,
    ): MetaVentaAgregadoDto {
        val idsVendedores = empleadoService.idsActivosPorRol(RolEmpleado.vendedor)
        val metas: Map<Long, MetaVentaResumen> = metaVentaService.aprobadasPorEmpleadosYAnio(idsVendedores, anio)
        val logradasAnualPorVendedor = inicioDao.unidadesFacturadasPorVendedor(idsVendedores, anio, null)
        val logradasMesPorVendedor = inicioDao.unidadesFacturadasPorVendedor(idsVendedores, anio, mes)
        val metaAnualTotal = metas.values.sumOf { it.metaAnual }.takeIf { metas.isNotEmpty() }
        val metaMesTotal = metas.values.sumOf { it.metaPorMes[mes - 1] }.takeIf { metas.isNotEmpty() }
        val logradasAnualTotal = idsVendedores.sumOf { logradasAnualPorVendedor[it] ?: 0 }
        val logradasMesTotal = idsVendedores.sumOf { logradasMesPorVendedor[it] ?: 0 }
        return MetaVentaAgregadoDto(
            mensual = medidor(metaMesTotal, logradasMesTotal),
            anual = medidor(metaAnualTotal, logradasAnualTotal),
        )
    }

    private fun medidor(
        meta: Int?,
        logradas: Int,
    ): MedidorMetaDto =
        if (meta == null) {
            MedidorMetaDto(tieneMeta = false, unidadesMeta = null, unidadesLogradas = logradas, porcentaje = null)
        } else {
            MedidorMetaDto(
                tieneMeta = true,
                unidadesMeta = meta,
                unidadesLogradas = logradas,
                porcentaje = ((logradas * PORCENTAJE_BASE) / meta).roundToInt(),
            )
        }
```

Y en el `private companion object` existente (`LIMITE_TAREAS = 50`), añade la constante usada arriba:

```kotlin
    private companion object {
        const val LIMITE_TAREAS = 50
        const val PORCENTAJE_BASE = 100.0
    }
```

- [ ] **Step 3: Test unitario de `InicioServiceImpl`**

```kotlin
package pe.quantum.crm.domain.inicio

import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import pe.quantum.crm.domain.empleados.EmpleadoService
import pe.quantum.crm.domain.empleados.RolEmpleado
import pe.quantum.crm.domain.metasventa.MetaVentaService
import pe.quantum.crm.domain.metasventa.dto.MetaVentaResumen
import pe.quantum.crm.domain.prospeccion.ProspeccionService
import pe.quantum.crm.domain.prospeccion.dto.ResumenProspeccionDto
import pe.quantum.crm.domain.tareas.TareaService
import pe.quantum.crm.domain.tareas.dto.TareaFiltros
import pe.quantum.crm.shared.Paginacion
import pe.quantum.crm.shared.Paginado
import pe.quantum.crm.shared.security.UsuarioActual
import java.time.LocalDate

class InicioServiceImplTest {
    private val tareaService = mockk<TareaService>()
    private val prospeccionService = mockk<ProspeccionService>()
    private val inicioDao = mockk<InicioDao>()
    private val empleadoService = mockk<EmpleadoService>()
    private val metaVentaService = mockk<MetaVentaService>()
    private val service = InicioService(tareaService, prospeccionService, inicioDao, empleadoService, metaVentaService)

    private val anio = LocalDate.now().year
    private val mes = LocalDate.now().monthValue

    private fun stubsComunes(usuario: UsuarioActual) {
        every { tareaService.listar(any<TareaFiltros>(), usuario, any(), any(), any(), any()) } returns
            Paginado(emptyList(), Paginacion.meta(1, 50, 0))
        every { inicioDao.eventosPorSeguir(any()) } returns emptyList()
        every { inicioDao.resumenPipeline(any()) } returns emptyList()
        every { prospeccionService.resumen(usuario) } returns ResumenProspeccionDto(total = 0, listasParaConvertir = 0, requierenAtencion = 0)
    }

    @Test
    fun `vendedor sin meta aprobada ve el medidor en estado sin meta`() {
        val vendedor = UsuarioActual(id = 5, rol = "vendedor")
        stubsComunes(vendedor)
        every { metaVentaService.aprobadasPorEmpleadosYAnio(listOf(5L), anio) } returns emptyMap()
        every { inicioDao.unidadesFacturadasPorVendedor(listOf(5L), anio, null) } returns mapOf(5L to 3)
        every { inicioDao.unidadesFacturadasPorVendedor(listOf(5L), anio, mes) } returns mapOf(5L to 1)

        val panel = service.panel(vendedor)

        assertThat(panel.metaVentas?.mensual?.tieneMeta).isFalse()
        assertThat(panel.metaVentas?.mensual?.unidadesLogradas).isEqualTo(1)
        assertThat(panel.metaVentas?.anual?.unidadesLogradas).isEqualTo(3)
        assertThat(panel.metaVentas?.equipo).isNull()
    }

    @Test
    fun `vendedor con meta aprobada calcula el porcentaje del mes`() {
        val vendedor = UsuarioActual(id = 5, rol = "vendedor")
        stubsComunes(vendedor)
        every { metaVentaService.aprobadasPorEmpleadosYAnio(listOf(5L), anio) } returns
            mapOf(5L to MetaVentaResumen(idEmpleado = 5, anio = anio, metaAnual = 120, metaPorMes = List(12) { 10 }))
        every { inicioDao.unidadesFacturadasPorVendedor(listOf(5L), anio, null) } returns mapOf(5L to 60)
        every { inicioDao.unidadesFacturadasPorVendedor(listOf(5L), anio, mes) } returns mapOf(5L to 5)

        val panel = service.panel(vendedor)

        assertThat(panel.metaVentas?.mensual?.tieneMeta).isTrue()
        assertThat(panel.metaVentas?.mensual?.porcentaje).isEqualTo(50)
        assertThat(panel.metaVentas?.anual?.porcentaje).isEqualTo(50)
    }

    @Test
    fun `jdv ve su meta personal y el agregado del equipo`() {
        val jdv = UsuarioActual(id = 2, rol = "jdv")
        stubsComunes(jdv)
        every { metaVentaService.aprobadasPorEmpleadosYAnio(listOf(2L), anio) } returns emptyMap()
        every { inicioDao.unidadesFacturadasPorVendedor(listOf(2L), anio, null) } returns emptyMap()
        every { inicioDao.unidadesFacturadasPorVendedor(listOf(2L), anio, mes) } returns emptyMap()
        every { empleadoService.idsActivosPorRol(RolEmpleado.vendedor) } returns listOf(5L, 6L)
        every { metaVentaService.aprobadasPorEmpleadosYAnio(listOf(5L, 6L), anio) } returns
            mapOf(
                5L to MetaVentaResumen(idEmpleado = 5, anio = anio, metaAnual = 120, metaPorMes = List(12) { 10 }),
                6L to MetaVentaResumen(idEmpleado = 6, anio = anio, metaAnual = 60, metaPorMes = List(12) { 5 }),
            )
        every { inicioDao.unidadesFacturadasPorVendedor(listOf(5L, 6L), anio, null) } returns mapOf(5L to 60, 6L to 30)
        every { inicioDao.unidadesFacturadasPorVendedor(listOf(5L, 6L), anio, mes) } returns mapOf(5L to 5, 6L to 5)

        val panel = service.panel(jdv)

        assertThat(panel.metaVentas?.equipo?.anual?.unidadesMeta).isEqualTo(180)
        assertThat(panel.metaVentas?.equipo?.anual?.unidadesLogradas).isEqualTo(90)
        assertThat(panel.metaVentas?.equipo?.mensual?.unidadesMeta).isEqualTo(15)
        assertThat(panel.metaVentas?.equipo?.mensual?.unidadesLogradas).isEqualTo(10)
    }

    @Test
    fun `gerencia no ve bloque de metas de venta en inicio`() {
        val gerencia = UsuarioActual(id = 1, rol = "gerencia")
        stubsComunes(gerencia)

        val panel = service.panel(gerencia)

        assertThat(panel.metaVentas).isNull()
    }
}
```

- [ ] **Step 4: Ejecutar los tests**

Run: `./gradlew test --tests "pe.quantum.crm.domain.inicio.InicioServiceImplTest"`
Expected: BUILD SUCCESSFUL, todos los tests en verde.

- [ ] **Step 5: Ejecutar toda la suite local para detectar regresiones**

Run: `./gradlew test`
Expected: BUILD SUCCESSFUL. Si `InicioControllerWebMvcTest` u otro test existente construye `InicioDto` manualmente, falla por el nuevo campo `metaVentas` obligatorio — si eso ocurre, añade `metaVentas = null` (o el valor esperado) en ese fixture antes de continuar.

- [ ] **Step 6: Commit**

```bash
git add src/main/kotlin/pe/quantum/crm/domain/inicio/dto/InicioDtos.kt \
        src/main/kotlin/pe/quantum/crm/domain/inicio/InicioService.kt \
        src/test/kotlin/pe/quantum/crm/domain/inicio/InicioServiceImplTest.kt
git commit -m "feat(inicio): medidor de cumplimiento de metas de venta en el panel de inicio"
```

---

### Task 9: Documentación del contrato (`contrato_api.md`, `matriz_permisos.md`)

**Files:**
- Modify: `docs/contrato_api.md`
- Modify: `docs/matriz_permisos.md`

**Interfaces:**
- Ninguna (solo documentación; este repo es dueño del contrato, CLAUDE.md "Coordinación con el frontend").

- [ ] **Step 1: Añadir la sección 21 a `contrato_api.md`**

Inserta después de la sección `## 20. Solicitudes` (antes de `## Apéndice`, línea 1823) el siguiente bloque:

```markdown
## 21. Metas de venta

Meta de unidades vendidas (no monto) por vendedor/jdv, mensual (12 meses) + anual (calculada = suma de los meses). Una fila por `(id_empleado, año)`. El JDV propone el año completo de un vendedor (o el suyo propio); Gerencia aprueba, rechaza (con motivo) o modifica directamente (auto-aprobado). Las unidades de una oportunidad solo cuentan para el cumplimiento cuando está `facturado`; si se cancela o se elimina estando facturada, dejan de contar automáticamente (sin acción manual).

### POST /metas-venta
> Propone (jdv) o crea/sobreescribe directo y aprobado (gerencia/admin) la meta de un empleado para un año.

**Roles:** `jdv` `gerencia` `admin`

**Body:**
```json
{
  "id_empleado": 5,
  "anio": 2027,
  "meta_enero": 8, "meta_febrero": 8, "meta_marzo": 10, "meta_abril": 10,
  "meta_mayo": 10, "meta_junio": 12, "meta_julio": 12, "meta_agosto": 10,
  "meta_septiembre": 10, "meta_octubre": 10, "meta_noviembre": 12, "meta_diciembre": 12
}
```

**Respuesta 201:** el objeto meta (`id`, `id_empleado`, `empleado`, `anio`, `meta_enero`..`meta_diciembre`, `meta_anual`, `estado`, `propuesto_por`, `resolutor`, `motivo_rechazo`, `resolved_at`, `created_at`). `meta_anual` lo calcula el backend (suma de los 12 meses); nunca se acepta como input.

**Errores:** `400 VALIDACION` (falta algún mes o `id_empleado`/`anio`) · `403 PERMISO_INSUFICIENTE` (rol no puede proponer) · `404` no aplica (id_empleado inválido es `400 VALIDACION`, campo `id_empleado`) · `409 META_YA_EXISTE` (jdv sobre una fila `propuesta`/`aprobada` existente; usar `PATCH`).

---

### PATCH /metas-venta/:id
> Edita cualquier subconjunto de los 12 meses de una meta existente. Recalcula `meta_anual` y deja la meta `aprobada`.

**Roles:** `gerencia` `admin`

**Body:** cualquier subconjunto de `meta_enero`..`meta_diciembre`, por ejemplo `{ "meta_marzo": 15 }`.

**Errores:** `400 VALIDACION` · `403 PERMISO_INSUFICIENTE` · `404 NO_ENCONTRADO` · `409 META_RECHAZADA` (no se edita una rechazada; debe volver a proponerse).

---

### PATCH /metas-venta/:id/aprobar
> Aprueba una meta `propuesta` tal cual fue propuesta. Notifica al JDV proponente.

**Roles:** `gerencia` `admin`

**Body:** vacío.

**Errores:** `403 PERMISO_INSUFICIENTE` · `409 META_YA_RESUELTA`.

---

### PATCH /metas-venta/:id/rechazar
> Rechaza una meta `propuesta`. El motivo es obligatorio (ahí se especifica qué corregir). Notifica al JDV.

**Roles:** `gerencia` `admin`

**Body:** `{ "motivo": "Marzo está muy alto respecto al histórico del vendedor" }`

**Errores:** `400 VALIDACION` (falta motivo) · `403 PERMISO_INSUFICIENTE` · `409 META_YA_RESUELTA`.

---

### GET /metas-venta
> Lista metas, paginado estándar (§4). `admin`/`gerencia`/`jdv` ven todas (el jdv ve todo el equipo, incluida la suya); `vendedor`/`analista` solo las propias.

**Query params:** `id_empleado`, `anio`, `estado` (`propuesta|aprobada|rechazada`).

---

### GET /metas-venta/:id
> Detalle. `404` si no es visible para el usuario (IDOR).

---

**Nota — panel de Inicio:** `GET /inicio` (§17) incluye `meta_ventas` (null para roles distintos de `vendedor`/`jdv`) con el cumplimiento mensual/anual y, para `jdv`, el agregado del equipo. Ver §17.
```

- [ ] **Step 2: Actualizar el ejemplo de respuesta de `GET /inicio` (§17)**

En `docs/contrato_api.md`, dentro del bloque JSON de `## 17. Inicio` (línea ~1503, justo antes del cierre `}` del objeto `data`), añade el campo `meta_ventas` después de `resumen_prospeccion`:

```json
    "resumen_prospeccion": {
      "total": 4,
      "listas_para_convertir": 1,
      "requieren_atencion": 2
    },
    "meta_ventas": {
      "mensual": { "tiene_meta": true, "unidades_meta": 10, "unidades_logradas": 6, "porcentaje": 60 },
      "anual": { "tiene_meta": true, "unidades_meta": 120, "unidades_logradas": 60, "porcentaje": 50 },
      "equipo": null
    }
```

Y en **Notas** de esa misma sección, añade:

```markdown
- `meta_ventas` es `null` para roles distintos de `vendedor`/`jdv` (no venden). Cuando no hay meta `aprobada` para el periodo, `tiene_meta` es `false` y `unidades_meta`/`porcentaje` vienen `null` (`unidades_logradas` siempre se calcula). `equipo` solo viene con datos para `jdv` (agregado de vendedores activos); para `vendedor` es `null`.
```

- [ ] **Step 3: Añadir la sección 2.13 a `matriz_permisos.md`**

Inserta después de `### 2.12 Solicitudes de aprobación` (después de la línea `---` que sigue a las notas, ~línea 205) el siguiente bloque:

```markdown
### 2.13 Metas de venta

| Operación | admin | gerencia | jdv | vendedor | analista |
|---|---|---|---|---|---|
| Proponer meta de un vendedor o de sí mismo | — (crea directo) | — (crea directo) | ✓ | — | — |
| Crear/modificar meta directo (queda aprobada) | ✓ | ✓ | — | — | — |
| Aprobar / rechazar propuesta | ✓ | ✓ | — | — | — |
| Ver metas propias | ✓ | ✓ | ✓ | ✓ | — (no aplica, no tiene meta) |
| Ver metas del equipo (todos los vendedores) | ✓ | ✓ | ✓ | — | — |
| Ver medidor de cumplimiento en Inicio | — (no vende) | — (no vende) | ✓ (propio + equipo) | ✓ (propio) | — |

**Notas:**
- La meta es en unidades vendidas, no en monto. Un vendedor solo aparece en la tabla como `id_empleado`, nunca `gerencia`/`admin` (no tienen cartera propia, igual que en reasignación de vendedor).
- Las unidades de una oportunidad cuentan para el cumplimiento del vendedor únicamente mientras esté en estado `facturado` (`oportunidades.facturado_en`); al cancelarse (retroceder de estado) o eliminarse estando facturada, dejan de contar sin acción manual.

---
```

- [ ] **Step 4: Commit**

```bash
git add docs/contrato_api.md docs/matriz_permisos.md
git commit -m "docs: contrato de API y matriz de permisos de metas de venta"
```

---

### Task 10: Verificación final

**Files:** ninguno nuevo — solo verificación.

- [ ] **Step 1: Suite completa de tests locales**

Run: `./gradlew test`
Expected: BUILD SUCCESSFUL, 0 failures. (Excluye `@Tag("integration")` por configuración del proyecto — `MetaVentaRepositoryTest` y `SchemaMigrationIntegrationTest` quedan para CI, ver Global Constraints.)

- [ ] **Step 2: Formato y estilo**

Run: `./gradlew ktlintFormat`
Expected: BUILD SUCCESSFUL. Revisa el diff resultante (puede reordenar imports); si toca algo fuera de los archivos de esta feature, no lo incluyas en el commit.

Run: `./gradlew ktlintCheck`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Revisión de `git status` antes de un commit final si `ktlintFormat` tocó archivos**

```bash
git status
git diff --stat
```

Si `ktlintFormat` modificó archivos de esta feature, añádelos y comitea:

```bash
git add -u
git commit -m "style: ktlintFormat sobre los archivos de metas de venta"
```

Si tocó archivos fuera del alcance de esta feature (drift preexistente), no los incluyas — descártalos con `git checkout -- <archivo>` solo tras confirmar que no son cambios propios de esta rama.

- [ ] **Step 4: Resumen para el usuario**

Confirma explícitamente qué se verificó en local (`./gradlew test`, `ktlintCheck`) y qué queda pendiente de verificación en CI (migraciones V32-V34 contra Postgres real, `MetaVentaRepositoryTest`, `SchemaMigrationIntegrationTest`) — no afirmes que esas partes "pasan" sin haberlas corrido.
