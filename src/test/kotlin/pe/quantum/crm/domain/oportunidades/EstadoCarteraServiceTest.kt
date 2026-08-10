package pe.quantum.crm.domain.oportunidades

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import pe.quantum.crm.domain.empresas.EmpresaService
import pe.quantum.crm.domain.empresas.dto.CambioEstadoCartera
import pe.quantum.crm.shared.enums.EstadoCartera
import pe.quantum.crm.shared.enums.EstadoOportunidad

/**
 * `EstadoCarteraService.actualizar` es la UNICA via por la que el sistema
 * modifica `estado_cartera` (CLAUDE.md regla 3, reglas_negocio.md §3.2). No
 * tenia tests: si alguien invierte la prioridad facturado > activa, o rompe la
 * delegacion en `EmpresaService.aplicarEstadoDerivado` (que trae la guarda de
 * entrada real), nada lo detecta. Ver docs/plan-ejecucion-subagentes.md, C.4.
 */
class EstadoCarteraServiceTest {
    private val oportunidadRepository = mockk<OportunidadRepository>()
    private val empresaService = mockk<EmpresaService>()
    private val service = EstadoCarteraService(oportunidadRepository, empresaService)

    @Test
    fun `con una oportunidad facturada el estado derivado es cliente`() {
        every { oportunidadRepository.existsByIdEmpresaAndEstado(10, EstadoOportunidad.facturado) } returns true
        every {
            empresaService.aplicarEstadoDerivado(10, EstadoCartera.cliente)
        } returns CambioEstadoCartera(anterior = EstadoCartera.oportunidad_activa, nuevo = EstadoCartera.cliente)

        val resultado = service.actualizar(10)

        assertThat(resultado?.nuevo).isEqualTo(EstadoCartera.cliente)
        verify(exactly = 0) { oportunidadRepository.existsByIdEmpresaAndEstadoIn(any(), any()) }
    }

    @Test
    fun `con una oportunidad activa y ninguna facturada el derivado es oportunidad_activa`() {
        every { oportunidadRepository.existsByIdEmpresaAndEstado(10, EstadoOportunidad.facturado) } returns false
        every {
            oportunidadRepository.existsByIdEmpresaAndEstadoIn(10, EstadoCarteraService.ESTADOS_ACTIVOS)
        } returns true
        every {
            empresaService.aplicarEstadoDerivado(10, EstadoCartera.oportunidad_activa)
        } returns CambioEstadoCartera(anterior = EstadoCartera.prospeccion, nuevo = EstadoCartera.oportunidad_activa)

        val resultado = service.actualizar(10)

        assertThat(resultado?.nuevo).isEqualTo(EstadoCartera.oportunidad_activa)
    }

    @Test
    fun `sin oportunidades activas ni facturadas el derivado es null`() {
        every { oportunidadRepository.existsByIdEmpresaAndEstado(10, EstadoOportunidad.facturado) } returns false
        every {
            oportunidadRepository.existsByIdEmpresaAndEstadoIn(10, EstadoCarteraService.ESTADOS_ACTIVOS)
        } returns false
        every { empresaService.aplicarEstadoDerivado(10, null) } returns null

        val resultado = service.actualizar(10)

        assertThat(resultado).isNull()
        verify { empresaService.aplicarEstadoDerivado(10, null) }
    }

    @Test
    fun `facturado gana sobre activa`() {
        // Ambas existen: una oportunidad facturada Y otra activa. La prioridad
        // debe quedarse en `cliente`, no degradar a `oportunidad_activa`.
        every { oportunidadRepository.existsByIdEmpresaAndEstado(10, EstadoOportunidad.facturado) } returns true
        every {
            empresaService.aplicarEstadoDerivado(10, EstadoCartera.cliente)
        } returns CambioEstadoCartera(anterior = EstadoCartera.oportunidad_activa, nuevo = EstadoCartera.cliente)

        val resultado = service.actualizar(10)

        assertThat(resultado?.nuevo).isEqualTo(EstadoCartera.cliente)
        // Al ganar la rama `facturado`, ni siquiera se consulta la de activas:
        // es un `when` de prioridad, no una acumulacion de condiciones.
        verify(exactly = 0) { oportunidadRepository.existsByIdEmpresaAndEstadoIn(any(), any()) }
    }

    @Test
    fun `el resultado es el que devuelve aplicarEstadoDerivado`() {
        // `actualizar` no decide el CambioEstadoCartera: solo calcula el
        // `derivado` y delega la guarda de entrada (no escribir sin cambio,
        // respetar estados manuales) en EmpresaService.aplicarEstadoDerivado.
        every { oportunidadRepository.existsByIdEmpresaAndEstado(10, EstadoOportunidad.facturado) } returns false
        every {
            oportunidadRepository.existsByIdEmpresaAndEstadoIn(10, EstadoCarteraService.ESTADOS_ACTIVOS)
        } returns true
        val esperado = CambioEstadoCartera(anterior = EstadoCartera.prospeccion, nuevo = EstadoCartera.oportunidad_activa)
        every { empresaService.aplicarEstadoDerivado(10, EstadoCartera.oportunidad_activa) } returns esperado

        val resultado = service.actualizar(10)

        assertThat(resultado).isSameAs(esperado)
    }
}
