package pe.quantum.crm.domain.oportunidades

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class MontoTotalSumarItemsTest {
    private fun item(
        cantidad: Int? = null,
        precioVenta: BigDecimal? = null,
        descuento: BigDecimal? = null,
    ): OportunidadItem =
        OportunidadItem(
            idOportunidad = 1L,
            idModelo = 1L,
            cantidad = cantidad,
            precioVenta = precioVenta,
            descuento = descuento,
            createdBy = 1L,
            updatedBy = 1L,
        )

    @Test
    fun `lista vacia retorna null`() {
        assertNull(MontoTotal.sumarItems(emptyList()))
    }

    @Test
    fun `un item completo retorna igual a calcular de ese item`() {
        val i = item(cantidad = 2, precioVenta = BigDecimal("1000.00"), descuento = BigDecimal("10"))
        val esperado = MontoTotal.calcular(i.cantidad, i.precioVenta, i.descuento)

        assertEquals(esperado, MontoTotal.sumarItems(listOf(i)))
    }

    @Test
    fun `dos items completos suma los dos subtotales`() {
        val i1 = item(cantidad = 2, precioVenta = BigDecimal("1000.00"), descuento = BigDecimal("10"))
        val i2 = item(cantidad = 1, precioVenta = BigDecimal("500.00"), descuento = null)
        val esperado =
            MontoTotal.calcular(i1.cantidad, i1.precioVenta, i1.descuento)!!
                .add(MontoTotal.calcular(i2.cantidad, i2.precioVenta, i2.descuento)!!)

        assertEquals(esperado, MontoTotal.sumarItems(listOf(i1, i2)))
    }

    @Test
    fun `item incompleto cuenta como cero y no anula el total`() {
        val completo = item(cantidad = 2, precioVenta = BigDecimal("1000.00"), descuento = BigDecimal("10"))
        val incompleto = item(cantidad = null, precioVenta = BigDecimal("500.00"), descuento = null)
        val esperado = MontoTotal.calcular(completo.cantidad, completo.precioVenta, completo.descuento)

        assertEquals(esperado, MontoTotal.sumarItems(listOf(completo, incompleto)))
    }

    @Test
    fun `todos los items incompletos retorna null`() {
        val i1 = item(cantidad = null, precioVenta = BigDecimal("1000.00"))
        val i2 = item(cantidad = 3, precioVenta = null)

        assertNull(MontoTotal.sumarItems(listOf(i1, i2)))
    }

    @Test
    fun `descuento null se propaga como cero`() {
        val i = item(cantidad = 3, precioVenta = BigDecimal("100.00"), descuento = null)
        val esperado = MontoTotal.calcular(i.cantidad, i.precioVenta, BigDecimal.ZERO)

        assertEquals(esperado, MontoTotal.sumarItems(listOf(i)))
    }
}
