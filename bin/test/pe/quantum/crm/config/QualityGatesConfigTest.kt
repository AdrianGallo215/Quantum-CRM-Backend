package pe.quantum.crm.config

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Test de contrato sobre la configuracion de calidad del build (B0.3).
 *
 * Verifica que el `build.gradle.kts` declara los cuatro gates de calidad exigidos
 * por TESTING-backend.md (§8) y SECURITY-backend.md (§12): ktlint, detekt, Kover
 * (con umbrales 90% dominio / 75% global) y OWASP Dependency-Check.
 *
 * No ejecuta los gates (eso se valida corriendo `./gradlew ktlintCheck detekt
 * koverVerify dependencyCheckAnalyze`); evita que la configuracion desaparezca
 * silenciosamente.
 */
class QualityGatesConfigTest {
    private val buildScript: String = File(File(".").canonicalFile, "build.gradle.kts").readText()

    @Test
    fun `el build aplica el plugin de ktlint`() {
        assertThat(buildScript)
            .withFailMessage("build.gradle.kts no aplica el plugin de ktlint")
            .contains("org.jlleitschuh.gradle.ktlint")
    }

    @Test
    fun `el build aplica el plugin de detekt`() {
        assertThat(buildScript)
            .withFailMessage("build.gradle.kts no aplica el plugin de detekt")
            .contains("io.gitlab.arturbosch.detekt")
    }

    @Test
    fun `el build aplica el plugin de Kover`() {
        assertThat(buildScript)
            .withFailMessage("build.gradle.kts no aplica el plugin de Kover")
            .contains("org.jetbrains.kotlinx.kover")
    }

    @Test
    fun `el build aplica OWASP Dependency-Check`() {
        assertThat(buildScript)
            .withFailMessage("build.gradle.kts no aplica OWASP Dependency-Check")
            .contains("org.owasp.dependencycheck")
    }

    @Test
    fun `Kover exige el umbral global de 75 por ciento`() {
        assertThat(buildScript)
            .withFailMessage("Kover debe exigir 75%% de cobertura global (TESTING-backend.md §8)")
            .contains("75")
    }

    @Test
    fun `Kover exige el umbral de 90 por ciento para el dominio`() {
        assertThat(buildScript)
            .withFailMessage("Kover debe exigir 90%% de cobertura para pe.quantum.crm.domain (TESTING-backend.md §8)")
            .contains("90")
            .contains("pe.quantum.crm.domain")
    }
}
