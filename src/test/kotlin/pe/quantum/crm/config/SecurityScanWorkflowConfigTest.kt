package pe.quantum.crm.config

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Test de contrato sobre el workflow de escaneo de seguridad (B0.4).
 *
 * El OWASP Dependency-Check se desacopla del gate de cada PR (la NVD publica es
 * inestable y su descarga inicial puede tardar horas). Corre en un workflow
 * dedicado, en cadencia nocturna + manual, con la NVD_API_KEY del secret y cache
 * de la base NVD. Sigue fallando el build ante CVE alto (SECURITY-backend.md §12),
 * solo cambia cuando corre respecto al pipeline de PR (CiPipelineConfigTest).
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

    @Test
    fun `corre en cadencia programada y de forma manual`() {
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
        assertThat(workflowText())
            .withFailMessage("El workflow de seguridad debe ejecutar dependencyCheckAnalyze")
            .contains("dependencyCheckAnalyze")
    }

    @Test
    fun `recibe la NVD_API_KEY del secret`() {
        assertThat(workflowText())
            .withFailMessage(
                "El scan debe recibir NVD_API_KEY desde los secrets del repo " +
                    "(memoria owasp-dependency-check-nvd-apikey)",
            )
            .contains("NVD_API_KEY")
            .contains("secrets.NVD_API_KEY")
    }

    @Test
    fun `cachea la base NVD entre corridas`() {
        assertThat(workflowText().lowercase())
            .withFailMessage("El scan debe cachear la base NVD (dependency-check-data) para no re-descargarla")
            .contains("dependency-check-data")
    }

    @Test
    fun `configura JDK 21 Temurin`() {
        val texto = workflowText()
        assertThat(texto)
            .withFailMessage("El scan debe usar setup-java con Temurin y JDK 21")
            .contains("setup-java")
            .contains("temurin")
        assertThat(texto).contains("21")
    }
}
