package pe.quantum.crm.domain.tareas

import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import pe.quantum.crm.domain.actividades.AuditoriaActividadService
import pe.quantum.crm.domain.actividades.TipoActividad
import pe.quantum.crm.domain.actividades.dto.CambioCampo
import pe.quantum.crm.domain.contactos.ContactoService
import pe.quantum.crm.domain.empleados.EmpleadoService
import pe.quantum.crm.domain.empresas.EmpresaService
import pe.quantum.crm.domain.notificaciones.NotificacionService
import pe.quantum.crm.domain.oportunidades.OportunidadService
import pe.quantum.crm.domain.tareas.dto.ActualizarTareaRequest
import pe.quantum.crm.shared.enums.EstadoAccion
import pe.quantum.crm.shared.enums.TipoAccion
import pe.quantum.crm.shared.security.UsuarioActual
import java.time.Instant
import java.time.LocalDateTime
import java.util.Optional

/**
 * Editar una tarea deja rastro de QUE cambio, no solo de que algo cambio.
 * Se auditan TODAS las ediciones, tambien las del propio dueno: un historial
 * con huecos no se puede leer.
 */
class TareaAuditoriaTest {
    private val tareaRepository = mockk<TareaRepository>()
    private val tareaResponsableRepository = mockk<TareaResponsableRepository>(relaxed = true)
    private val empresaService = mockk<EmpresaService>(relaxed = true)
    private val oportunidadService = mockk<OportunidadService>(relaxed = true)
    private val contactoService = mockk<ContactoService>(relaxed = true)
    private val empleadoService = mockk<EmpleadoService>(relaxed = true)
    private val notificacionService = mockk<NotificacionService>(relaxed = true)
    private val auditoriaService = mockk<AuditoriaActividadService>(relaxed = true)
    private val service =
        TareaServiceImpl(
            tareaRepository,
            tareaResponsableRepository,
            empresaService,
            oportunidadService,
            contactoService,
            empleadoService,
            notificacionService,
            auditoriaService,
        )

    private val supervisor = UsuarioActual(id = 1, rol = "gerencia")

    private fun tarea() =
        Tarea(
            id = 5,
            idEmpresa = 3,
            idOportunidad = null,
            idAsignado = 7,
            tipoAccion = TipoAccion.llamada,
            estadoAccion = EstadoAccion.pendiente,
            descripcion = "descripcion vieja",
            fechaEjecucion = LocalDateTime.of(2026, 9, 1, 10, 0),
            createdAt = LocalDateTime.of(2026, 8, 1, 10, 0),
            createdBy = 7,
            updatedAt = LocalDateTime.of(2026, 8, 1, 10, 0),
            updatedBy = 7,
        )

    private fun prepararEdicion() {
        every { tareaRepository.findById(5) } returns Optional.of(tarea())
        every { tareaRepository.save(any()) } answers { firstArg() }
        every { empresaService.resumenPorIds(any()) } returns emptyMap()
        every { contactoService.resumenPorIds(any()) } returns emptyMap()
        every { empleadoService.resumenPorIds(any()) } returns emptyMap()
        every { tareaResponsableRepository.findByIdIdTareaIn(any()) } returns emptyList()
    }

    @Test
    fun `cambiar la descripcion registra el valor anterior y el nuevo`() {
        prepararEdicion()
        val cambios = slot<List<CambioCampo>>()
        every { auditoriaService.registrar(TipoActividad.tarea, 5, capture(cambios), 1) } returns Unit

        service.actualizar(5, ActualizarTareaRequest(descripcion = "descripcion nueva"), supervisor)

        val descripcion = cambios.captured.single { it.campo == "descripcion" }
        assertThat(descripcion.valorAnterior).isEqualTo("descripcion vieja")
        assertThat(descripcion.valorNuevo).isEqualTo("descripcion nueva")
    }

    @Test
    fun `reprogramar registra el cambio de fecha_ejecucion`() {
        prepararEdicion()
        val cambios = slot<List<CambioCampo>>()
        every { auditoriaService.registrar(TipoActividad.tarea, 5, capture(cambios), 1) } returns Unit

        service.actualizar(
            5,
            ActualizarTareaRequest(fechaEjecucion = Instant.parse("2026-09-10T15:00:00Z")),
            supervisor,
        )

        assertThat(cambios.captured.map { it.campo }).contains("fecha_ejecucion")
    }

    @Test
    fun `una edicion que no cambia nada no registra auditoria`() {
        prepararEdicion()
        val cambios = slot<List<CambioCampo>>()
        every { auditoriaService.registrar(TipoActividad.tarea, 5, capture(cambios), 1) } returns Unit

        // Se manda exactamente el mismo valor que ya tenia.
        service.actualizar(5, ActualizarTareaRequest(descripcion = "descripcion vieja"), supervisor)

        assertThat(cambios.captured).isEmpty()
    }

    @Test
    fun `la auditoria se registra con el id del actor, no del dueno de la tarea`() {
        prepararEdicion()
        service.actualizar(5, ActualizarTareaRequest(descripcion = "otra"), supervisor)

        // La tarea es del empleado 7; quien edita es el 1. Se guarda el 1.
        verify { auditoriaService.registrar(TipoActividad.tarea, 5, any(), 1) }
    }
}
