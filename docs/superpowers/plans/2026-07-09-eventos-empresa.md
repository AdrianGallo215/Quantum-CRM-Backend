# Eventos a nivel de Empresa — cierre de gaps — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Cerrar los 2 gaps de comportamiento pedidos en `solicitud-backend-eventos-empresa.md` sobre los endpoints `GET/POST /empresas/:id/eventos` (ya implementados), con tests que hoy no existen, y documentar el contrato.

**Architecture:** Sin cambios de arquitectura. Todo el trabajo es en `pe.quantum.crm.domain.eventos` (DTO + `EventoServiceImpl`), con un test unitario nuevo que mockea las 4 dependencias de `EventoServiceImpl` (sin Spring, sin base de datos).

**Tech Stack:** Kotlin, JUnit 5, MockK (ya en el classpath de test vía `springmockk`), AssertJ.

## Global Constraints

- `estado_cartera` y `dispara_cambio_estado` no se tocan — fuera de alcance (spec, sección "Fuera de alcance").
- No se agrega el flag `aplica_a_empresa` en catálogo (spec sección 4).
- Todo evento con `etapa_asociada = NULL` en el seed V18 corresponde a un hito de prospección — la validación de la Tarea 2 no debe romper esos 3 casos.
- Inyección por constructor, sin `@Autowired` en campos (CLAUDE.md regla 8) — ya respetado por `EventoServiceImpl`, no cambia.

---

### Task 1: Exponer `es_hito_prospeccion` en `EventoDto`

**Files:**
- Modify: `src/main/kotlin/pe/quantum/crm/domain/eventos/dto/EventoDtos.kt:8-24`
- Modify: `src/main/kotlin/pe/quantum/crm/domain/eventos/EventoServiceImpl.kt:255-274`
- Create: `src/test/kotlin/pe/quantum/crm/domain/eventos/EventoServiceImplTest.kt`

**Interfaces:**
- Consumes: `EventoRepository` (`findByIdEmpresaAndIdOportunidadIsNullOrderByIdAsc(idEmpresa: Long): List<Evento>`, JPA `save`/`findById`), `CatalogoEventoService.porId(id: Long): CatalogoEventoDto`, `CatalogoEventoService.todosPorId(): Map<Long, CatalogoEventoDto>`, `EmpresaService.vinculoVisible(id: Long, usuario: UsuarioActual): EmpresaVinculo`, `CatalogoEventoDto(id, nombre, etapaAsociada: String?, disparaCambioEstado, estadoDestino: String?, esRecomendado, esHitoProspeccion)`.
- Produces: `EventoDto.esHitoProspeccion: Boolean` — usado por el frontend y por los tests de las Tareas 2-4. Helpers de test reutilizados por tareas siguientes en el mismo archivo: `usuario`, `empresaVinculo(id)`, `catalogo(id, etapaAsociada, esHitoProspeccion)`, `conId(id)`.

- [ ] **Step 1: Escribir el test que falla**

Crear `src/test/kotlin/pe/quantum/crm/domain/eventos/EventoServiceImplTest.kt`:

```kotlin
package pe.quantum.crm.domain.eventos

import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import pe.quantum.crm.domain.catalogoeventos.CatalogoEventoService
import pe.quantum.crm.domain.catalogoeventos.dto.CatalogoEventoDto
import pe.quantum.crm.domain.empresas.EmpresaService
import pe.quantum.crm.domain.empresas.dto.EmpresaVinculo
import pe.quantum.crm.domain.eventos.dto.CrearEventoRequest
import pe.quantum.crm.domain.eventos.dto.MarcarOcurridoRequest
import pe.quantum.crm.domain.oportunidades.OportunidadService
import pe.quantum.crm.shared.enums.EstadoCartera
import pe.quantum.crm.shared.enums.EstadoOportunidad
import pe.quantum.crm.shared.exception.NoEncontradoException
import pe.quantum.crm.shared.exception.ValidacionException
import pe.quantum.crm.shared.security.UsuarioActual
import java.util.Optional

/**
 * Unit tests de EventoServiceImpl sin Spring ni base de datos: las 4
 * dependencias se mockean directamente con MockK.
 */
class EventoServiceImplTest {
    private val eventoRepository = mockk<EventoRepository>()
    private val catalogoEventoService = mockk<CatalogoEventoService>()
    private val oportunidadService = mockk<OportunidadService>()
    private val empresaService = mockk<EmpresaService>()
    private val service = EventoServiceImpl(eventoRepository, catalogoEventoService, oportunidadService, empresaService)

    private val usuario = UsuarioActual(id = 1, rol = "vendedor")

    private fun empresaVinculo(id: Long = 10) =
        EmpresaVinculo(id = id, razonSocial = "Kincar S.A.C.", idVendedor = 1, estadoCartera = EstadoCartera.prospeccion.name)

    private fun catalogo(
        id: Long = 5,
        etapaAsociada: EstadoOportunidad? = null,
        esHitoProspeccion: Boolean = true,
    ) = CatalogoEventoDto(
        id = id,
        nombre = "Reporte Tributario recibido",
        etapaAsociada = etapaAsociada?.name,
        disparaCambioEstado = false,
        estadoDestino = null,
        esRecomendado = false,
        esHitoProspeccion = esHitoProspeccion,
    )

    /** Devuelve una copia del evento con `id` asignado, simulando lo que hace JPA al guardar. */
    private fun Evento.conId(nuevoId: Long) =
        Evento(
            id = nuevoId,
            idOportunidad = idOportunidad,
            idEmpresa = idEmpresa,
            idCatalogoEvento = idCatalogoEvento,
            esPersonalizado = esPersonalizado,
            nombrePersonalizado = nombrePersonalizado,
            descripcion = descripcion,
            estado = estado,
            fechaEstimada = fechaEstimada,
            fechaSeguimiento = fechaSeguimiento,
            fechaOcurrencia = fechaOcurrencia,
            disparaCambioEstado = disparaCambioEstado,
            estadoDestino = estadoDestino,
            registradoPor = registradoPor,
            createdAt = createdAt,
            createdBy = createdBy,
            updatedAt = updatedAt,
            updatedBy = updatedBy,
        )

    @Test
    fun `crear hito de prospeccion sobre una empresa expone es_hito_prospeccion en true`() {
        val slot = slot<Evento>()
        every { empresaService.vinculoVisible(10, usuario) } returns empresaVinculo()
        every { catalogoEventoService.porId(5) } returns catalogo()
        every { catalogoEventoService.todosPorId() } returns mapOf(5L to catalogo())
        every { eventoRepository.save(capture(slot)) } answers { slot.captured.conId(1) }

        val dto = service.crearEnEmpresa(10, CrearEventoRequest(idCatalogoEvento = 5), usuario)

        assertThat(dto.idOportunidad).isNull()
        assertThat(dto.idEmpresa).isEqualTo(10)
        assertThat(dto.esHitoProspeccion).isTrue()
    }

    @Test
    fun `evento de catalogo del pipeline expone es_hito_prospeccion en false`() {
        every { empresaService.vinculoVisible(10, usuario) } returns empresaVinculo()
        every { catalogoEventoService.todosPorId() } returns
            mapOf(5L to catalogo(etapaAsociada = null, esHitoProspeccion = false))
        every { eventoRepository.findByIdEmpresaAndIdOportunidadIsNullOrderByIdAsc(10) } returns
            listOf(Evento(id = 1, idEmpresa = 10, idCatalogoEvento = 5, createdBy = 1, updatedBy = 1))

        val resultado = service.listarPorEmpresa(10, usuario)

        assertThat(resultado.pendientes).hasSize(1)
        assertThat(resultado.pendientes.first().esHitoProspeccion).isFalse()
    }
}
```

- [ ] **Step 2: Confirmar que falla**

Run: `./gradlew test --tests "pe.quantum.crm.domain.eventos.EventoServiceImplTest"`
Expected: FAIL en compilación — `EventoDto` no tiene el parámetro/propiedad `esHitoProspeccion` (unresolved reference).

- [ ] **Step 3: Implementar el cambio mínimo**

En `EventoDtos.kt`, agregar el campo al final de `EventoDto` (después de `etapaAsociada`):

```kotlin
data class EventoDto(
    val id: Long,
    val idOportunidad: Long?,
    val idEmpresa: Long?,
    val idCatalogoEvento: Long?,
    val nombre: String,
    val esPersonalizado: Boolean,
    val descripcion: String?,
    val estado: String,
    val fechaEstimada: LocalDate?,
    val fechaSeguimiento: LocalDate?,
    val fechaOcurrencia: LocalDateTime?,
    val disparaCambioEstado: Boolean,
    val estadoDestino: String?,
    val esRecomendado: Boolean,
    val etapaAsociada: String?,
    val esHitoProspeccion: Boolean,
)
```

En `EventoServiceImpl.kt`, en `Evento.toDto()`, agregar la propiedad al construir el DTO (después de `etapaAsociada`):

```kotlin
    private fun Evento.toDto(catalogo: Map<Long, CatalogoEventoDto>? = null): EventoDto {
        val entrada = idCatalogoEvento?.let { (catalogo ?: catalogoEventoService.todosPorId())[it] }
        return EventoDto(
            id = requireNotNull(id),
            idOportunidad = idOportunidad,
            idEmpresa = idEmpresa,
            idCatalogoEvento = idCatalogoEvento,
            nombre = nombrePersonalizado ?: entrada?.nombre ?: "Evento",
            esPersonalizado = esPersonalizado,
            descripcion = descripcion,
            estado = estado.name,
            fechaEstimada = fechaEstimada,
            fechaSeguimiento = fechaSeguimiento,
            fechaOcurrencia = fechaOcurrencia,
            disparaCambioEstado = disparaCambioEstado,
            estadoDestino = estadoDestino?.name,
            esRecomendado = entrada?.esRecomendado ?: false,
            etapaAsociada = entrada?.etapaAsociada,
            esHitoProspeccion = entrada?.esHitoProspeccion ?: false,
        )
    }
```

- [ ] **Step 4: Confirmar que pasa**

Run: `./gradlew test --tests "pe.quantum.crm.domain.eventos.EventoServiceImplTest"`
Expected: PASS (2 tests).

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/pe/quantum/crm/domain/eventos/dto/EventoDtos.kt src/main/kotlin/pe/quantum/crm/domain/eventos/EventoServiceImpl.kt src/test/kotlin/pe/quantum/crm/domain/eventos/EventoServiceImplTest.kt
git commit -m "feat(eventos): exponer es_hito_prospeccion en EventoDto"
```

---

### Task 2: Rechazar eventos de pipeline sobre una empresa suelta

**Files:**
- Modify: `src/main/kotlin/pe/quantum/crm/domain/eventos/EventoServiceImpl.kt:192-216` (rama catálogo de `crear()`)
- Modify: `src/test/kotlin/pe/quantum/crm/domain/eventos/EventoServiceImplTest.kt` (agregar test)

**Interfaces:**
- Consumes: helpers de Task 1 (`usuario`, `empresaVinculo`, `catalogo`).
- Produces: `EventoServiceImpl.crear()` ahora valida `idEmpresa != null && catalogo.etapaAsociada != null` → `ValidacionException(field = "id_catalogo_evento")`.

- [ ] **Step 1: Escribir el test que falla**

Agregar al final de la clase `EventoServiceImplTest` (antes del cierre `}`):

```kotlin
    @Test
    fun `crear evento del catalogo con etapa_asociada sobre una empresa lanza VALIDACION`() {
        every { empresaService.vinculoVisible(10, usuario) } returns empresaVinculo()
        every { catalogoEventoService.porId(5) } returns
            catalogo(etapaAsociada = EstadoOportunidad.evaluacion_calidda, esHitoProspeccion = false)

        val ex =
            assertThrows<ValidacionException> {
                service.crearEnEmpresa(10, CrearEventoRequest(idCatalogoEvento = 5), usuario)
            }

        assertThat(ex.field).isEqualTo("id_catalogo_evento")
    }
```

- [ ] **Step 2: Confirmar que falla**

Run: `./gradlew test --tests "pe.quantum.crm.domain.eventos.EventoServiceImplTest"`
Expected: FAIL — no se lanza `ValidacionException` (el mock de `eventoRepository.save` no está configurado para este caso y el evento se crea sin validar; el test falla porque `assertThrows` no captura ninguna excepción, o falla con MockKException por llamada no stubbeada a `save`/`todosPorId`).

- [ ] **Step 3: Implementar el cambio mínimo**

En `EventoServiceImpl.kt`, dentro de `crear()`, rama `else` (catálogo), justo después de `val catalogo = catalogoEventoService.porId(idCatalogo)`:

```kotlin
                val catalogo = catalogoEventoService.porId(idCatalogo)
                if (idEmpresa != null && catalogo.etapaAsociada != null) {
                    throw ValidacionException(
                        "Este evento pertenece a una etapa del pipeline y debe registrarse en una oportunidad, no en una empresa",
                        field = "id_catalogo_evento",
                    )
                }
                Evento(
```

(el resto del bloque `Evento(...)` que sigue queda igual.)

- [ ] **Step 4: Confirmar que pasa**

Run: `./gradlew test --tests "pe.quantum.crm.domain.eventos.EventoServiceImplTest"`
Expected: PASS (3 tests).

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/pe/quantum/crm/domain/eventos/EventoServiceImpl.kt src/test/kotlin/pe/quantum/crm/domain/eventos/EventoServiceImplTest.kt
git commit -m "feat(eventos): rechazar eventos de pipeline sobre una empresa sin oportunidad"
```

---

### Task 3: Confirmar IDOR (404) en los endpoints de empresa

**Files:**
- Modify: `src/test/kotlin/pe/quantum/crm/domain/eventos/EventoServiceImplTest.kt` (agregar test, sin cambios de producción)

**Interfaces:**
- Consumes: helpers de Task 1, `NoEncontradoException` (ya importado).

- [ ] **Step 1: Escribir el test**

Agregar a `EventoServiceImplTest`:

```kotlin
    @Test
    fun `listar eventos de una empresa ajena o inexistente devuelve 404`() {
        every { empresaService.vinculoVisible(99, usuario) } throws NoEncontradoException("La empresa no existe")

        assertThrows<NoEncontradoException> { service.listarPorEmpresa(99, usuario) }
    }
```

- [ ] **Step 2: Ejecutar (ya debería pasar, sin cambio de producción)**

Run: `./gradlew test --tests "pe.quantum.crm.domain.eventos.EventoServiceImplTest"`
Expected: PASS (4 tests) — `listarPorEmpresa` ya delega el filtro IDOR a `empresaService.vinculoVisible` (`EventoServiceImpl.kt:67`), no requiere cambios.

- [ ] **Step 3: Commit**

```bash
git add src/test/kotlin/pe/quantum/crm/domain/eventos/EventoServiceImplTest.kt
git commit -m "test(eventos): confirmar 404 por IDOR al listar eventos de empresa ajena"
```

---

### Task 4: Confirmar que un hito de empresa no genera sugerencia de cambio de estado

**Files:**
- Modify: `src/test/kotlin/pe/quantum/crm/domain/eventos/EventoServiceImplTest.kt` (agregar test, sin cambios de producción)

**Interfaces:**
- Consumes: helpers de Task 1, `MarcarOcurridoRequest()` (ya importado).

- [ ] **Step 1: Escribir el test**

Agregar a `EventoServiceImplTest`:

```kotlin
    @Test
    fun `marcar ocurrido un hito de empresa no genera sugerencia de cambio de estado`() {
        val evento =
            Evento(
                id = 7,
                idEmpresa = 10,
                idCatalogoEvento = 5,
                disparaCambioEstado = false,
                estadoDestino = null,
                createdBy = 1,
                updatedBy = 1,
            )
        every { eventoRepository.findById(7) } returns Optional.of(evento)
        every { empresaService.vinculoVisible(10, usuario) } returns empresaVinculo()
        every { eventoRepository.save(evento) } returns evento

        val resultado = service.marcarOcurrido(7, MarcarOcurridoRequest(), usuario)

        assertThat(resultado.sugerencia).isNull()
    }
```

- [ ] **Step 2: Ejecutar (ya debería pasar, sin cambio de producción)**

Run: `./gradlew test --tests "pe.quantum.crm.domain.eventos.EventoServiceImplTest"`
Expected: PASS (5 tests) — `marcarOcurrido` ya devuelve `sugerencia = null` cuando `disparaCambioEstado = false` (`EventoServiceImpl.kt:95-104`).

- [ ] **Step 3: Ejecutar toda la suite para descartar regresiones**

Run: `./gradlew test`
Expected: BUILD SUCCESSFUL (los 2 fallos preexistentes de `LocalEnvironmentConfigTest` sobre `.env.example` son ajenos a este cambio; si aparecen, no bloquean esta tarea).

- [ ] **Step 4: Commit**

```bash
git add src/test/kotlin/pe/quantum/crm/domain/eventos/EventoServiceImplTest.kt
git commit -m "test(eventos): confirmar que un hito de empresa no dispara sugerencia de estado"
```

---

### Task 5: Documentar los endpoints en `contrato_api.md`

**Files:**
- Modify: `docs/contrato_api.md` (nueva sección después de `PUT /empresas/:id`, y el shape de evento en §11)

**Interfaces:**
- Ninguna (solo documentación).

- [ ] **Step 1: Agregar el campo `es_hito_prospeccion` al shape de evento en §11**

Localizar el bloque JSON de ejemplo de evento en `contrato_api.md` §11 (`GET /oportunidades/:id/eventos`) y agregar la línea `"es_hito_prospeccion": false,` junto a `"es_recomendado"` / `"etapa_asociada"`, en los 3 arrays de ejemplo (`pendientes`, `ocurridos`, `descartados`) donde aplique.

- [ ] **Step 2: Agregar la sección `GET/POST /empresas/:id/eventos`**

Insertar después de la sección `PUT /empresas/:id` (antes de `PATCH /empresas/:id/estado-cartera`):

```markdown
### GET /empresas/:id/eventos
> Lista los eventos de la empresa que no están vinculados a ninguna oportunidad (`id_oportunidad IS NULL`). Hitos de prospección (reglas_negocio.md §10.3).

**Roles:** todos (mismo filtro por rol que el resto de `/empresas`)

**Respuesta 200:** mismo shape que `GET /oportunidades/:id/eventos` (§11), incluyendo `es_hito_prospeccion`.

---

### POST /empresas/:id/eventos
> Registra un nuevo evento en la empresa, sin oportunidad asociada.

**Roles:** todos (solo su empresa si es vendedor/analista)

**Body:** idéntico al de `POST /oportunidades/:id/eventos` (catálogo o personalizado, §11).

**Respuesta 201:** el evento creado, con `id_empresa` seteado e `id_oportunidad = null`.

**Notas:**
- Si `id_catalogo_evento` referencia un evento con `etapa_asociada` no nula → `400 VALIDACION` (ese evento pertenece a una oportunidad, no a una empresa suelta).
- `PATCH /eventos/:id/ocurrido`, `PATCH /eventos/:id/descartado` y `PUT /eventos/:id` (§11) operan igual sobre estos eventos. `sugerencia` en `PATCH /eventos/:id/ocurrido` siempre viene `null` para eventos de empresa (no disparan cambio de estado).

---
```

- [ ] **Step 3: Commit**

```bash
git add docs/contrato_api.md
git commit -m "docs(contrato): documentar GET/POST /empresas/:id/eventos y es_hito_prospeccion"
```

---

## Self-Review Notes

- **Spec coverage:** Cambio 1 → Task 1. Cambio 2 → Task 2. Sección 3 (confirmación) → Tasks 3-4. Sección 4 (flag opcional) → deliberadamente no implementada (se responde en el chat, no requiere tarea). Contrato → Task 5.
- **Placeholders:** ninguno — todo el código de test y producción está completo.
- **Consistencia de tipos:** `EventoDto.esHitoProspeccion: Boolean`, `CatalogoEventoDto.esHitoProspeccion: Boolean`, `EmpresaVinculo(id, razonSocial, idVendedor, estadoCartera)` — verificados contra el código fuente actual antes de escribir el plan.
