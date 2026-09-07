package pe.quantum.crm.domain.simulaciones.dto

import java.math.BigDecimal

/**
 * Datos minimos de un item de oportunidad para resolver su cuota Quantum
 * (§6.2). Vive en el subpaquete `dto` porque `oportunidades` lo construye y lo
 * pasa: es API publica de `simulaciones` (regla 12).
 *
 * Se pasan los datos y no solo el id a proposito: `OportunidadServiceImpl.toDtos`
 * ya tiene los items cargados, y pedirle a `simulaciones` que los relea seria
 * una consulta redundante y una llamada re-entrante
 * `oportunidades -> simulaciones -> oportunidades`.
 */
data class ItemParaCuota(
    val idItem: Long,
    val precioVenta: BigDecimal?,
    val descuento: BigDecimal?,
)
