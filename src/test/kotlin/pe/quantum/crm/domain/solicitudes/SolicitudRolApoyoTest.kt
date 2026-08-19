package pe.quantum.crm.domain.solicitudes

import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import pe.quantum.crm.domain.empleados.EmpleadoService
import pe.quantum.crm.domain.empresas.EmpresaService
import pe.quantum.crm.domain.notificaciones.NotificacionService
import pe.quantum.crm.domain.oportunidades.OportunidadService
import pe.quantum.crm.domain.solicitudes.dto.CrearSolicitudRequest
import pe.quantum.crm.shared.enums.EntidadSolicitud
import pe.quantum.crm.shared.enums.TipoSolicitud
import pe.quantum.crm.shared.exception.PermisoInsuficienteException
import pe.quantum.crm.shared.security.UsuarioActual
import java.math.BigDecimal

class SolicitudRolApoyoTest {
    private val solicitudRepository = mockk<SolicitudRepository>()
    private val oportunidadService = mockk<OportunidadService>()
    private val empresaService = mockk<EmpresaService>()
    private val empleadoService = mockk<EmpleadoService>()
    private val notificacionService = mockk<NotificacionService>(relaxed = true)
    private val service =
        SolicitudServiceImpl(solicitudRepository, oportunidadService, empresaService, empleadoService, notificacionService)

    private fun solicitudDescuentoRequest() =
        CrearSolicitudRequest(
            tipo = TipoSolicitud.descuento,
            entidadTipo = EntidadSolicitud.oportunidad,
            entidadId = 45,
            motivo = "Cliente frecuente",
            dctoSolicitado = BigDecimal("5.00"),
        )

    private fun solicitudReasignacionRequest() =
        CrearSolicitudRequest(
            tipo = TipoSolicitud.reasignacion_cliente,
            entidadTipo = EntidadSolicitud.empresa,
            entidadId = 12,
            motivo = "Vacaciones largas del vendedor actual",
            idVendedorNuevo = 8,
        )

    @Test
    fun `un rol de apoyo no puede crear una solicitud de descuento`() {
        assertThatThrownBy { service.crear(solicitudDescuentoRequest(), UsuarioActual(7L, "analista")) }
            .isInstanceOf(PermisoInsuficienteException::class.java)
            .hasMessageContaining("apoyo")
    }

    @Test
    fun `un rol de apoyo no puede crear una solicitud de reasignacion`() {
        assertThatThrownBy { service.crear(solicitudReasignacionRequest(), UsuarioActual(8L, "otro")) }
            .isInstanceOf(PermisoInsuficienteException::class.java)
    }
}
