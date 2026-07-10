package pe.quantum.crm.db

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.core.io.ClassPathResource
import org.springframework.jdbc.core.JdbcTemplate
import pe.quantum.crm.support.IntegrationTestBase

/**
 * Verifica el backfill de V23 (drift previo entre `empresas.id_vendedor` y
 * `oportunidades.id_vendedor`, reglas_negocio.md §8): re-ejecuta el SQL real de
 * la migracion contra datos sembrados a mano con drift intencional. Flyway ya
 * corrio V23 al levantar el contenedor (sin filas todavia, no-op); este test
 * prueba el mismo SQL contra datos insertados despues.
 */
@Tag("integration")
@SpringBootTest
class VendedorSyncBackfillIntegrationTest : IntegrationTestBase() {
    @Autowired
    lateinit var jdbcTemplate: JdbcTemplate

    @Test
    fun `V23 sincroniza el vendedor de oportunidades activas y respeta las cerradas`() {
        val vendedorA =
            jdbcTemplate.queryForObject(
                "INSERT INTO empleados (nombres, apellidos, email, rol) " +
                    "VALUES ('Ana', 'Diaz', 'ana.backfill@quantum.pe', 'vendedor') RETURNING id",
                Long::class.java,
            )!!
        val vendedorB =
            jdbcTemplate.queryForObject(
                "INSERT INTO empleados (nombres, apellidos, email, rol) " +
                    "VALUES ('Luis', 'Soto', 'luis.backfill@quantum.pe', 'vendedor') RETURNING id",
                Long::class.java,
            )!!
        val financiadora =
            jdbcTemplate.queryForObject(
                "INSERT INTO financiadoras (nombre) VALUES ('Financiadora backfill test') RETURNING id",
                Long::class.java,
            )!!
        val empresa =
            jdbcTemplate.queryForObject(
                """
                INSERT INTO empresas
                    (ruc, razon_social, actividad_econ, id_vendedor, estado_sunat, condicion_sunat, direccion_fiscal, created_by, updated_by)
                VALUES
                    ('20999999999', 'Backfill Test S.A.C.', 'Transporte', $vendedorB, 'ACTIVO', 'HABIDO', 'Av. Test 123', $vendedorA, $vendedorA)
                RETURNING id
                """.trimIndent(),
                Long::class.java,
            )!!
        val activa =
            jdbcTemplate.queryForObject(
                """
                INSERT INTO oportunidades (id_empresa, id_vendedor, id_financiadora, estado, created_by, updated_by)
                VALUES ($empresa, $vendedorA, $financiadora, 'evaluacion_calidda', $vendedorA, $vendedorA)
                RETURNING id
                """.trimIndent(),
                Long::class.java,
            )!!
        val cerrada =
            jdbcTemplate.queryForObject(
                """
                INSERT INTO oportunidades (id_empresa, id_vendedor, id_financiadora, estado, motivo_cierre, created_by, updated_by)
                VALUES ($empresa, $vendedorA, $financiadora, 'cerrado', 'Cliente declino', $vendedorA, $vendedorA)
                RETURNING id
                """.trimIndent(),
                Long::class.java,
            )!!

        val sql =
            ClassPathResource("db/migration/V23__sync_oportunidad_vendedor_activas.sql")
                .inputStream.bufferedReader().readText()
        jdbcTemplate.execute(sql)

        val vendedorActiva = jdbcTemplate.queryForObject("SELECT id_vendedor FROM oportunidades WHERE id = $activa", Long::class.java)
        val vendedorCerrada = jdbcTemplate.queryForObject("SELECT id_vendedor FROM oportunidades WHERE id = $cerrada", Long::class.java)
        assertThat(vendedorActiva).isEqualTo(vendedorB)
        assertThat(vendedorCerrada).isEqualTo(vendedorA)
    }
}
