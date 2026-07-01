package pe.quantum.crm.db

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.math.BigDecimal

/**
 * Test de integracion de B0.5: verifica que Flyway aplica las 19 migraciones en
 * orden contra un PostgreSQL 16 real (Testcontainers) y que el schema y los seeds
 * (Calidda, catalogo de eventos, admin) quedan como especifica `schema.sql` y las
 * migraciones de seed (V17/V18/V19).
 *
 * Corre en CI (Docker compatible en el runner). En local, Docker Desktop 29 rompe
 * el cliente docker-java de Testcontainers; las migraciones se verifican ahi
 * corriendo Flyway contra el PostgreSQL de docker-compose. Ver memoria
 * testcontainers-docker29-blocker.
 */
@Tag("integration")
@SpringBootTest
@Testcontainers
class SchemaMigrationIntegrationTest {
    companion object {
        @Container
        @JvmStatic
        val postgres = PostgreSQLContainer("postgres:16-alpine")

        @JvmStatic
        @DynamicPropertySource
        fun datasourceProps(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url", postgres::getJdbcUrl)
            registry.add("spring.datasource.username", postgres::getUsername)
            registry.add("spring.datasource.password", postgres::getPassword)
        }
    }

    @Autowired
    lateinit var jdbcTemplate: JdbcTemplate

    private fun int(sql: String): Int = jdbcTemplate.queryForObject(sql, Int::class.java)!!

    private fun str(sql: String): String? = jdbcTemplate.queryForObject(sql, String::class.java)

    private fun strList(sql: String): List<String> = jdbcTemplate.queryForList(sql, String::class.java)

    @Test
    fun `las 19 migraciones se aplican en orden y sin fallos`() {
        val version = int("SELECT MAX(version::int) FROM flyway_schema_history WHERE success")
        val aplicadas = int("SELECT COUNT(*) FROM flyway_schema_history WHERE success AND version IS NOT NULL")
        val fallidas = int("SELECT COUNT(*) FROM flyway_schema_history WHERE NOT success")
        assertThat(version).isEqualTo(19)
        assertThat(aplicadas).isEqualTo(19)
        assertThat(fallidas).isZero()
    }

    @Test
    fun `el schema crea las 15 tablas de dominio`() {
        val tablas = strList("SELECT tablename FROM pg_tables WHERE schemaname = 'public' AND tablename <> 'flyway_schema_history'")
        assertThat(tablas).containsExactlyInAnyOrder(
            "empleados",
            "modelos",
            "modelo_aplicaciones",
            "financiadoras",
            "empresas",
            "empresa_segmentos",
            "contactos",
            "empresa_contactos",
            "oportunidades",
            "oportunidad_estados_log",
            "oportunidad_contactos",
            "catalogo_eventos",
            "eventos",
            "tareas",
            "buses_entregados",
        )
    }

    @Test
    fun `los 10 enums de dominio existen`() {
        val enums = strList("SELECT typname FROM pg_type WHERE typtype = 'e'")
        assertThat(enums).containsExactlyInAnyOrder(
            "rol_empleado",
            "aplicacion_enum",
            "segmento_enum",
            "origen_lead_enum",
            "estado_cartera_enum",
            "estado_op_enum",
            "estado_evento_enum",
            "tipo_accion_enum",
            "estado_accion_enum",
            "estado_entrega_enum",
        )
    }

    @Test
    fun `el seed inserta a Calidda como unica financiadora default`() {
        val defaults = strList("SELECT nombre FROM financiadoras WHERE es_default")
        assertThat(defaults).containsExactly("Calidda – Fraccionamiento GNV")

        val cuota = jdbcTemplate.queryForObject("SELECT cuota_por_unidad FROM financiadoras WHERE es_default", BigDecimal::class.java)
        assertThat(cuota).isEqualByComparingTo("937.50")
    }

    @Test
    fun `el seed carga el catalogo de eventos con sus hitos y disparos`() {
        assertThat(int("SELECT COUNT(*) FROM catalogo_eventos")).isEqualTo(10)
        assertThat(int("SELECT COUNT(*) FROM catalogo_eventos WHERE es_hito_prospeccion")).isEqualTo(3)
        assertThat(int("SELECT COUNT(*) FROM catalogo_eventos WHERE dispara_cambio_estado")).isEqualTo(3)

        val desembolso = str("SELECT estado_destino::text FROM catalogo_eventos WHERE nombre = 'Desembolso Calidda'")
        assertThat(desembolso).isEqualTo("facturado")
    }

    @Test
    fun `el seed crea el empleado admin inicial`() {
        val rol = str("SELECT rol::text FROM empleados WHERE email = 'admin@quantum.pe'")
        assertThat(rol).isEqualTo("admin")
    }
}
