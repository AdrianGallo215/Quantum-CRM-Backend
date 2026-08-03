package pe.quantum.crm.mantenimiento

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.mockk.verifyOrder
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import pe.quantum.crm.domain.empresas.EmpresaService
import pe.quantum.crm.domain.oportunidades.OportunidadService
import pe.quantum.crm.integracion.drive.DriveException

class CarpetasDriveBackfillServiceTest {
    private val empresaService = mockk<EmpresaService>()
    private val oportunidadService = mockk<OportunidadService>()
    private val service = CarpetasDriveBackfillService(empresaService, oportunidadService)

    @Test
    fun `crea las carpetas de todas las empresas y oportunidades pendientes`() {
        every { empresaService.idsSinCarpetaDrive() } returns listOf(1L, 2L)
        every { oportunidadService.idsSinCarpetaDrive() } returns listOf(10L)
        every { empresaService.asegurarCarpetaDrive(any<Long>()) } returns "carpeta"
        every { oportunidadService.asegurarCarpetaDrive(any<Long>()) } returns "carpeta"

        val resultado = service.ejecutar(tamanoLote = null)

        assertThat(resultado.empresasProcesadas).isEqualTo(2)
        assertThat(resultado.oportunidadesProcesadas).isEqualTo(1)
        assertThat(resultado.errores).isEmpty()
        assertThat(resultado.pendientesRestantes).isZero()
    }

    @Test
    fun `procesa las empresas antes que las oportunidades`() {
        every { empresaService.idsSinCarpetaDrive() } returns listOf(1L)
        every { oportunidadService.idsSinCarpetaDrive() } returns listOf(10L)
        every { empresaService.asegurarCarpetaDrive(1L) } returns "carpeta-empresa"
        every { oportunidadService.asegurarCarpetaDrive(10L) } returns "carpeta-op"

        service.ejecutar(tamanoLote = null)

        verifyOrder {
            empresaService.asegurarCarpetaDrive(1L)
            oportunidadService.asegurarCarpetaDrive(10L)
        }
    }

    @Test
    fun `un fallo en un registro no detiene el resto del lote`() {
        every { empresaService.idsSinCarpetaDrive() } returns listOf(1L, 2L, 3L)
        every { oportunidadService.idsSinCarpetaDrive() } returns emptyList()
        every { empresaService.asegurarCarpetaDrive(1L) } returns "carpeta-1"
        every { empresaService.asegurarCarpetaDrive(2L) } throws DriveException("Drive caido")
        every { empresaService.asegurarCarpetaDrive(3L) } returns "carpeta-3"

        val resultado = service.ejecutar(tamanoLote = null)

        // La 3 se proceso pese al fallo de la 2.
        verify { empresaService.asegurarCarpetaDrive(3L) }
        assertThat(resultado.empresasProcesadas).isEqualTo(2)
        assertThat(resultado.errores).hasSize(1)
        assertThat(resultado.errores[0].entidad).isEqualTo("empresa")
        assertThat(resultado.errores[0].id).isEqualTo(2L)
        assertThat(resultado.errores[0].motivo).contains("Drive caido")
        // La que fallo sigue pendiente.
        assertThat(resultado.pendientesRestantes).isEqualTo(1)
    }

    @Test
    fun `tamano_lote limita el total procesado y reporta los pendientes`() {
        every { empresaService.idsSinCarpetaDrive() } returns listOf(1L, 2L, 3L)
        every { oportunidadService.idsSinCarpetaDrive() } returns listOf(10L, 11L)
        every { empresaService.asegurarCarpetaDrive(any<Long>()) } returns "carpeta"

        val resultado = service.ejecutar(tamanoLote = 2)

        assertThat(resultado.empresasProcesadas).isEqualTo(2)
        assertThat(resultado.oportunidadesProcesadas).isZero()
        // Quedan 1 empresa + 2 oportunidades.
        assertThat(resultado.pendientesRestantes).isEqualTo(3)
        verify(exactly = 0) { oportunidadService.asegurarCarpetaDrive(any<Long>()) }
    }

    @Test
    fun `tamano_lote negativo no lanza excepcion - procesa cero y reporta todo pendiente`() {
        every { empresaService.idsSinCarpetaDrive() } returns listOf(1L, 2L)
        every { oportunidadService.idsSinCarpetaDrive() } returns listOf(10L)

        val resultado = service.ejecutar(tamanoLote = -5)

        assertThat(resultado.empresasProcesadas).isZero()
        assertThat(resultado.oportunidadesProcesadas).isZero()
        assertThat(resultado.pendientesRestantes).isEqualTo(3)
        verify(exactly = 0) { empresaService.asegurarCarpetaDrive(any<Long>()) }
        verify(exactly = 0) { oportunidadService.asegurarCarpetaDrive(any<Long>()) }
    }

    @Test
    fun `sin pendientes no toca Drive y reporta cero`() {
        every { empresaService.idsSinCarpetaDrive() } returns emptyList()
        every { oportunidadService.idsSinCarpetaDrive() } returns emptyList()

        val resultado = service.ejecutar(tamanoLote = null)

        assertThat(resultado.empresasProcesadas).isZero()
        assertThat(resultado.oportunidadesProcesadas).isZero()
        assertThat(resultado.pendientesRestantes).isZero()
        verify(exactly = 0) { empresaService.asegurarCarpetaDrive(any<Long>()) }
        verify(exactly = 0) { oportunidadService.asegurarCarpetaDrive(any<Long>()) }
    }
}
