package pe.quantum.crm.domain.oportunidades

import jakarta.validation.Validation
import jakarta.validation.Validator
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import pe.quantum.crm.domain.oportunidades.dto.ActualizarOportunidadRequest
import pe.quantum.crm.domain.oportunidades.dto.CrearOportunidadRequest
import java.math.BigDecimal

/**
 * Escala de los campos monetarios de la oportunidad (V10: `dcto NUMERIC(5,2)`,
 * `precio_unitario NUMERIC(12,2)`).
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

    private fun crear(dcto: BigDecimal?) = CrearOportunidadRequest(idEmpresa = 1L, idModelo = 1L, cantidad = 100, dcto = dcto)

    @Test
    fun `crear - dcto con mas de 2 decimales se rechaza`() {
        assertThat(camposInvalidos(crear(BigDecimal("2.994")))).contains("dcto")
    }

    @Test
    fun `crear - dcto con hasta 2 decimales se acepta`() {
        assertThat(camposInvalidos(crear(BigDecimal("2.99")))).isEmpty()
        assertThat(camposInvalidos(crear(BigDecimal("3")))).isEmpty()
        assertThat(camposInvalidos(crear(BigDecimal("100.00")))).isEmpty()
        assertThat(camposInvalidos(crear(null))).isEmpty()
    }

    @Test
    fun `actualizar - dcto con mas de 2 decimales se rechaza`() {
        assertThat(camposInvalidos(ActualizarOportunidadRequest(dcto = BigDecimal("2.994")))).contains("dcto")
        assertThat(camposInvalidos(ActualizarOportunidadRequest(dcto = BigDecimal("0.001")))).contains("dcto")
    }

    @Test
    fun `actualizar - precio_unitario con mas de 2 decimales se rechaza`() {
        assertThat(camposInvalidos(ActualizarOportunidadRequest(precioUnitario = BigDecimal("45000.005"))))
            .contains("precioUnitario")
    }

    @Test
    fun `actualizar - precio_unitario supera los 10 digitos enteros de NUMERIC(12,2)`() {
        // 11 digitos enteros: la columna no lo admite, la API tampoco debe.
        assertThat(camposInvalidos(ActualizarOportunidadRequest(precioUnitario = BigDecimal("12345678901.00"))))
            .contains("precioUnitario")
        // 10 digitos enteros: el maximo que cabe en NUMERIC(12,2).
        assertThat(camposInvalidos(ActualizarOportunidadRequest(precioUnitario = BigDecimal("1234567890.99"))))
            .isEmpty()
    }

    @Test
    fun `actualizar - los ceros a la derecha cuentan como decimales`() {
        // @Digits mira la escala del BigDecimal, no el valor: "2.9000" (escala 4)
        // se rechaza aunque valga lo mismo que 2.90. Es deliberado y esta anotado
        // aqui para que no sorprenda: JSON.stringify emite 2.9, no 2.9000, y
        // aceptar escalas arbitrarias es lo que abrio el descuadre de monto_total.
        assertThat(camposInvalidos(ActualizarOportunidadRequest(dcto = BigDecimal("2.9000")))).contains("dcto")
    }

    @Test
    fun `actualizar - valores con escala valida no se rechazan`() {
        assertThat(camposInvalidos(ActualizarOportunidadRequest(dcto = BigDecimal("2.99"), precioUnitario = BigDecimal("45000.00"))))
            .isEmpty()
    }
}
