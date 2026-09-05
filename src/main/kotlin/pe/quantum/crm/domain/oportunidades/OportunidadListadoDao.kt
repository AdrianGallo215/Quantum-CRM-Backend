package pe.quantum.crm.domain.oportunidades

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Component
import pe.quantum.crm.domain.oportunidades.dto.OportunidadFiltros
import pe.quantum.crm.shared.enums.EstadoOportunidad
import pe.quantum.crm.shared.security.UsuarioActual

/** Una pagina de ids ya ordenada, con el total de filas que pasan el filtro completo. */
data class PaginaOrdenada(
    val ids: List<Long>,
    val total: Long,
)

/**
 * Rama de consulta nativa del listado de oportunidades (D29 de
 * `plan-07-mapa-retirar-columnas.md`).
 *
 * `cantidad` y `monto_total` dejaron de ser columnas de `oportunidades`
 * mantenidas al dia por la sincronizacion de D21: son agregados de
 * `oportunidad_items`. Ordenar por ellos exige una subconsulta correlacionada,
 * que no se expresa de forma limpia con `CriteriaBuilder` (seria una TERCERA
 * reimplementacion de la formula de dinero, con una tecnica distinta de las otras
 * dos). Se resuelve con SQL nativo parametrizado, mismo patron que
 * `reportes`/`inicio`.
 *
 * Esto solo decide QUE ids entran en la pagina y en que orden. Las entidades las
 * recupera por JPA `OportunidadServiceImpl`, que las pasa por su `toDtos()` de
 * siempre: la construccion del DTO no se duplica.
 */
@Component
class OportunidadListadoDao(
    private val jdbc: NamedParameterJdbcTemplate,
    private val visibilidad: OportunidadVisibilidad,
) {
    /**
     * Ids de la pagina, ordenados por el agregado `campo` (`cantidad` o
     * `montoTotal`), y total de la consulta completa para el envelope.
     *
     * El desempate por id evita que dos oportunidades con el mismo agregado (dos
     * sin items, p. ej.) se repitan o se pierdan entre paginas: sin el, el orden
     * de las filas empatadas no esta definido y cada pagina lo resuelve distinto.
     */
    @Suppress("LongParameterList") // Son los mismos parametros del listado, sin envolver.
    fun paginaOrdenadaPorAgregado(
        filtros: OportunidadFiltros,
        estado: EstadoOportunidad?,
        usuario: UsuarioActual,
        campo: String,
        ascendente: Boolean,
        limite: Int,
        desplazamiento: Long,
    ): PaginaOrdenada {
        val params = MapSqlParameterSource()
        val where = filtrosSql(filtros, estado, usuario, params)
        val total =
            jdbc.queryForObject("SELECT COUNT(*) FROM oportunidades o WHERE $where", params, Long::class.java) ?: 0L
        if (total == 0L) {
            return PaginaOrdenada(emptyList(), 0L)
        }
        params.addValue("limite", limite)
        params.addValue("desplazamiento", desplazamiento)
        val sql =
            """
            SELECT o.id
            FROM oportunidades o
            WHERE $where
            ORDER BY (${subconsultaAgregado(campo)}) ${if (ascendente) "ASC" else "DESC"}, o.id DESC
            LIMIT :limite OFFSET :desplazamiento
            """.trimIndent()
        return PaginaOrdenada(jdbc.queryForList(sql, params, Long::class.java), total)
    }

    /**
     * Subconsulta correlacionada por la que ordena. Sin items el agregado es 0 (no
     * null), para que una oportunidad recien creada no se ordene de forma arbitraria
     * segun el tratamiento de nulos del motor.
     */
    private fun subconsultaAgregado(campo: String): String =
        if (campo == "cantidad") {
            "SELECT COALESCE(SUM(i.cantidad), 0) FROM oportunidad_items i WHERE i.id_oportunidad = o.id"
        } else {
            // La FUENTE DE VERDAD de esta formula es MontoTotal.calcular
            // (domain.oportunidades); aqui se duplica en SQL igual que en `reportes`
            // (D22) e `inicio`, porque el ORDER BY lo resuelve el motor. Si cambia
            // alla, cambia aqui.
            "SELECT COALESCE(SUM(ROUND(i.cantidad * i.precio_venta * " +
                "(1 - COALESCE(i.descuento, 0) / 100), 2)), 0) " +
                "FROM oportunidad_items i WHERE i.id_oportunidad = o.id"
        }

    /**
     * Los MISMOS filtros que `OportunidadServiceImpl.especificacion`, en SQL
     * parametrizado y con las ramas en el mismo orden. La visibilidad no se
     * reimplementa aqui: la resuelve `OportunidadVisibilidad.filtroVisibilidadSql`,
     * que vive pegado al predicado de Criteria equivalente precisamente para que las
     * dos versiones de la regla no se separen.
     */
    private fun filtrosSql(
        filtros: OportunidadFiltros,
        estado: EstadoOportunidad?,
        usuario: UsuarioActual,
        params: MapSqlParameterSource,
    ): String {
        val condiciones = mutableListOf<String>()
        val idsColaboracion = visibilidad.idsColaboracion(usuario)
        val filtroVisibilidad = visibilidad.filtroVisibilidadSql(idsColaboracion, usuario)
        if (filtroVisibilidad != null) {
            condiciones += filtroVisibilidad
            // Se enlazan los dos: cual de ellos referencia el fragmento lo decide
            // `filtroVisibilidadSql`, y un parametro de mas que el SQL no nombra es
            // inofensivo. Un IN vacio no se enlaza nunca (ese caso es `1 <> 1`).
            idsColaboracion?.takeIf { it.isNotEmpty() }?.let { params.addValue("idsColaboracion", it) }
            params.addValue("idUsuario", usuario.id)
        } else if (filtros.idVendedor != null) {
            condiciones += "o.id_vendedor = :idVendedor"
            params.addValue("idVendedor", filtros.idVendedor)
        }
        if (estado != null) {
            condiciones += "o.estado = CAST(:estado AS estado_op_enum)"
            params.addValue("estado", estado.name)
        } else if (!filtros.incluirCerradas) {
            condiciones += "o.estado <> CAST(:estadoExcluido AS estado_op_enum)"
            params.addValue("estadoExcluido", EstadoOportunidad.cerrado.name)
        }
        filtros.idEmpresa?.let {
            condiciones += "o.id_empresa = :idEmpresa"
            params.addValue("idEmpresa", it)
        }
        filtros.idFinanciadora?.let {
            condiciones += "o.id_financiadora = :idFinanciadora"
            params.addValue("idFinanciadora", it)
        }
        return if (condiciones.isEmpty()) "TRUE" else condiciones.joinToString(" AND ")
    }
}
