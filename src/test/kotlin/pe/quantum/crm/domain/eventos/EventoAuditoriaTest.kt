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
