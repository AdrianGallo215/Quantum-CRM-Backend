package pe.quantum.crm.config

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.yaml.snakeyaml.Yaml
import java.io.File

/**
 * Test de contrato sobre los archivos de entorno local (B0.2).
 *
 * No levanta Docker: valida que los deliverables de infraestructura existan y
 * cumplan los requisitos del PRD (§12) y SECURITY-backend.md (§9): PostgreSQL 16
 * con healthcheck, `.env.example` con todas las variables, y `.gitignore` que
 * excluye secretos y artefactos de build.
 *
 * La verificacion en caliente ("docker-compose up -d levanta y la app conecta")
 * se valida ejecutando, no en la suite, para no acoplar los tests a Docker.
 */
class LocalEnvironmentConfigTest {
    private val projectRoot = File(".").canonicalFile

    @Suppress("UNCHECKED_CAST")
    private fun loadCompose(): Map<String, Any> {
        val file = File(projectRoot, "docker-compose.yml")
        assertThat(file)
            .withFailMessage("Falta docker-compose.yml en la raiz del proyecto")
            .exists()
        return file.inputStream().use { Yaml().load(it) as Map<String, Any> }
    }

    @Suppress("UNCHECKED_CAST")
    private fun postgresService(): Map<String, Any> {
        val services = loadCompose()["services"] as? Map<String, Any>
        assertThat(services).withFailMessage("docker-compose.yml no define 'services'").isNotNull
        val postgres = services!!["postgres"] as? Map<String, Any>
        assertThat(postgres).withFailMessage("docker-compose.yml no define el servicio 'postgres'").isNotNull
        return postgres!!
    }

    @Test
    fun `docker-compose usa la imagen oficial de postgres 16`() {
        val image = postgresService()["image"] as? String
        assertThat(image)
            .withFailMessage("El servicio postgres debe usar la imagen postgres:16, fue: %s", image)
            .isNotNull()
            .startsWith("postgres:16")
    }

    @Suppress("UNCHECKED_CAST")
    @Test
    fun `el servicio postgres define un healthcheck con pg_isready`() {
        val healthcheck = postgresService()["healthcheck"] as? Map<String, Any>
        assertThat(healthcheck)
            .withFailMessage("El servicio postgres debe definir un healthcheck")
            .isNotNull
        val test = healthcheck!!["test"].toString()
        assertThat(test)
            .withFailMessage("El healthcheck debe usar pg_isready, fue: %s", test)
            .contains("pg_isready")
    }

    @Test
    fun `el servicio postgres expone el puerto 5432`() {
        val ports = postgresService()["ports"].toString()
        assertThat(ports).contains("5432")
    }

    @Test
    fun `env example contiene todas las variables requeridas por el PRD`() {
        val envExample = File(projectRoot, ".env.example")
        assertThat(envExample)
            .withFailMessage("Falta .env.example en la raiz del proyecto")
            .exists()
        val contenido = envExample.readText()
        val requeridas =
            listOf(
                "DB_URL",
                "DB_USERNAME",
                "DB_PASSWORD",
                "JWT_SECRET",
                "JWT_EXPIRATION_MS",
                "JWT_REFRESH_EXPIRATION_MS",
                "CORS_ALLOWED_ORIGINS",
                "SPRING_PROFILES_ACTIVE",
            )
        assertThat(requeridas)
            .allSatisfy { variable ->
                assertThat(contenido)
                    .withFailMessage(".env.example no declara la variable requerida: %s", variable)
                    .contains("$variable=")
            }
    }

    @Test
    fun `env example no contiene secretos reales solo placeholders`() {
        val contenido = File(projectRoot, ".env.example").readText()
        // El JWT_SECRET de ejemplo nunca debe ser un secreto real de 256 bits.
        val jwtLine = contenido.lineSequence().first { it.trimStart().startsWith("JWT_SECRET=") }
        val valor = jwtLine.substringAfter("=").trim()
        assertThat(valor.matches(Regex("^[0-9a-fA-F]{64}$")))
            .withFailMessage("JWT_SECRET en .env.example parece un secreto real, debe ser un placeholder")
            .isFalse()
    }

    @Test
    fun `gitignore excluye env y artefactos de build`() {
        val gitignore = File(projectRoot, ".gitignore")
        assertThat(gitignore)
            .withFailMessage("Falta .gitignore en la raiz del proyecto")
            .exists()
        val lineas = gitignore.readLines().map { it.trim() }
        assertThat(lineas)
            .withFailMessage(".gitignore debe excluir .env, build/ y .gradle/")
            .contains(".env", "build/", ".gradle/")
    }
}
