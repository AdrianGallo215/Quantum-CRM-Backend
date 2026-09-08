package pe.quantum.crm.domain.reportes

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pe.quantum.crm.domain.reportes.dto.FilaExportComercial
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * Consulta plana de la gestion comercial para el export en Excel (plan-15).
 *
 * Solo lectura, SQL nativo parametrizado, mismo patron que `ReporteService`.
 * No importa NI UNA clase de otro modulo de dominio: consultar `empresas`,
 * `tareas` o `eventos` por SQL no cruza la frontera que vigila ArchUnit
 * (CLAUDE.md regla 12) — lo que la cruzaria es importar sus entidades.
 *
 * LA FORMA DEL RESULTADO: una fila por ACTIVIDAD, repitiendo prospecto y
 * oportunidad. El universo de filas base se arma con un UNION de dos ramas para
 * que no se pierda ningun registro:
 *   · una fila por oportunidad;
 *   · una fila "de prospeccion" (sin oportunidad) por empresa que no tiene
 *     ninguna oportunidad O que tiene actividades colgadas de la empresa.
 * Sin esa segunda condicion, las tareas y eventos de prospeccion de una empresa
 * que YA tiene oportunidades desaparecerian en silencio.
 *
 * La formula de dinero esta duplicada aqui a proposito, igual que en
 * `ReporteService`: la FUENTE DE VERDAD es `MontoTotal.calcular`
 * (domain.oportunidades), y este modulo es SQL nativo y no puede cruzar de
 * modulo. Si cambia alla, cambia aqui.
 */
@Service
class ExportComercialService(
    private val jdbc: NamedParameterJdbcTemplate,
) {
    /**
     * @param desde inclusive; null = sin limite inferior.
     * @param hasta inclusive (se convierte a limite exclusivo sumando un dia);
     *   null = sin limite superior.
     */
    @Transactional(readOnly = true)
    fun filas(
        desde: LocalDate?,
        hasta: LocalDate?,
    ): List<FilaExportComercial> {
        val parametros = MapSqlParameterSource()
        val sql =
            buildString {
                append(CONSULTA_BASE)
                // Fragmentos FIJOS, sin datos del usuario dentro: los valores
                // viajan como parametros nombrados (CLAUDE.md regla 11).
                if (desde != null) {
                    append(" AND COALESCE(o.created_at, emp.created_at) >= :desde")
                    parametros.addValue("desde", desde.atStartOfDay())
                }
                if (hasta != null) {
                    append(" AND COALESCE(o.created_at, emp.created_at) < :hasta")
                    parametros.addValue("hasta", hasta.plusDays(1).atStartOfDay())
                }
                append(ORDEN)
            }
        return jdbc.query(sql, parametros) { rs, _ -> aFila(rs) }
    }

    @Suppress("LongMethod") // 33 columnas: el mapeo es plano por naturaleza.
    private fun aFila(rs: ResultSet): FilaExportComercial =
        FilaExportComercial(
            ruc = rs.getString("ruc"),
            razonSocial = rs.getString("razon_social"),
            empresaCreadaEn = rs.momento("empresa_creada_en"),
            responsableEmpresa = rs.getString("responsable_empresa"),
            origenLead = rs.getString("origen_lead"),
            segmentos = rs.getString("segmentos"),
            estadoCartera = rs.getString("estado_cartera"),
            idOportunidad = rs.getObject("id_oportunidad") as? Long,
            vendedorOportunidad = rs.getString("vendedor_oportunidad"),
            estadoOportunidad = rs.getString("estado_oportunidad"),
            modelosYUnidades = rs.getString("modelos_y_unidades"),
            unidadesTotales = rs.getObject("unidades_totales")?.let { rs.getInt("unidades_totales") },
            montoTotal = rs.getBigDecimal("monto_total"),
            financiadora = rs.getString("financiadora"),
            oportunidadCreadaEn = rs.momento("oportunidad_creada_en"),
            fechaCierreEstimado = rs.getDate("fecha_cierre_estimado")?.toLocalDate(),
            facturadoEn = rs.momento("facturado_en"),
            motivoCierre = rs.getString("motivo_cierre"),
            notasOportunidad = rs.getString("notas_oportunidad"),
            siguienteAccion = rs.getString("siguiente_accion"),
            // El CRM no guarda "principal bloqueo" en ninguna columna. Siempre null,
            // que el Excel convierte en "-". Rellenarlo con notas o motivo_cierre
            // seria inventar contenido: el ticket lo prohibe (R8).
            principalBloqueo = null,
            primerContacto = rs.momento("primer_contacto"),
            ultimaGestion = rs.momento("ultima_gestion"),
            diasSinGestion = rs.getObject("dias_sin_gestion")?.let { rs.getInt("dias_sin_gestion") },
            actividadTipo = rs.getString("actividad_tipo"),
            actividadTitulo = rs.getString("actividad_titulo"),
            actividadTipoAccion = rs.getString("actividad_tipo_accion"),
            actividadEstado = rs.getString("actividad_estado"),
            actividadFecha = rs.momento("actividad_fecha"),
            actividadResponsable = rs.getString("actividad_responsable"),
            actividadDescripcion = rs.getString("actividad_descripcion"),
            actividadComentarios = rs.getString("actividad_comentarios"),
            actividadRegistradaEn = rs.momento("actividad_registrada_en"),
        )

    /** `getTimestamp` devuelve null sin lanzar; el `?.` evita el NPE del unboxing. */
    private fun ResultSet.momento(columna: String): LocalDateTime? = (getObject(columna) as? Timestamp)?.toLocalDateTime()

    private companion object {
        val CONSULTA_BASE =
            """
            WITH actividades AS (
                SELECT t.id_empresa            AS act_id_empresa,
                       t.id_oportunidad        AS act_id_oportunidad,
                       'tarea'                 AS act_tipo,
                       t.id                    AS act_id,
                       t.tipo_accion::text     AS act_titulo,
                       t.tipo_accion::text     AS act_tipo_accion,
                       t.descripcion           AS act_descripcion,
                       t.estado_accion::text   AS act_estado,
                       t.fecha_ejecucion       AS act_fecha,
                       t.id_asignado           AS act_id_responsable,
                       t.created_at            AS act_created_at
                FROM tareas t
                UNION ALL
                SELECT ev.id_empresa,
                       ev.id_oportunidad,
                       'evento',
                       ev.id,
                       COALESCE(ce.nombre, ev.nombre_personalizado),
                       NULL,
                       ev.descripcion,
                       ev.estado::text,
                       COALESCE(ev.fecha_ocurrencia, ev.fecha_estimada::timestamp),
                       ev.created_by,
                       ev.created_at
                FROM eventos ev
                LEFT JOIN catalogo_eventos ce ON ce.id = ev.id_catalogo_evento
            ),
            universo AS (
                SELECT emp.id AS u_id_empresa, o.id AS u_id_oportunidad
                FROM empresas emp
                JOIN oportunidades o ON o.id_empresa = emp.id
                UNION ALL
                SELECT emp.id, NULL::bigint
                FROM empresas emp
                WHERE NOT EXISTS (SELECT 1 FROM oportunidades o2 WHERE o2.id_empresa = emp.id)
                   OR EXISTS (
                       SELECT 1 FROM actividades a2
                       WHERE a2.act_id_empresa = emp.id AND a2.act_id_oportunidad IS NULL
                   )
            )
            SELECT emp.ruc,
                   emp.razon_social,
                   emp.created_at                                   AS empresa_creada_en,
                   CONCAT(ev_emp.nombres, ' ', ev_emp.apellidos)    AS responsable_empresa,
                   emp.origen_lead::text                            AS origen_lead,
                   (SELECT string_agg(s.segmento::text, ', ' ORDER BY s.segmento::text)
                    FROM empresa_segmentos s WHERE s.id_empresa = emp.id) AS segmentos,
                   emp.estado_cartera::text                         AS estado_cartera,
                   o.id                                             AS id_oportunidad,
                   CONCAT(ev_op.nombres, ' ', ev_op.apellidos)      AS vendedor_oportunidad,
                   o.estado::text                                   AS estado_oportunidad,
                   (SELECT string_agg(m.codigo || ' x' || COALESCE(i.cantidad, 0)::text, ', ' ORDER BY m.codigo)
                    FROM oportunidad_items i
                    JOIN modelos m ON m.id = i.id_modelo
                    WHERE i.id_oportunidad = o.id)                  AS modelos_y_unidades,
                   (SELECT SUM(COALESCE(i.cantidad, 0))
                    FROM oportunidad_items i WHERE i.id_oportunidad = o.id) AS unidades_totales,
                   (SELECT SUM(ROUND(i.cantidad * i.precio_venta * (1 - COALESCE(i.descuento, 0) / 100), 2))
                    FROM oportunidad_items i WHERE i.id_oportunidad = o.id) AS monto_total,
                   fin.nombre                                       AS financiadora,
                   o.created_at                                     AS oportunidad_creada_en,
                   o.fecha_cierre_estimado,
                   o.facturado_en,
                   o.motivo_cierre,
                   o.notas                                          AS notas_oportunidad,
                   COALESCE(
                       (SELECT t.tipo_accion::text FROM tareas t
                        WHERE t.id_oportunidad = u.u_id_oportunidad AND t.estado_accion = 'pendiente'
                        ORDER BY t.fecha_ejecucion NULLS LAST, t.id LIMIT 1),
                       (SELECT t.tipo_accion::text FROM tareas t
                        WHERE u.u_id_oportunidad IS NULL AND t.id_empresa = u.u_id_empresa
                          AND t.id_oportunidad IS NULL AND t.estado_accion = 'pendiente'
                        ORDER BY t.fecha_ejecucion NULLS LAST, t.id LIMIT 1)
                   )                                                AS siguiente_accion,
                   (SELECT MIN(a2.act_created_at) FROM actividades a2
                    WHERE a2.act_id_oportunidad = u.u_id_oportunidad
                       OR (u.u_id_oportunidad IS NULL AND a2.act_id_oportunidad IS NULL
                           AND a2.act_id_empresa = u.u_id_empresa)) AS primer_contacto,
                   (SELECT MAX(a2.act_created_at) FROM actividades a2
                    WHERE a2.act_id_oportunidad = u.u_id_oportunidad
                       OR (u.u_id_oportunidad IS NULL AND a2.act_id_oportunidad IS NULL
                           AND a2.act_id_empresa = u.u_id_empresa)) AS ultima_gestion,
                   (SELECT EXTRACT(DAY FROM (NOW() - MAX(a2.act_created_at)))::int FROM actividades a2
                    WHERE a2.act_id_oportunidad = u.u_id_oportunidad
                       OR (u.u_id_oportunidad IS NULL AND a2.act_id_oportunidad IS NULL
                           AND a2.act_id_empresa = u.u_id_empresa)) AS dias_sin_gestion,
                   a.act_tipo                                       AS actividad_tipo,
                   a.act_titulo                                     AS actividad_titulo,
                   a.act_tipo_accion                                AS actividad_tipo_accion,
                   a.act_estado                                     AS actividad_estado,
                   a.act_fecha                                      AS actividad_fecha,
                   CONCAT(ev_act.nombres, ' ', ev_act.apellidos)    AS actividad_responsable,
                   a.act_descripcion                                AS actividad_descripcion,
                   (SELECT string_agg(c.texto, ' | ' ORDER BY c.created_at)
                    FROM actividad_comentarios c
                    WHERE (a.act_tipo = 'tarea'  AND c.id_tarea  = a.act_id)
                       OR (a.act_tipo = 'evento' AND c.id_evento = a.act_id)) AS actividad_comentarios,
                   a.act_created_at                                 AS actividad_registrada_en
            FROM universo u
            JOIN empresas emp ON emp.id = u.u_id_empresa
            LEFT JOIN oportunidades o ON o.id = u.u_id_oportunidad
            LEFT JOIN empleados ev_emp ON ev_emp.id = emp.id_vendedor
            LEFT JOIN empleados ev_op ON ev_op.id = o.id_vendedor
            LEFT JOIN financiadoras fin ON fin.id = o.id_financiadora
            LEFT JOIN actividades a
                   ON a.act_id_oportunidad = u.u_id_oportunidad
                   OR (u.u_id_oportunidad IS NULL AND a.act_id_oportunidad IS NULL
                       AND a.act_id_empresa = u.u_id_empresa)
            LEFT JOIN empleados ev_act ON ev_act.id = a.act_id_responsable
            WHERE 1 = 1
            """.trimIndent()

        // String plano y con espacio inicial a proposito: `trimIndent()` sobre un
        // """ que empieza con salto de linea BORRA ese salto, y el ORDER BY quedaria
        // pegado al ultimo parametro (`...:desdeORDER BY`), con SQL invalido.
        const val ORDEN = " ORDER BY emp.razon_social, o.id NULLS FIRST, a.act_created_at"
    }
}
