package pe.quantum.crm.domain.solicitudes

import io.mockk.every
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
import pe.quantum.crm.domain.oportunidades.OportunidadService
import pe.quantum.crm.domain.oportunidades.dto.OportunidadVinculo
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
    private val oportunidadService = mockk<OportunidadService>()
    private val empresaService = mockk<EmpresaService>()
    private val empleadoService = mockk<EmpleadoService>()
    private val notificacionService = mockk<NotificacionService>(relaxed = true)
    private val service =
        SolicitudServiceImpl(solicitudRepository, oportunidadService, empresaService, empleadoService, notificacionService)

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
            entidadTipo = EntidadSolicitud.oportunidad,
            entidadId = 45,
            motivo = "Cliente frecuente",
            dctoSolicitado = BigDecimal(dcto),
        )

    @Test
    fun `crear descuento 5 pct de vendedor deriva aprobador jdv y notifica a los jdv activos`() {
        every { oportunidadService.vinculoVisible(45, vendedor) } returns
            OportunidadVinculo(id = 45, idEmpresa = 10, idVendedor = 5, estado = "evaluacion_calidda")
        every { empresaService.resumenPorIds(listOf(10)) } returns
            mapOf(10L to EmpresaResumen(id = 10, razonSocial = "Transportes Lima Norte S.A.C.", distrito = null))
        every {
            solicitudRepository.existsByTipoAndEntidadTipoAndEntidadIdAndEstado(
                TipoSolicitud.descuento,
                EntidadSolicitud.oportunidad,
                45,
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
        every { oportunidadService.vinculoVisible(45, vendedor) } returns
            OportunidadVinculo(id = 45, idEmpresa = 10, idVendedor = 5, estado = "evaluacion_calidda")
        assertThatThrownBy { service.crear(requestDescuento("2.00"), vendedor) }
            .isInstanceOf(ValidacionException::class.java)
    }

    @Test
    fun `crear con pendiente duplicada es 409 SOLICITUD_DUPLICADA`() {
        every { oportunidadService.vinculoVisible(45, vendedor) } returns
            OportunidadVinculo(id = 45, idEmpresa = 10, idVendedor = 5, estado = "evaluacion_calidda")
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
}
