package pe.quantum.crm.domain.oportunidades

import jakarta.validation.Validation
import jakarta.validation.Validator
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import pe.quantum.crm.domain.oportunidades.dto.ActualizarOportunidadItemRequest
import pe.quantum.crm.domain.oportunidades.dto.CrearOportunidadRequest
import java.math.BigDecimal

/**
 * Escala de los campos monetarios del POST de oportunidad y del PUT de item
 * (V42: `descuento NUMERIC(5,2)`, `precio_venta NUMERIC(12,2)` — los mismos
 * limites que las columnas planas de V10 que estos campos reemplazan).
 *
 * Por que importa: Java calcula `monto_total` con el valor recibido, pero la
 * columna redondea a 2 decimales. Con `dcto = 2.994` sobre 100 x 45000 se
 * persiste `monto_total = 4365270.00` y `dcto = 2.99`; recalcular desde lo
 * persistido da 4365450.00 — 180 USD que la formula ya no explica. Ademas el PUT
 * responde "2.994" (entidad en memoria) y el GET siguiente "2.99". Se rechaza en
 * el borde en vez de aceptar en silencio algo distinto de lo enviado.
 */
class OportunidadDtosEscalaTest {
    private companion object {
        private val factory = Validation.buildDefaultValidatorFactory()
        private val validator: Validator = factory.validator

        @JvmStatic
        @AfterAll
        fun cerrar() = factory.close()
    }

    private fun camposInvalidos(objeto: Any): List<String> = validator.validate(objeto).map { it.propertyPath.toString() }

    private fun crear(descuento: BigDecimal?) =
        CrearOportunidadRequest(idEmpresa = 1L, idModelo = 1L, cantidad = 100, descuento = descuento)

    @Test
    fun `crear - descuento con mas de 2 decimales se rechaza`() {
        assertThat(camposInvalidos(crear(BigDecimal("2.994")))).contains("descuento")
    }

    @Test
    fun `crear - descuento con hasta 2 decimales se acepta`() {
        assertThat(camposInvalidos(crear(BigDecimal("2.99")))).isEmpty()
        assertThat(camposInvalidos(crear(BigDecimal("3")))).isEmpty()
        assertThat(camposInvalidos(crear(BigDecimal("100.00")))).isEmpty()
        assertThat(camposInvalidos(crear(null))).isEmpty()
    }

    @Test
    fun `actualizar item - descuento con mas de 2 decimales se rechaza`() {
        assertThat(camposInvalidos(ActualizarOportunidadItemRequest(descuento = BigDecimal("2.994")))).contains("descuento")
        assertThat(camposInvalidos(ActualizarOportunidadItemRequest(descuento = BigDecimal("0.001")))).contains("descuento")
    }

    @Test
    fun `actualizar item - precio_venta con mas de 2 decimales se rechaza`() {
        assertThat(camposInvalidos(ActualizarOportunidadItemRequest(precioVenta = BigDecimal("45000.005"))))
            .contains("precioVenta")
    }

    @Test
    fun `actualizar item - precio_venta supera los 10 digitos enteros de NUMERIC(12,2)`() {
        // 11 digitos enteros: la columna no lo admite, la API tampoco debe.
        assertThat(camposInvalidos(ActualizarOportunidadItemRequest(precioVenta = BigDecimal("12345678901.00"))))
            .contains("precioVenta")
        // 10 digitos enteros: el maximo que cabe en NUMERIC(12,2).
        assertThat(camposInvalidos(ActualizarOportunidadItemRequest(precioVenta = BigDecimal("1234567890.99"))))
            .isEmpty()
    }

    @Test
    fun `actualizar item - los ceros a la derecha cuentan como decimales`() {
        // @Digits mira la escala del BigDecimal, no el valor: "2.9000" (escala 4)
        // se rechaza aunque valga lo mismo que 2.90. Es deliberado y esta anotado
        // aqui para que no sorprenda: JSON.stringify emite 2.9, no 2.9000, y
        // aceptar escalas arbitrarias es lo que abrio el descuadre de monto_total.
        assertThat(camposInvalidos(ActualizarOportunidadItemRequest(descuento = BigDecimal("2.9000")))).contains("descuento")
    }

    @Test
    fun `actualizar item - valores con escala valida no se rechazan`() {
        assertThat(camposInvalidos(ActualizarOportunidadItemRequest(descuento = BigDecimal("2.99"), precioVenta = BigDecimal("45000.00"))))
            .isEmpty()
    }
}
