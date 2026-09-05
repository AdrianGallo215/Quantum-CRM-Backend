package pe.quantum.crm.domain.solicitudes

import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import pe.quantum.crm.domain.empleados.EmpleadoService
import pe.quantum.crm.domain.empleados.RolEmpleado
import pe.quantum.crm.domain.empresas.EmpresaService
import pe.quantum.crm.domain.empresas.dto.EmpresaResumen
import pe.quantum.crm.domain.empresas.dto.EmpresaVinculo
import pe.quantum.crm.domain.notificaciones.EntidadNotificacion
import pe.quantum.crm.domain.notificaciones.NotificacionService
import pe.quantum.crm.domain.notificaciones.TipoNotificacion
import pe.quantum.crm.domain.oportunidades.OportunidadItemService
import pe.quantum.crm.domain.oportunidades.dto.OportunidadItemVinculo
import pe.quantum.crm.domain.solicitudes.dto.CrearSolicitudRequest
import pe.quantum.crm.shared.enums.EntidadSolicitud
import pe.quantum.crm.shared.enums.EstadoSolicitud
import pe.quantum.crm.shared.enums.TipoSolicitud
import pe.quantum.crm.shared.exception.ConflictoException
import pe.quantum.crm.shared.exception.PermisoInsuficienteException
import pe.quantum.crm.shared.exception.ValidacionException
import pe.quantum.crm.shared.security.UsuarioActual
import java.math.BigDecimal

class SolicitudServiceImplTest {
    private val solicitudRepository = mockk<SolicitudRepository>()
    private val oportunidadItemService = mockk<OportunidadItemService>()
    private val empresaService = mockk<EmpresaService>()
    private val empleadoService = mockk<EmpleadoService>()
    private val notificacionService = mockk<NotificacionService>(relaxed = true)
    private val service =
        SolicitudServiceImpl(solicitudRepository, oportunidadItemService, empresaService, empleadoService, notificacionService)

    private val vendedor = UsuarioActual(id = 5, rol = "vendedor")
    private val jdv = UsuarioActual(id = 2, rol = "jdv")

    /** JPA asigna el id al guardar; el mock simula eso con una copia. */
    private fun Solicitud.conId(nuevoId: Long) =
        Solicitud(
            id = nuevoId,
            tipo = tipo,
            rolAprobador = rolAprobador,
            idSolicitante = idSolicitante,
            entidadTipo = entidadTipo,
            entidadId = entidadId,
            entidadDescripcion = entidadDescripcion,
            motivo = motivo,
            dctoSolicitado = dctoSolicitado,
            idVendedorNuevo = idVendedorNuevo,
        )

    private fun requestDescuento(dcto: String = "5.00") =
        CrearSolicitudRequest(
            tipo = TipoSolicitud.descuento,
            entidadTipo = EntidadSolicitud.oportunidad_item,
            entidadId = 91,
            motivo = "Cliente frecuente",
            dctoSolicitado = BigDecimal(dcto),
        )

    @Test
    fun `crear descuento 5 pct de vendedor deriva aprobador jdv y notifica a los jdv activos`() {
        every { oportunidadItemService.vinculoVisible(91, vendedor) } returns
            OportunidadItemVinculo(id = 91, idOportunidad = 45, idEmpresa = 10, descuento = null)
        every { empresaService.resumenPorIds(listOf(10)) } returns
            mapOf(10L to EmpresaResumen(id = 10, razonSocial = "Transportes Lima Norte S.A.C.", distrito = null))
        every {
            solicitudRepository.existsByTipoAndEntidadTipoAndEntidadIdAndEstado(
                TipoSolicitud.descuento,
                EntidadSolicitud.oportunidad_item,
                91,
                EstadoSolicitud.pendiente,
            )
        } returns false
        val guardada = slot<Solicitud>()
        every { solicitudRepository.save(capture(guardada)) } answers { guardada.captured.conId(77) }
        every { empleadoService.idsActivosPorRol(RolEmpleado.jdv) } returns listOf(2)
        every { empleadoService.resumenPorIds(any()) } returns emptyMap()

        val dto = service.crear(requestDescuento(), vendedor)

        assertThat(dto.rolAprobador).isEqualTo("jdv")
        assertThat(guardada.captured.entidadDescripcion).contains("Transportes Lima Norte")
        verify {
            notificacionService.notificar(
                destinatarios = setOf(2L),
                idActor = 5,
                tipo = TipoNotificacion.solicitud_creada,
                mensaje = any(),
                entidadTipo = EntidadNotificacion.solicitud,
                entidadId = any(),
            )
        }
    }

    @Test
    fun `crear descuento dentro del limite propio es VALIDACION - no necesita solicitud`() {
        every { oportunidadItemService.vinculoVisible(91, vendedor) } returns
            OportunidadItemVinculo(id = 91, idOportunidad = 45, idEmpresa = 10, descuento = null)
        assertThatThrownBy { service.crear(requestDescuento("2.00"), vendedor) }
            .isInstanceOf(ValidacionException::class.java)
    }

    @Test
    fun `crear con pendiente duplicada es 409 SOLICITUD_DUPLICADA`() {
        every { oportunidadItemService.vinculoVisible(91, vendedor) } returns
            OportunidadItemVinculo(id = 91, idOportunidad = 45, idEmpresa = 10, descuento = null)
        every { empresaService.resumenPorIds(listOf(10)) } returns
            mapOf(10L to EmpresaResumen(id = 10, razonSocial = "ABC", distrito = null))
        every {
            solicitudRepository.existsByTipoAndEntidadTipoAndEntidadIdAndEstado(any(), any(), any(), any())
        } returns true
        assertThatThrownBy { service.crear(requestDescuento(), vendedor) }
            .isInstanceOf(ConflictoException::class.java)
            .hasMessageContaining("pendiente")
    }

    @Test
    fun `vendedor no puede solicitar reasignacion de cliente`() {
        val request =
            CrearSolicitudRequest(
                tipo = TipoSolicitud.reasignacion_cliente,
                entidadTipo = EntidadSolicitud.empresa,
                entidadId = 12,
                motivo = "x",
                idVendedorNuevo = 8,
            )
        assertThatThrownBy { service.crear(request, vendedor) }
            .isInstanceOf(PermisoInsuficienteException::class.java)
    }

    @Test
    fun `jdv solicita reasignacion - aprobador gerencia y valida destino asignable`() {
        every { empresaService.vinculoVisible(12, jdv) } returns
            EmpresaVinculo(id = 12, razonSocial = "ABC S.A.", idVendedor = 5, estadoCartera = "cliente")
        every { empleadoService.esAsignableComoVendedor(8) } returns true
        every {
            solicitudRepository.existsByTipoAndEntidadTipoAndEntidadIdAndEstado(any(), any(), any(), any())
        } returns false
        val guardada = slot<Solicitud>()
        every { solicitudRepository.save(capture(guardada)) } answers { guardada.captured.conId(78) }
        every { empleadoService.idsActivosPorRol(RolEmpleado.gerencia) } returns listOf(1)
        every { empleadoService.resumenPorIds(any()) } returns emptyMap()

        val dto =
            service.crear(
                CrearSolicitudRequest(
                    tipo = TipoSolicitud.reasignacion_cliente,
                    entidadTipo = EntidadSolicitud.empresa,
                    entidadId = 12,
                    motivo = "Vacaciones largas del vendedor actual",
                    idVendedorNuevo = 8,
                ),
                jdv,
            )
        assertThat(dto.rolAprobador).isEqualTo("gerencia")
        assertThat(dto.entidadDescripcion).contains("ABC S.A.")
    }

    private fun solicitudDescuentoDe(idSolicitante: Long) =
        Solicitud(
            id = 9,
            tipo = TipoSolicitud.descuento,
            rolAprobador = pe.quantum.crm.shared.enums.AprobadorSolicitud.jdv,
            idSolicitante = idSolicitante,
            entidadTipo = EntidadSolicitud.oportunidad_item,
            entidadId = 91,
            entidadDescripcion = "X — Oportunidad #45 (ítem #91)",
            motivo = "m",
            dctoSolicitado = BigDecimal("5"),
        )

    @Test
    fun `listar como gerencia filtra su bandeja sin lanzar`() {
        val gerencia = UsuarioActual(id = 1, rol = "gerencia")
        every {
            solicitudRepository.findAll(
                any<org.springframework.data.jpa.domain.Specification<Solicitud>>(),
                any<org.springframework.data.domain.PageRequest>(),
            )
        } returns org.springframework.data.domain.PageImpl(emptyList())
        every { empleadoService.resumenPorIds(any()) } returns emptyMap()

        val resultado = service.listar(pe.quantum.crm.domain.solicitudes.dto.SolicitudFiltros(), gerencia, null, null, null, null)

        assertThat(resultado.items).isEmpty()
        assertThat(resultado.meta.page).isEqualTo(1)
    }

    @Test
    fun `detalle de solicitud ajena para vendedor es 404`() {
        every { solicitudRepository.findById(9) } returns java.util.Optional.of(solicitudDescuentoDe(99))
        assertThatThrownBy { service.detalle(9, vendedor) }
            .isInstanceOf(pe.quantum.crm.shared.exception.NoEncontradoException::class.java)
    }

    @Test
    fun `detalle propio para vendedor si es visible`() {
        every { solicitudRepository.findById(9) } returns java.util.Optional.of(solicitudDescuentoDe(5))
        every { empleadoService.resumenPorIds(any()) } returns emptyMap()
        assertThat(service.detalle(9, vendedor).id).isEqualTo(9)
    }

    private fun solicitudDescuentoPendiente(rolAprobador: pe.quantum.crm.shared.enums.AprobadorSolicitud) =
        Solicitud(
            id = 9,
            tipo = TipoSolicitud.descuento,
            rolAprobador = rolAprobador,
            idSolicitante = 5,
            entidadTipo = EntidadSolicitud.oportunidad_item,
            entidadId = 91,
            entidadDescripcion = "Transportes Lima Norte S.A.C. — Oportunidad #45 (ítem #91)",
            motivo = "Cliente frecuente",
            dctoSolicitado = BigDecimal("8.00"),
        )

    @Test
    fun `aprobar descuento aplica el cambio, marca aprobada y notifica al solicitante`() {
        val gerencia = UsuarioActual(id = 1, rol = "gerencia")
        val pendiente = solicitudDescuentoPendiente(pe.quantum.crm.shared.enums.AprobadorSolicitud.gerencia)
        every { solicitudRepository.findParaResolver(9) } returns pendiente
        every { oportunidadItemService.aplicarDescuentoAprobado(91, BigDecimal("8.00"), 1) } just Runs
        every { solicitudRepository.save(any()) } answers { firstArg() }
        every { empleadoService.resumenPorIds(any()) } returns emptyMap()

        val dto = service.aprobar(9, gerencia)

        assertThat(dto.estado).isEqualTo("aprobada")
        verify { oportunidadItemService.aplicarDescuentoAprobado(91, BigDecimal("8.00"), 1) }
        verify {
            notificacionService.notificar(
                destinatarios = setOf(5L),
                idActor = 1,
                tipo = TipoNotificacion.solicitud_aprobada,
                mensaje = any(),
                entidadTipo = EntidadNotificacion.solicitud,
                entidadId = 9,
            )
        }
    }

    @Test
    fun `aprobar una bandeja ajena es PERMISO_INSUFICIENTE`() {
        val pendienteJdv = solicitudDescuentoPendiente(pe.quantum.crm.shared.enums.AprobadorSolicitud.jdv)
        every { solicitudRepository.findParaResolver(9) } returns pendienteJdv
        assertThatThrownBy { service.aprobar(9, UsuarioActual(id = 1, rol = "gerencia")) }
            .isInstanceOf(PermisoInsuficienteException::class.java)
    }

    @Test
    fun `aprobar una ya resuelta es 409 SOLICITUD_YA_RESUELTA`() {
        val resuelta =
            solicitudDescuentoPendiente(pe.quantum.crm.shared.enums.AprobadorSolicitud.gerencia)
                .apply { estado = EstadoSolicitud.aprobada }
        every { solicitudRepository.findParaResolver(9) } returns resuelta
        assertThatThrownBy { service.aprobar(9, UsuarioActual(id = 1, rol = "gerencia")) }
            .isInstanceOf(ConflictoException::class.java)
            .hasMessageContaining("resuelta")
    }

    private fun solicitudReasignacionPendiente(
        entidadId: Long,
        idVendedorNuevo: Long,
    ) = Solicitud(
        id = 9,
        tipo = TipoSolicitud.reasignacion_cliente,
        rolAprobador = pe.quantum.crm.shared.enums.AprobadorSolicitud.gerencia,
        idSolicitante = 2,
        entidadTipo = EntidadSolicitud.empresa,
        entidadId = entidadId,
        entidadDescripcion = "ABC S.A.",
        motivo = "Vacaciones largas",
        idVendedorNuevo = idVendedorNuevo,
    )

    @Test
    fun `aprobar reasignacion ejecuta reasignarVendedor con el usuario aprobador`() {
        val gerencia = UsuarioActual(id = 1, rol = "gerencia")
        val pendiente = solicitudReasignacionPendiente(entidadId = 12, idVendedorNuevo = 8)
        every { solicitudRepository.findParaResolver(9) } returns pendiente
        every { empresaService.reasignarVendedor(12, 8, gerencia) } returns 8
        every { solicitudRepository.save(any()) } answers { firstArg() }
        every { empleadoService.resumenPorIds(any()) } returns emptyMap()

        val dto = service.aprobar(9, gerencia)

        assertThat(dto.estado).isEqualTo("aprobada")
        verify { empresaService.reasignarVendedor(12, 8, gerencia) }
    }

    @Test
    fun `denegar exige motivo y notifica con el mensaje de gerencia`() {
        val gerencia = UsuarioActual(id = 1, rol = "gerencia")
        val pendiente = solicitudDescuentoPendiente(pe.quantum.crm.shared.enums.AprobadorSolicitud.gerencia)
        every { solicitudRepository.findParaResolver(9) } returns pendiente
        every { solicitudRepository.save(any()) } answers { firstArg() }
        every { empleadoService.resumenPorIds(any()) } returns emptyMap()

        val dto = service.denegar(9, "El margen no lo soporta", gerencia)

        assertThat(dto.estado).isEqualTo("denegada")
        assertThat(dto.motivoResolucion).isEqualTo("El margen no lo soporta")
        verify {
            notificacionService.notificar(
                destinatarios = setOf(pendiente.idSolicitante),
                idActor = 1,
                tipo = TipoNotificacion.solicitud_denegada,
                mensaje = match { it.contains("El margen no lo soporta") },
                entidadTipo = EntidadNotificacion.solicitud,
                entidadId = 9,
            )
        }
    }

    @Test
    fun `denegar con motivo en blanco es VALIDACION`() {
        assertThatThrownBy { service.denegar(9, "  ", UsuarioActual(id = 1, rol = "gerencia")) }
            .isInstanceOf(ValidacionException::class.java)
    }
}
