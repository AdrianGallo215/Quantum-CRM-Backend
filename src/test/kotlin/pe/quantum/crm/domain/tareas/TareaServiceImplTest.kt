package pe.quantum.crm.domain.tareas

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import pe.quantum.crm.domain.contactos.ContactoService
import pe.quantum.crm.domain.empleados.EmpleadoService
import pe.quantum.crm.domain.empleados.dto.EmpleadoResumen
import pe.quantum.crm.domain.empresas.EmpresaService
import pe.quantum.crm.domain.empresas.dto.EmpresaResumen
import pe.quantum.crm.domain.empresas.dto.EmpresaVinculo
import pe.quantum.crm.domain.notificaciones.EntidadNotificacion
import pe.quantum.crm.domain.notificaciones.NotificacionService
import pe.quantum.crm.domain.notificaciones.TipoNotificacion
import pe.quantum.crm.domain.oportunidades.OportunidadService
import pe.quantum.crm.domain.tareas.dto.CrearTareaRequest
import pe.quantum.crm.shared.enums.EstadoAccion
import pe.quantum.crm.shared.enums.TipoAccion
import pe.quantum.crm.shared.security.UsuarioActual

class TareaServiceImplTest {
    private val tareaRepository = mockk<TareaRepository>()
    private val empresaService = mockk<EmpresaService>()
    private val oportunidadService = mockk<OportunidadService>()
    private val contactoService = mockk<ContactoService>()
    private val empleadoService = mockk<EmpleadoService>()
    private val notificacionService = mockk<NotificacionService>(relaxed = true)
    private val service =
        TareaServiceImpl(tareaRepository, empresaService, oportunidadService, contactoService, empleadoService, notificacionService)

    /** `Tarea.id` es `val` (autogenerado): se reconstruye con un id real, simulando lo que hace JPA al guardar. */
    private fun Tarea.conId(nuevoId: Long) =
        Tarea(
            id = nuevoId,
            idEmpresa = idEmpresa,
            idOportunidad = idOportunidad,
            idContacto = idContacto,
            idAsignado = idAsignado,
            tipoAccion = tipoAccion,
            estadoAccion = estadoAccion,
            descripcion = descripcion,
            fechaEjecucion = fechaEjecucion,
            createdAt = createdAt,
            createdBy = createdBy,
            updatedAt = updatedAt,
            updatedBy = updatedBy,
        )

    @Test
    fun `crear tarea con id_asignado distinto al actor notifica tarea_creada`() {
        every { empresaService.vinculoVisible(10, any()) } returns
            EmpresaVinculo(id = 10, razonSocial = "Kincar S.A.C.", idVendedor = 3, estadoCartera = "prospeccion")
        every { oportunidadService.tieneOportunidadesActivas(10) } returns false
        every { empleadoService.existeActivo(3) } returns true
        every { tareaRepository.save(any()) } answers { firstArg<Tarea>().conId(1) }
        every { empresaService.resumenPorIds(listOf(10)) } returns
            mapOf(10L to EmpresaResumen(id = 10, razonSocial = "Kincar S.A.C.", distrito = null))
        every { contactoService.resumenPorIds(emptyList()) } returns emptyMap()
        every { empleadoService.resumenPorIds(any()) } returns emptyMap()
        every { empleadoService.resumenPorIds(listOf(9)) } returns
            mapOf(9L to EmpleadoResumen(id = 9, nombres = "Diego", apellidos = "Reyes"))

        service.crear(
            CrearTareaRequest(idEmpresa = 10, idAsignado = 3, tipoAccion = TipoAccion.llamada, descripcion = "Llamar"),
            UsuarioActual(id = 9, rol = "vendedor"),
        )

        verify {
            notificacionService.notificar(
                destinatarios = setOf(3L),
                idActor = 9L,
                tipo = TipoNotificacion.tarea_creada,
                mensaje = "Diego Reyes te asignó una tarea en Kincar S.A.C.",
                entidadTipo = EntidadNotificacion.empresa,
                entidadId = 10L,
            )
        }
    }

    @Test
    fun `pendientesParaRecordatorio proyecta solo tareas pendientes con asignado y fecha`() {
        every { tareaRepository.findByEstadoAccionAndIdAsignadoIsNotNullAndFechaEjecucionIsNotNull(EstadoAccion.pendiente) } returns
            listOf(
                Tarea(
                    id = 1,
                    idEmpresa = 10,
                    idOportunidad = null,
                    idAsignado = 3,
                    tipoAccion = TipoAccion.llamada,
                    estadoAccion = EstadoAccion.pendiente,
                    fechaEjecucion = java.time.LocalDateTime.of(2026, 7, 10, 9, 0),
                    createdAt = java.time.LocalDateTime.now(),
                    createdBy = 1,
                    updatedAt = java.time.LocalDateTime.now(),
                    updatedBy = 1,
                ),
            )

        val resultado = service.pendientesParaRecordatorio()

        assertThat(resultado).hasSize(1)
        assertThat(resultado.first().idAsignado).isEqualTo(3)
    }
}
