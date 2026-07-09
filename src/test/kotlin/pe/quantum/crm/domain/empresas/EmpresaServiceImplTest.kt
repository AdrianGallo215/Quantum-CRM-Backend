package pe.quantum.crm.domain.empresas

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import pe.quantum.crm.domain.empleados.EmpleadoService
import pe.quantum.crm.domain.empleados.dto.EmpleadoResumen
import pe.quantum.crm.domain.notificaciones.EntidadNotificacion
import pe.quantum.crm.domain.notificaciones.NotificacionService
import pe.quantum.crm.domain.notificaciones.TipoNotificacion
import pe.quantum.crm.shared.enums.EstadoCartera
import pe.quantum.crm.shared.security.UsuarioActual
import java.time.LocalDateTime
import java.util.Optional

class EmpresaServiceImplTest {
    private val empresaRepository = mockk<EmpresaRepository>()
    private val empleadoService = mockk<EmpleadoService>()
    private val notificacionService = mockk<NotificacionService>(relaxed = true)
    private val service = EmpresaServiceImpl(empresaRepository, empleadoService, notificacionService)

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
        every { empleadoService.existeActivo(2) } returns true
        every { empresaRepository.save(entidad) } returns entidad
        every { empleadoService.resumenPorIds(listOf(9)) } returns mapOf(9L to EmpleadoResumen(id = 9, nombres = "Ana", apellidos = "Diaz"))

        service.reasignarVendedor(1, 2, UsuarioActual(id = 9, rol = "jdv"))

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
}
