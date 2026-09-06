package pe.quantum.crm.domain.simulaciones.dto

import jakarta.validation.constraints.DecimalMax
import jakarta.validation.constraints.DecimalMin
import jakarta.validation.constraints.Digits
import jakarta.validation.constraints.Positive
import jakarta.validation.constraints.Size
import java.math.BigDecimal
import java.time.Instant

private const val PRECIO_VENTA_MIN = "0.01"
private const val MONTO_MIN = "0.00"
private const val MONTO_DIGITOS_ENTEROS = 10
private const val DECIMALES_MONETARIOS = 2
private const val DESCUENTO_MIN = "0.00"
private const val DESCUENTO_MAX = "100.00"
private const val DESCUENTO_DIGITOS_ENTEROS = 3
private const val TEA_MIN = "0.01"
private const val TEA_MAX = "199.99"
private const val TEA_DIGITOS_ENTEROS = 4
private const val NOMBRE_LONGITUD_MIN = 1
private const val NOMBRE_LONGITUD_MAX = 200

/**
 * Respuesta de una simulacion (`reglas_simulaciones.md`, migracion V43).
 *
 * Los importes salen como `String` (`toPlainString()`), igual que
 * `OportunidadItemDto` — nunca como numero, para no perder precision decimal
 * en JSON.
 */
data class SimulacionDto(
    val id: Long,
    /** Real si `nombre IS NOT NULL`; si no, el autogenerado de §8.1. NUNCA se persiste el autogenerado. */
    val nombre: String,
    /** true si `nombre` viene de la columna; false si se autogenero al leer. */
    val nombreEsManual: Boolean,
    val modo: String,
    val idOportunidadItem: Long?,
    val idModelo: Long?,
    val modelo: ModeloEnSimulacionDto?,
    val idSimulacionOrigen: Long?,
    val precioVenta: String,
    val descuento: String,
    val cuotaInicial: String,
    val plazoMeses: Int,
    val tea: String,
    val valorResidual: String,
    val diasTrabajados: Int,
    val comisionEstructuracion: String,
    /** SOLO LECTURA: siempre server-side, nunca aceptado del cliente (§4). */
    val cuotaFinal: String,
    val esPrincipal: Boolean,
    val createdAt: Instant,
    val updatedAt: Instant,
)

/** Modelo de bus mostrado en la simulacion; no participa del calculo (§3.2). */
data class ModeloEnSimulacionDto(
    val id: Long,
    val codigo: String,
)

/**
 * Body de creacion de una simulacion. Limites espejo EXACTO de los CHECK de
 * V43 (lineas 67-78): `precio_venta > 0`, `descuento` 0-100,
 * `cuota_inicial >= 0`, `plazo_meses > 0`, `tea` 0-200 exclusivo,
 * `valor_residual >= 0`, `dias_trabajados > 0`, `comision_estructuracion >= 0`,
 * `nombre` no vacio si viene.
 *
 * `cuotaFinal` NO se declara aqui: la calcula el backend, nunca se acepta del
 * cliente (restriccion 2 del encargo). `esPrincipal` tampoco: la decide el
 * Service (D38).
 */
data class CrearSimulacionRequest(
    val modo: String,
    @field:Size(min = NOMBRE_LONGITUD_MIN, max = NOMBRE_LONGITUD_MAX, message = "nombre no puede estar vacio")
    val nombre: String? = null,
    val idOportunidadItem: Long? = null,
    val idModelo: Long? = null,
    @field:DecimalMin(value = PRECIO_VENTA_MIN, message = "precio_venta debe ser mayor a 0")
    @field:Digits(
        integer = MONTO_DIGITOS_ENTEROS,
        fraction = DECIMALES_MONETARIOS,
        message = "precio_venta admite como maximo 10 digitos enteros y 2 decimales",
    )
    val precioVenta: BigDecimal,
    @field:DecimalMin(value = DESCUENTO_MIN, message = "descuento no puede ser negativo")
    @field:DecimalMax(value = DESCUENTO_MAX, message = "descuento no puede superar 100")
    @field:Digits(
        integer = DESCUENTO_DIGITOS_ENTEROS,
        fraction = DECIMALES_MONETARIOS,
        message = "descuento admite como maximo 2 decimales",
    )
    val descuento: BigDecimal? = null,
    @field:DecimalMin(value = MONTO_MIN, message = "cuota_inicial no puede ser negativa")
    @field:Digits(
        integer = MONTO_DIGITOS_ENTEROS,
        fraction = DECIMALES_MONETARIOS,
        message = "cuota_inicial admite como maximo 10 digitos enteros y 2 decimales",
    )
    val cuotaInicial: BigDecimal,
    @field:Positive(message = "plazo_meses debe ser mayor a 0")
    val plazoMeses: Int,
    @field:DecimalMin(value = TEA_MIN, message = "tea debe ser mayor a 0")
    @field:DecimalMax(value = TEA_MAX, message = "tea debe ser menor a 200")
    @field:Digits(
        integer = TEA_DIGITOS_ENTEROS,
        fraction = DECIMALES_MONETARIOS,
        message = "tea admite como maximo 4 digitos enteros y 2 decimales",
    )
    val tea: BigDecimal,
    @field:DecimalMin(value = MONTO_MIN, message = "valor_residual no puede ser negativo")
    @field:Digits(
        integer = MONTO_DIGITOS_ENTEROS,
        fraction = DECIMALES_MONETARIOS,
        message = "valor_residual admite como maximo 10 digitos enteros y 2 decimales",
    )
    val valorResidual: BigDecimal? = null,
    @field:Positive(message = "dias_trabajados debe ser mayor a 0")
    val diasTrabajados: Int? = null,
    @field:DecimalMin(value = MONTO_MIN, message = "comision_estructuracion no puede ser negativa")
    @field:Digits(
        integer = MONTO_DIGITOS_ENTEROS,
        fraction = DECIMALES_MONETARIOS,
        message = "comision_estructuracion admite como maximo 10 digitos enteros y 2 decimales",
    )
    val comisionEstructuracion: BigDecimal? = null,
)

/**
 * Body de `PATCH` parcial de una simulacion: todos los campos nullable, solo
 * se toca lo que viene. Mismas anotaciones de rango que
 * [CrearSimulacionRequest], espejo de los mismos CHECK de V43.
 */
data class ActualizarSimulacionRequest(
    /**
     * Se acepta y se RECHAZA en el Service si difiere del actual (§2 exige que el
     * Service sea una de las tres lineas de defensa; sin este campo la unica
     * defensa de backend seria el trigger, que responde 500). Ver decision D36
     * de plan-09-mapa-simulaciones-modulo.md.
     */
    val modo: String? = null,
    @field:Size(min = NOMBRE_LONGITUD_MIN, max = NOMBRE_LONGITUD_MAX, message = "nombre no puede estar vacio")
    val nombre: String? = null,
    val idOportunidadItem: Long? = null,
    val idModelo: Long? = null,
    @field:DecimalMin(value = PRECIO_VENTA_MIN, message = "precio_venta debe ser mayor a 0")
    @field:Digits(
        integer = MONTO_DIGITOS_ENTEROS,
        fraction = DECIMALES_MONETARIOS,
        message = "precio_venta admite como maximo 10 digitos enteros y 2 decimales",
    )
    val precioVenta: BigDecimal? = null,
    @field:DecimalMin(value = DESCUENTO_MIN, message = "descuento no puede ser negativo")
    @field:DecimalMax(value = DESCUENTO_MAX, message = "descuento no puede superar 100")
    @field:Digits(
        integer = DESCUENTO_DIGITOS_ENTEROS,
        fraction = DECIMALES_MONETARIOS,
        message = "descuento admite como maximo 2 decimales",
    )
    val descuento: BigDecimal? = null,
    @field:DecimalMin(value = MONTO_MIN, message = "cuota_inicial no puede ser negativa")
    @field:Digits(
        integer = MONTO_DIGITOS_ENTEROS,
        fraction = DECIMALES_MONETARIOS,
        message = "cuota_inicial admite como maximo 10 digitos enteros y 2 decimales",
    )
    val cuotaInicial: BigDecimal? = null,
    @field:Positive(message = "plazo_meses debe ser mayor a 0")
    val plazoMeses: Int? = null,
    @field:DecimalMin(value = TEA_MIN, message = "tea debe ser mayor a 0")
    @field:DecimalMax(value = TEA_MAX, message = "tea debe ser menor a 200")
    @field:Digits(
        integer = TEA_DIGITOS_ENTEROS,
        fraction = DECIMALES_MONETARIOS,
        message = "tea admite como maximo 4 digitos enteros y 2 decimales",
    )
    val tea: BigDecimal? = null,
    @field:DecimalMin(value = MONTO_MIN, message = "valor_residual no puede ser negativo")
    @field:Digits(
        integer = MONTO_DIGITOS_ENTEROS,
        fraction = DECIMALES_MONETARIOS,
        message = "valor_residual admite como maximo 10 digitos enteros y 2 decimales",
    )
    val valorResidual: BigDecimal? = null,
    @field:Positive(message = "dias_trabajados debe ser mayor a 0")
    val diasTrabajados: Int? = null,
    @field:DecimalMin(value = MONTO_MIN, message = "comision_estructuracion no puede ser negativa")
    @field:Digits(
        integer = MONTO_DIGITOS_ENTEROS,
        fraction = DECIMALES_MONETARIOS,
        message = "comision_estructuracion admite como maximo 10 digitos enteros y 2 decimales",
    )
    val comisionEstructuracion: BigDecimal? = null,
)

data class SimulacionFiltros(
    val idOportunidadItem: Long? = null,
    val idModelo: Long? = null,
    val modo: String? = null,
)

/** Salida de `GET /simulaciones/:id/cronograma`. Nada de esto se persiste (§4). */
data class CronogramaDto(
    val cuotaFinal: String,
    val cuotaFinanciera: String,
    val valorVenta: String,
    val igv: String,
    val principal: String,
    val tasaNominalMensual: String,
    val filas: List<FilaCronogramaDto>,
)

/**
 * Una fila del cronograma. `interes`, `igv`, `cuota` y `cuotaConIgv` van null en
 * el mes 0 (la fila de la cuota inicial); `igv` va null en todo el modo leasing,
 * que no desglosa IGV (§3.3).
 */
data class FilaCronogramaDto(
    val mes: Int,
    val saldoInicial: String,
    val amortizacion: String,
    val interes: String?,
    val igv: String?,
    val saldoFinal: String,
    val cuota: String?,
    val cuotaConIgv: String?,
)
