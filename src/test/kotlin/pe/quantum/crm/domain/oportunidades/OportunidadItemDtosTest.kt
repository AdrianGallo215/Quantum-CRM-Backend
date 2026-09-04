package pe.quantum.crm.domain.oportunidades

import jakarta.validation.Validation
import jakarta.validation.Validator
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import pe.quantum.crm.domain.oportunidades.dto.CrearOportunidadItemRequest
import java.math.BigDecimal

/**
 * Validacion de `CrearOportunidadItemRequest` (V42: `oportunidad_items`, con los
 * mismos CHECK que la oportunidad — `descuento NUMERIC(5,2)`,
 * `precio_venta`/`cuota_financiadora` NUMERIC(12,2)). Ver `OportunidadDtosEscalaTest`
 * para el razonamiento completo de por que `@Digits` importa.
 */
class OportunidadItemDtosTest {
    private companion object {
        private val factory = Validation.buildDefaultValidatorFactory()
        private val validator: Validator = factory.validator

        @JvmStatic
        @AfterAll
        fun cerrar() = factory.close()
    }

    private fun camposInvalidos(objeto: Any): List<String> = validator.validate(objeto).map { it.propertyPath.toString() }

    private fun crear(
        idModelo: Long = 1L,
        cantidad: Int? = 10,
        precioVenta: BigDecimal? = BigDecimal("45000.00"),
        descuento: BigDecimal? = BigDecimal("5.00"),
        cuotaFinanciadora: BigDecimal? = BigDecimal("1000.00"),
    ) = CrearOportunidadItemRequest(
        idModelo = idModelo,
        cantidad = cantidad,
        precioVenta = precioVenta,
        descuento = descuento,
        cuotaFinanciadora = cuotaFinanciadora,
    )

    @Test
    fun `descuento mayor a 100 viola DecimalMax`() {
        assertThat(camposInvalidos(crear(descuento = BigDecimal("100.5")))).contains("descuento")
    }

    @Test
    fun `descuento negativo viola DecimalMin`() {
        assertThat(camposInvalidos(crear(descuento = BigDecimal("-1")))).contains("descuento")
    }

    @Test
    fun `descuento con mas de 2 decimales viola Digits`() {
        assertThat(camposInvalidos(crear(descuento = BigDecimal("2.994")))).contains("descuento")
    }

    @Test
    fun `cantidad cero viola Positive`() {
        assertThat(camposInvalidos(crear(cantidad = 0))).contains("cantidad")
    }

    @Test
    fun `cantidad negativa viola Positive`() {
        assertThat(camposInvalidos(crear(cantidad = -1))).contains("cantidad")
    }

    @Test
    fun `precioVenta con 11 digitos enteros viola Digits`() {
        assertThat(camposInvalidos(crear(precioVenta = BigDecimal("100000000000")))).contains("precioVenta")
    }

    @Test
    fun `idModelo cero viola Positive`() {
        assertThat(camposInvalidos(crear(idModelo = 0L))).contains("idModelo")
    }

    @Test
    fun `idModelo negativo viola Positive`() {
        assertThat(camposInvalidos(crear(idModelo = -1L))).contains("idModelo")
    }

    @Test
    fun `request completamente valido no tiene violaciones`() {
        assertThat(camposInvalidos(crear())).isEmpty()
    }
}
