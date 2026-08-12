package pe.quantum.crm.domain.tareas

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate
import pe.quantum.crm.shared.enums.EstadoAccion
import pe.quantum.crm.support.IntegrationTestBase
import java.time.LocalDateTime

/**
 * Test de integracion de la ventana temporal de `pendientesParaRecordatorio`
 * (hallazgo C.1). Sin cota, el job escaneaba la tabla entera y lanzaba un
 * `exists` por cada tarea vencida indefinidamente; la ventana [ahora-30d,
 * ahora+24h] es un superconjunto estricto de lo que `RecordatorioJob` puede
 * notificar.
 */
@Tag("integration")
@SpringBootTest
class TareaRepositoryVentanaIntegrationTest : IntegrationTestBase() {
    @Autowired
    lateinit var tareaRepository: TareaRepository

    @Autowired
    lateinit var jdbcTemplate: JdbcTemplate

    private fun id(sql: String): Long = jdbcTemplate.queryForObject(sql, Long::class.java)!!

    @Test
    fun `la ventana incluye lo notificable y excluye lo demas`() {
        val admin =
            id(
                "INSERT INTO empleados (nombres, apellidos, email, rol) " +
                    "VALUES ('Vera', 'Ventana', 'vera.ventana@quantum.pe', 'vendedor') RETURNING id",
            )
        val empresa =
            id(
                """
                INSERT INTO empresas
                    (ruc, razon_social, actividad_econ, id_vendedor, estado_sunat, condicion_sunat, direccion_fiscal, created_by, updated_by)
                VALUES
                    ('20777777777', 'Ventana Test S.A.C.', 'Transporte', $admin, 'ACTIVO', 'HABIDO', 'Av. Ventana 1', $admin, $admin)
                RETURNING id
                """.trimIndent(),
            )

        // Dentro de la ventana: hace 2 dias.
        val dentroVencida =
            id(
                "INSERT INTO tareas (id_empresa, id_asignado, tipo_accion, estado_accion, fecha_ejecucion, created_by, updated_by) " +
                    "VALUES ($empresa, $admin, 'llamada', 'pendiente', " +
                    "'${LocalDateTime.now().minusDays(2)}', $admin, $admin) RETURNING id",
            )

        // Fuera de la ventana por abajo: hace 400 dias.
        id(
            "INSERT INTO tareas (id_empresa, id_asignado, tipo_accion, estado_accion, fecha_ejecucion, created_by, updated_by) " +
                "VALUES ($empresa, $admin, 'llamada', 'pendiente', " +
                "'${LocalDateTime.now().minusDays(400)}', $admin, $admin) RETURNING id",
        )

        // Fuera de la ventana por arriba: a 48h vista.
        id(
            "INSERT INTO tareas (id_empresa, id_asignado, tipo_accion, estado_accion, fecha_ejecucion, created_by, updated_by) " +
                "VALUES ($empresa, $admin, 'llamada', 'pendiente', " +
                "'${LocalDateTime.now().plusHours(48)}', $admin, $admin) RETURNING id",
        )

        // Sin fecha_ejecucion: `Between` debe excluirla igual que antes lo hacia `IsNotNull`.
        id(
            "INSERT INTO tareas (id_empresa, id_asignado, tipo_accion, estado_accion, created_by, updated_by) " +
                "VALUES ($empresa, $admin, 'llamada', 'pendiente', $admin, $admin) RETURNING id",
        )

        val ahora = LocalDateTime.now()
        val resultado =
            tareaRepository.findByEstadoAccionAndIdAsignadoIsNotNullAndFechaEjecucionBetween(
                EstadoAccion.pendiente,
                ahora.minusDays(30),
                ahora.plusHours(24),
            )

        assertThat(resultado.map { it.id }).containsExactly(dentroVencida)
    }
}
