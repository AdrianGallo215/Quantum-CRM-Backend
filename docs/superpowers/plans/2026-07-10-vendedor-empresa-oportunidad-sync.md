# Sincronización vendedor empresa-oportunidad — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Reasignar el vendedor de una empresa debe cascadear automáticamente a todas sus oportunidades activas, eliminando la posibilidad de que el vendedor de una empresa y el de sus oportunidades activas diverjan.

**Architecture:** `EmpresaServiceImpl.reasignarVendedor()` publica un `ApplicationEvent` de Spring dentro de su transacción; un `@EventListener` síncrono en `OportunidadServiceImpl` (que ya depende de `EmpresaService`, evitando un ciclo de beans) hace la cascada sobre las oportunidades activas y reutiliza la notificación `oportunidad_traspasada`. Se elimina el traspaso manual por oportunidad individual. Una migración Flyway de backfill corrige el drift ya existente en los datos.

**Tech Stack:** Kotlin 1.9, Spring Boot 3.2 (Spring events, Spring Data JPA), Flyway, JUnit 5 + MockK + AssertJ, Testcontainers (PostgreSQL 16).

## Global Constraints

- TDD obligatorio: test que falla antes que el código, en cada task.
- `spring.jpa.hibernate.ddl-auto=validate` — nunca tocar el schema fuera de Flyway.
- Inyección por constructor únicamente.
- `@Transactional(readOnly = true)` en lecturas, `@Transactional` cubriendo toda la escritura.
- Un módulo nunca accede a tablas/entidades de otro módulo, solo a su interfaz de servicio pública.
- Estados activos de oportunidad: `evaluacion_calidda`, `documentos_legales` (constante ya existente `EstadoCarteraService.ESTADOS_ACTIVOS`). Estados cerrados (`facturado`, `cerrado`) nunca cambian de vendedor.
- `./gradlew test` corre los unitarios (sin Docker). `./gradlew integrationTest` corre los de Testcontainers — en esta máquina Docker Desktop 29 rompe Testcontainers en local (ver comentario en `IntegrationTestBase.kt`); esos tests se validan compilando y se confirman en CI.
- Antes de cada commit: los tests afectados deben pasar.

---

### Task 1: `EmpresaServiceImpl` publica `VendedorEmpresaReasignadoEvent`

**Files:**
- Create: `src/main/kotlin/pe/quantum/crm/domain/empresas/VendedorEmpresaReasignadoEvent.kt`
- Modify: `src/main/kotlin/pe/quantum/crm/domain/empresas/EmpresaServiceImpl.kt:33-38` (constructor), `:195-218` (`reasignarVendedor`)
- Modify: `src/main/kotlin/pe/quantum/crm/domain/empresas/EmpresaService.kt:53-58` (comentario de `reasignarVendedor`)
- Test: `src/test/kotlin/pe/quantum/crm/domain/empresas/EmpresaServiceImplTest.kt`

**Interfaces:**
- Produces: `data class VendedorEmpresaReasignadoEvent(val idEmpresa: Long, val idVendedorNuevo: Long, val idActor: Long)` en el paquete `pe.quantum.crm.domain.empresas`. Task 2 lo consume tal cual (mismo tipo, mismos nombres de campo).

- [ ] **Step 1: Escribir el test que falla**

Edita `src/test/kotlin/pe/quantum/crm/domain/empresas/EmpresaServiceImplTest.kt`. Agrega el import y el mock del publisher, actualiza la construcción del `service`, y agrega el test nuevo:

```kotlin
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.Runs
import io.mockk.slot
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.context.ApplicationEventPublisher
import pe.quantum.crm.domain.empleados.EmpleadoService
import pe.quantum.crm.domain.empleados.dto.EmpleadoResumen
import pe.quantum.crm.domain.notificaciones.EntidadNotificacion
import pe.quantum.crm.domain.notificaciones.NotificacionService
import pe.quantum.crm.domain.notificaciones.TipoNotificacion
import pe.quantum.crm.shared.enums.EstadoCartera
import pe.quantum.crm.shared.security.UsuarioActual
import java.time.LocalDateTime
import java.util.Optional

class EmpresaServiceImplTest {
    private val empresaRepository = mockk<EmpresaRepository>()
    private val empleadoService = mockk<EmpleadoService>()
    private val notificacionService = mockk<NotificacionService>(relaxed = true)
    private val eventPublisher = mockk<ApplicationEventPublisher>(relaxed = true)
    private val service = EmpresaServiceImpl(empresaRepository, empleadoService, notificacionService, eventPublisher)
    // ... (deja el resto del archivo igual)
```

Agrega este test nuevo al final de la clase, antes de la llave de cierre:

```kotlin
    @Test
    fun `reasignarVendedor publica VendedorEmpresaReasignadoEvent con los datos del cambio`() {
        val entidad = empresa()
        every { empresaRepository.findById(1) } returns Optional.of(entidad)
        every { empleadoService.existeActivo(2) } returns true
        every { empresaRepository.save(entidad) } returns entidad
        every { empleadoService.resumenPorIds(listOf(9)) } returns
            mapOf(9L to EmpleadoResumen(id = 9, nombres = "Ana", apellidos = "Diaz"))
        val evento = slot<VendedorEmpresaReasignadoEvent>()
        every { eventPublisher.publishEvent(capture(evento)) } just Runs

        service.reasignarVendedor(1, 2, UsuarioActual(id = 9, rol = "jdv"))

        assertThat(evento.captured).isEqualTo(VendedorEmpresaReasignadoEvent(idEmpresa = 1, idVendedorNuevo = 2, idActor = 9))
    }
```

- [ ] **Step 2: Ejecutar el test y verificar que falla**

Run: `./gradlew test --tests "pe.quantum.crm.domain.empresas.EmpresaServiceImplTest"`
Expected: FAIL — no compila (`VendedorEmpresaReasignadoEvent` no existe, `EmpresaServiceImpl` no acepta 4 argumentos).

- [ ] **Step 3: Crear el evento**

Crea `src/main/kotlin/pe/quantum/crm/domain/empresas/VendedorEmpresaReasignadoEvent.kt`:

```kotlin
package pe.quantum.crm.domain.empresas

/**
 * Se publica cuando `EmpresaServiceImpl.reasignarVendedor` cambia el vendedor de
 * una empresa. `OportunidadServiceImpl` lo escucha para cascadear el mismo
 * vendedor a las oportunidades activas de la empresa (reglas_negocio.md §8).
 */
data class VendedorEmpresaReasignadoEvent(
    val idEmpresa: Long,
    val idVendedorNuevo: Long,
    val idActor: Long,
)
```

- [ ] **Step 4: Publicar el evento desde `reasignarVendedor`**

En `src/main/kotlin/pe/quantum/crm/domain/empresas/EmpresaServiceImpl.kt`, agrega el import y el nuevo parámetro del constructor:

```kotlin
import org.springframework.context.ApplicationEventPublisher
```

```kotlin
@Service
class EmpresaServiceImpl(
    private val empresaRepository: EmpresaRepository,
    private val empleadoService: EmpleadoService,
    private val notificacionService: NotificacionService,
    private val eventPublisher: ApplicationEventPublisher,
) : EmpresaService {
```

Modifica el cuerpo de `reasignarVendedor` (líneas 195-218) para publicar el evento después de guardar:

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
        eventPublisher.publishEvent(VendedorEmpresaReasignadoEvent(idEmpresa = id, idVendedorNuevo = idVendedor, idActor = usuario.id))
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

En `src/main/kotlin/pe/quantum/crm/domain/empresas/EmpresaService.kt`, actualiza el comentario de la interfaz (líneas 53-58) para reflejar la cascada:

```kotlin
    /**
     * Reasignacion de vendedor (solo admin/gerente/jdv — verificado en controller).
     * Notifica al vendedor destino. Publica `VendedorEmpresaReasignadoEvent`, que
     * cascade el mismo vendedor a las oportunidades activas de la empresa
     * (reglas_negocio.md §8).
     */
    fun reasignarVendedor(
        id: Long,
        idVendedor: Long,
        usuario: UsuarioActual,
    ): Long
```

- [ ] **Step 5: Ejecutar el test y verificar que pasa**

Run: `./gradlew test --tests "pe.quantum.crm.domain.empresas.EmpresaServiceImplTest"`
Expected: PASS (todos los tests de la clase, incluido el nuevo).

- [ ] **Step 6: Commit**

```bash
git add src/main/kotlin/pe/quantum/crm/domain/empresas/VendedorEmpresaReasignadoEvent.kt src/main/kotlin/pe/quantum/crm/domain/empresas/EmpresaServiceImpl.kt src/main/kotlin/pe/quantum/crm/domain/empresas/EmpresaService.kt src/test/kotlin/pe/quantum/crm/domain/empresas/EmpresaServiceImplTest.kt
git commit -m "feat(empresas): publicar VendedorEmpresaReasignadoEvent al reasignar vendedor"
```

---

### Task 2: Cascada en `OportunidadServiceImpl` y eliminación del traspaso manual

**Files:**
- Modify: `src/main/kotlin/pe/quantum/crm/domain/oportunidades/OportunidadRepository.kt`
- Modify: `src/main/kotlin/pe/quantum/crm/domain/oportunidades/OportunidadServiceImpl.kt`
- Modify: `src/main/kotlin/pe/quantum/crm/domain/oportunidades/OportunidadService.kt`
- Modify: `src/main/kotlin/pe/quantum/crm/domain/oportunidades/OportunidadController.kt`
- Modify: `src/main/kotlin/pe/quantum/crm/domain/oportunidades/dto/OportunidadDtos.kt`
- Test: `src/test/kotlin/pe/quantum/crm/domain/oportunidades/OportunidadServiceImplTest.kt`

**Interfaces:**
- Consumes: `VendedorEmpresaReasignadoEvent(idEmpresa, idVendedorNuevo, idActor)` de Task 1. `EstadoCarteraService.ESTADOS_ACTIVOS` (ya existe: `listOf(EstadoOportunidad.evaluacion_calidda, EstadoOportunidad.documentos_legales)`).
- Produces: `OportunidadRepository.findByIdEmpresaAndEstadoIn(idEmpresa: Long, estados: Collection<EstadoOportunidad>): List<Oportunidad>`.

- [ ] **Step 1: Escribir los tests que fallan**

En `src/test/kotlin/pe/quantum/crm/domain/oportunidades/OportunidadServiceImplTest.kt`:

1. Agrega el import `pe.quantum.crm.domain.empresas.VendedorEmpresaReasignadoEvent`.
2. Da un parámetro `id` al helper `oportunidad()` (todas las llamadas existentes usan nombres, así que no rompe nada):

```kotlin
    private fun oportunidad(
        id: Long = 100,
        idVendedor: Long = 1,
    ) =
        Oportunidad(
            id = id,
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
```

3. **Borra** el test `` `traspasar notifica al vendedor destino` `` (líneas 111-135) — el endpoint que prueba desaparece en este task.
4. Agrega estos dos tests nuevos al final de la clase:

```kotlin
    @Test
    fun `onVendedorEmpresaReasignado actualiza y notifica las oportunidades activas con vendedor distinto`() {
        val activa = oportunidad(id = 100, idVendedor = 1)
        val yaAsignada = oportunidad(id = 101, idVendedor = 2)
        every {
            oportunidadRepository.findByIdEmpresaAndEstadoIn(10, EstadoCarteraService.ESTADOS_ACTIVOS)
        } returns listOf(activa, yaAsignada)
        every { oportunidadRepository.saveAll(listOf(activa)) } returns listOf(activa)
        every { empleadoService.resumenPorIds(listOf(9)) } returns
            mapOf(9L to EmpleadoResumen(id = 9, nombres = "Aldo", apellidos = "Martinez"))
        every { empresaService.resumenPorIds(listOf(10)) } returns
            mapOf(10L to EmpresaResumen(id = 10, razonSocial = "Kincar S.A.C.", distrito = null))

        service.onVendedorEmpresaReasignado(VendedorEmpresaReasignadoEvent(idEmpresa = 10, idVendedorNuevo = 2, idActor = 9))

        assertThat(activa.idVendedor).isEqualTo(2)
        assertThat(activa.updatedBy).isEqualTo(9)
        assertThat(yaAsignada.idVendedor).isEqualTo(2)
        verify(exactly = 1) {
            notificacionService.notificar(
                destinatarios = setOf(2L),
                idActor = 9L,
                tipo = TipoNotificacion.oportunidad_traspasada,
                mensaje = "Aldo Martinez te traspasó la oportunidad de Kincar S.A.C.",
                entidadTipo = EntidadNotificacion.oportunidad,
                entidadId = 100L,
            )
        }
    }

    @Test
    fun `onVendedorEmpresaReasignado no hace nada si ninguna oportunidad activa cambia de vendedor`() {
        val yaAsignada = oportunidad(id = 101, idVendedor = 2)
        every {
            oportunidadRepository.findByIdEmpresaAndEstadoIn(10, EstadoCarteraService.ESTADOS_ACTIVOS)
        } returns listOf(yaAsignada)

        service.onVendedorEmpresaReasignado(VendedorEmpresaReasignadoEvent(idEmpresa = 10, idVendedorNuevo = 2, idActor = 9))

        verify(exactly = 0) { oportunidadRepository.saveAll(any<List<Oportunidad>>()) }
        verify(exactly = 0) { notificacionService.notificar(any(), any(), any(), any(), any(), any()) }
    }
```

- [ ] **Step 2: Ejecutar los tests y verificar que fallan**

Run: `./gradlew test --tests "pe.quantum.crm.domain.oportunidades.OportunidadServiceImplTest"`
Expected: FAIL — no compila (`onVendedorEmpresaReasignado` y `findByIdEmpresaAndEstadoIn` no existen todavía).

- [ ] **Step 3: Repository — agregar la query**

En `src/main/kotlin/pe/quantum/crm/domain/oportunidades/OportunidadRepository.kt`, agrega el método a `OportunidadRepository`:

```kotlin
interface OportunidadRepository :
    JpaRepository<Oportunidad, Long>,
    JpaSpecificationExecutor<Oportunidad> {
    fun existsByIdEmpresaAndEstado(
        idEmpresa: Long,
        estado: EstadoOportunidad,
    ): Boolean

    fun existsByIdEmpresaAndEstadoIn(
        idEmpresa: Long,
        estados: Collection<EstadoOportunidad>,
    ): Boolean

    fun findByIdEmpresaAndEstadoIn(
        idEmpresa: Long,
        estados: Collection<EstadoOportunidad>,
    ): List<Oportunidad>
}
```

- [ ] **Step 4: Servicio — agregar el listener y quitar `traspasar`**

En `src/main/kotlin/pe/quantum/crm/domain/oportunidades/OportunidadServiceImpl.kt`, agrega el import:

```kotlin
import org.springframework.context.event.EventListener
import pe.quantum.crm.domain.empresas.VendedorEmpresaReasignadoEvent
```

Reemplaza el método `traspasar` (líneas 234-259) por el listener:

```kotlin
    /**
     * Cascada de vendedor (reglas §8): al reasignar `empresas.id_vendedor`, todas
     * las oportunidades activas de esa empresa heredan el mismo vendedor. El
     * listener corre sincrono, dentro de la misma transaccion que publico el
     * evento (`EmpresaServiceImpl.reasignarVendedor`) — si falla, tambien se
     * revierte la reasignacion de la empresa (atomicidad, reglas §1.2).
     */
    @EventListener
    @Transactional
    fun onVendedorEmpresaReasignado(event: VendedorEmpresaReasignadoEvent) {
        val activas =
            oportunidadRepository
                .findByIdEmpresaAndEstadoIn(event.idEmpresa, EstadoCarteraService.ESTADOS_ACTIVOS)
                .filter { it.idVendedor != event.idVendedorNuevo }
        if (activas.isEmpty()) {
            return
        }
        val ahora = LocalDateTime.now()
        activas.forEach {
            it.idVendedor = event.idVendedorNuevo
            it.updatedAt = ahora
            it.updatedBy = event.idActor
        }
        oportunidadRepository.saveAll(activas)
        val actor = empleadoService.resumenPorIds(listOf(event.idActor))[event.idActor]
        val empresa = empresaService.resumenPorIds(listOf(event.idEmpresa))[event.idEmpresa]
        activas.forEach {
            notificacionService.notificar(
                destinatarios = setOf(event.idVendedorNuevo),
                idActor = event.idActor,
                tipo = TipoNotificacion.oportunidad_traspasada,
                mensaje = "${actor?.nombreCompleto()} te traspasó la oportunidad de ${empresa?.razonSocial}",
                entidadTipo = EntidadNotificacion.oportunidad,
                entidadId = requireNotNull(it.id),
            )
        }
    }
```

En `src/main/kotlin/pe/quantum/crm/domain/oportunidades/OportunidadService.kt`, elimina el método `traspasar` de la interfaz (líneas 35-40):

```kotlin
    fun cambiarEstado(
        id: Long,
        request: CambiarEstadoRequest,
        usuario: UsuarioActual,
    ): CambioEstadoDto

    fun log(
        id: Long,
        usuario: UsuarioActual,
    ): List<LogEstadoDto>
```

(el bloque `/** Traspaso de vendedor... */ fun traspasar(...)` que estaba entre ambos se borra por completo).

- [ ] **Step 5: Controller — quitar el endpoint**

En `src/main/kotlin/pe/quantum/crm/domain/oportunidades/OportunidadController.kt`, elimina el método completo:

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

`PreAuthorize` y `TraspasarVendedorRequest` no se usan en ningún otro método de este archivo, así que elimina también ambos imports:

```kotlin
import org.springframework.security.access.prepost.PreAuthorize
```
```kotlin
import pe.quantum.crm.domain.oportunidades.dto.TraspasarVendedorRequest
```

- [ ] **Step 6: DTO — quitar `TraspasarVendedorRequest`**

En `src/main/kotlin/pe/quantum/crm/domain/oportunidades/dto/OportunidadDtos.kt`, elimina:

```kotlin
data class TraspasarVendedorRequest(
    val idVendedor: Long,
)
```

- [ ] **Step 7: Ejecutar los tests y verificar que pasan**

Run: `./gradlew test --tests "pe.quantum.crm.domain.oportunidades.OportunidadServiceImplTest"`
Expected: PASS (todos los tests de la clase).

Run: `./gradlew compileKotlin compileTestKotlin`
Expected: BUILD SUCCESSFUL (confirma que controller/DTO/interfaz quedaron consistentes en todo el módulo).

- [ ] **Step 8: Commit**

```bash
git add src/main/kotlin/pe/quantum/crm/domain/oportunidades/OportunidadRepository.kt src/main/kotlin/pe/quantum/crm/domain/oportunidades/OportunidadServiceImpl.kt src/main/kotlin/pe/quantum/crm/domain/oportunidades/OportunidadService.kt src/main/kotlin/pe/quantum/crm/domain/oportunidades/OportunidadController.kt src/main/kotlin/pe/quantum/crm/domain/oportunidades/dto/OportunidadDtos.kt src/test/kotlin/pe/quantum/crm/domain/oportunidades/OportunidadServiceImplTest.kt
git commit -m "feat(oportunidades): cascadear vendedor via evento, eliminar traspaso manual"
```

---

### Task 3: Backfill de datos existentes (migración Flyway)

**Files:**
- Create: `src/main/resources/db/migration/V23__sync_oportunidad_vendedor_activas.sql`
- Modify: `src/test/kotlin/pe/quantum/crm/support/SeedFixtures.kt:27`
- Test: `src/test/kotlin/pe/quantum/crm/db/VendedorSyncBackfillIntegrationTest.kt`

**Interfaces:**
- Ninguna — esta task no expone código Kotlin nuevo a otras tasks.

- [ ] **Step 1: Escribir el test de integración que falla**

Crea `src/test/kotlin/pe/quantum/crm/db/VendedorSyncBackfillIntegrationTest.kt`:

```kotlin
package pe.quantum.crm.db

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.core.io.ClassPathResource
import org.springframework.jdbc.core.JdbcTemplate
import pe.quantum.crm.support.IntegrationTestBase

/**
 * Verifica el backfill de V23 (drift previo entre `empresas.id_vendedor` y
 * `oportunidades.id_vendedor`, reglas_negocio.md §8): re-ejecuta el SQL real de
 * la migracion contra datos sembrados a mano con drift intencional. Flyway ya
 * corrio V23 al levantar el contenedor (sin filas todavia, no-op); este test
 * prueba el mismo SQL contra datos insertados despues.
 */
@Tag("integration")
@SpringBootTest
class VendedorSyncBackfillIntegrationTest : IntegrationTestBase() {
    @Autowired
    lateinit var jdbcTemplate: JdbcTemplate

    @Test
    fun `V23 sincroniza el vendedor de oportunidades activas y respeta las cerradas`() {
        val vendedorA =
            jdbcTemplate.queryForObject(
                "INSERT INTO empleados (nombres, apellidos, email, rol) " +
                    "VALUES ('Ana', 'Diaz', 'ana.backfill@quantum.pe', 'vendedor') RETURNING id",
                Long::class.java,
            )!!
        val vendedorB =
            jdbcTemplate.queryForObject(
                "INSERT INTO empleados (nombres, apellidos, email, rol) " +
                    "VALUES ('Luis', 'Soto', 'luis.backfill@quantum.pe', 'vendedor') RETURNING id",
                Long::class.java,
            )!!
        val financiadora =
            jdbcTemplate.queryForObject(
                "INSERT INTO financiadoras (nombre) VALUES ('Financiadora backfill test') RETURNING id",
                Long::class.java,
            )!!
        val empresa =
            jdbcTemplate.queryForObject(
                """
                INSERT INTO empresas
                    (ruc, razon_social, actividad_econ, id_vendedor, estado_sunat, condicion_sunat, direccion_fiscal, created_by, updated_by)
                VALUES
                    ('20999999999', 'Backfill Test S.A.C.', 'Transporte', $vendedorB, 'ACTIVO', 'HABIDO', 'Av. Test 123', $vendedorA, $vendedorA)
                RETURNING id
                """.trimIndent(),
                Long::class.java,
            )!!
        val activa =
            jdbcTemplate.queryForObject(
                """
                INSERT INTO oportunidades (id_empresa, id_vendedor, id_financiadora, estado, created_by, updated_by)
                VALUES ($empresa, $vendedorA, $financiadora, 'evaluacion_calidda', $vendedorA, $vendedorA)
                RETURNING id
                """.trimIndent(),
                Long::class.java,
            )!!
        val cerrada =
            jdbcTemplate.queryForObject(
                """
                INSERT INTO oportunidades (id_empresa, id_vendedor, id_financiadora, estado, motivo_cierre, created_by, updated_by)
                VALUES ($empresa, $vendedorA, $financiadora, 'cerrado', 'Cliente declino', $vendedorA, $vendedorA)
                RETURNING id
                """.trimIndent(),
                Long::class.java,
            )!!

        val sql =
            ClassPathResource("db/migration/V23__sync_oportunidad_vendedor_activas.sql")
                .inputStream.bufferedReader().readText()
        jdbcTemplate.execute(sql)

        val vendedorActiva = jdbcTemplate.queryForObject("SELECT id_vendedor FROM oportunidades WHERE id = $activa", Long::class.java)
        val vendedorCerrada = jdbcTemplate.queryForObject("SELECT id_vendedor FROM oportunidades WHERE id = $cerrada", Long::class.java)
        assertThat(vendedorActiva).isEqualTo(vendedorB)
        assertThat(vendedorCerrada).isEqualTo(vendedorA)
    }
}
```

Run: `./gradlew compileTestKotlin`
Expected: FAIL — `ClassPathResource("db/migration/V23__sync_oportunidad_vendedor_activas.sql")` no existe todavía (el `compileTestKotlin` sí compila, pero si prefieres confirmar el fallo en ejecución real, este test específicamente solo puede correr en CI por el bloqueo de Docker 29 local — ver Global Constraints. Verifica igual que el archivo de migración no existe con `ls src/main/resources/db/migration/V23*` antes del Step 2).

- [ ] **Step 2: Crear la migración**

Crea `src/main/resources/db/migration/V23__sync_oportunidad_vendedor_activas.sql`:

```sql
-- =============================================================================
-- V23 — Backfill: sincronizar id_vendedor de oportunidades activas con su empresa
-- Corrige el drift causado por reasignaciones de empresa anteriores a este cambio
-- de regla (reglas_negocio.md §8): desde ahora, el vendedor de una oportunidad
-- activa siempre debe igualar al vendedor de su empresa. Corrida unica de backfill;
-- de aqui en adelante la sincronizacion la hace el evento VendedorEmpresaReasignadoEvent.
-- =============================================================================

UPDATE oportunidades o
SET id_vendedor = e.id_vendedor,
    updated_at = CURRENT_TIMESTAMP
FROM empresas e
WHERE o.id_empresa = e.id
  AND o.estado NOT IN ('facturado', 'cerrado')
  AND e.id_vendedor IS NOT NULL
  AND o.id_vendedor <> e.id_vendedor;
```

- [ ] **Step 3: Actualizar el contador de migraciones**

En `src/test/kotlin/pe/quantum/crm/support/SeedFixtures.kt:27`, cambia:

```kotlin
    /** Total de migraciones aplicadas (V1..V23). Actualizar al agregar migraciones. */
    const val MIGRACIONES_TOTAL = 23
```

- [ ] **Step 4: Verificar**

Run: `./gradlew compileTestKotlin`
Expected: BUILD SUCCESSFUL.

Run: `./gradlew integrationTest --tests "pe.quantum.crm.db.VendedorSyncBackfillIntegrationTest" --tests "pe.quantum.crm.db.SchemaMigrationIntegrationTest"`
Expected: PASS en CI. En esta máquina (Docker Desktop 29) es esperable que falle por el bloqueo de Testcontainers documentado en `IntegrationTestBase.kt` — si falla, confirma que el error es de arranque de contenedor (Docker/Ryuk), no un fallo de aserción, y continúa; CI lo valida.

- [ ] **Step 5: Commit**

```bash
git add src/main/resources/db/migration/V23__sync_oportunidad_vendedor_activas.sql src/test/kotlin/pe/quantum/crm/support/SeedFixtures.kt src/test/kotlin/pe/quantum/crm/db/VendedorSyncBackfillIntegrationTest.kt
git commit -m "fix(db): backfill V23 para sincronizar vendedor de oportunidades activas con su empresa"
```

---

### Task 4: Actualizar documentación

**Files:**
- Modify: `docs/reglas_negocio.md`
- Modify: `docs/contrato_api.md`
- Modify: `docs/matriz_permisos.md`

**Interfaces:** ninguna — solo texto.

- [ ] **Step 1: `reglas_negocio.md` §1.1**

Reemplaza el párrafo (línea 28):

```
Ningún dato puede vivir en dos lugares. Si un valor es derivado de otro, se calcula en el backend y se lee vía JOIN. No existen campos espejo. La única excepción aceptada es el snapshot de `id_vendedor` en `oportunidades`, documentada explícitamente en la sección 8.
```

por:

```
Ningún dato puede vivir en dos lugares con valores que puedan divergir. Si un valor es derivado de otro, se calcula en el backend y se lee vía JOIN. La única excepción aceptada es el snapshot de `id_vendedor` en `oportunidades` (sección 8): se mantiene sincronizado automáticamente con `empresas.id_vendedor` mientras la oportunidad esté activa, así que nunca queda divergente en la práctica.
```

- [ ] **Step 2: `reglas_negocio.md` §8.2 a §8.4**

Reemplaza desde `### 8.2 Reasignación de empresa (robo de cliente)` hasta el final de `### 8.4 Snapshot de id_vendedor al crear oportunidad` (líneas ~309-330) por:

```
### 8.2 Reasignación de empresa (robo de cliente)

La reasignación de `empresas.id_vendedor` es una decisión de Aldo (JdV). Solo los roles `admin`, `gerente` y `jdv` pueden modificar `empresas.id_vendedor`.

Al reasignar una empresa, las oportunidades **ya cerradas** (`facturado` o `cerrado`) conservan su `id_vendedor` original — el snapshot no cambia nunca. Todas las oportunidades **activas** (`evaluacion_calidda`, `documentos_legales`) de esa empresa cambian automáticamente al nuevo vendedor, en la misma transacción que la reasignación. No existe un traspaso manual selectivo por oportunidad individual: el único punto de entrada para cambiar el vendedor de una oportunidad activa es reasignar la empresa.

### 8.3 Cascada automática a oportunidades activas

Cuando se reasigna `empresas.id_vendedor`:

- El backend actualiza `oportunidades.id_vendedor` de todas las oportunidades activas de esa empresa cuyo vendedor difiera del nuevo, en la misma transacción (implementado vía evento de aplicación síncrono — ver `VendedorEmpresaReasignadoEvent`).
- El historial completo (log de estados, eventos, tareas) permanece en la misma oportunidad; no se duplica nada.
- El vendedor anterior deja de ver esas oportunidades en su pipeline (el pipeline filtra por `id_vendedor = usuario_actual`).
- El nuevo vendedor las hereda con todo el historial, y recibe una notificación `oportunidad_traspasada` por cada una.
- **Consecuencia aceptada**: si una oportunidad se factura después de la cascada, la comisión corresponde al vendedor vigente en ese momento, no al original. El módulo de comisiones (post-MVP) deberá tener esto en cuenta.

### 8.4 Snapshot de id_vendedor al crear oportunidad

```
oportunidades.id_vendedor = empresas.id_vendedor  (al momento de crear la oportunidad)
```

Este valor se resincroniza automáticamente ante cualquier reasignación posterior de la empresa, mientras la oportunidad esté activa (sección 8.3). Una vez que la oportunidad cierra (`facturado` o `cerrado`), el valor queda congelado para siempre.
```

- [ ] **Step 3: `contrato_api.md` — eliminar el endpoint de traspaso**

Elimina la sección completa (líneas ~802-816):

```
### PATCH /oportunidades/:id/vendedor
> Traspasa la oportunidad a otro vendedor (traspaso activo).

**Roles:** `admin` `gerente` `jdv`

**Body:** `{ "id_vendedor": 2 }`

**Respuesta 200:** `{ "data": { "id_vendedor": 2 } }`

**Notas:**
- Modifica `oportunidades.id_vendedor` directamente. No duplica la oportunidad.
- El vendedor anterior deja de ver la oportunidad en su pipeline.

---
```

En la fila de la tabla de autorización por rol (línea 127), elimina:

```
| Traspasar oportunidad | ✓ | ✓ | ✓ | — | — |
```

En la sección `### PATCH /empresas/:id/vendedor` (línea 506), agrega una nota:

```
### PATCH /empresas/:id/vendedor
> Reasigna el vendedor de una empresa.

**Roles:** `admin` `gerente` `jdv`

**Body:** `{ "id_vendedor": 2 }`

**Respuesta 200:** `{ "data": { "id_vendedor": 2 } }`

**Notas:**
- Cascada automáticamente: todas las oportunidades activas de esta empresa cambian a `id_vendedor` en la misma operación (reglas_negocio.md §8.3). Las oportunidades cerradas (`facturado`, `cerrado`) no se ven afectadas.

---
```

- [ ] **Step 4: `matriz_permisos.md`**

Línea 13, reemplaza:

```
| `jdv` | Aldo | Jefe de ventas. Visibilidad total del equipo. Puede reasignar y traspasar. |
```

por:

```
| `jdv` | Aldo | Jefe de ventas. Visibilidad total del equipo. Puede reasignar el vendedor de una empresa (cascada automáticamente a sus oportunidades activas). |
```

Elimina la fila (línea 92):

```
| Traspasar oportunidad (cambiar `id_vendedor`) | ✓ | ✓ | ✓ | — | — |
```

- [ ] **Step 5: Commit**

```bash
git add docs/reglas_negocio.md docs/contrato_api.md docs/matriz_permisos.md
git commit -m "docs: actualizar reglas_negocio, contrato_api y matriz_permisos para la cascada automática de vendedor"
```

---

### Task 5: Verificación final

**Files:** ninguno nuevo — solo comandos de verificación sobre todo lo anterior.

- [ ] **Step 1: Formato y análisis estático**

Run: `./gradlew ktlintCheck`
Expected: BUILD SUCCESSFUL. Si falla, ejecuta `./gradlew ktlintFormat` y revisa el diff antes de continuar.

Run: `./gradlew detekt`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 2: Suite completa unitaria**

Run: `./gradlew test`
Expected: BUILD SUCCESSFUL — incluye `EmpresaServiceImplTest`, `OportunidadServiceImplTest` y el resto de la suite existente, sin regresiones.

- [ ] **Step 3: Cobertura**

Run: `./gradlew koverVerify`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Confirmar que no quedan referencias sueltas al traspaso manual**

Run: `grep -rn "traspasar\|TraspasarVendedorRequest" src/main src/test`
Expected: sin resultados (o solo coincidencias de `oportunidad_traspasada`, que es el tipo de notificación que se conserva).

- [ ] **Step 5: Commit final (si quedó algo pendiente de formateo)**

```bash
git add -A
git status
```

Si `ktlintFormat` tocó archivos en el Step 1, commitéalos:

```bash
git commit -m "style: aplicar ktlintFormat tras la sincronización de vendedor"
```
