package pe.quantum.crm.support

import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.PostgreSQLContainer

/**
 * Clase base para tests de integracion (B0.6).
 *
 * Levanta un unico PostgreSQL 16 (patron singleton container): el contenedor se
 * arranca una sola vez por JVM y se comparte entre todas las clases que extienden
 * esta base, en vez de arrancar/parar uno por clase. Ryuk lo limpia al terminar la
 * JVM. `@DynamicPropertySource` apunta el DataSource de Spring al contenedor, con
 * lo que Flyway corre las migraciones reales sobre el.
 *
 * Los tests de integracion deben:
 *   - anotarse con `@Tag("integration")` (la tarea `integrationTest` los corre; el
 *     `test` unitario los excluye — ver build.gradle.kts),
 *   - extender esta clase para reutilizar el contenedor y el wiring del DataSource.
 *
 * En local, Docker Desktop 29 rompe el cliente docker-java de Testcontainers, por
 * lo que estos tests corren en CI. Ver memoria testcontainers-docker29-blocker.
 *
 * No es una utility class pese a tener solo companion: es una base abstracta para
 * heredar; por eso se suprime UtilityClassWithPublicConstructor.
 */
@Suppress("UtilityClassWithPublicConstructor")
abstract class IntegrationTestBase {
    companion object {
        @JvmStatic
        protected val postgres: PostgreSQLContainer<*> =
            PostgreSQLContainer("postgres:16-alpine").apply { start() }

        @JvmStatic
        @DynamicPropertySource
        fun registerDatasourceProperties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url", postgres::getJdbcUrl)
            registry.add("spring.datasource.username", postgres::getUsername)
            registry.add("spring.datasource.password", postgres::getPassword)
        }
    }
}
