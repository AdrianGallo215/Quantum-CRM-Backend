package pe.quantum.crm.domain.prospeccion

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.within
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate
import pe.quantum.crm.support.IntegrationTestBase
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit

/**
 * Test de integracion del hallazgo E2: `dias_sin_actividad` no puede alimentarse
 * de fechas futuras (hallazgo [Medio], `ProspeccionDao.kt:88`). NO se ejecuto en
 * la maquina que escribio este archivo (Testcontainers roto por Docker 29);
 * pendiente de CI.
 */
@Tag("integration")
@SpringBootTest
class ProspeccionDaoSqlIntegrationTest : IntegrationTestBase() {
    @Autowired
    lateinit var jdbcTemplate: JdbcTemplate

    @Autowired
    lateinit var prospeccionDao: ProspeccionDao

    private fun id(sql: String): Long = jdbcTemplate.queryForObject(sql, Long::class.java)!!

    private fun crearVendedor(): Long =
        id(
            "INSERT INTO empleados (nombres, apellidos, email, rol) " +
                "VALUES ('Prosp', 'Actividad', 'prosp.actividad@quantum.pe', 'vendedor') RETURNING id",
        )

    private fun crearEmpresa(idVendedor: Long): Long =
        id(
            """
            INSERT INTO empresas
                (ruc, razon_social, actividad_econ, id_vendedor, estado_sunat, condicion_sunat, direccion_fiscal, created_by, updated_by)
            VALUES
                ('20666666666', 'Actividad Test S.A.C.', 'Transporte', $idVendedor, 'ACTIVO', 'HABIDO', 'Av. Actividad 1', $idVendedor, $idVendedor)
            RETURNING id
            """.trimIndent(),
        )

    @Test
    fun `una tarea completada con fecha_ejecucion futura no cuenta como actividad`() {
        val vendedor = crearVendedor()
        val empresa = crearEmpresa(vendedor)
        // `fecha_ejecucion` a futuro, pero completada hace 3 dias (`updated_at`).
        jdbcTemplate.update(
            "INSERT INTO tareas " +
                "(id_empresa, id_asignado, tipo_accion, estado_accion, fecha_ejecucion, updated_at, created_by, updated_by) " +
                "VALUES (?, ?, 'llamada', 'completada', ?, ?, ?, ?)",
            empresa,
            vendedor,
            LocalDateTime.now().plusDays(30),
            LocalDateTime.now().minusDays(3),
            vendedor,
            vendedor,
        )

        val resultado = prospeccionDao.ultimaActividad(listOf(empresa))

        assertThat(resultado).containsKey(empresa)
        assertThat(resultado[empresa]).isCloseTo(LocalDateTime.now().minusDays(3), within(3, ChronoUnit.MINUTES))
    }

    @Test
    fun `un evento ocurrido con fecha_ocurrencia futura no cuenta como actividad`() {
        val vendedor = crearVendedor()
        val empresa = crearEmpresa(vendedor)
        val catalogo =
            id(
                "INSERT INTO catalogo_eventos (nombre, es_hito_prospeccion) " +
                    "VALUES ('Evento futuro test', false) RETURNING id",
            )
        jdbcTemplate.update(
            "INSERT INTO eventos (id_empresa, id_catalogo_evento, estado, fecha_ocurrencia, created_by, updated_by) " +
                "VALUES (?, ?, 'ocurrido', ?, ?, ?)",
            empresa,
            catalogo,
            LocalDateTime.now().plusDays(10),
            vendedor,
            vendedor,
        )

        val resultado = prospeccionDao.ultimaActividad(listOf(empresa))

        assertThat(resultado).doesNotContainKey(empresa)
    }
}
