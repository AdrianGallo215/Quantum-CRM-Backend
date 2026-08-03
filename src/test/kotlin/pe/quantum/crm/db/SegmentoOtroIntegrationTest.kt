package pe.quantum.crm.db

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate
import pe.quantum.crm.support.IntegrationTestBase

/**
 * El frontend permite seleccionar el segmento "otro" pero `segmento_enum` (Postgres)
 * solo tenia urbano/personal/turismo/interprovincial (reglas_negocio.md §2.3),
 * causando un 400 al crear empresas con ese segmento. V24 agrega 'otro' al enum.
 */
@Tag("integration")
@SpringBootTest
class SegmentoOtroIntegrationTest : IntegrationTestBase() {
    @Autowired
    lateinit var jdbcTemplate: JdbcTemplate

    @Test
    fun `segmento_enum acepta el valor otro`() {
        val empleado =
            jdbcTemplate.queryForObject(
                "INSERT INTO empleados (nombres, apellidos, email, rol) " +
                    "VALUES ('Ana', 'Diaz', 'ana.segmento-test@quantum.pe', 'vendedor') RETURNING id",
                Long::class.java,
            )!!
        val empresa =
            jdbcTemplate.queryForObject(
                """
                INSERT INTO empresas (ruc, razon_social, actividad_econ, estado_sunat, condicion_sunat, direccion_fiscal, created_by, updated_by)
                VALUES ('20999999998', 'Segmento Otro Test S.A.C.', 'Transporte', 'ACTIVO', 'HABIDO', 'Av. Test 456', $empleado, $empleado)
                RETURNING id
                """.trimIndent(),
                Long::class.java,
            )!!

        jdbcTemplate.update("INSERT INTO empresa_segmentos (id_empresa, segmento) VALUES ($empresa, 'otro')")

        val segmento =
            jdbcTemplate.queryForObject(
                "SELECT segmento FROM empresa_segmentos WHERE id_empresa = $empresa",
                String::class.java,
            )
        assertThat(segmento).isEqualTo("otro")
    }
}
