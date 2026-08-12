package pe.quantum.crm.domain.reportes

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.annotation.Transactional
import pe.quantum.crm.support.IntegrationTestBase
import java.time.LocalDate

/**
 * Cobertura de `ReporteService` contra Postgres real (hallazgo F2d / E2: ~900
 * lineas de SQL nativo agregado sin ningun test). Igual que
 * `ReporteServiceIntegrationTest`, mockear el `NamedParameterJdbcTemplate` solo
 * probaria el mock, no la consulta — es una tautologia sin valor para SQL
 * agregado. La unica prueba honesta es contra una base real, de ahi
 * `@Tag("integration")`.
 *
 * NO se ejecutaron en la maquina que escribio este archivo: Testcontainers esta
 * roto por un problema de Docker 29 (ver memoria
 * testcontainers-docker29-blocker). Se validan en CI mediante la tarea
 * `integrationTest`. Las aserciones se derivaron leyendo `ReporteService.kt`
 * linea por linea, no ejecutando el codigo.
 *
 * Cubre los 5 escenarios del plan (docs/plan-ejecucion-subagentes.md, agente E):
 *   1. `ventas`  — rango de fechas: una operacion dentro, otra fuera.
 *   2. `ventas`  — regresion del hallazgo [Critico] ya corregido: una oportunidad
 *      facturada SIN fila en `oportunidad_estados_log` debe seguir contando,
 *      porque la fuente real es `oportunidades.facturado_en` (V33).
 *   3. `equipo`  — desglose por vendedor: dos vendedores con cifras distintas no
 *      se mezclan entre si.
 *   4. `prospeccion` — el criterio de entrada al embudo exige `estado = 'ocurrido'`
 *      Y `id_oportunidad IS NULL` (reglas_negocio.md §10.3); un hito agendado
 *      (`pendiente`) no cuenta como ingresada.
 *   5. `descuentos` — el promedio sobre un conjunto conocido: un `dcto` NULL cuenta
 *      como 0% y las oportunidades `cerrado` quedan fuera del universo. Confirmado
 *      como comportamiento intencional por el dueño del proyecto (hallazgo [Medio]
 *      E4, no era un bug).
 */
@Tag("integration")
@SpringBootTest
@Transactional
class ReporteServiceSqlIntegrationTest : IntegrationTestBase() {
    @Autowired
    lateinit var jdbcTemplate: JdbcTemplate

    @Autowired
    lateinit var reporteService: ReporteService

    private val periodoMayo2019 = PeriodoReporte.de(LocalDate.of(2019, 5, 1), LocalDate.of(2019, 5, 31))

    // ── Semilla ────────────────────────────────────────────────
    // Deliberadamente independiente de la de ReporteServiceIntegrationTest: cada
    // archivo de test es su propia unidad y no debe depender de que el otro exista
    // ni de que mantenga sus helpers privados sin cambios.

    private fun id(sql: String): Long = jdbcTemplate.queryForObject(sql, Long::class.java)!!

    private fun crearVendedor(sufijo: String): Long =
        id(
            "INSERT INTO empleados (nombres, apellidos, email, rol) " +
                "VALUES ('Sql', 'Test$sufijo', 'sql.test$sufijo@quantum.pe', 'vendedor') RETURNING id",
        )

    private fun crearEmpresa(
        sufijo: String,
        idVendedor: Long,
        creadaEn: String,
        estadoCartera: String = "no_contactado",
    ): Long =
        id(
            """
            INSERT INTO empresas
                (ruc, razon_social, actividad_econ, id_vendedor, estado_sunat, condicion_sunat,
                 direccion_fiscal, origen_lead, estado_cartera, created_at, created_by, updated_by)
            VALUES
                ('209$sufijo', 'Sql Test $sufijo S.A.C.', 'Transporte', $idVendedor, 'ACTIVO', 'HABIDO',
                 'Av. Sql Test 1', 'visita_fria', '$estadoCartera', TIMESTAMP '$creadaEn', $idVendedor, $idVendedor)
            RETURNING id
            """.trimIndent(),
        )

    private fun crearModelo(sufijo: String): Long = id("INSERT INTO modelos (codigo) VALUES ('SQL-$sufijo') RETURNING id")

    private fun crearFinanciadora(sufijo: String): Long =
        id("INSERT INTO financiadoras (nombre) VALUES ('Financiadora sql $sufijo') RETURNING id")

    @Suppress("LongParameterList")
    private fun crearOportunidad(
        idEmpresa: Long,
        idVendedor: Long,
        idFinanciadora: Long,
        idModelo: Long,
        creadaEn: String,
        estado: String = "facturado",
        facturadoEn: String? = null,
        montoTotal: String = "100000.00",
        cantidad: Int = 2,
        dcto: String? = "0.00",
    ): Long =
        id(
            """
            INSERT INTO oportunidades
                (id_empresa, id_vendedor, id_financiadora, id_modelo, estado, cantidad, precio_unitario,
                 dcto, monto_total, facturado_en, created_at, created_by, updated_by)
            VALUES
                ($idEmpresa, $idVendedor, $idFinanciadora, $idModelo, '$estado', $cantidad, 50000.00,
                 ${dcto ?: "NULL"}, $montoTotal, ${facturadoEn?.let { "TIMESTAMP '$it'" } ?: "NULL"},
                 TIMESTAMP '$creadaEn', $idVendedor, $idVendedor)
            RETURNING id
            """.trimIndent(),
        )

    private fun logFacturado(
        idOportunidad: Long,
        idVendedor: Long,
        changedAt: String,
    ) {
        jdbcTemplate.update(
            """
            INSERT INTO oportunidad_estados_log (id_oportunidad, estado_anterior, estado_nuevo, changed_at, changed_by)
            VALUES ($idOportunidad, 'documentos_legales', 'facturado', TIMESTAMP '$changedAt', $idVendedor)
            """.trimIndent(),
        )
    }

    private fun idHitoProspeccion(posicion: Int): Long =
        id(
            "SELECT id FROM (SELECT id, ROW_NUMBER() OVER (ORDER BY id) AS pos " +
                "FROM catalogo_eventos WHERE es_hito_prospeccion = true) h WHERE pos = $posicion",
        )

    private fun crearHito(
        idEmpresa: Long,
        idVendedor: Long,
        posicionHito: Int,
        estado: String,
        idOportunidad: Long? = null,
    ) {
        val ocurrencia = if (estado == "ocurrido") "TIMESTAMP '2019-05-10 10:00:00'" else "NULL"
        jdbcTemplate.update(
            """
            INSERT INTO eventos
                (id_oportunidad, id_empresa, id_catalogo_evento, es_personalizado, estado,
                 fecha_ocurrencia, created_by, updated_by)
            VALUES
                (${idOportunidad ?: "NULL"}, $idEmpresa, ${idHitoProspeccion(posicionHito)}, false, '$estado',
                 $ocurrencia, $idVendedor, $idVendedor)
            """.trimIndent(),
        )
    }

    // ── 1. /reportes/ventas con rango de fechas ──────────────────

    @Test
    fun `ventas con rango de fechas solo cuenta la operacion facturada dentro del rango`() {
        val vendedor = crearVendedor("V1")
        val empresa = crearEmpresa("11111111", vendedor, "2019-04-01 09:00:00")
        val financiadora = crearFinanciadora("V1")
        val modelo = crearModelo("V1")

        // Dentro del rango (mayo 2019): debe contar.
        val dentro =
            crearOportunidad(
                idEmpresa = empresa,
                idVendedor = vendedor,
                idFinanciadora = financiadora,
                idModelo = modelo,
                creadaEn = "2019-04-01 09:00:00",
                facturadoEn = "2019-05-15 12:00:00",
                montoTotal = "150000.00",
                cantidad = 3,
            )
        logFacturado(dentro, vendedor, "2019-05-15 12:00:00")

        // Fuera del rango (facturada en abril): no debe contar.
        val fuera =
            crearOportunidad(
                idEmpresa = empresa,
                idVendedor = vendedor,
                idFinanciadora = financiadora,
                idModelo = modelo,
                creadaEn = "2019-03-01 09:00:00",
                facturadoEn = "2019-04-20 12:00:00",
                montoTotal = "999999.00",
                cantidad = 9,
            )
        logFacturado(fuera, vendedor, "2019-04-20 12:00:00")

        val reporte = reporteService.ventas(periodoMayo2019, vendedor)

        assertThat(reporte.operacionesCount).isEqualTo(1)
        assertThat(reporte.montoTotal).isEqualTo("150000.00")
        assertThat(reporte.unidadesTotal).isEqualTo(3)
        assertThat(reporte.porMes).singleElement().satisfies({ assertThat(it.mes).isEqualTo("2019-05") })
    }

    // ── 2. /reportes/ventas sin log de estados (regresion) ───────

    @Test
    fun `ventas cuenta una oportunidad facturada aunque no exista fila en oportunidad_estados_log`() {
        val vendedor = crearVendedor("V2")
        val empresa = crearEmpresa("22222222", vendedor, "2019-04-05 09:00:00")
        val oportunidad =
            crearOportunidad(
                idEmpresa = empresa,
                idVendedor = vendedor,
                idFinanciadora = crearFinanciadora("V2"),
                idModelo = crearModelo("V2"),
                creadaEn = "2019-04-05 09:00:00",
                facturadoEn = "2019-05-20 08:00:00",
                montoTotal = "80000.00",
                cantidad = 1,
            )
        // Deliberadamente SIN logFacturado(oportunidad, ...): antes del fix, el
        // reporte derivaba la fecha de facturacion de un LATERAL sobre el log con
        // un ON que actuaba como INNER JOIN implicito, y esta fila desaparecia en
        // silencio. Hoy la consulta lee o.facturado_en directamente (V33), asi que
        // debe aparecer igual.
        assertThat(
            jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM oportunidad_estados_log WHERE id_oportunidad = $oportunidad",
                Int::class.java,
            ),
        ).isZero()

        val reporte = reporteService.ventas(periodoMayo2019, vendedor)

        assertThat(reporte.operacionesCount).isEqualTo(1)
        assertThat(reporte.montoTotal).isEqualTo("80000.00")
    }

    // ── 3. /reportes/equipo con desglose por vendedor ────────────

    @Test
    fun `equipo desglosa cifras distintas por cada vendedor sin mezclarlas`() {
        val vendedorA = crearVendedor("E1")
        val vendedorB = crearVendedor("E2")
        val empresaA = crearEmpresa("33333333", vendedorA, "2019-05-01 00:00:00")
        val empresaB = crearEmpresa("44444444", vendedorB, "2019-05-01 00:00:00")

        val oportunidadA =
            crearOportunidad(
                idEmpresa = empresaA,
                idVendedor = vendedorA,
                idFinanciadora = crearFinanciadora("E1"),
                idModelo = crearModelo("E1"),
                creadaEn = "2019-05-01 00:00:00",
                facturadoEn = "2019-05-11 00:00:00",
                montoTotal = "200000.00",
            )
        logFacturado(oportunidadA, vendedorA, "2019-05-11 00:00:00")

        val oportunidadB =
            crearOportunidad(
                idEmpresa = empresaB,
                idVendedor = vendedorB,
                idFinanciadora = crearFinanciadora("E2"),
                idModelo = crearModelo("E2"),
                creadaEn = "2019-05-01 00:00:00",
                facturadoEn = "2019-05-21 00:00:00",
                montoTotal = "50000.00",
            )
        logFacturado(oportunidadB, vendedorB, "2019-05-21 00:00:00")

        val reporte = reporteService.equipo(periodoMayo2019)
        val itemA = reporte.single { it.vendedor.id == vendedorA }
        val itemB = reporte.single { it.vendedor.id == vendedorB }

        assertThat(itemA.oportunidadesCerradasMes).isEqualTo(1)
        assertThat(itemA.valorCerradoMes).isEqualTo("200000.00")
        // 2019-05-01 -> 2019-05-11: 10 dias.
        assertThat(itemA.velocidadPromedioDias).isEqualTo(10)

        assertThat(itemB.oportunidadesCerradasMes).isEqualTo(1)
        assertThat(itemB.valorCerradoMes).isEqualTo("50000.00")
        // 2019-05-01 -> 2019-05-21: 20 dias. Si el agrupado se mezclara entre
        // vendedores, esta cifra saldria contaminada por la de A (10 dias) o
        // sumaria ambos montos.
        assertThat(itemB.velocidadPromedioDias).isEqualTo(20)
    }

    // ── 4. /reportes/prospeccion con el criterio correcto del embudo ─

    @Test
    fun `prospeccion exige hito ocurrido y sin oportunidad para contar como ingresada`() {
        val vendedor = crearVendedor("P1")

        // Caso 1: hito ocurrido, sin oportunidad -> SI cuenta.
        val empresaOcurrida = crearEmpresa("55555555", vendedor, "2019-05-05 09:00:00")
        crearHito(empresaOcurrida, vendedor, posicionHito = 1, estado = "ocurrido")

        // Caso 2: hito solo agendado (pendiente) -> NO cuenta, aunque exista.
        val empresaPendiente = crearEmpresa("66666666", vendedor, "2019-05-06 09:00:00")
        crearHito(empresaPendiente, vendedor, posicionHito = 1, estado = "pendiente")

        // Caso 3: hito ocurrido pero ligado a una oportunidad -> NO cuenta segun
        // el filtro de id_oportunidad IS NULL (reglas §10.3).
        val empresaConOportunidad = crearEmpresa("77777777", vendedor, "2019-05-07 09:00:00")
        val oportunidad =
            crearOportunidad(
                idEmpresa = empresaConOportunidad,
                idVendedor = vendedor,
                idFinanciadora = crearFinanciadora("P1"),
                idModelo = crearModelo("P1"),
                creadaEn = "2019-05-08 09:00:00",
                estado = "prospeccion",
                facturadoEn = null,
            )
        crearHito(empresaConOportunidad, vendedor, posicionHito = 1, estado = "ocurrido", idOportunidad = oportunidad)

        val reporte = reporteService.prospeccion(periodoMayo2019, vendedor)

        // Solo la empresa del caso 1 entra al embudo.
        assertThat(reporte.ingresadas).isEqualTo(1)
        assertThat(reporte.hito1Completado).isEqualTo(1)
    }

    // ── 5. /reportes/descuentos con el promedio ──────────────────

    @Test
    fun `descuentos calcula el promedio global sobre un conjunto conocido`() {
        val vendedor = crearVendedor("D1")
        val empresa = crearEmpresa("88888888", vendedor, "2019-05-05 09:00:00")
        val financiadora = crearFinanciadora("D1")
        val modelo = crearModelo("D1")

        // Tres operaciones con dcto explicito: 10, 20 y 0. Promedio esperado: 10.00.
        crearOportunidad(
            idEmpresa = empresa,
            idVendedor = vendedor,
            idFinanciadora = financiadora,
            idModelo = modelo,
            creadaEn = "2019-05-05 09:00:00",
            estado = "prospeccion",
            dcto = "10.00",
        )
        crearOportunidad(
            idEmpresa = empresa,
            idVendedor = vendedor,
            idFinanciadora = financiadora,
            idModelo = modelo,
            creadaEn = "2019-05-06 09:00:00",
            estado = "prospeccion",
            dcto = "20.00",
        )
        crearOportunidad(
            idEmpresa = empresa,
            idVendedor = vendedor,
            idFinanciadora = financiadora,
            idModelo = modelo,
            creadaEn = "2019-05-07 09:00:00",
            estado = "prospeccion",
            dcto = "0.00",
        )

        val reporte = reporteService.descuentos(periodoMayo2019)
        val itemVendedor = reporte.porVendedor.single { it.vendedor.contains("D1") }

        assertThat(reporte.dctoPromedioGlobal).isEqualTo("10.00")
        assertThat(itemVendedor.dctoPromedio).isEqualTo("10.00")
        assertThat(itemVendedor.operacionesConDcto).isEqualTo(2)
        assertThat(itemVendedor.operacionesSinDcto).isEqualTo(1)
        assertThat(itemVendedor.dctoMaximoAplicado).isEqualTo("20.00")
    }

    /**
     * Comportamiento confirmado como intencional por el dueño del proyecto
     * (2026-08-12), no es un bug: un `dcto` NULL cuenta como 0% en el promedio, no
     * se excluye. "Sin descuento" y "0% de descuento explícito" son lo mismo para
     * este reporte.
     */
    @Test
    fun `descuentos trata un dcto NULL como cero para el promedio`() {
        val vendedor = crearVendedor("D2")
        val empresa = crearEmpresa("99999999", vendedor, "2019-05-05 09:00:00")
        val financiadora = crearFinanciadora("D2")
        val modelo = crearModelo("D2")

        // Un 20% explicito y un NULL (dcto no registrado).
        crearOportunidad(
            idEmpresa = empresa,
            idVendedor = vendedor,
            idFinanciadora = financiadora,
            idModelo = modelo,
            creadaEn = "2019-05-05 09:00:00",
            estado = "prospeccion",
            dcto = "20.00",
        )
        crearOportunidad(
            idEmpresa = empresa,
            idVendedor = vendedor,
            idFinanciadora = financiadora,
            idModelo = modelo,
            creadaEn = "2019-05-06 09:00:00",
            estado = "prospeccion",
            dcto = null,
        )

        val reporte = reporteService.descuentos(periodoMayo2019)
        val itemVendedor = reporte.porVendedor.single { it.vendedor.contains("D2") }

        // (20 + 0) / 2 = 10.00: el NULL cuenta como cero en el promedio.
        assertThat(itemVendedor.dctoPromedio).isEqualTo("10.00")
        assertThat(itemVendedor.operacionesSinDcto).isEqualTo(1)
        assertThat(itemVendedor.operacionesConDcto).isEqualTo(1)
    }

    /**
     * Comportamiento confirmado como intencional por el dueño del proyecto
     * (2026-08-12), no es un bug: las oportunidades `cerrado` quedan fuera de este
     * reporte, igual que del resto de calculos de descuentos.
     */
    @Test
    fun `descuentos excluye las oportunidades cerradas`() {
        val vendedor = crearVendedor("D3")
        val empresa = crearEmpresa("99999977", vendedor, "2019-05-05 09:00:00")
        val financiadora = crearFinanciadora("D3")
        val modelo = crearModelo("D3")

        crearOportunidad(
            idEmpresa = empresa,
            idVendedor = vendedor,
            idFinanciadora = financiadora,
            idModelo = modelo,
            creadaEn = "2019-05-05 09:00:00",
            estado = "cerrado",
            dcto = "15.00",
        )

        val reporte = reporteService.descuentos(periodoMayo2019)

        assertThat(reporte.porVendedor.none { it.vendedor.contains("D3") }).isTrue()
    }
}
