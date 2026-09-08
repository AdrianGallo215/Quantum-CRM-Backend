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
