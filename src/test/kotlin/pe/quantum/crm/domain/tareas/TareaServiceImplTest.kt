package pe.quantum.crm.domain.tareas

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
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
import pe.quantum.crm.domain.tareas.dto.ActualizarTareaRequest
import pe.quantum.crm.domain.tareas.dto.CrearTareaRequest
import pe.quantum.crm.shared.enums.EstadoAccion
import pe.quantum.crm.shared.enums.TipoAccion
import pe.quantum.crm.shared.exception.PermisoInsuficienteException
import pe.quantum.crm.shared.security.UsuarioActual
import java.time.Instant
import java.time.LocalDateTime

class TareaServiceImplTest {
    private val tareaRepository = mockk<TareaRepository>()
    private val tareaResponsableRepository = mockk<TareaResponsableRepository>(relaxed = true)
    private val empresaService = mockk<EmpresaService>()
    private val oportunidadService = mockk<OportunidadService>()
    private val contactoService = mockk<ContactoService>()
    private val empleadoService = mockk<EmpleadoService>()
    private val notificacionService = mockk<NotificacionService>(relaxed = true)
    private val service =
        TareaServiceImpl(
            tareaRepository,
            tareaResponsableRepository,
            empresaService,
            oportunidadService,
            contactoService,
            empleadoService,
            notificacionService,
        )

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

        // El dueño y el actor son distintos: requiere un rol supervisor (matriz_permisos.md §2.6).
        service.crear(
            CrearTareaRequest(idEmpresa = 10, idAsignado = 3, tipoAccion = TipoAccion.llamada, descripcion = "Llamar"),
            UsuarioActual(id = 9, rol = "admin"),
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
    fun `vendedor puede crear una tarea asignandose a si mismo sin lanzar excepcion`() {
        every { empresaService.vinculoVisible(10, any()) } returns
            EmpresaVinculo(id = 10, razonSocial = "Kincar S.A.C.", idVendedor = 9, estadoCartera = "prospeccion")
        every { oportunidadService.tieneOportunidadesActivas(10) } returns false
        every { empleadoService.existeActivo(9) } returns true
        every { tareaRepository.save(any()) } answers { firstArg<Tarea>().conId(1) }
        every { empresaService.resumenPorIds(listOf(10)) } returns
            mapOf(10L to EmpresaResumen(id = 10, razonSocial = "Kincar S.A.C.", distrito = null))
        every { contactoService.resumenPorIds(emptyList()) } returns emptyMap()
        every { empleadoService.resumenPorIds(any()) } returns emptyMap()

        val dto =
            service.crear(
                CrearTareaRequest(idEmpresa = 10, tipoAccion = TipoAccion.llamada),
                UsuarioActual(id = 9, rol = "vendedor"),
            )

        assertThat(dto.idAsignado).isEqualTo(9)
    }

    @Test
    fun `vendedor no puede asignar el dueno de una tarea a otro empleado`() {
        every { empresaService.vinculoVisible(10, any()) } returns
            EmpresaVinculo(id = 10, razonSocial = "Kincar S.A.C.", idVendedor = 9, estadoCartera = "prospeccion")
        every { oportunidadService.tieneOportunidadesActivas(10) } returns false

        assertThrows<PermisoInsuficienteException> {
            service.crear(
                CrearTareaRequest(idEmpresa = 10, idAsignado = 3, tipoAccion = TipoAccion.llamada),
                UsuarioActual(id = 9, rol = "vendedor"),
            )
        }
    }

    @Test
    fun `crear tarea con colaboradores notifica tarea_colaborador_agregado a cada uno`() {
        every { empresaService.vinculoVisible(10, any()) } returns
            EmpresaVinculo(id = 10, razonSocial = "Kincar S.A.C.", idVendedor = 9, estadoCartera = "prospeccion")
        every { oportunidadService.tieneOportunidadesActivas(10) } returns false
        every { empleadoService.existeActivo(9) } returns true
        every { empleadoService.existeActivo(4) } returns true
        every { empleadoService.existeActivo(5) } returns true
        every { tareaRepository.save(any()) } answers { firstArg<Tarea>().conId(1) }
        every { empresaService.resumenPorIds(listOf(10)) } returns
            mapOf(10L to EmpresaResumen(id = 10, razonSocial = "Kincar S.A.C.", distrito = null))
        every { contactoService.resumenPorIds(emptyList()) } returns emptyMap()
        every { empleadoService.resumenPorIds(any()) } returns emptyMap()
        every { empleadoService.resumenPorIds(listOf(9)) } returns
            mapOf(9L to EmpleadoResumen(id = 9, nombres = "Diego", apellidos = "Reyes"))

        service.crear(
            CrearTareaRequest(idEmpresa = 10, idsColaboradores = listOf(4, 5), tipoAccion = TipoAccion.llamada),
            UsuarioActual(id = 9, rol = "admin"),
        )

        verify {
            notificacionService.notificar(
                destinatarios = setOf(4L, 5L),
                idActor = 9L,
                tipo = TipoNotificacion.tarea_colaborador_agregado,
                mensaje = "Diego Reyes te agregó como colaborador en una tarea en Kincar S.A.C.",
                entidadTipo = EntidadNotificacion.empresa,
                entidadId = 10L,
            )
        }
    }

    private val tareaExistente =
        Tarea(
            id = 1, idEmpresa = 10, idOportunidad = null, idContacto = null, idAsignado = 3,
            tipoAccion = TipoAccion.llamada, estadoAccion = EstadoAccion.pendiente,
            descripcion = "Llamar",
            createdAt = LocalDateTime.of(2026, 7, 1, 9, 0), createdBy = 1,
            updatedAt = LocalDateTime.of(2026, 7, 1, 9, 0), updatedBy = 1,
        )

    @Test
    fun `actualizar reasigna el dueno y notifica solo al nuevo dueno`() {
        every { tareaRepository.findById(1) } returns java.util.Optional.of(tareaExistente)
        every { empleadoService.existeActivo(7) } returns true
        every { tareaRepository.save(any()) } answers { firstArg() }
        every { empresaService.resumenPorIds(listOf(10)) } returns
            mapOf(10L to EmpresaResumen(id = 10, razonSocial = "Kincar S.A.C.", distrito = null))
        every { contactoService.resumenPorIds(emptyList()) } returns emptyMap()
        every { empleadoService.resumenPorIds(any()) } returns emptyMap()
        every { empleadoService.resumenPorIds(listOf(1)) } returns
            mapOf(1L to EmpleadoResumen(id = 1, nombres = "Ana", apellidos = "Gerencia"))

        service.actualizar(1, ActualizarTareaRequest(idAsignado = 7), UsuarioActual(id = 1, rol = "gerencia"))

        verify {
            notificacionService.notificar(
                destinatarios = setOf(7L),
                idActor = 1L,
                tipo = TipoNotificacion.tarea_creada,
                mensaje = "Ana Gerencia te asignó una tarea en Kincar S.A.C.",
                entidadTipo = EntidadNotificacion.empresa,
                entidadId = 10L,
            )
        }
        verify(exactly = 0) {
            notificacionService.notificar(any(), any(), TipoNotificacion.tarea_colaborador_agregado, any(), any(), any())
        }
    }

    @Test
    fun `actualizar reemplaza colaboradores y notifica solo a los agregados`() {
        every { tareaRepository.findById(1) } returns java.util.Optional.of(tareaExistente)
        every { tareaResponsableRepository.findByIdIdTarea(1) } returns
            listOf(TareaResponsable(id = TareaResponsableId(idTarea = 1, idEmpleado = 4), createdBy = 1))
        every { empleadoService.existeActivo(4) } returns true
        every { empleadoService.existeActivo(5) } returns true
        every { tareaRepository.save(any()) } answers { firstArg() }
        every { empresaService.resumenPorIds(listOf(10)) } returns
            mapOf(10L to EmpresaResumen(id = 10, razonSocial = "Kincar S.A.C.", distrito = null))
        every { contactoService.resumenPorIds(emptyList()) } returns emptyMap()
        every { empleadoService.resumenPorIds(any()) } returns emptyMap()
        every { empleadoService.resumenPorIds(listOf(1)) } returns
            mapOf(1L to EmpleadoResumen(id = 1, nombres = "Ana", apellidos = "Gerencia"))

        service.actualizar(1, ActualizarTareaRequest(idsColaboradores = listOf(4, 5)), UsuarioActual(id = 1, rol = "gerencia"))

        verify {
            notificacionService.notificar(
                destinatarios = setOf(5L),
                idActor = 1L,
                tipo = TipoNotificacion.tarea_colaborador_agregado,
                mensaje = "Ana Gerencia te agregó como colaborador en una tarea en Kincar S.A.C.",
                entidadTipo = EntidadNotificacion.empresa,
                entidadId = 10L,
            )
        }
        verify { tareaResponsableRepository.deleteByIdIdTarea(1) }
    }

    @Test
    fun `vendedor no puede agregar como colaborador a otro empleado distinto de si mismo`() {
        val tareaPropia =
            Tarea(
                id = 1, idEmpresa = 10, idAsignado = 9,
                tipoAccion = TipoAccion.llamada, estadoAccion = EstadoAccion.pendiente,
                createdAt = LocalDateTime.now(), createdBy = 9,
                updatedAt = LocalDateTime.now(), updatedBy = 9,
            )
        every { tareaRepository.findById(1) } returns java.util.Optional.of(tareaPropia)

        assertThrows<PermisoInsuficienteException> {
            service.actualizar(1, ActualizarTareaRequest(idsColaboradores = listOf(4)), UsuarioActual(id = 9, rol = "vendedor"))
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
                    fechaEjecucion = LocalDateTime.of(2026, 7, 10, 9, 0),
                    createdAt = LocalDateTime.now(),
                    createdBy = 1,
                    updatedAt = LocalDateTime.now(),
                    updatedBy = 1,
                ),
            )

        val resultado = service.pendientesParaRecordatorio()

        assertThat(resultado).hasSize(1)
        assertThat(resultado.first().idAsignado).isEqualTo(3)
    }

    @Test
    fun `actividadesPorContacto mapea tipo_accion como titulo y ordena por fecha`() {
        val tarea1 =
            Tarea(
                id = 1, idEmpresa = 10, idContacto = 5, idAsignado = 3,
                tipoAccion = TipoAccion.llamada, estadoAccion = EstadoAccion.pendiente,
                descripcion = "Llamar para seguimiento",
                fechaEjecucion = LocalDateTime.of(2026, 7, 20, 10, 0),
                createdAt = LocalDateTime.of(2026, 7, 1, 9, 0), createdBy = 9,
                updatedAt = LocalDateTime.of(2026, 7, 1, 9, 0), updatedBy = 9,
            )
        every { tareaRepository.findByIdContactoOrdenado(5) } returns listOf(tarea1)

        val resultado = service.actividadesPorContacto(5, UsuarioActual(id = 9, rol = "admin"))

        assertThat(resultado).hasSize(1)
        val actividad = resultado.first()
        assertThat(actividad.tipo).isEqualTo("tarea")
        assertThat(actividad.titulo).isEqualTo("llamada")
        assertThat(actividad.descripcion).isEqualTo("Llamar para seguimiento")
        assertThat(actividad.estado).isEqualTo("pendiente")
        // La columna `fecha_ejecucion` es TIMESTAMP en UTC: el DTO la expone como instante.
        assertThat(actividad.fecha).isEqualTo(Instant.parse("2026-07-20T10:00:00Z"))
    }

    @Test
    fun `actividadesPorContacto oculta tareas asignadas a otros cuando la visibilidad es restringida`() {
        val propia =
            Tarea(
                id = 1, idEmpresa = 10, idContacto = 5, idAsignado = 9,
                tipoAccion = TipoAccion.llamada, estadoAccion = EstadoAccion.pendiente,
                createdAt = LocalDateTime.now(), createdBy = 9,
                updatedAt = LocalDateTime.now(), updatedBy = 9,
            )
        val ajena =
            Tarea(
                id = 2, idEmpresa = 10, idContacto = 5, idAsignado = 3,
                tipoAccion = TipoAccion.correo, estadoAccion = EstadoAccion.pendiente,
                createdAt = LocalDateTime.now(), createdBy = 3,
                updatedAt = LocalDateTime.now(), updatedBy = 3,
            )
        every { tareaRepository.findByIdContactoOrdenado(5) } returns listOf(propia, ajena)

        val resultado = service.actividadesPorContacto(5, UsuarioActual(id = 9, rol = "vendedor"))

        assertThat(resultado).hasSize(1)
        assertThat(resultado.first().id).isEqualTo(1)
    }

    @Test
    fun `actividadesPorContacto incluye tareas donde el usuario es colaborador aunque no sea el dueno`() {
        val ajenaConColaborador =
            Tarea(
                id = 2, idEmpresa = 10, idContacto = 5, idAsignado = 3,
                tipoAccion = TipoAccion.correo, estadoAccion = EstadoAccion.pendiente,
                createdAt = LocalDateTime.now(), createdBy = 3,
                updatedAt = LocalDateTime.now(), updatedBy = 3,
            )
        every { tareaRepository.findByIdContactoOrdenado(5) } returns listOf(ajenaConColaborador)
        every { tareaResponsableRepository.findByIdIdTareaIn(listOf(2)) } returns
            listOf(TareaResponsable(id = TareaResponsableId(idTarea = 2, idEmpleado = 9), createdBy = 3))

        val resultado = service.actividadesPorContacto(5, UsuarioActual(id = 9, rol = "vendedor"))

        assertThat(resultado).hasSize(1)
        assertThat(resultado.first().id).isEqualTo(2)
    }
}
