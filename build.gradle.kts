import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    id("org.springframework.boot") version "3.2.5"
    id("io.spring.dependency-management") version "1.1.4"
    kotlin("jvm") version "1.9.25"
    kotlin("plugin.spring") version "1.9.25"
    kotlin("plugin.jpa") version "1.9.25"

    // Gates de calidad (B0.3)
    id("org.jlleitschuh.gradle.ktlint") version "12.1.1"
    id("io.gitlab.arturbosch.detekt") version "1.23.6"
    id("org.jetbrains.kotlinx.kover") version "0.8.3"
    id("org.owasp.dependencycheck") version "10.0.4"
}

group = "pe.quantum"
version = "0.0.1-SNAPSHOT"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

repositories {
    mavenCentral()
}

dependencies {
    // Spring Boot starters
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-validation")

    // Kotlin support
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin")
    implementation("org.jetbrains.kotlin:kotlin-reflect")

    // Migraciones
    implementation("org.flywaydb:flyway-core")

    // Driver
    runtimeOnly("org.postgresql:postgresql")

    // Testing
    testImplementation("org.springframework.boot:spring-boot-starter-test") {
        exclude(group = "org.mockito")
    }
    testImplementation("org.springframework.security:spring-security-test")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.testcontainers:junit-jupiter")
    testImplementation("org.testcontainers:postgresql")
    testImplementation("com.ninja-squad:springmockk:4.0.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

dependencyManagement {
    imports {
        mavenBom("org.testcontainers:testcontainers-bom:1.21.3")
    }
}

kotlin {
    compilerOptions {
        freeCompilerArgs.addAll("-Xjsr305=strict")
    }
}

tasks.withType<KotlinCompile> {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
    }
}

tasks.withType<Test> {
    useJUnitPlatform()
}

// ── Gates de calidad (B0.3) ────────────────────────────────

detekt {
    buildUponDefaultConfig = true
    config.setFrom("$projectDir/config/detekt/detekt.yml")
    autoCorrect = false
}

// detekt 1.23.6 se compilo con Kotlin 1.9.23; el proyecto usa 1.9.25. Alineamos
// SOLO el classpath de la tarea detekt para evitar el error de version.
configurations.matching { it.name == "detekt" }.all {
    resolutionStrategy.eachDependency {
        if (requested.group == "org.jetbrains.kotlin") {
            useVersion("1.9.23")
        }
    }
}

// Cobertura: 75% global, 90% en los servicios de dominio (TESTING-backend.md §8).
// Se excluye el bootstrap de la aplicacion: no contiene logica de negocio.
// El umbral de dominio se aplica sobre una report variant filtrada a
// `pe.quantum.crm.domain` (Kover 0.8 no permite filtros por regla).
kover {
    currentProject {
        createVariant("domain") {
            add("jvm")
        }
    }
    reports {
        filters {
            excludes {
                classes(
                    "pe.quantum.crm.CrmApplication",
                    "pe.quantum.crm.CrmApplicationKt",
                )
            }
        }
        verify {
            rule("Cobertura global minima 75 por ciento") {
                minBound(75)
            }
        }
        variant("domain") {
            filters {
                includes {
                    packages("pe.quantum.crm.domain")
                }
            }
            verify {
                rule("Cobertura de dominio minima 90 por ciento") {
                    minBound(90)
                }
            }
        }
    }
}

// `./gradlew koverVerify` debe verificar tambien la variante de dominio.
tasks.named("koverVerify") {
    dependsOn("koverVerifyDomain")
}

// OWASP Dependency-Check (SECURITY-backend.md §12). El build falla ante CVE alto.
// La NVD requiere API key (env NVD_API_KEY); sin ella el escaneo es mucho mas lento.
dependencyCheck {
    failBuildOnCVSS = 7.0f
    formats = listOf("HTML", "JSON")
    System.getenv("NVD_API_KEY")?.let { nvd.apiKey = it }
}
