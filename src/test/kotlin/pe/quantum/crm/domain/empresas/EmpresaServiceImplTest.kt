package pe.quantum.crm.domain.empresas

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import pe.quantum.crm.domain.empleados.EmpleadoService
import pe.quantum.crm.shared.enums.EstadoCartera
import java.time.LocalDateTime
import java.util.Optional

class EmpresaServiceImplTest {
    private val empresaRepository = mockk<EmpresaRepository>()
    private val empleadoService = mockk<EmpleadoService>()
    private val service = EmpresaServiceImpl(empresaRepository, empleadoService)

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
}
