package pe.quantum.crm.db

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate
import pe.quantum.crm.support.IntegrationTestBase
import pe.quantum.crm.support.SeedFixtures
import java.math.BigDecimal

/**
 * Test de integracion de B0.5: verifica que Flyway aplica todas las migraciones en
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
class SchemaMigrationIntegrationTest : IntegrationTestBase() {
    @Autowired
    lateinit var jdbcTemplate: JdbcTemplate

    private fun int(sql: String): Int = jdbcTemplate.queryForObject(sql, Int::class.java)!!

    private fun str(sql: String): String? = jdbcTemplate.queryForObject(sql, String::class.java)

    private fun strList(sql: String): List<String> = jdbcTemplate.queryForList(sql, String::class.java)

    @Test
    fun `todas las migraciones se aplican en orden y sin fallos`() {
        val version = int("SELECT MAX(version::int) FROM flyway_schema_history WHERE success")
        val aplicadas = int("SELECT COUNT(*) FROM flyway_schema_history WHERE success AND version IS NOT NULL")
        val fallidas = int("SELECT COUNT(*) FROM flyway_schema_history WHERE NOT success")
        // Version maxima y cantidad de migraciones son dos hechos distintos: no coinciden
        // desde que la numeracion tiene un hueco (no existe V40). Ver SeedFixtures.
        assertThat(version).isEqualTo(SeedFixtures.MIGRACION_VERSION_MAX)
        assertThat(aplicadas).isEqualTo(SeedFixtures.MIGRACIONES_TOTAL)
        assertThat(fallidas).isZero()
    }

    @Test
    fun `el schema crea las 24 tablas de dominio`() {
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
            "oportunidad_items",
            "catalogo_eventos",
            "eventos",
            "tareas",
            "tarea_responsables",
            "buses_entregados",
            "notificaciones",
            "recordatorios_enviados",
            "metas_venta",
            "solicitudes",
            "simulaciones",
            "simulacion_log",
            "tipo_cambio",
        )
    }

    @Test
    fun `los 21 enums de dominio existen`() {
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
            "tipo_notificacion_enum",
            "entidad_notificacion_enum",
            "origen_recordatorio_enum",
            "umbral_recordatorio_enum",
            "estado_meta_enum",
            "tipo_solicitud_enum",
            "estado_solicitud_enum",
            "aprobador_solicitud_enum",
            "entidad_solicitud_enum",
            "modo_simulacion_enum",
            "tipo_evento_simulacion_enum",
        )
    }

    @Test
    fun `el seed inserta a Calidda como unica financiadora default`() {
        val defaults = strList("SELECT nombre FROM financiadoras WHERE es_default")
        assertThat(defaults).containsExactly(SeedFixtures.CALIDDA_NOMBRE)

        val cuota = jdbcTemplate.queryForObject("SELECT cuota_por_unidad FROM financiadoras WHERE es_default", BigDecimal::class.java)
        assertThat(cuota).isEqualByComparingTo(SeedFixtures.CALIDDA_CUOTA_POR_UNIDAD)
    }

    @Test
    fun `el seed carga el catalogo de eventos con sus hitos y disparos`() {
        assertThat(int("SELECT COUNT(*) FROM catalogo_eventos"))
            .isEqualTo(SeedFixtures.CATALOGO_EVENTOS_TOTAL)
        assertThat(int("SELECT COUNT(*) FROM catalogo_eventos WHERE es_hito_prospeccion"))
            .isEqualTo(SeedFixtures.CATALOGO_HITOS_PROSPECCION)
        assertThat(int("SELECT COUNT(*) FROM catalogo_eventos WHERE dispara_cambio_estado"))
            .isEqualTo(SeedFixtures.CATALOGO_DISPARAN_CAMBIO_ESTADO)

        val desembolso = str("SELECT estado_destino::text FROM catalogo_eventos WHERE nombre = 'Desembolso Calidda'")
        assertThat(desembolso).isEqualTo("facturado")
    }

    @Test
    fun `el seed crea el empleado admin inicial`() {
        val rol = str("SELECT rol::text FROM empleados WHERE email = '${SeedFixtures.ADMIN_EMAIL}'")
        assertThat(rol).isEqualTo(SeedFixtures.ADMIN_ROL)
    }

    @Test
    fun `los campos de SUNAT de empresas admiten null tras V38`() {
        // V6 los creo NOT NULL, pero el contrato, el DTO y la entidad los tratan
        // como opcionales; `ddl-auto=validate` no compara nulabilidad, asi que la
        // discrepancia solo salia como 500 en el primer POST /empresas con body
        // minimo. V38 relaja la columna; esto impide que alguien la vuelva a
        // apretar sin darse cuenta.
        val obligatorias =
            strList(
                """
                SELECT column_name FROM information_schema.columns
                WHERE table_schema = 'public' AND table_name = 'empresas'
                  AND is_nullable = 'NO'
                  AND column_name IN ('actividad_econ', 'estado_sunat', 'condicion_sunat', 'direccion_fiscal')
                """.trimIndent(),
            )
        assertThat(obligatorias).isEmpty()
    }
}
