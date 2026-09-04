package pe.quantum.crm.domain.tipocambio

import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatCode
import org.junit.jupiter.api.Test
import pe.quantum.crm.integracion.sunat.SunatTipoCambioClient
import pe.quantum.crm.integracion.sunat.TipoCambioExterno
import java.math.BigDecimal
import java.time.LocalDate

class TipoCambioServiceImplTest {
    private val tipoCambioRepository = mockk<TipoCambioRepository>()
    private val sunatTipoCambioClient = mockk<SunatTipoCambioClient>()
    private val service = TipoCambioServiceImpl(tipoCambioRepository, sunatTipoCambioClient)

    private fun fila(
        fecha: LocalDate,
        compra: String = "3.700",
        venta: String = "3.750",
    ) = TipoCambio(fecha = fecha, compra = BigDecimal(compra), venta = BigDecimal(venta))

    @Test
    fun `vigente devuelve null cuando no hay ningun tipo de cambio guardado`() {
        every { tipoCambioRepository.findFirstByOrderByFechaDesc() } returns null

        assertThat(service.vigente()).isNull()
    }

    @Test
    fun `vigente mapea a DTO la fila de fecha mayor que devuelve el repositorio`() {
        val masReciente = fila(LocalDate.of(2026, 9, 1), compra = "3.710", venta = "3.760")
        every { tipoCambioRepository.findFirstByOrderByFechaDesc() } returns masReciente

        val dto = service.vigente()

        assertThat(dto).isNotNull
        assertThat(dto!!.fecha).isEqualTo(LocalDate.of(2026, 9, 1))
        assertThat(dto.compra).isEqualByComparingTo(BigDecimal("3.710"))
        assertThat(dto.venta).isEqualByComparingTo(BigDecimal("3.760"))
    }

    @Test
    fun `actualizarDesdeSunat devuelve false y no guarda ni lanza cuando SUNAT no responde`() {
        every { sunatTipoCambioClient.consultar() } returns null

        var resultado = true
        assertThatCode { resultado = service.actualizarDesdeSunat() }.doesNotThrowAnyException()

        assertThat(resultado).isFalse()
        verify(exactly = 0) { tipoCambioRepository.save(any()) }
    }

    @Test
    fun `actualizarDesdeSunat guarda el dato publicado y devuelve true`() {
        every { sunatTipoCambioClient.consultar() } returns
            TipoCambioExterno(LocalDate.of(2026, 9, 1), BigDecimal("3.700"), BigDecimal("3.750"))
        val guardada = slot<TipoCambio>()
        every { tipoCambioRepository.save(capture(guardada)) } answers { firstArg() }

        assertThat(service.actualizarDesdeSunat()).isTrue()

        assertThat(guardada.captured.fecha).isEqualTo(LocalDate.of(2026, 9, 1))
        assertThat(guardada.captured.compra).isEqualByComparingTo(BigDecimal("3.700"))
        assertThat(guardada.captured.venta).isEqualByComparingTo(BigDecimal("3.750"))
        assertThat(guardada.captured.fuente).isEqualTo("sunat")
    }

    @Test
    fun `actualizarDesdeSunat dos veces con la misma fecha reusa la PK natural y no duplica`() {
        every { sunatTipoCambioClient.consultar() } returns
            TipoCambioExterno(LocalDate.of(2026, 9, 1), BigDecimal("3.700"), BigDecimal("3.750"))
        val guardadas = mutableListOf<TipoCambio>()
        every { tipoCambioRepository.save(capture(guardadas)) } answers { firstArg() }

        service.actualizarDesdeSunat()
        service.actualizarDesdeSunat()

        assertThat(guardadas).hasSize(2)
        assertThat(guardadas.map { it.fecha }.distinct()).containsExactly(LocalDate.of(2026, 9, 1))
    }
}
