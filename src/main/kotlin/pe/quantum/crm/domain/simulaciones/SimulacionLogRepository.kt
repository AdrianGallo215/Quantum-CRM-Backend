package pe.quantum.crm.domain.simulaciones

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import java.time.LocalDateTime

/**
 * Bitácora de `simulacion_log`: solo INSERT, nunca UPDATE ni DELETE
 * (reglas_simulaciones.md §7). Las dos queries de historial son de Plan E
 * (D43 de plan-11-mapa-historial-calculadora.md); el registro de eventos desde
 * `SimulacionServiceImpl` viene de Plan D.
 */
interface SimulacionLogRepository : JpaRepository<SimulacionLog, Long> {
    /**
     * Los eventos con snapshot (creada/editada/restaurada) de los ultimos 7 dias,
     * hasta 15, mas recientes primero (§7.2). El desempate por `id` no es
     * cosmetico: dos eventos de la misma transaccion pueden compartir `created_at`
     * (K29 de plan-11-mapa-historial-calculadora.md) — restaurar() registra un
     * `editada` y luego un `restaurada` en la misma transaccion, y actualizar()
     * puede registrar `enlazada_a_item` + `editada` — y sin el desempate, el
     * orden entre ellos queda indefinido.
     */
    @Query(
        value = """
            SELECT * FROM simulacion_log
            WHERE id_simulacion = :idSimulacion
              AND tipo_evento IN ('creada', 'editada', 'restaurada')
              AND created_at > now() - interval '7 days'
            ORDER BY created_at DESC, id DESC
            LIMIT 15
        """,
        nativeQuery = true,
    )
    fun historial(idSimulacion: Long): List<SimulacionLog>

    /**
     * El evento con snapshot inmediatamente ANTERIOR al par (`momento`, `id`) dado,
     * sin filtro de ventana: el diff del evento mas antiguo del historial se
     * calcula contra su predecesor real aunque caiga fuera de los 7 dias (K23).
     *
     * Compara el par completo, no solo `created_at`: con dos eventos empatados en
     * timestamp (K29), comparar solo por fecha se saltaria el de `id` menor.
     */
    @Query(
        value = """
            SELECT * FROM simulacion_log
            WHERE id_simulacion = :idSimulacion
              AND tipo_evento IN ('creada', 'editada', 'restaurada')
              AND (created_at < :momento OR (created_at = :momento AND id < :id))
            ORDER BY created_at DESC, id DESC
            LIMIT 1
        """,
        nativeQuery = true,
    )
    fun eventoAnteriorA(
        idSimulacion: Long,
        momento: LocalDateTime,
        id: Long,
    ): SimulacionLog?
}
