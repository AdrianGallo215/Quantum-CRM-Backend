package pe.quantum.crm.domain.oportunidades

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatCode
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.assertj.core.api.Assertions.catchThrowable
import org.junit.jupiter.api.Test
import org.springframework.data.domain.PageImpl
import org.springframework.data.domain.PageRequest
import org.springframework.data.jpa.domain.Specification
import pe.quantum.crm.domain.contactos.ContactoService
import pe.quantum.crm.domain.empleados.EmpleadoService
import pe.quantum.crm.domain.empresas.EmpresaService
import pe.quantum.crm.domain.financiadoras.FinanciadoraService
import pe.quantum.crm.domain.modelos.ModeloService
import pe.quantum.crm.domain.notificaciones.NotificacionService
import pe.quantum.crm.domain.oportunidades.dto.ActualizarOportunidadRequest
import pe.quantum.crm.domain.oportunidades.dto.CambiarEstadoRequest
import pe.quantum.crm.domain.oportunidades.dto.OportunidadFiltros
import pe.quantum.crm.domain.tareas.TareaService
import pe.quantum.crm.integracion.drive.DriveStorageService
import pe.quantum.crm.shared.enums.EstadoOportunidad
import pe.quantum.crm.shared.exception.NoEncontradoException
import pe.quantum.crm.shared.exception.PermisoInsuficienteException
import pe.quantum.crm.shared.security.UsuarioActual
import java.math.BigDecimal
import java.time.LocalDateTime
import java.util.Optional

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
    private val tareaService = mockk<TareaService>()
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
            OportunidadVisibilidad(tareaService),
        )

    private val analista = UsuarioActual(id = 7L, rol = "analista")
    private val otro = UsuarioActual(id = 8L, rol = "otro")

    private fun oportunidadDe(
        id: Long,
        idVendedor: Long,
    ) = Oportunidad(
        id = id,
        idEmpresa = 10,
        idVendedor = idVendedor,
        idFinanciadora = 1,
        idModelo = 1,
        estado = EstadoOportunidad.evaluacion_calidda,
        cantidad = 1,
        precioUnitario = BigDecimal.TEN,
        dcto = BigDecimal.ZERO,
        montoTotal = BigDecimal.TEN,
        createdAt = LocalDateTime.now(),
        createdBy = idVendedor,
        updatedAt = LocalDateTime.now(),
        updatedBy = idVendedor,
    )

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

    @Test
    fun `un rol de apoyo no puede asegurar la carpeta de drive de una oportunidad`() {
        assertThatThrownBy { service.asegurarCarpetaDrive(1L, analista) }
            .isInstanceOf(PermisoInsuficienteException::class.java)
            .hasMessageContaining("apoyo")
    }

    @Test
    fun `un rol de apoyo sin colaboraciones no ve ninguna oportunidad`() {
        every { tareaService.idsOportunidadesDondeColabora(7L) } returns emptySet()
        every { oportunidadRepository.findAll(any<Specification<Oportunidad>>(), any<PageRequest>()) } returns
            PageImpl(emptyList())

        val resultado = service.listar(OportunidadFiltros(), analista, null, null, null, null)

        assertThat(resultado.items).isEmpty()
        verify { tareaService.idsOportunidadesDondeColabora(7L) }
    }

    @Test
    fun `un rol de apoyo no ve una oportunidad en la que no colabora`() {
        every { tareaService.idsOportunidadesDondeColabora(7L) } returns setOf(99L)
        every { oportunidadRepository.findById(1L) } returns Optional.of(oportunidadDe(id = 1L, idVendedor = 3L))

        assertThatThrownBy { service.detalle(1L, analista) }
            .isInstanceOf(NoEncontradoException::class.java)
    }

    @Test
    fun `un rol de apoyo si ve una oportunidad en la que colabora`() {
        every { tareaService.idsOportunidadesDondeColabora(7L) } returns setOf(1L)
        every { oportunidadRepository.findById(1L) } returns Optional.of(oportunidadDe(id = 1L, idVendedor = 3L))
        every { empresaService.resumenPorIds(any()) } returns emptyMap()
        every { empleadoService.resumenPorIds(any()) } returns emptyMap()
        every { financiadoraService.porIds(any()) } returns emptyMap()
        every { modeloService.resumenPorIds(any()) } returns emptyMap()
        every { consultas.tareasPendientesPorOportunidad(any()) } returns emptyMap()
        every { consultas.eventosPendientesPorOportunidad(any()) } returns emptyMap()
        every { contactoOportunidadRepository.findByIdIdOportunidad(1L) } returns emptyList()
        every { contactoService.resumenPorIds(any()) } returns emptyMap()
        every { logRepository.findFirstByIdOportunidadOrderByChangedAtDescIdDesc(1L) } returns null

        assertThatCode { service.detalle(1L, analista) }.doesNotThrowAnyException()
    }
}
