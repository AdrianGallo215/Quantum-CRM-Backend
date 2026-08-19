package pe.quantum.crm.domain.oportunidades

import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.assertj.core.api.Assertions.catchThrowable
import org.junit.jupiter.api.Test
import pe.quantum.crm.domain.contactos.ContactoService
import pe.quantum.crm.domain.empleados.EmpleadoService
import pe.quantum.crm.domain.empresas.EmpresaService
import pe.quantum.crm.domain.financiadoras.FinanciadoraService
import pe.quantum.crm.domain.modelos.ModeloService
import pe.quantum.crm.domain.notificaciones.NotificacionService
import pe.quantum.crm.domain.oportunidades.dto.ActualizarOportunidadRequest
import pe.quantum.crm.domain.oportunidades.dto.CambiarEstadoRequest
import pe.quantum.crm.integracion.drive.DriveStorageService
import pe.quantum.crm.shared.exception.PermisoInsuficienteException
import pe.quantum.crm.shared.security.UsuarioActual

class OportunidadRolApoyoTest {
    private val oportunidadRepository = mockk<OportunidadRepository>()
    private val logRepository = mockk<OportunidadEstadoLogRepository>()
    private val contactoOportunidadRepository = mockk<OportunidadContactoRepository>()
    private val estadoCarteraService = mockk<EstadoCarteraService>()
    private val empresaService = mockk<EmpresaService>()
    private val empleadoService = mockk<EmpleadoService>()
    private val financiadoraService = mockk<FinanciadoraService>()
    private val modeloService = mockk<ModeloService>()
    private val contactoService = mockk<ContactoService>()
    private val consultas = mockk<OportunidadConsultas>()
    private val notificacionService = mockk<NotificacionService>(relaxed = true)
    private val driveStorageService = mockk<DriveStorageService>(relaxed = true)
    private val service =
        OportunidadServiceImpl(
            oportunidadRepository,
            logRepository,
            contactoOportunidadRepository,
            estadoCarteraService,
            empresaService,
            empleadoService,
            financiadoraService,
            modeloService,
            contactoService,
            consultas,
            notificacionService,
            driveStorageService,
        )

    private val analista = UsuarioActual(id = 7L, rol = "analista")
    private val otro = UsuarioActual(id = 8L, rol = "otro")

    @Test
    fun `un rol de apoyo no puede actualizar una oportunidad`() {
        assertThatThrownBy { service.actualizar(1L, ActualizarOportunidadRequest(), analista) }
            .isInstanceOf(PermisoInsuficienteException::class.java)
            .hasMessageContaining("apoyo")
    }

    @Test
    fun `el mensaje de error explica que el rol es de apoyo y solo consulta`() {
        val error = catchThrowable { service.actualizar(1L, ActualizarOportunidadRequest(), otro) }

        assertThat(error).isInstanceOf(PermisoInsuficienteException::class.java)
        assertThat(error.message)
            .contains("apoyo")
            .contains("consultar")
    }

    @Test
    fun `un rol de apoyo no puede cambiar el estado de una oportunidad`() {
        assertThatThrownBy {
            service.cambiarEstado(1L, CambiarEstadoRequest(estado = "documentos_legales"), analista)
        }.isInstanceOf(PermisoInsuficienteException::class.java)
    }
}
