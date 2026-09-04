package pe.quantum.crm.domain.tipocambio

import org.springframework.data.jpa.repository.JpaRepository
import java.time.LocalDate

interface TipoCambioRepository : JpaRepository<TipoCambio, LocalDate> {
    /**
     * Valor vigente con fallback al ultimo guardado: si el job de SUNAT no corrio hoy,
     * esta misma consulta devuelve la fila de fecha mas reciente disponible
     * (reglas_simulaciones.md §12).
     */
    fun findFirstByOrderByFechaDesc(): TipoCambio?
}
