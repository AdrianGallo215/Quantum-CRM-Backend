package pe.quantum.crm.domain.oportunidades.dto

import jakarta.validation.constraints.DecimalMax
import jakarta.validation.constraints.DecimalMin
import jakarta.validation.constraints.Digits
import jakarta.validation.constraints.Positive
import java.math.BigDecimal

private const val DCTO_MIN = "0.0"
private const val DCTO_MAX = "100.0"
private const val PRECIO_MIN = "0.0"
private const val DCTO_DIGITOS_ENTEROS = 3
private const val PRECIO_DIGITOS_ENTEROS = 10
private const val DECIMALES_MONETARIOS = 2

/**
 * Item de oportunidad en listado y detalle (V42: `oportunidad_items`). Montos
 * como string, igual que `OportunidadDto` (contrato_api.md §10).
 *
 * `montoItem` es el subtotal del item (`cantidad x precioVenta x (1 - descuento/100)`).
 * NO se persiste: este DTO solo declara el campo, el calculo lo hace quien lo
 * construya.
 *
 * `advertencias` es el canal de salida de `reglas_negocio.md §12.2` (cambio de
 * modelo con precio editado manualmente), igual que en `OportunidadDto`. Solo
 * `actualizar()` puede llenarlo; el resto de caminos lo dejan vacio.
 */
data class OportunidadItemDto(
    val id: Long,
    val idModelo: Long,
    val modelo: ModeloEnOportunidadDto?,
    val cantidad: Int?,
    val precioVenta: String?,
    val descuento: String?,
    val cuotaFinanciadora: String,
    val montoItem: String?,
    val advertencias: List<String> = emptyList(),
)

/**
 * Body de creacion de un item de oportunidad. Limites espejo de los CHECK de
 * V42 (`descuento NUMERIC(5,2)`, `precio_venta`/`cuota_financiadora` NUMERIC(12,2)),
 * los mismos que `CrearOportunidadRequest` usa para `dcto`/`precioUnitario` —
 * ver el KDoc de `OportunidadDtos.kt` para el razonamiento de `@Digits`.
 */
data class CrearOportunidadItemRequest(
    @field:Positive(message = "id_modelo debe ser un identificador valido")
    val idModelo: Long,
    @field:Positive(message = "cantidad debe ser mayor a 0")
    val cantidad: Int? = null,
    @field:DecimalMin(value = PRECIO_MIN, message = "precio_venta no puede ser negativo")
    @field:Digits(
        integer = PRECIO_DIGITOS_ENTEROS,
        fraction = DECIMALES_MONETARIOS,
        message = "precio_venta admite como maximo 10 digitos enteros y 2 decimales",
    )
    val precioVenta: BigDecimal? = null,
    @field:DecimalMin(value = DCTO_MIN, message = "descuento no puede ser negativo")
    @field:DecimalMax(value = DCTO_MAX, message = "descuento no puede superar 100")
    @field:Digits(
        integer = DCTO_DIGITOS_ENTEROS,
        fraction = DECIMALES_MONETARIOS,
        message = "descuento admite como maximo 2 decimales",
    )
    val descuento: BigDecimal? = null,
    @field:DecimalMin(value = PRECIO_MIN, message = "cuota_financiadora no puede ser negativo")
    @field:Digits(
        integer = PRECIO_DIGITOS_ENTEROS,
        fraction = DECIMALES_MONETARIOS,
        message = "cuota_financiadora admite como maximo 10 digitos enteros y 2 decimales",
    )
    val cuotaFinanciadora: BigDecimal? = null,
)

/**
 * Body de `PUT` parcial de un item de oportunidad: mismos campos que
 * `CrearOportunidadItemRequest`, pero `idModelo` tambien nullable porque no
 * todos los campos vienen siempre.
 */
data class ActualizarOportunidadItemRequest(
    @field:Positive(message = "id_modelo debe ser un identificador valido")
    val idModelo: Long? = null,
    @field:Positive(message = "cantidad debe ser mayor a 0")
    val cantidad: Int? = null,
    @field:DecimalMin(value = PRECIO_MIN, message = "precio_venta no puede ser negativo")
    @field:Digits(
        integer = PRECIO_DIGITOS_ENTEROS,
        fraction = DECIMALES_MONETARIOS,
        message = "precio_venta admite como maximo 10 digitos enteros y 2 decimales",
    )
    val precioVenta: BigDecimal? = null,
    @field:DecimalMin(value = DCTO_MIN, message = "descuento no puede ser negativo")
    @field:DecimalMax(value = DCTO_MAX, message = "descuento no puede superar 100")
    @field:Digits(
        integer = DCTO_DIGITOS_ENTEROS,
        fraction = DECIMALES_MONETARIOS,
        message = "descuento admite como maximo 2 decimales",
    )
    val descuento: BigDecimal? = null,
    @field:DecimalMin(value = PRECIO_MIN, message = "cuota_financiadora no puede ser negativo")
    @field:Digits(
        integer = PRECIO_DIGITOS_ENTEROS,
        fraction = DECIMALES_MONETARIOS,
        message = "cuota_financiadora admite como maximo 10 digitos enteros y 2 decimales",
    )
    val cuotaFinanciadora: BigDecimal? = null,
)
