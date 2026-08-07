package pe.quantum.crm.domain.reportes

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pe.quantum.crm.domain.reportes.dto.DescuentosPorVendedorDto
import pe.quantum.crm.domain.reportes.dto.EtapaPipelineDto
import pe.quantum.crm.domain.reportes.dto.OportunidadSinActividadDto
import pe.quantum.crm.domain.reportes.dto.ProspeccionPorOrigenDto
import pe.quantum.crm.domain.reportes.dto.ReporteDescuentosDto
import pe.quantum.crm.domain.reportes.dto.ReporteEquipoItemDto
import pe.quantum.crm.domain.reportes.dto.ReportePipelineDto
import pe.quantum.crm.domain.reportes.dto.ReporteProspeccionDto
import pe.quantum.crm.domain.reportes.dto.ReporteVentasDto
import pe.quantum.crm.domain.reportes.dto.VelocidadEtapaDto
import pe.quantum.crm.domain.reportes.dto.VendedorRefDto
import pe.quantum.crm.domain.reportes.dto.VentasPorMesDto
import pe.quantum.crm.domain.reportes.dto.VentasPorModeloDto
import pe.quantum.crm.domain.reportes.dto.VentasPorVendedorDto
import java.math.BigDecimal
import java.math.RoundingMode
import java.sql.ResultSet
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit

/** Rango de fechas de un reporte. Default: mes calendario actual (contrato §18). */
data class PeriodoReporte(
    val desde: LocalDate,
    val hastaExclusivo: LocalDate,
) {
    companion object {
        fun de(
            fechaDesde: LocalDate?,
            fechaHasta: LocalDate?,
        ): PeriodoReporte {
            val hoy = LocalDate.now()
            val desde = fechaDesde ?: hoy.withDayOfMonth(1)
            val hasta = fechaHasta ?: hoy.withDayOfMonth(hoy.lengthOfMonth())
            return PeriodoReporte(desde = desde, hastaExclusivo = hasta.plusDays(1))
        }
    }
}

/**
 * Reportes estrategicos y operativos (contrato_api.md §18, B6.1/B6.2).
 * Solo lectura: SQL nativo parametrizado + agregacion en memoria (los volumenes
 * del MVP son bajos). El permiso admin/gerencia/jdv se verifica en el controller.
 */
@Service
// Un metodo por reporte del contrato §18. Cada uno es SQL + mapeo de filas +
// agregacion en un unico DTO: extraer trozos separaria la consulta de la forma
// que produce, que es justo lo que hace el reporte legible.
@Suppress("TooManyFunctions", "LongMethod")
class ReporteService(
    private val jdbc: NamedParameterJdbcTemplate,
) {
    // ── Ventas ─────────────────────────────────────────────────

    private data class VentaRow(
        val id: Long,
        val monto: BigDecimal,
        val cantidad: Int,
        val dcto: BigDecimal?,
        val mes: String,
        val idVendedor: Long,
        val vendedor: String,
        val modelo: String?,
    )

    /**
     * Ventas del periodo. La fecha de facturacion es `oportunidades.facturado_en`
     * (V33: "Fuente del computo"), la misma columna que usa `InicioDao` para el
     * panel del vendedor — una sola fuente de verdad para "cuando se facturo".
     *
     * Antes se derivaba del log de estados con un LATERAL cuyo
     * `ON f.fecha_facturacion IS NOT NULL` lo volvia un INNER JOIN: toda
     * oportunidad `facturado` sin fila en el log desaparecia del reporte en
     * silencio, y gerencia veia una cifra distinta de la del vendedor. Ademas
     * `facturado_en` esta indexada (idx_oportunidades_facturado_en).
     */
    @Transactional(readOnly = true)
    fun ventas(
        periodo: PeriodoReporte,
        idVendedor: Long?,
    ): ReporteVentasDto {
        val sql =
            buildString {
                append(
                    """
                    SELECT o.id, COALESCE(o.monto_total, 0) AS monto, COALESCE(o.cantidad, 0) AS cantidad, o.dcto,
                           to_char(o.facturado_en, 'YYYY-MM') AS mes,
                           o.id_vendedor, CONCAT(e.nombres, ' ', e.apellidos) AS vendedor,
                           m.codigo AS modelo
                    FROM oportunidades o
                    LEFT JOIN empleados e ON e.id = o.id_vendedor
                    LEFT JOIN modelos m ON m.id = o.id_modelo
                    WHERE o.estado = 'facturado'
                      AND o.facturado_en >= :desde AND o.facturado_en < :hasta
                    """.trimIndent(),
                )
                if (idVendedor != null) {
                    append(" AND o.id_vendedor = :idVendedor")
                }
            }
        val rows =
            jdbc.query(sql, parametros(periodo).addValue("idVendedor", idVendedor)) { rs, _ ->
                VentaRow(
                    id = rs.getLong("id"),
                    monto = rs.getBigDecimal("monto"),
                    cantidad = rs.getInt("cantidad"),
                    dcto = rs.getBigDecimal("dcto"),
                    mes = rs.getString("mes"),
                    idVendedor = rs.getLong("id_vendedor"),
                    vendedor = rs.getString("vendedor") ?: "Sin vendedor",
                    modelo = rs.getString("modelo"),
                )
            }
        val montoTotal = rows.sumMonto { it.monto }
        return ReporteVentasDto(
            montoTotal = montoTotal.toPlainString(),
            unidadesTotal = rows.sumOf { it.cantidad },
            operacionesCount = rows.size,
            ticketPromedio =
                if (rows.isEmpty()) {
                    null
                } else {
                    montoTotal.divide(BigDecimal(rows.size), 2, RoundingMode.HALF_UP).toPlainString()
                },
            dctoPromedio = promedio(rows.mapNotNull { it.dcto })?.toPlainString(),
            porMes =
                rows.groupBy { it.mes }.toSortedMap().map { (mes, grupo) ->
                    VentasPorMesDto(
                        mes = mes,
                        monto = grupo.sumMonto { it.monto }.toPlainString(),
                        unidades = grupo.sumOf { it.cantidad },
                        operaciones = grupo.size,
                    )
                },
            porVendedor =
                rows.groupBy { it.idVendedor }.map { (id, grupo) ->
                    VentasPorVendedorDto(
                        idVendedor = id,
                        nombre = grupo.first().vendedor,
                        monto = grupo.sumMonto { it.monto }.toPlainString(),
                        unidades = grupo.sumOf { it.cantidad },
                    )
                },
            porModelo =
                rows.groupBy { it.modelo ?: "Sin modelo" }.map { (modelo, grupo) ->
                    VentasPorModeloDto(
                        modelo = modelo,
                        unidades = grupo.sumOf { it.cantidad },
                        monto = grupo.sumMonto { it.monto }.toPlainString(),
                    )
                },
        )
    }

    // ── Pipeline ───────────────────────────────────────────────

    private data class PipelineRow(
        val id: Long,
        val estado: String,
        val monto: BigDecimal?,
        val empresa: String,
        val vendedor: String?,
        val esCalidda: Boolean,
        val entradaEtapa: LocalDateTime,
        val ultimaActividad: LocalDateTime,
    )

    @Transactional(readOnly = true)
    fun pipeline(): ReportePipelineDto {
        val rows =
            jdbc.query(
                """
                SELECT o.id, o.estado::text AS estado, o.monto_total, emp.razon_social,
                       CONCAT(e.nombres, ' ', e.apellidos) AS vendedor,
                       COALESCE(fin.es_default, false) AS es_calidda,
                       COALESCE(
                           (SELECT MAX(l.changed_at) FROM oportunidad_estados_log l WHERE l.id_oportunidad = o.id),
                           o.created_at
                       ) AS entrada_etapa,
                       GREATEST(
                           o.created_at,
                           COALESCE((SELECT MAX(l.changed_at) FROM oportunidad_estados_log l WHERE l.id_oportunidad = o.id), o.created_at),
                           COALESCE((SELECT MAX(ev.fecha_ocurrencia) FROM eventos ev WHERE ev.id_oportunidad = o.id AND ev.estado = 'ocurrido'), o.created_at),
                           COALESCE((SELECT MAX(t.updated_at) FROM tareas t WHERE t.id_oportunidad = o.id AND t.estado_accion = 'completada'), o.created_at)
                       ) AS ultima_actividad
                FROM oportunidades o
                JOIN empresas emp ON emp.id = o.id_empresa
                LEFT JOIN empleados e ON e.id = o.id_vendedor
                LEFT JOIN financiadoras fin ON fin.id = o.id_financiadora
                WHERE o.estado IN ('evaluacion_calidda', 'documentos_legales')
                """.trimIndent(),
                MapSqlParameterSource(),
            ) { rs, _ ->
                PipelineRow(
                    id = rs.getLong("id"),
                    estado = rs.getString("estado"),
                    monto = rs.getBigDecimal("monto_total"),
                    empresa = rs.getString("razon_social"),
                    vendedor = rs.getString("vendedor"),
                    esCalidda = rs.getBoolean("es_calidda"),
                    entradaEtapa = rs.getTimestamp("entrada_etapa").toLocalDateTime(),
                    ultimaActividad = rs.getTimestamp("ultima_actividad").toLocalDateTime(),
                )
            }
        val ahora = LocalDateTime.now()
        val porEtapa =
            rows.groupBy { it.estado }.map { (etapa, grupo) ->
                val dias = grupo.map { ChronoUnit.DAYS.between(it.entradaEtapa, ahora).toInt() }
                val promedio = if (dias.isEmpty()) 0 else dias.average().toInt()
                EtapaPipelineDto(
                    etapa = etapa,
                    count = grupo.size,
                    valor = grupo.sumMonto { it.monto ?: BigDecimal.ZERO }.toPlainString(),
                    tiempoPromedioDias = promedio,
                    oportunidadesSobrePromedio = dias.count { it > promedio },
                )
            }
        val totalActivo = rows.sumMonto { it.monto ?: BigDecimal.ZERO }
        val valorCalidda = rows.filter { it.esCalidda }.sumMonto { it.monto ?: BigDecimal.ZERO }
        val concentracion =
            if (totalActivo.signum() == 0) {
                BigDecimal.ZERO.setScale(2)
            } else {
                valorCalidda.multiply(BigDecimal(100)).divide(totalActivo, 2, RoundingMode.HALF_UP)
            }
        val sinActividad =
            rows
                .map { it to ChronoUnit.DAYS.between(it.ultimaActividad, ahora).toInt() }
                .filter { (_, dias) -> dias >= DIAS_SIN_ACTIVIDAD }
                .sortedByDescending { (_, dias) -> dias }
                .map { (row, dias) ->
                    OportunidadSinActividadDto(
                        id = row.id,
                        empresa = row.empresa,
                        estado = row.estado,
                        diasSinActividad = dias,
                        montoTotal = row.monto?.toPlainString(),
                        vendedor = row.vendedor,
                    )
                }
        return ReportePipelineDto(
            porEtapa = porEtapa,
            totalActivo = totalActivo.toPlainString(),
            concentracionCaliddaPct = concentracion.toPlainString(),
            oportunidadesSinActividad = sinActividad,
        )
    }

    // ── Equipo ─────────────────────────────────────────────────

    @Transactional(readOnly = true)
    fun equipo(periodo: PeriodoReporte): List<ReporteEquipoItemDto> {
        val vendedores = mutableMapOf<Long, String>()
        jdbc.query(
            """
            SELECT id, CONCAT(nombres, ' ', apellidos) AS nombre
            FROM empleados
            WHERE activo = true AND (rol = 'vendedor' OR id IN (SELECT DISTINCT id_vendedor FROM oportunidades))
            ORDER BY id
            """.trimIndent(),
            MapSqlParameterSource(),
        ) { rs -> vendedores[rs.getLong("id")] = rs.getString("nombre") }

        val activas =
            agrupadoPorVendedor(
                """
                SELECT id_vendedor AS clave, COUNT(*) AS n, COALESCE(SUM(monto_total), 0) AS monto, AVG(dcto) AS extra
                FROM oportunidades
                WHERE estado IN ('evaluacion_calidda', 'documentos_legales')
                GROUP BY id_vendedor
                """.trimIndent(),
                MapSqlParameterSource(),
            )
        // Cerradas del periodo: misma fuente de "cuando se facturo" que `ventas`
        // e `InicioDao` — `oportunidades.facturado_en` (V33). `extra` = dias entre
        // la creacion y la facturacion, para velocidadPromedioDias.
        val cerradas =
            agrupadoPorVendedor(
                """
                SELECT o.id_vendedor AS clave, COUNT(*) AS n, COALESCE(SUM(o.monto_total), 0) AS monto,
                       AVG(EXTRACT(EPOCH FROM (o.facturado_en - o.created_at)) / 86400.0) AS extra
                FROM oportunidades o
                WHERE o.estado = 'facturado' AND o.facturado_en >= :desde AND o.facturado_en < :hasta
                GROUP BY o.id_vendedor
                """.trimIndent(),
                parametros(periodo),
            )
        val tareas =
            agrupadoPorVendedor(
                """
                SELECT id_asignado AS clave,
                       COUNT(*) FILTER (WHERE estado_accion = 'completada' AND updated_at >= NOW() - INTERVAL '7 days') AS n,
                       0 AS monto,
                       COUNT(*) FILTER (WHERE estado_accion = 'pendiente' AND fecha_ejecucion < NOW()) AS extra
                FROM tareas
                WHERE id_asignado IS NOT NULL
                GROUP BY id_asignado
                """.trimIndent(),
                MapSqlParameterSource(),
            )
        val ultimoRegistro = mutableMapOf<Long, LocalDateTime>()
        jdbc.query(
            """
            SELECT created_by AS clave, MAX(f) AS ultimo
            FROM (
                SELECT created_by, created_at AS f FROM tareas
                UNION ALL SELECT created_by, created_at AS f FROM eventos
                UNION ALL SELECT created_by, created_at AS f FROM oportunidades
            ) registros
            GROUP BY created_by
            """.trimIndent(),
            MapSqlParameterSource(),
        ) { rs -> ultimoRegistro[rs.getLong("clave")] = rs.getTimestamp("ultimo").toLocalDateTime() }

        val ahora = LocalDateTime.now()
        return vendedores.map { (id, nombre) ->
            val pipeline = activas[id]
            val cerrado = cerradas[id]
            val tarea = tareas[id]
            ReporteEquipoItemDto(
                vendedor = VendedorRefDto(id = id, nombre = nombre),
                oportunidadesActivas = pipeline?.n ?: 0,
                valorPipeline = (pipeline?.monto ?: BigDecimal.ZERO).toPlainString(),
                oportunidadesCerradasMes = cerrado?.n ?: 0,
                valorCerradoMes = (cerrado?.monto ?: BigDecimal.ZERO).toPlainString(),
                tareasCompletadasSemana = tarea?.n ?: 0,
                tareasVencidas = tarea?.extra?.toInt() ?: 0,
                diasUltimoRegistro = ultimoRegistro[id]?.let { ChronoUnit.DAYS.between(it, ahora).toInt() },
                dctoPromedio = pipeline?.extra?.setScale(2, RoundingMode.HALF_UP)?.toPlainString(),
                velocidadPromedioDias = cerrado?.extra?.toInt(),
            )
        }
    }

    // ── Velocidad por etapa ────────────────────────────────────

    @Transactional(readOnly = true)
    fun velocidadEtapas(): Pair<List<VelocidadEtapaDto>, String?> {
        val duraciones = mutableMapOf<String, MutableList<Double>>()
        jdbc.query(
            """
            SELECT etapa, dias FROM (
                SELECT estado_nuevo::text AS etapa,
                       EXTRACT(EPOCH FROM (LEAD(changed_at) OVER (PARTITION BY id_oportunidad ORDER BY changed_at, id) - changed_at)) / 86400.0 AS dias
                FROM oportunidad_estados_log
            ) transiciones
            WHERE dias IS NOT NULL AND etapa IN ('evaluacion_calidda', 'documentos_legales')
            """.trimIndent(),
            MapSqlParameterSource(),
        ) { rs -> duraciones.getOrPut(rs.getString("etapa")) { mutableListOf() }.add(rs.getDouble("dias")) }

        val etapas =
            duraciones.map { (etapa, dias) ->
                val ordenados = dias.sorted()
                VelocidadEtapaDto(
                    etapa = etapa,
                    diasPromedio = dias.average().toInt(),
                    diasMediana = ordenados[ordenados.size / 2].toInt(),
                    muestra = dias.size,
                )
            }
        val advertencia =
            if (etapas.isEmpty() || etapas.any { it.muestra < MUESTRA_MINIMA }) {
                "Muestra reducida. Los promedios pueden no ser representativos con menos de 10 operaciones por etapa."
            } else {
                null
            }
        return etapas to advertencia
    }

    // ── Prospeccion ────────────────────────────────────────────

    private data class ProspectoRow(
        val idEmpresa: Long,
        val origen: String?,
        val convertida: Boolean,
        val diasConversion: Double?,
    )

    /**
     * Embudo de prospeccion (reglas_negocio.md §10.3). El criterio de ENTRADA por
     * hitos es exactamente el mismo que el de AVANCE: hito del catalogo
     * `ocurrido`, sin `id_oportunidad`. Sin esos dos filtros una empresa con un
     * hito solo agendado (`pendiente`) o `descartado` entraba en `ingresadas`
     * pero no podia sumar nunca en `hito_N_completado` ni en `convertidas`, con
     * lo que la tasa de conversion salia siempre por debajo de la real.
     */
    @Transactional(readOnly = true)
    fun prospeccion(
        periodo: PeriodoReporte,
        idVendedor: Long?,
    ): ReporteProspeccionDto {
        val sql =
            buildString {
                append(
                    """
                    SELECT emp.id AS id_empresa, emp.origen_lead::text AS origen,
                           EXISTS (SELECT 1 FROM oportunidades o WHERE o.id_empresa = emp.id) AS convertida,
                           (SELECT EXTRACT(EPOCH FROM (MIN(o.created_at) - emp.created_at)) / 86400.0
                            FROM oportunidades o WHERE o.id_empresa = emp.id) AS dias_conversion
                    FROM empresas emp
                    WHERE emp.created_at >= :desde AND emp.created_at < :hasta
                      AND emp.en_cartera_maestra = false
                      AND (emp.estado_cartera IN ('prospeccion', 'oportunidad_activa', 'cliente')
                           OR EXISTS (SELECT 1 FROM eventos e2
                                      JOIN catalogo_eventos ce ON ce.id = e2.id_catalogo_evento
                                      WHERE e2.id_empresa = emp.id AND ce.es_hito_prospeccion = true
                                        AND e2.id_oportunidad IS NULL AND e2.estado = 'ocurrido'))
                    """.trimIndent(),
                )
                if (idVendedor != null) {
                    append(" AND emp.id_vendedor = :idVendedor")
                }
            }
        val rows =
            jdbc.query(sql, parametros(periodo).addValue("idVendedor", idVendedor)) { rs, _ ->
                ProspectoRow(
                    idEmpresa = rs.getLong("id_empresa"),
                    origen = rs.getString("origen"),
                    convertida = rs.getBoolean("convertida"),
                    diasConversion = rs.getBigDecimal("dias_conversion")?.toDouble(),
                )
            }
        // Hitos completados por empresa, en el orden del catalogo (1, 2, 3).
        val hitosPorEmpresa = mutableMapOf<Long, MutableSet<Int>>()
        if (rows.isNotEmpty()) {
            jdbc.query(
                """
                SELECT e.id_empresa, orden.posicion
                FROM eventos e
                JOIN (
                    SELECT id, ROW_NUMBER() OVER (ORDER BY id) AS posicion
                    FROM catalogo_eventos WHERE es_hito_prospeccion = true
                ) orden ON orden.id = e.id_catalogo_evento
                WHERE e.id_empresa IN (:ids) AND e.id_oportunidad IS NULL AND e.estado = 'ocurrido'
                GROUP BY e.id_empresa, orden.posicion
                """.trimIndent(),
                MapSqlParameterSource("ids", rows.map { it.idEmpresa }),
            ) { rs ->
                hitosPorEmpresa.getOrPut(rs.getLong("id_empresa")) { mutableSetOf() }.add(rs.getInt("posicion"))
            }
        }
        val convertidas = rows.count { it.convertida }
        val tiempos = rows.mapNotNull { it.diasConversion }
        return ReporteProspeccionDto(
            ingresadas = rows.size,
            hito1Completado = rows.count { hitosPorEmpresa[it.idEmpresa]?.contains(1) == true },
            hito2Completado = rows.count { hitosPorEmpresa[it.idEmpresa]?.contains(2) == true },
            hito3Completado = rows.count { hitosPorEmpresa[it.idEmpresa]?.contains(3) == true },
            convertidasAOportunidad = convertidas,
            tasaConversionPct = porcentaje(convertidas, rows.size),
            tiempoPromedioConversionDias = tiempos.takeIf { it.isNotEmpty() }?.average()?.toInt(),
            porOrigenLead =
                rows.groupBy { it.origen ?: "sin_origen" }.map { (origen, grupo) ->
                    val convertidasOrigen = grupo.count { it.convertida }
                    ProspeccionPorOrigenDto(
                        origen = origen,
                        ingresadas = grupo.size,
                        convertidas = convertidasOrigen,
                        tasaPct = porcentaje(convertidasOrigen, grupo.size),
                    )
                },
        )
    }

    // ── Descuentos ─────────────────────────────────────────────

    private data class DescuentoRow(
        val vendedor: String,
        val dcto: BigDecimal?,
    )

    @Transactional(readOnly = true)
    fun descuentos(periodo: PeriodoReporte): ReporteDescuentosDto {
        val rows =
            jdbc.query(
                """
                SELECT CONCAT(e.nombres, ' ', e.apellidos) AS vendedor, o.dcto
                FROM oportunidades o
                LEFT JOIN empleados e ON e.id = o.id_vendedor
                WHERE o.estado != 'cerrado' AND o.created_at >= :desde AND o.created_at < :hasta
                """.trimIndent(),
                parametros(periodo),
            ) { rs, _ ->
                DescuentoRow(vendedor = rs.getString("vendedor") ?: "Sin vendedor", dcto = rs.getBigDecimal("dcto"))
            }
        return ReporteDescuentosDto(
            dctoPromedioGlobal = promedio(rows.map { it.dcto ?: BigDecimal.ZERO })?.toPlainString(),
            porVendedor =
                rows.groupBy { it.vendedor }.map { (vendedor, grupo) ->
                    val conDcto = grupo.filter { (it.dcto ?: BigDecimal.ZERO).signum() > 0 }
                    DescuentosPorVendedorDto(
                        vendedor = vendedor,
                        dctoPromedio = promedio(grupo.map { it.dcto ?: BigDecimal.ZERO })?.toPlainString(),
                        operacionesSinDcto = grupo.size - conDcto.size,
                        operacionesConDcto = conDcto.size,
                        dctoMaximoAplicado = grupo.mapNotNull { it.dcto }.maxOrNull()?.toPlainString(),
                    )
                },
        )
    }

    // ── helpers ────────────────────────────────────────────────

    private data class AgrupadoRow(
        val n: Int,
        val monto: BigDecimal,
        val extra: BigDecimal?,
    )

    private fun agrupadoPorVendedor(
        sql: String,
        params: MapSqlParameterSource,
    ): Map<Long, AgrupadoRow> {
        val resultado = mutableMapOf<Long, AgrupadoRow>()
        jdbc.query(sql, params) { rs: ResultSet ->
            resultado[rs.getLong("clave")] =
                AgrupadoRow(
                    n = rs.getInt("n"),
                    monto = rs.getBigDecimal("monto") ?: BigDecimal.ZERO,
                    extra = rs.getBigDecimal("extra"),
                )
        }
        return resultado
    }

    private fun parametros(periodo: PeriodoReporte): MapSqlParameterSource =
        MapSqlParameterSource()
            .addValue("desde", periodo.desde.atStartOfDay())
            .addValue("hasta", periodo.hastaExclusivo.atStartOfDay())

    private companion object {
        const val DIAS_SIN_ACTIVIDAD = 7
        const val MUESTRA_MINIMA = 10
    }
}

// ── Aritmetica de dinero ───────────────────────────────────────
// Funciones puras, `internal` y de primer nivel a proposito: son la unica parte
// de este archivo verificable sin Postgres y por eso tienen tests reales
// (ReporteAritmeticaTest). Siguen en este archivo y en este paquete, asi que las
// llamadas dentro de ReporteService no cambian.

/** Suma montos y redondea UNA sola vez, sobre el total (escala 2, HALF_UP). */
internal fun <T> List<T>.sumMonto(selector: (T) -> BigDecimal): BigDecimal =
    fold(BigDecimal.ZERO) { acc, item -> acc + selector(item) }.setScale(2, RoundingMode.HALF_UP)

/** Promedio con escala 2 (HALF_UP). null si no hay valores: "sin datos" no es "cero". */
internal fun promedio(valores: List<BigDecimal>): BigDecimal? =
    valores
        .takeIf { it.isNotEmpty() }
        ?.fold(BigDecimal.ZERO) { acc, valor -> acc + valor }
        ?.divide(BigDecimal(valores.size), 2, RoundingMode.HALF_UP)

/** Porcentaje con escala 2 (HALF_UP). Total 0 -> "0.00", sin dividir por cero. */
internal fun porcentaje(
    parte: Int,
    total: Int,
): String =
    if (total == 0) {
        "0.00"
    } else {
        BigDecimal(parte).multiply(BigDecimal(100)).divide(BigDecimal(total), 2, RoundingMode.HALF_UP).toPlainString()
    }
