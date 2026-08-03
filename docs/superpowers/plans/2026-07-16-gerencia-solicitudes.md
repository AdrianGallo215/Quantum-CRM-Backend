# Gerencia y Solicitudes de Aprobación — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Renombrar el rol `gerente` a `gerencia`, introducir el sistema de Solicitudes de aprobación (descuentos por encima del límite del rol y reasignación de clientes) con trazabilidad completa, y la Cartera Maestra de empresas reservadas a Gerencia.

**Architecture:** Nuevo módulo `domain/solicitudes` (Controller → Service → Repository, mismo patrón que el resto). La política de descuentos es un objeto puro en `shared`. La aprobación aplica el cambio en la misma transacción vía las interfaces públicas de `oportunidades`/`empresas` (regla 12 del monolito modular). La Cartera Maestra es un flag en `empresas` filtrado server-side.

**Tech Stack:** Kotlin 1.9, Spring Boot 3.2, Spring Data JPA (Specifications), Flyway (V25–V28), PostgreSQL 16, MockK + AssertJ (unit), SpringBootTest + MockMvc (web), Testcontainers (migraciones).

**Documentos fuente:** `docs/gerencia_solicitudes_modelo_datos.md` (schema exacto) y `docs/gerencia_contrato_frontend.md` (contrato de endpoints). Ante duda de forma de request/response, esos documentos mandan.

## Global Constraints

- TDD siempre: test que falla ANTES del código; ninguna tarea termina sin `./gradlew test` en verde.
- `spring.jpa.hibernate.ddl-auto=validate`: todo cambio de schema es una migración Flyway nueva (V25+). Nunca editar migraciones ya aplicadas.
- `monto_total` se calcula siempre con `MontoTotal.calcular(...)`; nunca se acepta como input.
- IDOR: recurso ajeno → 404 (`NoEncontradoException`), no 403.
- Inyección por constructor (`private val`); relaciones JPA `LAZY`; controllers nunca ven entidades, solo DTOs.
- Un módulo no toca tablas/entidades de otro: solo interfaces de servicio públicas (ArchUnit lo verifica).
- Enums Kotlin en minúscula snake_case para coincidir con los enums nativos de PostgreSQL (`@JdbcTypeCode(SqlTypes.NAMED_ENUM)`), con `@Suppress("ktlint:standard:enum-entry-name-case", "EnumNaming", "EnumEntryName")`.
- Envelope de respuesta único (`ApiResponse`); errores con código de `contrato_api.md §3` + los nuevos: `APROBACION_REQUERIDA` (422), `SOLICITUD_DUPLICADA`/`SOLICITUD_YA_RESUELTA`/`SOLICITUD_NO_APLICABLE`/`CARTERA_MAESTRA_CON_OPORTUNIDADES` (409).
- Antes de cada commit: `./gradlew ktlintFormat test` en verde.
- Límites de descuento: vendedor/analista ≤ 3%, jdv ≤ 7%, gerencia/admin sin límite. Solicitud 3<d≤7 de vendedor → aprueba jdv; d>7 (de vendedor o jdv) → aprueba gerencia.

---

### Task 1: Renombrar rol `gerente` → `gerencia` (migración V25 + código)

**Files:**
- Create: `src/main/resources/db/migration/V25__rename_rol_gerente_a_gerencia.sql`
- Modify: `src/main/kotlin/pe/quantum/crm/domain/empleados/RolEmpleado.kt`
- Modify: `src/main/kotlin/pe/quantum/crm/shared/security/UsuarioActual.kt`
- Modify: toda referencia `'gerente'` / `"gerente"` / `RolEmpleado.gerente` en `src/main` y `src/test` (buscar con `grep -rn "gerente" src/`)
- Test: `src/test/kotlin/pe/quantum/crm/shared/security/UsuarioActualTest.kt` (crear si no existe)

**Interfaces:**
- Consumes: nada nuevo.
- Produces: `RolEmpleado.gerencia`; en `UsuarioActual`: `esSupervisor` (admin|gerencia|jdv), `puedeValidarFacturado` (admin|gerencia|analista), `puedeVerCarteraMaestra: Boolean` (admin|gerencia), `puedeReasignarDirecto: Boolean` (admin|gerencia), `puedeAprobar(rolAprobador: String): Boolean` (admin o rol == rolAprobador). Tasks 3–12 dependen de estos nombres exactos.

- [ ] **Step 1: Escribir el test que falla**

```kotlin
// src/test/kotlin/pe/quantum/crm/shared/security/UsuarioActualTest.kt
package pe.quantum.crm.shared.security

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class UsuarioActualTest {
    @Test
    fun `gerencia es supervisor, valida facturado, ve cartera maestra y reasigna directo`() {
        val gerencia = UsuarioActual(id = 1, rol = "gerencia")
        assertThat(gerencia.esSupervisor).isTrue()
        assertThat(gerencia.puedeValidarFacturado).isTrue()
        assertThat(gerencia.puedeVerCarteraMaestra).isTrue()
        assertThat(gerencia.puedeReasignarDirecto).isTrue()
        assertThat(gerencia.visibilidadRestringida).isFalse()
    }

    @Test
    fun `jdv no ve cartera maestra ni reasigna directo, pero sigue siendo supervisor`() {
        val jdv = UsuarioActual(id = 2, rol = "jdv")
        assertThat(jdv.esSupervisor).isTrue()
        assertThat(jdv.puedeVerCarteraMaestra).isFalse()
        assertThat(jdv.puedeReasignarDirecto).isFalse()
    }

    @Test
    fun `puedeAprobar - admin aprueba ambas bandejas, cada rol solo la suya`() {
        assertThat(UsuarioActual(1, "admin").puedeAprobar("jdv")).isTrue()
        assertThat(UsuarioActual(1, "admin").puedeAprobar("gerencia")).isTrue()
        assertThat(UsuarioActual(2, "gerencia").puedeAprobar("gerencia")).isTrue()
        assertThat(UsuarioActual(2, "gerencia").puedeAprobar("jdv")).isFalse()
        assertThat(UsuarioActual(3, "jdv").puedeAprobar("jdv")).isTrue()
        assertThat(UsuarioActual(3, "jdv").puedeAprobar("gerencia")).isFalse()
        assertThat(UsuarioActual(4, "vendedor").puedeAprobar("jdv")).isFalse()
    }

    @Test
    fun `el rol gerente ya no existe - gerencia lo reemplaza en todos los checks`() {
        val exGerente = UsuarioActual(id = 1, rol = "gerente")
        // Un token viejo con "gerente" queda sin privilegios: debe re-loguear.
        assertThat(exGerente.esSupervisor).isFalse()
        assertThat(exGerente.puedeValidarFacturado).isFalse()
    }
}
```

- [ ] **Step 2: Correr el test y verificar que falla**

Run: `./gradlew test --tests "pe.quantum.crm.shared.security.UsuarioActualTest"`
Expected: FAIL — `unresolved reference: puedeVerCarteraMaestra` (error de compilación cuenta como test rojo).

- [ ] **Step 3: Escribir la migración V25**

```sql
-- src/main/resources/db/migration/V25__rename_rol_gerente_a_gerencia.sql
-- =============================================================================
-- V25 — El rol 'gerente' pasa a llamarse 'gerencia'.
-- Los empleados existentes migran automáticamente (mismo valor, renombrado).
-- Los JWT vigentes emitidos con rol=gerente quedan sin privilegios: re-login.
-- =============================================================================
ALTER TYPE rol_empleado RENAME VALUE 'gerente' TO 'gerencia';
```

- [ ] **Step 4: Actualizar RolEmpleado y UsuarioActual**

En `RolEmpleado.kt`, reemplazar la entrada `gerente,` por `gerencia,`.

`UsuarioActual.kt` — cuerpo nuevo del data class (mantener `UsuarioActualProvider` intacto):

```kotlin
data class UsuarioActual(
    val id: Long,
    val rol: String,
) {
    /** Roles que ven todo el pipeline y la cartera del equipo (matriz_permisos.md). */
    val esSupervisor: Boolean
        get() = rol == "admin" || rol == "gerencia" || rol == "jdv"

    /** Roles que pueden confirmar el paso a `facturado` (matriz_permisos.md). */
    val puedeValidarFacturado: Boolean
        get() = rol == "admin" || rol == "gerencia" || rol == "analista"

    /** vendedor/analista solo ven sus propios registros (contrato_api.md §5). */
    val visibilidadRestringida: Boolean
        get() = !esSupervisor

    /** Cartera Maestra: exclusiva de gerencia y admin (gerencia_contrato_frontend.md §1). */
    val puedeVerCarteraMaestra: Boolean
        get() = rol == "admin" || rol == "gerencia"

    /** Reasignación directa de empresas; el jdv ahora requiere solicitud aprobada. */
    val puedeReasignarDirecto: Boolean
        get() = rol == "admin" || rol == "gerencia"

    /** true si este usuario puede resolver una solicitud dirigida a `rolAprobador`. */
    fun puedeAprobar(rolAprobador: String): Boolean = rol == "admin" || rol == rolAprobador
}
```

- [ ] **Step 5: Reemplazo global de `gerente` → `gerencia`**

Run: `grep -rln "gerente" src/ | grep -v "Gerente"` y en cada archivo reemplazar el valor de rol: `RolEmpleado.gerente` → `RolEmpleado.gerencia`, `"gerente"` → `"gerencia"`, `hasAnyRole('admin', 'gerente', ...)` → `hasAnyRole('admin', 'gerencia', ...)`. Incluye como mínimo `EmpleadoController.kt`, `EmpresaController.kt` (`@PreAuthorize`), tests de empleados/auth y cualquier seed de test. NO tocar textos de UI como el cargo `"Gerente"` en datos de prueba de contactos (es un cargo, no un rol).

- [ ] **Step 6: Correr toda la suite**

Run: `./gradlew ktlintFormat test`
Expected: PASS completo (los tests de Testcontainers validan que V25 aplica limpio).

- [ ] **Step 7: Commit**

```bash
git add -A src/main src/test
git commit -m "feat(roles): renombrar rol gerente a gerencia (V25) y helpers de permisos nuevos"
```

---

### Task 2: Migraciones V26–V28 + entidad `Solicitud` + repository

**Files:**
- Create: `src/main/resources/db/migration/V26__create_solicitudes.sql` (copiar EXACTO de `docs/gerencia_solicitudes_modelo_datos.md §3`, incluida la columna `entidad_descripcion`)
- Create: `src/main/resources/db/migration/V27__empresas_cartera_maestra.sql` (EXACTO de `§4`)
- Create: `src/main/resources/db/migration/V28__notificaciones_solicitudes.sql` (EXACTO de `§5`)
- Create: `src/main/kotlin/pe/quantum/crm/shared/enums/SolicitudEnums.kt`
- Create: `src/main/kotlin/pe/quantum/crm/domain/solicitudes/Solicitud.kt`
- Create: `src/main/kotlin/pe/quantum/crm/domain/solicitudes/SolicitudRepository.kt`
- Modify: `src/main/kotlin/pe/quantum/crm/domain/notificaciones/NotificacionEnums.kt`
- Test: `src/test/kotlin/pe/quantum/crm/domain/solicitudes/SolicitudRepositoryTest.kt`

**Interfaces:**
- Consumes: helpers de Task 1.
- Produces: enums `TipoSolicitud { descuento, reasignacion_cliente }`, `EstadoSolicitud { pendiente, aprobada, denegada }`, `AprobadorSolicitud { jdv, gerencia }`, `EntidadSolicitud { oportunidad, empresa }` (paquete `pe.quantum.crm.shared.enums`); entidad `Solicitud`; `SolicitudRepository : JpaRepository<Solicitud, Long>, JpaSpecificationExecutor<Solicitud>` con `existsByTipoAndEntidadTipoAndEntidadIdAndEstado(...)` y `findParaResolver(id)` (lock pesimista). `TipoNotificacion.{solicitud_creada, solicitud_aprobada, solicitud_denegada}` y `EntidadNotificacion.solicitud`.

- [ ] **Step 1: Escribir el test de repositorio que falla** (usa la base de tests con Testcontainers existente en `src/test/kotlin/pe/quantum/crm/support` — mismo patrón que otros tests de repositorio del repo; si no existe ninguno, usar `@SpringBootTest` completo como `CrmApplicationTests`)

```kotlin
// src/test/kotlin/pe/quantum/crm/domain/solicitudes/SolicitudRepositoryTest.kt
package pe.quantum.crm.domain.solicitudes

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.dao.DataIntegrityViolationException
import pe.quantum.crm.shared.enums.AprobadorSolicitud
import pe.quantum.crm.shared.enums.EntidadSolicitud
import pe.quantum.crm.shared.enums.EstadoSolicitud
import pe.quantum.crm.shared.enums.TipoSolicitud
import java.math.BigDecimal

@SpringBootTest
class SolicitudRepositoryTest
    @Autowired
    constructor(
        private val repository: SolicitudRepository,
    ) {
        // id_solicitante=1 es el admin seed de V19.
        private fun solicitudDescuento(entidadId: Long = 999) =
            Solicitud(
                tipo = TipoSolicitud.descuento,
                rolAprobador = AprobadorSolicitud.jdv,
                idSolicitante = 1,
                entidadTipo = EntidadSolicitud.oportunidad,
                entidadId = entidadId,
                entidadDescripcion = "Empresa X — Oportunidad #$entidadId",
                motivo = "Cliente recurrente",
                dctoSolicitado = BigDecimal("5.00"),
            )

        @Test
        fun `persiste y recupera una solicitud pendiente de descuento`() {
            val guardada = repository.save(solicitudDescuento())
            val leida = repository.findById(requireNotNull(guardada.id)).orElseThrow()
            assertThat(leida.estado).isEqualTo(EstadoSolicitud.pendiente)
            assertThat(leida.dctoSolicitado).isEqualByComparingTo(BigDecimal("5.00"))
            repository.delete(leida)
        }

        @Test
        fun `el indice parcial rechaza dos pendientes del mismo tipo sobre la misma entidad`() {
            val primera = repository.save(solicitudDescuento(entidadId = 777))
            assertThatThrownBy {
                repository.saveAndFlush(solicitudDescuento(entidadId = 777))
            }.isInstanceOf(DataIntegrityViolationException::class.java)
            repository.delete(primera)
        }

        @Test
        fun `existsBy detecta la pendiente duplicada`() {
            val guardada = repository.save(solicitudDescuento(entidadId = 555))
            assertThat(
                repository.existsByTipoAndEntidadTipoAndEntidadIdAndEstado(
                    TipoSolicitud.descuento,
                    EntidadSolicitud.oportunidad,
                    555,
                    EstadoSolicitud.pendiente,
                ),
            ).isTrue()
            repository.delete(guardada)
        }
    }
```

- [ ] **Step 2: Correr y verificar que falla**

Run: `./gradlew test --tests "pe.quantum.crm.domain.solicitudes.SolicitudRepositoryTest"`
Expected: FAIL — no compila (no existen `Solicitud` ni los enums).

- [ ] **Step 3: Crear las tres migraciones** copiando el SQL textual de `docs/gerencia_solicitudes_modelo_datos.md` §3.1, §3.2 (V26), §4 (V27) y §5 (V28).

- [ ] **Step 4: Crear enums, entidad y repository**

```kotlin
// src/main/kotlin/pe/quantum/crm/shared/enums/SolicitudEnums.kt
package pe.quantum.crm.shared.enums

/**
 * Enums del sistema de solicitudes de aprobacion (migracion V26). En minuscula
 * para coincidir con las etiquetas de los enums nativos de PostgreSQL.
 */
@Suppress("ktlint:standard:enum-entry-name-case", "EnumNaming", "EnumEntryName")
enum class TipoSolicitud {
    descuento,
    reasignacion_cliente,
}

@Suppress("ktlint:standard:enum-entry-name-case", "EnumNaming", "EnumEntryName")
enum class EstadoSolicitud {
    pendiente,
    aprobada,
    denegada,
}

@Suppress("ktlint:standard:enum-entry-name-case", "EnumNaming", "EnumEntryName")
enum class AprobadorSolicitud {
    jdv,
    gerencia,
}

@Suppress("ktlint:standard:enum-entry-name-case", "EnumNaming", "EnumEntryName")
enum class EntidadSolicitud {
    oportunidad,
    empresa,
}
```

```kotlin
// src/main/kotlin/pe/quantum/crm/domain/solicitudes/Solicitud.kt
package pe.quantum.crm.domain.solicitudes

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import pe.quantum.crm.shared.enums.AprobadorSolicitud
import pe.quantum.crm.shared.enums.EntidadSolicitud
import pe.quantum.crm.shared.enums.EstadoSolicitud
import pe.quantum.crm.shared.enums.TipoSolicitud
import java.math.BigDecimal
import java.time.LocalDateTime

/**
 * Solicitud de aprobacion (tabla `solicitudes`, migracion V26). Es a la vez la
 * capa intermedia de permisos y el registro de trazabilidad: nunca se borra y
 * su unica transicion es pendiente -> aprobada|denegada
 * (docs/gerencia_solicitudes_modelo_datos.md).
 */
@Entity
@Table(name = "solicitudes")
@Suppress("LongParameterList") // Una entidad JPA refleja las columnas de su tabla.
class Solicitud(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(nullable = false, columnDefinition = "tipo_solicitud_enum")
    val tipo: TipoSolicitud,
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(nullable = false, columnDefinition = "estado_solicitud_enum")
    var estado: EstadoSolicitud = EstadoSolicitud.pendiente,
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(name = "rol_aprobador", nullable = false, columnDefinition = "aprobador_solicitud_enum")
    val rolAprobador: AprobadorSolicitud,
    @Column(name = "id_solicitante", nullable = false)
    val idSolicitante: Long,
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(name = "entidad_tipo", nullable = false, columnDefinition = "entidad_solicitud_enum")
    val entidadTipo: EntidadSolicitud,
    @Column(name = "entidad_id", nullable = false)
    val entidadId: Long,
    @Column(name = "entidad_descripcion", nullable = false)
    val entidadDescripcion: String,
    @Column(nullable = false)
    val motivo: String,
    @Column(name = "dcto_solicitado")
    val dctoSolicitado: BigDecimal? = null,
    @Column(name = "id_vendedor_nuevo")
    val idVendedorNuevo: Long? = null,
    @Column(name = "id_resolutor")
    var idResolutor: Long? = null,
    @Column(name = "motivo_resolucion")
    var motivoResolucion: String? = null,
    @Column(name = "resolved_at")
    var resolvedAt: LocalDateTime? = null,
    @Column(name = "created_at", nullable = false)
    val createdAt: LocalDateTime = LocalDateTime.now(),
    @Column(name = "updated_at", nullable = false)
    var updatedAt: LocalDateTime = LocalDateTime.now(),
)
```

```kotlin
// src/main/kotlin/pe/quantum/crm/domain/solicitudes/SolicitudRepository.kt
package pe.quantum.crm.domain.solicitudes

import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.JpaSpecificationExecutor
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import pe.quantum.crm.shared.enums.EntidadSolicitud
import pe.quantum.crm.shared.enums.EstadoSolicitud
import pe.quantum.crm.shared.enums.TipoSolicitud

interface SolicitudRepository :
    JpaRepository<Solicitud, Long>,
    JpaSpecificationExecutor<Solicitud> {
    fun existsByTipoAndEntidadTipoAndEntidadIdAndEstado(
        tipo: TipoSolicitud,
        entidadTipo: EntidadSolicitud,
        entidadId: Long,
        estado: EstadoSolicitud,
    ): Boolean

    /** SELECT ... FOR UPDATE: dos aprobadores en paralelo no resuelven dos veces. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from Solicitud s where s.id = :id")
    fun findParaResolver(id: Long): Solicitud?
}
```

En `NotificacionEnums.kt` agregar al final de `TipoNotificacion`: `solicitud_creada,`, `solicitud_aprobada,`, `solicitud_denegada,` y a `EntidadNotificacion`: `solicitud,`.

- [ ] **Step 5: Correr el test y la suite completa**

Run: `./gradlew test --tests "pe.quantum.crm.domain.solicitudes.SolicitudRepositoryTest"` y luego `./gradlew test`
Expected: PASS (V26–V28 aplican, el entity valida contra el schema).

- [ ] **Step 6: Commit**

```bash
git add src/main/resources/db/migration/V26__create_solicitudes.sql src/main/resources/db/migration/V27__empresas_cartera_maestra.sql src/main/resources/db/migration/V28__notificaciones_solicitudes.sql src/main/kotlin/pe/quantum/crm/shared/enums/SolicitudEnums.kt src/main/kotlin/pe/quantum/crm/domain/solicitudes src/main/kotlin/pe/quantum/crm/domain/notificaciones/NotificacionEnums.kt src/test/kotlin/pe/quantum/crm/domain/solicitudes
git commit -m "feat(solicitudes): migraciones V26-V28, entidad Solicitud y repository"
```

---

### Task 3: Política de descuentos + enforcement en oportunidades

**Files:**
- Create: `src/main/kotlin/pe/quantum/crm/shared/PoliticaDescuento.kt`
- Modify: `src/main/kotlin/pe/quantum/crm/shared/exception/NegocioExceptions.kt`
- Modify: `src/main/kotlin/pe/quantum/crm/domain/oportunidades/OportunidadServiceImpl.kt` (métodos `crear` y `actualizar`)
- Test: `src/test/kotlin/pe/quantum/crm/shared/PoliticaDescuentoTest.kt`
- Test: `src/test/kotlin/pe/quantum/crm/domain/oportunidades/OportunidadServiceImplTest.kt` (agregar casos)

**Interfaces:**
- Consumes: `AprobadorSolicitud` (Task 2).
- Produces: `PoliticaDescuento.limitePara(rol: String): BigDecimal?`, `PoliticaDescuento.excedeLimite(rol: String, dcto: BigDecimal?): Boolean`, `PoliticaDescuento.aprobadorPara(rol: String, dcto: BigDecimal): AprobadorSolicitud?`; excepción `AprobacionRequeridaException` (HTTP 422, code `APROBACION_REQUERIDA`, field `dcto`). Task 5 usa `aprobadorPara`; Task 12 no toca esto.

- [ ] **Step 1: Test de la política (falla)**

```kotlin
// src/test/kotlin/pe/quantum/crm/shared/PoliticaDescuentoTest.kt
package pe.quantum.crm.shared

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import pe.quantum.crm.shared.enums.AprobadorSolicitud
import java.math.BigDecimal

class PoliticaDescuentoTest {
    @Test
    fun `limites por rol - vendedor y analista 3, jdv 7, gerencia y admin sin limite`() {
        assertThat(PoliticaDescuento.limitePara("vendedor")).isEqualByComparingTo(BigDecimal(3))
        assertThat(PoliticaDescuento.limitePara("analista")).isEqualByComparingTo(BigDecimal(3))
        assertThat(PoliticaDescuento.limitePara("jdv")).isEqualByComparingTo(BigDecimal(7))
        assertThat(PoliticaDescuento.limitePara("gerencia")).isNull()
        assertThat(PoliticaDescuento.limitePara("admin")).isNull()
    }

    @Test
    fun `excedeLimite - bordes exactos aplican directo`() {
        assertThat(PoliticaDescuento.excedeLimite("vendedor", BigDecimal("3.00"))).isFalse()
        assertThat(PoliticaDescuento.excedeLimite("vendedor", BigDecimal("3.01"))).isTrue()
        assertThat(PoliticaDescuento.excedeLimite("jdv", BigDecimal("7.00"))).isFalse()
        assertThat(PoliticaDescuento.excedeLimite("jdv", BigDecimal("7.50"))).isTrue()
        assertThat(PoliticaDescuento.excedeLimite("gerencia", BigDecimal("50"))).isFalse()
        assertThat(PoliticaDescuento.excedeLimite("vendedor", null)).isFalse()
    }

    @Test
    fun `aprobadorPara - vendedor 3-7 va al jdv, mas de 7 a gerencia, jdv siempre a gerencia`() {
        assertThat(PoliticaDescuento.aprobadorPara("vendedor", BigDecimal("5"))).isEqualTo(AprobadorSolicitud.jdv)
        assertThat(PoliticaDescuento.aprobadorPara("vendedor", BigDecimal("7.00"))).isEqualTo(AprobadorSolicitud.jdv)
        assertThat(PoliticaDescuento.aprobadorPara("vendedor", BigDecimal("8"))).isEqualTo(AprobadorSolicitud.gerencia)
        assertThat(PoliticaDescuento.aprobadorPara("analista", BigDecimal("5"))).isEqualTo(AprobadorSolicitud.jdv)
        assertThat(PoliticaDescuento.aprobadorPara("jdv", BigDecimal("9"))).isEqualTo(AprobadorSolicitud.gerencia)
        // Dentro del limite propio o rol sin limite: no corresponde solicitud.
        assertThat(PoliticaDescuento.aprobadorPara("vendedor", BigDecimal("2"))).isNull()
        assertThat(PoliticaDescuento.aprobadorPara("jdv", BigDecimal("5"))).isNull()
        assertThat(PoliticaDescuento.aprobadorPara("gerencia", BigDecimal("90"))).isNull()
    }
}
```

- [ ] **Step 2: Correr y ver el rojo**

Run: `./gradlew test --tests "pe.quantum.crm.shared.PoliticaDescuentoTest"`
Expected: FAIL — `unresolved reference: PoliticaDescuento`.

- [ ] **Step 3: Implementar la política y la excepción**

```kotlin
// src/main/kotlin/pe/quantum/crm/shared/PoliticaDescuento.kt
package pe.quantum.crm.shared

import pe.quantum.crm.shared.enums.AprobadorSolicitud
import java.math.BigDecimal

/**
 * Limites de descuento por rol y derivacion del aprobador
 * (docs/gerencia_contrato_frontend.md §2): vendedor/analista hasta 3%, jdv
 * hasta 7%, gerencia/admin sin limite. Por encima del limite propio el cambio
 * requiere una solicitud aprobada.
 */
object PoliticaDescuento {
    val LIMITE_VENDEDOR: BigDecimal = BigDecimal(3)
    val LIMITE_JDV: BigDecimal = BigDecimal(7)

    /** Limite directo del rol; null = sin limite. */
    fun limitePara(rol: String): BigDecimal? =
        when (rol) {
            "vendedor", "analista" -> LIMITE_VENDEDOR
            "jdv" -> LIMITE_JDV
            else -> null
        }

    fun excedeLimite(
        rol: String,
        dcto: BigDecimal?,
    ): Boolean {
        if (dcto == null) return false
        val limite = limitePara(rol) ?: return false
        return dcto > limite
    }

    /** Quien aprueba un descuento fuera de limite; null si no requiere solicitud. */
    fun aprobadorPara(
        rol: String,
        dcto: BigDecimal,
    ): AprobadorSolicitud? =
        when {
            !excedeLimite(rol, dcto) -> null
            dcto <= LIMITE_JDV && (rol == "vendedor" || rol == "analista") -> AprobadorSolicitud.jdv
            else -> AprobadorSolicitud.gerencia
        }
}
```

Agregar a `NegocioExceptions.kt`:

```kotlin
/** El cambio supera el limite del rol y requiere una solicitud aprobada (422). */
class AprobacionRequeridaException(
    message: String,
) : ApiException(
        code = "APROBACION_REQUERIDA",
        message = message,
        status = HttpStatus.UNPROCESSABLE_ENTITY,
        field = "dcto",
    )
```

- [ ] **Step 4: Test del enforcement en el servicio (falla)** — agregar a `OportunidadServiceImplTest.kt` (seguir el patrón MockK del archivo; el helper de construcción de oportunidad/request ya existe ahí):

```kotlin
@Test
fun `actualizar con dcto sobre el limite del rol lanza APROBACION_REQUERIDA sin guardar`() {
    val vendedor = UsuarioActual(id = 5, rol = "vendedor")
    val entidad = oportunidad(idVendedor = 5) // helper existente del test
    every { oportunidadRepository.findById(1) } returns Optional.of(entidad)

    assertThatThrownBy {
        service.actualizar(1, ActualizarOportunidadRequest(dcto = BigDecimal("5.00")), vendedor)
    }.isInstanceOf(AprobacionRequeridaException::class.java)

    verify(exactly = 0) { oportunidadRepository.save(any()) }
}

@Test
fun `actualizar con dcto dentro del limite guarda normal`() {
    val vendedor = UsuarioActual(id = 5, rol = "vendedor")
    val entidad = oportunidad(idVendedor = 5)
    every { oportunidadRepository.findById(1) } returns Optional.of(entidad)
    every { oportunidadRepository.save(any()) } answers { firstArg() }
    // ... mocks de toDto que el test existente ya arma para `actualizar`

    val dto = service.actualizar(1, ActualizarOportunidadRequest(dcto = BigDecimal("3.00")), vendedor)
    assertThat(dto.dcto).isEqualTo("3.00")
}

@Test
fun `crear con dcto sobre el limite lanza APROBACION_REQUERIDA`() {
    val vendedor = UsuarioActual(id = 5, rol = "vendedor")
    every { empresaService.vinculoVisible(10, vendedor) } returns
        EmpresaVinculo(id = 10, razonSocial = "ABC", idVendedor = 5, estadoCartera = "prospeccion")

    assertThatThrownBy {
        service.crear(crearRequest(idEmpresa = 10, dcto = BigDecimal("4.00")), vendedor)
    }.isInstanceOf(AprobacionRequeridaException::class.java)
}
```

- [ ] **Step 5: Implementar el enforcement**

En `OportunidadServiceImpl.crear`, inmediatamente después de `val empresa = empresaService.vinculoVisible(...)`:

```kotlin
validarLimiteDescuento(request.dcto, usuario)
```

En `OportunidadServiceImpl.actualizar`, inmediatamente después del guard de `montoTotal`:

```kotlin
validarLimiteDescuento(request.dcto, usuario)
```

Y el privado (junto a los demás privados del archivo):

```kotlin
/** Descuento sobre el limite del rol: 422, el cambio requiere solicitud (frontend §3.1). */
private fun validarLimiteDescuento(
    dcto: BigDecimal?,
    usuario: UsuarioActual,
) {
    if (PoliticaDescuento.excedeLimite(usuario.rol, dcto)) {
        val limite = requireNotNull(PoliticaDescuento.limitePara(usuario.rol))
        throw AprobacionRequeridaException(
            "Un descuento de ${dcto!!.toPlainString()}% supera tu límite de ${limite.toPlainString()}%; requiere aprobación",
        )
    }
}
```

Imports nuevos: `pe.quantum.crm.shared.PoliticaDescuento`, `pe.quantum.crm.shared.exception.AprobacionRequeridaException`, `java.math.BigDecimal`.

- [ ] **Step 6: Correr los tests**

Run: `./gradlew test --tests "pe.quantum.crm.shared.PoliticaDescuentoTest" --tests "pe.quantum.crm.domain.oportunidades.OportunidadServiceImplTest"`
Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add src/main/kotlin/pe/quantum/crm/shared src/main/kotlin/pe/quantum/crm/domain/oportunidades/OportunidadServiceImpl.kt src/test
git commit -m "feat(oportunidades): limite de descuento por rol con 422 APROBACION_REQUERIDA"
```

---

### Task 4: Endurecer reasignación de vendedor + destinos asignables

**Files:**
- Modify: `src/main/kotlin/pe/quantum/crm/domain/empleados/EmpleadoService.kt`
- Modify: `src/main/kotlin/pe/quantum/crm/domain/empleados/EmpleadoServiceImpl.kt`
- Modify: `src/main/kotlin/pe/quantum/crm/domain/empleados/EmpleadoRepository.kt`
- Modify: `src/main/kotlin/pe/quantum/crm/domain/empresas/EmpresaServiceImpl.kt` (`reasignarVendedor`)
- Modify: `src/main/kotlin/pe/quantum/crm/domain/empresas/EmpresaController.kt` (`@PreAuthorize` de `PATCH /{id}/vendedor`)
- Test: `src/test/kotlin/pe/quantum/crm/domain/empresas/EmpresaServiceImplTest.kt` (o crear si el repo solo tiene tests web de empresas)

**Interfaces:**
- Consumes: `puedeReasignarDirecto` (Task 1).
- Produces: en `EmpleadoService`: `fun esAsignableComoVendedor(id: Long): Boolean` (activo y rol `vendedor` o `jdv`) y `fun idsActivosPorRol(rol: RolEmpleado): List<Long>`. Tasks 5, 7, 8, 11 y 12 los usan con esas firmas exactas.

- [ ] **Step 1: Tests que fallan**

```kotlin
// En EmpresaServiceImplTest.kt (patrón MockK igual a ContactoServiceImplTest).
// Si el archivo no existe, crearlo con estos mocks y helper:
//   private val empresaRepository = mockk<EmpresaRepository>()
//   private val empleadoService = mockk<EmpleadoService>()
//   private val notificacionService = mockk<NotificacionService>(relaxed = true)
//   private val eventPublisher = mockk<ApplicationEventPublisher>(relaxed = true)
//   private val service = EmpresaServiceImpl(empresaRepository, empleadoService, notificacionService, eventPublisher)
//   private fun empresa(id: Long = 1) =
//       Empresa(id = id, ruc = "20100000001", razonSocial = "Empresa $id",
//               createdAt = LocalDateTime.now(), createdBy = 1,
//               updatedAt = LocalDateTime.now(), updatedBy = 1)
@Test
fun `reasignarVendedor por jdv lanza PERMISO_INSUFICIENTE - debe usar solicitud`() {
    val jdv = UsuarioActual(id = 2, rol = "jdv")
    assertThatThrownBy { service.reasignarVendedor(10, 8, jdv) }
        .isInstanceOf(PermisoInsuficienteException::class.java)
}

@Test
fun `reasignarVendedor rechaza destino que no es vendedor ni jdv`() {
    val gerencia = UsuarioActual(id = 1, rol = "gerencia")
    every { empresaRepository.findById(10) } returns Optional.of(empresa(id = 10))
    every { empleadoService.esAsignableComoVendedor(99) } returns false
    assertThatThrownBy { service.reasignarVendedor(10, 99, gerencia) }
        .isInstanceOf(ValidacionException::class.java)
}
```

```kotlin
// En el test de EmpleadoServiceImpl existente (o nuevo EmpleadoServiceImplTest):
@Test
fun `esAsignableComoVendedor - solo vendedor o jdv activos`() {
    every { empleadoRepository.findById(8) } returns Optional.of(empleado(rol = RolEmpleado.vendedor, activo = true))
    assertThat(service.esAsignableComoVendedor(8)).isTrue()
    every { empleadoRepository.findById(9) } returns Optional.of(empleado(rol = RolEmpleado.gerencia, activo = true))
    assertThat(service.esAsignableComoVendedor(9)).isFalse()
    every { empleadoRepository.findById(10) } returns Optional.of(empleado(rol = RolEmpleado.vendedor, activo = false))
    assertThat(service.esAsignableComoVendedor(10)).isFalse()
    every { empleadoRepository.findById(11) } returns Optional.empty()
    assertThat(service.esAsignableComoVendedor(11)).isFalse()
}
```

- [ ] **Step 2: Verificar rojo**

Run: `./gradlew test --tests "*EmpresaServiceImplTest" --tests "*EmpleadoServiceImplTest"`
Expected: FAIL (métodos inexistentes).

- [ ] **Step 3: Implementar**

`EmpleadoService.kt` — agregar a la interfaz:

```kotlin
/** true si el empleado esta activo y su rol puede tener cartera (vendedor o jdv). */
fun esAsignableComoVendedor(id: Long): Boolean

/** Ids de empleados activos con el rol dado (destinatarios de notificaciones). */
fun idsActivosPorRol(rol: RolEmpleado): List<Long>
```

`EmpleadoServiceImpl.kt` — implementación (ajustar al estilo del archivo):

```kotlin
@Transactional(readOnly = true)
override fun esAsignableComoVendedor(id: Long): Boolean =
    empleadoRepository
        .findById(id)
        .map { it.activo && (it.rol == RolEmpleado.vendedor || it.rol == RolEmpleado.jdv) }
        .orElse(false)

@Transactional(readOnly = true)
override fun idsActivosPorRol(rol: RolEmpleado): List<Long> =
    empleadoRepository.findByRolAndActivoTrue(rol).map { requireNotNull(it.id) }
```

`EmpleadoRepository.kt` — agregar: `fun findByRolAndActivoTrue(rol: RolEmpleado): List<Empleado>`.

`EmpresaServiceImpl.reasignarVendedor` — al inicio del método:

```kotlin
if (!usuario.puedeReasignarDirecto) {
    throw PermisoInsuficienteException("La reasignación directa es exclusiva de gerencia; envía una solicitud")
}
if (!empleadoService.esAsignableComoVendedor(idVendedor)) {
    throw ValidacionException("El destino debe ser un vendedor o jdv activo", field = "id_vendedor")
}
```

(y borrar el check anterior `existeActivo` que queda redundante). `EmpresaController` — el `@PreAuthorize` de `reasignarVendedor` queda `hasAnyRole('admin', 'gerencia')`.

- [ ] **Step 4: Correr suite y arreglar tests rotos** — los tests existentes que reasignaban como jdv deben cambiar a `gerencia` o convertirse en el caso 403.

Run: `./gradlew test`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add -A src/main src/test
git commit -m "feat(empresas): reasignacion directa solo gerencia/admin; destino debe ser vendedor o jdv activo"
```

---

### Task 5: Módulo solicitudes — DTOs y `crear`

**Files:**
- Create: `src/main/kotlin/pe/quantum/crm/domain/solicitudes/dto/SolicitudDtos.kt`
- Create: `src/main/kotlin/pe/quantum/crm/domain/solicitudes/SolicitudService.kt`
- Create: `src/main/kotlin/pe/quantum/crm/domain/solicitudes/SolicitudServiceImpl.kt`
- Test: `src/test/kotlin/pe/quantum/crm/domain/solicitudes/SolicitudServiceImplTest.kt`

**Interfaces:**
- Consumes: `SolicitudRepository`, `PoliticaDescuento.aprobadorPara`, `OportunidadService.vinculoVisible(id, usuario): OportunidadVinculo`, `EmpresaService.vinculoVisible(id, usuario): EmpresaVinculo` (`razonSocial`), `EmpleadoService.{esAsignableComoVendedor, idsActivosPorRol, resumenPorIds}`, `NotificacionService.notificar`, `RolEmpleado`.
- Produces: `SolicitudService` con `crear(request, usuario): SolicitudDto`, y los DTOs. Tasks 6–9 implementan el resto de la interfaz declarada aquí.

- [ ] **Step 1: Crear DTOs e interfaz (compilables, sin lógica)**

```kotlin
// src/main/kotlin/pe/quantum/crm/domain/solicitudes/dto/SolicitudDtos.kt
package pe.quantum.crm.domain.solicitudes.dto

import com.fasterxml.jackson.databind.PropertyNamingStrategies
import com.fasterxml.jackson.databind.annotation.JsonNaming
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import pe.quantum.crm.domain.empleados.dto.EmpleadoResumen
import pe.quantum.crm.shared.enums.EntidadSolicitud
import pe.quantum.crm.shared.enums.TipoSolicitud
import java.math.BigDecimal
import java.time.LocalDateTime

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
data class CrearSolicitudRequest(
    @field:NotNull(message = "tipo es obligatorio")
    val tipo: TipoSolicitud?,
    @field:NotNull(message = "entidad_tipo es obligatorio")
    val entidadTipo: EntidadSolicitud?,
    @field:NotNull(message = "entidad_id es obligatorio")
    val entidadId: Long?,
    @field:NotBlank(message = "motivo es obligatorio")
    val motivo: String?,
    val dctoSolicitado: BigDecimal? = null,
    val idVendedorNuevo: Long? = null,
)

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
data class DenegarSolicitudRequest(
    @field:NotBlank(message = "motivo es obligatorio")
    val motivo: String?,
)

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
data class SolicitudDto(
    val id: Long,
    val tipo: String,
    val estado: String,
    val rolAprobador: String,
    val entidadTipo: String,
    val entidadId: Long,
    val entidadDescripcion: String,
    val dctoSolicitado: String?,
    val idVendedorNuevo: Long?,
    val vendedorNuevo: EmpleadoResumen?,
    val motivo: String,
    val solicitante: EmpleadoResumen?,
    val resolutor: EmpleadoResumen?,
    val motivoResolucion: String?,
    val resolvedAt: LocalDateTime?,
    val createdAt: LocalDateTime,
)

data class SolicitudFiltros(
    val estado: String? = null,
    val tipo: String? = null,
    val mias: Boolean = false,
)
```

> Nota: si el resto de DTOs del repo no usa `@JsonNaming` (revisar `OportunidadDtos.kt` y copiar el mecanismo que use el proyecto para snake_case — puede ser configuración global de Jackson), seguir el patrón del proyecto y omitir la anotación.

```kotlin
// src/main/kotlin/pe/quantum/crm/domain/solicitudes/SolicitudService.kt
package pe.quantum.crm.domain.solicitudes

import pe.quantum.crm.domain.solicitudes.dto.CrearSolicitudRequest
import pe.quantum.crm.domain.solicitudes.dto.SolicitudDto
import pe.quantum.crm.domain.solicitudes.dto.SolicitudFiltros
import pe.quantum.crm.shared.Paginado
import pe.quantum.crm.shared.security.UsuarioActual

/**
 * Interfaz publica del modulo solicitudes: capa intermedia de aprobacion
 * (docs/gerencia_solicitudes_modelo_datos.md, gerencia_contrato_frontend.md §4).
 */
interface SolicitudService {
    /** Valida tipo/payload/visibilidad, deriva el aprobador y notifica. 409 si ya hay una pendiente. */
    fun crear(
        request: CrearSolicitudRequest,
        usuario: UsuarioActual,
    ): SolicitudDto

    /** Visibilidad: admin todo; gerencia su bandeja; jdv su bandeja + propias; resto solo propias. */
    fun listar(
        filtros: SolicitudFiltros,
        usuario: UsuarioActual,
        page: Int?,
        perPage: Int?,
        sort: String?,
        dir: String?,
    ): Paginado<SolicitudDto>

    fun detalle(
        id: Long,
        usuario: UsuarioActual,
    ): SolicitudDto

    /** Aplica el cambio y marca aprobada, en la misma transaccion. Notifica al solicitante. */
    fun aprobar(
        id: Long,
        usuario: UsuarioActual,
    ): SolicitudDto

    /** Deniega con motivo obligatorio. Notifica al solicitante. */
    fun denegar(
        id: Long,
        motivo: String,
        usuario: UsuarioActual,
    ): SolicitudDto
}
```

- [ ] **Step 2: Test de `crear` (falla)**

```kotlin
// src/test/kotlin/pe/quantum/crm/domain/solicitudes/SolicitudServiceImplTest.kt
package pe.quantum.crm.domain.solicitudes

import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import pe.quantum.crm.domain.empleados.EmpleadoService
import pe.quantum.crm.domain.empleados.RolEmpleado
import pe.quantum.crm.domain.empresas.EmpresaService
import pe.quantum.crm.domain.empresas.dto.EmpresaVinculo
import pe.quantum.crm.domain.notificaciones.NotificacionService
import pe.quantum.crm.domain.notificaciones.TipoNotificacion
import pe.quantum.crm.domain.oportunidades.OportunidadService
import pe.quantum.crm.domain.oportunidades.dto.OportunidadVinculo
import pe.quantum.crm.domain.solicitudes.dto.CrearSolicitudRequest
import pe.quantum.crm.shared.enums.EntidadSolicitud
import pe.quantum.crm.shared.enums.EstadoSolicitud
import pe.quantum.crm.shared.enums.TipoSolicitud
import pe.quantum.crm.shared.exception.ConflictoException
import pe.quantum.crm.shared.exception.PermisoInsuficienteException
import pe.quantum.crm.shared.exception.ValidacionException
import pe.quantum.crm.shared.security.UsuarioActual
import java.math.BigDecimal

class SolicitudServiceImplTest {
    private val solicitudRepository = mockk<SolicitudRepository>(relaxed = false)
    private val oportunidadService = mockk<OportunidadService>()
    private val empresaService = mockk<EmpresaService>()
    private val empleadoService = mockk<EmpleadoService>()
    private val notificacionService = mockk<NotificacionService>(relaxed = true)
    private val service =
        SolicitudServiceImpl(solicitudRepository, oportunidadService, empresaService, empleadoService, notificacionService)

    private val vendedor = UsuarioActual(id = 5, rol = "vendedor")
    private val jdv = UsuarioActual(id = 2, rol = "jdv")

    private fun requestDescuento(dcto: String = "5.00") =
        CrearSolicitudRequest(
            tipo = TipoSolicitud.descuento,
            entidadTipo = EntidadSolicitud.oportunidad,
            entidadId = 45,
            motivo = "Cliente frecuente",
            dctoSolicitado = BigDecimal(dcto),
        )

    @Test
    fun `crear descuento 5 pct de vendedor deriva aprobador jdv y notifica a los jdv activos`() {
        every { oportunidadService.vinculoVisible(45, vendedor) } returns
            OportunidadVinculo(id = 45, idEmpresa = 10, idVendedor = 5, estado = "evaluacion_calidda")
        every { empresaService.resumenPorIds(listOf(10)) } returns
            mapOf(10L to empresaResumen(10, "Transportes Lima Norte S.A.C.")) // helper: armar segun el DTO real
        every {
            solicitudRepository.existsByTipoAndEntidadTipoAndEntidadIdAndEstado(
                TipoSolicitud.descuento, EntidadSolicitud.oportunidad, 45, EstadoSolicitud.pendiente,
            )
        } returns false
        val guardada = slot<Solicitud>()
        every { solicitudRepository.save(capture(guardada)) } answers { firstArg() }
        every { empleadoService.idsActivosPorRol(RolEmpleado.jdv) } returns listOf(2)
        every { empleadoService.resumenPorIds(any()) } returns emptyMap()

        val dto = service.crear(requestDescuento(), vendedor)

        assertThat(dto.rolAprobador).isEqualTo("jdv")
        assertThat(guardada.captured.entidadDescripcion).contains("Transportes Lima Norte")
        verify {
            notificacionService.notificar(
                destinatarios = setOf(2L),
                idActor = 5,
                tipo = TipoNotificacion.solicitud_creada,
                mensaje = any(),
                entidadTipo = pe.quantum.crm.domain.notificaciones.EntidadNotificacion.solicitud,
                entidadId = any(),
            )
        }
    }

    @Test
    fun `crear descuento dentro del limite propio es VALIDACION - no necesita solicitud`() {
        every { oportunidadService.vinculoVisible(45, vendedor) } returns
            OportunidadVinculo(id = 45, idEmpresa = 10, idVendedor = 5, estado = "evaluacion_calidda")
        assertThatThrownBy { service.crear(requestDescuento("2.00"), vendedor) }
            .isInstanceOf(ValidacionException::class.java)
    }

    @Test
    fun `crear con pendiente duplicada es 409 SOLICITUD_DUPLICADA`() {
        every { oportunidadService.vinculoVisible(45, vendedor) } returns
            OportunidadVinculo(id = 45, idEmpresa = 10, idVendedor = 5, estado = "evaluacion_calidda")
        every { empresaService.resumenPorIds(listOf(10)) } returns mapOf(10L to empresaResumen(10, "ABC"))
        every {
            solicitudRepository.existsByTipoAndEntidadTipoAndEntidadIdAndEstado(any(), any(), any(), any())
        } returns true
        assertThatThrownBy { service.crear(requestDescuento(), vendedor) }
            .isInstanceOf(ConflictoException::class.java)
            .hasMessageContaining("pendiente")
    }

    @Test
    fun `vendedor no puede solicitar reasignacion de cliente`() {
        val request =
            CrearSolicitudRequest(
                tipo = TipoSolicitud.reasignacion_cliente,
                entidadTipo = EntidadSolicitud.empresa,
                entidadId = 12,
                motivo = "x",
                idVendedorNuevo = 8,
            )
        assertThatThrownBy { service.crear(request, vendedor) }
            .isInstanceOf(PermisoInsuficienteException::class.java)
    }

    @Test
    fun `jdv solicita reasignacion - aprobador gerencia y valida destino asignable`() {
        every { empresaService.vinculoVisible(12, jdv) } returns
            EmpresaVinculo(id = 12, razonSocial = "ABC S.A.", idVendedor = 5, estadoCartera = "cliente")
        every { empleadoService.esAsignableComoVendedor(8) } returns true
        every {
            solicitudRepository.existsByTipoAndEntidadTipoAndEntidadIdAndEstado(any(), any(), any(), any())
        } returns false
        every { solicitudRepository.save(any()) } answers { firstArg() }
        every { empleadoService.idsActivosPorRol(RolEmpleado.gerencia) } returns listOf(1)
        every { empleadoService.resumenPorIds(any()) } returns emptyMap()

        val dto =
            service.crear(
                CrearSolicitudRequest(
                    tipo = TipoSolicitud.reasignacion_cliente,
                    entidadTipo = EntidadSolicitud.empresa,
                    entidadId = 12,
                    motivo = "Vacaciones largas del vendedor actual",
                    idVendedorNuevo = 8,
                ),
                jdv,
            )
        assertThat(dto.rolAprobador).isEqualTo("gerencia")
        assertThat(dto.entidadDescripcion).contains("ABC S.A.")
    }
}
```

(El helper `empresaResumen(id, razonSocial)` se arma con el constructor real de `EmpresaResumen` — ver `domain/empresas/dto`.)

- [ ] **Step 3: Verificar rojo**

Run: `./gradlew test --tests "pe.quantum.crm.domain.solicitudes.SolicitudServiceImplTest"`
Expected: FAIL — `SolicitudServiceImpl` no existe.

- [ ] **Step 4: Implementar `SolicitudServiceImpl` con `crear` (el resto de métodos como `TODO()` temporal SOLO si el mismo commit no incluye Tasks 6–8; preferible implementar `crear` + esqueletos privados y completar en las tasks siguientes lanzando `NotImplementedError` — pero NO mergear así)**

```kotlin
// src/main/kotlin/pe/quantum/crm/domain/solicitudes/SolicitudServiceImpl.kt
package pe.quantum.crm.domain.solicitudes

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pe.quantum.crm.domain.empleados.EmpleadoService
import pe.quantum.crm.domain.empleados.RolEmpleado
import pe.quantum.crm.domain.empresas.EmpresaService
import pe.quantum.crm.domain.notificaciones.EntidadNotificacion
import pe.quantum.crm.domain.notificaciones.NotificacionService
import pe.quantum.crm.domain.notificaciones.TipoNotificacion
import pe.quantum.crm.domain.oportunidades.OportunidadService
import pe.quantum.crm.domain.solicitudes.dto.CrearSolicitudRequest
import pe.quantum.crm.domain.solicitudes.dto.SolicitudDto
import pe.quantum.crm.domain.solicitudes.dto.SolicitudFiltros
import pe.quantum.crm.shared.Paginado
import pe.quantum.crm.shared.PoliticaDescuento
import pe.quantum.crm.shared.enums.AprobadorSolicitud
import pe.quantum.crm.shared.enums.EntidadSolicitud
import pe.quantum.crm.shared.enums.EstadoSolicitud
import pe.quantum.crm.shared.enums.TipoSolicitud
import pe.quantum.crm.shared.exception.ConflictoException
import pe.quantum.crm.shared.exception.PermisoInsuficienteException
import pe.quantum.crm.shared.exception.ValidacionException
import pe.quantum.crm.shared.security.UsuarioActual

@Service
class SolicitudServiceImpl(
    private val solicitudRepository: SolicitudRepository,
    private val oportunidadService: OportunidadService,
    private val empresaService: EmpresaService,
    private val empleadoService: EmpleadoService,
    private val notificacionService: NotificacionService,
) : SolicitudService {
    @Transactional
    override fun crear(
        request: CrearSolicitudRequest,
        usuario: UsuarioActual,
    ): SolicitudDto {
        val tipo = requireNotNull(request.tipo)
        val entidadId = requireNotNull(request.entidadId)
        val (rolAprobador, entidadTipo, descripcion) =
            when (tipo) {
                TipoSolicitud.descuento -> validarDescuento(request, usuario)
                TipoSolicitud.reasignacion_cliente -> validarReasignacion(request, usuario)
            }
        if (solicitudRepository.existsByTipoAndEntidadTipoAndEntidadIdAndEstado(
                tipo, entidadTipo, entidadId, EstadoSolicitud.pendiente,
            )
        ) {
            throw ConflictoException("SOLICITUD_DUPLICADA", "Ya existe una solicitud pendiente de este tipo sobre esta entidad")
        }
        val solicitud =
            solicitudRepository.save(
                Solicitud(
                    tipo = tipo,
                    rolAprobador = rolAprobador,
                    idSolicitante = usuario.id,
                    entidadTipo = entidadTipo,
                    entidadId = entidadId,
                    entidadDescripcion = descripcion,
                    motivo = requireNotNull(request.motivo),
                    dctoSolicitado = request.dctoSolicitado,
                    idVendedorNuevo = request.idVendedorNuevo,
                ),
            )
        notificarCreada(solicitud, usuario)
        return toDto(listOf(solicitud)).first()
    }

    // ── privados de creacion ───────────────────────────────────

    private data class Contexto(
        val rolAprobador: AprobadorSolicitud,
        val entidadTipo: EntidadSolicitud,
        val descripcion: String,
    )

    /** Descuento: solo sobre oportunidades visibles, y solo si excede el limite propio. */
    private fun validarDescuento(
        request: CrearSolicitudRequest,
        usuario: UsuarioActual,
    ): Contexto {
        if (request.entidadTipo != EntidadSolicitud.oportunidad) {
            throw ValidacionException("Una solicitud de descuento aplica sobre una oportunidad", field = "entidad_tipo")
        }
        val dcto =
            request.dctoSolicitado
                ?: throw ValidacionException("dcto_solicitado es obligatorio", field = "dcto_solicitado")
        // IDOR: si no es visible para el solicitante, 404 desde vinculoVisible.
        val oportunidad = oportunidadService.vinculoVisible(requireNotNull(request.entidadId), usuario)
        val aprobador =
            PoliticaDescuento.aprobadorPara(usuario.rol, dcto)
                ?: throw ValidacionException(
                    "Un descuento de ${dcto.toPlainString()}% está dentro de tu límite: aplícalo directamente",
                    field = "dcto_solicitado",
                )
        val empresa = empresaService.resumenPorIds(listOf(oportunidad.idEmpresa))[oportunidad.idEmpresa]
        val descripcion = "${empresa?.razonSocial ?: "Empresa"} — Oportunidad #${oportunidad.id}"
        return Contexto(aprobador, EntidadSolicitud.oportunidad, descripcion)
    }

    /** Reasignacion: solo la solicita el jdv y siempre la aprueba gerencia. */
    private fun validarReasignacion(
        request: CrearSolicitudRequest,
        usuario: UsuarioActual,
    ): Contexto {
        if (usuario.rol != "jdv") {
            throw PermisoInsuficienteException("Solo el jefe de ventas puede solicitar reasignar un cliente")
        }
        if (request.entidadTipo != EntidadSolicitud.empresa) {
            throw ValidacionException("Una reasignación de cliente aplica sobre una empresa", field = "entidad_tipo")
        }
        val destino =
            request.idVendedorNuevo
                ?: throw ValidacionException("id_vendedor_nuevo es obligatorio", field = "id_vendedor_nuevo")
        if (!empleadoService.esAsignableComoVendedor(destino)) {
            throw ValidacionException("El destino debe ser un vendedor o jdv activo", field = "id_vendedor_nuevo")
        }
        val empresa = empresaService.vinculoVisible(requireNotNull(request.entidadId), usuario)
        return Contexto(AprobadorSolicitud.gerencia, EntidadSolicitud.empresa, empresa.razonSocial)
    }

    private fun notificarCreada(
        solicitud: Solicitud,
        usuario: UsuarioActual,
    ) {
        val rol = if (solicitud.rolAprobador == AprobadorSolicitud.jdv) RolEmpleado.jdv else RolEmpleado.gerencia
        val solicitante = empleadoService.resumenPorIds(listOf(usuario.id))[usuario.id]
        val nombre = solicitante?.let { "${it.nombres} ${it.apellidos}" } ?: "Un usuario"
        notificacionService.notificar(
            destinatarios = empleadoService.idsActivosPorRol(rol).toSet(),
            idActor = usuario.id,
            tipo = TipoNotificacion.solicitud_creada,
            mensaje = "$nombre envió una solicitud de ${etiquetaTipo(solicitud.tipo)} sobre ${solicitud.entidadDescripcion}",
            entidadTipo = EntidadNotificacion.solicitud,
            entidadId = requireNotNull(solicitud.id),
        )
    }

    private fun etiquetaTipo(tipo: TipoSolicitud): String =
        when (tipo) {
            TipoSolicitud.descuento -> "descuento"
            TipoSolicitud.reasignacion_cliente -> "reasignación de cliente"
        }

    /** Ensambla DTOs por lotes (sin N+1), mismo patron que OportunidadServiceImpl.toDtos. */
    internal fun toDto(solicitudes: List<Solicitud>): List<SolicitudDto> {
        if (solicitudes.isEmpty()) return emptyList()
        val idsEmpleados =
            (
                solicitudes.map { it.idSolicitante } +
                    solicitudes.mapNotNull { it.idResolutor } +
                    solicitudes.mapNotNull { it.idVendedorNuevo }
            ).distinct()
        val empleados = empleadoService.resumenPorIds(idsEmpleados)
        return solicitudes.map { s ->
            SolicitudDto(
                id = requireNotNull(s.id),
                tipo = s.tipo.name,
                estado = s.estado.name,
                rolAprobador = s.rolAprobador.name,
                entidadTipo = s.entidadTipo.name,
                entidadId = s.entidadId,
                entidadDescripcion = s.entidadDescripcion,
                dctoSolicitado = s.dctoSolicitado?.toPlainString(),
                idVendedorNuevo = s.idVendedorNuevo,
                vendedorNuevo = s.idVendedorNuevo?.let { empleados[it] },
                motivo = s.motivo,
                solicitante = empleados[s.idSolicitante],
                resolutor = s.idResolutor?.let { empleados[it] },
                motivoResolucion = s.motivoResolucion,
                resolvedAt = s.resolvedAt,
                createdAt = s.createdAt,
            )
        }
    }

    // listar/detalle: Task 6 · aprobar: Tasks 7-8 · denegar: Task 8
    override fun listar(
        filtros: SolicitudFiltros,
        usuario: UsuarioActual,
        page: Int?,
        perPage: Int?,
        sort: String?,
        dir: String?,
    ): Paginado<SolicitudDto> = throw NotImplementedError("Task 6")

    override fun detalle(
        id: Long,
        usuario: UsuarioActual,
    ): SolicitudDto = throw NotImplementedError("Task 6")

    override fun aprobar(
        id: Long,
        usuario: UsuarioActual,
    ): SolicitudDto = throw NotImplementedError("Task 7")

    override fun denegar(
        id: Long,
        motivo: String,
        usuario: UsuarioActual,
    ): SolicitudDto = throw NotImplementedError("Task 8")
}
```

- [ ] **Step 5: Correr los tests**

Run: `./gradlew test --tests "pe.quantum.crm.domain.solicitudes.SolicitudServiceImplTest"`
Expected: PASS los casos de `crear`.

- [ ] **Step 6: Commit**

```bash
git add src/main/kotlin/pe/quantum/crm/domain/solicitudes src/test/kotlin/pe/quantum/crm/domain/solicitudes
git commit -m "feat(solicitudes): crear solicitud con derivacion de aprobador, dedupe y notificacion"
```

---

### Task 6: `listar` y `detalle` con visibilidad por rol

**Files:**
- Modify: `src/main/kotlin/pe/quantum/crm/domain/solicitudes/SolicitudServiceImpl.kt`
- Test: `src/test/kotlin/pe/quantum/crm/domain/solicitudes/SolicitudServiceImplTest.kt`

**Interfaces:**
- Consumes: `Paginacion.pageRequest/meta` (shared), `SolicitudRepository.findAll(Specification, Pageable)`.
- Produces: `listar` y `detalle` reales (firmas de Task 5).

- [ ] **Step 1: Tests que fallan** (agregar al test de Task 5)

```kotlin
@Test
fun `listar como gerencia filtra su bandeja - rol_aprobador gerencia`() {
    val gerencia = UsuarioActual(id = 1, rol = "gerencia")
    val specSlot = slot<org.springframework.data.jpa.domain.Specification<Solicitud>>()
    every { solicitudRepository.findAll(capture(specSlot), any<org.springframework.data.domain.PageRequest>()) } returns
        org.springframework.data.domain.PageImpl(emptyList())
    every { empleadoService.resumenPorIds(any()) } returns emptyMap()

    service.listar(SolicitudFiltros(), gerencia, null, null, null, null)
    // La spec no es inspeccionable directamente; el criterio real se valida en el WebMvc/integration test.
    verify { solicitudRepository.findAll(any<org.springframework.data.jpa.domain.Specification<Solicitud>>(), any<org.springframework.data.domain.PageRequest>()) }
}

@Test
fun `detalle de solicitud ajena para vendedor es 404`() {
    val otra =
        Solicitud(
            id = 9, tipo = TipoSolicitud.descuento, rolAprobador = pe.quantum.crm.shared.enums.AprobadorSolicitud.jdv,
            idSolicitante = 99, entidadTipo = EntidadSolicitud.oportunidad, entidadId = 45,
            entidadDescripcion = "X — Oportunidad #45", motivo = "m", dctoSolicitado = BigDecimal("5"),
        )
    every { solicitudRepository.findById(9) } returns java.util.Optional.of(otra)
    assertThatThrownBy { service.detalle(9, vendedor) }
        .isInstanceOf(pe.quantum.crm.shared.exception.NoEncontradoException::class.java)
}

@Test
fun `detalle propio para vendedor si es visible`() {
    val propia =
        Solicitud(
            id = 9, tipo = TipoSolicitud.descuento, rolAprobador = pe.quantum.crm.shared.enums.AprobadorSolicitud.jdv,
            idSolicitante = 5, entidadTipo = EntidadSolicitud.oportunidad, entidadId = 45,
            entidadDescripcion = "X — Oportunidad #45", motivo = "m", dctoSolicitado = BigDecimal("5"),
        )
    every { solicitudRepository.findById(9) } returns java.util.Optional.of(propia)
    every { empleadoService.resumenPorIds(any()) } returns emptyMap()
    assertThat(service.detalle(9, vendedor).id).isEqualTo(9)
}
```

- [ ] **Step 2: Rojo**: `./gradlew test --tests "*SolicitudServiceImplTest"` → FAIL (`NotImplementedError`).

- [ ] **Step 3: Implementar** — reemplazar los `NotImplementedError` de `listar`/`detalle`:

```kotlin
@Transactional(readOnly = true)
override fun listar(
    filtros: SolicitudFiltros,
    usuario: UsuarioActual,
    page: Int?,
    perPage: Int?,
    sort: String?,
    dir: String?,
): Paginado<SolicitudDto> {
    val pageRequest = Paginacion.pageRequest(page, perPage, sort, dir, defaultSort = "createdAt")
    val resultado = solicitudRepository.findAll(especificacion(filtros, usuario), pageRequest)
    val meta = Paginacion.meta(pageRequest.pageNumber + 1, pageRequest.pageSize, resultado.totalElements)
    return Paginado(toDto(resultado.content), meta)
}

@Transactional(readOnly = true)
override fun detalle(
    id: Long,
    usuario: UsuarioActual,
): SolicitudDto = toDto(listOf(visible(id, usuario))).first()

// ── privados de visibilidad ────────────────────────────────

/** IDOR: solicitud fuera del alcance del rol → 404, no 403. */
private fun visible(
    id: Long,
    usuario: UsuarioActual,
): Solicitud {
    val solicitud =
        solicitudRepository.findById(id).orElseThrow { NoEncontradoException("La solicitud no existe") }
    val alcanzable =
        when (usuario.rol) {
            "admin" -> true
            "gerencia" -> solicitud.rolAprobador == AprobadorSolicitud.gerencia
            "jdv" -> solicitud.rolAprobador == AprobadorSolicitud.jdv || solicitud.idSolicitante == usuario.id
            else -> solicitud.idSolicitante == usuario.id
        }
    if (!alcanzable) {
        throw NoEncontradoException("La solicitud no existe")
    }
    return solicitud
}

private fun especificacion(
    filtros: SolicitudFiltros,
    usuario: UsuarioActual,
): Specification<Solicitud> =
    Specification { root, _, cb ->
        val predicados = mutableListOf<Predicate>()
        when {
            filtros.mias || usuario.rol == "vendedor" || usuario.rol == "analista" ->
                predicados += cb.equal(root.get<Long>("idSolicitante"), usuario.id)
            usuario.rol == "gerencia" ->
                predicados += cb.equal(root.get<AprobadorSolicitud>("rolAprobador"), AprobadorSolicitud.gerencia)
            usuario.rol == "jdv" ->
                predicados +=
                    cb.or(
                        cb.equal(root.get<AprobadorSolicitud>("rolAprobador"), AprobadorSolicitud.jdv),
                        cb.equal(root.get<Long>("idSolicitante"), usuario.id),
                    )
            // admin: sin predicado de alcance.
        }
        filtros.estado?.let { estado ->
            runCatching { EstadoSolicitud.valueOf(estado) }.getOrNull()?.let {
                predicados += cb.equal(root.get<EstadoSolicitud>("estado"), it)
            }
        }
        filtros.tipo?.let { tipo ->
            runCatching { TipoSolicitud.valueOf(tipo) }.getOrNull()?.let {
                predicados += cb.equal(root.get<TipoSolicitud>("tipo"), it)
            }
        }
        cb.and(*predicados.toTypedArray())
    }
```

Imports nuevos: `jakarta.persistence.criteria.Predicate`, `org.springframework.data.jpa.domain.Specification`, `pe.quantum.crm.shared.Paginacion`, `pe.quantum.crm.shared.exception.NoEncontradoException`.

- [ ] **Step 4: Verde**: `./gradlew test --tests "*SolicitudServiceImplTest"` → PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/pe/quantum/crm/domain/solicitudes src/test/kotlin/pe/quantum/crm/domain/solicitudes
git commit -m "feat(solicitudes): listado y detalle con visibilidad por rol (bandejas gerencia/jdv)"
```

---

### Task 7: Aprobar solicitud de descuento (aplica el cambio)

**Files:**
- Modify: `src/main/kotlin/pe/quantum/crm/domain/oportunidades/OportunidadService.kt`
- Modify: `src/main/kotlin/pe/quantum/crm/domain/oportunidades/OportunidadServiceImpl.kt`
- Modify: `src/main/kotlin/pe/quantum/crm/domain/solicitudes/SolicitudServiceImpl.kt` (`aprobar`)
- Test: `src/test/kotlin/pe/quantum/crm/domain/oportunidades/OportunidadServiceImplTest.kt` y `SolicitudServiceImplTest.kt`

**Interfaces:**
- Consumes: `puedeAprobar` (Task 1), `findParaResolver` (Task 2).
- Produces: en `OportunidadService`: `fun aplicarDescuentoAprobado(id: Long, dcto: BigDecimal, idAprobador: Long)` — setea `dcto`, recalcula `monto_total`, `updatedBy = idAprobador`; lanza `ConflictoException("SOLICITUD_NO_APLICABLE", ...)` si la oportunidad no existe o está en `cerrado`/`facturado`. `SolicitudService.aprobar` operativo para tipo `descuento`.

- [ ] **Step 1: Tests que fallan**

En `OportunidadServiceImplTest`:

```kotlin
@Test
fun `aplicarDescuentoAprobado setea dcto y recalcula monto_total`() {
    val entidad = oportunidad(idVendedor = 5).apply {
        cantidad = 2
        precioUnitario = BigDecimal("100.00")
    }
    every { oportunidadRepository.findById(1) } returns Optional.of(entidad)
    every { oportunidadRepository.save(any()) } answers { firstArg() }

    service.aplicarDescuentoAprobado(1, BigDecimal("5.00"), idAprobador = 2)

    assertThat(entidad.dcto).isEqualByComparingTo(BigDecimal("5.00"))
    assertThat(entidad.montoTotal).isEqualByComparingTo(BigDecimal("190.00")) // 2 × 100 × 0.95
    assertThat(entidad.updatedBy).isEqualTo(2)
}

@Test
fun `aplicarDescuentoAprobado sobre oportunidad cerrada es SOLICITUD_NO_APLICABLE`() {
    val cerrada = oportunidad(idVendedor = 5).apply { estado = EstadoOportunidad.cerrado }
    every { oportunidadRepository.findById(1) } returns Optional.of(cerrada)
    assertThatThrownBy { service.aplicarDescuentoAprobado(1, BigDecimal("5.00"), 2) }
        .isInstanceOf(ConflictoException::class.java)
}
```

En `SolicitudServiceImplTest`, agregar primero estos helpers al final de la clase (los usan Tasks 7 y 8):

```kotlin
private fun solicitudDescuentoPendiente(rolAprobador: AprobadorSolicitud) =
    Solicitud(
        id = 9,
        tipo = TipoSolicitud.descuento,
        rolAprobador = rolAprobador,
        idSolicitante = 5,
        entidadTipo = EntidadSolicitud.oportunidad,
        entidadId = 45,
        entidadDescripcion = "Transportes Lima Norte S.A.C. — Oportunidad #45",
        motivo = "Cliente frecuente",
        dctoSolicitado = BigDecimal("8.00"),
    )

private fun solicitudReasignacionPendiente(
    entidadId: Long,
    idVendedorNuevo: Long,
) = Solicitud(
    id = 9,
    tipo = TipoSolicitud.reasignacion_cliente,
    rolAprobador = AprobadorSolicitud.gerencia,
    idSolicitante = 2,
    entidadTipo = EntidadSolicitud.empresa,
    entidadId = entidadId,
    entidadDescripcion = "ABC S.A.",
    motivo = "Vacaciones largas",
    idVendedorNuevo = idVendedorNuevo,
)
```

Y los tests:

```kotlin
@Test
fun `aprobar descuento aplica el cambio, marca aprobada y notifica al solicitante`() {
    val gerencia = UsuarioActual(id = 1, rol = "gerencia")
    val pendiente = solicitudDescuentoPendiente(rolAprobador = AprobadorSolicitud.gerencia)
    every { solicitudRepository.findParaResolver(9) } returns pendiente
    every { oportunidadService.aplicarDescuentoAprobado(45, BigDecimal("8.00"), 1) } just Runs
    every { solicitudRepository.save(any()) } answers { firstArg() }
    every { empleadoService.resumenPorIds(any()) } returns emptyMap()

    val dto = service.aprobar(9, gerencia)

    assertThat(dto.estado).isEqualTo("aprobada")
    verify { oportunidadService.aplicarDescuentoAprobado(45, BigDecimal("8.00"), 1) }
    verify {
        notificacionService.notificar(
            destinatarios = setOf(pendiente.idSolicitante),
            idActor = 1,
            tipo = TipoNotificacion.solicitud_aprobada,
            mensaje = any(),
            entidadTipo = pe.quantum.crm.domain.notificaciones.EntidadNotificacion.solicitud,
            entidadId = 9,
        )
    }
}

@Test
fun `aprobar una bandeja ajena es PERMISO_INSUFICIENTE`() {
    val pendienteJdv = solicitudDescuentoPendiente(rolAprobador = AprobadorSolicitud.jdv)
    every { solicitudRepository.findParaResolver(9) } returns pendienteJdv
    assertThatThrownBy { service.aprobar(9, UsuarioActual(id = 1, rol = "gerencia")) }
        .isInstanceOf(PermisoInsuficienteException::class.java)
}

@Test
fun `aprobar una ya resuelta es 409 SOLICITUD_YA_RESUELTA`() {
    val resuelta = solicitudDescuentoPendiente(rolAprobador = AprobadorSolicitud.gerencia)
        .apply { estado = EstadoSolicitud.aprobada }
    every { solicitudRepository.findParaResolver(9) } returns resuelta
    assertThatThrownBy { service.aprobar(9, UsuarioActual(id = 1, rol = "gerencia")) }
        .isInstanceOf(ConflictoException::class.java)
        .hasMessageContaining("resuelta")
}
```

- [ ] **Step 2: Rojo**: `./gradlew test --tests "*OportunidadServiceImplTest" --tests "*SolicitudServiceImplTest"` → FAIL.

- [ ] **Step 3: Implementar**

`OportunidadService.kt` — agregar a la interfaz:

```kotlin
/**
 * Aplica un descuento ya aprobado por solicitud (modulo solicitudes): setea
 * `dcto`, recalcula `monto_total` y audita con el aprobador. NO valida limites
 * de rol (la aprobacion ES la autorizacion). 409 SOLICITUD_NO_APLICABLE si la
 * oportunidad no existe o ya salio del pipeline activo.
 */
fun aplicarDescuentoAprobado(
    id: Long,
    dcto: BigDecimal,
    idAprobador: Long,
)
```

`OportunidadServiceImpl.kt`:

```kotlin
@Transactional
override fun aplicarDescuentoAprobado(
    id: Long,
    dcto: BigDecimal,
    idAprobador: Long,
) {
    val oportunidad =
        oportunidadRepository.findById(id).orElseThrow {
            ConflictoException("SOLICITUD_NO_APLICABLE", "La oportunidad de la solicitud ya no existe")
        }
    if (oportunidad.estado == EstadoOportunidad.cerrado || oportunidad.estado == EstadoOportunidad.facturado) {
        throw ConflictoException(
            "SOLICITUD_NO_APLICABLE",
            "La oportunidad está en ${oportunidad.estado.name}; el descuento ya no aplica",
        )
    }
    oportunidad.dcto = dcto
    oportunidad.montoTotal = MontoTotal.calcular(oportunidad.cantidad, oportunidad.precioUnitario, dcto)
    oportunidad.updatedAt = LocalDateTime.now()
    oportunidad.updatedBy = idAprobador
    oportunidadRepository.save(oportunidad)
}
```

`SolicitudServiceImpl.aprobar` (reemplaza el `NotImplementedError`; el branch de reasignación queda para Task 8):

```kotlin
@Transactional
override fun aprobar(
    id: Long,
    usuario: UsuarioActual,
): SolicitudDto {
    val solicitud = pendienteParaResolver(id, usuario)
    // El efecto corre en ESTA transaccion: si falla, la solicitud sigue pendiente.
    when (solicitud.tipo) {
        TipoSolicitud.descuento ->
            oportunidadService.aplicarDescuentoAprobado(
                solicitud.entidadId,
                requireNotNull(solicitud.dctoSolicitado),
                usuario.id,
            )
        TipoSolicitud.reasignacion_cliente -> aplicarReasignacion(solicitud, usuario) // Task 8
    }
    resolver(solicitud, EstadoSolicitud.aprobada, usuario, motivoResolucion = null)
    notificarResolucion(solicitud, usuario, TipoNotificacion.solicitud_aprobada, "aprobó")
    return toDto(listOf(solicitud)).first()
}

// ── privados de resolucion ─────────────────────────────────

/** Lock pesimista + guardas: bandeja correcta y estado pendiente. */
private fun pendienteParaResolver(
    id: Long,
    usuario: UsuarioActual,
): Solicitud {
    val solicitud =
        solicitudRepository.findParaResolver(id)
            ?: throw NoEncontradoException("La solicitud no existe")
    if (!usuario.puedeAprobar(solicitud.rolAprobador.name)) {
        throw PermisoInsuficienteException("Esta solicitud la resuelve ${solicitud.rolAprobador.name}")
    }
    if (solicitud.estado != EstadoSolicitud.pendiente) {
        throw ConflictoException("SOLICITUD_YA_RESUELTA", "La solicitud ya fue resuelta")
    }
    return solicitud
}

private fun resolver(
    solicitud: Solicitud,
    estado: EstadoSolicitud,
    usuario: UsuarioActual,
    motivoResolucion: String?,
) {
    solicitud.estado = estado
    solicitud.idResolutor = usuario.id
    solicitud.motivoResolucion = motivoResolucion
    solicitud.resolvedAt = LocalDateTime.now()
    solicitud.updatedAt = LocalDateTime.now()
    solicitudRepository.save(solicitud)
}

private fun notificarResolucion(
    solicitud: Solicitud,
    usuario: UsuarioActual,
    tipo: TipoNotificacion,
    verbo: String,
) {
    val resolutor = empleadoService.resumenPorIds(listOf(usuario.id))[usuario.id]
    val nombre = resolutor?.let { "${it.nombres} ${it.apellidos}" } ?: "Gerencia"
    val sufijo = solicitud.motivoResolucion?.let { ": $it" } ?: ""
    notificacionService.notificar(
        destinatarios = setOf(solicitud.idSolicitante),
        idActor = usuario.id,
        tipo = tipo,
        mensaje = "$nombre $verbo tu solicitud de ${etiquetaTipo(solicitud.tipo)} sobre ${solicitud.entidadDescripcion}$sufijo",
        entidadTipo = EntidadNotificacion.solicitud,
        entidadId = requireNotNull(solicitud.id),
    )
}

private fun aplicarReasignacion(
    solicitud: Solicitud,
    usuario: UsuarioActual,
): Unit = throw NotImplementedError("Task 8")
```

Imports nuevos: `java.time.LocalDateTime`, `java.math.BigDecimal` donde falten. Nota: si el DTO de `EmpleadoResumen` no expone `nombres`/`apellidos` así, usar la extensión existente `nombreCompleto()` de `pe.quantum.crm.domain.empleados.dto`.

- [ ] **Step 4: Verde**: `./gradlew test --tests "*OportunidadServiceImplTest" --tests "*SolicitudServiceImplTest"` → PASS.

- [ ] **Step 5: Commit**

```bash
git add -A src/main src/test
git commit -m "feat(solicitudes): aprobar descuento aplica el cambio en la misma transaccion y notifica"
```

---

### Task 8: Aprobar reasignación + denegar

**Files:**
- Modify: `src/main/kotlin/pe/quantum/crm/domain/solicitudes/SolicitudServiceImpl.kt`
- Modify: `src/main/kotlin/pe/quantum/crm/domain/empresas/EmpresaServiceImpl.kt` (si hace falta: `reasignarVendedor` ya sirve — el aprobador es gerencia/admin y pasa `puedeReasignarDirecto`)
- Test: `src/test/kotlin/pe/quantum/crm/domain/solicitudes/SolicitudServiceImplTest.kt`

**Interfaces:**
- Consumes: `EmpresaService.reasignarVendedor(id, idVendedor, usuario)` (con su cascada y notificación `empresa_asignada` existentes).
- Produces: `aprobar` completo para ambos tipos; `denegar` operativo.

- [ ] **Step 1: Tests que fallan**

```kotlin
@Test
fun `aprobar reasignacion ejecuta reasignarVendedor con el usuario aprobador`() {
    val gerencia = UsuarioActual(id = 1, rol = "gerencia")
    val pendiente = solicitudReasignacionPendiente(entidadId = 12, idVendedorNuevo = 8) // helper local
    every { solicitudRepository.findParaResolver(9) } returns pendiente
    every { empresaService.reasignarVendedor(12, 8, gerencia) } returns 8
    every { solicitudRepository.save(any()) } answers { firstArg() }
    every { empleadoService.resumenPorIds(any()) } returns emptyMap()

    val dto = service.aprobar(9, gerencia)

    assertThat(dto.estado).isEqualTo("aprobada")
    verify { empresaService.reasignarVendedor(12, 8, gerencia) }
}

@Test
fun `denegar exige motivo y notifica con el mensaje de gerencia`() {
    val gerencia = UsuarioActual(id = 1, rol = "gerencia")
    val pendiente = solicitudDescuentoPendiente(rolAprobador = AprobadorSolicitud.gerencia)
    every { solicitudRepository.findParaResolver(9) } returns pendiente
    every { solicitudRepository.save(any()) } answers { firstArg() }
    every { empleadoService.resumenPorIds(any()) } returns emptyMap()

    val dto = service.denegar(9, "El margen no lo soporta", gerencia)

    assertThat(dto.estado).isEqualTo("denegada")
    assertThat(dto.motivoResolucion).isEqualTo("El margen no lo soporta")
    verify {
        notificacionService.notificar(
            destinatarios = setOf(pendiente.idSolicitante),
            idActor = 1,
            tipo = TipoNotificacion.solicitud_denegada,
            mensaje = match { it.contains("El margen no lo soporta") },
            entidadTipo = pe.quantum.crm.domain.notificaciones.EntidadNotificacion.solicitud,
            entidadId = 9,
        )
    }
}

@Test
fun `denegar con motivo en blanco es VALIDACION`() {
    assertThatThrownBy { service.denegar(9, "  ", UsuarioActual(id = 1, rol = "gerencia")) }
        .isInstanceOf(ValidacionException::class.java)
}
```

- [ ] **Step 2: Rojo**: `./gradlew test --tests "*SolicitudServiceImplTest"` → FAIL.

- [ ] **Step 3: Implementar** — reemplazar `aplicarReasignacion` y `denegar`:

```kotlin
/**
 * Reutiliza reasignarVendedor: cascada a oportunidades activas y notificacion
 * `empresa_asignada` incluidas. El actor es el aprobador (gerencia/admin), que
 * pasa el guard de `puedeReasignarDirecto`.
 */
private fun aplicarReasignacion(
    solicitud: Solicitud,
    usuario: UsuarioActual,
) {
    empresaService.reasignarVendedor(
        solicitud.entidadId,
        requireNotNull(solicitud.idVendedorNuevo),
        usuario,
    )
}

@Transactional
override fun denegar(
    id: Long,
    motivo: String,
    usuario: UsuarioActual,
): SolicitudDto {
    if (motivo.isBlank()) {
        throw ValidacionException("El motivo de la denegación es obligatorio", field = "motivo")
    }
    val solicitud = pendienteParaResolver(id, usuario)
    resolver(solicitud, EstadoSolicitud.denegada, usuario, motivoResolucion = motivo)
    notificarResolucion(solicitud, usuario, TipoNotificacion.solicitud_denegada, "denegó")
    return toDto(listOf(solicitud)).first()
}
```

Caso borde a cubrir en el mismo paso: si `reasignarVendedor` lanza `NoEncontradoException` (empresa borrada) o `ValidacionException` (vendedor ya no asignable), la transacción entera revierte y la solicitud sigue pendiente — comportamiento correcto según contrato (`SOLICITUD_NO_APLICABLE` manual: gerencia la deniega). Envolver así:

```kotlin
// dentro de aplicarReasignacion, reemplazando la llamada directa:
try {
    empresaService.reasignarVendedor(solicitud.entidadId, requireNotNull(solicitud.idVendedorNuevo), usuario)
} catch (ex: NoEncontradoException) {
    throw ConflictoException("SOLICITUD_NO_APLICABLE", "La empresa de la solicitud ya no existe")
} catch (ex: ValidacionException) {
    throw ConflictoException("SOLICITUD_NO_APLICABLE", "El vendedor destino ya no es asignable")
}
```

- [ ] **Step 4: Verde**: `./gradlew test --tests "*SolicitudServiceImplTest"` → PASS. Luego `./gradlew test` completo.

- [ ] **Step 5: Commit**

```bash
git add -A src/main src/test
git commit -m "feat(solicitudes): aprobar reasignacion (reusa cascada) y denegar con motivo obligatorio"
```

---

### Task 9: `SolicitudController` + tests WebMvc

**Files:**
- Create: `src/main/kotlin/pe/quantum/crm/domain/solicitudes/SolicitudController.kt`
- Test: `src/test/kotlin/pe/quantum/crm/domain/solicitudes/SolicitudControllerWebMvcTest.kt`

**Interfaces:**
- Consumes: `SolicitudService` completo (Tasks 5–8), `UsuarioActualProvider`, `ApiResponse`, patrón de otros controllers (`TareaController` como referencia de estilo).
- Produces: `POST /api/v1/solicitudes` (201), `GET /api/v1/solicitudes`, `GET /api/v1/solicitudes/{id}`, `PATCH /api/v1/solicitudes/{id}/aprobar`, `PATCH /api/v1/solicitudes/{id}/denegar`.

- [ ] **Step 1: Test WebMvc que falla** — seguir el patrón de `AuthControllerWebMvcTest` (SpringBootTest sin DB + `SinBaseDeDatosMocks` + `@MockkBean lateinit var solicitudService: SolicitudService`); generar tokens con `jwtService` para roles `vendedor` y `gerencia`. Casos mínimos:

```kotlin
// src/test/kotlin/pe/quantum/crm/domain/solicitudes/SolicitudControllerWebMvcTest.kt
// Cabecera idéntica a AuthControllerWebMvcTest (@SpringBootTest sin DataSource/JPA/Flyway,
// @AutoConfigureMockMvc, @Import(SinBaseDeDatosMocks::class)). Generar el header
// Authorization con jwtService igual que los demás WebMvc tests autenticados del repo.

@MockkBean
lateinit var solicitudService: SolicitudService

private fun solicitudDto(estado: String = "pendiente") =
    SolicitudDto(
        id = 7, tipo = "descuento", estado = estado, rolAprobador = "jdv",
        entidadTipo = "oportunidad", entidadId = 45,
        entidadDescripcion = "ABC — Oportunidad #45", dctoSolicitado = "5.00",
        idVendedorNuevo = null, vendedorNuevo = null, motivo = "Cliente frecuente",
        solicitante = null, resolutor = null, motivoResolucion = null,
        resolvedAt = null, createdAt = LocalDateTime.now(),
    )

@Test
fun `POST solicitudes responde 201 con el envelope`() {
    every { solicitudService.crear(any(), any()) } returns solicitudDto()
    mockMvc.post("/api/v1/solicitudes") {
        header("Authorization", "Bearer ${tokenVendedor()}")
        contentType = MediaType.APPLICATION_JSON
        content =
            """{"tipo":"descuento","entidad_tipo":"oportunidad","entidad_id":45,
               "dcto_solicitado":"5.00","motivo":"Cliente frecuente"}"""
    }.andExpect {
        status { isCreated() }
        jsonPath("$.data.id") { value(7) }
        jsonPath("$.data.rol_aprobador") { value("jdv") }
    }
}

@Test
fun `POST solicitudes sin motivo responde 400 VALIDACION`() {
    mockMvc.post("/api/v1/solicitudes") {
        header("Authorization", "Bearer ${tokenVendedor()}")
        contentType = MediaType.APPLICATION_JSON
        content = """{"tipo":"descuento","entidad_tipo":"oportunidad","entidad_id":45,"dcto_solicitado":"5.00"}"""
    }.andExpect {
        status { isBadRequest() }
        jsonPath("$.error.code") { value("VALIDACION") }
    }
}

@Test
fun `GET solicitudes responde 200 paginado`() {
    every { solicitudService.listar(any(), any(), any(), any(), any(), any()) } returns
        Paginado(listOf(solicitudDto()), Paginacion.meta(1, 20, 1))
    mockMvc.get("/api/v1/solicitudes?estado=pendiente") {
        header("Authorization", "Bearer ${tokenGerencia()}")
    }.andExpect {
        status { isOk() }
        jsonPath("$.meta.page") { value(1) }
        jsonPath("$.data[0].id") { value(7) }
    }
}

@Test
fun `PATCH aprobar responde 200 con estado aprobada`() {
    every { solicitudService.aprobar(7, any()) } returns solicitudDto(estado = "aprobada")
    mockMvc.patch("/api/v1/solicitudes/7/aprobar") {
        header("Authorization", "Bearer ${tokenGerencia()}")
    }.andExpect {
        status { isOk() }
        jsonPath("$.data.estado") { value("aprobada") }
    }
}

@Test
fun `PATCH denegar sin motivo responde 400`() {
    mockMvc.patch("/api/v1/solicitudes/7/denegar") {
        header("Authorization", "Bearer ${tokenGerencia()}")
        contentType = MediaType.APPLICATION_JSON
        content = """{"motivo":""}"""
    }.andExpect { status { isBadRequest() } }
}

@Test
fun `sin token responde 401`() {
    mockMvc.get("/api/v1/solicitudes").andExpect { status { isUnauthorized() } }
}
```

(`tokenVendedor()`/`tokenGerencia()`: helpers locales que emiten un JWT con `jwtService` para un empleado con ese rol — copiar el mecanismo exacto de los otros WebMvc tests autenticados, p. ej. `EmpleadoMeControllerTest`.)

- [ ] **Step 2: Rojo**: `./gradlew test --tests "*SolicitudControllerWebMvcTest"` → FAIL.

- [ ] **Step 3: Implementar el controller**

```kotlin
// src/main/kotlin/pe/quantum/crm/domain/solicitudes/SolicitudController.kt
package pe.quantum.crm.domain.solicitudes

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
import pe.quantum.crm.domain.solicitudes.dto.CrearSolicitudRequest
import pe.quantum.crm.domain.solicitudes.dto.DenegarSolicitudRequest
import pe.quantum.crm.domain.solicitudes.dto.SolicitudDto
import pe.quantum.crm.domain.solicitudes.dto.SolicitudFiltros
import pe.quantum.crm.shared.ApiResponse
import pe.quantum.crm.shared.Paginado
import pe.quantum.crm.shared.security.UsuarioActualProvider

/** Endpoints de solicitudes de aprobacion (gerencia_contrato_frontend.md §4). */
@RestController
@RequestMapping("/api/v1/solicitudes")
class SolicitudController(
    private val solicitudService: SolicitudService,
    private val usuarioProvider: UsuarioActualProvider,
) {
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun crear(
        @Valid @RequestBody request: CrearSolicitudRequest,
    ): ApiResponse<SolicitudDto> = ApiResponse.ok(solicitudService.crear(request, usuarioProvider.actual()))

    @GetMapping
    @Suppress("LongParameterList") // Query params del contrato.
    fun listar(
        @RequestParam(required = false) estado: String?,
        @RequestParam(required = false) tipo: String?,
        @RequestParam(required = false, defaultValue = "false") mias: Boolean,
        @RequestParam(required = false) page: Int?,
        @RequestParam(name = "per_page", required = false) perPage: Int?,
        @RequestParam(required = false) sort: String?,
        @RequestParam(required = false) dir: String?,
    ): ApiResponse<List<SolicitudDto>> {
        val paginado: Paginado<SolicitudDto> =
            solicitudService.listar(
                SolicitudFiltros(estado = estado, tipo = tipo, mias = mias),
                usuarioProvider.actual(),
                page,
                perPage,
                sort,
                dir,
            )
        return ApiResponse.paginado(paginado)
    }

    @GetMapping("/{id}")
    fun detalle(
        @PathVariable id: Long,
    ): ApiResponse<SolicitudDto> = ApiResponse.ok(solicitudService.detalle(id, usuarioProvider.actual()))

    @PatchMapping("/{id}/aprobar")
    fun aprobar(
        @PathVariable id: Long,
    ): ApiResponse<SolicitudDto> = ApiResponse.ok(solicitudService.aprobar(id, usuarioProvider.actual()))

    @PatchMapping("/{id}/denegar")
    fun denegar(
        @PathVariable id: Long,
        @Valid @RequestBody request: DenegarSolicitudRequest,
    ): ApiResponse<SolicitudDto> = ApiResponse.ok(solicitudService.denegar(id, requireNotNull(request.motivo), usuarioProvider.actual()))
}
```

> Ajustar `ApiResponse.ok` / `ApiResponse.paginado` a los factory methods reales de `shared/ApiResponse.kt` (mirar cómo lo hace `EmpresaController` con listados paginados y copiar exactamente).

- [ ] **Step 4: Verde**: `./gradlew test --tests "*SolicitudControllerWebMvcTest"` → PASS. Luego suite completa.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/pe/quantum/crm/domain/solicitudes/SolicitudController.kt src/test/kotlin/pe/quantum/crm/domain/solicitudes
git commit -m "feat(solicitudes): endpoints REST de solicitudes con tests WebMvc"
```

---

### Task 10: Cartera Maestra — entidad y visibilidad

**Files:**
- Modify: `src/main/kotlin/pe/quantum/crm/domain/empresas/Empresa.kt`
- Modify: `src/main/kotlin/pe/quantum/crm/domain/empresas/EmpresaServiceImpl.kt` (`visible`, `especificacion`)
- Modify: `src/main/kotlin/pe/quantum/crm/domain/empresas/dto/EmpresaDtos.kt` (`EmpresaFiltros.carteraMaestra`, `enCarteraMaestra` en lista y detalle)
- Modify: `src/main/kotlin/pe/quantum/crm/domain/empresas/EmpresaController.kt` (query param `cartera_maestra`)
- Modify: `src/main/kotlin/pe/quantum/crm/domain/prospeccion/ProspeccionDao.kt` y `src/main/kotlin/pe/quantum/crm/domain/inicio/InicioDao.kt` y `src/main/kotlin/pe/quantum/crm/domain/reportes/*` — agregar `AND e.en_cartera_maestra = false` a toda query que liste empresas para roles no-gerencia (revisar cada query nativa/JPQL que toque `empresas`)
- Test: `src/test/kotlin/pe/quantum/crm/domain/empresas/EmpresaServiceImplTest.kt`

**Interfaces:**
- Consumes: columna V27 (Task 2), `puedeVerCarteraMaestra` (Task 1).
- Produces: `Empresa.enCarteraMaestra: Boolean`; filtro server-side en todos los listados; `EmpresaFiltros(carteraMaestra: Boolean? = null)`. Task 11 usa la property de la entidad.

- [ ] **Step 1: Tests que fallan**

```kotlin
@Test
fun `detalle de empresa en cartera maestra para jdv es 404 - IDOR`() {
    val jdv = UsuarioActual(id = 2, rol = "jdv")
    every { empresaRepository.findById(10) } returns Optional.of(empresa(id = 10).apply { enCarteraMaestra = true })
    assertThatThrownBy { service.detalle(10, jdv) }
        .isInstanceOf(NoEncontradoException::class.java)
}

@Test
fun `detalle de empresa en cartera maestra para gerencia responde normal`() {
    val gerencia = UsuarioActual(id = 1, rol = "gerencia")
    every { empresaRepository.findById(10) } returns Optional.of(empresa(id = 10).apply { enCarteraMaestra = true })
    every { empleadoService.resumenPorIds(any()) } returns emptyMap()
    assertThat(service.detalle(10, gerencia).enCarteraMaestra).isTrue()
}
```

- [ ] **Step 2: Rojo**: compilación falla (`enCarteraMaestra` no existe).

- [ ] **Step 3: Implementar**

`Empresa.kt` — agregar tras `estadoCartera`:

```kotlin
@Column(name = "en_cartera_maestra", nullable = false)
var enCarteraMaestra: Boolean = false,
```

`EmpresaServiceImpl.visible` — nueva condición:

```kotlin
private fun visible(
    id: Long,
    usuario: UsuarioActual,
): Empresa {
    val empresa = entidad(id)
    if (empresa.enCarteraMaestra && !usuario.puedeVerCarteraMaestra) {
        throw NoEncontradoException("La empresa no existe")
    }
    if (usuario.visibilidadRestringida && empresa.idVendedor != usuario.id) {
        throw NoEncontradoException("La empresa no existe")
    }
    return empresa
}
```

`EmpresaServiceImpl.especificacion` — al inicio de los predicados:

```kotlin
if (!usuario.puedeVerCarteraMaestra) {
    predicados += cb.isFalse(root.get("enCarteraMaestra"))
} else {
    filtros.carteraMaestra?.let {
        predicados += cb.equal(root.get<Boolean>("enCarteraMaestra"), it)
    }
}
```

`EmpresaFiltros` — agregar `val carteraMaestra: Boolean? = null`. `EmpresaListaDto`/`EmpresaDetalleDto` — agregar `val enCarteraMaestra: Boolean` y poblarlo en los mappers. `EmpresaController.listar` — agregar `@RequestParam(name = "cartera_maestra", required = false) carteraMaestra: Boolean?` y pasarlo a los filtros.

DAOs de prospección/inicio/reportes: buscar con `grep -rn "empresas" src/main/kotlin/pe/quantum/crm/domain/prospeccion src/main/kotlin/pe/quantum/crm/domain/inicio src/main/kotlin/pe/quantum/crm/domain/reportes` toda query que seleccione empresas y agregar `AND e.en_cartera_maestra = false` (las de cartera maestra no tienen vendedor ni oportunidades activas, pero aparecerían en vistas de supervisor como prospección "todas"). Nota: `reglas` — el jdv es supervisor pero NO ve cartera maestra; si alguna query filtra por "supervisor ve todo", el filtro debe ser por `puedeVerCarteraMaestra`, no por `esSupervisor`.

- [ ] **Step 4: Verde + suite completa**: `./gradlew test` → PASS (ajustar constructores de DTOs en tests existentes que ahora exigen `enCarteraMaestra`).

- [ ] **Step 5: Commit**

```bash
git add -A src/main src/test
git commit -m "feat(empresas): cartera maestra invisible fuera de gerencia/admin en todos los listados"
```

---

### Task 11: Cartera Maestra — mover y liberar

**Files:**
- Modify: `src/main/kotlin/pe/quantum/crm/domain/empresas/EmpresaService.kt`
- Modify: `src/main/kotlin/pe/quantum/crm/domain/empresas/EmpresaServiceImpl.kt`
- Modify: `src/main/kotlin/pe/quantum/crm/domain/empresas/EmpresaController.kt`
- Modify: `src/main/kotlin/pe/quantum/crm/domain/empresas/dto/EmpresaDtos.kt` (request nuevo)
- Test: `src/test/kotlin/pe/quantum/crm/domain/empresas/EmpresaServiceImplTest.kt`

**Interfaces:**
- Consumes: `EstadoCartera.oportunidad_activa` (proxy de "tiene oportunidades activas", evita ciclo de dependencia con el módulo oportunidades), `esAsignableComoVendedor` (Task 4), `NotificacionService`.
- Produces: `EmpresaService.cambiarCarteraMaestra(id: Long, enCarteraMaestra: Boolean, idVendedor: Long?, usuario: UsuarioActual): Empresa`-shaped DTO — devolver `Map<String, Any?>`-friendly datos vía un DTO `CarteraMaestraDto(enCarteraMaestra: Boolean, idVendedor: Long?)`.

- [ ] **Step 1: Tests que fallan**

```kotlin
@Test
fun `mover a cartera maestra desasigna vendedor y exige que no haya oportunidades activas`() {
    val gerencia = UsuarioActual(id = 1, rol = "gerencia")
    val entidad = empresa(id = 10).apply { idVendedor = 5 }
    every { empresaRepository.findById(10) } returns Optional.of(entidad)
    every { empresaRepository.save(any()) } answers { firstArg() }

    val dto = service.cambiarCarteraMaestra(10, enCarteraMaestra = true, idVendedor = null, usuario = gerencia)

    assertThat(dto.enCarteraMaestra).isTrue()
    assertThat(entidad.idVendedor).isNull()
}

@Test
fun `mover a cartera maestra con oportunidad activa es 409`() {
    val gerencia = UsuarioActual(id = 1, rol = "gerencia")
    val activa = empresa(id = 10).apply { estadoCartera = EstadoCartera.oportunidad_activa }
    every { empresaRepository.findById(10) } returns Optional.of(activa)
    assertThatThrownBy { service.cambiarCarteraMaestra(10, true, null, gerencia) }
        .isInstanceOf(ConflictoException::class.java)
}

@Test
fun `liberar exige id_vendedor, asigna y notifica empresa_asignada`() {
    val gerencia = UsuarioActual(id = 1, rol = "gerencia")
    val reservada = empresa(id = 10).apply { enCarteraMaestra = true }
    every { empresaRepository.findById(10) } returns Optional.of(reservada)
    every { empleadoService.esAsignableComoVendedor(8) } returns true
    every { empresaRepository.save(any()) } answers { firstArg() }
    every { empleadoService.resumenPorIds(any()) } returns emptyMap()

    val dto = service.cambiarCarteraMaestra(10, enCarteraMaestra = false, idVendedor = 8, usuario = gerencia)

    assertThat(dto.enCarteraMaestra).isFalse()
    assertThat(dto.idVendedor).isEqualTo(8)
    verify {
        notificacionService.notificar(
            destinatarios = setOf(8L),
            idActor = 1,
            tipo = TipoNotificacion.empresa_asignada,
            mensaje = any(),
            entidadTipo = EntidadNotificacion.empresa,
            entidadId = 10,
        )
    }
}

@Test
fun `liberar sin id_vendedor es VALIDACION`() {
    val gerencia = UsuarioActual(id = 1, rol = "gerencia")
    val reservada = empresa(id = 10).apply { enCarteraMaestra = true }
    every { empresaRepository.findById(10) } returns Optional.of(reservada)
    assertThatThrownBy { service.cambiarCarteraMaestra(10, false, null, gerencia) }
        .isInstanceOf(ValidacionException::class.java)
}
```

- [ ] **Step 2: Rojo**: `./gradlew test --tests "*EmpresaServiceImplTest"` → FAIL.

- [ ] **Step 3: Implementar**

DTO (en `EmpresaDtos.kt`):

```kotlin
data class CarteraMaestraDto(
    val enCarteraMaestra: Boolean,
    val idVendedor: Long?,
)

data class CambiarCarteraMaestraRequest(
    val enCarteraMaestra: Boolean?,
    val idVendedor: Long? = null,
)
```

(ajustar el naming snake_case al mecanismo del proyecto, igual que Task 5).

Interfaz `EmpresaService`:

```kotlin
/**
 * Mueve una empresa a la Cartera Maestra (reserva de gerencia, la desasigna)
 * o la libera asignando vendedor (obligatorio) y notificando `empresa_asignada`.
 * Solo gerencia/admin (el controller ya lo restringe; el servicio re-verifica).
 */
fun cambiarCarteraMaestra(
    id: Long,
    enCarteraMaestra: Boolean,
    idVendedor: Long?,
    usuario: UsuarioActual,
): CarteraMaestraDto
```

Implementación en `EmpresaServiceImpl`:

```kotlin
@Transactional
override fun cambiarCarteraMaestra(
    id: Long,
    enCarteraMaestra: Boolean,
    idVendedor: Long?,
    usuario: UsuarioActual,
): CarteraMaestraDto {
    if (!usuario.puedeVerCarteraMaestra) {
        throw PermisoInsuficienteException("La cartera maestra es exclusiva de gerencia")
    }
    val empresa = entidad(id)
    if (enCarteraMaestra) {
        // El estado derivado delata oportunidades activas sin acoplar este
        // modulo al de oportunidades (seria una dependencia circular).
        if (empresa.estadoCartera == EstadoCartera.oportunidad_activa) {
            throw ConflictoException(
                "CARTERA_MAESTRA_CON_OPORTUNIDADES",
                "No se puede reservar una empresa con oportunidades activas",
            )
        }
        empresa.enCarteraMaestra = true
        empresa.idVendedor = null
    } else {
        val destino =
            idVendedor ?: throw ValidacionException("id_vendedor es obligatorio al liberar", field = "id_vendedor")
        if (!empleadoService.esAsignableComoVendedor(destino)) {
            throw ValidacionException("El destino debe ser un vendedor o jdv activo", field = "id_vendedor")
        }
        empresa.enCarteraMaestra = false
        empresa.idVendedor = destino
    }
    empresa.updatedAt = LocalDateTime.now()
    empresa.updatedBy = usuario.id
    empresaRepository.save(empresa)
    if (!enCarteraMaestra) {
        val actor = empleadoService.resumenPorIds(listOf(usuario.id))[usuario.id]
        notificacionService.notificar(
            destinatarios = setOf(requireNotNull(empresa.idVendedor)),
            idActor = usuario.id,
            tipo = TipoNotificacion.empresa_asignada,
            mensaje = "${actor?.nombreCompleto()} te asignó la empresa ${empresa.razonSocial} desde la cartera maestra",
            entidadTipo = EntidadNotificacion.empresa,
            entidadId = id,
        )
    }
    return CarteraMaestraDto(enCarteraMaestra = empresa.enCarteraMaestra, idVendedor = empresa.idVendedor)
}
```

Controller (`EmpresaController`):

```kotlin
@PatchMapping("/{id}/cartera-maestra")
@PreAuthorize("hasAnyRole('admin', 'gerencia')")
fun cambiarCarteraMaestra(
    @PathVariable id: Long,
    @RequestBody request: CambiarCarteraMaestraRequest,
): ApiResponse<CarteraMaestraDto> {
    val enCarteraMaestra =
        request.enCarteraMaestra
            ?: throw ValidacionException("en_cartera_maestra es obligatorio", field = "en_cartera_maestra")
    return ApiResponse.ok(
        empresaService.cambiarCarteraMaestra(id, enCarteraMaestra, request.idVendedor, usuarioProvider.actual()),
    )
}
```

- [ ] **Step 4: Verde + suite**: `./gradlew test` → PASS.

- [ ] **Step 5: Commit**

```bash
git add -A src/main src/test
git commit -m "feat(empresas): mover/liberar cartera maestra con asignacion y notificacion"
```

---

### Task 12: Creación de oportunidades por Gerencia (empresa sin vendedor)

**Files:**
- Modify: `src/main/kotlin/pe/quantum/crm/domain/oportunidades/dto/OportunidadDtos.kt` (`CrearOportunidadRequest.idVendedor`)
- Modify: `src/main/kotlin/pe/quantum/crm/domain/oportunidades/OportunidadServiceImpl.kt` (`crear`)
- Test: `src/test/kotlin/pe/quantum/crm/domain/oportunidades/OportunidadServiceImplTest.kt`

**Interfaces:**
- Consumes: `EmpresaService.reasignarVendedor` (asigna + notifica + cascada; misma transacción), `EmpleadoService.esAsignableComoVendedor`.
- Produces: `POST /oportunidades` acepta `id_vendedor` opcional; regla: obligatorio si la empresa no tiene vendedor y quien crea es un rol sin visibilidad restringida.

- [ ] **Step 1: Tests que fallan**

```kotlin
@Test
fun `gerencia crea oportunidad en empresa sin vendedor - exige id_vendedor y lo asigna a la empresa`() {
    val gerencia = UsuarioActual(id = 1, rol = "gerencia")
    every { empresaService.vinculoVisible(10, gerencia) } returns
        EmpresaVinculo(id = 10, razonSocial = "ABC", idVendedor = null, estadoCartera = "prospeccion")
    every { empleadoService.esAsignableComoVendedor(8) } returns true
    every { empresaService.reasignarVendedor(10, 8, gerencia) } returns 8
    // ... resto de mocks que el caso feliz de `crear` ya arma en este archivo

    val dto = service.crear(crearRequest(idEmpresa = 10, idVendedor = 8), gerencia)

    assertThat(dto.idVendedor).isEqualTo(8)
    verify { empresaService.reasignarVendedor(10, 8, gerencia) }
}

@Test
fun `gerencia crea oportunidad en empresa sin vendedor sin id_vendedor es VALIDACION`() {
    val gerencia = UsuarioActual(id = 1, rol = "gerencia")
    every { empresaService.vinculoVisible(10, gerencia) } returns
        EmpresaVinculo(id = 10, razonSocial = "ABC", idVendedor = null, estadoCartera = "prospeccion")
    assertThatThrownBy { service.crear(crearRequest(idEmpresa = 10), gerencia) }
        .isInstanceOf(ValidacionException::class.java)
}

@Test
fun `la oportunidad creada por gerencia en empresa con vendedor queda para ese vendedor, no para gerencia`() {
    val gerencia = UsuarioActual(id = 1, rol = "gerencia")
    every { empresaService.vinculoVisible(10, gerencia) } returns
        EmpresaVinculo(id = 10, razonSocial = "ABC", idVendedor = 5, estadoCartera = "cliente")
    // ... mocks del caso feliz
    val dto = service.crear(crearRequest(idEmpresa = 10), gerencia)
    assertThat(dto.idVendedor).isEqualTo(5)
}
```

- [ ] **Step 2: Rojo**: `./gradlew test --tests "*OportunidadServiceImplTest"` → FAIL.

- [ ] **Step 3: Implementar**

`CrearOportunidadRequest` — agregar `val idVendedor: Long? = null` (snake_case `id_vendedor` según el mecanismo del proyecto).

En `OportunidadServiceImpl.crear`, reemplazar la línea `idVendedor = empresa.idVendedor ?: usuario.id,` por una resolución previa (antes de construir la entidad):

```kotlin
// Snapshot del vendedor de la empresa (reglas §8.4). Una empresa sin vendedor
// solo la ven roles supervisores: quien crea DEBE asignar un vendedor real
// (gerencia/admin no pueden tener oportunidades propias).
val idVendedorSnapshot =
    empresa.idVendedor ?: run {
        if (usuario.visibilidadRestringida) {
            // Inalcanzable en la practica (la empresa seria invisible), pero
            // el guard mantiene la invariante si la visibilidad cambia.
            usuario.id
        } else {
            val destino =
                request.idVendedor
                    ?: throw ValidacionException(
                        "La empresa no tiene vendedor asignado; id_vendedor es obligatorio",
                        field = "id_vendedor",
                    )
            if (!empleadoService.esAsignableComoVendedor(destino)) {
                throw ValidacionException("El destino debe ser un vendedor o jdv activo", field = "id_vendedor")
            }
            // Misma transaccion: la empresa queda asignada y notificada.
            empresaService.reasignarVendedor(empresa.id, destino, usuario)
            destino
        }
    }
```

y en el constructor de la entidad: `idVendedor = idVendedorSnapshot,`.

> Ojo: `reasignarVendedor` exige `usuario.puedeReasignarDirecto` (Task 4). Para el `jdv` creando en una empresa sin vendedor esto daría 403 — correcto según la nueva política (el jdv no asigna clientes sin aprobación); documentado en el contrato frontend §3.3.

- [ ] **Step 4: Verde + suite**: `./gradlew test` → PASS.

- [ ] **Step 5: Commit**

```bash
git add -A src/main src/test
git commit -m "feat(oportunidades): gerencia crea oportunidades asignando vendedor a empresas sin responsable"
```

---

### Task 13: Documentación y verificación final

**Files:**
- Modify: `docs/contrato_api.md` — §3 (códigos nuevos: `APROBACION_REQUERIDA` 422, `SOLICITUD_DUPLICADA` 409, `SOLICITUD_YA_RESUELTA` 409, `SOLICITUD_NO_APLICABLE` 409, `CARTERA_MAESTRA_CON_OPORTUNIDADES` 409), §5 (renombrar `gerente`→`gerencia`, fila de cartera maestra), §8 (query param `cartera_maestra`, campo `en_cartera_maestra`, `PATCH /empresas/:id/cartera-maestra`, roles de `PATCH /empresas/:id/vendedor`), §10 (`id_vendedor` en POST, 422 en POST/PUT), sección nueva §19 Solicitudes (copiar de `docs/gerencia_contrato_frontend.md §4`)
- Modify: `docs/matriz_permisos.md` — renombrar rol, tabla nueva de solicitudes (crear/aprobar/denegar por rol), fila de cartera maestra en visibilidad, actualizar reasignación (jdv → vía solicitud), límites de descuento
- Modify: `docs/schema.sql` — reflejar V25–V28
- Test: N/A (documentación) — pero la suite y los linters corren igual

- [ ] **Step 1: Actualizar los tres documentos** siguiendo `docs/gerencia_solicitudes_modelo_datos.md` y `docs/gerencia_contrato_frontend.md` como fuente. En `matriz_permisos.md`, la tabla de solicitudes:

```markdown
### 2.12 Solicitudes de aprobación

| Operación | admin | gerencia | jdv | vendedor | analista |
|---|---|---|---|---|---|
| Crear solicitud de descuento (sobre su límite) | — (aplica directo) | — (aplica directo) | ✓ (>7%) | ✓ (>3%) | ✓ (>3%) |
| Crear solicitud de reasignación de cliente | — (reasigna directo) | — (reasigna directo) | ✓ | — | — |
| Ver bandeja de aprobación | ✓ Todas | ✓ Las dirigidas a gerencia | ✓ Las dirigidas a jdv | — | — |
| Ver solicitudes propias | ✓ | ✓ | ✓ | ✓ | ✓ |
| Aprobar / denegar | ✓ Cualquiera | ✓ Su bandeja | ✓ Su bandeja | — | — |
| Ver / gestionar cartera maestra | ✓ | ✓ | — | — | — |
```

- [ ] **Step 2: Verificación completa**

Run: `./gradlew ktlintCheck detekt test`
Expected: PASS los tres. Si `koverVerify` corre en CI, correrlo también.

- [ ] **Step 3: Commit**

```bash
git add docs
git commit -m "docs: contrato_api, matriz_permisos y schema con gerencia, solicitudes y cartera maestra"
```

---

## Riesgos y notas para el ejecutor

1. **El rename de `gerente` (Task 1) es transversal**: si algún `@PreAuthorize` o test queda con `'gerente'`, ese endpoint queda inaccesible para Gerencia. El grep del Step 5 es obligatorio, y la suite de WebMvc lo detecta.
2. **Ciclo de dependencias**: `solicitudes` depende de `oportunidades`, `empresas`, `empleados` y `notificaciones` — ninguno de esos módulos debe importar nada de `solicitudes` (ArchUnit fallará si pasa). La detección de "requiere aprobación" en oportunidades usa solo `shared/PoliticaDescuento`.
3. **Snake_case en JSON**: verificar cómo serializa el proyecto (config global de Jackson vs anotación por DTO) ANTES de escribir los DTOs de solicitudes, copiando un DTO existente con campos multi-palabra (p. ej. `rolEnOportunidad`).
4. **`SinBaseDeDatosMocks`** (`src/test/kotlin/pe/quantum/crm/support`) probablemente necesite mockear también `SolicitudService`/`SolicitudRepository` para los WebMvc tests sin DB — revisar cómo registra los mocks existentes.
5. Los tests de repositorio con Testcontainers comparten la DB del contexto: limpiar lo insertado (los tests de Task 2 ya lo hacen con `repository.delete`).
