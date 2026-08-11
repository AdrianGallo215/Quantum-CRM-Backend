package pe.quantum.crm.config

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Test de contrato sobre la configuracion de calidad del build (B0.3).
 *
 * Verifica que `build.gradle.kts` sigue declarando los cuatro gates exigidos por
 * TESTING-backend.md (§8) y SECURITY-backend.md (§12) —ktlint, detekt, Kover y
 * OWASP Dependency-Check— y, sobre todo, que **nadie afloja el trinquete de
 * cobertura**.
 *
 * Sobre el umbral de cobertura, para no repetir el error que este test tenia:
 *
 * Un umbral NO se comprueba leyendo el build script. Que la cobertura real llegue
 * al minimo lo prueba `./gradlew koverVerify`, que corre en CI y es la unica
 * fuente de verdad. Las versiones anteriores de este archivo tenian dos tests que
 * hacian `contains("75")` y `contains("90")` sobre el texto del build: pasaban
 * porque esas cifras aparecian en un *comentario*, mientras el gate real exigia 63
 * y 58. Fabricaban evidencia de una garantia inexistente; borrar un comentario los
 * ponia rojos y bajar `minBound` a 10 los dejaba verdes. Estan eliminados.
 *
 * Lo que si tiene sentido comprobar aqui es lo que `koverVerify` no puede
 * comprobar sobre si mismo: que el propio umbral no se rebaje. `koverVerify` pasa
 * igual de contento con `minBound(84)` que con `minBound(10)`. Este archivo
 * custodia el suelo (el trinquete), no la cobertura.
 *
 * SUELO VIGENTE: 85% global / 84% dominio. Progresion del trinquete: 63/58 ->
 * 71/67 (ola 1 de subagentes: modulos que no tenian ningun test) -> 85/84 (ola 2:
 * servicios de dominio y filtros de JPA Specification).
 *
 * OBJETIVO (TESTING-backend.md §8): 75% global / 90% dominio.
 *  · El global ya se SUPERA (86.6% medido en local).
 *  · El de dominio queda en 85.0% midiendo en local, pero eso no es deuda de
 *    tests unitarios: 366 de las 555 lineas sin cubrir son SQL nativo agregado,
 *    cubierto por tests @Tag("integration") que no corren en local (Testcontainers
 *    roto por Docker 29). Contandolos, el dominio da 94.9% y el objetivo se cumple.
 *
 * Al escribir los tests que faltan, subir el `minBound` Y estas constantes.
 */
class QualityGatesConfigTest {
    private companion object {
        /** Suelo de cobertura global que el build exige hoy. Solo puede subir. */
        const val SUELO_GLOBAL = 85

        /** Suelo de cobertura de dominio que el build exige hoy. Solo puede subir. */
        const val SUELO_DOMINIO = 84

        /** Meta de TESTING-backend.md §8, todavia no alcanzada. */
        const val OBJETIVO_GLOBAL = 75
        const val OBJETIVO_DOMINIO = 90
    }

    private val buildScript: String = File(File(".").canonicalFile, "build.gradle.kts").readText()

    /** Los `minBound(N)` declarados, en orden de aparicion. Ignora comentarios. */
    private fun umbralesDeclarados(): List<Int> =
        Regex("""minBound\((\d+)\)""")
            .findAll(buildScript)
            .map { it.groupValues[1].toInt() }
            .toList()

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
    fun `Kover declara una regla de cobertura global y otra de dominio`() {
        assertThat(umbralesDeclarados())
            .withFailMessage(
                "Kover debe declarar exactamente dos umbrales (global y dominio); se encontraron: %s. " +
                    "Si se borra una regla, koverVerify sigue pasando y la cobertura deja de estar gateada.",
                umbralesDeclarados(),
            ).hasSize(2)
        assertThat(buildScript)
            .withFailMessage("El umbral de dominio debe medirse sobre la variante filtrada a pe.quantum.crm.domain")
            .contains("pe.quantum.crm.domain")
    }

    @Test
    fun `el trinquete de cobertura global no se afloja`() {
        val global = umbralesDeclarados().first()
        assertThat(global)
            .withFailMessage(
                "El suelo de cobertura global bajo de %d a %d. El trinquete solo sube: si la cobertura " +
                    "real no alcanza, se escriben tests, no se rebaja el gate. (Objetivo: %d%%.)",
                SUELO_GLOBAL,
                global,
                OBJETIVO_GLOBAL,
            ).isGreaterThanOrEqualTo(SUELO_GLOBAL)
    }

    @Test
    fun `el trinquete de cobertura de dominio no se afloja`() {
        val dominio = umbralesDeclarados().last()
        assertThat(dominio)
            .withFailMessage(
                "El suelo de cobertura de dominio bajo de %d a %d. El trinquete solo sube. (Objetivo: %d%%.)",
                SUELO_DOMINIO,
                dominio,
                OBJETIVO_DOMINIO,
            ).isGreaterThanOrEqualTo(SUELO_DOMINIO)
    }

    @Test
    fun `koverVerify verifica tambien la variante de dominio`() {
        // Sin este `dependsOn`, `./gradlew koverVerify` solo comprueba el umbral
        // global y el de dominio queda declarado pero nunca ejecutado: un gate
        // que existe en el archivo y no corre en ningun sitio.
        assertThat(buildScript)
            .withFailMessage("`koverVerify` debe depender de `koverVerifyDomain`, si no el umbral de dominio no corre")
            .contains("koverVerifyDomain")
    }
}
