package pe.quantum.crm.integracion.sunat

import java.math.BigDecimal
import java.time.LocalDate

/**
 * Tipo de cambio tal como lo publica el proveedor, antes de persistirse.
 *
 * La `fecha` es SIEMPRE la que viene en la respuesta, nunca el reloj local: la
 * app corre en UTC y Lima es UTC-5, asi que "hoy" segun el servidor no coincide
 * con el dia publicado por SUNAT durante varias horas al dia.
 */
data class TipoCambioExterno(
    val fecha: LocalDate,
    val compra: BigDecimal,
    val venta: BigDecimal,
)
