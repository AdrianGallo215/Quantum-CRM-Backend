# DELETE admin-only de empresas y oportunidades — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Agregar `DELETE /api/v1/empresas/{id}` y `DELETE /api/v1/oportunidades/{id}`, exclusivos para el rol `admin`, que eliminan definitivamente la entidad y (vía cascada de base de datos) sus oportunidades/tareas/eventos/log relacionados — sin tocar los contactos vinculados, que solo se desvinculan.

**Architecture:** Se cambian 3 foreign keys de `RESTRICT` a `CASCADE` en una migración Flyway nueva (V29), de modo que un solo `DELETE` de Postgres arrastra todo el árbol correcto de forma atómica. Los dos endpoints nuevos son simples: `entidad(id)` (404 si no existe) + `repository.delete(entidad)`, protegidos con `@PreAuthorize("hasRole('admin')")`. Al eliminar una oportunidad se recalcula `estado_cartera` de su empresa reutilizando `EstadoCarteraService.actualizar()`, ya existente.

**Tech Stack:** Kotlin 1.9, Spring Boot 3.2, Spring Data JPA, Flyway, Spring Security 6 (`@PreAuthorize`), PostgreSQL 16, JUnit 5 + MockK + AssertJ, Testcontainers.

## Global Constraints

- `spring.jpa.hibernate.ddl-auto=validate` — Hibernate nunca toca el schema; todo cambio de constraint va por Flyway.
- Inyección por constructor (`private val`), nunca `@Autowired` en campos.
- `@Transactional(readOnly = true)` en lecturas, `@Transactional` en escrituras cubriendo toda la operación.
- IDOR: recurso ajeno → 404, no 403. (No aplica aquí: solo `admin` puede llamar estos endpoints y `admin` no tiene `visibilidadRestringida`.)
- No agregar código a un módulo que acceda a tablas/entidades de otro módulo directamente — solo vía su interfaz pública de servicio.
- Cualquier endpoint nuevo debe documentarse en `docs/contrato_api.md` (dueño del contrato) y, si cambia permisos, en `docs/matriz_permisos.md`.
- **Bloqueador conocido de entorno:** en local, Docker Desktop 29 rompe el cliente `docker-java` de Testcontainers. Los tests con `@Tag("integration")` (task `./gradlew integrationTest`) no se pueden ejecutar en esta máquina — solo corren en CI. Escríbelos igual (son parte del TDD y de la cobertura), pero verifica localmente con `./gradlew test` (excluye `integration`), `./gradlew ktlintCheck` y `./gradlew detekt`; la corrección de los tests de integración se confirma cuando corra CI.

---

### Task 1: Migración Flyway — cascada de eliminación de empresa

**Files:**
- Create: `src/main/resources/db/migration/V29__cascada_eliminacion_empresa.sql`
- Modify: `src/test/kotlin/pe/quantum/crm/support/SeedFixtures.kt:27`
- Test: `src/test/kotlin/pe/quantum/crm/db/EmpresaEliminacionCascadaIntegrationTest.kt` (nuevo)

**Interfaces:**
- Consumes: nada (es la base de los Tasks 2 y 3).
- Produces: constraints `oportunidades_id_empresa_fkey`, `tareas_id_empresa_fkey`, `empresa_contactos_id_empresa_fkey` en `ON DELETE CASCADE`. Los Tasks 2 y 3 dependen de que estas constraints ya estén en cascada para que un simple `repository.delete(entidad)` baste.

- [ ] **Step 1: Escribir el test de integración que falla (constraints todavía en RESTRICT)**

Sigue el patrón de `src/test/kotlin/pe/quantum/crm/db/VendedorSyncBackfillIntegrationTest.kt`: inserta datos crudos vía `JdbcTemplate`, ejecuta un `DELETE` real y verifica el resultado.

```kotlin
package pe.quantum.crm.db

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate
import pe.quantum.crm.support.IntegrationTestBase

/**
 * Verifica la cascada de V29 (reglas_negocio.md §11.2): al eliminar una empresa
 * se eliminan sus oportunidades, tareas, eventos y el log de estados, pero los
 * contactos vinculados sobreviven (solo se borra la fila de vinculo).
 */
@Tag("integration")
@SpringBootTest
class EmpresaEliminacionCascadaIntegrationTest : IntegrationTestBase() {
    @Autowired
    lateinit var jdbcTemplate: JdbcTemplate

    private fun id(sql: String): Long = jdbcTemplate.queryForObject(sql, Long::class.java)!!

    private fun count(sql: String): Int = jdbcTemplate.queryForObject(sql, Int::class.java)!!

    @Test
    fun `eliminar una empresa arrastra oportunidad, tarea, evento y log, pero no el contacto vinculado`() {
        val admin =
            id(
                "INSERT INTO empleados (nombres, apellidos, email, rol) " +
                    "VALUES ('Ada', 'Cascada', 'ada.cascada@quantum.pe', 'admin') RETURNING id",
            )
        val financiadora =
            id("INSERT INTO financiadoras (nombre) VALUES ('Financiadora cascada test') RETURNING id")
        val modelo =
            id("INSERT INTO modelos (codigo) VALUES ('MODELO-CASCADA-TEST') RETURNING id")
        val empresa =
            id(
                """
                INSERT INTO empresas
                    (ruc, razon_social, actividad_econ, id_vendedor, estado_sunat, condicion_sunat, direccion_fiscal, created_by, updated_by)
                VALUES
                    ('20888888888', 'Cascada Test S.A.C.', 'Transporte', $admin, 'ACTIVO', 'HABIDO', 'Av. Cascada 1', $admin, $admin)
                RETURNING id
                """.trimIndent(),
            )
        val oportunidad =
            id(
                """
                INSERT INTO oportunidades (id_empresa, id_vendedor, id_financiadora, id_modelo, estado, created_by, updated_by)
                VALUES ($empresa, $admin, $financiadora, $modelo, 'evaluacion_calidda', $admin, $admin)
                RETURNING id
                """.trimIndent(),
            )
        jdbcTemplate.update(
            "INSERT INTO oportunidad_estados_log (id_oportunidad, estado_anterior, estado_nuevo, changed_by) " +
                "VALUES ($oportunidad, NULL, 'evaluacion_calidda', $admin)",
        )
        val tareaDeEmpresa =
            id(
                "INSERT INTO tareas (id_empresa, tipo_accion, created_by, updated_by) " +
                    "VALUES ($empresa, 'llamada', $admin, $admin) RETURNING id",
            )
        val tareaDeOportunidad =
            id(
                "INSERT INTO tareas (id_empresa, id_oportunidad, tipo_accion, created_by, updated_by) " +
                    "VALUES ($empresa, $oportunidad, 'llamada', $admin, $admin) RETURNING id",
            )
        val eventoDeEmpresa =
            id(
                "INSERT INTO eventos (id_empresa, es_personalizado, nombre_personalizado, estado, created_by, updated_by) " +
                    "VALUES ($empresa, true, 'Evento propio de empresa', 'pendiente', $admin, $admin) RETURNING id",
            )
        val contacto =
            id(
                "INSERT INTO contactos (nombres, apellidos, created_by, updated_by) " +
                    "VALUES ('Carlos', 'Contacto', $admin, $admin) RETURNING id",
            )
        jdbcTemplate.update(
            "INSERT INTO empresa_contactos (id_empresa, id_contacto, es_principal) VALUES ($empresa, $contacto, false)",
        )

        jdbcTemplate.update("DELETE FROM empresas WHERE id = $empresa")

        assertThat(count("SELECT COUNT(*) FROM oportunidades WHERE id = $oportunidad")).isZero()
        assertThat(count("SELECT COUNT(*) FROM oportunidad_estados_log WHERE id_oportunidad = $oportunidad")).isZero()
        assertThat(count("SELECT COUNT(*) FROM tareas WHERE id IN ($tareaDeEmpresa, $tareaDeOportunidad)")).isZero()
        assertThat(count("SELECT COUNT(*) FROM eventos WHERE id = $eventoDeEmpresa")).isZero()
        assertThat(count("SELECT COUNT(*) FROM empresa_contactos WHERE id_empresa = $empresa")).isZero()
        assertThat(count("SELECT COUNT(*) FROM contactos WHERE id = $contacto")).isEqualTo(1)
    }
}
```

- [ ] **Step 2: Confirmar que el test fallaría hoy (razonamiento, sin poder ejecutar Testcontainers localmente)**

No se puede correr `./gradlew integrationTest` en esta máquina (Docker Desktop 29, ver Global Constraints). Verifica en su lugar que las constraints actuales son `RESTRICT`:

Run: `grep -n "ON DELETE" src/main/resources/db/migration/V10__create_oportunidades.sql src/main/resources/db/migration/V15__create_tareas.sql src/main/resources/db/migration/V9__create_empresa_contactos.sql`
Expected: las tres muestran `RESTRICT` en el lado `id_empresa` — antes de V29, el `DELETE FROM empresas` del test fallaría con `violates foreign key constraint`.

- [ ] **Step 3: Crear la migración V29**

```sql
-- =============================================================================
-- V29 — Cascada de eliminación de empresa (DELETE /empresas/:id, admin)
--
-- Antes: RESTRICT bloqueaba eliminar una empresa con oportunidades/tareas/
-- contactos vinculados (reglas_negocio.md §11.2 anterior).
--
-- Ahora: eliminar una empresa elimina en cascada sus oportunidades (y con
-- ellas, vía las constraints ya existentes, su log de estados, sus eventos y
-- sus tareas) y sus tareas propias. Los contactos vinculados NO se eliminan:
-- solo se borra la fila de `empresa_contactos` (la relación), nunca la fila
-- de `contactos` — por eso `empresa_contactos_id_contacto_fkey` se queda en
-- RESTRICT.
-- =============================================================================

ALTER TABLE oportunidades
    DROP CONSTRAINT oportunidades_id_empresa_fkey,
    ADD CONSTRAINT oportunidades_id_empresa_fkey
        FOREIGN KEY (id_empresa) REFERENCES empresas(id) ON DELETE CASCADE;

ALTER TABLE tareas
    DROP CONSTRAINT tareas_id_empresa_fkey,
    ADD CONSTRAINT tareas_id_empresa_fkey
        FOREIGN KEY (id_empresa) REFERENCES empresas(id) ON DELETE CASCADE;

ALTER TABLE empresa_contactos
    DROP CONSTRAINT empresa_contactos_id_empresa_fkey,
    ADD CONSTRAINT empresa_contactos_id_empresa_fkey
        FOREIGN KEY (id_empresa) REFERENCES empresas(id) ON DELETE CASCADE;

COMMENT ON COLUMN oportunidades.id_empresa IS 'CASCADE: al eliminar la empresa (admin, hard delete) se eliminan sus oportunidades.';
COMMENT ON COLUMN tareas.id_empresa IS 'CASCADE: al eliminar la empresa (admin, hard delete) se eliminan sus tareas.';
```

- [ ] **Step 4: Actualizar el contador de migraciones en `SeedFixtures.kt`**

`src/test/kotlin/pe/quantum/crm/support/SeedFixtures.kt:27` dice `const val MIGRACIONES_TOTAL = 24` con el comentario *"Actualizar al agregar migraciones"* — ya estaba desactualizado (hay 28 archivos `V1`..`V28` en `src/main/resources/db/migration/`, no 24). Corrígelo al total real después de agregar V29:

```kotlin
    /** Total de migraciones aplicadas (V1..V29). Actualizar al agregar migraciones. */
    const val MIGRACIONES_TOTAL = 29
```

- [ ] **Step 5: Verificar que el proyecto compila y las migraciones son válidas sintácticamente**

Run: `./gradlew compileTestKotlin`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 6: Commit**

```bash
git add src/main/resources/db/migration/V29__cascada_eliminacion_empresa.sql src/test/kotlin/pe/quantum/crm/support/SeedFixtures.kt src/test/kotlin/pe/quantum/crm/db/EmpresaEliminacionCascadaIntegrationTest.kt
git commit -m "feat(db): cascada ON DELETE de empresa hacia oportunidades, tareas y vinculos de contacto"
```

---

### Task 2: `DELETE /api/v1/empresas/{id}`

**Files:**
- Modify: `src/main/kotlin/pe/quantum/crm/domain/empresas/EmpresaService.kt`
- Modify: `src/main/kotlin/pe/quantum/crm/domain/empresas/EmpresaServiceImpl.kt`
- Modify: `src/main/kotlin/pe/quantum/crm/domain/empresas/EmpresaController.kt`
- Modify: `docs/contrato_api.md` (§8 Empresas)
- Modify: `docs/matriz_permisos.md` (§2.2 Empresas)
- Modify: `docs/reglas_negocio.md` (§11.2)
- Test: `src/test/kotlin/pe/quantum/crm/domain/empresas/EmpresaServiceImplTest.kt`
- Test: `src/test/kotlin/pe/quantum/crm/domain/empresas/EmpresaControllerWebMvcTest.kt` (nuevo)

**Interfaces:**
- Consumes: constraints en cascada del Task 1 (no requiere código nuevo de esa parte, solo que ya estén aplicadas).
- Produces: `EmpresaService.eliminar(id: Long)`. No lo consume ningún otro módulo en este plan.

- [ ] **Step 1: Escribir el test unitario que falla**

Añade a `src/test/kotlin/pe/quantum/crm/domain/empresas/EmpresaServiceImplTest.kt` (mismo archivo, misma clase `EmpresaServiceImplTest`, usa el `empresa()` helper ya definido en el archivo):

```kotlin
    @Test
    fun `eliminar borra la empresa cuando existe`() {
        val entidad = empresa()
        every { empresaRepository.findById(1) } returns Optional.of(entidad)
        every { empresaRepository.delete(entidad) } just Runs

        service.eliminar(1)

        verify { empresaRepository.delete(entidad) }
    }

    @Test
    fun `eliminar lanza NoEncontradoException si la empresa no existe`() {
        every { empresaRepository.findById(99) } returns Optional.empty()

        assertThatThrownBy { service.eliminar(99) }
            .isInstanceOf(pe.quantum.crm.shared.exception.NoEncontradoException::class.java)
        verify(exactly = 0) { empresaRepository.delete(any()) }
    }
```

- [ ] **Step 2: Ejecutar el test y verificar que falla**

Run: `./gradlew test --tests "pe.quantum.crm.domain.empresas.EmpresaServiceImplTest"`
Expected: FAIL — `EmpresaService` no tiene el método `eliminar` (error de compilación).

- [ ] **Step 3: Agregar `eliminar` a la interfaz `EmpresaService`**

En `src/main/kotlin/pe/quantum/crm/domain/empresas/EmpresaService.kt`, dentro de `interface EmpresaService { ... }`, después de `cambiarCarteraMaestra` (última función de la interfaz):

```kotlin
    /**
     * Elimina definitivamente la empresa (hard delete, exclusivo admin —
     * verificado en el controller). Cascada de base de datos (V29): arrastra
     * sus oportunidades, tareas, eventos y el log de estados. Los contactos
     * vinculados NO se eliminan, solo se desvinculan.
     */
    fun eliminar(id: Long)
```

- [ ] **Step 4: Implementar `eliminar` en `EmpresaServiceImpl`**

En `src/main/kotlin/pe/quantum/crm/domain/empresas/EmpresaServiceImpl.kt`, después del método `cambiarCarteraMaestra` (línea 319, justo antes del comentario `// ── privados ───...`):

```kotlin
    @Transactional
    override fun eliminar(id: Long) {
        val empresa = entidad(id)
        empresaRepository.delete(empresa)
    }
```

- [ ] **Step 5: Ejecutar el test y verificar que pasa**

Run: `./gradlew test --tests "pe.quantum.crm.domain.empresas.EmpresaServiceImplTest"`
Expected: `BUILD SUCCESSFUL`, ambos tests nuevos en verde.

- [ ] **Step 6: Escribir el test del controller (WebMvcTest) que falla**

Crea `src/test/kotlin/pe/quantum/crm/domain/empresas/EmpresaControllerWebMvcTest.kt`, siguiendo el patrón de `src/test/kotlin/pe/quantum/crm/domain/contactos/ContactoControllerWebMvcTest.kt` (mismo bloque `@SpringBootTest(properties = [...])`, `@AutoConfigureMockMvc`, `@Import(SinBaseDeDatosMocks::class)`):

```kotlin
package pe.quantum.crm.domain.empresas

import com.ninjasquad.springmockk.MockkBean
import io.mockk.every
import io.mockk.just
import io.mockk.Runs
import io.mockk.verify
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.http.HttpHeaders
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.delete
import pe.quantum.crm.config.security.JwtService
import pe.quantum.crm.domain.contactos.ContactoService
import pe.quantum.crm.shared.exception.NoEncontradoException
import pe.quantum.crm.support.SinBaseDeDatosMocks

/** Tests de `DELETE /empresas/:id` (contrato_api.md §8): exclusivo admin, sin cuerpo. */
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
class EmpresaControllerWebMvcTest {
    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var jwtService: JwtService

    @MockkBean
    lateinit var empresaService: EmpresaService

    @MockkBean
    lateinit var contactoService: ContactoService

    @Test
    fun `DELETE empresas id como admin devuelve 204`() {
        every { empresaService.eliminar(7) } just Runs
        val token = jwtService.generateAccessToken(empleadoId = 1, rol = "admin")

        mockMvc.delete("/api/v1/empresas/7") {
            header(HttpHeaders.AUTHORIZATION, "Bearer $token")
        }.andExpect {
            status { isNoContent() }
        }
        verify { empresaService.eliminar(7) }
    }

    @Test
    fun `DELETE empresas id como no-admin devuelve 403`() {
        val token = jwtService.generateAccessToken(empleadoId = 2, rol = "gerencia")

        mockMvc.delete("/api/v1/empresas/7") {
            header(HttpHeaders.AUTHORIZATION, "Bearer $token")
        }.andExpect {
            status { isForbidden() }
            jsonPath("$.error.code") { value("PERMISO_INSUFICIENTE") }
        }
        verify(exactly = 0) { empresaService.eliminar(any()) }
    }

    @Test
    fun `DELETE empresas id inexistente devuelve 404`() {
        every { empresaService.eliminar(99) } throws NoEncontradoException("La empresa no existe")
        val token = jwtService.generateAccessToken(empleadoId = 1, rol = "admin")

        mockMvc.delete("/api/v1/empresas/99") {
            header(HttpHeaders.AUTHORIZATION, "Bearer $token")
        }.andExpect {
            status { isNotFound() }
            jsonPath("$.error.code") { value("NO_ENCONTRADO") }
        }
    }
}
```

- [ ] **Step 7: Ejecutar el test y verificar que falla**

Run: `./gradlew test --tests "pe.quantum.crm.domain.empresas.EmpresaControllerWebMvcTest"`
Expected: FAIL — no existe `DELETE /api/v1/empresas/{id}` (404 en vez de 204/403, o error de compilación si `EmpresaService.eliminar` aún no existiera — pero ya se agregó en el Step 3).

- [ ] **Step 8: Agregar el endpoint al controller**

En `src/main/kotlin/pe/quantum/crm/domain/empresas/EmpresaController.kt`, agrega el import que falta junto a los demás `org.springframework.web.bind.annotation.*`:

```kotlin
import org.springframework.web.bind.annotation.DeleteMapping
```

Y agrega el método al final de la clase `EmpresaController`, después de `cambiarCarteraMaestra`:

```kotlin
    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('admin')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun eliminar(
        @PathVariable id: Long,
    ) {
        empresaService.eliminar(id)
    }
```

- [ ] **Step 9: Ejecutar el test y verificar que pasa**

Run: `./gradlew test --tests "pe.quantum.crm.domain.empresas.EmpresaControllerWebMvcTest"`
Expected: `BUILD SUCCESSFUL`, los 3 tests en verde.

- [ ] **Step 10: Documentar el endpoint en `contrato_api.md`**

En `docs/contrato_api.md`, después de la sección `### PATCH /empresas/:id/cartera-maestra` (termina en la línea con `---` justo antes de `## 9. Contactos`), inserta:

```markdown
### DELETE /empresas/:id
> Elimina definitivamente una empresa y todo lo que cuelga de ella en el pipeline comercial.

**Roles:** `admin`

**Respuesta 204:** sin body.

**Notas:**
- Elimina en cascada sus oportunidades, las tareas y eventos de esas oportunidades, el log de estados, y las tareas/eventos propios de la empresa (sin oportunidad asociada).
- Los contactos vinculados **no** se eliminan: solo se borra el vínculo (`empresa_contactos`). El contacto sigue existiendo y puede estar vinculado a otras empresas.
- Sin restricción por estado: incluye empresas con oportunidades en `facturado`. Operación irreversible.

---
```

Actualiza también la tabla de `## 5. Autorización por rol` (línea ~127-138), agregando una fila:

```markdown
| Eliminar empresa (definitivo, cascada) | ✓ | — | — | — | — |
```

- [ ] **Step 11: Documentar el permiso en `matriz_permisos.md`**

En `docs/matriz_permisos.md`, en la tabla de `### 2.2 Empresas` (línea ~55-62), agrega una fila después de `Mover/liberar Cartera Maestra`:

```markdown
| Eliminar empresa (definitivo, cascada a oportunidades/tareas/eventos) | ✓ | — | — | — | — |
```

- [ ] **Step 12: Actualizar la regla de negocio en `reglas_negocio.md`**

En `docs/reglas_negocio.md`, sección `### 11.2 Eliminación de contactos` (línea 415-419), reemplaza el párrafo final:

Antes:
```markdown
No se puede eliminar una empresa que tiene oportunidades (`ON DELETE RESTRICT` en `oportunidades`).
```

Después:
```markdown
`DELETE /empresas/:id` (exclusivo `admin`) elimina la empresa en cascada: se eliminan sus oportunidades, las tareas y eventos de esas oportunidades, el log de estados, y las tareas/eventos propios de la empresa. Los contactos vinculados nunca se eliminan — solo se borra la fila de `empresa_contactos` (`ON DELETE CASCADE` desde V29). Sin restricción por estado de las oportunidades (incluye `facturado`).
```

- [ ] **Step 13: Verificar todo el módulo**

Run: `./gradlew test ktlintCheck detekt`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 14: Commit**

```bash
git add src/main/kotlin/pe/quantum/crm/domain/empresas/EmpresaService.kt src/main/kotlin/pe/quantum/crm/domain/empresas/EmpresaServiceImpl.kt src/main/kotlin/pe/quantum/crm/domain/empresas/EmpresaController.kt src/test/kotlin/pe/quantum/crm/domain/empresas/EmpresaServiceImplTest.kt src/test/kotlin/pe/quantum/crm/domain/empresas/EmpresaControllerWebMvcTest.kt docs/contrato_api.md docs/matriz_permisos.md docs/reglas_negocio.md
git commit -m "feat(empresas): DELETE /empresas/:id definitivo, exclusivo admin, con cascada"
```

---

### Task 3: `DELETE /api/v1/oportunidades/{id}` + recálculo de `estado_cartera`

**Files:**
- Modify: `src/main/kotlin/pe/quantum/crm/domain/oportunidades/OportunidadService.kt`
- Modify: `src/main/kotlin/pe/quantum/crm/domain/oportunidades/OportunidadServiceImpl.kt`
- Modify: `src/main/kotlin/pe/quantum/crm/domain/oportunidades/OportunidadController.kt`
- Modify: `docs/contrato_api.md` (§10 Oportunidades)
- Modify: `docs/matriz_permisos.md` (§2.4 Oportunidades)
- Modify: `docs/reglas_negocio.md` (§3.3, línea 124)
- Test: `src/test/kotlin/pe/quantum/crm/domain/oportunidades/OportunidadServiceImplTest.kt`
- Test: `src/test/kotlin/pe/quantum/crm/domain/oportunidades/OportunidadControllerWebMvcTest.kt` (nuevo)

**Interfaces:**
- Consumes: `EstadoCarteraService.actualizar(idEmpresa: Long): CambioEstadoCartera?` (ya existe, sin cambios — `src/main/kotlin/pe/quantum/crm/domain/oportunidades/EstadoCarteraService.kt:26`).
- Produces: `OportunidadService.eliminar(id: Long)`. No lo consume ningún otro módulo en este plan.

- [ ] **Step 1: Escribir el test unitario que falla**

Añade a `src/test/kotlin/pe/quantum/crm/domain/oportunidades/OportunidadServiceImplTest.kt` (misma clase, usa el helper `oportunidad(id, idVendedor)` ya definido — nota que `idEmpresa` siempre es `10` en ese helper):

```kotlin
    @Test
    fun `eliminar borra la oportunidad y recalcula el estado de cartera de su empresa`() {
        val entidad = oportunidad(id = 100)
        every { oportunidadRepository.findById(100) } returns Optional.of(entidad)
        every { oportunidadRepository.delete(entidad) } just io.mockk.Runs
        every { estadoCarteraService.actualizar(10) } returns null

        service.eliminar(100)

        verify { oportunidadRepository.delete(entidad) }
        verify { estadoCarteraService.actualizar(10) }
    }

    @Test
    fun `eliminar lanza NoEncontradoException si la oportunidad no existe`() {
        every { oportunidadRepository.findById(999) } returns Optional.empty()

        assertThatThrownBy { service.eliminar(999) }
            .isInstanceOf(pe.quantum.crm.shared.exception.NoEncontradoException::class.java)
        verify(exactly = 0) { oportunidadRepository.delete(any()) }
        verify(exactly = 0) { estadoCarteraService.actualizar(any()) }
    }
```

- [ ] **Step 2: Ejecutar el test y verificar que falla**

Run: `./gradlew test --tests "pe.quantum.crm.domain.oportunidades.OportunidadServiceImplTest"`
Expected: FAIL — `OportunidadService` no tiene el método `eliminar` (error de compilación).

- [ ] **Step 3: Agregar `eliminar` a la interfaz `OportunidadService`**

En `src/main/kotlin/pe/quantum/crm/domain/oportunidades/OportunidadService.kt`, después de `aplicarDescuentoAprobado` (última función de la interfaz):

```kotlin
    /**
     * Elimina definitivamente la oportunidad (hard delete, exclusivo admin —
     * verificado en el controller). Cascada de base de datos (V29): arrastra
     * su log de estados, sus vinculos de contacto, sus eventos y sus tareas.
     * Recalcula `estado_cartera` de la empresa (reglas_negocio.md §3.3) ya que
     * esta oportunidad deja de contar.
     */
    fun eliminar(id: Long)
```

- [ ] **Step 4: Implementar `eliminar` en `OportunidadServiceImpl`**

En `src/main/kotlin/pe/quantum/crm/domain/oportunidades/OportunidadServiceImpl.kt`, después de `datosRecordatorio` (línea 473, justo antes del comentario `// ── privados ───...`):

```kotlin
    @Transactional
    override fun eliminar(id: Long) {
        val oportunidad = entidad(id)
        val idEmpresa = oportunidad.idEmpresa
        oportunidadRepository.delete(oportunidad)
        estadoCarteraService.actualizar(idEmpresa)
    }
```

- [ ] **Step 5: Ejecutar el test y verificar que pasa**

Run: `./gradlew test --tests "pe.quantum.crm.domain.oportunidades.OportunidadServiceImplTest"`
Expected: `BUILD SUCCESSFUL`, ambos tests nuevos en verde.

- [ ] **Step 6: Escribir el test del controller (WebMvcTest) que falla**

Crea `src/test/kotlin/pe/quantum/crm/domain/oportunidades/OportunidadControllerWebMvcTest.kt`:

```kotlin
package pe.quantum.crm.domain.oportunidades

import com.ninjasquad.springmockk.MockkBean
import io.mockk.every
import io.mockk.just
import io.mockk.Runs
import io.mockk.verify
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.http.HttpHeaders
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.delete
import pe.quantum.crm.config.security.JwtService
import pe.quantum.crm.shared.exception.NoEncontradoException
import pe.quantum.crm.support.SinBaseDeDatosMocks

/** Tests de `DELETE /oportunidades/:id` (contrato_api.md §10): exclusivo admin, sin cuerpo. */
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
class OportunidadControllerWebMvcTest {
    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var jwtService: JwtService

    @MockkBean
    lateinit var oportunidadService: OportunidadService

    @Test
    fun `DELETE oportunidades id como admin devuelve 204`() {
        every { oportunidadService.eliminar(50) } just Runs
        val token = jwtService.generateAccessToken(empleadoId = 1, rol = "admin")

        mockMvc.delete("/api/v1/oportunidades/50") {
            header(HttpHeaders.AUTHORIZATION, "Bearer $token")
        }.andExpect {
            status { isNoContent() }
        }
        verify { oportunidadService.eliminar(50) }
    }

    @Test
    fun `DELETE oportunidades id como no-admin devuelve 403`() {
        val token = jwtService.generateAccessToken(empleadoId = 2, rol = "vendedor")

        mockMvc.delete("/api/v1/oportunidades/50") {
            header(HttpHeaders.AUTHORIZATION, "Bearer $token")
        }.andExpect {
            status { isForbidden() }
            jsonPath("$.error.code") { value("PERMISO_INSUFICIENTE") }
        }
        verify(exactly = 0) { oportunidadService.eliminar(any()) }
    }

    @Test
    fun `DELETE oportunidades id inexistente devuelve 404`() {
        every { oportunidadService.eliminar(999) } throws NoEncontradoException("La oportunidad no existe")
        val token = jwtService.generateAccessToken(empleadoId = 1, rol = "admin")

        mockMvc.delete("/api/v1/oportunidades/999") {
            header(HttpHeaders.AUTHORIZATION, "Bearer $token")
        }.andExpect {
            status { isNotFound() }
            jsonPath("$.error.code") { value("NO_ENCONTRADO") }
        }
    }
}
```

- [ ] **Step 7: Ejecutar el test y verificar que falla**

Run: `./gradlew test --tests "pe.quantum.crm.domain.oportunidades.OportunidadControllerWebMvcTest"`
Expected: FAIL — no existe `DELETE /api/v1/oportunidades/{id}`.

- [ ] **Step 8: Agregar el endpoint al controller**

En `src/main/kotlin/pe/quantum/crm/domain/oportunidades/OportunidadController.kt`, agrega el import que falta junto a los demás `org.springframework.web.bind.annotation.*`:

```kotlin
import org.springframework.security.access.prepost.PreAuthorize
```

Y agrega el método al final de la clase `OportunidadController`, después de `desvincularContacto`:

```kotlin
    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('admin')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun eliminar(
        @PathVariable id: Long,
    ) {
        oportunidadService.eliminar(id)
    }
```

- [ ] **Step 9: Ejecutar el test y verificar que pasa**

Run: `./gradlew test --tests "pe.quantum.crm.domain.oportunidades.OportunidadControllerWebMvcTest"`
Expected: `BUILD SUCCESSFUL`, los 3 tests en verde.

- [ ] **Step 10: Documentar el endpoint en `contrato_api.md`**

En `docs/contrato_api.md`, después de la sección `### PUT /oportunidades/:id` (termina en `---` justo antes de `### PATCH /oportunidades/:id/estado`, línea 842), inserta:

```markdown
### DELETE /oportunidades/:id
> Elimina definitivamente una oportunidad.

**Roles:** `admin`

**Respuesta 204:** sin body.

**Notas:**
- Elimina en cascada su log de estados, sus vínculos de contacto (`oportunidad_contactos`), sus eventos y sus tareas. Los contactos en sí **no** se eliminan, solo el vínculo.
- Recalcula `estado_cartera` de la empresa tras eliminar (reglas_negocio.md §3.3): si la empresa se queda sin oportunidades activas/facturadas, vuelve a su estado manual (o `null`).
- Sin restricción por estado: incluye oportunidades en `facturado`. Operación irreversible.

---
```

Actualiza también la tabla de `## 5. Autorización por rol` (la misma fila que agregaste en el Task 2, ahora extendida):

```markdown
| Eliminar empresa / oportunidad (definitivo, cascada) | ✓ | — | — | — | — |
```

(Reemplaza la fila `Eliminar empresa (definitivo, cascada)` agregada en el Task 2 por esta versión combinada, para no duplicar filas casi idénticas.)

- [ ] **Step 11: Documentar el permiso en `matriz_permisos.md`**

En `docs/matriz_permisos.md`, en la tabla de `### 2.4 Oportunidades` (línea ~87-95), agrega una fila después de `Ver log de estados`:

```markdown
| Eliminar oportunidad (definitivo, cascada a tareas/eventos/log) | ✓ | — | — | — | — |
```

- [ ] **Step 12: Actualizar la regla de negocio en `reglas_negocio.md`**

En `docs/reglas_negocio.md`, línea 124, dentro de `### 3.3 Cuándo se llama`:

Antes:
```markdown
- Al **eliminar** una oportunidad (si se implementa) → recalcular
```

Después:
```markdown
- Al **eliminar** una oportunidad (`DELETE /oportunidades/:id`, exclusivo `admin`) → recalcular
```

- [ ] **Step 13: Verificar todo el módulo**

Run: `./gradlew test ktlintCheck detekt`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 14: Commit**

```bash
git add src/main/kotlin/pe/quantum/crm/domain/oportunidades/OportunidadService.kt src/main/kotlin/pe/quantum/crm/domain/oportunidades/OportunidadServiceImpl.kt src/main/kotlin/pe/quantum/crm/domain/oportunidades/OportunidadController.kt src/test/kotlin/pe/quantum/crm/domain/oportunidades/OportunidadServiceImplTest.kt src/test/kotlin/pe/quantum/crm/domain/oportunidades/OportunidadControllerWebMvcTest.kt docs/contrato_api.md docs/matriz_permisos.md docs/reglas_negocio.md
git commit -m "feat(oportunidades): DELETE /oportunidades/:id definitivo, exclusivo admin, recalcula estado_cartera"
```

---

### Task 4: Verificación final y caso `facturado`

**Files:**
- Test: `src/test/kotlin/pe/quantum/crm/domain/oportunidades/OportunidadServiceImplTest.kt`

**Interfaces:**
- Consumes: `OportunidadServiceImpl.eliminar` (Task 3).
- Produces: nada nuevo — cierre de cobertura.

- [ ] **Step 1: Escribir el test que confirma que no hay bloqueo por estado `facturado`**

Añade a `OportunidadServiceImplTest.kt` (reutiliza el helper `oportunidad`, pero construyendo una copia en `facturado` — revisa la firma exacta del data class `Oportunidad` en `src/main/kotlin/pe/quantum/crm/domain/oportunidades/Oportunidad.kt` antes de escribir el `.copy(...)`, ya que `estado` es `var`):

```kotlin
    @Test
    fun `eliminar permite borrar una oportunidad en estado facturado sin bloqueo de negocio`() {
        val entidad = oportunidad(id = 200)
        entidad.estado = pe.quantum.crm.shared.enums.EstadoOportunidad.facturado
        every { oportunidadRepository.findById(200) } returns Optional.of(entidad)
        every { oportunidadRepository.delete(entidad) } just io.mockk.Runs
        every { estadoCarteraService.actualizar(10) } returns null

        service.eliminar(200)

        verify { oportunidadRepository.delete(entidad) }
    }
```

- [ ] **Step 2: Ejecutar el test y verificar que pasa (ya debería pasar sin cambios de producción, confirma la decisión de diseño)**

Run: `./gradlew test --tests "pe.quantum.crm.domain.oportunidades.OportunidadServiceImplTest"`
Expected: `BUILD SUCCESSFUL` — este test pasa con la implementación del Task 3 sin ningún cambio adicional, porque `eliminar()` deliberadamente no valida `estado` (decisión 2 del spec).

- [ ] **Step 3: Correr la suite completa del proyecto**

Run: `./gradlew test ktlintCheck detekt`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 4: Commit**

```bash
git add src/test/kotlin/pe/quantum/crm/domain/oportunidades/OportunidadServiceImplTest.kt
git commit -m "test(oportunidades): confirma que eliminar no bloquea oportunidades facturadas"
```

---

## Notas para quien ejecute este plan

- Los tests de integración (`@Tag("integration")`, Task 1) no se pueden correr en esta máquina por el bloqueador de Docker Desktop 29 (ver Global Constraints). Escríbelos y razona su corrección con cuidado; se confirman en CI.
- Antes de cada commit, corre `./gradlew test` completo (no solo el archivo tocado) para detectar regresiones en otros módulos.
- `koverVerify` y `dependencyCheckAnalyze` no están en el flujo de cada tarea de este plan; si el pipeline de CI los exige antes de mergear, corre `./gradlew koverVerify` al final de la Task 4.
