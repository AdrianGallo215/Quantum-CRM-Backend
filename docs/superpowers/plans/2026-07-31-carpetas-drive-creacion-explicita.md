# Creación explícita y backfill de carpetas de Drive — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Permitir crear la carpeta de Drive de una empresa u oportunidad a propósito (sin subir un archivo), y rellenar de una vez las carpetas faltantes de todos los registros anteriores a la integración.

**Architecture:** No hay lógica nueva de creación de carpetas: todo delega en el `asegurarCarpetaDrive` ya existente y probado. Se añaden dos endpoints `POST .../carpeta-drive` que lo exponen como acción independiente, y un módulo coordinador `mantenimiento` (fuera de `domain/`, siguiendo el precedente de `importcsvtemp`) que itera los registros sin carpeta llamando a los servicios de dominio **a través del proxy de Spring**, para que cada registro se persista en su propia transacción.

**Tech Stack:** Kotlin 1.9 · Spring Boot 3.2 · Spring Data JPA · JUnit 5 · MockK (springmockk) · AssertJ · ktlint · detekt.

## Global Constraints

- **TDD obligatorio.** El test que falla se escribe ANTES del código. Ninguna tarea termina sin tests pasando (CLAUDE.md regla 1).
- **Inyección por constructor** (`private val`), nunca `@Autowired` en campos (regla 8).
- **`@Transactional(readOnly = true)` en lecturas**, `@Transactional` en escrituras (regla 10).
- **Un módulo nunca accede a tablas ni entidades de otro módulo.** Solo vía su interfaz de servicio pública (regla 12).
- **IDOR: recurso ajeno → 404, no 403** (regla 14).
- **JSON en `snake_case`** (configurado globalmente en `application.properties`).
- Envelope de respuesta: `ApiResponse.ok(data)` produce `{ "data": ..., "meta": null, "error": null }`.
- Antes de cada commit: `./gradlew test` debe pasar. **Excepción conocida:** los 6 tests de `ImportCsvTempServiceImplTest` fallan por WIP preexistente ajeno a este trabajo (el delimitador CSV se cambió de `,` a `;` sin actualizar los tests). Se considera verde si el total de fallos sigue siendo exactamente esos 6.
- **No modificar** `EmpresaServiceImpl.crear()` ni `OportunidadServiceImpl.crear()`. No hay migración de base de datos en este plan.
- Comentarios y mensajes en español sin tildes en código Kotlin (el codebase existente usa `creacion`, `transaccion`, etc.).

---

## File Structure

**Crear:**
- `src/main/kotlin/pe/quantum/crm/mantenimiento/CarpetasDriveBackfillService.kt` — bucle, aislamiento de errores, conteos
- `src/main/kotlin/pe/quantum/crm/mantenimiento/CarpetasDriveBackfillController.kt` — `POST /api/v1/mantenimiento/carpetas-drive`, admin-only
- `src/main/kotlin/pe/quantum/crm/mantenimiento/dto/BackfillCarpetasDtos.kt` — DTOs de respuesta
- `src/test/kotlin/pe/quantum/crm/mantenimiento/CarpetasDriveBackfillServiceTest.kt`
- `src/test/kotlin/pe/quantum/crm/mantenimiento/CarpetasDriveBackfillControllerTest.kt`

**Modificar:**
- `EmpresaRepository.kt` — `findIdsSinCarpetaDrive()`
- `OportunidadRepository.kt` — `findIdsSinCarpetaDrive()`
- `EmpresaService.kt` / `EmpresaServiceImpl.kt` — `idsSinCarpetaDrive()`
- `OportunidadService.kt` / `OportunidadServiceImpl.kt` — `idsSinCarpetaDrive()` + sobrecarga `asegurarCarpetaDrive(id)`
- `EmpresaArchivoController.kt` → renombrar a `EmpresaDriveController.kt`, remapear rutas, añadir `POST /carpeta-drive`
- `OportunidadArchivoController.kt` → renombrar a `OportunidadDriveController.kt`, ídem
- Tests correspondientes de esos controllers (renombrar)
- `docs/contrato_api.md`, `docs/matriz_permisos.md`

---

### Task 1: Endpoint `POST /empresas/:id/carpeta-drive`

Remapea `EmpresaArchivoController` para poder colgar una ruta hermana de `/archivos`, lo renombra a `EmpresaDriveController` y añade el endpoint de creación explícita. Las URLs de `/archivos` no cambian.

**Files:**
- Modify → rename: `src/main/kotlin/pe/quantum/crm/domain/empresas/EmpresaArchivoController.kt` → `EmpresaDriveController.kt`
- Modify → rename: `src/test/kotlin/pe/quantum/crm/domain/empresas/EmpresaArchivoControllerTest.kt` → `EmpresaDriveControllerTest.kt`

**Interfaces:**
- Consumes: `EmpresaService.asegurarCarpetaDrive(id: Long, usuario: UsuarioActual): String` (ya existe), `EmpresaService.archivosDrive(id: Long, usuario: UsuarioActual): List<DriveArchivoSubido>` (ya existe), `DriveMultipartUploader.subirPrimerArchivo(request: HttpServletRequest, carpeta: String): DriveArchivoSubido` (ya existe)
- Produces: `CarpetaDriveDto(driveFolderId: String)` en `pe.quantum.crm.integracion.drive` — usado también por Task 2

- [ ] **Step 1: Crear el DTO de respuesta**

Crear `src/main/kotlin/pe/quantum/crm/integracion/drive/CarpetaDriveDto.kt`:

```kotlin
package pe.quantum.crm.integracion.drive

/** Respuesta de los endpoints `POST .../carpeta-drive` (contrato_api.md §8, §10). */
data class CarpetaDriveDto(
    val driveFolderId: String,
)
```

Va en `integracion/drive` porque lo consumen los dos modulos de dominio y el de mantenimiento; ninguno depende del otro.

- [ ] **Step 2: Escribir el test que falla**

Renombrar el archivo de test a `EmpresaDriveControllerTest.kt`, renombrar la clase a `EmpresaDriveControllerTest`, cambiar la construccion del controller a `EmpresaDriveController(...)`, y añadir estos tres tests al final de la clase (antes del helper `archivo(...)`):

```kotlin
    @Test
    fun `POST carpeta-drive crea la carpeta y devuelve su id`() {
        every { empresaService.asegurarCarpetaDrive(10, any()) } returns "carpeta-nueva"

        mockMvc
            .perform(post("/api/v1/empresas/10/carpeta-drive"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.driveFolderId").value("carpeta-nueva"))
    }

    @Test
    fun `POST carpeta-drive es idempotente - si ya existe devuelve la misma`() {
        every { empresaService.asegurarCarpetaDrive(10, any()) } returns "ya-existe"

        mockMvc
            .perform(post("/api/v1/empresas/10/carpeta-drive"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.driveFolderId").value("ya-existe"))
    }

    @Test
    fun `POST carpeta-drive en una empresa ajena responde 404`() {
        every { empresaService.asegurarCarpetaDrive(10, any()) } throws NoEncontradoException("La empresa no existe")

        mockMvc
            .perform(post("/api/v1/empresas/10/carpeta-drive"))
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.error.code").value("NO_ENCONTRADO"))
    }
```

Nota: el `jsonPath` usa `driveFolderId` (camelCase) y no `drive_folder_id` porque el MockMvc standalone no hereda la estrategia snake_case global — en produccion sale como `drive_folder_id`. Este mismo detalle ya se documento en la sesion anterior.

Ademas, en los tests existentes de esta clase, las rutas siguen siendo exactamente `/api/v1/empresas/10/archivos`: no se tocan.

- [ ] **Step 3: Ejecutar el test y verificar que falla**

Run: `./gradlew test --tests "pe.quantum.crm.domain.empresas.EmpresaDriveControllerTest" --console=plain`
Expected: FAIL — error de compilacion "Unresolved reference: EmpresaDriveController".

- [ ] **Step 4: Implementar el controller**

Renombrar `EmpresaArchivoController.kt` a `EmpresaDriveController.kt` y reemplazar su contenido:

```kotlin
package pe.quantum.crm.domain.empresas

import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import pe.quantum.crm.integracion.drive.CarpetaDriveDto
import pe.quantum.crm.integracion.drive.DriveArchivoSubido
import pe.quantum.crm.integracion.drive.DriveMultipartUploader
import pe.quantum.crm.shared.ApiResponse
import pe.quantum.crm.shared.security.UsuarioActualProvider

/**
 * Carpeta y documentos de una empresa en Google Drive (contrato_api.md §8).
 *
 * La subida NUNCA materializa el archivo en el servidor: ver
 * `DriveMultipartUploader` y `DriveUploadMultipartResolver`, sin el cual Tomcat
 * volcaria el archivo a un temporal en disco antes de llegar aqui.
 */
@RestController
@RequestMapping("/api/v1/empresas/{id}")
class EmpresaDriveController(
    private val empresaService: EmpresaService,
    private val driveMultipartUploader: DriveMultipartUploader,
    private val usuarioProvider: UsuarioActualProvider,
) {
    @GetMapping("/archivos")
    fun listar(
        @PathVariable id: Long,
    ): ApiResponse<List<DriveArchivoSubido>> = ApiResponse.ok(empresaService.archivosDrive(id, usuarioProvider.actual()))

    @PostMapping("/archivos")
    @ResponseStatus(HttpStatus.CREATED)
    fun subir(
        @PathVariable id: Long,
        request: HttpServletRequest,
    ): ApiResponse<DriveArchivoSubido> {
        // Visibilidad y carpeta ANTES de leer un solo byte: una empresa ajena
        // responde 404 sin haber transferido nada (IDOR, SECURITY §3.2).
        val carpeta = empresaService.asegurarCarpetaDrive(id, usuarioProvider.actual())
        return ApiResponse.ok(driveMultipartUploader.subirPrimerArchivo(request, carpeta))
    }

    /** Idempotente: si la empresa ya tiene carpeta, la devuelve sin tocar Drive. */
    @PostMapping("/carpeta-drive")
    fun crearCarpeta(
        @PathVariable id: Long,
    ): ApiResponse<CarpetaDriveDto> =
        ApiResponse.ok(CarpetaDriveDto(empresaService.asegurarCarpetaDrive(id, usuarioProvider.actual())))
}
```

- [ ] **Step 5: Ejecutar los tests y verificar que pasan**

Run: `./gradlew test --tests "pe.quantum.crm.domain.empresas.EmpresaDriveControllerTest" --console=plain`
Expected: PASS (8 tests: 5 preexistentes de archivos + 3 nuevos).

- [ ] **Step 6: Verificar que la ruta de subida en streaming sigue reconocida**

`DriveUploadMultipartResolver` matchea por regex `^/api/v1/empresas/\d+/archivos/?$`. El remapeo de clase→metodo NO cambia la URL final, asi que la regex sigue valida. Confirmarlo ejecutando:

Run: `./gradlew test --tests "pe.quantum.crm.integracion.drive.*" --console=plain`
Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add src/main/kotlin/pe/quantum/crm/integracion/drive/CarpetaDriveDto.kt \
        src/main/kotlin/pe/quantum/crm/domain/empresas/EmpresaDriveController.kt \
        src/test/kotlin/pe/quantum/crm/domain/empresas/EmpresaDriveControllerTest.kt
git rm --cached src/main/kotlin/pe/quantum/crm/domain/empresas/EmpresaArchivoController.kt 2>/dev/null || true
git commit -m "feat(drive): endpoint POST /empresas/:id/carpeta-drive

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

**IMPORTANTE:** la rama tiene WIP preexistente sin commitear ajeno a este trabajo. Añadir SOLO los archivos listados, nunca `git add -A` ni `git add .`.

---

### Task 2: Endpoint `POST /oportunidades/:id/carpeta-drive`

Mismo patron que Task 1, sobre el controller de oportunidades.

**Files:**
- Modify → rename: `src/main/kotlin/pe/quantum/crm/domain/oportunidades/OportunidadArchivoController.kt` → `OportunidadDriveController.kt`
- Modify → rename: `src/test/kotlin/pe/quantum/crm/domain/oportunidades/OportunidadArchivoControllerTest.kt` → `OportunidadDriveControllerTest.kt`

**Interfaces:**
- Consumes: `CarpetaDriveDto(driveFolderId: String)` de Task 1; `OportunidadService.asegurarCarpetaDrive(id: Long, usuario: UsuarioActual): String` (ya existe); `OportunidadService.archivosDrive(id: Long, usuario: UsuarioActual): List<DriveArchivoSubido>` (ya existe)
- Produces: nada nuevo

- [ ] **Step 1: Escribir el test que falla**

Renombrar el test a `OportunidadDriveControllerTest.kt`, renombrar la clase, cambiar la construccion a `OportunidadDriveController(...)`, y añadir al final de la clase (antes del helper `archivo(...)`):

```kotlin
    @Test
    fun `POST carpeta-drive crea la carpeta y devuelve su id`() {
        every { oportunidadService.asegurarCarpetaDrive(100, any()) } returns "carpeta-nueva"

        mockMvc
            .perform(post("/api/v1/oportunidades/100/carpeta-drive"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.driveFolderId").value("carpeta-nueva"))
    }

    @Test
    fun `POST carpeta-drive es idempotente - si ya existe devuelve la misma`() {
        every { oportunidadService.asegurarCarpetaDrive(100, any()) } returns "ya-existe"

        mockMvc
            .perform(post("/api/v1/oportunidades/100/carpeta-drive"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.driveFolderId").value("ya-existe"))
    }

    @Test
    fun `POST carpeta-drive en una oportunidad ajena responde 404`() {
        every { oportunidadService.asegurarCarpetaDrive(100, any()) } throws NoEncontradoException("La oportunidad no existe")

        mockMvc
            .perform(post("/api/v1/oportunidades/100/carpeta-drive"))
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.error.code").value("NO_ENCONTRADO"))
    }
```

- [ ] **Step 2: Ejecutar el test y verificar que falla**

Run: `./gradlew test --tests "pe.quantum.crm.domain.oportunidades.OportunidadDriveControllerTest" --console=plain`
Expected: FAIL — "Unresolved reference: OportunidadDriveController".

- [ ] **Step 3: Implementar el controller**

Renombrar `OportunidadArchivoController.kt` a `OportunidadDriveController.kt` y reemplazar su contenido:

```kotlin
package pe.quantum.crm.domain.oportunidades

import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import pe.quantum.crm.integracion.drive.CarpetaDriveDto
import pe.quantum.crm.integracion.drive.DriveArchivoSubido
import pe.quantum.crm.integracion.drive.DriveMultipartUploader
import pe.quantum.crm.shared.ApiResponse
import pe.quantum.crm.shared.security.UsuarioActualProvider

/**
 * Carpeta y documentos de una oportunidad en Google Drive (contrato_api.md §10).
 *
 * La subida NUNCA materializa el archivo en el servidor: ver
 * `DriveMultipartUploader` y `DriveUploadMultipartResolver`, sin el cual Tomcat
 * volcaria el archivo a un temporal en disco antes de llegar aqui.
 */
@RestController
@RequestMapping("/api/v1/oportunidades/{id}")
class OportunidadDriveController(
    private val oportunidadService: OportunidadService,
    private val driveMultipartUploader: DriveMultipartUploader,
    private val usuarioProvider: UsuarioActualProvider,
) {
    @GetMapping("/archivos")
    fun listar(
        @PathVariable id: Long,
    ): ApiResponse<List<DriveArchivoSubido>> = ApiResponse.ok(oportunidadService.archivosDrive(id, usuarioProvider.actual()))

    @PostMapping("/archivos")
    @ResponseStatus(HttpStatus.CREATED)
    fun subir(
        @PathVariable id: Long,
        request: HttpServletRequest,
    ): ApiResponse<DriveArchivoSubido> {
        // Visibilidad y carpeta ANTES de leer un solo byte: una oportunidad ajena
        // responde 404 sin haber transferido nada (IDOR, SECURITY §3.2).
        val carpeta = oportunidadService.asegurarCarpetaDrive(id, usuarioProvider.actual())
        return ApiResponse.ok(driveMultipartUploader.subirPrimerArchivo(request, carpeta))
    }

    /** Idempotente: si la oportunidad ya tiene carpeta, la devuelve sin tocar Drive. */
    @PostMapping("/carpeta-drive")
    fun crearCarpeta(
        @PathVariable id: Long,
    ): ApiResponse<CarpetaDriveDto> =
        ApiResponse.ok(CarpetaDriveDto(oportunidadService.asegurarCarpetaDrive(id, usuarioProvider.actual())))
}
```

- [ ] **Step 4: Ejecutar los tests y verificar que pasan**

Run: `./gradlew test --tests "pe.quantum.crm.domain.oportunidades.OportunidadDriveControllerTest" --console=plain`
Expected: PASS (8 tests).

- [ ] **Step 5: Verificar que no se rompio ninguna ruta existente**

Run: `./gradlew test --console=plain`
Expected: `260 tests completed, 6 failed` — exactamente los 6 de `ImportCsvTempServiceImplTest`. Si falla algo mas, detenerse y arreglarlo antes de commitear.

- [ ] **Step 6: Commit**

```bash
git add src/main/kotlin/pe/quantum/crm/domain/oportunidades/OportunidadDriveController.kt \
        src/test/kotlin/pe/quantum/crm/domain/oportunidades/OportunidadDriveControllerTest.kt
git rm --cached src/main/kotlin/pe/quantum/crm/domain/oportunidades/OportunidadArchivoController.kt 2>/dev/null || true
git commit -m "feat(drive): endpoint POST /oportunidades/:id/carpeta-drive

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

### Task 3: Consultar los registros sin carpeta

Añade a ambos modulos de dominio la capacidad de reportar que ids les falta carpeta. El coordinador del backfill (Task 5) los consume sin tocar repositorios ajenos (regla 12).

**Files:**
- Modify: `src/main/kotlin/pe/quantum/crm/domain/empresas/EmpresaRepository.kt`
- Modify: `src/main/kotlin/pe/quantum/crm/domain/empresas/EmpresaService.kt`
- Modify: `src/main/kotlin/pe/quantum/crm/domain/empresas/EmpresaServiceImpl.kt`
- Modify: `src/main/kotlin/pe/quantum/crm/domain/oportunidades/OportunidadRepository.kt`
- Modify: `src/main/kotlin/pe/quantum/crm/domain/oportunidades/OportunidadService.kt`
- Modify: `src/main/kotlin/pe/quantum/crm/domain/oportunidades/OportunidadServiceImpl.kt`
- Test: `src/test/kotlin/pe/quantum/crm/domain/empresas/EmpresaServiceImplTest.kt`
- Test: `src/test/kotlin/pe/quantum/crm/domain/oportunidades/OportunidadServiceImplTest.kt`

**Interfaces:**
- Consumes: nada de tareas anteriores
- Produces: `EmpresaService.idsSinCarpetaDrive(): List<Long>` y `OportunidadService.idsSinCarpetaDrive(): List<Long>` — ambos consumidos por Task 5

- [ ] **Step 1: Escribir los tests que fallan**

En `EmpresaServiceImplTest.kt`, añadir al final de la clase:

```kotlin
    @Test
    fun `idsSinCarpetaDrive delega en el repositorio y devuelve los ids pendientes`() {
        every { empresaRepository.findIdsSinCarpetaDrive() } returns listOf(3L, 7L)

        assertThat(service.idsSinCarpetaDrive()).containsExactly(3L, 7L)
    }
```

En `OportunidadServiceImplTest.kt`, añadir al final de la clase:

```kotlin
    @Test
    fun `idsSinCarpetaDrive delega en el repositorio y devuelve los ids pendientes`() {
        every { oportunidadRepository.findIdsSinCarpetaDrive() } returns listOf(11L, 12L)

        assertThat(service.idsSinCarpetaDrive()).containsExactly(11L, 12L)
    }
```

- [ ] **Step 2: Ejecutar los tests y verificar que fallan**

Run: `./gradlew test --tests "*EmpresaServiceImplTest" --tests "*OportunidadServiceImplTest" --console=plain`
Expected: FAIL — "Unresolved reference: findIdsSinCarpetaDrive".

- [ ] **Step 3: Añadir las consultas a los repositorios**

En `EmpresaRepository.kt`, añadir el import `org.springframework.data.jpa.repository.Query` y dentro de la interfaz `EmpresaRepository`:

```kotlin
    /** Ids de empresas sin carpeta de Drive (backfill, ver modulo `mantenimiento`). */
    @Query("select e.id from Empresa e where e.driveFolderId is null order by e.id")
    fun findIdsSinCarpetaDrive(): List<Long>
```

En `OportunidadRepository.kt`, añadir el import `org.springframework.data.jpa.repository.Query` y dentro de la interfaz `OportunidadRepository`:

```kotlin
    /** Ids de oportunidades sin carpeta de Drive (backfill, ver modulo `mantenimiento`). */
    @Query("select o.id from Oportunidad o where o.driveFolderId is null order by o.id")
    fun findIdsSinCarpetaDrive(): List<Long>
```

Se usa `@Query` con proyeccion de id en vez de `findByDriveFolderIdIsNull()` para no cargar entidades completas en memoria.

- [ ] **Step 4: Añadir el metodo a las interfaces de servicio**

En `EmpresaService.kt`, dentro de la interfaz, junto a los otros metodos de Drive:

```kotlin
    /**
     * Ids de empresas sin carpeta de Drive. Sin chequeo de visibilidad: lo consume
     * el backfill administrativo, que corre sobre todo el sistema.
     */
    fun idsSinCarpetaDrive(): List<Long>
```

En `OportunidadService.kt`, dentro de la interfaz:

```kotlin
    /**
     * Ids de oportunidades sin carpeta de Drive. Sin chequeo de visibilidad: lo
     * consume el backfill administrativo, que corre sobre todo el sistema.
     */
    fun idsSinCarpetaDrive(): List<Long>
```

- [ ] **Step 5: Implementar en los servicios**

En `EmpresaServiceImpl.kt`, junto a `archivosDrive`:

```kotlin
    @Transactional(readOnly = true)
    override fun idsSinCarpetaDrive(): List<Long> = empresaRepository.findIdsSinCarpetaDrive()
```

En `OportunidadServiceImpl.kt`, junto a `archivosDrive`:

```kotlin
    @Transactional(readOnly = true)
    override fun idsSinCarpetaDrive(): List<Long> = oportunidadRepository.findIdsSinCarpetaDrive()
```

- [ ] **Step 6: Ejecutar los tests y verificar que pasan**

Run: `./gradlew test --tests "*EmpresaServiceImplTest" --tests "*OportunidadServiceImplTest" --console=plain`
Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add src/main/kotlin/pe/quantum/crm/domain/empresas/EmpresaRepository.kt \
        src/main/kotlin/pe/quantum/crm/domain/empresas/EmpresaService.kt \
        src/main/kotlin/pe/quantum/crm/domain/empresas/EmpresaServiceImpl.kt \
        src/main/kotlin/pe/quantum/crm/domain/oportunidades/OportunidadRepository.kt \
        src/main/kotlin/pe/quantum/crm/domain/oportunidades/OportunidadService.kt \
        src/main/kotlin/pe/quantum/crm/domain/oportunidades/OportunidadServiceImpl.kt \
        src/test/kotlin/pe/quantum/crm/domain/empresas/EmpresaServiceImplTest.kt \
        src/test/kotlin/pe/quantum/crm/domain/oportunidades/OportunidadServiceImplTest.kt
git commit -m "feat(drive): consultar registros sin carpeta de Drive

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

### Task 4: Sobrecarga de sistema `OportunidadService.asegurarCarpetaDrive(id)`

`EmpresaService` ya tiene una version sin `usuario` para uso interno entre modulos. `OportunidadService` no. El backfill la necesita porque corre como job de sistema, sin un usuario cuyo filtro de visibilidad aplicar.

**Files:**
- Modify: `src/main/kotlin/pe/quantum/crm/domain/oportunidades/OportunidadService.kt`
- Modify: `src/main/kotlin/pe/quantum/crm/domain/oportunidades/OportunidadServiceImpl.kt`
- Test: `src/test/kotlin/pe/quantum/crm/domain/oportunidades/OportunidadServiceImplTest.kt`

**Interfaces:**
- Consumes: nada de tareas anteriores
- Produces: `OportunidadService.asegurarCarpetaDrive(id: Long): String` — consumido por Task 5

- [ ] **Step 1: Escribir los tests que fallan**

En `OportunidadServiceImplTest.kt`, añadir al final de la clase:

```kotlin
    @Test
    fun `asegurarCarpetaDrive de sistema crea la carpeta sin exigir visibilidad`() {
        val entidad = oportunidadConVendedor(3)
        every { oportunidadRepository.findById(100) } returns Optional.of(entidad)
        every { empresaService.asegurarCarpetaDrive(10) } returns "carpeta-empresa"
        every { modeloService.resumen(1) } returns busX()
        every { driveStorageService.crearCarpeta("OP-100 - BUS-X", "carpeta-empresa") } returns "carpeta-op"
        every { oportunidadRepository.save(entidad) } returns entidad

        assertThat(service.asegurarCarpetaDrive(100)).isEqualTo("carpeta-op")
        assertThat(entidad.driveFolderId).isEqualTo("carpeta-op")
    }

    @Test
    fun `asegurarCarpetaDrive de sistema es idempotente`() {
        val entidad = oportunidadConVendedor(3).apply { driveFolderId = "ya-existe" }
        every { oportunidadRepository.findById(100) } returns Optional.of(entidad)

        assertThat(service.asegurarCarpetaDrive(100)).isEqualTo("ya-existe")

        verify(exactly = 0) { driveStorageService.crearCarpeta(any(), any()) }
    }
```

- [ ] **Step 2: Ejecutar los tests y verificar que fallan**

Run: `./gradlew test --tests "*OportunidadServiceImplTest" --console=plain`
Expected: FAIL — no existe la sobrecarga de un solo argumento.

- [ ] **Step 3: Añadir el metodo a la interfaz**

En `OportunidadService.kt`, junto a la sobrecarga existente:

```kotlin
    /**
     * Igual que la sobrecarga con usuario, pero sin chequeo de visibilidad: uso
     * interno de jobs de sistema (backfill administrativo). Crea antes la carpeta
     * de la empresa si le falta.
     */
    fun asegurarCarpetaDrive(id: Long): String
```

- [ ] **Step 4: Refactorizar la implementacion para compartir el cuerpo**

En `OportunidadServiceImpl.kt`, reemplazar el `asegurarCarpetaDrive(id, usuario)` existente por estas tres funciones:

```kotlin
    @Transactional
    override fun asegurarCarpetaDrive(
        id: Long,
        usuario: UsuarioActual,
    ): String = asegurarCarpetaDriveDe(visible(id, usuario))

    @Transactional
    override fun asegurarCarpetaDrive(id: Long): String = asegurarCarpetaDriveDe(entidad(id))

    private fun asegurarCarpetaDriveDe(oportunidad: Oportunidad): String {
        oportunidad.driveFolderId?.let { return it }
        val carpetaEmpresa = empresaService.asegurarCarpetaDrive(oportunidad.idEmpresa)
        val codigoModelo = oportunidad.idModelo?.let { modeloService.resumen(it).codigo }
        val carpeta =
            driveStorageService.crearCarpeta(
                nombre = nombreCarpetaDrive(requireNotNull(oportunidad.id), codigoModelo),
                parentFolderId = carpetaEmpresa,
            )
        oportunidad.driveFolderId = carpeta
        oportunidadRepository.save(oportunidad)
        return carpeta
    }
```

Es el mismo patron que ya usa `EmpresaServiceImpl` con su `asegurarCarpetaDriveDe(empresa)`.

- [ ] **Step 5: Ejecutar los tests y verificar que pasan**

Run: `./gradlew test --tests "*OportunidadServiceImplTest" --console=plain`
Expected: PASS — incluidos los tests preexistentes de `asegurarCarpetaDrive(id, usuario)`, que deben seguir verdes tras el refactor.

- [ ] **Step 6: Commit**

```bash
git add src/main/kotlin/pe/quantum/crm/domain/oportunidades/OportunidadService.kt \
        src/main/kotlin/pe/quantum/crm/domain/oportunidades/OportunidadServiceImpl.kt \
        src/test/kotlin/pe/quantum/crm/domain/oportunidades/OportunidadServiceImplTest.kt
git commit -m "feat(drive): sobrecarga de sistema de asegurarCarpetaDrive en oportunidades

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

### Task 5: Servicio de backfill

El corazon del plan. Itera los registros pendientes llamando a los servicios de dominio **desde fuera**, para que cada llamada pase por el proxy de Spring y abra su propia transaccion.

**Files:**
- Create: `src/main/kotlin/pe/quantum/crm/mantenimiento/dto/BackfillCarpetasDtos.kt`
- Create: `src/main/kotlin/pe/quantum/crm/mantenimiento/CarpetasDriveBackfillService.kt`
- Test: `src/test/kotlin/pe/quantum/crm/mantenimiento/CarpetasDriveBackfillServiceTest.kt`

**Interfaces:**
- Consumes: `EmpresaService.idsSinCarpetaDrive()`, `EmpresaService.asegurarCarpetaDrive(id: Long)`, `OportunidadService.idsSinCarpetaDrive()`, `OportunidadService.asegurarCarpetaDrive(id: Long)` (Tasks 3 y 4)
- Produces: `CarpetasDriveBackfillService.ejecutar(tamanoLote: Int?): BackfillCarpetasDto` — consumido por Task 6

- [ ] **Step 1: Crear los DTOs**

Crear `src/main/kotlin/pe/quantum/crm/mantenimiento/dto/BackfillCarpetasDtos.kt`:

```kotlin
package pe.quantum.crm.mantenimiento.dto

/** Resultado del backfill de carpetas de Drive (contrato_api.md §22). */
data class BackfillCarpetasDto(
    val empresasProcesadas: Int,
    val oportunidadesProcesadas: Int,
    val errores: List<ErrorBackfillDto>,
    val pendientesRestantes: Int,
)

/** Un registro que no pudo procesarse; el resto del lote continuo igualmente. */
data class ErrorBackfillDto(
    val entidad: String,
    val id: Long,
    val motivo: String,
)
```

- [ ] **Step 2: Escribir los tests que fallan**

Crear `src/test/kotlin/pe/quantum/crm/mantenimiento/CarpetasDriveBackfillServiceTest.kt`:

```kotlin
package pe.quantum.crm.mantenimiento

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.mockk.verifyOrder
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import pe.quantum.crm.domain.empresas.EmpresaService
import pe.quantum.crm.domain.oportunidades.OportunidadService
import pe.quantum.crm.integracion.drive.DriveException

class CarpetasDriveBackfillServiceTest {
    private val empresaService = mockk<EmpresaService>()
    private val oportunidadService = mockk<OportunidadService>()
    private val service = CarpetasDriveBackfillService(empresaService, oportunidadService)

    @Test
    fun `crea las carpetas de todas las empresas y oportunidades pendientes`() {
        every { empresaService.idsSinCarpetaDrive() } returns listOf(1L, 2L)
        every { oportunidadService.idsSinCarpetaDrive() } returns listOf(10L)
        every { empresaService.asegurarCarpetaDrive(any<Long>()) } returns "carpeta"
        every { oportunidadService.asegurarCarpetaDrive(any<Long>()) } returns "carpeta"

        val resultado = service.ejecutar(tamanoLote = null)

        assertThat(resultado.empresasProcesadas).isEqualTo(2)
        assertThat(resultado.oportunidadesProcesadas).isEqualTo(1)
        assertThat(resultado.errores).isEmpty()
        assertThat(resultado.pendientesRestantes).isZero()
    }

    @Test
    fun `procesa las empresas antes que las oportunidades`() {
        every { empresaService.idsSinCarpetaDrive() } returns listOf(1L)
        every { oportunidadService.idsSinCarpetaDrive() } returns listOf(10L)
        every { empresaService.asegurarCarpetaDrive(1L) } returns "carpeta-empresa"
        every { oportunidadService.asegurarCarpetaDrive(10L) } returns "carpeta-op"

        service.ejecutar(tamanoLote = null)

        verifyOrder {
            empresaService.asegurarCarpetaDrive(1L)
            oportunidadService.asegurarCarpetaDrive(10L)
        }
    }

    @Test
    fun `un fallo en un registro no detiene el resto del lote`() {
        every { empresaService.idsSinCarpetaDrive() } returns listOf(1L, 2L, 3L)
        every { oportunidadService.idsSinCarpetaDrive() } returns emptyList()
        every { empresaService.asegurarCarpetaDrive(1L) } returns "carpeta-1"
        every { empresaService.asegurarCarpetaDrive(2L) } throws DriveException("Drive caido")
        every { empresaService.asegurarCarpetaDrive(3L) } returns "carpeta-3"

        val resultado = service.ejecutar(tamanoLote = null)

        // La 3 se proceso pese al fallo de la 2.
        verify { empresaService.asegurarCarpetaDrive(3L) }
        assertThat(resultado.empresasProcesadas).isEqualTo(2)
        assertThat(resultado.errores).hasSize(1)
        assertThat(resultado.errores[0].entidad).isEqualTo("empresa")
        assertThat(resultado.errores[0].id).isEqualTo(2L)
        assertThat(resultado.errores[0].motivo).contains("Drive caido")
        // La que fallo sigue pendiente.
        assertThat(resultado.pendientesRestantes).isEqualTo(1)
    }

    @Test
    fun `tamano_lote limita el total procesado y reporta los pendientes`() {
        every { empresaService.idsSinCarpetaDrive() } returns listOf(1L, 2L, 3L)
        every { oportunidadService.idsSinCarpetaDrive() } returns listOf(10L, 11L)
        every { empresaService.asegurarCarpetaDrive(any<Long>()) } returns "carpeta"

        val resultado = service.ejecutar(tamanoLote = 2)

        assertThat(resultado.empresasProcesadas).isEqualTo(2)
        assertThat(resultado.oportunidadesProcesadas).isZero()
        // Quedan 1 empresa + 2 oportunidades.
        assertThat(resultado.pendientesRestantes).isEqualTo(3)
        verify(exactly = 0) { oportunidadService.asegurarCarpetaDrive(any<Long>()) }
    }

    @Test
    fun `sin pendientes no toca Drive y reporta cero`() {
        every { empresaService.idsSinCarpetaDrive() } returns emptyList()
        every { oportunidadService.idsSinCarpetaDrive() } returns emptyList()

        val resultado = service.ejecutar(tamanoLote = null)

        assertThat(resultado.empresasProcesadas).isZero()
        assertThat(resultado.oportunidadesProcesadas).isZero()
        assertThat(resultado.pendientesRestantes).isZero()
        verify(exactly = 0) { empresaService.asegurarCarpetaDrive(any<Long>()) }
        verify(exactly = 0) { oportunidadService.asegurarCarpetaDrive(any<Long>()) }
    }
}
```

- [ ] **Step 3: Ejecutar los tests y verificar que fallan**

Run: `./gradlew test --tests "pe.quantum.crm.mantenimiento.CarpetasDriveBackfillServiceTest" --console=plain`
Expected: FAIL — "Unresolved reference: CarpetasDriveBackfillService".

- [ ] **Step 4: Implementar el servicio**

Crear `src/main/kotlin/pe/quantum/crm/mantenimiento/CarpetasDriveBackfillService.kt`:

```kotlin
package pe.quantum.crm.mantenimiento

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import pe.quantum.crm.domain.empresas.EmpresaService
import pe.quantum.crm.domain.oportunidades.OportunidadService
import pe.quantum.crm.mantenimiento.dto.BackfillCarpetasDto
import pe.quantum.crm.mantenimiento.dto.ErrorBackfillDto

/**
 * Crea las carpetas de Drive que les faltan a empresas y oportunidades anteriores
 * a la integracion (ver docs/superpowers/specs/2026-07-31-carpetas-drive-creacion-explicita-design.md).
 *
 * NO lleva `@Transactional` a proposito. Cada `asegurarCarpetaDrive` se invoca
 * desde fuera del servicio de dominio, asi que pasa por el proxy de Spring y abre
 * SU PROPIA transaccion: lo ya procesado queda commiteado aunque la llamada se
 * corte a la mitad, y repetir el endpoint retoma donde quedo. Envolver todo el
 * bucle en una transaccion unica romperia justo esa garantia.
 */
@Service
class CarpetasDriveBackfillService(
    private val empresaService: EmpresaService,
    private val oportunidadService: OportunidadService,
) {
    private val log = LoggerFactory.getLogger(CarpetasDriveBackfillService::class.java)

    /**
     * @param tamanoLote tope de registros a procesar en esta llamada; `null` procesa
     *   todos los pendientes.
     */
    fun ejecutar(tamanoLote: Int?): BackfillCarpetasDto {
        val empresasPendientes = empresaService.idsSinCarpetaDrive()
        val oportunidadesPendientes = oportunidadService.idsSinCarpetaDrive()
        val errores = mutableListOf<ErrorBackfillDto>()

        // Empresas primero: la carpeta de una oportunidad cuelga de la de su
        // empresa, asi se evita trabajo redundante.
        var presupuesto = tamanoLote ?: (empresasPendientes.size + oportunidadesPendientes.size)
        val empresasTomadas = empresasPendientes.take(presupuesto)
        val empresasCreadas =
            empresasTomadas.count { id ->
                procesar("empresa", id, errores) { empresaService.asegurarCarpetaDrive(id) }
            }
        presupuesto -= empresasTomadas.size

        val oportunidadesTomadas = oportunidadesPendientes.take(maxOf(presupuesto, 0))
        val oportunidadesCreadas =
            oportunidadesTomadas.count { id ->
                procesar("oportunidad", id, errores) { oportunidadService.asegurarCarpetaDrive(id) }
            }

        val restantes =
            (empresasPendientes.size - empresasCreadas) + (oportunidadesPendientes.size - oportunidadesCreadas)
        log.info(
            "Backfill de carpetas de Drive: empresas={} oportunidades={} errores={} pendientes={}",
            empresasCreadas,
            oportunidadesCreadas,
            errores.size,
            restantes,
        )
        return BackfillCarpetasDto(
            empresasProcesadas = empresasCreadas,
            oportunidadesProcesadas = oportunidadesCreadas,
            errores = errores,
            pendientesRestantes = restantes,
        )
    }

    /**
     * Aisla el fallo de un registro: se anota y el bucle sigue con el siguiente.
     * Un RUC raro o una caida puntual de Drive no debe abortar todo el backfill.
     */
    @Suppress("TooGenericExceptionCaught") // El aislamiento por registro es justo el objetivo.
    private fun procesar(
        entidad: String,
        id: Long,
        errores: MutableList<ErrorBackfillDto>,
        accion: () -> String,
    ): Boolean =
        try {
            accion()
            true
        } catch (ex: RuntimeException) {
            log.warn("Backfill: no se pudo crear la carpeta de {} {}", entidad, id, ex)
            errores += ErrorBackfillDto(entidad = entidad, id = id, motivo = ex.message ?: ex.javaClass.simpleName)
            false
        }
}
```

- [ ] **Step 5: Ejecutar los tests y verificar que pasan**

Run: `./gradlew test --tests "pe.quantum.crm.mantenimiento.CarpetasDriveBackfillServiceTest" --console=plain`
Expected: PASS (5 tests).

- [ ] **Step 6: Commit**

```bash
git add src/main/kotlin/pe/quantum/crm/mantenimiento/ \
        src/test/kotlin/pe/quantum/crm/mantenimiento/
git commit -m "feat(drive): servicio de backfill de carpetas faltantes

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

### Task 6: Endpoint del backfill, admin-only

**Files:**
- Create: `src/main/kotlin/pe/quantum/crm/mantenimiento/CarpetasDriveBackfillController.kt`
- Test: `src/test/kotlin/pe/quantum/crm/mantenimiento/CarpetasDriveBackfillControllerTest.kt`

**Interfaces:**
- Consumes: `CarpetasDriveBackfillService.ejecutar(tamanoLote: Int?): BackfillCarpetasDto` (Task 5)
- Produces: `POST /api/v1/mantenimiento/carpetas-drive`

- [ ] **Step 1: Escribir el test que falla**

Crear `src/test/kotlin/pe/quantum/crm/mantenimiento/CarpetasDriveBackfillControllerTest.kt`:

```kotlin
package pe.quantum.crm.mantenimiento

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import pe.quantum.crm.mantenimiento.dto.BackfillCarpetasDto
import pe.quantum.crm.mantenimiento.dto.ErrorBackfillDto
import pe.quantum.crm.shared.GlobalExceptionHandler

/**
 * La restriccion a `admin` la aplica `@PreAuthorize`, que necesita el contexto de
 * Spring Security completo; aqui se prueba el enrutamiento y el envelope. La
 * verificacion del 403 va en el test de contexto (ver Step 5).
 */
class CarpetasDriveBackfillControllerTest {
    private val backfillService = mockk<CarpetasDriveBackfillService>()

    private val mockMvc =
        MockMvcBuilders
            .standaloneSetup(CarpetasDriveBackfillController(backfillService))
            .setControllerAdvice(GlobalExceptionHandler())
            .build()

    @Test
    fun `sin tamano_lote procesa todo y devuelve los conteos`() {
        every { backfillService.ejecutar(null) } returns
            BackfillCarpetasDto(
                empresasProcesadas = 12,
                oportunidadesProcesadas = 30,
                errores = emptyList(),
                pendientesRestantes = 0,
            )

        mockMvc
            .perform(post("/api/v1/mantenimiento/carpetas-drive"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.empresasProcesadas").value(12))
            .andExpect(jsonPath("$.data.oportunidadesProcesadas").value(30))
            .andExpect(jsonPath("$.data.pendientesRestantes").value(0))

        verify { backfillService.ejecutar(null) }
    }

    @Test
    fun `con tamano_lote lo propaga al servicio`() {
        every { backfillService.ejecutar(25) } returns
            BackfillCarpetasDto(
                empresasProcesadas = 25,
                oportunidadesProcesadas = 0,
                errores = emptyList(),
                pendientesRestantes = 17,
            )

        mockMvc
            .perform(post("/api/v1/mantenimiento/carpetas-drive").param("tamano_lote", "25"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.pendientesRestantes").value(17))

        verify { backfillService.ejecutar(25) }
    }

    @Test
    fun `expone los errores por registro sin fallar la respuesta`() {
        every { backfillService.ejecutar(null) } returns
            BackfillCarpetasDto(
                empresasProcesadas = 1,
                oportunidadesProcesadas = 0,
                errores = listOf(ErrorBackfillDto(entidad = "empresa", id = 7, motivo = "Drive caido")),
                pendientesRestantes = 1,
            )

        mockMvc
            .perform(post("/api/v1/mantenimiento/carpetas-drive"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.errores[0].entidad").value("empresa"))
            .andExpect(jsonPath("$.data.errores[0].id").value(7))
            .andExpect(jsonPath("$.data.errores[0].motivo").value("Drive caido"))
    }
}
```

- [ ] **Step 2: Ejecutar el test y verificar que falla**

Run: `./gradlew test --tests "pe.quantum.crm.mantenimiento.CarpetasDriveBackfillControllerTest" --console=plain`
Expected: FAIL — "Unresolved reference: CarpetasDriveBackfillController".

- [ ] **Step 3: Implementar el controller**

Crear `src/main/kotlin/pe/quantum/crm/mantenimiento/CarpetasDriveBackfillController.kt`:

```kotlin
package pe.quantum.crm.mantenimiento

import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import pe.quantum.crm.mantenimiento.dto.BackfillCarpetasDto
import pe.quantum.crm.shared.ApiResponse

/**
 * Operacion administrativa: crea las carpetas de Drive que faltan en registros
 * anteriores a la integracion (contrato_api.md §22).
 *
 * Idempotente y re-ejecutable: si no hay pendientes responde todo en cero sin
 * tocar Drive.
 */
@RestController
@RequestMapping("/api/v1/mantenimiento/carpetas-drive")
class CarpetasDriveBackfillController(
    private val backfillService: CarpetasDriveBackfillService,
) {
    @PostMapping
    @PreAuthorize("hasRole('admin')")
    fun crearCarpetasFaltantes(
        @RequestParam(name = "tamano_lote", required = false) tamanoLote: Int?,
    ): ApiResponse<BackfillCarpetasDto> = ApiResponse.ok(backfillService.ejecutar(tamanoLote))
}
```

- [ ] **Step 4: Ejecutar los tests y verificar que pasan**

Run: `./gradlew test --tests "pe.quantum.crm.mantenimiento.CarpetasDriveBackfillControllerTest" --console=plain`
Expected: PASS (3 tests).

- [ ] **Step 5: Añadir el test de 403 para rol no-admin**

`@PreAuthorize` NO se evalua en MockMvc standalone: hace falta el contexto real de Spring Security. Va en un archivo aparte para no mezclar los dos estilos de montaje.

Crear `src/test/kotlin/pe/quantum/crm/mantenimiento/CarpetasDriveBackfillControllerWebMvcTest.kt`:

```kotlin
package pe.quantum.crm.mantenimiento

import com.ninjasquad.springmockk.MockkBean
import io.mockk.every
import io.mockk.verify
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.http.HttpHeaders
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.post
import pe.quantum.crm.config.security.JwtService
import pe.quantum.crm.mantenimiento.dto.BackfillCarpetasDto
import pe.quantum.crm.support.SinBaseDeDatosMocks

/** El backfill es exclusivo de admin (matriz_permisos.md §2.12). */
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
class CarpetasDriveBackfillControllerWebMvcTest {
    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var jwtService: JwtService

    @MockkBean
    lateinit var backfillService: CarpetasDriveBackfillService

    @Test
    fun `POST carpetas-drive como admin devuelve 200`() {
        every { backfillService.ejecutar(null) } returns
            BackfillCarpetasDto(
                empresasProcesadas = 2,
                oportunidadesProcesadas = 1,
                errores = emptyList(),
                pendientesRestantes = 0,
            )
        val token = jwtService.generateAccessToken(empleadoId = 1, rol = "admin")

        mockMvc.post("/api/v1/mantenimiento/carpetas-drive") {
            header(HttpHeaders.AUTHORIZATION, "Bearer $token")
        }.andExpect {
            status { isOk() }
            jsonPath("$.data.empresas_procesadas") { value(2) }
        }
        verify { backfillService.ejecutar(null) }
    }

    @Test
    fun `POST carpetas-drive como no-admin devuelve 403 y no ejecuta nada`() {
        val token = jwtService.generateAccessToken(empleadoId = 2, rol = "gerencia")

        mockMvc.post("/api/v1/mantenimiento/carpetas-drive") {
            header(HttpHeaders.AUTHORIZATION, "Bearer $token")
        }.andExpect {
            status { isForbidden() }
            jsonPath("$.error.code") { value("PERMISO_INSUFICIENTE") }
        }
        verify(exactly = 0) { backfillService.ejecutar(any()) }
    }

    @Test
    fun `POST carpetas-drive sin token devuelve 401`() {
        mockMvc.post("/api/v1/mantenimiento/carpetas-drive").andExpect {
            status { isUnauthorized() }
        }
        verify(exactly = 0) { backfillService.ejecutar(any()) }
    }
}
```

Este test SI corre con el contexto completo, asi que el JSON sale en `snake_case` real (`empresas_procesadas`), a diferencia del test standalone del Step 1 que usa camelCase.

Run: `./gradlew test --tests "pe.quantum.crm.mantenimiento.CarpetasDriveBackfillControllerWebMvcTest" --console=plain`
Expected: PASS (3 tests).

- [ ] **Step 6: Ejecutar la suite completa**

Run: `./gradlew test --console=plain`
Expected: solo los 6 fallos conocidos de `ImportCsvTempServiceImplTest`.

- [ ] **Step 7: Commit**

```bash
git add src/main/kotlin/pe/quantum/crm/mantenimiento/CarpetasDriveBackfillController.kt \
        src/test/kotlin/pe/quantum/crm/mantenimiento/
git commit -m "feat(drive): endpoint admin POST /mantenimiento/carpetas-drive

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

### Task 7: Documentacion del contrato

**Files:**
- Modify: `docs/contrato_api.md`
- Modify: `docs/matriz_permisos.md`

**Interfaces:**
- Consumes: los tres endpoints de Tasks 1, 2 y 6
- Produces: nada de codigo

- [ ] **Step 1: Documentar `POST /empresas/:id/carpeta-drive`**

En `docs/contrato_api.md`, justo antes de `### GET /empresas/:id/archivos`, insertar:

```markdown
### POST /empresas/:id/carpeta-drive
> Crea la carpeta de Google Drive de la empresa. Idempotente.

**Roles:** los mismos que ven la empresa (un vendedor solo las suyas).

**Body:** vacío.

**Respuesta 200:** `{ "data": { "drive_folder_id": "1AbCdEfGhIjKlMnOpQrStUvWxYz" } }`

**Notas:**
- Si la empresa ya tiene carpeta, la devuelve sin tocar Drive. El frontend puede llamarlo sin verificar antes.
- El botón "Crear File del Cliente" debe **ocultarse** cuando `drive_folder_id` ya viene distinto de `null` en `GET /empresas/:id`.
- Errores: `404 NO_ENCONTRADO` (ajena o inexistente) · `502 DRIVE_NO_DISPONIBLE` / `DRIVE_SIN_CUOTA`.

---
```

- [ ] **Step 2: Documentar `POST /oportunidades/:id/carpeta-drive`**

En `docs/contrato_api.md`, justo antes de `### GET /oportunidades/:id/archivos`, insertar:

```markdown
### POST /oportunidades/:id/carpeta-drive
> Crea la carpeta de Google Drive de la oportunidad, dentro de la de su empresa. Idempotente.

**Roles:** los mismos que ven la oportunidad (un vendedor solo las suyas).

**Body:** vacío.

**Respuesta 200:** `{ "data": { "drive_folder_id": "1XyZaBcDeFgHiJkLmNoPqRsTuV" } }`

**Notas:**
- Si la empresa de esa oportunidad tampoco tiene carpeta, se crean **ambas**: primero la de la empresa, y la de la oportunidad dentro.
- Si la oportunidad ya tiene carpeta, la devuelve sin tocar Drive.
- El botón "Crear File de la Oportunidad" debe **ocultarse** cuando `drive_folder_id` ya viene distinto de `null` en `GET /oportunidades/:id`.
- Errores: `404 NO_ENCONTRADO` (ajena o inexistente) · `502 DRIVE_NO_DISPONIBLE` / `DRIVE_SIN_CUOTA`.

---
```

- [ ] **Step 3: Documentar el backfill**

En `docs/contrato_api.md`, antes de la seccion `## Apéndice — Endpoints no implementados en MVP`, insertar:

```markdown
## 22. Mantenimiento

### POST /mantenimiento/carpetas-drive
> Crea las carpetas de Google Drive que faltan en empresas y oportunidades anteriores a la integración.

**Roles:** `admin`

**Query params:** `tamano_lote` (opcional). Sin él procesa **todos** los pendientes en un solo llamado.

**Respuesta 200:**

```json
{
  "data": {
    "empresas_procesadas": 12,
    "oportunidades_procesadas": 30,
    "errores": [
      { "entidad": "empresa", "id": 7, "motivo": "Google Drive no pudo crear la carpeta" }
    ],
    "pendientes_restantes": 1
  }
}
```

**Notas:**
- Idempotente y re-ejecutable. Si no hay pendientes responde todo en cero sin tocar Drive.
- Cada carpeta se persiste en su propia transacción: si la llamada se corta a la mitad, lo ya procesado queda guardado y repetir el endpoint retoma donde quedó.
- Un registro que falle no aborta el resto: se lista en `errores` y sigue pendiente. Repetir el endpoint lo reintenta.
- `pendientes_restantes > 0` significa que hace falta volver a llamarlo (por `tamano_lote` o por errores).

---
```

- [ ] **Step 4: Actualizar la matriz de permisos**

En `docs/matriz_permisos.md`, en la tabla `### 2.2 Empresas`, tras la fila de archivos de Drive:

```markdown
| Crear carpeta de Drive (`POST /empresas/:id/carpeta-drive`) | ✓ Cualquiera | ✓ Cualquiera | ✓ Cualquiera | ✓ Solo las suyas | ✓ Solo las suyas |
```

En `### 2.4 Oportunidades`, tras la fila de archivos de Drive:

```markdown
| Crear carpeta de Drive (`POST /oportunidades/:id/carpeta-drive`) | ✓ Cualquiera | ✓ Cualquiera | ✓ Cualquiera | ✓ Solo las suyas | ✓ Solo las suyas |
```

Y al final de `## 2. Operaciones por dominio`, una subseccion nueva:

```markdown
### 2.12 Mantenimiento

| Operación | admin | gerencia | jdv | vendedor | analista |
|---|---|---|---|---|---|
| Backfill de carpetas de Drive (`POST /mantenimiento/carpetas-drive`) | ✓ | — | — | — | — |
```

- [ ] **Step 5: Commit**

```bash
git add docs/contrato_api.md docs/matriz_permisos.md
git commit -m "docs: contrato y permisos de creacion y backfill de carpetas de Drive

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

### Task 8: Verificacion final y smoke test contra Drive real

**Files:**
- Create temporal (se borra al final): `src/test/kotlin/pe/quantum/crm/integracion/drive/BackfillSmokeTemporal.kt`

**Interfaces:**
- Consumes: todo lo anterior
- Produces: evidencia de que funciona contra la API real

- [ ] **Step 1: Formatear y verificar gates de calidad**

```bash
./gradlew ktlintFormat --console=plain
./gradlew ktlintCheck detekt --console=plain 2>&1 | grep -E "\.kt:" | grep -iE "mantenimiento|DriveController|CarpetaDriveDto"
```

Expected: sin hallazgos en los archivos nuevos. Los hallazgos preexistentes en `EmpresaServiceImpl`/`OportunidadServiceImpl` (`LongMethod` en `crear`, `CyclomaticComplexMethod` en `actualizar`) ya estaban antes de este trabajo y no se arreglan aqui.

- [ ] **Step 2: Suite completa**

Run: `./gradlew test --console=plain`
Expected: los unicos fallos son los 6 de `ImportCsvTempServiceImplTest`. Anotar el numero total de tests para el reporte final.

- [ ] **Step 3: Smoke test manual contra Drive real**

Crear el archivo temporal `src/test/kotlin/pe/quantum/crm/integracion/drive/BackfillSmokeTemporal.kt`:

```kotlin
package pe.quantum.crm.integracion.drive

import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport
import com.google.api.client.http.HttpRequestInitializer
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.drive.Drive
import com.google.api.services.drive.DriveScopes
import com.google.auth.http.HttpCredentialsAdapter
import com.google.auth.oauth2.ServiceAccountCredentials
import java.io.ByteArrayInputStream
import java.io.File
import java.util.Base64

/** ARCHIVO TEMPORAL — verifica el encadenado empresa→oportunidad contra la API real. */
fun main() {
    val env =
        File(".env")
            .readLines()
            .filter { it.contains("=") && !it.trimStart().startsWith("#") }
            .associate { linea ->
                val i = linea.indexOf('=')
                linea.substring(0, i).trim() to linea.substring(i + 1).trim()
            }
    val propiedades =
        DriveProperties(
            credentialsBase64 = env.getValue("GOOGLE_DRIVE_CREDENTIALS_BASE64"),
            rootFolderId = env.getValue("ROOT_DRIVE_FOLDER_ID"),
        )
    val credenciales =
        ServiceAccountCredentials
            .fromStream(ByteArrayInputStream(Base64.getDecoder().decode(propiedades.credentialsBase64)))
            .createScoped(listOf(DriveScopes.DRIVE))
    val drive =
        Drive
            .Builder(
                GoogleNetHttpTransport.newTrustedTransport(),
                GsonFactory.getDefaultInstance(),
                HttpRequestInitializer { req ->
                    HttpCredentialsAdapter(credenciales).initialize(req)
                    req.connectTimeout = propiedades.connectTimeoutMs
                    req.readTimeout = propiedades.readTimeoutMs
                },
            ).setApplicationName(propiedades.applicationName)
            .build()
    val servicio = DriveStorageServiceImpl(drive, propiedades)

    println("--- carpeta de empresa bajo la unidad compartida ---")
    val carpetaEmpresa = servicio.crearCarpeta("SMOKETEST-BACKFILL-20123456789 - ACME SAC")
    println("empresa=$carpetaEmpresa")

    println("--- subcarpeta de oportunidad DENTRO de la de la empresa ---")
    val carpetaOp = servicio.crearCarpeta("SMOKETEST-BACKFILL-OP-1 - BUS-X", carpetaEmpresa)
    println("oportunidad=$carpetaOp")

    println("--- verificacion leyendo de vuelta desde Drive ---")
    val leida =
        drive
            .files()
            .get(carpetaOp)
            .setSupportsAllDrives(true)
            .setFields("id, name, parents, driveId, mimeType")
            .execute()
    println("name=${leida.name}")
    println("parents=${leida.parents}  (debe ser [$carpetaEmpresa])")
    println("driveId=${leida.driveId}  (debe ser ${propiedades.rootFolderId})")
    check(leida.parents == listOf(carpetaEmpresa)) { "La subcarpeta NO quedo dentro de la carpeta de la empresa" }
    check(leida.driveId == propiedades.rootFolderId) { "NO quedo en la unidad compartida" }
    println("\nTODO OK. Borrar en Drive lo que empieza con SMOKETEST-")
}
```

Crear el init script en el scratchpad (mismo patron ya usado en esta integracion), por ejemplo `backfill-smoke-init.gradle`:

```groovy
allprojects {
    afterEvaluate { p ->
        if (p.plugins.hasPlugin('org.jetbrains.kotlin.jvm')) {
            p.tasks.register('backfillSmoke', JavaExec) {
                group = 'verification'
                classpath = p.sourceSets.test.runtimeClasspath
                mainClass = 'pe.quantum.crm.integracion.drive.BackfillSmokeTemporalKt'
            }
        }
    }
}
```

Run: `./gradlew backfillSmoke --console=plain -q --init-script <ruta-al-init-script>`
Expected: los dos `check(...)` pasan; `parents` = la carpeta de la empresa y `driveId` = la unidad compartida.

**No** ejecutar el endpoint de backfill real contra la base de datos en este paso: no hay Postgres local disponible. El backfill sobre datos reales lo dispara el usuario cuando despliegue.

- [ ] **Step 4: Borrar el archivo temporal**

```bash
rm -f src/test/kotlin/pe/quantum/crm/integracion/drive/BackfillSmokeTemporal.kt
./gradlew compileTestKotlin --console=plain -q
```

Expected: compila limpio sin el archivo.

- [ ] **Step 5: Reportar al usuario**

Informar: los tres endpoints nuevos, el resultado del smoke test con los ids reales creados, el conteo de tests, y **recordar que quedaron carpetas `SMOKETEST-` en la unidad compartida** pendientes de borrar (junto con las de sesiones anteriores).

- [ ] **Step 6: Commit final**

```bash
git status --porcelain
```

Revisar que no quede nada de este trabajo sin commitear. Si el `.env` aparece modificado, **no commitearlo** (esta en `.gitignore`, pero verificar).

---

## Notas de ejecucion

**Riesgo conocido — rama sucia:** `feature/b08-auth-endpoints` tiene WIP preexistente sin commitear en archivos rastreados, ajeno a este trabajo (entre otros, el cambio de delimitador CSV que rompe 6 tests). **Nunca** usar `git add -A`, `git add .` ni `git commit -a`. Cada commit lista sus archivos explicitamente.

**Orden de dependencias:** Tasks 1 y 2 son independientes entre si (solo comparten el DTO creado en Task 1). Task 5 depende de 3 y 4. Task 6 depende de 5. Tasks 7 y 8 al final.
