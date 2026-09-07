package pe.quantum.crm.domain.simulaciones

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatCode
import org.junit.jupiter.api.Test
import pe.quantum.crm.shared.enums.ModoSimulacion
import pe.quantum.crm.shared.simulacion.MotorSimulacion
import pe.quantum.crm.shared.simulacion.ParametrosSimulacion
import java.math.BigDecimal

/**
 * [CuotaEfimera] es una funcion pura (`reglas_simulaciones.md` §6.1): sin
 * mockk, sin Spring, los datos llegan por parametro.
 *
 * El contrato que verifican estos tests es el de la decision D55 de
 * `plan-13-mapa-cierre-simulaciones.md`: la cuota efimera devuelve `null`
 * cuando no se puede calcular y NUNCA lanza. Es un dato informativo de un
 * listado; jamas puede impedir leer la oportunidad.
 */
class CuotaEfimeraTest {
    @Test
    fun `caso feliz - usa los parametros por defecto de §6_1 y devuelve una cuota positiva`() {
        val precioVenta = BigDecimal("190000")

        // El esperado NO es un numero magico: se calcula corriendo el motor con
        // los mismos defaults de §6.1 (leasing por D54, 48 meses, TEA 14,
        // cuota inicial 45000, valor residual 25000). Si CuotaEfimera usara
        // otros parametros, este test lo detecta.
        val esperado =
            MotorSimulacion
                .calcular(
                    ParametrosSimulacion(
                        modo = ModoSimulacion.leasing,
                        precioVenta = precioVenta,
                        descuento = BigDecimal.ZERO,
                        cuotaInicial = BigDecimal("45000"),
                        plazoMeses = 48,
                        tea = BigDecimal("14"),
                        valorResidual = BigDecimal("25000"),
                    ),
                ).cuotaFinal

        val cuota = CuotaEfimera.calcular(precioVenta, null)

        assertThat(cuota).isNotNull()
        assertThat(cuota!!.signum()).isPositive()
        assertThat(cuota).isEqualByComparingTo(esperado)
    }

    @Test
    fun `precio de venta nulo - item incompleto, no hay cuota que mostrar`() {
        assertThat(CuotaEfimera.calcular(null, null)).isNull()
        assertThat(CuotaEfimera.calcular(null, BigDecimal("10"))).isNull()
    }

    @Test
    fun `cuota inicial igual al PV efectivo - §13 no se cumple, devuelve null`() {
        // PV_efectivo = 45000 x (1 - 0/100) = 45000, exactamente la cuota
        // inicial por defecto: §13 exige cuota_inicial < PV_efectivo (estricto).
        assertThat(CuotaEfimera.calcular(BigDecimal("45000"), null)).isNull()
    }

    @Test
    fun `el descuento hunde el PV efectivo por debajo de la cuota inicial - devuelve null`() {
        // PV_efectivo = 50000 x (1 - 20/100) = 40000 < 45000 → §13 no se cumple.
        assertThat(CuotaEfimera.calcular(BigDecimal("50000"), BigDecimal("20"))).isNull()
    }

    @Test
    fun `valor residual mayor que el Principal - caso K32, devuelve null SIN excepcion`() {
        // K32 de plan-13-mapa-cierre-simulaciones.md, el caso mas importante de
        // este test. En leasing el Principal se calcula sin IGV:
        //   Principal = 60000/1.18 - 45000/1.18 = 15000/1.18 = 12711.8644...
        // y el valor residual por defecto de §6.1 (25000) es MAYOR que ese
        // Principal, asi que §13 (`valor_residual < Principal`) no se cumple.
        //
        // Este es EXACTAMENTE el caso que, sin la guarda de CuotaEfimera,
        // dejaria pasar una CronogramaInconsistenteException (o una cuota
        // absurda) hasta el GlobalExceptionHandler y haria que
        // GET /oportunidades respondiera 500 en produccion por un solo item
        // barato. Aqui tiene que degradar a `null`, en silencio y sin lanzar.
        val precioVenta = BigDecimal("60000")

        assertThatCode { CuotaEfimera.calcular(precioVenta, null) }.doesNotThrowAnyException()
        assertThat(CuotaEfimera.calcular(precioVenta, null)).isNull()
    }

    @Test
    fun `descuento nulo se trata igual que descuento cero`() {
        val precioVenta = BigDecimal("190000")

        val conNulo = CuotaEfimera.calcular(precioVenta, null)
        val conCero = CuotaEfimera.calcular(precioVenta, BigDecimal.ZERO)

        assertThat(conNulo).isNotNull()
        assertThat(conNulo).isEqualByComparingTo(conCero!!)
    }

    @Test
    fun `ningun precio hace lanzar la cuota efimera - la guarda que protege GET oportunidades`() {
        val precios =
            listOf(
                BigDecimal("1"),
                BigDecimal("100"),
                BigDecimal("44999"),
                BigDecimal("45000"),
                BigDecimal("46000"),
                BigDecimal("60000"),
                BigDecimal("110000"),
                BigDecimal("1000000"),
            )
        val descuentos = listOf(null, BigDecimal.ZERO, BigDecimal("10"), BigDecimal("50"), BigDecimal("100"))

        for (precio in precios) {
            for (descuento in descuentos) {
                assertThatCode {
                    val cuota = CuotaEfimera.calcular(precio, descuento)
                    // Cada llamada completa: o devuelve un BigDecimal, o null.
                    if (cuota != null) {
                        assertThat(cuota).isInstanceOf(BigDecimal::class.java)
                    }
                }.`as`("precio=%s descuento=%s", precio, descuento)
                    .doesNotThrowAnyException()
            }
        }
    }
}
