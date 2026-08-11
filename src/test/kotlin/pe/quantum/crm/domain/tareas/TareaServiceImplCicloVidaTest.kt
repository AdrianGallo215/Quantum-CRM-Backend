package pe.quantum.crm.domain.tareas

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import pe.quantum.crm.domain.contactos.ContactoService
import pe.quantum.crm.domain.contactos.dto.ContactoResumen
import pe.quantum.crm.domain.empleados.EmpleadoService
import pe.quantum.crm.domain.empleados.dto.EmpleadoResumen
import pe.quantum.crm.domain.empresas.EmpresaService
import pe.quantum.crm.domain.empresas.dto.EmpresaResumen
import pe.quantum.crm.domain.empresas.dto.EmpresaVinculo
import pe.quantum.crm.domain.notificaciones.EntidadNotificacion
import pe.quantum.crm.domain.notificaciones.NotificacionService
import pe.quantum.crm.domain.notificaciones.TipoNotificacion
import pe.quantum.crm.domain.oportunidades.OportunidadService
import pe.quantum.crm.domain.oportunidades.dto.OportunidadVinculo
import pe.quantum.crm.domain.tareas.dto.ActualizarTareaRequest
import pe.quantum.crm.domain.tareas.dto.CrearTareaRequest
import pe.quantum.crm.shared.enums.EstadoAccion
import pe.quantum.crm.shared.enums.TipoAccion
import pe.quantum.crm.shared.exception.EstadoInvalidoException
import pe.quantum.crm.shared.exception.NoEncontradoException
import pe.quantum.crm.shared.exception.PermisoInsuficienteException
import pe.quantum.crm.shared.exception.ValidacionException
import pe.quantum.crm.shared.security.UsuarioActual
import java.time.Instant
import java.time.LocalDateTime
import java.util.Optional

/**
 * Ciclo de vida de una tarea: alta con sus guard clauses (reglas §10.2 y
 * matriz_permisos.md §2.6), completar/cancelar y edicion. Va en su propio
 * archivo para no engordar `TareaServiceImplTest`, que cubre notificaciones y
 * recordatorios.
 */
class TareaServiceImplCicloVidaTest {
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

    private val gerencia = UsuarioActual(id = 1, rol = "gerencia")
    private val vendedor = UsuarioActual(id = 9, rol = "vendedor")

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

    private fun empresaVinculo(idVendedor: Long = 9) =
        EmpresaVinculo(id = 10, razonSocial = "Kincar S.A.C.", idVendedor = idVendedor, estadoCartera = "prospeccion")

    /** Stubs de `toDtos`: los resumenes que compone el DTO de salida. */
    private fun stubsDeSalida() {
        every { empresaService.resumenPorIds(listOf(10)) } returns
            mapOf(10L to EmpresaResumen(id = 10, razonSocial = "Kincar S.A.C.", distrito = null))
        every { contactoService.resumenPorIds(any()) } returns emptyMap()
        every { empleadoService.resumenPorIds(any()) } returns emptyMap()
        every { empleadoService.resumenPorIds(listOf(1)) } returns
            mapOf(1L to EmpleadoResumen(id = 1, nombres = "Ana", apellidos = "Gerencia"))
        every { empleadoService.resumenPorIds(listOf(9)) } returns
            mapOf(9L to EmpleadoResumen(id = 9, nombres = "Diego", apellidos = "Reyes"))
    }

    // ── crear ──────────────────────────────────────────────────

    @Test
    fun `crear una tarea sobre una oportunidad la vincula a la oportunidad y notifica con esa entidad`() {
        every { empresaService.vinculoVisible(10, any()) } returns empresaVinculo()
        every { oportunidadService.vinculoVisible(50, any()) } returns
            OportunidadVinculo(id = 50, idEmpresa = 10, idVendedor = 9, estado = "evaluacion_calidda")
        every { empleadoService.existeActivo(9) } returns true
        every { tareaRepository.save(any()) } answers { firstArg<Tarea>().conId(1) }
        stubsDeSalida()

        val dto =
            service.crear(
                CrearTareaRequest(idEmpresa = 10, idOportunidad = 50, tipoAccion = TipoAccion.reunion),
                vendedor,
            )

        assertThat(dto.idOportunidad).isEqualTo(50)
        verify {
            notificacionService.notificar(
                destinatarios = setOf(9L),
                idActor = 9L,
                tipo = TipoNotificacion.tarea_creada,
                mensaje = any(),
                entidadTipo = EntidadNotificacion.oportunidad,
                entidadId = 50L,
            )
        }
    }

    @Test
    fun `crear una tarea con una oportunidad de otra empresa lanza VALIDACION`() {
        every { empresaService.vinculoVisible(10, any()) } returns empresaVinculo()
        every { oportunidadService.vinculoVisible(50, any()) } returns
            OportunidadVinculo(id = 50, idEmpresa = 77, idVendedor = 9, estado = "evaluacion_calidda")

        val ex =
            assertThrows<ValidacionException> {
                service.crear(
                    CrearTareaRequest(idEmpresa = 10, idOportunidad = 50, tipoAccion = TipoAccion.llamada),
                    vendedor,
                )
            }

        assertThat(ex.field).isEqualTo("id_oportunidad")
    }

    @Test
    fun `regla 10 2 - una empresa con oportunidad activa no admite tareas sueltas de empresa`() {
        every { empresaService.vinculoVisible(10, any()) } returns empresaVinculo()
        every { oportunidadService.tieneOportunidadesActivas(10) } returns true

        val ex =
            assertThrows<ValidacionException> {
                service.crear(CrearTareaRequest(idEmpresa = 10, tipoAccion = TipoAccion.llamada), vendedor)
            }

        assertThat(ex.field).isEqualTo("id_oportunidad")
        verify(exactly = 0) { tareaRepository.save(any()) }
    }

    @Test
    fun `crear una tarea con un contacto inexistente devuelve 404`() {
        every { empresaService.vinculoVisible(10, any()) } returns empresaVinculo()
        every { oportunidadService.tieneOportunidadesActivas(10) } returns false
        every { contactoService.existe(77) } returns false

        assertThrows<NoEncontradoException> {
            service.crear(
                CrearTareaRequest(idEmpresa = 10, idContacto = 77, tipoAccion = TipoAccion.llamada),
                vendedor,
            )
        }
    }

    @Test
    fun `crear una tarea con un contacto existente lo expone en el DTO`() {
        every { empresaService.vinculoVisible(10, any()) } returns empresaVinculo()
        every { oportunidadService.tieneOportunidadesActivas(10) } returns false
        every { contactoService.existe(77) } returns true
        every { empleadoService.existeActivo(9) } returns true
        every { tareaRepository.save(any()) } answers { firstArg<Tarea>().conId(1) }
        stubsDeSalida()
        every { contactoService.resumenPorIds(listOf(77)) } returns
            mapOf(77L to ContactoResumen(id = 77, nombres = "Luis", apellidos = "Paz"))

        val dto =
            service.crear(
                CrearTareaRequest(idEmpresa = 10, idContacto = 77, tipoAccion = TipoAccion.llamada),
                vendedor,
            )

        assertThat(dto.idContacto).isEqualTo(77)
        assertThat(dto.contacto?.nombres).isEqualTo("Luis")
    }

    @Test
    fun `crear una tarea sobre un empleado inactivo devuelve 404`() {
        every { empresaService.vinculoVisible(10, any()) } returns empresaVinculo()
        every { oportunidadService.tieneOportunidadesActivas(10) } returns false
        every { empleadoService.existeActivo(9) } returns false

        assertThrows<NoEncontradoException> {
            service.crear(CrearTareaRequest(idEmpresa = 10, tipoAccion = TipoAccion.llamada), vendedor)
        }
    }

    @Test
    fun `crear una tarea con un colaborador inactivo devuelve 404`() {
        every { empresaService.vinculoVisible(10, any()) } returns empresaVinculo()
        every { oportunidadService.tieneOportunidadesActivas(10) } returns false
        every { empleadoService.existeActivo(1) } returns true
        every { empleadoService.existeActivo(4) } returns false

        assertThrows<NoEncontradoException> {
            service.crear(
                CrearTareaRequest(idEmpresa = 10, idsColaboradores = listOf(4), tipoAccion = TipoAccion.llamada),
                gerencia,
            )
        }
    }

    @Test
    fun `el dueno de la tarea nunca queda tambien como colaborador de si mismo`() {
        every { empresaService.vinculoVisible(10, any()) } returns empresaVinculo()
        every { oportunidadService.tieneOportunidadesActivas(10) } returns false
        every { empleadoService.existeActivo(9) } returns true
        every { tareaRepository.save(any()) } answers { firstArg<Tarea>().conId(1) }
        stubsDeSalida()

        service.crear(
            CrearTareaRequest(idEmpresa = 10, idsColaboradores = listOf(9), tipoAccion = TipoAccion.llamada),
            vendedor,
        )

        verify(exactly = 0) { tareaResponsableRepository.saveAll(any<List<TareaResponsable>>()) }
        verify(exactly = 0) {
            notificacionService.notificar(any(), any(), TipoNotificacion.tarea_colaborador_agregado, any(), any(), any())
        }
    }

    // ── completar / cancelar ───────────────────────────────────

    private fun tareaPendiente(
        idAsignado: Long = 3,
        estado: EstadoAccion = EstadoAccion.pendiente,
    ) = Tarea(
        id = 1, idEmpresa = 10, idOportunidad = null, idContacto = null, idAsignado = idAsignado,
        tipoAccion = TipoAccion.llamada, estadoAccion = estado, descripcion = "Llamar",
        createdAt = LocalDateTime.of(2026, 7, 1, 9, 0), createdBy = 1,
        updatedAt = LocalDateTime.of(2026, 7, 1, 9, 0), updatedBy = 1,
    )

    @Test
    fun `completar una tarea pendiente la deja completada y sella el auditor`() {
        val tarea = tareaPendiente()
        every { tareaRepository.findById(1) } returns Optional.of(tarea)
        every { tareaRepository.save(any()) } answers { firstArg() }
        stubsDeSalida()

        val dto = service.completar(1, null, gerencia)

        assertThat(dto.estadoAccion).isEqualTo("completada")
        assertThat(dto.descripcion).isEqualTo("Llamar")
        assertThat(tarea.updatedBy).isEqualTo(1)
    }

    @Test
    fun `completar con descripcion sustituye la de la tarea`() {
        every { tareaRepository.findById(1) } returns Optional.of(tareaPendiente())
        every { tareaRepository.save(any()) } answers { firstArg() }
        stubsDeSalida()

        val dto = service.completar(1, "Contestó el gerente de flota", gerencia)

        assertThat(dto.descripcion).isEqualTo("Contestó el gerente de flota")
    }

    @Test
    fun `solo una tarea pendiente puede completarse`() {
        every { tareaRepository.findById(1) } returns Optional.of(tareaPendiente(estado = EstadoAccion.cancelada))

        assertThrows<EstadoInvalidoException> { service.completar(1, null, gerencia) }
        verify(exactly = 0) { tareaRepository.save(any()) }
    }

    @Test
    fun `cancelar una tarea pendiente la deja cancelada`() {
        every { tareaRepository.findById(1) } returns Optional.of(tareaPendiente())
        every { tareaRepository.save(any()) } answers { firstArg() }
        stubsDeSalida()

        val dto = service.cancelar(1, gerencia)

        assertThat(dto.estadoAccion).isEqualTo("cancelada")
    }

    @Test
    fun `solo una tarea pendiente puede cancelarse`() {
        every { tareaRepository.findById(1) } returns Optional.of(tareaPendiente(estado = EstadoAccion.completada))

        assertThrows<EstadoInvalidoException> { service.cancelar(1, gerencia) }
    }

    // ── visibilidad (IDOR → 404) ───────────────────────────────

    @Test
    fun `una tarea inexistente devuelve 404`() {
        every { tareaRepository.findById(99) } returns Optional.empty()

        assertThrows<NoEncontradoException> { service.completar(99, null, gerencia) }
    }

    @Test
    fun `una tarea ajena devuelve 404 al vendedor que no la posee ni colabora`() {
        every { tareaRepository.findById(1) } returns Optional.of(tareaPendiente(idAsignado = 3))
        every { tareaResponsableRepository.existsByIdIdTareaAndIdIdEmpleado(1, 9) } returns false

        assertThrows<NoEncontradoException> { service.cancelar(1, vendedor) }
    }

    @Test
    fun `un colaborador si puede completar una tarea cuyo dueno es otro`() {
        every { tareaRepository.findById(1) } returns Optional.of(tareaPendiente(idAsignado = 3))
        every { tareaResponsableRepository.existsByIdIdTareaAndIdIdEmpleado(1, 9) } returns true
        every { tareaRepository.save(any()) } answers { firstArg() }
        stubsDeSalida()

        val dto = service.completar(1, null, vendedor)

        assertThat(dto.estadoAccion).isEqualTo("completada")
    }

    // ── actualizar ─────────────────────────────────────────────

    @Test
    fun `solo se pueden editar tareas pendientes`() {
        every { tareaRepository.findById(1) } returns Optional.of(tareaPendiente(estado = EstadoAccion.completada))

        assertThrows<EstadoInvalidoException> {
            service.actualizar(1, ActualizarTareaRequest(descripcion = "otra cosa"), gerencia)
        }
    }

    @Test
    fun `actualizar cambia tipo de accion, descripcion, fecha y contacto de una sola vez`() {
        every { tareaRepository.findById(1) } returns Optional.of(tareaPendiente())
        every { contactoService.existe(77) } returns true
        every { tareaRepository.save(any()) } answers { firstArg() }
        stubsDeSalida()

        val dto =
            service.actualizar(
                1,
                ActualizarTareaRequest(
                    tipoAccion = TipoAccion.reunion,
                    descripcion = "Visita a planta",
                    fechaEjecucion = Instant.parse("2026-08-01T14:00:00Z"),
                    idContacto = 77,
                ),
                gerencia,
            )

        assertThat(dto.tipoAccion).isEqualTo("reunion")
        assertThat(dto.descripcion).isEqualTo("Visita a planta")
        assertThat(dto.fechaEjecucion).isEqualTo(Instant.parse("2026-08-01T14:00:00Z"))
        assertThat(dto.idContacto).isEqualTo(77)
    }

    @Test
    fun `actualizar con un contacto inexistente devuelve 404`() {
        every { tareaRepository.findById(1) } returns Optional.of(tareaPendiente())
        every { contactoService.existe(77) } returns false

        assertThrows<NoEncontradoException> {
            service.actualizar(1, ActualizarTareaRequest(idContacto = 77), gerencia)
        }
    }

    @Test
    fun `actualizar reasignando a un empleado inactivo devuelve 404`() {
        every { tareaRepository.findById(1) } returns Optional.of(tareaPendiente())
        every { empleadoService.existeActivo(7) } returns false

        assertThrows<NoEncontradoException> {
            service.actualizar(1, ActualizarTareaRequest(idAsignado = 7), gerencia)
        }
    }

    @Test
    fun `reasignar al mismo dueno que ya tenia no dispara notificacion`() {
        every { tareaRepository.findById(1) } returns Optional.of(tareaPendiente(idAsignado = 3))
        every { tareaRepository.save(any()) } answers { firstArg() }
        stubsDeSalida()

        service.actualizar(1, ActualizarTareaRequest(idAsignado = 3), gerencia)

        verify(exactly = 0) { notificacionService.notificar(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `reemplazar colaboradores por uno inactivo devuelve 404 sin borrar el set actual`() {
        every { tareaRepository.findById(1) } returns Optional.of(tareaPendiente())
        every { empleadoService.existeActivo(4) } returns false

        assertThrows<NoEncontradoException> {
            service.actualizar(1, ActualizarTareaRequest(idsColaboradores = listOf(4)), gerencia)
        }
        verify(exactly = 0) { tareaResponsableRepository.deleteByIdIdTarea(any()) }
    }

    @Test
    fun `enviar una lista vacia de colaboradores los borra a todos y no notifica a nadie`() {
        every { tareaRepository.findById(1) } returns Optional.of(tareaPendiente())
        every { tareaResponsableRepository.findByIdIdTarea(1) } returns
            listOf(TareaResponsable(id = TareaResponsableId(idTarea = 1, idEmpleado = 4), createdBy = 1))
        every { tareaRepository.save(any()) } answers { firstArg() }
        stubsDeSalida()

        service.actualizar(1, ActualizarTareaRequest(idsColaboradores = emptyList()), gerencia)

        verify { tareaResponsableRepository.deleteByIdIdTarea(1) }
        verify(exactly = 0) { tareaResponsableRepository.saveAll(any<List<TareaResponsable>>()) }
        verify(exactly = 0) { notificacionService.notificar(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `un vendedor no puede reasignar la tarea a otro empleado`() {
        every { tareaRepository.findById(1) } returns Optional.of(tareaPendiente(idAsignado = 9))

        assertThrows<PermisoInsuficienteException> {
            service.actualizar(1, ActualizarTareaRequest(idAsignado = 3), vendedor)
        }
    }
}
