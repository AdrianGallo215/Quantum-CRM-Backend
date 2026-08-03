# Importación CSV temporal de empresas — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Agregar un endpoint temporal `POST /api/v1/import-csv-temp/empresas` que crea empresas en lote desde un CSV de 3 columnas (RUC, Razón Social, Segmento), en modo "mejor esfuerzo" (las filas válidas se crean, las inválidas se reportan con motivo).

**Architecture:** Módulo nuevo y autocontenido `pe.quantum.crm.importcsvtemp` (fuera de `domain/`, para poder borrarlo entero más adelante). Solo llama a `EmpresaService.crear(...)` — nunca toca `EmpresaRepository` ni la entidad `Empresa` — para reutilizar toda la validación de negocio existente sin duplicarla. Cada fila corre en la transacción propia de `EmpresaService.crear` (no hay `@Transactional` de archivo completo), lo que da el modo "mejor esfuerzo" gratis y de paso detecta RUC repetido dentro del mismo CSV.

**Tech Stack:** Kotlin 1.9 · Spring Boot 3.2 (`@RestController`, `MultipartFile`) · MockK (tests unitarios) · Spring Boot Test + `springmockk` (`@MockkBean`) para el test del controller.

## Global Constraints

- Spec de referencia: `docs/superpowers/specs/2026-07-09-import-csv-temp-empresas-design.md`. Cualquier ambigüedad se resuelve con ese documento.
- TDD estricto (`TESTING-backend.md`): escribir el test primero, verificar que falla (o no compila — ambos son RED válidos en este repo), luego el código mínimo, verificar que pasa, commitear.
- El módulo vive en `src/main/kotlin/pe/quantum/crm/importcsvtemp/` (fuera de `domain/`). Sus tests en `src/test/kotlin/pe/quantum/crm/importcsvtemp/`.
- Acceso a empresas **solo** vía `EmpresaService.crear(request: CrearEmpresaRequest, usuario: UsuarioActual): EmpresaDetalleDto` (interfaz pública ya existente en `pe.quantum.crm.domain.empresas`). Nunca `EmpresaRepository` ni `Empresa` desde este módulo.
- JSON de respuesta en snake_case automático (`spring.jackson.property-naming-strategy=SNAKE_CASE`, `application.properties:18`) — los DTOs se escriben en camelCase Kotlin como el resto del proyecto, Jackson hace la conversión.
- No se toca `contrato_api.md` ni `matriz_permisos.md` — endpoint temporal, fuera del contrato estable.
- Sin `@PreAuthorize`: cualquier rol autenticado puede usar el endpoint (mismo permiso que `POST /empresas`).
- Límite: 1000 filas de datos por archivo.
- Ningún paquete nuevo de dependencias en `build.gradle.kts` (el parser CSV es código propio, ya no hay ninguna librería CSV en el proyecto).
- Antes de cada commit, los tests nuevos deben pasar con `./gradlew test`.

---

### Task 1: DTOs, interfaz y validaciones por fila (mejor esfuerzo)

**Files:**
- Create: `src/main/kotlin/pe/quantum/crm/importcsvtemp/dto/ImportCsvTempDtos.kt`
- Create: `src/main/kotlin/pe/quantum/crm/importcsvtemp/ImportCsvTempService.kt`
- Create: `src/main/kotlin/pe/quantum/crm/importcsvtemp/ImportCsvTempServiceImpl.kt`
- Test: `src/test/kotlin/pe/quantum/crm/importcsvtemp/ImportCsvTempServiceImplTest.kt`

**Interfaces:**
- Consumes: `pe.quantum.crm.domain.empresas.EmpresaService.crear(request: CrearEmpresaRequest, usuario: UsuarioActual): EmpresaDetalleDto` (lanza `RucDuplicadoException` u otras `ApiException` de `pe.quantum.crm.shared.exception` si la fila es inválida). `pe.quantum.crm.domain.empresas.dto.CrearEmpresaRequest(ruc: String, razonSocial: String, segmentos: List<Segmento>? = null, ...)`. `pe.quantum.crm.shared.enums.Segmento` (enum: `urbano`, `personal`, `turismo`, `interprovincial`). `pe.quantum.crm.shared.security.UsuarioActual(id: Long, rol: String)`.
- Produces: `ImportCsvTempService.importarEmpresas(archivo: MultipartFile, usuario: UsuarioActual): ImportEmpresasResultDto` — la firma que usará el controller en la Task 3. `ImportEmpresasResultDto(totalFilas: Int, creadas: Int, conError: Int, detalle: List<ImportEmpresaFilaResultado>)`. `ImportEmpresaFilaResultado(fila: Int, ruc: String?, razonSocial: String?, estado: String, motivo: String?)` — `estado` es `"creada"` o `"error"`.

- [ ] **Step 1: Escribir los tests que fallan (la clase de producción aún no existe)**

Crear `src/test/kotlin/pe/quantum/crm/importcsvtemp/ImportCsvTempServiceImplTest.kt`:

```kotlin
package pe.quantum.crm.importcsvtemp

import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockMultipartFile
import pe.quantum.crm.domain.empresas.EmpresaService
import pe.quantum.crm.domain.empresas.dto.CrearEmpresaRequest
import pe.quantum.crm.domain.empresas.dto.EmpresaDetalleDto
import pe.quantum.crm.shared.enums.Segmento
import pe.quantum.crm.shared.exception.RucDuplicadoException
import pe.quantum.crm.shared.security.UsuarioActual
import java.time.LocalDateTime

/**
 * Unit tests de ImportCsvTempServiceImpl sin Spring ni base de datos: EmpresaService
 * se mockea directamente con MockK (mismo patron que EventoServiceImplTest).
 */
class ImportCsvTempServiceImplTest {
    private val empresaService = mockk<EmpresaService>()
    private val service = ImportCsvTempServiceImpl(empresaService)

    private val usuario = UsuarioActual(id = 1, rol = "vendedor")

    private fun csv(vararg filasDeDatos: String): MockMultipartFile {
        val contenido = (listOf("ruc,razon_social,segmento") + filasDeDatos.toList()).joinToString("\n")
        return MockMultipartFile("file", "empresas.csv", "text/csv", contenido.toByteArray(Charsets.UTF_8))
    }

    private fun empresaDetalleDto(
        ruc: String,
        razonSocial: String,
    ) = EmpresaDetalleDto(
        id = 1,
        ruc = ruc,
        razonSocial = razonSocial,
        actividadEcon = null,
        ciiu = null,
        sectorIndustrial = null,
        estadoSunat = null,
        condicionSunat = null,
        direccionFiscal = null,
        ubicacionReal = null,
        distrito = null,
        provincia = null,
        departamento = null,
        avalFiador = null,
        origenLead = null,
        estadoCartera = "no_contactado",
        fileDrive = null,
        sitioWeb = null,
        notas = null,
        idVendedor = null,
        vendedor = null,
        segmentos = listOf("urbano"),
        contactos = null,
        createdAt = LocalDateTime.now(),
        createdBy = 1,
    )

    @Test
    fun `fila valida crea la empresa via EmpresaService`() {
        val slot = slot<CrearEmpresaRequest>()
        every { empresaService.crear(capture(slot), usuario) } answers {
            empresaDetalleDto(ruc = slot.captured.ruc, razonSocial = slot.captured.razonSocial)
        }

        val resultado = service.importarEmpresas(csv("20999999999,Beta SRL,urbano"), usuario)

        assertThat(resultado.totalFilas).isEqualTo(1)
        assertThat(resultado.creadas).isEqualTo(1)
        assertThat(resultado.conError).isEqualTo(0)
        assertThat(resultado.detalle.single().estado).isEqualTo("creada")
        assertThat(slot.captured.ruc).isEqualTo("20999999999")
        assertThat(slot.captured.razonSocial).isEqualTo("Beta SRL")
        assertThat(slot.captured.segmentos).containsExactly(Segmento.urbano)
    }

    @Test
    fun `ruc con menos de 11 digitos queda en error y no aborta el archivo`() {
        every { empresaService.crear(match { it.ruc == "20999999999" }, usuario) } returns
            empresaDetalleDto(ruc = "20999999999", razonSocial = "Beta SRL")

        val resultado = service.importarEmpresas(csv("123,Empresa Corta,urbano", "20999999999,Beta SRL,urbano"), usuario)

        assertThat(resultado.totalFilas).isEqualTo(2)
        assertThat(resultado.creadas).isEqualTo(1)
        assertThat(resultado.conError).isEqualTo(1)
        val filaInvalida = resultado.detalle.first { it.fila == 2 }
        assertThat(filaInvalida.estado).isEqualTo("error")
        assertThat(filaInvalida.motivo).isEqualTo("RUC debe tener 11 dígitos")
    }

    @Test
    fun `fila con menos de 3 columnas queda en error`() {
        val resultado = service.importarEmpresas(csv("20999999999,Beta SRL"), usuario)

        assertThat(resultado.detalle.single().estado).isEqualTo("error")
        assertThat(resultado.detalle.single().motivo).contains("Fila incompleta")
    }

    @Test
    fun `segmento desconocido queda en error y no llama a EmpresaService`() {
        val resultado = service.importarEmpresas(csv("20999999999,Beta SRL,corporativo"), usuario)

        assertThat(resultado.detalle.single().estado).isEqualTo("error")
        assertThat(resultado.detalle.single().motivo).isEqualTo("Segmento desconocido: corporativo")
        verify(exactly = 0) { empresaService.crear(any(), any()) }
    }

    @Test
    fun `ruc ya existente en BD queda en error con el motivo de RucDuplicadoException`() {
        every { empresaService.crear(any(), usuario) } throws RucDuplicadoException()

        val resultado = service.importarEmpresas(csv("20999999999,Beta SRL,urbano"), usuario)

        assertThat(resultado.detalle.single().estado).isEqualTo("error")
        assertThat(resultado.detalle.single().motivo).isEqualTo("Esta empresa ya está registrada en el sistema")
    }

    @Test
    fun `dos filas con el mismo ruc - la primera se crea y la segunda queda en error por duplicado`() {
        var primeraLlamada = true
        every { empresaService.crear(match { it.ruc == "20999999999" }, usuario) } answers {
            if (primeraLlamada) {
                primeraLlamada = false
                empresaDetalleDto(ruc = "20999999999", razonSocial = "Beta SRL")
            } else {
                throw RucDuplicadoException()
            }
        }

        val resultado =
            service.importarEmpresas(
                csv("20999999999,Beta SRL,urbano", "20999999999,Beta SRL Duplicada,turismo"),
                usuario,
            )

        assertThat(resultado.detalle[0].estado).isEqualTo("creada")
        assertThat(resultado.detalle[1].estado).isEqualTo("error")
        assertThat(resultado.detalle[1].motivo).isEqualTo("Esta empresa ya está registrada en el sistema")
    }
}
```

- [ ] **Step 2: Ejecutar y verificar que falla (no compila: las clases de producción no existen)**

Run: `./gradlew test --tests "pe.quantum.crm.importcsvtemp.ImportCsvTempServiceImplTest"`
Expected: FAIL — error de compilación, `ImportCsvTempServiceImpl`, `ImportCsvTempService`, `EmpresaDetalleDto`... (los dos primeros) no resuelven porque no existen todavía.

- [ ] **Step 3: Crear los DTOs**

Crear `src/main/kotlin/pe/quantum/crm/importcsvtemp/dto/ImportCsvTempDtos.kt`:

```kotlin
package pe.quantum.crm.importcsvtemp.dto

/** Resultado de procesar una fila del CSV de importación de empresas. */
data class ImportEmpresaFilaResultado(
    val fila: Int,
    val ruc: String?,
    val razonSocial: String?,
    val estado: String,
    val motivo: String?,
)

/** Resultado agregado de `POST /import-csv-temp/empresas`. */
data class ImportEmpresasResultDto(
    val totalFilas: Int,
    val creadas: Int,
    val conError: Int,
    val detalle: List<ImportEmpresaFilaResultado>,
)
```

- [ ] **Step 4: Crear la interfaz del servicio**

Crear `src/main/kotlin/pe/quantum/crm/importcsvtemp/ImportCsvTempService.kt`:

```kotlin
package pe.quantum.crm.importcsvtemp

import org.springframework.web.multipart.MultipartFile
import pe.quantum.crm.importcsvtemp.dto.ImportEmpresasResultDto
import pe.quantum.crm.shared.security.UsuarioActual

/** Interfaz del módulo temporal de importación de empresas por CSV. */
interface ImportCsvTempService {
    fun importarEmpresas(
        archivo: MultipartFile,
        usuario: UsuarioActual,
    ): ImportEmpresasResultDto
}
```

- [ ] **Step 5: Implementar el servicio (validaciones por fila, mejor esfuerzo)**

Crear `src/main/kotlin/pe/quantum/crm/importcsvtemp/ImportCsvTempServiceImpl.kt`:

```kotlin
package pe.quantum.crm.importcsvtemp

import org.springframework.stereotype.Service
import org.springframework.web.multipart.MultipartFile
import pe.quantum.crm.domain.empresas.EmpresaService
import pe.quantum.crm.domain.empresas.dto.CrearEmpresaRequest
import pe.quantum.crm.importcsvtemp.dto.ImportEmpresaFilaResultado
import pe.quantum.crm.importcsvtemp.dto.ImportEmpresasResultDto
import pe.quantum.crm.shared.enums.Segmento
import pe.quantum.crm.shared.exception.ApiException
import pe.quantum.crm.shared.security.UsuarioActual

/**
 * Importación "mejor esfuerzo" de empresas desde CSV: cada fila corre en la
 * transacción propia de `EmpresaService.crear`, así que una fila inválida no
 * revierte las demás y un RUC repetido dentro del mismo archivo se detecta solo
 * (la fila anterior ya commiteó antes de procesar la siguiente).
 */
@Service
class ImportCsvTempServiceImpl(
    private val empresaService: EmpresaService,
) : ImportCsvTempService {
    override fun importarEmpresas(
        archivo: MultipartFile,
        usuario: UsuarioActual,
    ): ImportEmpresasResultDto {
        val lineas =
            archivo.inputStream.bufferedReader(Charsets.UTF_8).readLines()
                .map { it.removeSuffix("\r") }
                .filter { it.isNotBlank() }
        val filasDatos = lineas.drop(1)

        val detalle =
            filasDatos.mapIndexed { indice, linea ->
                procesarFila(fila = indice + 2, linea = linea, usuario = usuario)
            }
        val creadas = detalle.count { it.estado == ESTADO_CREADA }
        return ImportEmpresasResultDto(
            totalFilas = detalle.size,
            creadas = creadas,
            conError = detalle.size - creadas,
            detalle = detalle,
        )
    }

    private fun procesarFila(
        fila: Int,
        linea: String,
        usuario: UsuarioActual,
    ): ImportEmpresaFilaResultado {
        val campos = linea.split(",")
        if (campos.size < 3) {
            return ImportEmpresaFilaResultado(
                fila = fila,
                ruc = campos.getOrNull(0)?.trim(),
                razonSocial = campos.getOrNull(1)?.trim(),
                estado = ESTADO_ERROR,
                motivo = "Fila incompleta: se esperaban 3 columnas (ruc, razon_social, segmento)",
            )
        }
        val ruc = campos[0].trim()
        val razonSocial = campos[1].trim()
        val segmentoTexto = campos[2].trim()

        if (!RUC_REGEX.matches(ruc)) {
            return ImportEmpresaFilaResultado(fila, ruc, razonSocial, ESTADO_ERROR, "RUC debe tener 11 dígitos")
        }
        if (razonSocial.isBlank()) {
            return ImportEmpresaFilaResultado(fila, ruc, razonSocial, ESTADO_ERROR, "Razón social no puede estar vacía")
        }
        val segmento =
            runCatching { Segmento.valueOf(segmentoTexto.lowercase()) }.getOrNull()
                ?: return ImportEmpresaFilaResultado(fila, ruc, razonSocial, ESTADO_ERROR, "Segmento desconocido: $segmentoTexto")

        return try {
            val request = CrearEmpresaRequest(ruc = ruc, razonSocial = razonSocial, segmentos = listOf(segmento))
            empresaService.crear(request, usuario)
            ImportEmpresaFilaResultado(fila, ruc, razonSocial, ESTADO_CREADA, null)
        } catch (ex: ApiException) {
            ImportEmpresaFilaResultado(fila, ruc, razonSocial, ESTADO_ERROR, ex.message)
        }
    }

    private companion object {
        const val ESTADO_CREADA = "creada"
        const val ESTADO_ERROR = "error"
        val RUC_REGEX = Regex("\\d{11}")
    }
}
```

- [ ] **Step 6: Ejecutar y verificar que pasa**

Run: `./gradlew test --tests "pe.quantum.crm.importcsvtemp.ImportCsvTempServiceImplTest"`
Expected: PASS (6 tests verdes).

- [ ] **Step 7: Formatear y commitear**

Run: `./gradlew ktlintFormat`

```bash
git add src/main/kotlin/pe/quantum/crm/importcsvtemp/dto/ImportCsvTempDtos.kt src/main/kotlin/pe/quantum/crm/importcsvtemp/ImportCsvTempService.kt src/main/kotlin/pe/quantum/crm/importcsvtemp/ImportCsvTempServiceImpl.kt src/test/kotlin/pe/quantum/crm/importcsvtemp/ImportCsvTempServiceImplTest.kt
git commit -m "feat(import-csv-temp): validaciones por fila y creación vía EmpresaService"
```

---

### Task 2: Validaciones de archivo completo + parser CSV con comillas

**Files:**
- Modify: `src/main/kotlin/pe/quantum/crm/importcsvtemp/ImportCsvTempServiceImpl.kt` (reemplazo completo — ver Step 3)
- Modify: `src/test/kotlin/pe/quantum/crm/importcsvtemp/ImportCsvTempServiceImplTest.kt` (agregar 5 tests al final de la clase, antes del `}` de cierre)

**Interfaces:**
- Consumes: lo mismo que Task 1. Además `pe.quantum.crm.shared.exception.ValidacionException(message: String, field: String? = null)` (400 `VALIDACION`).
- Produces: sin cambios en la firma pública de `ImportCsvTempService` — solo se endurece la validación interna de `ImportCsvTempServiceImpl`.

- [ ] **Step 1: Escribir los tests que fallan**

Agregar estos 5 métodos dentro de la clase `ImportCsvTempServiceImplTest`, después del último test existente (`dos filas con el mismo ruc...`) y antes del `}` final de la clase:

```kotlin
    @Test
    fun `archivo vacio lanza ValidacionException`() {
        val archivo = MockMultipartFile("file", "empresas.csv", "text/csv", ByteArray(0))

        assertThrows<ValidacionException> { service.importarEmpresas(archivo, usuario) }
    }

    @Test
    fun `archivo con solo cabecera lanza ValidacionException`() {
        assertThrows<ValidacionException> { service.importarEmpresas(csv(), usuario) }
    }

    @Test
    fun `archivo con mas de 1000 filas de datos lanza ValidacionException`() {
        val filas = (1..1001).map { "fila-de-datos-$it" }.toTypedArray()

        assertThrows<ValidacionException> { service.importarEmpresas(csv(*filas), usuario) }
    }

    @Test
    fun `razon social con coma entre comillas se parsea completa`() {
        val slot = slot<CrearEmpresaRequest>()
        every { empresaService.crear(capture(slot), usuario) } answers {
            empresaDetalleDto(ruc = slot.captured.ruc, razonSocial = slot.captured.razonSocial)
        }

        service.importarEmpresas(csv("""20999999999,"Empresa S.A., Sucursal Lima",urbano"""), usuario)

        assertThat(slot.captured.razonSocial).isEqualTo("Empresa S.A., Sucursal Lima")
    }

    @Test
    fun `archivo que no se puede leer lanza ValidacionException`() {
        val archivo = mockk<MultipartFile>()
        every { archivo.isEmpty } returns false
        every { archivo.inputStream } throws IOException("boom")

        assertThrows<ValidacionException> { service.importarEmpresas(archivo, usuario) }
    }
```

Agregar estos imports al inicio del archivo de test, junto a los existentes:

```kotlin
import org.junit.jupiter.api.assertThrows
import org.springframework.web.multipart.MultipartFile
import pe.quantum.crm.shared.exception.ValidacionException
import java.io.IOException
```

- [ ] **Step 2: Ejecutar y verificar que fallan**

Run: `./gradlew test --tests "pe.quantum.crm.importcsvtemp.ImportCsvTempServiceImplTest"`
Expected: FAIL — los 5 tests nuevos fallan porque `ImportCsvTempServiceImpl` todavía no valida archivo vacío, cabecera sola, límite de filas, ni parsea comillas (la coma dentro de `"Empresa S.A., Sucursal Lima"` se corta como columna extra), ni envuelve `IOException`.

- [ ] **Step 3: Reemplazar `ImportCsvTempServiceImpl.kt` completo**

Reemplazar todo el contenido de `src/main/kotlin/pe/quantum/crm/importcsvtemp/ImportCsvTempServiceImpl.kt` por:

```kotlin
package pe.quantum.crm.importcsvtemp

import org.springframework.stereotype.Service
import org.springframework.web.multipart.MultipartFile
import pe.quantum.crm.domain.empresas.EmpresaService
import pe.quantum.crm.domain.empresas.dto.CrearEmpresaRequest
import pe.quantum.crm.importcsvtemp.dto.ImportEmpresaFilaResultado
import pe.quantum.crm.importcsvtemp.dto.ImportEmpresasResultDto
import pe.quantum.crm.shared.enums.Segmento
import pe.quantum.crm.shared.exception.ApiException
import pe.quantum.crm.shared.exception.ValidacionException
import pe.quantum.crm.shared.security.UsuarioActual
import java.io.IOException

/**
 * Importación "mejor esfuerzo" de empresas desde CSV: cada fila corre en la
 * transacción propia de `EmpresaService.crear`, así que una fila inválida no
 * revierte las demás y un RUC repetido dentro del mismo archivo se detecta solo
 * (la fila anterior ya commiteó antes de procesar la siguiente).
 */
@Service
class ImportCsvTempServiceImpl(
    private val empresaService: EmpresaService,
) : ImportCsvTempService {
    override fun importarEmpresas(
        archivo: MultipartFile,
        usuario: UsuarioActual,
    ): ImportEmpresasResultDto {
        if (archivo.isEmpty) {
            throw ValidacionException("El archivo CSV está vacío")
        }
        val lineas =
            try {
                archivo.inputStream.bufferedReader(Charsets.UTF_8).readLines()
                    .map { it.removeSuffix("\r") }
                    .filter { it.isNotBlank() }
            } catch (ex: IOException) {
                throw ValidacionException("No se pudo leer el archivo CSV")
            }
        if (lineas.size < 2) {
            throw ValidacionException("El archivo CSV no tiene filas de datos, solo cabecera")
        }
        val filasDatos = lineas.drop(1)
        if (filasDatos.size > MAX_FILAS_DATOS) {
            throw ValidacionException("El archivo excede el máximo de $MAX_FILAS_DATOS filas de datos")
        }

        val detalle =
            filasDatos.mapIndexed { indice, linea ->
                procesarFila(fila = indice + 2, linea = linea, usuario = usuario)
            }
        val creadas = detalle.count { it.estado == ESTADO_CREADA }
        return ImportEmpresasResultDto(
            totalFilas = detalle.size,
            creadas = creadas,
            conError = detalle.size - creadas,
            detalle = detalle,
        )
    }

    private fun procesarFila(
        fila: Int,
        linea: String,
        usuario: UsuarioActual,
    ): ImportEmpresaFilaResultado {
        val campos = parseCsvLine(linea)
        if (campos.size < 3) {
            return ImportEmpresaFilaResultado(
                fila = fila,
                ruc = campos.getOrNull(0)?.trim(),
                razonSocial = campos.getOrNull(1)?.trim(),
                estado = ESTADO_ERROR,
                motivo = "Fila incompleta: se esperaban 3 columnas (ruc, razon_social, segmento)",
            )
        }
        val ruc = campos[0].trim()
        val razonSocial = campos[1].trim()
        val segmentoTexto = campos[2].trim()

        if (!RUC_REGEX.matches(ruc)) {
            return ImportEmpresaFilaResultado(fila, ruc, razonSocial, ESTADO_ERROR, "RUC debe tener 11 dígitos")
        }
        if (razonSocial.isBlank()) {
            return ImportEmpresaFilaResultado(fila, ruc, razonSocial, ESTADO_ERROR, "Razón social no puede estar vacía")
        }
        val segmento =
            runCatching { Segmento.valueOf(segmentoTexto.lowercase()) }.getOrNull()
                ?: return ImportEmpresaFilaResultado(fila, ruc, razonSocial, ESTADO_ERROR, "Segmento desconocido: $segmentoTexto")

        return try {
            val request = CrearEmpresaRequest(ruc = ruc, razonSocial = razonSocial, segmentos = listOf(segmento))
            empresaService.crear(request, usuario)
            ImportEmpresaFilaResultado(fila, ruc, razonSocial, ESTADO_CREADA, null)
        } catch (ex: ApiException) {
            ImportEmpresaFilaResultado(fila, ruc, razonSocial, ESTADO_ERROR, ex.message)
        }
    }

    /** Parser CSV mínimo: soporta campos entre comillas dobles con comas internas. */
    private fun parseCsvLine(linea: String): List<String> {
        val campos = mutableListOf<String>()
        val actual = StringBuilder()
        var dentroComillas = false
        var i = 0
        while (i < linea.length) {
            val c = linea[i]
            when {
                c == '"' && dentroComillas && i + 1 < linea.length && linea[i + 1] == '"' -> {
                    actual.append('"')
                    i++
                }
                c == '"' -> dentroComillas = !dentroComillas
                c == ',' && !dentroComillas -> {
                    campos.add(actual.toString())
                    actual.clear()
                }
                else -> actual.append(c)
            }
            i++
        }
        campos.add(actual.toString())
        return campos
    }

    private companion object {
        const val MAX_FILAS_DATOS = 1000
        const val ESTADO_CREADA = "creada"
        const val ESTADO_ERROR = "error"
        val RUC_REGEX = Regex("\\d{11}")
    }
}
```

- [ ] **Step 4: Ejecutar y verificar que todos los tests pasan (11 en total)**

Run: `./gradlew test --tests "pe.quantum.crm.importcsvtemp.ImportCsvTempServiceImplTest"`
Expected: PASS (11 tests verdes).

- [ ] **Step 5: Formatear y commitear**

Run: `./gradlew ktlintFormat`

```bash
git add src/main/kotlin/pe/quantum/crm/importcsvtemp/ImportCsvTempServiceImpl.kt src/test/kotlin/pe/quantum/crm/importcsvtemp/ImportCsvTempServiceImplTest.kt
git commit -m "feat(import-csv-temp): validar archivo completo y soportar comillas en el CSV"
```

---

### Task 3: Controller + wiring de autenticación

**Files:**
- Create: `src/main/kotlin/pe/quantum/crm/importcsvtemp/ImportCsvTempController.kt`
- Test: `src/test/kotlin/pe/quantum/crm/importcsvtemp/ImportCsvTempControllerTest.kt`

**Interfaces:**
- Consumes: `ImportCsvTempService.importarEmpresas(archivo: MultipartFile, usuario: UsuarioActual): ImportEmpresasResultDto` (Task 1/2). `pe.quantum.crm.shared.security.UsuarioActualProvider.actual(): UsuarioActual` (ya existe, lee el `SecurityContext`). `pe.quantum.crm.shared.ApiResponse.ok(data: T): ApiResponse<T>` (ya existe).
- Produces: endpoint `POST /api/v1/import-csv-temp/empresas` — sin consumidores dentro de este plan (es el punto de entrada final).

- [ ] **Step 1: Escribir el test que falla (el controller aún no existe)**

Crear `src/test/kotlin/pe/quantum/crm/importcsvtemp/ImportCsvTempControllerTest.kt`:

```kotlin
package pe.quantum.crm.importcsvtemp

import com.ninjasquad.springmockk.MockkBean
import io.mockk.every
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.http.HttpHeaders
import org.springframework.mock.web.MockMultipartFile
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import pe.quantum.crm.config.security.JwtService
import pe.quantum.crm.importcsvtemp.dto.ImportEmpresaFilaResultado
import pe.quantum.crm.importcsvtemp.dto.ImportEmpresasResultDto
import pe.quantum.crm.shared.exception.ValidacionException
import pe.quantum.crm.shared.security.UsuarioActual
import pe.quantum.crm.support.SinBaseDeDatosMocks

/**
 * Tests del endpoint temporal `POST /import-csv-temp/empresas` (B08-temp), sin base
 * de datos: ImportCsvTempService se mockea. Ejercita la cadena de seguridad (mismo
 * patron que AuthControllerWebMvcTest / EmpleadoMeControllerTest) y el envelope.
 */
@SpringBootTest(
    properties = [
        "spring.autoconfigure.exclude=" +
            "org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration," +
            "org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration," +
            "org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration",
    ],
)
@AutoConfigureMockMvc
@Import(SinBaseDeDatosMocks::class)
class ImportCsvTempControllerTest {
    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var jwtService: JwtService

    @MockkBean
    lateinit var importCsvTempService: ImportCsvTempService

    private val archivo =
        MockMultipartFile(
            "file",
            "empresas.csv",
            "text/csv",
            "ruc,razon_social,segmento\n20999999999,Beta SRL,urbano".toByteArray(),
        )

    @Test
    fun `importar sin token devuelve 401`() {
        mockMvc.perform(multipart("/api/v1/import-csv-temp/empresas").file(archivo))
            .andExpect(status().isUnauthorized)
    }

    @Test
    fun `importar con token valido devuelve el resultado de la importacion`() {
        val token = jwtService.generateAccessToken(empleadoId = 7, rol = "vendedor")
        every { importCsvTempService.importarEmpresas(any(), UsuarioActual(id = 7, rol = "vendedor")) } returns
            ImportEmpresasResultDto(
                totalFilas = 1,
                creadas = 1,
                conError = 0,
                detalle =
                    listOf(
                        ImportEmpresaFilaResultado(
                            fila = 2,
                            ruc = "20999999999",
                            razonSocial = "Beta SRL",
                            estado = "creada",
                            motivo = null,
                        ),
                    ),
            )

        mockMvc.perform(
            multipart("/api/v1/import-csv-temp/empresas")
                .file(archivo)
                .header(HttpHeaders.AUTHORIZATION, "Bearer $token"),
        ).andExpect(status().isOk)
            .andExpect(jsonPath("$.data.creadas").value(1))
            .andExpect(jsonPath("$.data.detalle[0].estado").value("creada"))
    }

    @Test
    fun `archivo invalido responde 400 VALIDACION`() {
        val token = jwtService.generateAccessToken(empleadoId = 7, rol = "vendedor")
        every { importCsvTempService.importarEmpresas(any(), any()) } throws
            ValidacionException("El archivo CSV está vacío")

        mockMvc.perform(
            multipart("/api/v1/import-csv-temp/empresas")
                .file(archivo)
                .header(HttpHeaders.AUTHORIZATION, "Bearer $token"),
        ).andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.error.code").value("VALIDACION"))
    }
}
```

- [ ] **Step 2: Ejecutar y verificar que falla (no compila: `ImportCsvTempController` no existe)**

Run: `./gradlew test --tests "pe.quantum.crm.importcsvtemp.ImportCsvTempControllerTest"`
Expected: FAIL — error de compilación / contexto de Spring no encuentra un bean que exponga la ruta (el controller no existe todavía).

- [ ] **Step 3: Crear el controller**

Crear `src/main/kotlin/pe/quantum/crm/importcsvtemp/ImportCsvTempController.kt`:

```kotlin
package pe.quantum.crm.importcsvtemp

import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.multipart.MultipartFile
import pe.quantum.crm.importcsvtemp.dto.ImportEmpresasResultDto
import pe.quantum.crm.shared.ApiResponse
import pe.quantum.crm.shared.security.UsuarioActualProvider

/**
 * Endpoint temporal de importación masiva de empresas por CSV (módulo desechable,
 * ver docs/superpowers/specs/2026-07-09-import-csv-temp-empresas-design.md).
 * No forma parte de contrato_api.md.
 */
@RestController
@RequestMapping("/api/v1/import-csv-temp/empresas")
class ImportCsvTempController(
    private val importCsvTempService: ImportCsvTempService,
    private val usuarioProvider: UsuarioActualProvider,
) {
    @PostMapping
    fun importarEmpresas(
        @RequestParam("file") file: MultipartFile,
    ): ApiResponse<ImportEmpresasResultDto> {
        val usuario = usuarioProvider.actual()
        return ApiResponse.ok(importCsvTempService.importarEmpresas(file, usuario))
    }
}
```

- [ ] **Step 4: Ejecutar y verificar que pasa**

Run: `./gradlew test --tests "pe.quantum.crm.importcsvtemp.ImportCsvTempControllerTest"`
Expected: PASS (3 tests verdes).

- [ ] **Step 5: Correr toda la suite del módulo y formatear**

Run: `./gradlew test --tests "pe.quantum.crm.importcsvtemp.*"`
Expected: PASS (14 tests verdes: 11 de `ImportCsvTempServiceImplTest` + 3 de `ImportCsvTempControllerTest`).

Run: `./gradlew ktlintFormat`

- [ ] **Step 6: Commitear**

```bash
git add src/main/kotlin/pe/quantum/crm/importcsvtemp/ImportCsvTempController.kt src/test/kotlin/pe/quantum/crm/importcsvtemp/ImportCsvTempControllerTest.kt
git commit -m "feat(import-csv-temp): exponer POST /import-csv-temp/empresas"
```

---

### Task 4: Suite completa y verificación final

**Files:** ninguno nuevo — solo verificación.

- [ ] **Step 1: Correr toda la suite unitaria del proyecto**

Run: `./gradlew test`
Expected: PASS — ningún test existente se rompió (el módulo nuevo no toca código de `domain/empresas` ni de ningún otro módulo, solo lo consume vía `EmpresaService`).

- [ ] **Step 2: ktlint y detekt**

Run: `./gradlew ktlintCheck detekt`
Expected: PASS, sin warnings nuevos en `pe.quantum.crm.importcsvtemp`.

- [ ] **Step 3: Commit final si `ktlintCheck`/`detekt` requirieron ajustes manuales**

Si el Step 2 falló y se corrigió algo a mano:

```bash
git add -u
git commit -m "chore(import-csv-temp): ajustes de lint"
```

Si Step 2 pasó limpio en el primer intento, no hay nada que commitear en esta tarea.

---

## Fuera de alcance (recordatorio, ver spec)

- No se documenta en `contrato_api.md` ni `matriz_permisos.md`.
- No soporta Excel, ni más de un segmento por fila, ni otras entidades además de empresas.
- No hay endpoint de plantilla ni preview — sube y procesa en una sola llamada.
- Este módulo se borra por completo cuando llegue el módulo de importación definitivo.
