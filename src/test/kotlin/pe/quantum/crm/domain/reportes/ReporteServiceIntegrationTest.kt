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
 * Consultas de `ReporteService` contra Postgres real. El SQL nativo no se puede
 * verificar de otra forma: mockear el `NamedParameterJdbcTemplate` solo probaria
 * que se llama al mock, que es justo lo que dejo pasar los bugs que estos tests
 * fijan.
 *
 * Cubre dos correcciones:
 *   B1 — la fecha de facturacion sale de `oportunidades.facturado_en` (V33,
 *        "Fuente del computo"), no de un LATERAL sobre el log de estados que
 *        actuaba como INNER JOIN y borraba del reporte, en silencio, toda
 *        oportunidad facturada sin fila en el log. `InicioDao` ya usaba la
 *        columna: el panel del vendedor y el reporte de gerencia daban cifras
 *        distintas para la misma venta.
 *   B4 — el criterio de entrada al embudo de prospeccion aplica los mismos dos
 *        filtros que el conteo de hitos (`id_oportunidad IS NULL` y
 *        `estado = 'ocurrido'`, reglas_negocio.md §10.3). Sin ellos una empresa
 *        con un hito solo agendado entraba en `ingresadas` pero no podia sumar
 *        en `hito_N_completado` ni en `convertidas`, y hundia la conversion.
 *
 * Los datos se siembran a mano con fechas de 2019 y se filtra por vendedor para
 * no mezclarse con lo que dejen otros tests en el contenedor compartido; ademas
 * la clase es transaccional, asi que todo se revierte.
 */
@Tag("integration")
@SpringBootTest
@Transactional
class ReporteServiceIntegrationTest : IntegrationTestBase() {
    @Autowired
    lateinit var jdbcTemplate: JdbcTemplate

    @Autowired
    lateinit var reporteService: ReporteService

    private val periodoMayo2019 = PeriodoReporte.de(LocalDate.of(2019, 5, 1), LocalDate.of(2019, 5, 31))

    // ── Semilla ────────────────────────────────────────────────

    private fun id(sql: String): Long = jdbcTemplate.queryForObject(sql, Long::class.java)!!

    private fun crearVendedor(sufijo: String): Long =
        id(
            "INSERT INTO empleados (nombres, apellidos, email, rol) " +
                "VALUES ('Rep', 'Orte$sufijo', 'rep.orte$sufijo@quantum.pe', 'vendedor') RETURNING id",
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
                ('208$sufijo', 'Reporte Test $sufijo S.A.C.', 'Transporte', $idVendedor, 'ACTIVO', 'HABIDO',
                 'Av. Reporte 1', 'visita_fria', '$estadoCartera', TIMESTAMP '$creadaEn', $idVendedor, $idVendedor)
            RETURNING id
            """.trimIndent(),
        )

    @Suppress("LongParameterList")
    private fun crearOportunidadFacturada(
        idEmpresa: Long,
        idVendedor: Long,
        idFinanciadora: Long,
        idModelo: Long,
        creadaEn: String,
        facturadoEn: String?,
        montoTotal: String = "100000.00",
        cantidad: Int = 2,
    ): Long =
        id(
            """
            INSERT INTO oportunidades
                (id_empresa, id_vendedor, id_financiadora, id_modelo, estado, cantidad, precio_unitario,
                 dcto, monto_total, facturado_en, created_at, created_by, updated_by)
            VALUES
                ($idEmpresa, $idVendedor, $idFinanciadora, $idModelo, 'facturado', $cantidad, 50000.00,
                 0.00, $montoTotal, ${facturadoEn?.let { "TIMESTAMP '$it'" } ?: "NULL"},
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

    private fun crearModelo(sufijo: String): Long = id("INSERT INTO modelos (codigo) VALUES ('REP-$sufijo') RETURNING id")

    private fun crearFinanciadora(sufijo: String): Long =
        id("INSERT INTO financiadoras (nombre) VALUES ('Financiadora reporte $sufijo') RETURNING id")

    // ── B1: fecha de facturacion ───────────────────────────────

    @Test
    fun `ventas incluye una oportunidad facturada sin fila en el log de estados`() {
        val vendedor = crearVendedor("V1")
        val empresa = crearEmpresa("11111111", vendedor, "2019-04-01 09:00:00")
        crearOportunidadFacturada(
            idEmpresa = empresa,
            idVendedor = vendedor,
            idFinanciadora = crearFinanciadora("V1"),
            idModelo = crearModelo("V1"),
            creadaEn = "2019-04-01 09:00:00",
            facturadoEn = "2019-05-10 12:00:00",
            montoTotal = "100000.00",
            cantidad = 2,
        )
        // Sin logFacturado(): es el caso que el LATERAL descartaba en silencio.

        val reporte = reporteService.ventas(periodoMayo2019, vendedor)

        assertThat(reporte.operacionesCount).isEqualTo(1)
        assertThat(reporte.montoTotal).isEqualTo("100000.00")
        assertThat(reporte.unidadesTotal).isEqualTo(2)
        assertThat(reporte.porMes).singleElement().satisfies({ assertThat(it.mes).isEqualTo("2019-05") })
    }

    @Test
    fun `ventas usa facturado_en y no el log cuando ambos discrepan`() {
        val vendedor = crearVendedor("V2")
        val empresa = crearEmpresa("22222222", vendedor, "2019-04-01 09:00:00")
        val oportunidad =
            crearOportunidadFacturada(
                idEmpresa = empresa,
                idVendedor = vendedor,
                idFinanciadora = crearFinanciadora("V2"),
                idModelo = crearModelo("V2"),
                creadaEn = "2019-04-01 09:00:00",
                facturadoEn = "2019-06-02 12:00:00",
            )
        // El log dice mayo, la columna dice junio: manda la columna (V33), que es
        // la misma fuente que usa InicioDao para el panel del vendedor.
        logFacturado(oportunidad, vendedor, "2019-05-20 12:00:00")

        assertThat(reporteService.ventas(periodoMayo2019, vendedor).operacionesCount).isZero()

        val junio = PeriodoReporte.de(LocalDate.of(2019, 6, 1), LocalDate.of(2019, 6, 30))
        assertThat(reporteService.ventas(junio, vendedor).operacionesCount).isEqualTo(1)
    }

    @Test
    fun `equipo cuenta la oportunidad facturada sin log y calcula los dias hasta el cierre`() {
        val vendedor = crearVendedor("V3")
        val empresa = crearEmpresa("33333333", vendedor, "2019-05-01 00:00:00")
        crearOportunidadFacturada(
            idEmpresa = empresa,
            idVendedor = vendedor,
            idFinanciadora = crearFinanciadora("V3"),
            idModelo = crearModelo("V3"),
            creadaEn = "2019-05-01 00:00:00",
            facturadoEn = "2019-05-11 00:00:00",
            montoTotal = "250000.00",
        )

        val item = reporteService.equipo(periodoMayo2019).single { it.vendedor.id == vendedor }

        assertThat(item.oportunidadesCerradasMes).isEqualTo(1)
        assertThat(item.valorCerradoMes).isEqualTo("250000.00")
        // 2019-05-01 -> 2019-05-11: 10 dias exactos, igual que con el LATERAL.
        assertThat(item.velocidadPromedioDias).isEqualTo(10)
    }

    // ── B4: entrada al embudo de prospeccion ───────────────────

    @Test
    fun `prospeccion no cuenta como ingresada una empresa cuyo unico hito esta pendiente`() {
        val vendedor = crearVendedor("P1")
        val empresa = crearEmpresa("44444444", vendedor, "2019-05-05 09:00:00")
        crearHito(empresa, vendedor, posicionHito = 1, estado = "pendiente")

        val reporte = reporteService.prospeccion(periodoMayo2019, vendedor)

        assertThat(reporte.ingresadas).isZero()
        assertThat(reporte.hito1Completado).isZero()
    }

    @Test
    fun `prospeccion no cuenta como ingresada una empresa cuyo unico hito esta descartado`() {
        val vendedor = crearVendedor("P2")
        val empresa = crearEmpresa("55555555", vendedor, "2019-05-05 09:00:00")
        crearHito(empresa, vendedor, posicionHito = 1, estado = "descartado")

        assertThat(reporteService.prospeccion(periodoMayo2019, vendedor).ingresadas).isZero()
    }

    @Test
    fun `prospeccion cuenta la empresa con un hito ocurrido y sin oportunidad`() {
        val vendedor = crearVendedor("P3")
        val empresa = crearEmpresa("66666666", vendedor, "2019-05-05 09:00:00")
        crearHito(empresa, vendedor, posicionHito = 1, estado = "ocurrido")

        val reporte = reporteService.prospeccion(periodoMayo2019, vendedor)

        assertThat(reporte.ingresadas).isEqualTo(1)
        assertThat(reporte.hito1Completado).isEqualTo(1)
        assertThat(reporte.tasaConversionPct).isEqualTo("0.00")
    }

    @Test
    fun `prospeccion mide la conversion sobre las mismas empresas que pueden avanzar`() {
        val vendedor = crearVendedor("P4")
        val conHitoOcurrido = crearEmpresa("77777777", vendedor, "2019-05-02 09:00:00")
        val conHitoPendiente = crearEmpresa("88888888", vendedor, "2019-05-03 09:00:00")
        crearHito(conHitoOcurrido, vendedor, posicionHito = 1, estado = "ocurrido")
        crearHito(conHitoPendiente, vendedor, posicionHito = 1, estado = "pendiente")
        crearOportunidadFacturada(
            idEmpresa = conHitoOcurrido,
            idVendedor = vendedor,
            idFinanciadora = crearFinanciadora("P4"),
            idModelo = crearModelo("P4"),
            creadaEn = "2019-05-12 09:00:00",
            facturadoEn = null,
        )

        val reporte = reporteService.prospeccion(periodoMayo2019, vendedor)

        // 1 de 1, no 1 de 2: la empresa con el hito solo agendado nunca podria
        // sumar en hito_N_completado, asi que tampoco entra en el denominador.
        assertThat(reporte.ingresadas).isEqualTo(1)
        assertThat(reporte.convertidasAOportunidad).isEqualTo(1)
        assertThat(reporte.tasaConversionPct).isEqualTo("100.00")
    }

    @Test
    fun `prospeccion ignora los hitos registrados contra una oportunidad`() {
        val vendedor = crearVendedor("P5")
        val empresa = crearEmpresa("99999999", vendedor, "2019-05-05 09:00:00")
        val oportunidad =
            crearOportunidadFacturada(
                idEmpresa = empresa,
                idVendedor = vendedor,
                idFinanciadora = crearFinanciadora("P5"),
                idModelo = crearModelo("P5"),
                creadaEn = "2019-05-06 09:00:00",
                facturadoEn = null,
            )
        // Hito ocurrido pero ligado a la oportunidad: §10.3 solo cuenta los de
        // empresa sin oportunidad. Con estado_cartera 'no_contactado' esta empresa
        // no entra por la primera rama del OR, asi que aisla el filtro del EXISTS.
        crearHito(empresa, vendedor, posicionHito = 1, estado = "ocurrido", idOportunidad = oportunidad)

        assertThat(reporteService.prospeccion(periodoMayo2019, vendedor).ingresadas).isZero()
    }

    @Test
    fun `prospeccion sigue contando la empresa por su estado_cartera aunque no tenga hitos`() {
        val vendedor = crearVendedor("P6")
        crearEmpresa("10101010", vendedor, "2019-05-05 09:00:00", estadoCartera = "prospeccion")

        // La primera rama del OR no se toca: el fix solo endurece el EXISTS.
        assertThat(reporteService.prospeccion(periodoMayo2019, vendedor).ingresadas).isEqualTo(1)
    }
}
