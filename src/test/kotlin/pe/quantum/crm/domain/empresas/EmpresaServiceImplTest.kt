package pe.quantum.crm.domain.empresas

import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.context.ApplicationEventPublisher
import pe.quantum.crm.domain.empleados.EmpleadoService
import pe.quantum.crm.domain.empleados.dto.EmpleadoResumen
import pe.quantum.crm.domain.notificaciones.EntidadNotificacion
import pe.quantum.crm.domain.notificaciones.NotificacionService
import pe.quantum.crm.domain.notificaciones.TipoNotificacion
import pe.quantum.crm.shared.enums.EstadoCartera
import pe.quantum.crm.shared.exception.PermisoInsuficienteException
import pe.quantum.crm.shared.exception.ValidacionException
import pe.quantum.crm.shared.security.UsuarioActual
import java.time.LocalDateTime
import java.util.Optional

class EmpresaServiceImplTest {
    private val empresaRepository = mockk<EmpresaRepository>()
    private val empleadoService = mockk<EmpleadoService>()
    private val notificacionService = mockk<NotificacionService>(relaxed = true)
    private val eventPublisher = mockk<ApplicationEventPublisher>(relaxed = true)
    private val service = EmpresaServiceImpl(empresaRepository, empleadoService, notificacionService, eventPublisher)

    private fun empresa(estadoCartera: EstadoCartera = EstadoCartera.prospeccion) =
        Empresa(
            id = 1,
            ruc = "20123456789",
            razonSocial = "Transportes ABC",
            estadoCartera = estadoCartera,
            createdAt = LocalDateTime.now(),
            createdBy = 1,
            updatedAt = LocalDateTime.now(),
            updatedBy = 1,
        )

    @Test
    fun `aplicarEstadoDerivado devuelve el cambio cuando prospeccion pasa a oportunidad_activa`() {
        val entidad = empresa(estadoCartera = EstadoCartera.prospeccion)
        every { empresaRepository.findById(1) } returns Optional.of(entidad)
        every { empresaRepository.save(entidad) } returns entidad

        val resultado = service.aplicarEstadoDerivado(1, EstadoCartera.oportunidad_activa)

        assertThat(resultado?.anterior).isEqualTo(EstadoCartera.prospeccion)
        assertThat(resultado?.nuevo).isEqualTo(EstadoCartera.oportunidad_activa)
        assertThat(entidad.estadoCartera).isEqualTo(EstadoCartera.oportunidad_activa)
    }

    @Test
    fun `aplicarEstadoDerivado devuelve null cuando no hay cambio real`() {
        val entidad = empresa(estadoCartera = EstadoCartera.oportunidad_activa)
        every { empresaRepository.findById(1) } returns Optional.of(entidad)

        val resultado = service.aplicarEstadoDerivado(1, EstadoCartera.oportunidad_activa)

        assertThat(resultado).isNull()
        verify(exactly = 0) { empresaRepository.save(any()) }
    }

    @Test
    fun `reasignarVendedor notifica al vendedor destino con el nombre del actor y de la empresa`() {
        val entidad = empresa()
        every { empresaRepository.findById(1) } returns Optional.of(entidad)
        every { empleadoService.esAsignableComoVendedor(2) } returns true
        every { empresaRepository.save(entidad) } returns entidad
        every { empleadoService.resumenPorIds(listOf(9)) } returns mapOf(9L to EmpleadoResumen(id = 9, nombres = "Ana", apellidos = "Diaz"))

        service.reasignarVendedor(1, 2, UsuarioActual(id = 9, rol = "gerencia"))

        verify {
            notificacionService.notificar(
                destinatarios = setOf(2L),
                idActor = 9L,
                tipo = TipoNotificacion.empresa_asignada,
                mensaje = "Ana Diaz te asignó la empresa Transportes ABC",
                entidadTipo = EntidadNotificacion.empresa,
                entidadId = 1L,
            )
        }
    }

    @Test
    fun `reasignarVendedor publica VendedorEmpresaReasignadoEvent con los datos del cambio`() {
        val entidad = empresa()
        every { empresaRepository.findById(1) } returns Optional.of(entidad)
        every { empleadoService.esAsignableComoVendedor(2) } returns true
        every { empresaRepository.save(entidad) } returns entidad
        every { empleadoService.resumenPorIds(listOf(9)) } returns
            mapOf(9L to EmpleadoResumen(id = 9, nombres = "Ana", apellidos = "Diaz"))
        val evento = slot<VendedorEmpresaReasignadoEvent>()
        every { eventPublisher.publishEvent(capture(evento)) } just Runs

        service.reasignarVendedor(1, 2, UsuarioActual(id = 9, rol = "gerencia"))

        assertThat(evento.captured).isEqualTo(VendedorEmpresaReasignadoEvent(idEmpresa = 1, idVendedorNuevo = 2, idActor = 9))
    }

    @Test
    fun `reasignarVendedor por jdv lanza PERMISO_INSUFICIENTE - debe usar solicitud`() {
        val jdv = UsuarioActual(id = 2, rol = "jdv")
        assertThatThrownBy { service.reasignarVendedor(10, 8, jdv) }
            .isInstanceOf(PermisoInsuficienteException::class.java)
    }

    @Test
    fun `reasignarVendedor rechaza destino que no es vendedor ni jdv`() {
        val gerencia = UsuarioActual(id = 1, rol = "gerencia")
        every { empresaRepository.findById(10) } returns Optional.of(empresa())
        every { empleadoService.esAsignableComoVendedor(99) } returns false
        assertThatThrownBy { service.reasignarVendedor(10, 99, gerencia) }
            .isInstanceOf(ValidacionException::class.java)
    }

    @Test
    fun `detalle de empresa en cartera maestra para jdv es 404 - IDOR`() {
        val jdv = UsuarioActual(id = 2, rol = "jdv")
        every { empresaRepository.findById(10) } returns Optional.of(empresa().apply { enCarteraMaestra = true })
        assertThatThrownBy { service.detalle(10, jdv) }
            .isInstanceOf(pe.quantum.crm.shared.exception.NoEncontradoException::class.java)
    }

    @Test
    fun `detalle de empresa en cartera maestra para gerencia responde normal`() {
        val gerencia = UsuarioActual(id = 1, rol = "gerencia")
        every { empresaRepository.findById(10) } returns Optional.of(empresa().apply { enCarteraMaestra = true })
        every { empleadoService.resumenPorIds(any()) } returns emptyMap()
        assertThat(service.detalle(10, gerencia).enCarteraMaestra).isTrue()
    }

    @Test
    fun `segmentosPorIds devuelve los segmentos de cada empresa como String`() {
        val entidad = empresa().apply { segmentos = mutableSetOf(pe.quantum.crm.shared.enums.Segmento.interprovincial) }
        every { empresaRepository.findAllById(setOf(1L)) } returns listOf(entidad)

        val resultado = service.segmentosPorIds(listOf(1L))

        assertThat(resultado[1L]).containsExactly("interprovincial")
    }
}
