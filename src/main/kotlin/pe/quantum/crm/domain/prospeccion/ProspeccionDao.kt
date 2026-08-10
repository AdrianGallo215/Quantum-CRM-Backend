package pe.quantum.crm.domain.prospeccion

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Component
import pe.quantum.crm.domain.contactos.dto.ContactoResumen
import java.time.LocalDateTime

/** Fila base de una empresa en prospeccion. */
data class EmpresaProspeccionRow(
    val id: Long,
    val ruc: String,
    val razonSocial: String,
    val distrito: String?,
    val createdAt: LocalDateTime,
)

/**
 * Consultas agregadas de prospeccion (SQL nativo parametrizado, solo lectura).
 * Cruza empresas, eventos-hito, tareas y contactos sin acoplar entidades JPA
 * de otros modulos.
 */
@Component
class ProspeccionDao(
    private val jdbc: NamedParameterJdbcTemplate,
) {
    /** Empresas con `estado_cartera = 'prospeccion'`, con filtro por vendedor. */
    fun empresasEnProspeccion(idVendedor: Long?): List<EmpresaProspeccionRow> {
        val sql =
            buildString {
                append("SELECT id, ruc, razon_social, distrito, created_at FROM empresas ")
                // Cartera Maestra: reservada de gerencia, nunca aparece en prospeccion general.
                append("WHERE estado_cartera = 'prospeccion' AND en_cartera_maestra = false ")
                if (idVendedor != null) {
                    append("AND id_vendedor = :idVendedor ")
                }
                append("ORDER BY id")
            }
        return jdbc.query(sql, MapSqlParameterSource("idVendedor", idVendedor)) { rs, _ ->
            EmpresaProspeccionRow(
                id = rs.getLong("id"),
                ruc = rs.getString("ruc"),
                razonSocial = rs.getString("razon_social"),
                distrito = rs.getString("distrito"),
                createdAt = rs.getTimestamp("created_at").toLocalDateTime(),
            )
        }
    }

    /**
     * Hitos ocurridos por empresa: `(id_empresa, id_catalogo) → fecha`.
     *
     * La clave presente significa "el hito ocurrio"; el valor puede ser null.
     * El CHECK `chk_evento_fecha_ocurrencia` de V14 solo exige que
     * `fecha_ocurrencia` sea nula si el evento NO ocurrio, no al reves: un
     * evento `ocurrido` sin fecha es un dato valido, y entonces
     * `MAX(fecha_ocurrencia)` vuelve NULL.
     */
    fun hitosOcurridos(idsEmpresa: Collection<Long>): Map<Pair<Long, Long>, LocalDateTime?> {
        if (idsEmpresa.isEmpty()) {
            return emptyMap()
        }
        val resultado = mutableMapOf<Pair<Long, Long>, LocalDateTime?>()
        jdbc.query(
            """
            SELECT e.id_empresa, e.id_catalogo_evento, MAX(e.fecha_ocurrencia) AS fecha
            FROM eventos e
            JOIN catalogo_eventos ce ON ce.id = e.id_catalogo_evento
            WHERE e.id_empresa IN (:ids)
              AND e.id_oportunidad IS NULL
              AND e.estado = 'ocurrido'
              AND ce.es_hito_prospeccion = true
            GROUP BY e.id_empresa, e.id_catalogo_evento
            """.trimIndent(),
            MapSqlParameterSource("ids", idsEmpresa),
        ) { rs ->
            resultado[rs.getLong("id_empresa") to rs.getLong("id_catalogo_evento")] =
                rs.getTimestamp("fecha")?.toLocalDateTime()
        }
        return resultado
    }

    /**
     * Ultima actividad por empresa: MAX entre tareas completadas y eventos
     * ocurridos sin oportunidad (contrato §16).
     */
    fun ultimaActividad(idsEmpresa: Collection<Long>): Map<Long, LocalDateTime> {
        if (idsEmpresa.isEmpty()) {
            return emptyMap()
        }
        val resultado = mutableMapOf<Long, LocalDateTime>()
        jdbc.query(
            """
            SELECT id_empresa, MAX(f) AS ultima
            FROM (
                SELECT id_empresa, COALESCE(fecha_ejecucion, updated_at) AS f
                FROM tareas
                WHERE id_empresa IN (:ids) AND id_oportunidad IS NULL AND estado_accion = 'completada'
                UNION ALL
                SELECT id_empresa, fecha_ocurrencia AS f
                FROM eventos
                WHERE id_empresa IN (:ids) AND id_oportunidad IS NULL AND estado = 'ocurrido'
            ) actividad
            GROUP BY id_empresa
            """.trimIndent(),
            MapSqlParameterSource("ids", idsEmpresa),
        ) { rs ->
            rs.getTimestamp("ultima")?.let { resultado[rs.getLong("id_empresa")] = it.toLocalDateTime() }
        }
        return resultado
    }

    /** Descripcion de la proxima tarea pendiente (sin oportunidad) por empresa. */
    fun siguienteTarea(idsEmpresa: Collection<Long>): Map<Long, String> {
        if (idsEmpresa.isEmpty()) {
            return emptyMap()
        }
        val resultado = mutableMapOf<Long, String>()
        jdbc.query(
            """
            SELECT DISTINCT ON (id_empresa) id_empresa, descripcion
            FROM tareas
            WHERE id_empresa IN (:ids) AND id_oportunidad IS NULL AND estado_accion = 'pendiente'
            ORDER BY id_empresa, fecha_ejecucion ASC NULLS LAST
            """.trimIndent(),
            MapSqlParameterSource("ids", idsEmpresa),
        ) { rs ->
            rs.getString("descripcion")?.let { resultado[rs.getLong("id_empresa")] = it }
        }
        return resultado
    }

    fun segmentos(idsEmpresa: Collection<Long>): Map<Long, List<String>> {
        if (idsEmpresa.isEmpty()) {
            return emptyMap()
        }
        val resultado = mutableMapOf<Long, MutableList<String>>()
        jdbc.query(
            "SELECT id_empresa, segmento FROM empresa_segmentos WHERE id_empresa IN (:ids) ORDER BY segmento",
            MapSqlParameterSource("ids", idsEmpresa),
        ) { rs ->
            resultado.getOrPut(rs.getLong("id_empresa")) { mutableListOf() }.add(rs.getString("segmento"))
        }
        return resultado
    }

    fun contactoPrincipal(idsEmpresa: Collection<Long>): Map<Long, ContactoResumen> {
        if (idsEmpresa.isEmpty()) {
            return emptyMap()
        }
        val resultado = mutableMapOf<Long, ContactoResumen>()
        jdbc.query(
            """
            SELECT DISTINCT ON (ec.id_empresa) ec.id_empresa, c.id, c.nombres, c.apellidos, c.tlf_1
            FROM empresa_contactos ec
            JOIN contactos c ON c.id = ec.id_contacto
            WHERE ec.id_empresa IN (:ids)
            ORDER BY ec.id_empresa, ec.es_principal DESC, c.id
            """.trimIndent(),
            MapSqlParameterSource("ids", idsEmpresa),
        ) { rs ->
            resultado[rs.getLong("id_empresa")] =
                ContactoResumen(
                    id = rs.getLong("id"),
                    nombres = rs.getString("nombres"),
                    apellidos = rs.getString("apellidos"),
                    tlf_1 = rs.getString("tlf_1"),
                )
        }
        return resultado
    }
}
