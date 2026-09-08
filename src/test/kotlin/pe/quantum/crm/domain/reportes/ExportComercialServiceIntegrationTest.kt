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
 * El SQL del export comercial contra Postgres real (plan-15).
 *
 * Mockear el `NamedParameterJdbcTemplate` solo probaria el mock: para SQL con
 * CTE, UNION y subconsultas correlacionadas la unica prueba honesta es una base
 * de verdad. De ahi `@Tag("integration")`.
 *
 * NO se ejecutaron en la maquina que escribio este archivo: Testcontainers esta
 * roto por Docker 29 (ver la memoria testcontainers-docker29-blocker). Se validan
 * en CI con la tarea `integrationTest`.
 *
 * Los cuatro escenarios que sostienen el diseno de "ninguna fila se pierde":
 *   1. Un prospecto SIN oportunidad aparece igual.
 *   2. Una oportunidad SIN actividades aparece igual, con una sola fila.
 *   3. Una oportunidad con dos actividades produce DOS filas.
 *   4. El rango de fechas recorta por fecha de ingreso.
 */
@Tag("integration")
@SpringBootTest
@Transactional
class ExportComercialServiceIntegrationTest : IntegrationTestBase() {
    @Autowired
    lateinit var jdbcTemplate: JdbcTemplate

    @Autowired
    lateinit var exportService: ExportComercialService

    private fun id(sql: String): Long = jdbcTemplate.queryForObject(sql, Long::class.java)!!

    private fun crearVendedor(sufijo: String): Long =
        id(
            "INSERT INTO empleados (nombres, apellidos, email, rol) " +
                "VALUES ('Exp', 'Test$sufijo', 'exp.test$sufijo@quantum.pe', 'vendedor') RETURNING id",
        )

    private fun crearEmpresa(
        sufijo: String,
        idVendedor: Long,
        creadaEn: String,
    ): Long =
        id(
            """
            INSERT INTO empresas
                (ruc, razon_social, actividad_econ, id_vendedor, estado_sunat, condicion_sunat,
                 direccion_fiscal, origen_lead, estado_cartera, created_at, created_by, updated_by)
            VALUES
                ('215$sufijo', 'Exp Test $sufijo S.A.C.', 'Transporte', $idVendedor, 'ACTIVO', 'HABIDO',
                 'Av. Export $sufijo', 'cartera', 'prospeccion', TIMESTAMP '$creadaEn', $idVendedor, $idVendedor)
            RETURNING id
            """.trimIndent(),
        )

    /**
     * `id_financiadora` es NOT NULL desde V10 y sigue siendolo: se toma la
     * financiadora por defecto, que las migraciones de seed ya dejan creada.
     */
    private fun crearOportunidad(
        idEmpresa: Long,
        idVendedor: Long,
        creadaEn: String,
    ): Long =
        id(
            """
            INSERT INTO oportunidades
                (id_empresa, id_vendedor, id_financiadora, estado, created_at, created_by, updated_by)
            VALUES
                ($idEmpresa, $idVendedor, (SELECT id FROM financiadoras WHERE es_default = true LIMIT 1),
                 'evaluacion_calidda', TIMESTAMP '$creadaEn', $idVendedor, $idVendedor)
            RETURNING id
            """.trimIndent(),
        )

    private fun crearTarea(
        idEmpresa: Long,
        idOportunidad: Long?,
        idVendedor: Long,
    ): Long =
        id(
            """
            INSERT INTO tareas
                (id_empresa, id_oportunidad, id_asignado, tipo_accion, estado_accion, descripcion,
                 fecha_ejecucion, created_by, updated_by)
            VALUES
                ($idEmpresa, ${idOportunidad ?: "NULL"}, $idVendedor, 'llamada', 'pendiente', 'Seguimiento',
                 CURRENT_TIMESTAMP, $idVendedor, $idVendedor)
            RETURNING id
            """.trimIndent(),
        )

    @Test
    fun `un prospecto sin oportunidad aparece en el export`() {
        val vendedor = crearVendedor("P1")
        val empresa = crearEmpresa("P1", vendedor, "2026-03-01 10:00:00")

        val filas = exportService.filas(null, null)

        val delProspecto = filas.filter { it.razonSocial == "Exp Test P1 S.A.C." }
        assertThat(delProspecto).hasSize(1)
        assertThat(delProspecto.first().idOportunidad).isNull()
        assertThat(delProspecto.first().ruc).isEqualTo("215P1")
        assertThat(empresa).isPositive()
    }

    @Test
    fun `una oportunidad sin actividades produce exactamente una fila`() {
        val vendedor = crearVendedor("O1")
        val empresa = crearEmpresa("O1", vendedor, "2026-03-01 10:00:00")
        val oportunidad = crearOportunidad(empresa, vendedor, "2026-03-05 10:00:00")

        val filas = exportService.filas(null, null)

        val deLaOportunidad = filas.filter { it.idOportunidad == oportunidad }
        assertThat(deLaOportunidad).hasSize(1)
        assertThat(deLaOportunidad.first().actividadTipo).isNull()
        assertThat(deLaOportunidad.first().estadoOportunidad).isEqualTo("evaluacion_calidda")
    }

    @Test
    fun `una oportunidad con dos actividades produce dos filas`() {
        val vendedor = crearVendedor("O2")
        val empresa = crearEmpresa("O2", vendedor, "2026-03-01 10:00:00")
        val oportunidad = crearOportunidad(empresa, vendedor, "2026-03-05 10:00:00")
        crearTarea(empresa, oportunidad, vendedor)
        crearTarea(empresa, oportunidad, vendedor)

        val filas = exportService.filas(null, null)

        val deLaOportunidad = filas.filter { it.idOportunidad == oportunidad }
        assertThat(deLaOportunidad).hasSize(2)
        assertThat(deLaOportunidad).allMatch { it.actividadTipo == "tarea" }
    }

    @Test
    fun `una actividad de prospeccion no se pierde aunque la empresa ya tenga oportunidades`() {
        // Es la trampa que el diseno del universo de filas existe para evitar:
        // sin la rama de prospeccion, esta tarea desapareceria en silencio.
        val vendedor = crearVendedor("O3")
        val empresa = crearEmpresa("O3", vendedor, "2026-03-01 10:00:00")
        crearOportunidad(empresa, vendedor, "2026-03-05 10:00:00")
        crearTarea(empresa, null, vendedor)

        val filas = exportService.filas(null, null)

        val deProspeccion =
            filas.filter { it.razonSocial == "Exp Test O3 S.A.C." && it.idOportunidad == null }
        assertThat(deProspeccion).hasSize(1)
        assertThat(deProspeccion.first().actividadTipo).isEqualTo("tarea")
    }

    @Test
    fun `el rango de fechas recorta por fecha de ingreso`() {
        val vendedor = crearVendedor("F1")
        crearEmpresa("F1", vendedor, "2026-01-10 10:00:00")
        crearEmpresa("F2", vendedor, "2026-06-10 10:00:00")

        val filas = exportService.filas(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 31))

        val razones = filas.mapNotNull { it.razonSocial }
        assertThat(razones).contains("Exp Test F1 S.A.C.")
        assertThat(razones).doesNotContain("Exp Test F2 S.A.C.")
    }

    @Test
    fun `sin rango de fechas se devuelve todo el historico`() {
        val vendedor = crearVendedor("F3")
        crearEmpresa("F3", vendedor, "2019-01-10 10:00:00")

        val filas = exportService.filas(null, null)

        assertThat(filas.mapNotNull { it.razonSocial }).contains("Exp Test F3 S.A.C.")
    }
}
