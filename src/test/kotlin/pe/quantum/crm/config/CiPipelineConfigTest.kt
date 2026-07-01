package pe.quantum.crm.config

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.yaml.snakeyaml.Yaml
import java.io.File

/**
 * Test de contrato sobre el pipeline de CI (B0.4).
 *
 * No ejecuta el workflow: valida que `.github/workflows/ci.yml` declare todos los
 * gates exigidos por DEVOPS-backend.md (§3 esquema, §4 tabla de gates) y que
 * respete el principio fail-fast (lo mas rapido primero). Los gates son:
 * ktlint, detekt, compilacion, tests (con Testcontainers + ArchUnit),
 * cobertura (Kover) y escaneo de vulnerabilidades (OWASP Dependency-Check).
 *
 * La verificacion en caliente ("el workflow corre en un PR y todos los jobs
 * pasan") ocurre en GitHub Actions, no en la suite local.
 */
class CiPipelineConfigTest {
    private val projectRoot = File(".").canonicalFile
    private val workflowFile = File(projectRoot, ".github/workflows/ci.yml")

    @Suppress("UNCHECKED_CAST")
    private fun loadWorkflow(): Map<String, Any> {
        assertThat(workflowFile)
            .withFailMessage("Falta el workflow de CI en .github/workflows/ci.yml")
            .exists()
        return workflowFile.inputStream().use { Yaml().load(it) as Map<String, Any> }
    }

    private fun workflowText(): String {
        assertThat(workflowFile)
            .withFailMessage("Falta el workflow de CI en .github/workflows/ci.yml")
            .exists()
        return workflowFile.readText()
    }

    @Test
    fun `el workflow corre en pull request y en push a develop y main`() {
        // SnakeYAML parsea la clave `on:` como el booleano `true` (YAML 1.1),
        // por eso los triggers se validan sobre el texto del workflow.
        val texto = workflowText()
        assertThat(texto)
            .withFailMessage("El workflow debe dispararse en pull_request")
            .contains("pull_request")
        assertThat(texto)
            .withFailMessage("El workflow debe dispararse en push a develop y main")
            .contains("develop")
            .contains("main")
    }

    @Suppress("UNCHECKED_CAST")
    @Test
    fun `el job build corre en ubuntu`() {
        val jobs = loadWorkflow()["jobs"] as Map<String, Any>
        val build = jobs["build"] as Map<String, Any>
        assertThat(build["runs-on"].toString())
            .withFailMessage("El job build debe correr en ubuntu-latest")
            .contains("ubuntu")
    }

    @Test
    fun `el workflow configura JDK 21 Temurin`() {
        val texto = workflowText()
        assertThat(texto)
            .withFailMessage("El workflow debe usar setup-java con distribucion Temurin")
            .contains("setup-java")
            .contains("temurin")
        assertThat(texto)
            .withFailMessage("El workflow debe configurar JDK 21")
            .contains("21")
    }

    @Test
    fun `el workflow cachea Gradle`() {
        val texto = workflowText().lowercase()
        assertThat(texto)
            .withFailMessage("El workflow debe cachear Gradle para reproducibilidad y velocidad")
            .contains("gradle")
            .containsAnyOf("cache", "setup-gradle")
    }

    @Test
    fun `el workflow declara todos los gates de calidad`() {
        val texto = workflowText()
        val gates =
            mapOf(
                "ktlint" to "ktlintCheck",
                "detekt" to "detekt",
                "compilacion" to "compileKotlin",
                "tests" to "test",
                "cobertura" to "koverVerify",
                "dependency-check" to "dependencyCheckAnalyze",
            )
        assertThat(gates.entries).allSatisfy { (gate, comando) ->
            assertThat(texto)
                .withFailMessage("El workflow no ejecuta el gate '%s' (%s)", gate, comando)
                .contains(comando)
        }
    }

    @Test
    fun `los gates respetan el orden fail-fast lint antes que tests`() {
        val texto = workflowText()
        val posKtlint = texto.indexOf("ktlintCheck")
        val posDetekt = texto.indexOf("detekt")
        val posCompile = texto.indexOf("compileKotlin")
        val posTest = texto.indexOf(":test").let { if (it >= 0) it else texto.indexOf(" test") }
        assertThat(posKtlint).isGreaterThanOrEqualTo(0)
        assertThat(posTest).isGreaterThanOrEqualTo(0)
        assertThat(posKtlint)
            .withFailMessage("ktlint (rapido) debe correr antes que los tests (lento) - fail fast")
            .isLessThan(posTest)
        assertThat(posDetekt)
            .withFailMessage("detekt debe correr antes que los tests - fail fast")
            .isLessThan(posTest)
        assertThat(posCompile)
            .withFailMessage("La compilacion debe correr antes que los tests - fail fast")
            .isLessThan(posTest)
    }

    @Test
    fun `el escaneo de dependencias recibe la NVD_API_KEY del secret`() {
        val texto = workflowText()
        assertThat(texto)
            .withFailMessage(
                "El dependency-check debe recibir NVD_API_KEY desde los secrets del repo " +
                    "(sin ella la descarga NVD es lentisima; ver memoria owasp-dependency-check-nvd-apikey)",
            )
            .contains("NVD_API_KEY")
            .contains("secrets.NVD_API_KEY")
    }

    @Test
    fun `el workflow sube el reporte de cobertura como artifact`() {
        val texto = workflowText()
        assertThat(texto)
            .withFailMessage("El workflow debe subir el reporte de cobertura con upload-artifact")
            .contains("upload-artifact")
    }
}
