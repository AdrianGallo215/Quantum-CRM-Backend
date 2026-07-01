package pe.quantum.crm

import com.ninjasquad.springmockk.MockkBean
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import pe.quantum.crm.domain.empleados.EmpleadoService

/**
 * Test de arranque del contexto de Spring (B0.1).
 *
 * Verifica que el `ApplicationContext` de la aplicacion se ensambla correctamente:
 * que existe un `@SpringBootApplication` y que el cableado base (web, security,
 * actuator) levanta sin errores.
 *
 * Se excluyen las autoconfiguraciones de base de datos (DataSource, JPA, Flyway)
 * porque B0.1 todavia no tiene `docker-compose` (B0.2) ni migraciones (B0.5). La
 * verificacion de "la app se conecta a PostgreSQL" se hace con un test de
 * integracion sobre Testcontainers en B0.6, donde vive ese setup.
 */
@SpringBootTest(
    properties = [
        "spring.autoconfigure.exclude=" +
            "org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration," +
            "org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration," +
            "org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration",
    ],
)
class CrmApplicationTests {
    // Sin DataSource no hay repositorios JPA; se mockea el servicio que los usa
    // para que el contexto (web + security) ensamble sin base de datos.
    @MockkBean
    lateinit var empleadoService: EmpleadoService

    @Test
    fun `el contexto de la aplicacion levanta`() {
        // Pasa si el ApplicationContext arranca. Si no existe `CrmApplication`
        // anotada con `@SpringBootApplication`, este test falla.
    }
}
