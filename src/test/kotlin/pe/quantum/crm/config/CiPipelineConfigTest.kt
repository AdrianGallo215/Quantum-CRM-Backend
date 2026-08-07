package pe.quantum.crm.config

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.yaml.snakeyaml.Yaml
import java.io.File

/**
 * Test de contrato sobre el pipeline de CI de PR (B0.4).
 *
 * No ejecuta el workflow: valida que `.github/workflows/ci.yml` declare los gates
 * que gatean cada PR y que respete el principio fail-fast (lo mas rapido primero):
 * ktlint, detekt, compilacion, tests (unitarios + ArchUnit + Testcontainers) y
 * cobertura (Kover).
 *
 * Las aserciones van sobre el YAML *parseado* (pasos, `uses`, `run`, `with`), no
 * sobre `contains` del texto crudo: un `contains("21")` se satisface con cualquier
 * "21" del archivo —incluido un comentario— y da por verificado algo que nadie
 * verifico. Solo se usa el texto donde el parseo no sirve, y se dice por que.
 *
 * El escaneo de vulnerabilidades (OWASP Dependency-Check) NO gatea cada PR: la API
 * publica de la NVD es inestable y su descarga inicial puede tardar horas, lo que
 * bloquearia todo merge ante una caida de la NVD. Se ejecuta en un workflow de
 * seguridad nocturno + manual (ver SecurityScanWorkflowConfigTest). El build sigue
 * fallando ante CVE alto, solo cambia la cadencia.
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

    @Suppress("UNCHECKED_CAST")
    private fun jobBuild(): Map<String, Any> {
        val jobs = loadWorkflow()["jobs"] as Map<String, Any>
        val build = jobs["build"] as? Map<String, Any>
        assertThat(build).withFailMessage("ci.yml no define el job 'build'").isNotNull
        return build!!
    }

    @Suppress("UNCHECKED_CAST")
    private fun pasos(): List<Map<String, Any>> = jobBuild()["steps"] as List<Map<String, Any>>

    /** Comandos `run` de los pasos, en orden de ejecucion. */
    private fun comandos(): List<String> = pasos().mapNotNull { it["run"]?.toString() }

    /** Indice del primer paso cuyo `run` contiene el comando dado, o -1. */
    private fun ordenDe(comando: String): Int = comandos().indexOfFirst { it.contains(comando) }

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
            .contains("branches: [develop, main]")
    }

    @Test
    fun `el job build corre en ubuntu`() {
        assertThat(jobBuild()["runs-on"].toString())
            .withFailMessage("El job build debe correr en ubuntu-latest")
            .contains("ubuntu")
    }

    @Suppress("UNCHECKED_CAST")
    @Test
    fun `el workflow configura JDK 21 Temurin`() {
        val setupJava = pasos().firstOrNull { it["uses"]?.toString()?.startsWith("actions/setup-java") == true }
        assertThat(setupJava)
            .withFailMessage("El workflow debe usar actions/setup-java")
            .isNotNull
        val parametros = setupJava!!["with"] as? Map<String, Any>
        assertThat(parametros?.get("distribution")?.toString())
            .withFailMessage("La distribucion del JDK debe ser temurin, fue: %s", parametros?.get("distribution"))
            .isEqualTo("temurin")
        assertThat(parametros?.get("java-version")?.toString())
            .withFailMessage(
                "El JDK debe ser el 21 (el proyecto compila con toolchain 21), fue: %s",
                parametros?.get("java-version"),
            ).isEqualTo("21")
    }

    @Test
    fun `el workflow cachea Gradle`() {
        val usos = pasos().mapNotNull { it["uses"]?.toString() }
        assertThat(usos)
            .withFailMessage(
                "El workflow debe usar gradle/actions/setup-gradle, que cachea dependencias y el " +
                    "daemon entre corridas. Pasos declarados: %s",
                usos,
            ).anyMatch { it.startsWith("gradle/actions/setup-gradle") }
    }

    @Test
    fun `el workflow declara todos los gates de calidad`() {
        val gates =
            mapOf(
                "ktlint" to "ktlintCheck",
                "detekt" to "detekt",
                "compilacion" to "compileKotlin",
                "tests unitarios (incluye ArchUnit)" to "gradlew test",
                "cobertura" to "koverVerify",
                "tests de integracion" to "integrationTest",
            )
        assertThat(gates.entries).allSatisfy { (gate, comando) ->
            assertThat(comandos())
                .withFailMessage("El workflow no ejecuta el gate '%s' (%s). Comandos: %s", gate, comando, comandos())
                .anyMatch { it.contains(comando) }
        }
    }

    @Test
    fun `los gates respetan el orden fail-fast lint antes que tests`() {
        val posKtlint = ordenDe("ktlintCheck")
        val posDetekt = ordenDe("detekt")
        val posCompile = ordenDe("compileKotlin")
        val posTest = ordenDe("gradlew test")
        assertThat(posTest).withFailMessage("El workflow no ejecuta los tests unitarios").isGreaterThanOrEqualTo(0)
        assertThat(posKtlint)
            .withFailMessage("ktlint (rapido) debe correr antes que los tests (lento) - fail fast")
            .isBetween(0, posTest - 1)
        assertThat(posDetekt)
            .withFailMessage("detekt debe correr antes que los tests - fail fast")
            .isBetween(0, posTest - 1)
        assertThat(posCompile)
            .withFailMessage("La compilacion debe correr antes que los tests - fail fast")
            .isBetween(0, posTest - 1)
    }

    @Test
    fun `el CI de PR no bloquea con el escaneo de dependencias`() {
        // El scan de vulnerabilidades corre en el workflow de seguridad nocturno,
        // no en cada PR: la NVD inestable no debe poder bloquear todo merge.
        assertThat(workflowText())
            .withFailMessage("dependencyCheckAnalyze no debe estar en el CI de PR (corre en security-scan.yml)")
            .doesNotContain("dependencyCheckAnalyze")
    }

    @Test
    fun `el workflow sube el reporte de cobertura como artifact`() {
        val usos = pasos().mapNotNull { it["uses"]?.toString() }
        assertThat(usos)
            .withFailMessage("El workflow debe subir el reporte de cobertura con upload-artifact. Pasos: %s", usos)
            .anyMatch { it.startsWith("actions/upload-artifact") }
    }

    @Test
    fun `el CI no anuncia umbrales de cobertura que no son los suyos`() {
        // El comentario del paso de Kover llego a anunciar "90% dominio, 75% global"
        // mientras el build exigia 63 y 58: quien leia el CI se creia protegido al
        // 90%. La causa raiz fue duplicar la cifra. El workflow no vuelve a nombrar
        // ningun porcentaje de cobertura; el suelo vigente vive en los `minBound` de
        // build.gradle.kts y lo custodia QualityGatesConfigTest.
        val porcentajes = Regex("""\d{1,3}\s?%""").findAll(workflowText()).map { it.value }.toList()
        assertThat(porcentajes)
            .withFailMessage(
                "ci.yml no debe declarar cifras de cobertura: se desincronizan del build y anuncian una " +
                    "proteccion que no existe. Fuente de verdad: los minBound de build.gradle.kts. Encontrado: %s",
                porcentajes,
            ).isEmpty()
    }
}
