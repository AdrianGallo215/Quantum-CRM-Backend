package pe.quantum.crm.domain.empresas

import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.assertj.core.api.Assertions.catchThrowable
import org.junit.jupiter.api.Test
import org.springframework.context.ApplicationEventPublisher
import org.springframework.data.domain.PageImpl
import org.springframework.data.domain.PageRequest
import org.springframework.data.jpa.domain.Specification
import org.springframework.transaction.support.TransactionTemplate
import pe.quantum.crm.domain.contactos.ContactoService
import pe.quantum.crm.domain.empleados.EmpleadoService
import pe.quantum.crm.domain.empresas.dto.ActualizarEmpresaRequest
import pe.quantum.crm.domain.empresas.dto.CrearEmpresaRequest
import pe.quantum.crm.domain.empresas.dto.EmpresaFiltros
import pe.quantum.crm.domain.notificaciones.NotificacionService
import pe.quantum.crm.domain.tareas.TareaService
import pe.quantum.crm.integracion.drive.DriveStorageService
import pe.quantum.crm.shared.exception.NoEncontradoException
import pe.quantum.crm.shared.exception.PermisoInsuficienteException
import pe.quantum.crm.shared.security.UsuarioActual
import java.time.LocalDateTime
import java.util.Optional

class EmpresaRolApoyoTest {
    private val empresaRepository = mockk<EmpresaRepository>()
    private val empleadoService = mockk<EmpleadoService>()
    private val notificacionService = mockk<NotificacionService>(relaxed = true)
    private val eventPublisher = mockk<ApplicationEventPublisher>(relaxed = true)
    private val driveStorageService = mockk<DriveStorageService>()
    private val contactoService = mockk<ContactoService>()
    private val transactionTemplate = mockk<TransactionTemplate>()
    private val tareaService = mockk<TareaService>()
    private val service =
        EmpresaServiceImpl(
            empresaRepository,
            empleadoService,
            notificacionService,
            eventPublisher,
            driveStorageService,
            contactoService,
            transactionTemplate,
            tareaService,
        )

    private val analista = UsuarioActual(id = 7L, rol = "analista")
    private val otro = UsuarioActual(id = 8L, rol = "otro")

    private fun crearEmpresaRequestValido() = CrearEmpresaRequest(ruc = "20123456789", razonSocial = "Transportes ABC")

    private fun actualizarEmpresaRequestValido() = ActualizarEmpresaRequest(razonSocial = "Transportes ABC")

    private fun empresaDe(
        id: Long,
        idVendedor: Long?,
    ) = Empresa(
        id = id,
        ruc = "20123456789",
        razonSocial = "Transportes ABC",
        idVendedor = idVendedor,
        estadoCartera = pe.quantum.crm.shared.enums.EstadoCartera.prospeccion,
        createdAt = LocalDateTime.now(),
        createdBy = 1,
        updatedAt = LocalDateTime.now(),
        updatedBy = 1,
    )

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
            .hasMessageContaining("apoyo")
    }

    @Test
    fun `un rol de apoyo no puede mover una empresa a la cartera maestra`() {
        assertThatThrownBy { service.cambiarCarteraMaestra(1L, true, null, analista) }
            .isInstanceOf(PermisoInsuficienteException::class.java)
            .hasMessageContaining("apoyo")
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

    @Test
    fun `un rol de apoyo sin colaboraciones no ve ninguna empresa`() {
        every { tareaService.idsEmpresasDondeColabora(7L) } returns emptySet()
        every { empresaRepository.findAll(any<Specification<Empresa>>(), any<PageRequest>()) } returns
            PageImpl(emptyList())
        every { empleadoService.resumenPorIds(any()) } returns emptyMap()

        assertThat(service.listar(EmpresaFiltros(), analista, null, null, null, null).items).isEmpty()
    }

    @Test
    fun `un rol de apoyo no ve una empresa en la que no colabora`() {
        every { tareaService.idsEmpresasDondeColabora(7L) } returns setOf(99L)
        every { empresaRepository.findById(1L) } returns Optional.of(empresaDe(id = 1L, idVendedor = 3L))

        assertThatThrownBy { service.detalle(1L, analista) }
            .isInstanceOf(NoEncontradoException::class.java)
    }

    /**
     * Distingue "la regla cambio" de "sigue siendo la regla vieja": la empresa
     * tiene `idVendedor == usuario.id`, que bajo la regla anterior
     * (`visibilidadRestringida && idVendedor != id`) la haria "propia". El rol
     * de apoyo no tiene cartera propia — solo colaboracion via tarea — asi que
     * sin colaborar sigue siendo 404 aunque `idVendedor` coincida.
     */
    @Test
    fun `un rol de apoyo no ve una empresa donde figura como idVendedor pero no colabora`() {
        every { tareaService.idsEmpresasDondeColabora(7L) } returns setOf(99L)
        every { empresaRepository.findById(1L) } returns Optional.of(empresaDe(id = 1L, idVendedor = 7L))

        assertThatThrownBy { service.detalle(1L, analista) }
            .isInstanceOf(NoEncontradoException::class.java)
    }
}
