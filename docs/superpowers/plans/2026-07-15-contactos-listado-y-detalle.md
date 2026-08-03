# Contactos: listado paginado + detalle — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `GET /contactos` gana paginación + `oportunidades_count`; nuevo `GET /contactos/:id` devuelve el contacto con sus empresas (segmentos incluidos), sus oportunidades vinculadas y sus tareas como línea de tiempo (`actividades[]`).

**Architecture:** `ContactoRepository` pasa de `@Query` manual a `JpaSpecificationExecutor` (como `empresas`). Toda composición cruzada de módulos (`oportunidades`, `tareas`) ocurre en `ContactoController`, nunca dentro de `ContactoServiceImpl`, porque `oportunidades`→`contactos` y `tareas`→`contactos` ya existen y una dependencia inversa crearía un ciclo de beans de Spring. `empresas[]` (con `segmentos`) sí se arma dentro de `ContactoServiceImpl` porque `EmpresaService` ya es su dependencia sin ciclo.

**Tech Stack:** Kotlin 1.9, Spring Boot 3.2, Spring Data JPA (`Specification`), MockK + JUnit5 + AssertJ para tests unitarios, `@SpringBootTest`+`MockMvc`+`springmockk` para tests de controller.

## Global Constraints

- TDD obligatorio: escribir el test que falla antes del código de cada paso (CLAUDE.md regla 1).
- Inyección por constructor siempre (CLAUDE.md regla 8).
- `@Transactional(readOnly = true)` en toda lectura (CLAUDE.md regla 10).
- Un módulo nunca inyecta el servicio de otro módulo si eso crea un ciclo — la composición cruzada va en el controller (CLAUDE.md regla 12; ver spec §"Decisiones tomadas" punto 2).
- IDOR → 404, nunca 403 (CLAUDE.md regla 14). `detalle()` de un contacto inexistente debe lanzar `NoEncontradoException`.
- Spec de referencia: `docs/superpowers/specs/2026-07-15-contactos-listado-y-detalle-design.md`.

---

## Task 1: `ContactoRepository` con `Specification` + paginación en `buscar()`

**Files:**
- Modify: `src/main/kotlin/pe/quantum/crm/domain/contactos/ContactoRepository.kt`
- Modify: `src/main/kotlin/pe/quantum/crm/domain/contactos/ContactoService.kt`
- Modify: `src/main/kotlin/pe/quantum/crm/domain/contactos/ContactoServiceImpl.kt`
- Modify: `src/main/kotlin/pe/quantum/crm/domain/contactos/dto/ContactoDtos.kt`
- Create: `src/test/kotlin/pe/quantum/crm/domain/contactos/ContactoServiceImplTest.kt`

**Interfaces:**
- Produces: `ContactoService.buscar(q: String?, idEmpresa: Long?, usuario: UsuarioActual, page: Int?, perPage: Int?, sort: String?, dir: String?): Paginado<ContactoListaDto>`
- Produces: `data class ContactoListaDto(id: Long, nombres: String, apellidos: String, email1: String?, email2: String?, tlf1: String?, tlf2: String?, notas: String?, empresas: List<EmpresaDeContactoDto>, oportunidadesCount: Int = 0)`

- [ ] **Step 1: Write the failing test**

```kotlin
package pe.quantum.crm.domain.contactos

import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.data.domain.PageImpl
import org.springframework.data.domain.PageRequest
import pe.quantum.crm.domain.empresas.EmpresaService
import pe.quantum.crm.domain.empresas.dto.EmpresaResumen
import pe.quantum.crm.shared.security.UsuarioActual
import java.time.LocalDateTime

class ContactoServiceImplTest {
    private val contactoRepository = mockk<ContactoRepository>()
    private val empresaContactoRepository = mockk<EmpresaContactoRepository>()
    private val empresaService = mockk<EmpresaService>()
    private val service = ContactoServiceImpl(contactoRepository, empresaContactoRepository, empresaService)

    private fun contacto(id: Long = 1) =
        Contacto(
            id = id,
            nombres = "Hugo",
            apellidos = "Rodríguez",
            tlf1 = "964415122",
            createdAt = LocalDateTime.now(),
            createdBy = 1,
            updatedAt = LocalDateTime.now(),
            updatedBy = 1,
        )

    private val usuario = UsuarioActual(id = 1, rol = "admin")

    @Test
    fun `buscar sin filtros devuelve Paginado con meta correcto`() {
        val entidad = contacto()
        every { contactoRepository.findAll(any(), any<PageRequest>()) } returns
            PageImpl(listOf(entidad), PageRequest.of(0, 20), 1)
        every { empresaContactoRepository.findByIdIdContacto(1) } returns emptyList()

        val resultado = service.buscar(q = null, idEmpresa = null, usuario = usuario, page = null, perPage = null, sort = null, dir = null)

        assertThat(resultado.items).hasSize(1)
        assertThat(resultado.items.first().id).isEqualTo(1)
        assertThat(resultado.items.first().oportunidadesCount).isEqualTo(0)
        assertThat(resultado.meta.page).isEqualTo(1)
        assertThat(resultado.meta.perPage).isEqualTo(20)
        assertThat(resultado.meta.total).isEqualTo(1)
    }

    @Test
    fun `buscar con id_empresa valida visibilidad y filtra por contactos vinculados`() {
        every { empresaService.vinculoVisible(10, usuario) } returns
            pe.quantum.crm.domain.empresas.dto.EmpresaVinculo(id = 10, razonSocial = "ABC", idVendedor = null, estadoCartera = "prospeccion")
        every { empresaContactoRepository.findByIdIdEmpresa(10) } returns
            listOf(EmpresaContacto(id = EmpresaContactoId(idEmpresa = 10, idContacto = 1)))
        every { contactoRepository.findAll(any(), any<PageRequest>()) } returns
            PageImpl(listOf(contacto()), PageRequest.of(0, 20), 1)
        every { empresaContactoRepository.findByIdIdContacto(1) } returns emptyList()

        val resultado = service.buscar(q = null, idEmpresa = 10, usuario = usuario, page = null, perPage = null, sort = null, dir = null)

        assertThat(resultado.items).hasSize(1)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests "pe.quantum.crm.domain.contactos.ContactoServiceImplTest"`
Expected: FAIL — compile error (`ContactoServiceImpl.buscar` no acepta `page`/`perPage`/`sort`/`dir`; `ContactoListaDto` no existe).

- [ ] **Step 3: `ContactoRepository` → `Specification`**

Replace the entire file:
```kotlin
package pe.quantum.crm.domain.contactos

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.JpaSpecificationExecutor

interface ContactoRepository :
    JpaRepository<Contacto, Long>,
    JpaSpecificationExecutor<Contacto>

interface EmpresaContactoRepository : JpaRepository<EmpresaContacto, EmpresaContactoId> {
    fun findByIdIdEmpresa(idEmpresa: Long): List<EmpresaContacto>

    fun findByIdIdContacto(idContacto: Long): List<EmpresaContacto>

    fun findByIdIdContactoIn(idsContacto: Collection<Long>): List<EmpresaContacto>

    fun existsByIdIdContacto(idContacto: Long): Boolean

    fun countByIdIdEmpresa(idEmpresa: Long): Long
}
```

- [ ] **Step 4: Add `ContactoListaDto` to `ContactoDtos.kt`**

Add this data class right after `ContactoDto`:
```kotlin
/** Fila del listado paginado de contactos (contrato_api.md §9). */
data class ContactoListaDto(
    val id: Long,
    val nombres: String,
    val apellidos: String,
    val email1: String?,
    val email2: String?,
    val tlf1: String?,
    val tlf2: String?,
    val notas: String?,
    val empresas: List<EmpresaDeContactoDto>,
    val oportunidadesCount: Int = 0,
)
```

- [ ] **Step 5: Update `ContactoService.buscar` signature**

In `ContactoService.kt`, replace:
```kotlin
    fun buscar(
        q: String?,
        idEmpresa: Long?,
        usuario: UsuarioActual,
    ): List<ContactoDto>
```
with:
```kotlin
    fun buscar(
        q: String?,
        idEmpresa: Long?,
        usuario: UsuarioActual,
        page: Int?,
        perPage: Int?,
        sort: String?,
        dir: String?,
    ): Paginado<ContactoListaDto>
```
Add imports: `pe.quantum.crm.domain.contactos.dto.ContactoListaDto` and `pe.quantum.crm.shared.Paginado`.

- [ ] **Step 6: Implement in `ContactoServiceImpl`**

Replace the `buscar` override:
```kotlin
    @Transactional(readOnly = true)
    override fun buscar(
        q: String?,
        idEmpresa: Long?,
        usuario: UsuarioActual,
        page: Int?,
        perPage: Int?,
        sort: String?,
        dir: String?,
    ): Paginado<ContactoListaDto> {
        val idsPermitidos =
            idEmpresa?.let {
                empresaService.vinculoVisible(it, usuario)
                empresaContactoRepository.findByIdIdEmpresa(it).map { vinculo -> vinculo.id.idContacto }
            }
        val pageRequest = Paginacion.pageRequest(page, perPage, sort, dir, defaultSort = "id")
        val resultado = contactoRepository.findAll(especificacion(q, idsPermitidos), pageRequest)
        val items = resultado.content.map { it.toListaDto() }
        val meta = Paginacion.meta(pageRequest.pageNumber + 1, pageRequest.pageSize, resultado.totalElements)
        return Paginado(items, meta)
    }
```
Add the specification and DTO-mapping private functions (near the other private functions at the bottom of the class):
```kotlin
    private fun especificacion(
        q: String?,
        idsPermitidos: List<Long>?,
    ): Specification<Contacto> =
        Specification { root, _, cb ->
            val predicados = mutableListOf<Predicate>()
            if (idsPermitidos != null) {
                if (idsPermitidos.isEmpty()) {
                    predicados += cb.disjunction()
                } else {
                    predicados += root.get<Long>("id").`in`(idsPermitidos)
                }
            }
            q?.takeIf { it.isNotBlank() }?.let { texto ->
                val patron = "%${texto.lowercase()}%"
                predicados +=
                    cb.or(
                        cb.like(cb.lower(cb.concat(cb.concat(root.get("nombres"), " "), root.get("apellidos"))), patron),
                        cb.like(root.get("tlf1"), "%${texto.trim()}%"),
                        cb.like(root.get("tlf2"), "%${texto.trim()}%"),
                    )
            }
            cb.and(*predicados.toTypedArray())
        }

    private fun Contacto.toListaDto(): ContactoListaDto {
        val vinculos = empresaContactoRepository.findByIdIdContacto(requireNotNull(id))
        val empresas = empresaService.resumenPorIds(vinculos.map { it.id.idEmpresa })
        return ContactoListaDto(
            id = requireNotNull(id),
            nombres = nombres,
            apellidos = apellidos,
            email1 = email1,
            email2 = email2,
            tlf1 = tlf1,
            tlf2 = tlf2,
            notas = notas,
            empresas =
                vinculos.mapNotNull { vinculo ->
                    empresas[vinculo.id.idEmpresa]?.let {
                        EmpresaDeContactoDto(id = it.id, razonSocial = it.razonSocial, cargo = vinculo.cargo)
                    }
                },
        )
    }
```
Add imports: `jakarta.persistence.criteria.Predicate`, `org.springframework.data.jpa.domain.Specification`, `pe.quantum.crm.domain.contactos.dto.ContactoListaDto`, `pe.quantum.crm.shared.Paginacion`, `pe.quantum.crm.shared.Paginado`.

- [ ] **Step 7: Run test to verify it passes**

Run: `./gradlew test --tests "pe.quantum.crm.domain.contactos.ContactoServiceImplTest"`
Expected: PASS (2 tests)

- [ ] **Step 8: Commit**

```bash
git add src/main/kotlin/pe/quantum/crm/domain/contactos/ src/test/kotlin/pe/quantum/crm/domain/contactos/ContactoServiceImplTest.kt
git commit -m "feat(contactos): paginar buscar() con Specification y ContactoListaDto"
```

---

## Task 2: `OportunidadService.countPorContacto`

**Files:**
- Modify: `src/main/kotlin/pe/quantum/crm/domain/oportunidades/OportunidadRepository.kt`
- Modify: `src/main/kotlin/pe/quantum/crm/domain/oportunidades/OportunidadService.kt`
- Modify: `src/main/kotlin/pe/quantum/crm/domain/oportunidades/OportunidadServiceImpl.kt`
- Modify: `src/test/kotlin/pe/quantum/crm/domain/oportunidades/OportunidadServiceImplTest.kt`

**Interfaces:**
- Consumes: `OportunidadContactoRepository` (existente, ya inyectado en `OportunidadServiceImpl` como `contactoOportunidadRepository`)
- Produces: `OportunidadService.countPorContacto(idContacto: Long): Int`

- [ ] **Step 1: Write the failing test**

Add to `OportunidadServiceImplTest.kt` (dentro de la clase, junto a los demás `@Test`):
```kotlin
    @Test
    fun `countPorContacto devuelve la cantidad de vinculos del contacto`() {
        every { contactoOportunidadRepository.countByIdIdContacto(5) } returns 3L

        val resultado = service.countPorContacto(5)

        assertThat(resultado).isEqualTo(3)
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests "pe.quantum.crm.domain.oportunidades.OportunidadServiceImplTest.countPorContacto*"`
Expected: FAIL — `countPorContacto` unresolved reference.

- [ ] **Step 3: Add repository method**

In `OportunidadRepository.kt`, add to `OportunidadContactoRepository`:
```kotlin
interface OportunidadContactoRepository : JpaRepository<OportunidadContacto, OportunidadContactoId> {
    fun findByIdIdOportunidad(idOportunidad: Long): List<OportunidadContacto>

    fun countByIdIdContacto(idContacto: Long): Long
}
```

- [ ] **Step 4: Add to `OportunidadService` interface**

```kotlin
    /** Cantidad de oportunidades distintas vinculadas a un contacto (listado de contactos). */
    fun countPorContacto(idContacto: Long): Int
```

- [ ] **Step 5: Implement in `OportunidadServiceImpl`**

Add near `tieneOportunidadesActivas`:
```kotlin
    @Transactional(readOnly = true)
    override fun countPorContacto(idContacto: Long): Int = contactoOportunidadRepository.countByIdIdContacto(idContacto).toInt()
```

- [ ] **Step 6: Run test to verify it passes**

Run: `./gradlew test --tests "pe.quantum.crm.domain.oportunidades.OportunidadServiceImplTest"`
Expected: PASS (all tests in the class, including the new one)

- [ ] **Step 7: Commit**

```bash
git add src/main/kotlin/pe/quantum/crm/domain/oportunidades/ src/test/kotlin/pe/quantum/crm/domain/oportunidades/OportunidadServiceImplTest.kt
git commit -m "feat(oportunidades): agregar countPorContacto para el listado de contactos"
```

---

## Task 3: `GET /contactos` — page/per_page + `oportunidades_count` en el controller

**Files:**
- Modify: `src/main/kotlin/pe/quantum/crm/domain/contactos/ContactoController.kt`
- Create: `src/test/kotlin/pe/quantum/crm/domain/contactos/ContactoControllerWebMvcTest.kt`

**Interfaces:**
- Consumes: `ContactoService.buscar(...)` (Task 1), `OportunidadService.countPorContacto(Long): Int` (Task 2)

- [ ] **Step 1: Write the failing test**

```kotlin
package pe.quantum.crm.domain.contactos

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
import pe.quantum.crm.domain.contactos.dto.ContactoListaDto
import pe.quantum.crm.domain.oportunidades.OportunidadService
import pe.quantum.crm.shared.PageMeta
import pe.quantum.crm.shared.Paginado
import pe.quantum.crm.support.SinBaseDeDatosMocks

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
class ContactoControllerWebMvcTest {
    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var jwtService: JwtService

    @MockkBean
    lateinit var contactoService: ContactoService

    @MockkBean
    lateinit var oportunidadService: OportunidadService

    @Test
    fun `GET contactos devuelve meta de paginacion y oportunidades_count por item`() {
        val item =
            ContactoListaDto(
                id = 5,
                nombres = "Hugo",
                apellidos = "Rodríguez",
                email1 = null,
                email2 = null,
                tlf1 = "964415122",
                tlf2 = null,
                notas = null,
                empresas = emptyList(),
            )
        every { contactoService.buscar(null, null, any(), 2, 10, null, null) } returns
            Paginado(listOf(item), PageMeta(page = 2, perPage = 10, total = 11, totalPages = 2))
        every { oportunidadService.countPorContacto(5) } returns 3
        val token = jwtService.generateAccessToken(empleadoId = 1, rol = "admin")

        mockMvc.get("/api/v1/contactos?page=2&per_page=10") {
            header(HttpHeaders.AUTHORIZATION, "Bearer $token")
        }.andExpect {
            status { isOk() }
            jsonPath("$.data[0].id") { value(5) }
            jsonPath("$.data[0].oportunidades_count") { value(3) }
            jsonPath("$.meta.page") { value(2) }
            jsonPath("$.meta.per_page") { value(10) }
            jsonPath("$.meta.total") { value(11) }
            jsonPath("$.meta.total_pages") { value(2) }
        }
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests "pe.quantum.crm.domain.contactos.ContactoControllerWebMvcTest"`
Expected: FAIL — `ContactoController.buscar` no acepta `page`/`per_page`, o el mock de `buscar` con la firma nueva no coincide.

- [ ] **Step 3: Update `ContactoController.buscar`**

Replace the `buscar` method and constructor of `ContactoController`:
```kotlin
@RestController
@RequestMapping("/api/v1/contactos")
class ContactoController(
    private val contactoService: ContactoService,
    private val oportunidadService: OportunidadService,
    private val usuarioProvider: UsuarioActualProvider,
) {
    @GetMapping
    fun buscar(
        @RequestParam(required = false) q: String?,
        @RequestParam(name = "id_empresa", required = false) idEmpresa: Long?,
        @RequestParam(required = false) page: Int?,
        @RequestParam(name = "per_page", required = false) perPage: Int?,
    ): ApiResponse<List<ContactoListaDto>> {
        val resultado = contactoService.buscar(q, idEmpresa, usuarioProvider.actual(), page, perPage, null, null)
        val conConteo = resultado.items.map { it.copy(oportunidadesCount = oportunidadService.countPorContacto(it.id)) }
        return ApiResponse.ok(conConteo, resultado.meta)
    }
```
Add imports: `pe.quantum.crm.domain.contactos.dto.ContactoListaDto`, `pe.quantum.crm.domain.oportunidades.OportunidadService`. Remove the now-unused `ContactoDto` import if no longer referenced elsewhere in the file (it is still used by `crear`/`actualizar`, so keep it).

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests "pe.quantum.crm.domain.contactos.ContactoControllerWebMvcTest"`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/pe/quantum/crm/domain/contactos/ContactoController.kt src/test/kotlin/pe/quantum/crm/domain/contactos/ContactoControllerWebMvcTest.kt
git commit -m "feat(contactos): GET /contactos acepta page/per_page y expone oportunidades_count"
```

---

## Task 4: `EmpresaService.segmentosPorIds`

**Files:**
- Modify: `src/main/kotlin/pe/quantum/crm/domain/empresas/EmpresaService.kt`
- Modify: `src/main/kotlin/pe/quantum/crm/domain/empresas/EmpresaServiceImpl.kt`
- Modify: `src/test/kotlin/pe/quantum/crm/domain/empresas/EmpresaServiceImplTest.kt`

**Interfaces:**
- Produces: `EmpresaService.segmentosPorIds(ids: Collection<Long>): Map<Long, List<String>>`

- [ ] **Step 1: Write the failing test**

Add to `EmpresaServiceImplTest.kt`:
```kotlin
    @Test
    fun `segmentosPorIds devuelve los segmentos de cada empresa como String`() {
        val entidad = empresa().apply { segmentos = mutableSetOf(pe.quantum.crm.shared.enums.Segmento.interprovincial) }
        every { empresaRepository.findAllById(listOf(1L)) } returns listOf(entidad)

        val resultado = service.segmentosPorIds(listOf(1L))

        assertThat(resultado[1L]).containsExactly("interprovincial")
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests "pe.quantum.crm.domain.empresas.EmpresaServiceImplTest.segmentosPorIds*"`
Expected: FAIL — `segmentosPorIds` unresolved reference.

- [ ] **Step 3: Add to `EmpresaService` interface**

```kotlin
    /** Segmentos por empresa (para el detalle de contacto: empresas[].segmentos). */
    fun segmentosPorIds(ids: Collection<Long>): Map<Long, List<String>>
```

- [ ] **Step 4: Implement in `EmpresaServiceImpl`**

Add near `resumenPorIds`:
```kotlin
    @Transactional(readOnly = true)
    override fun segmentosPorIds(ids: Collection<Long>): Map<Long, List<String>> =
        empresaRepository.findAllById(ids.toSet()).associate { requireNotNull(it.id) to it.segmentos.map { s -> s.name }.sorted() }
```

- [ ] **Step 5: Run test to verify it passes**

Run: `./gradlew test --tests "pe.quantum.crm.domain.empresas.EmpresaServiceImplTest"`
Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add src/main/kotlin/pe/quantum/crm/domain/empresas/ src/test/kotlin/pe/quantum/crm/domain/empresas/EmpresaServiceImplTest.kt
git commit -m "feat(empresas): agregar segmentosPorIds para el detalle de contacto"
```

---

## Task 5: `ContactoService.detalle` — contacto + empresas con segmentos

**Files:**
- Modify: `src/main/kotlin/pe/quantum/crm/domain/contactos/ContactoService.kt`
- Modify: `src/main/kotlin/pe/quantum/crm/domain/contactos/ContactoServiceImpl.kt`
- Modify: `src/main/kotlin/pe/quantum/crm/domain/contactos/dto/ContactoDtos.kt`
- Modify: `src/test/kotlin/pe/quantum/crm/domain/contactos/ContactoServiceImplTest.kt`

**Interfaces:**
- Consumes: `EmpresaService.segmentosPorIds` (Task 4)
- Produces:
  ```kotlin
  data class EmpresaDeContactoDetalleDto(id: Long, razonSocial: String, cargo: String?, tomaDecision: Boolean?, esPrincipal: Boolean, segmentos: List<String>)
  data class ContactoDetalleDto(id: Long, nombres: String, apellidos: String, email1: String?, email2: String?, tlf1: String?, tlf2: String?, notas: String?, empresas: List<EmpresaDeContactoDetalleDto>, oportunidades: List<OportunidadResumenParaContacto> = emptyList(), actividades: List<ActividadContactoDto> = emptyList())
  ```
  `fun ContactoService.detalle(id: Long): ContactoDetalleDto` — lanza `NoEncontradoException` si no existe.

- [ ] **Step 1: Write the failing test**

Add to `ContactoServiceImplTest.kt`:
```kotlin
    @Test
    fun `detalle arma empresas con cargo, toma_decision, es_principal y segmentos`() {
        val entidad = contacto()
        every { contactoRepository.findById(1) } returns java.util.Optional.of(entidad)
        every { empresaContactoRepository.findByIdIdContacto(1) } returns
            listOf(
                EmpresaContacto(
                    id = EmpresaContactoId(idEmpresa = 3, idContacto = 1),
                    cargo = "Gerente",
                    tomaDecision = true,
                    esPrincipal = true,
                ),
            )
        every { empresaService.resumenPorIds(listOf(3L)) } returns
            mapOf(3L to EmpresaResumen(id = 3, razonSocial = "Transp. Sta. Anita S.A.", distrito = null))
        every { empresaService.segmentosPorIds(listOf(3L)) } returns mapOf(3L to listOf("interprovincial"))

        val resultado = service.detalle(1)

        assertThat(resultado.empresas).hasSize(1)
        val empresa = resultado.empresas.first()
        assertThat(empresa.cargo).isEqualTo("Gerente")
        assertThat(empresa.tomaDecision).isTrue()
        assertThat(empresa.esPrincipal).isTrue()
        assertThat(empresa.segmentos).containsExactly("interprovincial")
    }

    @Test
    fun `detalle de un contacto inexistente lanza NoEncontradoException`() {
        every { contactoRepository.findById(99) } returns java.util.Optional.empty()

        org.assertj.core.api.Assertions.assertThatThrownBy { service.detalle(99) }
            .isInstanceOf(pe.quantum.crm.shared.exception.NoEncontradoException::class.java)
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests "pe.quantum.crm.domain.contactos.ContactoServiceImplTest.detalle*"`
Expected: FAIL — `service.detalle` unresolved reference.

- [ ] **Step 3: Add DTOs to `ContactoDtos.kt`**

Add after `ContactoListaDto` (needs `OportunidadResumenParaContacto` and `ActividadContactoDto` from Tasks 6/7 — declare them there with forward references via imports once those tasks land; for now add local placeholtypes that Tasks 6/7 will relocate is NOT allowed by policy, so instead declare `ContactoDetalleDto` here already importing the real types from their target packages):
```kotlin
/** Empresa vinculada en el detalle de contacto, con datos completos de la relacion. */
data class EmpresaDeContactoDetalleDto(
    val id: Long,
    val razonSocial: String,
    val cargo: String?,
    val tomaDecision: Boolean?,
    val esPrincipal: Boolean,
    val segmentos: List<String>,
)

/** Detalle de contacto (contrato_api.md §9): empresas, oportunidades y actividades (solo tareas por ahora). */
data class ContactoDetalleDto(
    val id: Long,
    val nombres: String,
    val apellidos: String,
    val email1: String?,
    val email2: String?,
    val tlf1: String?,
    val tlf2: String?,
    val notas: String?,
    val empresas: List<EmpresaDeContactoDetalleDto>,
    val oportunidades: List<pe.quantum.crm.domain.oportunidades.dto.OportunidadResumenParaContacto> = emptyList(),
    val actividades: List<pe.quantum.crm.domain.tareas.dto.ActividadContactoDto> = emptyList(),
)
```
This task compiles once Tasks 6 and 7 add those two types — run this task's test only after Task 7 lands, or stub the two types now as empty `data class OportunidadResumenParaContacto(val id: Long)` / `data class ActividadContactoDto(val id: Long)` placeholders in their final files ahead of schedule (simplest: do Task 5's DTO step, but create the two files below with their real final shape immediately, since Tasks 6/7 will only *add service methods*, not redefine these types).

Create `src/main/kotlin/pe/quantum/crm/domain/oportunidades/dto/OportunidadResumenParaContacto.kt` now (used by both this task and Task 6):
```kotlin
package pe.quantum.crm.domain.oportunidades.dto

import pe.quantum.crm.domain.empresas.dto.EmpresaResumen
import java.time.LocalDate

/** Oportunidad vinculada a un contacto, para su vista de detalle (contrato_api.md §9). */
data class OportunidadResumenParaContacto(
    val id: Long,
    val empresa: EmpresaResumen?,
    val modelo: ModeloEnOportunidadDto?,
    val estado: String,
    val montoTotal: String?,
    val fechaCierreEstimado: LocalDate?,
    val rolEnOportunidad: String?,
)
```

Create `src/main/kotlin/pe/quantum/crm/domain/tareas/dto/ActividadContactoDto.kt` now (used by both this task and Task 7):
```kotlin
package pe.quantum.crm.domain.tareas.dto

import java.time.LocalDateTime

/** Actividad en la linea de tiempo del detalle de contacto. Solo tareas por ahora (contrato_api.md §9). */
data class ActividadContactoDto(
    val id: Long,
    val tipo: String = "tarea",
    val titulo: String,
    val descripcion: String?,
    val fecha: LocalDateTime,
    val estado: String,
)
```

- [ ] **Step 4: Add to `ContactoService` interface**

```kotlin
    /** Detalle del contacto: empresas con segmentos. `oportunidades`/`actividades` los completa el controller. */
    fun detalle(id: Long): ContactoDetalleDto
```
Add import `pe.quantum.crm.domain.contactos.dto.ContactoDetalleDto`.

- [ ] **Step 5: Implement in `ContactoServiceImpl`**

Add:
```kotlin
    @Transactional(readOnly = true)
    override fun detalle(id: Long): ContactoDetalleDto {
        val contacto = entidad(id)
        val vinculos = empresaContactoRepository.findByIdIdContacto(id)
        val empresas = empresaService.resumenPorIds(vinculos.map { it.id.idEmpresa })
        val segmentos = empresaService.segmentosPorIds(vinculos.map { it.id.idEmpresa })
        return ContactoDetalleDto(
            id = requireNotNull(contacto.id),
            nombres = contacto.nombres,
            apellidos = contacto.apellidos,
            email1 = contacto.email1,
            email2 = contacto.email2,
            tlf1 = contacto.tlf1,
            tlf2 = contacto.tlf2,
            notas = contacto.notas,
            empresas =
                vinculos.mapNotNull { vinculo ->
                    empresas[vinculo.id.idEmpresa]?.let {
                        EmpresaDeContactoDetalleDto(
                            id = it.id,
                            razonSocial = it.razonSocial,
                            cargo = vinculo.cargo,
                            tomaDecision = vinculo.tomaDecision,
                            esPrincipal = vinculo.esPrincipal,
                            segmentos = segmentos[vinculo.id.idEmpresa].orEmpty(),
                        )
                    }
                },
        )
    }
```
Add imports: `pe.quantum.crm.domain.contactos.dto.ContactoDetalleDto`, `pe.quantum.crm.domain.contactos.dto.EmpresaDeContactoDetalleDto`.

- [ ] **Step 6: Run test to verify it passes**

Run: `./gradlew test --tests "pe.quantum.crm.domain.contactos.ContactoServiceImplTest"`
Expected: PASS (all tests, including the two new ones)

- [ ] **Step 7: Commit**

```bash
git add src/main/kotlin/pe/quantum/crm/domain/contactos/ src/main/kotlin/pe/quantum/crm/domain/oportunidades/dto/OportunidadResumenParaContacto.kt src/main/kotlin/pe/quantum/crm/domain/tareas/dto/ActividadContactoDto.kt src/test/kotlin/pe/quantum/crm/domain/contactos/ContactoServiceImplTest.kt
git commit -m "feat(contactos): ContactoService.detalle con empresas y segmentos"
```

---

## Task 6: `OportunidadService.oportunidadesPorContacto`

**Files:**
- Modify: `src/main/kotlin/pe/quantum/crm/domain/oportunidades/OportunidadRepository.kt`
- Modify: `src/main/kotlin/pe/quantum/crm/domain/oportunidades/OportunidadService.kt`
- Modify: `src/main/kotlin/pe/quantum/crm/domain/oportunidades/OportunidadServiceImpl.kt`
- Modify: `src/test/kotlin/pe/quantum/crm/domain/oportunidades/OportunidadServiceImplTest.kt`

**Interfaces:**
- Consumes: `OportunidadResumenParaContacto` (Task 5, ya creado en `oportunidades/dto/`)
- Produces: `OportunidadService.oportunidadesPorContacto(idContacto: Long): List<OportunidadResumenParaContacto>`

- [ ] **Step 1: Write the failing test**

Add to `OportunidadServiceImplTest.kt`:
```kotlin
    @Test
    fun `oportunidadesPorContacto mapea empresa, modelo, monto y rol`() {
        val vinculo = OportunidadContacto(id = OportunidadContactoId(idOportunidad = 100, idContacto = 5), rolEnOportunidad = "Contacto Principal")
        every { contactoOportunidadRepository.findByIdIdContacto(5) } returns listOf(vinculo)
        every { oportunidadRepository.findAllById(listOf(100L)) } returns listOf(oportunidad(id = 100))
        every { empresaService.resumenPorIds(listOf(10L)) } returns
            mapOf(10L to EmpresaResumen(id = 10, razonSocial = "Transp. Sta. Anita S.A.", distrito = null))
        every { modeloService.resumenPorIds(listOf(1L)) } returns mapOf(1L to busX())

        val resultado = service.oportunidadesPorContacto(5)

        assertThat(resultado).hasSize(1)
        val dto = resultado.first()
        assertThat(dto.id).isEqualTo(100)
        assertThat(dto.empresa?.razonSocial).isEqualTo("Transp. Sta. Anita S.A.")
        assertThat(dto.modelo?.codigo).isEqualTo("BUS-X")
        assertThat(dto.montoTotal).isEqualTo("10")
        assertThat(dto.rolEnOportunidad).isEqualTo("Contacto Principal")
    }

    @Test
    fun `oportunidadesPorContacto devuelve vacio si no hay vinculos`() {
        every { contactoOportunidadRepository.findByIdIdContacto(5) } returns emptyList()

        val resultado = service.oportunidadesPorContacto(5)

        assertThat(resultado).isEmpty()
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests "pe.quantum.crm.domain.oportunidades.OportunidadServiceImplTest.oportunidadesPorContacto*"`
Expected: FAIL — unresolved reference.

- [ ] **Step 3: Add repository method**

In `OportunidadContactoRepository`:
```kotlin
    fun findByIdIdContacto(idContacto: Long): List<OportunidadContacto>
```

- [ ] **Step 4: Add to `OportunidadService` interface**

```kotlin
    /** Oportunidades vinculadas a un contacto, para su vista de detalle (contrato_api.md §9). */
    fun oportunidadesPorContacto(idContacto: Long): List<OportunidadResumenParaContacto>
```
Add import `pe.quantum.crm.domain.oportunidades.dto.OportunidadResumenParaContacto`.

- [ ] **Step 5: Implement in `OportunidadServiceImpl`**

```kotlin
    @Transactional(readOnly = true)
    override fun oportunidadesPorContacto(idContacto: Long): List<OportunidadResumenParaContacto> {
        val vinculos = contactoOportunidadRepository.findByIdIdContacto(idContacto)
        if (vinculos.isEmpty()) {
            return emptyList()
        }
        val idsOportunidad = vinculos.map { it.id.idOportunidad }
        val oportunidades = oportunidadRepository.findAllById(idsOportunidad).associateBy { requireNotNull(it.id) }
        val empresas = empresaService.resumenPorIds(oportunidades.values.map { it.idEmpresa })
        val modelos = modeloService.resumenPorIds(oportunidades.values.mapNotNull { it.idModelo })
        return vinculos.mapNotNull { vinculo ->
            oportunidades[vinculo.id.idOportunidad]?.let { op ->
                OportunidadResumenParaContacto(
                    id = requireNotNull(op.id),
                    empresa = empresas[op.idEmpresa],
                    modelo =
                        op.idModelo?.let { modelos[it] }?.let {
                            pe.quantum.crm.domain.oportunidades.dto.ModeloEnOportunidadDto(
                                id = it.id,
                                codigo = it.codigo,
                                precioBase = it.precioBase?.toPlainString(),
                            )
                        },
                    estado = op.estado.name,
                    montoTotal = op.montoTotal?.toPlainString(),
                    fechaCierreEstimado = op.fechaCierreEstimado,
                    rolEnOportunidad = vinculo.rolEnOportunidad,
                )
            }
        }
    }
```
Add import `pe.quantum.crm.domain.oportunidades.dto.OportunidadResumenParaContacto`.

- [ ] **Step 6: Run test to verify it passes**

Run: `./gradlew test --tests "pe.quantum.crm.domain.oportunidades.OportunidadServiceImplTest"`
Expected: PASS

- [ ] **Step 7: Commit**

```bash
git add src/main/kotlin/pe/quantum/crm/domain/oportunidades/ src/test/kotlin/pe/quantum/crm/domain/oportunidades/OportunidadServiceImplTest.kt
git commit -m "feat(oportunidades): agregar oportunidadesPorContacto para el detalle de contacto"
```

---

## Task 7: `TareaService.actividadesPorContacto`

**Files:**
- Modify: `src/main/kotlin/pe/quantum/crm/domain/tareas/TareaRepository.kt`
- Modify: `src/main/kotlin/pe/quantum/crm/domain/tareas/TareaService.kt`
- Modify: `src/main/kotlin/pe/quantum/crm/domain/tareas/TareaServiceImpl.kt`
- Modify: `src/test/kotlin/pe/quantum/crm/domain/tareas/TareaServiceImplTest.kt`

**Interfaces:**
- Consumes: `ActividadContactoDto` (Task 5, ya creado en `tareas/dto/`)
- Produces: `TareaService.actividadesPorContacto(idContacto: Long, usuario: UsuarioActual): List<ActividadContactoDto>` — respeta `visibilidadRestringida` (vendedor/analista solo ven tareas asignadas a si mismos, igual que `visible()`/`especificacion()` ya hacen en este módulo).

- [ ] **Step 1: Write the failing test**

Add to `TareaServiceImplTest.kt`:
```kotlin
    @Test
    fun `actividadesPorContacto mapea tipo_accion como titulo y ordena por fecha`() {
        val tarea1 =
            Tarea(
                id = 1, idEmpresa = 10, idContacto = 5, idAsignado = 3,
                tipoAccion = TipoAccion.llamada, estadoAccion = EstadoAccion.pendiente,
                descripcion = "Llamar para seguimiento",
                fechaEjecucion = java.time.LocalDateTime.of(2026, 7, 20, 10, 0),
                createdAt = java.time.LocalDateTime.of(2026, 7, 1, 9, 0), createdBy = 9,
                updatedAt = java.time.LocalDateTime.of(2026, 7, 1, 9, 0), updatedBy = 9,
            )
        every { tareaRepository.findByIdContactoOrdenado(5) } returns listOf(tarea1)

        val resultado = service.actividadesPorContacto(5, UsuarioActual(id = 9, rol = "admin"))

        assertThat(resultado).hasSize(1)
        val actividad = resultado.first()
        assertThat(actividad.tipo).isEqualTo("tarea")
        assertThat(actividad.titulo).isEqualTo("llamada")
        assertThat(actividad.descripcion).isEqualTo("Llamar para seguimiento")
        assertThat(actividad.estado).isEqualTo("pendiente")
        assertThat(actividad.fecha).isEqualTo(java.time.LocalDateTime.of(2026, 7, 20, 10, 0))
    }

    @Test
    fun `actividadesPorContacto oculta tareas asignadas a otros cuando la visibilidad es restringida`() {
        val propia =
            Tarea(
                id = 1, idEmpresa = 10, idContacto = 5, idAsignado = 9,
                tipoAccion = TipoAccion.llamada, estadoAccion = EstadoAccion.pendiente,
                createdAt = java.time.LocalDateTime.now(), createdBy = 9,
                updatedAt = java.time.LocalDateTime.now(), updatedBy = 9,
            )
        val ajena =
            Tarea(
                id = 2, idEmpresa = 10, idContacto = 5, idAsignado = 3,
                tipoAccion = TipoAccion.correo, estadoAccion = EstadoAccion.pendiente,
                createdAt = java.time.LocalDateTime.now(), createdBy = 3,
                updatedAt = java.time.LocalDateTime.now(), updatedBy = 3,
            )
        every { tareaRepository.findByIdContactoOrdenado(5) } returns listOf(propia, ajena)

        val resultado = service.actividadesPorContacto(5, UsuarioActual(id = 9, rol = "vendedor"))

        assertThat(resultado).hasSize(1)
        assertThat(resultado.first().id).isEqualTo(1)
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests "pe.quantum.crm.domain.tareas.TareaServiceImplTest.actividadesPorContacto*"`
Expected: FAIL — `findByIdContactoOrdenado`/`actividadesPorContacto` unresolved.

- [ ] **Step 3: Add repository query**

In `TareaRepository.kt`, add:
```kotlin
import org.springframework.data.jpa.repository.Query

interface TareaRepository :
    JpaRepository<Tarea, Long>,
    JpaSpecificationExecutor<Tarea> {
    fun findByEstadoAccionAndIdAsignadoIsNotNullAndFechaEjecucionIsNotNull(estadoAccion: EstadoAccion): List<Tarea>

    @Query(
        """
        SELECT t FROM Tarea t
        WHERE t.idContacto = :idContacto
        ORDER BY COALESCE(t.fechaEjecucion, t.createdAt) DESC
        """,
    )
    fun findByIdContactoOrdenado(idContacto: Long): List<Tarea>
}
```

- [ ] **Step 4: Add to `TareaService` interface**

```kotlin
    /** Tareas de un contacto como linea de tiempo (detalle de contacto, §9). vendedor/analista solo ven las suyas. */
    fun actividadesPorContacto(
        idContacto: Long,
        usuario: UsuarioActual,
    ): List<ActividadContactoDto>
```
Add import `pe.quantum.crm.domain.tareas.dto.ActividadContactoDto`.

- [ ] **Step 5: Implement in `TareaServiceImpl`**

```kotlin
    @Transactional(readOnly = true)
    override fun actividadesPorContacto(
        idContacto: Long,
        usuario: UsuarioActual,
    ): List<ActividadContactoDto> {
        val tareas = tareaRepository.findByIdContactoOrdenado(idContacto)
        val visibles = if (usuario.visibilidadRestringida) tareas.filter { it.idAsignado == usuario.id } else tareas
        return visibles.map {
            ActividadContactoDto(
                id = requireNotNull(it.id),
                titulo = it.tipoAccion.name,
                descripcion = it.descripcion,
                fecha = it.fechaEjecucion ?: it.createdAt,
                estado = it.estadoAccion.name,
            )
        }
    }
```
Add import `pe.quantum.crm.domain.tareas.dto.ActividadContactoDto`.

- [ ] **Step 6: Run test to verify it passes**

Run: `./gradlew test --tests "pe.quantum.crm.domain.tareas.TareaServiceImplTest"`
Expected: PASS

- [ ] **Step 7: Commit**

```bash
git add src/main/kotlin/pe/quantum/crm/domain/tareas/ src/test/kotlin/pe/quantum/crm/domain/tareas/TareaServiceImplTest.kt
git commit -m "feat(tareas): agregar actividadesPorContacto respetando visibilidad restringida"
```

---

## Task 8: `GET /contactos/:id` — endpoint de detalle

**Files:**
- Modify: `src/main/kotlin/pe/quantum/crm/domain/contactos/ContactoController.kt`
- Modify: `src/test/kotlin/pe/quantum/crm/domain/contactos/ContactoControllerWebMvcTest.kt`

**Interfaces:**
- Consumes: `ContactoService.detalle(Long)` (Task 5), `OportunidadService.oportunidadesPorContacto(Long)` (Task 6), `TareaService.actividadesPorContacto(Long, UsuarioActual)` (Task 7)

- [ ] **Step 1: Write the failing test**

Add to `ContactoControllerWebMvcTest.kt`:
```kotlin
    @MockkBean
    lateinit var tareaService: pe.quantum.crm.domain.tareas.TareaService

    @Test
    fun `GET contactos por id inexistente devuelve 404`() {
        every { contactoService.detalle(99) } throws pe.quantum.crm.shared.exception.NoEncontradoException("El contacto no existe")
        val token = jwtService.generateAccessToken(empleadoId = 1, rol = "admin")

        mockMvc.get("/api/v1/contactos/99") {
            header(HttpHeaders.AUTHORIZATION, "Bearer $token")
        }.andExpect {
            status { isNotFound() }
            jsonPath("$.error.code") { value("NO_ENCONTRADO") }
        }
    }

    @Test
    fun `GET contactos por id devuelve empresas, oportunidades y actividades`() {
        val detalle =
            pe.quantum.crm.domain.contactos.dto.ContactoDetalleDto(
                id = 5, nombres = "Hugo", apellidos = "Rodríguez",
                email1 = null, email2 = null, tlf1 = "964415122", tlf2 = null, notas = null,
                empresas = emptyList(),
            )
        every { contactoService.detalle(5) } returns detalle
        every { oportunidadService.oportunidadesPorContacto(5) } returns emptyList()
        every { tareaService.actividadesPorContacto(5, any()) } returns emptyList()
        val token = jwtService.generateAccessToken(empleadoId = 1, rol = "admin")

        mockMvc.get("/api/v1/contactos/5") {
            header(HttpHeaders.AUTHORIZATION, "Bearer $token")
        }.andExpect {
            status { isOk() }
            jsonPath("$.data.id") { value(5) }
            jsonPath("$.data.oportunidades") { isEmpty() }
            jsonPath("$.data.actividades") { isEmpty() }
        }
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests "pe.quantum.crm.domain.contactos.ContactoControllerWebMvcTest"`
Expected: FAIL — no route for `GET /api/v1/contactos/5` (404 genérico sin el shape esperado / falta `tareaService` en el controller).

- [ ] **Step 3: Implement in `ContactoController`**

Add `tareaService` to the constructor and add the new endpoint:
```kotlin
@RestController
@RequestMapping("/api/v1/contactos")
class ContactoController(
    private val contactoService: ContactoService,
    private val oportunidadService: OportunidadService,
    private val tareaService: TareaService,
    private val usuarioProvider: UsuarioActualProvider,
) {
    @GetMapping
    fun buscar(
        @RequestParam(required = false) q: String?,
        @RequestParam(name = "id_empresa", required = false) idEmpresa: Long?,
        @RequestParam(required = false) page: Int?,
        @RequestParam(name = "per_page", required = false) perPage: Int?,
    ): ApiResponse<List<ContactoListaDto>> {
        val resultado = contactoService.buscar(q, idEmpresa, usuarioProvider.actual(), page, perPage, null, null)
        val conConteo = resultado.items.map { it.copy(oportunidadesCount = oportunidadService.countPorContacto(it.id)) }
        return ApiResponse.ok(conConteo, resultado.meta)
    }

    @GetMapping("/{id}")
    fun detalle(
        @PathVariable id: Long,
    ): ApiResponse<ContactoDetalleDto> {
        val usuario = usuarioProvider.actual()
        val contacto = contactoService.detalle(id)
        val completo =
            contacto.copy(
                oportunidades = oportunidadService.oportunidadesPorContacto(id),
                actividades = tareaService.actividadesPorContacto(id, usuario),
            )
        return ApiResponse.ok(completo)
    }
```
Add imports: `pe.quantum.crm.domain.contactos.dto.ContactoDetalleDto`, `pe.quantum.crm.domain.tareas.TareaService`.

**Nota:** `/{id}` no colisiona con ninguna ruta literal existente bajo `/api/v1/contactos` (no hay `/ruc` ni similar en este controller, a diferencia de `EmpresaController`).

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests "pe.quantum.crm.domain.contactos.ContactoControllerWebMvcTest"`
Expected: PASS (all tests in the class)

- [ ] **Step 5: Run the full test suite**

Run: `./gradlew test`
Expected: BUILD SUCCESSFUL (no regressions in `empresas`, `oportunidades`, `tareas`)

- [ ] **Step 6: Commit**

```bash
git add src/main/kotlin/pe/quantum/crm/domain/contactos/ContactoController.kt src/test/kotlin/pe/quantum/crm/domain/contactos/ContactoControllerWebMvcTest.kt
git commit -m "feat(contactos): GET /contactos/:id compone empresas, oportunidades y actividades"
```

---

## Task 9: Actualizar `contrato_api.md §9`

**Files:**
- Modify: `docs/contrato_api.md`

- [ ] **Step 1: Reescribir la sección `GET /contactos` y agregar `GET /contactos/:id`**

En `docs/contrato_api.md`, reemplazar el bloque de `### GET /contactos` (líneas ~521-544) por:

```markdown
### GET /contactos
> Busca contactos. Usado para vincular un contacto existente a una empresa, y para la vista de listado de Contactos.

**Roles:** todos

**Query params:** `q` (nombre o teléfono), `id_empresa` (contactos de una empresa específica), `page`, `per_page`

**Respuesta 200:**
```json
{
  "data": [
    {
      "id": 5,
      "nombres": "Hugo",
      "apellidos": "Rodríguez",
      "email_1": null,
      "tlf_1": "964415122",
      "oportunidades_count": 3,
      "empresas": [
        { "id": 3, "razon_social": "Transp. Negociaciones Sta. Anita S.A.", "cargo": "Gerente" }
      ]
    }
  ],
  "meta": { "page": 1, "per_page": 20, "total": 42, "total_pages": 3 }
}
```

---

### GET /contactos/:id
> Detalle completo del contacto: empresas vinculadas, oportunidades vinculadas y su línea de tiempo de actividades.

**Roles:** todos

**Respuesta 200:**
```json
{
  "data": {
    "id": 5, "nombres": "Hugo", "apellidos": "Rodríguez",
    "email_1": "h@x.com", "email_2": null, "tlf_1": "964415122", "tlf_2": null, "notas": null,
    "empresas": [
      { "id": 3, "razon_social": "Transp. Sta. Anita S.A.", "cargo": "Gerente",
        "toma_decision": true, "es_principal": true, "segmentos": ["interprovincial"] }
    ],
    "oportunidades": [
      { "id": 12, "empresa": { "id": 3, "razon_social": "Transp. Sta. Anita S.A." },
        "modelo": { "id": 2, "codigo": "KinWin K9" },
        "estado": "evaluacion_calidda", "monto_total": "450000.00",
        "fecha_cierre_estimado": "2024-12-15", "rol_en_oportunidad": "Contacto Principal" }
    ],
    "actividades": [
      { "id": 88, "tipo": "tarea", "titulo": "llamada",
        "descripcion": "Acordar términos...", "fecha": "2024-10-24T10:30:00", "estado": "pendiente" }
    ]
  }
}
```

**Notas:**
- `actividades[]` incluye solo tareas por ahora. `eventos` no tiene columna `id_contacto` en el schema actual y no existe una entidad de notas — se agregarán cuando el schema lo soporte (fuera de alcance de este cambio).
- `oportunidades[].modelo.codigo` usa el mismo campo que el resto del contrato (`contrato_api.md §10`), no `nombre`.
- `actividades[].titulo` es el valor de `tipo_accion` (`llamada`, `correo`, `reunion`, `whatsapp`, `otro`) — `Tarea` no tiene un campo de título libre.
- `actividades[]` respeta la visibilidad de tareas: vendedor/analista solo ven las tareas asignadas a sí mismos.
- Errores: `404 NO_ENCONTRADO` si el contacto no existe.

---
```

- [ ] **Step 2: Commit**

```bash
git add docs/contrato_api.md
git commit -m "docs(contrato_api): documentar paginacion, oportunidades_count y GET /contactos/:id"
```

---

## Self-Review Notes

- **Spec coverage:** Cambio 1 (paginación + `oportunidades_count`) → Tasks 1-3. Cambio 2 (detalle con empresas/oportunidades/actividades) → Tasks 4-8. Documentación → Task 9. Las 5 decisiones tomadas en el spec están reflejadas: DTO separado (Task 1), composición en el controller (Tasks 3 y 8), `Specification` (Task 1), mapeos honestos `codigo`/`tipo_accion` (Tasks 6-7), sin restricción de visibilidad para contactos/empresas pero sí para tareas individuales (Task 7).
- **Placeholder scan:** sin TBD/TODO. Task 5 explica explícitamente el orden de creación de los dos DTOs compartidos (`OportunidadResumenParaContacto`, `ActividadContactoDto`) para que no queden como referencias colgantes al ejecutar las tareas en orden.
- **Type consistency:** `ContactoListaDto`, `ContactoDetalleDto`, `EmpresaDeContactoDetalleDto`, `OportunidadResumenParaContacto`, `ActividadContactoDto` usan los mismos nombres de campo en todas las tareas que los consumen.
