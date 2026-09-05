package pe.quantum.crm.domain.tipocambio.dto

import java.math.BigDecimal
import java.time.LocalDate

data class TipoCambioDto(
    val fecha: LocalDate,
    val compra: BigDecimal,
    val venta: BigDecimal,
)
