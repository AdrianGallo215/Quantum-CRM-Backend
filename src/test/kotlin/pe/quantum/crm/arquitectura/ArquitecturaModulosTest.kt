package pe.quantum.crm.arquitectura

import com.tngtech.archunit.core.domain.JavaClass
import com.tngtech.archunit.core.domain.JavaClasses
import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.core.importer.ImportOption
import com.tngtech.archunit.core.importer.Location
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Frontera entre modulos de dominio (CLAUDE.md regla 12), verificada por ArchUnit.
 *
 * "Un modulo nunca accede a tablas ni entidades de otro modulo. Solo via su
 * interfaz de servicio publica."
 *
 * Hasta ahora esa regla se afirmaba en CLAUDE.md y en el CI sin que nada la
 * comprobara. Estos tests la comprueban sobre el bytecode compilado, no sobre los
 * `import` del fuente: asi tambien caen las dependencias que no se ven como import
 * (genericos, supertipos, anotaciones, nombres cualificados).
 *
 * Que se considera API publica de un modulo:
 *  - sus interfaces (los contratos `*Service`, `OportunidadesDeContacto`, ...),
 *  - sus DTOs (todo lo que vive en el subpaquete `dto`),
 *  - sus enums de contrato (`TipoNotificacion`, `RolEmpleado`, ...), que aparecen
 *    en las firmas de esas interfaces,
 *  - los eventos de aplicacion que publica (`*Event`), que son contrato por
 *    definicion: existen para que otro modulo los consuma.
 *
 * Que NO lo es, y por tanto no puede cruzar la frontera: entidades JPA (las
 * "tablas" de la regla), repositorios e implementaciones (`*Impl`).
 */
class ArquitecturaModulosTest {
    private companion object {
        const val RAIZ_DOMINIO = "pe.quantum.crm.domain."

        /**
         * Solo el bytecode de produccion. `DoNotIncludeTests` no cubre el layout de
         * Gradle+Kotlin (`build/classes/kotlin/test`), asi que se filtra a mano.
         */
        val SOLO_PRODUCCION =
            ImportOption { location: Location ->
                !location.contains("/test/") && !location.contains("/testFixtures/")
            }

        val CLASES: JavaClasses =
            ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_JARS)
                .withImportOption(SOLO_PRODUCCION)
                .importPackages("pe.quantum.crm.domain")
    }

    /** Modulo de dominio al que pertenece la clase, o null si no es de dominio. */
    private fun moduloDe(clase: JavaClass): String? {
        val nombre = raiz(clase).name
        if (!nombre.startsWith(RAIZ_DOMINIO)) return null
        val resto = nombre.removePrefix(RAIZ_DOMINIO)
        return if (resto.contains('.')) resto.substringBefore('.') else null
    }

    /**
     * Tipo de nivel superior: las clases sinteticas de Kotlin (`Companion`,
     * `DefaultImpls`, `WhenMappings`) heredan la naturaleza de quien las contiene.
     */
    private fun raiz(clase: JavaClass): JavaClass {
        var actual = if (clase.isArray) clase.baseComponentType else clase
        while (actual.enclosingClass.isPresent) {
            actual = actual.enclosingClass.get()
        }
        return actual
    }

    private fun esEntidad(clase: JavaClass): Boolean = clase.isAnnotatedWith("jakarta.persistence.Entity")

    private fun esRepositorio(clase: JavaClass): Boolean = clase.simpleName.endsWith("Repository")

    private fun esImplementacion(clase: JavaClass): Boolean = clase.simpleName.endsWith("Impl")

    private fun esApiPublica(clase: JavaClass): Boolean =
        clase.packageName.endsWith(".dto") ||
            clase.packageName.contains(".dto.") ||
            clase.isInterface ||
            clase.isEnum ||
            clase.simpleName.endsWith("Event")

    /** Dependencias entre modulos de dominio distintos, como "origen -> destino (clase)". */
    private fun cruzanFrontera(prohibida: (JavaClass) -> Boolean): List<String> =
        CLASES
            .flatMap { origen ->
                val moduloOrigen = moduloDe(origen) ?: return@flatMap emptyList<String>()
                origen.directDependenciesFromSelf.mapNotNull { dependencia ->
                    val destino = raiz(dependencia.targetClass)
                    val moduloDestino = moduloDe(destino)
                    if (moduloDestino == null || moduloDestino == moduloOrigen || !prohibida(destino)) {
                        null
                    } else {
                        "$moduloOrigen -> $moduloDestino: ${raiz(origen).simpleName} usa ${destino.simpleName}"
                    }
                }
            }.distinct()
            .sorted()

    @Test
    fun `ningun modulo de dominio accede a entidades JPA de otro modulo`() {
        val violaciones = cruzanFrontera { esEntidad(it) }
        assertThat(violaciones)
            .withFailMessage(
                "Las entidades JPA son privadas de su modulo (CLAUDE.md regla 12). " +
                    "Usar la interfaz de servicio del otro modulo y sus DTOs. Violaciones:%n%s",
                violaciones.joinToString(System.lineSeparator()),
            ).isEmpty()
    }

    @Test
    fun `ningun modulo de dominio accede a repositorios de otro modulo`() {
        val violaciones = cruzanFrontera { esRepositorio(it) }
        assertThat(violaciones)
            .withFailMessage(
                "Un repositorio solo lo usa su propio modulo: leer la tabla de otro modulo por su " +
                    "repositorio salta la logica de negocio y los filtros de visibilidad. Violaciones:%n%s",
                violaciones.joinToString(System.lineSeparator()),
            ).isEmpty()
    }

    @Test
    fun `ningun modulo de dominio depende de la implementacion de otro modulo`() {
        val violaciones = cruzanFrontera { esImplementacion(it) }
        assertThat(violaciones)
            .withFailMessage(
                "La dependencia debe ir a la interfaz de servicio, nunca a su `*Impl`. Violaciones:%n%s",
                violaciones.joinToString(System.lineSeparator()),
            ).isEmpty()
    }

    @Test
    fun `las dependencias entre modulos solo apuntan a la API publica del modulo destino`() {
        val violaciones =
            cruzanFrontera { destino ->
                esEntidad(destino) || esRepositorio(destino) || esImplementacion(destino) || !esApiPublica(destino)
            }
        assertThat(violaciones)
            .withFailMessage(
                "Cruzar la frontera de un modulo solo esta permitido hacia su API publica: interfaces de " +
                    "servicio, DTOs (subpaquete `dto`), enums de contrato y eventos publicados (`*Event`). " +
                    "Violaciones:%n%s",
                violaciones.joinToString(System.lineSeparator()),
            ).isEmpty()
    }

    @Test
    fun `el importador ve las clases de dominio (guarda contra un test que no prueba nada)`() {
        // Si el filtro de importacion se rompe, los tests de arriba pasarian sobre
        // una lista vacia y darian una garantia falsa. Esto lo impide.
        val modulos = CLASES.mapNotNull { moduloDe(it) }.distinct()
        assertThat(modulos)
            .withFailMessage("ArchUnit no importo el bytecode de dominio; las reglas no estarian verificando nada")
            .hasSizeGreaterThanOrEqualTo(10)
        assertThat(CLASES.count { esEntidad(it) })
            .withFailMessage("ArchUnit no reconoce ninguna entidad JPA; la metadata importada no es la real")
            .isGreaterThan(0)
    }
}
