package pe.quantum.crm.support

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate

/**
 * Test de integracion trivial (B0.6, criterio de aceptacion): verifica que la
 * clase base levanta el contenedor de Testcontainers y que la app se conecta a el.
 */
@Tag("integration")
@SpringBootTest
class ConnectivityIntegrationTest : IntegrationTestBase() {
    @Autowired
    lateinit var jdbcTemplate: JdbcTemplate

    @Test
    fun `el contenedor de PostgreSQL esta corriendo`() {
        assertThat(postgres.isRunning).isTrue()
    }

    @Test
    fun `la app se conecta al PostgreSQL del contenedor`() {
        val uno = jdbcTemplate.queryForObject("SELECT 1", Int::class.java)
        assertThat(uno).isEqualTo(1)
    }

    @Test
    fun `la conexion apunta a un PostgreSQL 16`() {
        val version = jdbcTemplate.queryForObject("SHOW server_version", String::class.java)
        assertThat(version).startsWith("16")
    }
}
