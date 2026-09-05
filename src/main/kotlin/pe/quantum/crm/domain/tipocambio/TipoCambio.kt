package pe.quantum.crm.domain.tipocambio

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * Tipo de cambio PEN/USD publicado por SUNAT (tabla `tipo_cambio`, migracion V41).
 * Historica: una fila por `fecha`. El valor vigente (con fallback al ultimo guardado
 * si SUNAT no respondio hoy) sale de `ORDER BY fecha DESC LIMIT 1`, ver
 * `TipoCambioRepository.findFirstByOrderByFechaDesc()` y reglas_simulaciones.md §12.
 */
@Entity
@Table(name = "tipo_cambio")
class TipoCambio(
    @Id
    val fecha: LocalDate,
    @Column(nullable = false)
    val compra: BigDecimal,
    @Column(nullable = false)
    val venta: BigDecimal,
    @Column(nullable = false)
    val fuente: String = "sunat",
    @Column(name = "created_at", nullable = false)
    val createdAt: LocalDateTime = LocalDateTime.now(),
)
