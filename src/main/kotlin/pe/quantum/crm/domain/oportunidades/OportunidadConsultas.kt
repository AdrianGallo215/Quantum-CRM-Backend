package pe.quantum.crm.domain.oportunidades

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Component
import pe.quantum.crm.shared.enums.EstadoOportunidad

/**
 * Consultas de solo lectura sobre tablas de otros modulos (tareas, eventos,
 * catalogo_eventos) que el listado y el cambio de estado de oportunidades
 * necesitan. SQL nativo parametrizado para no acoplar entidades entre modulos.
 */
@Component
class OportunidadConsultas(
    private val jdbc: NamedParameterJdbcTemplate,
) {
    /** Tareas pendientes por oportunidad (para `tareas_pendientes_count`). */
    fun tareasPendientesPorOportunidad(ids: Collection<Long>): Map<Long, Int> = contarPorOportunidad(ids, TAREAS_PENDIENTES_SQL)

    /** Eventos pendientes por oportunidad (para `eventos_pendientes_count`). */
    fun eventosPendientesPorOportunidad(ids: Collection<Long>): Map<Long, Int> = contarPorOportunidad(ids, EVENTOS_PENDIENTES_SQL)

    /**
     * Eventos recomendados de la etapa que NO fueron registrados como ocurridos
     * en la oportunidad (advertencias del cambio de estado, reglas §5.4).
     */
    fun eventosRecomendadosSinRegistrar(
        idOportunidad: Long,
        etapa: EstadoOportunidad,
    ): List<String> =
        jdbc.query(
            """
            SELECT ce.nombre
            FROM catalogo_eventos ce
            WHERE ce.es_recomendado = true
              AND ce.etapa_asociada = CAST(:etapa AS estado_op_enum)
              AND NOT EXISTS (
                  SELECT 1 FROM eventos e
                  WHERE e.id_oportunidad = :idOportunidad
                    AND e.id_catalogo_evento = ce.id
                    AND e.estado = 'ocurrido'
              )
            ORDER BY ce.id
            """.trimIndent(),
            MapSqlParameterSource()
                .addValue("etapa", etapa.name)
                .addValue("idOportunidad", idOportunidad),
        ) { rs, _ -> rs.getString("nombre") }

    private fun contarPorOportunidad(
        ids: Collection<Long>,
        sql: String,
    ): Map<Long, Int> {
        if (ids.isEmpty()) {
            return emptyMap()
        }
        val resultado = mutableMapOf<Long, Int>()
        jdbc.query(sql, MapSqlParameterSource("ids", ids)) { rs ->
            resultado[rs.getLong("id_oportunidad")] = rs.getInt("total")
        }
        return resultado
    }

    private companion object {
        val TAREAS_PENDIENTES_SQL =
            """
            SELECT id_oportunidad, COUNT(*) AS total
            FROM tareas
            WHERE id_oportunidad IN (:ids) AND estado_accion = 'pendiente'
            GROUP BY id_oportunidad
            """.trimIndent()

        val EVENTOS_PENDIENTES_SQL =
            """
            SELECT id_oportunidad, COUNT(*) AS total
            FROM eventos
            WHERE id_oportunidad IN (:ids) AND estado = 'pendiente'
            GROUP BY id_oportunidad
            """.trimIndent()
    }
}
