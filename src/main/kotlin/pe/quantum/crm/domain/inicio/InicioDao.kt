package pe.quantum.crm.domain.inicio

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Component
import pe.quantum.crm.domain.empresas.dto.EmpresaResumen
import pe.quantum.crm.domain.inicio.dto.EventoSeguimientoDto
import java.math.BigDecimal
import java.time.LocalDate

/** Fila del resumen de pipeline por etapa. */
data class EtapaPipelineRow(
    val etapa: String,
    val count: Int,
    val valor: BigDecimal,
    val cantidadUnidades: Int,
)

/**
 * Consultas agregadas del panel de inicio (SQL nativo de solo lectura,
 * optimizadas para evitar N+1: un query por bloque del panel).
 */
@Component
class InicioDao(
    private val jdbc: NamedParameterJdbcTemplate,
) {
    /** Eventos pendientes con fecha de seguimiento, con filtro por vendedor. */
    fun eventosPorSeguir(idVendedor: Long?): List<EventoSeguimientoDto> {
        val sql =
            buildString {
                append(
                    """
                    SELECT e.id,
                           COALESCE(e.nombre_personalizado, ce.nombre, 'Evento') AS nombre,
                           e.fecha_seguimiento,
                           e.dispara_cambio_estado,
                           e.id_oportunidad,
                           emp.id AS id_empresa,
                           emp.razon_social,
                           emp.distrito
                    FROM eventos e
                    LEFT JOIN catalogo_eventos ce ON ce.id = e.id_catalogo_evento
                    LEFT JOIN oportunidades o ON o.id = e.id_oportunidad
                    LEFT JOIN empresas emp ON emp.id = COALESCE(o.id_empresa, e.id_empresa)
                    WHERE e.estado = 'pendiente' AND e.fecha_seguimiento IS NOT NULL
                      AND COALESCE(emp.en_cartera_maestra, false) = false
                    """.trimIndent(),
                )
                if (idVendedor != null) {
                    append(" AND COALESCE(o.id_vendedor, emp.id_vendedor) = :idVendedor")
                }
                append(" ORDER BY e.fecha_seguimiento ASC LIMIT 50")
            }
        val hoy = LocalDate.now()
        return jdbc.query(sql, MapSqlParameterSource("idVendedor", idVendedor)) { rs, _ ->
            val fechaSeguimiento = rs.getDate("fecha_seguimiento").toLocalDate()
            EventoSeguimientoDto(
                id = rs.getLong("id"),
                nombre = rs.getString("nombre"),
                fechaSeguimiento = fechaSeguimiento,
                seguimientoVencido = fechaSeguimiento.isBefore(hoy),
                disparaCambioEstado = rs.getBoolean("dispara_cambio_estado"),
                empresa =
                    rs.getLong("id_empresa").takeIf { !rs.wasNull() }?.let {
                        EmpresaResumen(id = it, razonSocial = rs.getString("razon_social"), distrito = rs.getString("distrito"))
                    },
                idOportunidad = rs.getLong("id_oportunidad").takeIf { !rs.wasNull() },
            )
        }
    }

    /** Conteo y valor de oportunidades no cerradas por etapa. */
    fun resumenPipeline(idVendedor: Long?): List<EtapaPipelineRow> {
        val sql =
            buildString {
                append(
                    """
                    SELECT o.estado, COUNT(*) AS total,
                           COALESCE(SUM(it.monto), 0) AS valor,
                           COALESCE(SUM(it.cantidad), 0) AS unidades
                    FROM oportunidades o
                    -- plan-08 C2: monto y unidades salen de `oportunidad_items`, pero el
                    -- agregado sigue siendo por ETAPA con UNA fila por oportunidad, asi
                    -- que los items se suman antes en un LATERAL y despues se agrupa.
                    -- Formula de dinero duplicada en SQL a proposito: la FUENTE DE VERDAD
                    -- es MontoTotal.calcular (domain.oportunidades); este modulo es SQL
                    -- nativo y no puede cruzar de modulo (regla 12). Si cambia alla,
                    -- cambia aqui.
                    LEFT JOIN LATERAL (
                        SELECT SUM(ROUND(i.cantidad * i.precio_venta * (1 - COALESCE(i.descuento, 0) / 100), 2)) AS monto,
                               SUM(i.cantidad) AS cantidad
                        FROM oportunidad_items i
                        WHERE i.id_oportunidad = o.id
                    ) it ON true
                    WHERE o.estado != 'cerrado'
                    """.trimIndent(),
                )
                if (idVendedor != null) {
                    append(" AND o.id_vendedor = :idVendedor")
                }
                append(" GROUP BY o.estado")
            }
        return jdbc.query(sql, MapSqlParameterSource("idVendedor", idVendedor)) { rs, _ ->
            EtapaPipelineRow(
                etapa = rs.getString("estado"),
                count = rs.getInt("total"),
                valor = rs.getBigDecimal("valor"),
                cantidadUnidades = rs.getInt("unidades"),
            )
        }
    }

    /**
     * Unidades facturadas por vendedor en un periodo: suma en vivo sobre
     * `oportunidades.facturado_en` (sin contador aparte, ver V33). `mes = null`
     * agrega todo el año.
     */
    fun unidadesFacturadasPorVendedor(
        idsVendedor: Collection<Long>,
        anio: Int,
        mes: Int?,
    ): Map<Long, Int> {
        if (idsVendedor.isEmpty()) return emptyMap()
        val sql =
            buildString {
                append("SELECT o.id_vendedor, COALESCE(SUM(i.cantidad), 0) AS unidades FROM oportunidades o ")
                // plan-08 C2: las unidades salen de `oportunidad_items`, no de la
                // columna plana `oportunidades.cantidad`.
                append("JOIN oportunidad_items i ON i.id_oportunidad = o.id ")
                append("WHERE o.estado = 'facturado' AND o.id_vendedor IN (:idsVendedor) ")
                append("AND EXTRACT(YEAR FROM o.facturado_en) = :anio ")
                if (mes != null) append("AND EXTRACT(MONTH FROM o.facturado_en) = :mes ")
                append("GROUP BY o.id_vendedor")
            }
        val params =
            MapSqlParameterSource()
                .addValue("idsVendedor", idsVendedor)
                .addValue("anio", anio)
                .addValue("mes", mes)
        return jdbc.query(sql, params) { rs, _ -> rs.getLong("id_vendedor") to rs.getInt("unidades") }.toMap()
    }
}
