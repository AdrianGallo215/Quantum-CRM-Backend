package pe.quantum.crm.domain.tipocambio

import pe.quantum.crm.domain.tipocambio.dto.TipoCambioDto

/**
 * API publica del modulo de tipo de cambio PEN/USD (SUNAT).
 *
 * La integracion externa nunca puede tumbar al que la llama: si SUNAT no
 * responde se conserva el ultimo valor guardado (reglas_simulaciones.md §12).
 */
interface TipoCambioService {
    /** Tipo de cambio vigente: la fila de fecha mayor. Null si nunca se guardo ninguno. */
    fun vigente(): TipoCambioDto?

    /** Consulta SUNAT y guarda si hay dato nuevo. Devuelve true si escribio. Lo invoca el job. */
    fun actualizarDesdeSunat(): Boolean
}
