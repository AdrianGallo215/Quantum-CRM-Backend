package pe.quantum.crm.domain.notificaciones

import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import pe.quantum.crm.domain.empleados.EmpleadoService
import pe.quantum.crm.domain.empleados.dto.EmpleadoResumen
import pe.quantum.crm.shared.exception.NoEncontradoException
import pe.quantum.crm.shared.security.UsuarioActual
import java.time.LocalDateTime
import java.util.Optional

class NotificacionServiceImplTest {
    private val notificacionRepository = mockk<NotificacionRepository>()
    private val empleadoService = mockk<EmpleadoService>()
    private val service = NotificacionServiceImpl(notificacionRepository, empleadoService)

    private val usuario = UsuarioActual(id = 1, rol = "vendedor")

    @Test
    fun `notificar excluye al actor del set de destinatarios`() {
        val slots = mutableListOf<Notificacion>()
        every { notificacionRepository.save(capture(slots)) } answers { firstArg() }

        service.notificar(
            destinatarios = setOf(1, 2, 3),
            idActor = 1,
            tipo = TipoNotificacion.tarea_creada,
            mensaje = "msg",
            entidadTipo = EntidadNotificacion.empresa,
            entidadId = 10,
        )

        assertThat(slots.map { it.idEmpleadoDestinatario }).containsExactlyInAnyOrder(2, 3)
        assertThat(slots).allMatch { it.idActor == 1L }
    }

    @Test
    fun `notificar con id_actor nulo no excluye a nadie`() {
        val slots = mutableListOf<Notificacion>()
        every { notificacionRepository.save(capture(slots)) } answers { firstArg() }

        service.notificar(
            destinatarios = setOf(5, 6),
            idActor = null,
            tipo = TipoNotificacion.tarea_recordatorio,
            mensaje = "msg",
            entidadTipo = EntidadNotificacion.empresa,
            entidadId = 10,
        )

        assertThat(slots.map { it.idEmpleadoDestinatario }).containsExactlyInAnyOrder(5, 6)
        assertThat(slots).allMatch { it.idActor == null }
    }

    @Test
    fun `notificar con set vacio tras excluir al actor no guarda nada`() {
        service.notificar(
            destinatarios = setOf(1),
            idActor = 1,
            tipo = TipoNotificacion.tarea_creada,
            mensaje = "msg",
            entidadTipo = EntidadNotificacion.empresa,
            entidadId = 10,
        )

        verify(exactly = 0) { notificacionRepository.save(any()) }
    }

    @Test
    fun `listar devuelve las notificaciones con el resumen del actor resuelto`() {
        val notificacion =
            Notificacion(
                id = 1,
                idEmpleadoDestinatario = 1,
                idActor = 2,
                tipo = TipoNotificacion.tarea_creada,
                mensaje = "Carlos te asignó una tarea",
                entidadTipo = EntidadNotificacion.empresa,
                entidadId = 10,
                createdAt = LocalDateTime.now(),
            )
        every { notificacionRepository.findTop20ByIdEmpleadoDestinatarioOrderByCreatedAtDesc(1) } returns listOf(notificacion)
        every { empleadoService.resumenPorIds(listOf(2)) } returns mapOf(2L to EmpleadoResumen(id = 2, nombres = "Carlos", apellidos = "Ruiz"))

        val resultado = service.listar(usuario)

        assertThat(resultado).hasSize(1)
        assertThat(resultado.first().actor?.nombres).isEqualTo("Carlos")
    }

    @Test
    fun `marcarLeida sobre una notificacion ajena o inexistente lanza NoEncontradoException`() {
        every { notificacionRepository.findByIdAndIdEmpleadoDestinatario(99, 1) } returns null

        assertThrows<NoEncontradoException> { service.marcarLeida(99, usuario) }
    }

    @Test
    fun `marcarLeida marca la notificacion propia como leida`() {
        val notificacion =
            Notificacion(
                id = 7,
                idEmpleadoDestinatario = 1,
                idActor = 2,
                tipo = TipoNotificacion.tarea_creada,
                mensaje = "msg",
                entidadTipo = EntidadNotificacion.empresa,
                entidadId = 10,
                createdAt = LocalDateTime.now(),
            )
        every { notificacionRepository.findByIdAndIdEmpleadoDestinatario(7, 1) } returns notificacion
        every { notificacionRepository.save(notificacion) } returns notificacion

        service.marcarLeida(7, usuario)

        assertThat(notificacion.leida).isTrue()
    }

    @Test
    fun `marcarTodasLeidas marca todas las pendientes del usuario`() {
        val pendientes =
            listOf(
                Notificacion(id = 1, idEmpleadoDestinatario = 1, idActor = null, tipo = TipoNotificacion.tarea_recordatorio, mensaje = "a", entidadTipo = EntidadNotificacion.empresa, entidadId = 1, createdAt = LocalDateTime.now()),
                Notificacion(id = 2, idEmpleadoDestinatario = 1, idActor = null, tipo = TipoNotificacion.tarea_recordatorio, mensaje = "b", entidadTipo = EntidadNotificacion.empresa, entidadId = 2, createdAt = LocalDateTime.now()),
            )
        every { notificacionRepository.findByIdEmpleadoDestinatarioAndLeidaFalse(1) } returns pendientes
        every { notificacionRepository.saveAll(pendientes) } returns pendientes

        service.marcarTodasLeidas(usuario)

        assertThat(pendientes).allMatch { it.leida }
    }

    @Test
    fun `contarNoLeidas delega en el repositorio`() {
        every { notificacionRepository.countByIdEmpleadoDestinatarioAndLeidaFalse(1) } returns 5L

        assertThat(service.contarNoLeidas(usuario)).isEqualTo(5L)
    }
}
