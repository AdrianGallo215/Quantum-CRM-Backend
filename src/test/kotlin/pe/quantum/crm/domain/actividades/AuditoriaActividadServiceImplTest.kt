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
import pe.quantum.crm.shared.comoInstanteUtc

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
