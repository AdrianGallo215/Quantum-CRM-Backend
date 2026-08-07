package pe.quantum.crm.config

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.yaml.snakeyaml.Yaml
import java.io.File

/**
 * Test de contrato sobre el workflow de escaneo de seguridad (B0.4).
 *
 * El OWASP Dependency-Check se desacopla del gate de cada PR (la NVD publica es
 * inestable y su descarga inicial puede tardar horas). Corre en un workflow
 * dedicado, en cadencia nocturna + manual, con la NVD_API_KEY del secret y cache
 * de la base NVD. Sigue fallando el build ante CVE alto (SECURITY-backend.md §12),
 * solo cambia cuando corre respecto al pipeline de PR (CiPipelineConfigTest).
 *
 * Como en CiPipelineConfigTest, lo que se puede parsear se parsea: un
 * `contains("21")` sobre el texto crudo lo satisface cualquier "21" del archivo y
 * no prueba que el JDK configurado sea el 21.
 */
class SecurityScanWorkflowConfigTest {
    private val projectRoot = File(".").canonicalFile
    private val workflowFile = File(projectRoot, ".github/workflows/security-scan.yml")

    private fun workflowText(): String {
        assertThat(workflowFile)
            .withFailMessage("Falta el workflow de seguridad en .github/workflows/security-scan.yml")
            .exists()
        return workflowFile.readText()
    }

    @Suppress("UNCHECKED_CAST")
    private fun pasos(): List<Map<String, Any>> {
        val raiz = workflowFile.inputStream().use { Yaml().load(it) as Map<String, Any> }
        val jobs = raiz["jobs"] as Map<String, Any>
        val job = jobs["dependency-check"] as? Map<String, Any>
        assertThat(job).withFailMessage("security-scan.yml no define el job 'dependency-check'").isNotNull
        return job!!["steps"] as List<Map<String, Any>>
    }

    @Test
    fun `corre en cadencia programada y de forma manual`() {
        // SnakeYAML parsea la clave `on:` como el booleano `true` (YAML 1.1),
        // por eso los triggers se validan sobre el texto del workflow.
        val texto = workflowText()
        assertThat(texto)
            .withFailMessage("El scan debe correr programado (schedule/cron)")
            .contains("schedule")
            .contains("cron")
        assertThat(texto)
            .withFailMessage("El scan debe poder dispararse manualmente (workflow_dispatch)")
            .contains("workflow_dispatch")
    }

    @Test
    fun `ejecuta el escaneo de dependencias OWASP`() {
        val comandos = pasos().mapNotNull { it["run"]?.toString() }
        assertThat(comandos)
            .withFailMessage("El workflow de seguridad debe ejecutar dependencyCheckAnalyze. Comandos: %s", comandos)
            .anyMatch { it.contains("dependencyCheckAnalyze") }
    }

    @Suppress("UNCHECKED_CAST")
    @Test
    fun `el paso del escaneo recibe la NVD_API_KEY del secret`() {
        // La key debe llegar al paso que corre el scan, no estar suelta en el
        // archivo: sin ella la descarga de la base NVD es aun mas lenta
        // (memoria owasp-dependency-check-nvd-apikey).
        val pasoScan =
            pasos().firstOrNull { it["run"]?.toString()?.contains("dependencyCheckAnalyze") == true }
        assertThat(pasoScan).withFailMessage("No hay ningun paso que ejecute dependencyCheckAnalyze").isNotNull
        val entorno = pasoScan!!["env"] as? Map<String, Any>
        assertThat(entorno?.get("NVD_API_KEY")?.toString())
            .withFailMessage("El paso del scan debe recibir NVD_API_KEY desde los secrets del repo, fue: %s", entorno)
            .isNotNull()
            .contains("secrets.NVD_API_KEY")
    }

    @Suppress("UNCHECKED_CAST")
    @Test
    fun `cachea la base NVD entre corridas`() {
        val pasoCache = pasos().firstOrNull { it["uses"]?.toString()?.startsWith("actions/cache") == true }
        assertThat(pasoCache)
            .withFailMessage("El scan debe cachear la base NVD para no re-descargarla en cada corrida")
            .isNotNull
        val parametros = pasoCache!!["with"] as? Map<String, Any>
        assertThat(parametros?.get("path")?.toString())
            .withFailMessage("El cache debe apuntar a dependency-check-data, fue: %s", parametros?.get("path"))
            .isNotNull()
            .contains("dependency-check-data")
    }

    @Suppress("UNCHECKED_CAST")
    @Test
    fun `configura JDK 21 Temurin`() {
        val setupJava = pasos().firstOrNull { it["uses"]?.toString()?.startsWith("actions/setup-java") == true }
        assertThat(setupJava).withFailMessage("El scan debe usar actions/setup-java").isNotNull
        val parametros = setupJava!!["with"] as? Map<String, Any>
        assertThat(parametros?.get("distribution")?.toString())
            .withFailMessage("La distribucion del JDK debe ser temurin, fue: %s", parametros?.get("distribution"))
            .isEqualTo("temurin")
        assertThat(parametros?.get("java-version")?.toString())
            .withFailMessage("El JDK debe ser el 21, fue: %s", parametros?.get("java-version"))
            .isEqualTo("21")
    }
}
