package pe.quantum.crm.domain.reportes

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate

/**
 * Aritmetica de dinero de los reportes (§18): las tres funciones puras sobre las
 * que se apoyan todos los totales, promedios y tasas. El SQL solo se puede
 * verificar contra Postgres (ver los tests @Tag("integration") de este paquete),
 * pero el redondeo si se verifica aqui: es donde nacen los descuadres de
 * centavos que gerencia lee como cifras.
 */
class ReporteAritmeticaTest {
    @Test
    fun `sumMonto - suma con escala 2 y HALF_UP`() {
        val montos = listOf(BigDecimal("100.10"), BigDecimal("200.25"), BigDecimal("0.65"))
        assertThat(montos.sumMonto { it }).isEqualByComparingTo(BigDecimal("301.00"))
        assertThat(montos.sumMonto { it }.scale()).isEqualTo(2)
    }

    @Test
    fun `sumMonto - lista vacia suma cero con escala 2`() {
        val vacia = emptyList<BigDecimal>()
        assertThat(vacia.sumMonto { it }.toPlainString()).isEqualTo("0.00")
    }

    @Test
    fun `sumMonto - redondea hacia arriba en el medio exacto`() {
        // 0.005 -> 0.01 (HALF_UP, no HALF_EVEN).
        assertThat(listOf(BigDecimal("0.005")).sumMonto { it }.toPlainString()).isEqualTo("0.01")
        assertThat(listOf(BigDecimal("0.015")).sumMonto { it }.toPlainString()).isEqualTo("0.02")
    }

    @Test
    fun `sumMonto - redondea una sola vez, sobre el total`() {
        // Tres veces 0.004: redondear cada sumando daria 0.00; el total 0.012 -> 0.01.
        val montos = listOf(BigDecimal("0.004"), BigDecimal("0.004"), BigDecimal("0.004"))
        assertThat(montos.sumMonto { it }.toPlainString()).isEqualTo("0.01")
    }

    @Test
    fun `promedio - lista vacia devuelve null, no cero`() {
        // null y 0 no significan lo mismo en un reporte: "sin operaciones" no es
        // "descuento promedio 0".
        assertThat(promedio(emptyList())).isNull()
    }

    @Test
    fun `promedio - divide con escala 2 y HALF_UP`() {
        val diezEntreTres = promedio(listOf(BigDecimal("3"), BigDecimal("3"), BigDecimal("4")))
        assertThat(diezEntreTres?.toPlainString()).isEqualTo("3.33")
        val veinteEntreTres = promedio(listOf(BigDecimal("7"), BigDecimal("7"), BigDecimal("6")))
        assertThat(veinteEntreTres?.toPlainString()).isEqualTo("6.67")
    }

    @Test
    fun `promedio - un solo valor conserva el valor con escala 2`() {
        assertThat(promedio(listOf(BigDecimal("2.5")))?.toPlainString()).isEqualTo("2.50")
    }

    @Test
    fun `porcentaje - total cero no divide por cero`() {
        assertThat(porcentaje(0, 0)).isEqualTo("0.00")
        assertThat(porcentaje(5, 0)).isEqualTo("0.00")
    }

    @Test
    fun `porcentaje - escala 2 y HALF_UP`() {
        assertThat(porcentaje(1, 3)).isEqualTo("33.33")
        assertThat(porcentaje(2, 3)).isEqualTo("66.67")
        assertThat(porcentaje(1, 8)).isEqualTo("12.50")
        assertThat(porcentaje(3, 3)).isEqualTo("100.00")
        assertThat(porcentaje(0, 7)).isEqualTo("0.00")
    }

    @Test
    fun `PeriodoReporte - hasta es inclusivo para el usuario y exclusivo hacia el SQL`() {
        val periodo = PeriodoReporte.de(LocalDate.of(2026, 3, 1), LocalDate.of(2026, 3, 31))
        assertThat(periodo.desde).isEqualTo(LocalDate.of(2026, 3, 1))
        assertThat(periodo.hastaExclusivo).isEqualTo(LocalDate.of(2026, 4, 1))
    }

    @Test
    fun `PeriodoReporte - sin fechas toma el mes calendario actual completo`() {
        val hoy = LocalDate.now()
        val periodo = PeriodoReporte.de(null, null)
        assertThat(periodo.desde).isEqualTo(hoy.withDayOfMonth(1))
        assertThat(periodo.hastaExclusivo).isEqualTo(hoy.withDayOfMonth(1).plusMonths(1))
    }

    /**
     * `ROW_NUMBER()` renumeraba: quitar un hito del catalogo movia a los siguientes y
     * cambiaba el significado de `hito_2_completado` en los informes ya emitidos.
     * Anclar la posicion al id del catalogo hace que quitar el hito 2 deje ese hueco
     * vacio en vez de rellenarlo con el 3.
     */
    @Test
    fun `las posiciones de hito se anclan a los tres primeros ids del catalogo`() {
        val posiciones = posicionesDeHito(listOf(10L, 20L, 30L, 40L))

        assertThat(posiciones).isEqualTo(mapOf(10L to 1, 20L to 2, 30L to 3))
    }

    /** Un cuarto hito existe en el catalogo pero el DTO solo tiene tres huecos: se ignora, no desplaza. */
    @Test
    fun `un cuarto hito no desplaza a los tres primeros`() {
        val posiciones = posicionesDeHito(listOf(10L, 20L, 30L, 40L))

        assertThat(posiciones).doesNotContainKey(40L)
    }

    @Test
    fun `con menos de tres hitos las posiciones sobrantes simplemente no existen`() {
        val posiciones = posicionesDeHito(listOf(10L, 20L))

        assertThat(posiciones).isEqualTo(mapOf(10L to 1, 20L to 2))
    }
}
