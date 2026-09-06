package pe.quantum.crm.domain.simulaciones.dto

import jakarta.validation.constraints.DecimalMax
import jakarta.validation.constraints.DecimalMin
import jakarta.validation.constraints.Digits
import jakarta.validation.constraints.Positive
import jakarta.validation.constraints.Size
import pe.quantum.crm.domain.empresas.dto.EmpresaResumen
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

/**
 * Body de `POST /simulaciones/:id/bifurcar` — "Guardar como Nueva Simulacion"
 * (§7.3). Mismos campos y limites que [ActualizarSimulacionRequest], porque lo
 * que llega es el mismo formulario de la calculadora; la diferencia esta en el
 * Service: aqui NADA se muta sobre el origen, se INSERTA una fila nueva con
 * `id_simulacion_origen` apuntando a el, y lo que el request no trae se hereda
 * del origen.
 *
 * `nombre` es la unica excepcion a esa herencia (decision D49 de
 * plan-11-mapa-historial-calculadora.md): si el request no lo trae, la
 * bifurcada nace SIN nombre manual y autogenera el suyo (§8.1). Heredar el del
 * origen dejaria dos simulaciones con el mismo titulo.
 *
 * `modo` SI se acepta y se aplica: a diferencia de [ActualizarSimulacionRequest]
 * —donde un modo distinto es 409 `MODO_INMUTABLE` (§2, decision D36)— esta es la
 * UNICA via autorizada para cambiar de modo (§2: *"Cambiar de modo exige Guardar
 * como Nueva Simulacion"*, hallazgo K27). No hay contradiccion con la
 * inmutabilidad: la fila del origen conserva su modo intacto; el modo nuevo vive
 * en una fila nueva.
 */
data class BifurcarSimulacionRequest(
    /** Se resuelve con el enum y se USA; NO pasa por `exigirModoInmutable` (K27, §2). */
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

/** Un campo que cambio entre dos eventos consecutivos del historial (§7.1). */
data class CampoDiffDto(
    val campo: String,
    val valorAnterior: String?,
    val valorNuevo: String?,
)

/**
 * Un evento del historial de una simulacion (§7.2): los eventos con snapshot
 * (`creada`/`editada`/`restaurada`) de los ultimos 7 dias, hasta 15, mas
 * recientes primero.
 */
data class EventoHistorialDto(
    val idEventoLog: Long,
    val tipoEvento: String,
    val createdAt: Instant,
    /** Null cuando el evento lo genero un job sin actor humano. */
    val createdBy: Long?,
    /**
     * Campos que cambiaron respecto del evento anterior. Vacio si es el primer
     * evento de la simulacion, y tambien cuando la escritura no toco ninguno de
     * los 10 parametros del snapshot —p. ej. un PATCH que solo reenlaza a otro
     * item, o un PATCH vacio— (K30). Un diff vacio es informacion honesta, no
     * un error.
     */
    val diff: List<CampoDiffDto>,
)

/** Body de `POST /simulaciones/:id/restaurar`. */
data class RestaurarSimulacionRequest(
    @field:Positive(message = "id_evento_log debe ser un identificador valido")
    val idEventoLog: Long,
)

/**
 * Body de `POST /api/v1/calculadora` — la Calculadora Financiera de
 * `reglas_simulaciones.md` §9: estimacion rapida durante la prospeccion, con el
 * MISMO motor y las MISMAS validaciones §13 que `crear`, pero **sin persistir
 * nada** (decision D50 de plan-11-mapa-historial-calculadora.md).
 *
 * `idOportunidadItem` NO se declara a proposito (§9: antes de "Enlazar a
 * Oportunidad" no existe item; enlazar es literalmente `POST /simulaciones`,
 * hallazgo K26). `idEmpresa`/`idModelo` son opcionales y solo sirven para
 * mostrarlos: no participan del calculo (§3.2).
 *
 * Limites de rango: espejo EXACTO de [CrearSimulacionRequest], mismas
 * constantes y mismos mensajes.
 */
data class CalculadoraRequest(
    val modo: String,
    val idEmpresa: Long? = null,
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
 * Salida de `POST /api/v1/calculadora`. Nada de esto se persiste (§9): no hay
 * `id`, ni `createdAt`, ni `esPrincipal`, porque no existe fila que los tenga.
 *
 * `empresa` y `modelo` van null cuando el request no trajo sus ids: son datos
 * de presentacion, no entradas del calculo.
 */
data class CalculadoraDto(
    val empresa: EmpresaResumen?,
    val modelo: ModeloEnSimulacionDto?,
    val cronograma: CronogramaDto,
)
