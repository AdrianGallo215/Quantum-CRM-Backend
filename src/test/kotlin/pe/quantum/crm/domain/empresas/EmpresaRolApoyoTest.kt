package pe.quantum.crm.domain.empresas

import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.assertj.core.api.Assertions.catchThrowable
import org.junit.jupiter.api.Test
import org.springframework.context.ApplicationEventPublisher
import org.springframework.transaction.support.TransactionTemplate
import pe.quantum.crm.domain.contactos.ContactoService
import pe.quantum.crm.domain.empleados.EmpleadoService
import pe.quantum.crm.domain.empresas.dto.ActualizarEmpresaRequest
import pe.quantum.crm.domain.empresas.dto.CrearEmpresaRequest
import pe.quantum.crm.domain.notificaciones.NotificacionService
import pe.quantum.crm.integracion.drive.DriveStorageService
import pe.quantum.crm.shared.exception.PermisoInsuficienteException
import pe.quantum.crm.shared.security.UsuarioActual

class EmpresaRolApoyoTest {
    private val empresaRepository = mockk<EmpresaRepository>()
    private val empleadoService = mockk<EmpleadoService>()
    private val notificacionService = mockk<NotificacionService>(relaxed = true)
    private val eventPublisher = mockk<ApplicationEventPublisher>(relaxed = true)
    private val driveStorageService = mockk<DriveStorageService>()
    private val contactoService = mockk<ContactoService>()
    private val transactionTemplate = mockk<TransactionTemplate>()
    private val service =
        EmpresaServiceImpl(
            empresaRepository,
            empleadoService,
            notificacionService,
            eventPublisher,
            driveStorageService,
            contactoService,
            transactionTemplate,
        )

    private val analista = UsuarioActual(id = 7L, rol = "analista")
    private val otro = UsuarioActual(id = 8L, rol = "otro")

    private fun crearEmpresaRequestValido() = CrearEmpresaRequest(ruc = "20123456789", razonSocial = "Transportes ABC")

    private fun actualizarEmpresaRequestValido() = ActualizarEmpresaRequest(razonSocial = "Transportes ABC")

    @Test
    fun `un rol de apoyo no puede crear una empresa`() {
        assertThatThrownBy { service.crear(crearEmpresaRequestValido(), analista) }
            .isInstanceOf(PermisoInsuficienteException::class.java)
            .hasMessageContaining("apoyo")
    }

    @Test
    fun `un rol de apoyo no puede crear una empresa sin carpeta de drive`() {
        assertThatThrownBy { service.crearSinCarpetaDrive(crearEmpresaRequestValido(), analista) }
            .isInstanceOf(PermisoInsuficienteException::class.java)
            .hasMessageContaining("apoyo")
    }

    @Test
    fun `un rol de apoyo no puede actualizar una empresa`() {
        assertThatThrownBy { service.actualizar(1L, actualizarEmpresaRequestValido(), analista) }
            .isInstanceOf(PermisoInsuficienteException::class.java)
    }

    @Test
    fun `un rol de apoyo no puede cambiar el estado de cartera`() {
        assertThatThrownBy { service.cambiarEstadoCarteraManual(1L, "prospeccion", analista) }
            .isInstanceOf(PermisoInsuficienteException::class.java)
    }

    @Test
    fun `un rol de apoyo no puede reasignar el vendedor`() {
        assertThatThrownBy { service.reasignarVendedor(1L, 2L, analista) }
            .isInstanceOf(PermisoInsuficienteException::class.java)
    }

    @Test
    fun `un rol de apoyo no puede mover una empresa a la cartera maestra`() {
        assertThatThrownBy { service.cambiarCarteraMaestra(1L, true, null, analista) }
            .isInstanceOf(PermisoInsuficienteException::class.java)
    }

    @Test
    fun `un rol de apoyo no puede asegurar la carpeta de drive de una empresa`() {
        assertThatThrownBy { service.asegurarCarpetaDrive(1L, analista) }
            .isInstanceOf(PermisoInsuficienteException::class.java)
            .hasMessageContaining("apoyo")
    }

    @Test
    fun `el mensaje de error explica que el rol es de apoyo y solo consulta`() {
        val error = catchThrowable { service.actualizar(1L, actualizarEmpresaRequestValido(), otro) }

        assertThat(error).isInstanceOf(PermisoInsuficienteException::class.java)
        assertThat(error.message)
            .contains("apoyo")
            .contains("consultar")
    }
}
