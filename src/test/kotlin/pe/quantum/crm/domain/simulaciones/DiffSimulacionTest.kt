package pe.quantum.crm.domain.simulaciones

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import pe.quantum.crm.shared.enums.ModoSimulacion
import pe.quantum.crm.shared.enums.TipoEventoSimulacion
import java.math.BigDecimal

/**
 * [DiffSimulacion] es una funcion pura (`reglas_simulaciones.md` §7.1, plan-11
 * decision D44): sin mockk, sin Spring, los snapshots llegan por parametro.
 */
class DiffSimulacionTest {
    @Suppress("LongParameterList") // Builder de fixture: refleja los 10 campos de snapshot de SimulacionLog.
    private fun log(
        idSimulacion: Long = 1L,
        tipoEvento: TipoEventoSimulacion = TipoEventoSimulacion.editada,
        modo: ModoSimulacion? = ModoSimulacion.leasing,
        precioVenta: BigDecimal? = BigDecimal("110000.00"),
        descuento: BigDecimal? = BigDecimal("0.00"),
        cuotaInicial: BigDecimal? = BigDecimal("56000.00"),
        plazoMeses: Int? = 48,
        tea: BigDecimal? = BigDecimal("18.00"),
        valorResidual: BigDecimal? = BigDecimal("0.00"),
        diasTrabajados: Int? = 22,
        comisionEstructuracion: BigDecimal? = BigDecimal("1180.00"),
        cuotaFinal: BigDecimal? = BigDecimal("1548.86"),
    ): SimulacionLog =
        SimulacionLog(
            idSimulacion = idSimulacion,
            tipoEvento = tipoEvento,
            modo = modo,
            precioVenta = precioVenta,
            descuento = descuento,
            cuotaInicial = cuotaInicial,
            plazoMeses = plazoMeses,
            tea = tea,
            valorResidual = valorResidual,
            diasTrabajados = diasTrabajados,
            comisionEstructuracion = comisionEstructuracion,
            cuotaFinal = cuotaFinal,
        )

    @Test
    fun `anterior null produce diff vacio - primer evento de todos (K23)`() {
        val diff = DiffSimulacion.calcular(anterior = null, actual = log())

        assertThat(diff).isEmpty()
    }

    @Test
    fun `dos snapshots identicos producen diff vacio`() {
        val diff = DiffSimulacion.calcular(anterior = log(), actual = log())

        assertThat(diff).isEmpty()
    }

    @Test
    fun `solo tea cambia - exactamente un CampoDiffDto`() {
        val anterior = log(tea = BigDecimal("18.00"))
        val actual = log(tea = BigDecimal("20.00"))

        val diff = DiffSimulacion.calcular(anterior, actual)

        assertThat(diff).hasSize(1)
        assertThat(diff[0].campo).isEqualTo("tea")
        assertThat(diff[0].valorAnterior).isEqualTo("18.00")
        assertThat(diff[0].valorNuevo).isEqualTo("20.00")
    }

    @Test
    fun `cambian 3 campos - salen exactamente esos 3, en el orden declarado`() {
        val anterior =
            log(
                precioVenta = BigDecimal("110000.00"),
                plazoMeses = 48,
                cuotaFinal = BigDecimal("1548.86"),
            )
        val actual =
            log(
                precioVenta = BigDecimal("120000.00"),
                plazoMeses = 36,
                cuotaFinal = BigDecimal("1600.00"),
            )

        val diff = DiffSimulacion.calcular(anterior, actual)

        assertThat(diff.map { it.campo }).containsExactly("precioVenta", "plazoMeses", "cuotaFinal")
    }

    @Test
    fun `BigDecimal 100_00 vs 100_0 en el mismo campo - NO aparece (mismo valor, distinta escala)`() {
        val anterior = log(precioVenta = BigDecimal("100.00"))
        val actual = log(precioVenta = BigDecimal("100.0"))

        val diff = DiffSimulacion.calcular(anterior, actual)

        assertThat(diff).isEmpty()
    }

    @Test
    fun `modo cambia de leasing a credito_directo - nombres crudos del enum, no traduccion`() {
        val anterior = log(modo = ModoSimulacion.leasing)
        val actual = log(modo = ModoSimulacion.credito_directo)

        val diff = DiffSimulacion.calcular(anterior, actual)

        assertThat(diff).hasSize(1)
        assertThat(diff[0].campo).isEqualTo("modo")
        assertThat(diff[0].valorAnterior).isEqualTo("leasing")
        assertThat(diff[0].valorNuevo).isEqualTo("credito_directo")
    }

    @Test
    fun `un campo pasa de valor a null en el snapshot actual - aparece con valorNuevo null`() {
        val anterior = log(valorResidual = BigDecimal("25000.00"))
        val actual = log(valorResidual = null)

        val diff = DiffSimulacion.calcular(anterior, actual)

        assertThat(diff).hasSize(1)
        assertThat(diff[0].campo).isEqualTo("valorResidual")
        assertThat(diff[0].valorAnterior).isEqualTo("25000.00")
        assertThat(diff[0].valorNuevo).isNull()
    }

    @Test
    fun `un campo pasa de null a valor - aparece con valorAnterior null`() {
        val anterior = log(diasTrabajados = null)
        val actual = log(diasTrabajados = 22)

        val diff = DiffSimulacion.calcular(anterior, actual)

        assertThat(diff).hasSize(1)
        assertThat(diff[0].campo).isEqualTo("diasTrabajados")
        assertThat(diff[0].valorAnterior).isNull()
        assertThat(diff[0].valorNuevo).isEqualTo("22")
    }
}
