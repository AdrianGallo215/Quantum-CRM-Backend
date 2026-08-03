package pe.quantum.crm.db

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate
import pe.quantum.crm.support.IntegrationTestBase

/**
 * Verifica la cascada de V29/V30 (reglas_negocio.md §11.2): al eliminar una empresa
 * se eliminan sus oportunidades, tareas, eventos, log de estados y buses entregados,
 * pero los contactos vinculados sobreviven (solo se borra la fila de vinculo).
 */
@Tag("integration")
@SpringBootTest
class EmpresaEliminacionCascadaIntegrationTest : IntegrationTestBase() {
    @Autowired
    lateinit var jdbcTemplate: JdbcTemplate

    private fun id(sql: String): Long = jdbcTemplate.queryForObject(sql, Long::class.java)!!

    private fun count(sql: String): Int = jdbcTemplate.queryForObject(sql, Int::class.java)!!

    @Test
    @Suppress("LongMethod") // Un solo escenario: montar el grafo completo, borrar y verificar cada tabla.
    fun `eliminar una empresa arrastra oportunidad, tarea, evento y log, pero no el contacto vinculado`() {
        val admin =
            id(
                "INSERT INTO empleados (nombres, apellidos, email, rol) " +
                    "VALUES ('Ada', 'Cascada', 'ada.cascada@quantum.pe', 'admin') RETURNING id",
            )
        val financiadora =
            id("INSERT INTO financiadoras (nombre) VALUES ('Financiadora cascada test') RETURNING id")
        val modelo =
            id("INSERT INTO modelos (codigo) VALUES ('MODELO-CASCADA-TEST') RETURNING id")
        val empresa =
            id(
                """
                INSERT INTO empresas
                    (ruc, razon_social, actividad_econ, id_vendedor, estado_sunat, condicion_sunat, direccion_fiscal, created_by, updated_by)
                VALUES
                    ('20888888888', 'Cascada Test S.A.C.', 'Transporte', $admin, 'ACTIVO', 'HABIDO', 'Av. Cascada 1', $admin, $admin)
                RETURNING id
                """.trimIndent(),
            )
        val oportunidad =
            id(
                """
                INSERT INTO oportunidades (id_empresa, id_vendedor, id_financiadora, id_modelo, estado, created_by, updated_by)
                VALUES ($empresa, $admin, $financiadora, $modelo, 'evaluacion_calidda', $admin, $admin)
                RETURNING id
                """.trimIndent(),
            )
        jdbcTemplate.update(
            "INSERT INTO oportunidad_estados_log (id_oportunidad, estado_anterior, estado_nuevo, changed_by) " +
                "VALUES ($oportunidad, NULL, 'evaluacion_calidda', $admin)",
        )
        val tareaDeEmpresa =
            id(
                "INSERT INTO tareas (id_empresa, tipo_accion, created_by, updated_by) " +
                    "VALUES ($empresa, 'llamada', $admin, $admin) RETURNING id",
            )
        val tareaDeOportunidad =
            id(
                "INSERT INTO tareas (id_empresa, id_oportunidad, tipo_accion, created_by, updated_by) " +
                    "VALUES ($empresa, $oportunidad, 'llamada', $admin, $admin) RETURNING id",
            )
        val eventoDeEmpresa =
            id(
                "INSERT INTO eventos (id_empresa, es_personalizado, nombre_personalizado, estado, created_by, updated_by) " +
                    "VALUES ($empresa, true, 'Evento propio de empresa', 'pendiente', $admin, $admin) RETURNING id",
            )
        val contacto =
            id(
                "INSERT INTO contactos (nombres, apellidos, created_by, updated_by) " +
                    "VALUES ('Carlos', 'Contacto', $admin, $admin) RETURNING id",
            )
        jdbcTemplate.update(
            "INSERT INTO empresa_contactos (id_empresa, id_contacto, es_principal) VALUES ($empresa, $contacto, false)",
        )
        jdbcTemplate.update(
            "INSERT INTO buses_entregados (id_oportunidad, id_modelo, estado_entrega) " +
                "VALUES ($oportunidad, $modelo, 'pendiente')",
        )

        jdbcTemplate.update("DELETE FROM empresas WHERE id = $empresa")

        assertThat(count("SELECT COUNT(*) FROM oportunidades WHERE id = $oportunidad")).isZero()
        assertThat(count("SELECT COUNT(*) FROM oportunidad_estados_log WHERE id_oportunidad = $oportunidad")).isZero()
        assertThat(count("SELECT COUNT(*) FROM tareas WHERE id IN ($tareaDeEmpresa, $tareaDeOportunidad)")).isZero()
        assertThat(count("SELECT COUNT(*) FROM eventos WHERE id = $eventoDeEmpresa")).isZero()
        assertThat(count("SELECT COUNT(*) FROM empresa_contactos WHERE id_empresa = $empresa")).isZero()
        assertThat(count("SELECT COUNT(*) FROM buses_entregados WHERE id_oportunidad = $oportunidad")).isZero()
        assertThat(count("SELECT COUNT(*) FROM contactos WHERE id = $contacto")).isEqualTo(1)
    }
}
