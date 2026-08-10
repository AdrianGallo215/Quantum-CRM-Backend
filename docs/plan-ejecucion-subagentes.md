# Plan de ejecución por subagentes — hallazgos [Alto] restantes

**Destinatario:** una sesión de Claude Code (Sonnet 5) que despliega subagentes en paralelo.
**Origen:** `docs/code-review-pendientes.md` (registro de hallazgos) y `docs/plan-correccion-code-review.md` (estrategia).
**Escrito el:** 2026-08-07, con el repo en `main`, commit base `c97f138`, Tanda 1 ya corregida y sin commitear.

Este documento contiene **tareas atómicas listas para ejecutar**. Cada una trae el test RED literal, el cambio de producción literal y su comando de verificación. No hay que decidir nada: hay que ejecutar.

---

## 0. Cómo usar este plan

### 0.1 Las tres decisiones de producto ya están tomadas

No las vuelvas a plantear. El dueño del proyecto respondió el 2026-08-07:

| | Decisión |
|---|---|
| **B1** RUC | Si el RUC existe y pertenece a **otro** vendedor → error soportado (409) con un mensaje que **no culpe al usuario**. Si pertenece al **mismo** vendedor → devolver la empresa existente con **200**. |
| **D1** Contraseña | **Sí**, implementar cambio de contraseña. |
| **F1** `id_modelo` | **`nullable = false`** en la entidad (lado código). **No** se toca el esquema. |

### 0.2 Reglas invariables (violarlas invalida la tarea)

1. **TDD estricto.** Escribe el test, **ejecútalo y compruébalo en rojo**, y solo entonces escribe el código de producción. Si el test pasa a la primera, está mal escrito: arréglalo hasta que falle por la razón correcta.
2. **Verificación tras cada tarea:** `./gradlew test ktlintCheck detekt` en verde.
3. **Testcontainers está roto en esta máquina** (Docker 29). Los tests `@Tag("integration")` **NO se pueden ejecutar aquí**. Puedes escribirlos, pero **declara explícitamente que no los has ejecutado** y no digas que pasan. Solo corren en CI.
4. **Nada de `git commit` ni `git push`.** El dueño revisa y commitea.
5. **No toques el esquema** (`src/main/resources/db/migration/`). Los cambios de esquema los aplica el dueño a mano en Supabase.
6. **No reduzcas el alcance de una tarea.** Si algo se bloquea, termina el resto y repórtalo explícitamente.
7. Si detekt se queja de `LongMethod` o `CyclomaticComplexMethod` tras tu cambio, **extrae un método privado**; no añadas `@Suppress`.

### 0.3 Propiedad de archivos — CRÍTICO para el paralelismo

Los agentes de una misma ola corren **en paralelo**. Cada archivo tiene **un solo dueño**. Si tu tarea te empuja a editar un archivo que no está en tu lista, **para y repórtalo**; no lo edites.

**Ningún agente de la Ola 1 puede tocar `docs/contrato_api.md` ni `build.gradle.kts`.** Esos son de la Ola 2. Si tu cambio afecta al contrato, escríbelo en tu informe final; el agente F lo consolidará.

### 0.4 Estado base esperado

Antes de empezar, verifica:

```bash
git status --porcelain     # solo docs/ y scripts/ sin trackear, más los .kt de la Tanda 1 modificados
./gradlew test ktlintCheck detekt   # debe estar VERDE
```

Si el baseline está rojo, **para y repórtalo**. No construyas sobre un build roto.

---

## 1. Mapa de olas y agentes

```
OLA 1 (5 agentes en paralelo, archivos disjuntos)
  ├── Agente A — empresas / RUC              → B1
  ├── Agente B — auth + empleados            → D1, D2
  ├── Agente C — oportunidades               → F1, A1
  ├── Agente D — catálogos                   → F2c
  └── Agente E — reportes                    → F2d, E2
                    ↓ (todos terminados y en verde)
OLA 2 (2 agentes en paralelo)
  ├── Agente F — documentación de contrato
  └── Agente G — trinquete de Kover          → F3
```

**El agente G debe ir el último de todos** porque mide la cobertura que producen A–E.

| Agente | Archivos de los que es **dueño exclusivo** |
|---|---|
| **A** | `domain/empresas/EmpresaService.kt`, `EmpresaServiceImpl.kt`, `EmpresaController.kt`, `domain/empresas/dto/EmpresaDtos.kt`, `shared/exception/NegocioExceptions.kt`, `test/.../empresas/EmpresaServiceImplTest.kt`, `test/.../empresas/EmpresaControllerWebMvcTest.kt` |
| **B** | `domain/empleados/EmpleadoService.kt`, `EmpleadoServiceImpl.kt`, `AuthController.kt`, `domain/empleados/dto/*.kt`, `config/security/SecurityConfig.kt`, `test/.../empleados/EmpleadoServiceTest.kt`, `AuthControllerWebMvcTest.kt`, `EmpleadoCrudControllerWebMvcTest.kt` |
| **C** | `domain/oportunidades/Oportunidad.kt`, `OportunidadServiceImpl.kt`, `OportunidadesDeContacto.kt`, `test/.../oportunidades/OportunidadServiceImplTest.kt`, **nuevo** `test/.../oportunidades/EstadoCarteraServiceTest.kt` |
| **D** | **nuevos** `test/.../modelos/ModeloServiceImplTest.kt`, `test/.../financiadoras/FinanciadoraServiceImplTest.kt`, `test/.../catalogoeventos/CatalogoEventoServiceImplTest.kt` |
| **E** | **nuevo** `test/.../reportes/ReporteServiceSqlIntegrationTest.kt` |
| **F** | `docs/contrato_api.md`, `docs/code-review-pendientes.md` |
| **G** | `build.gradle.kts`, `test/.../config/QualityGatesConfigTest.kt` |

---

# OLA 1

---

## Agente A — empresas / RUC (hallazgo B1)

> **Contexto que no debes re-derivar.** `reglas_negocio.md §2.1` dice literalmente:
> - *"Si existe y está asignada a otro vendedor, la respuesta es `409 Conflict` con el mensaje: `"Esta empresa ya está registrada en el sistema"`. No se expone a qué vendedor pertenece."*
> - *"Si existe y está asignada al mismo vendedor, se retorna la empresa existente con `200 OK`."*
>
> Hoy el código lanza 409 **siempre**. `EmpresaRepository.findByRuc` ya existe y no se usa en ningún sitio: es el resto de esta rama nunca implementada.
>
> **Restricción que descubrió el análisis y que DEBES respetar:** el import CSV depende de que `crearSinCarpetaDrive` **lance** `RucDuplicadoException` ante cualquier duplicado — así construye su reporte de errores (ver `ImportCsvTempServiceImplTest.kt:141`). Por tanto el comportamiento nuevo se aplica **solo a `crear`**, nunca a `crearSinCarpetaDrive`.

### Tarea A.1 — Tipo de resultado del alta

**Archivo:** `src/main/kotlin/pe/quantum/crm/domain/empresas/dto/EmpresaDtos.kt`

Añade al final del archivo:

```kotlin
/**
 * Resultado del alta de una empresa. `creada = false` significa que el RUC ya
 * existía en la cartera del MISMO vendedor y se devuelve la empresa existente
 * (reglas_negocio.md §2.1), lo que el controller traduce a 200 en vez de 201.
 */
data class AltaEmpresaResultado(
    val empresa: EmpresaDetalleDto,
    val creada: Boolean,
)
```

**Sin test propio** (es una data class sin lógica; la cubren A.3 y A.4).

---

### Tarea A.2 — Excepción con mensaje no culpabilizador

**Archivo:** `src/main/kotlin/pe/quantum/crm/shared/exception/NegocioExceptions.kt`

Localiza `RucDuplicadoException` y **sustituye solo su mensaje**, dejando intactos `code` y `status`:

```kotlin
/**
 * El RUC ya existe y pertenece a otro vendedor. No expone a quien (reglas §2.1).
 * El mensaje evita culpar al usuario: registrar un RUC que otro ya trabaja no es
 * un error suyo, es informacion que no tenia.
 */
class RucDuplicadoException :
    ApiException(
        code = "RUC_DUPLICADO",
        message =
            "Esta empresa ya está registrada en el sistema y la gestiona otro vendedor. " +
                "Coordina con tu jefe de ventas si necesitas acceder a ella.",
        status = HttpStatus.CONFLICT,
        field = "ruc",
    )
```

> **No cambies el `code`.** El frontend ya maneja `RUC_DUPLICADO` y `contrato_api.md §3` lo documenta. Cambiarlo rompería al frontend sin avisar.

**Después de este cambio:** ejecuta `./gradlew test` y **arregla los tests que afirmen el mensaje viejo** (búscalos con `grep -rn "ya está registrada" src/test`). Ese es el único motivo legítimo para tocar un test fuera de tu lista; si el archivo no es tuyo, repórtalo en vez de editarlo.

---

### Tarea A.3 — RED: comportamiento del alta según el dueño del RUC

**Archivo:** `src/test/kotlin/pe/quantum/crm/domain/empresas/EmpresaServiceImplTest.kt`

Añade **tres** tests. Reutiliza las fixtures y stubs que ya usa el test de creación existente en ese archivo (búscalo con `grep -n "fun \`crear" EmpresaServiceImplTest.kt`); copia su bloque de `every { ... }` y adáptalo.

1. `` `crear con un RUC del mismo vendedor devuelve la empresa existente sin insertar` ``
   - `every { empresaRepository.findByRuc("20512345678") } returns` una `Empresa` existente con `idVendedor = <el mismo del usuario>`.
   - Ejecuta `service.crear(...)`.
   - Afirma: `resultado.creada` es `false`; `resultado.empresa.id` es el de la empresa existente.
   - Afirma: `verify(exactly = 0) { empresaRepository.save(any()) }`.
   - Afirma que **no se llamó a Drive**: `verify(exactly = 0) { driveStorageService.<método de crear carpeta>(...) }` (mira cómo lo llama el test de creación existente).

2. `` `crear con un RUC de otro vendedor lanza RUC_DUPLICADO` ``
   - `findByRuc` devuelve una `Empresa` con `idVendedor` **distinto** al del usuario.
   - `assertThrows<RucDuplicadoException> { service.crear(...) }`.
   - Afirma `verify(exactly = 0) { empresaRepository.save(any()) }`.

3. `` `crear con un RUC nuevo inserta y marca creada` ``
   - `findByRuc` devuelve `null`.
   - Afirma `resultado.creada` es `true` y que `save` sí se llamó.

**Ejecuta y comprueba el rojo:**
```bash
./gradlew test --tests "pe.quantum.crm.domain.empresas.EmpresaServiceImplTest"
```
Los tres deben fallar (no compilarán hasta que `crear` devuelva `AltaEmpresaResultado`; añade la firma en A.4 y vuelve a ejecutar para ver el rojo **de comportamiento** antes de escribir la lógica).

---

### Tarea A.4 — GREEN: lógica del alta

**Archivo:** `src/main/kotlin/pe/quantum/crm/domain/empresas/EmpresaService.kt`

Cambia **solo** la firma de `crear` (deja `crearSinCarpetaDrive` exactamente como está):

```kotlin
    /**
     * Alta de empresa (reglas §2.1). Si el RUC ya existe y es del mismo vendedor,
     * devuelve la existente con `creada = false` en vez de fallar; si es de otro
     * vendedor, lanza `RucDuplicadoException`.
     */
    fun crear(
        request: CrearEmpresaRequest,
        usuario: UsuarioActual,
    ): AltaEmpresaResultado
```

**Archivo:** `src/main/kotlin/pe/quantum/crm/domain/empresas/EmpresaServiceImpl.kt`

En `alta(...)`, sustituye este bloque exacto:

```kotlin
        val idVendedor = vendedorAlCrear(request.idVendedor, usuario)
        if (empresaRepository.existsByRuc(request.ruc)) {
            throw RucDuplicadoException()
        }
```

por:

```kotlin
        val idVendedor = vendedorAlCrear(request.idVendedor, usuario)
        val existente = empresaRepository.findByRuc(request.ruc)
        if (existente != null) {
            // Del mismo vendedor: no es un error, ya la tiene en su cartera. Se
            // devuelve tal cual, sin insertar ni crear carpeta de Drive.
            if (reutilizarDelMismoVendedor && existente.idVendedor == idVendedor) {
                return AltaEmpresaResultado(existente.conContactos(), creada = false)
            }
            throw RucDuplicadoException()
        }
```

Y adapta la firma de `alta` y sus dos llamadores:

- `alta(request, usuario, conCarpetaDrive: Boolean, reutilizarDelMismoVendedor: Boolean): AltaEmpresaResultado`
- `crear(...)` → `alta(request, usuario, conCarpetaDrive = true, reutilizarDelMismoVendedor = true)`
- `crearSinCarpetaDrive(...)` → `alta(request, usuario, conCarpetaDrive = false, reutilizarDelMismoVendedor = false).empresa`
  (mantiene su tipo de retorno `EmpresaDetalleDto` y su comportamiento actual de lanzar siempre)

El `return` final de `alta` debe envolverse en `AltaEmpresaResultado(..., creada = true)`.

> **Cuidado:** `alta` NO lleva `@Transactional` a propósito (la llamada a Drive va antes de abrir la transacción). No añadas la anotación. El comentario grande que hay encima de `alta` explica por qué; consérvalo.

**Verifica el verde:**
```bash
./gradlew test --tests "pe.quantum.crm.domain.empresas.EmpresaServiceImplTest"
```

---

### Tarea A.5 — RED + GREEN: el controller devuelve 200 o 201

**Archivo RED:** `src/test/kotlin/pe/quantum/crm/domain/empresas/EmpresaControllerWebMvcTest.kt`

Dos tests:
1. `` `POST empresas con RUC nuevo responde 201` `` — el servicio mockeado devuelve `creada = true`; espera `status().isCreated`.
2. `` `POST empresas con RUC ya propio responde 200` `` — el servicio devuelve `creada = false`; espera `status().isOk`.

Ejecuta y comprueba el rojo del segundo.

**Archivo GREEN:** `src/main/kotlin/pe/quantum/crm/domain/empresas/EmpresaController.kt`

Sustituye el método `crear`. Hoy es:

```kotlin
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun crear(
        ...
    ): ApiResponse<EmpresaDetalleDto> = ApiResponse.ok(empresaService.crear(request, usuarioProvider.actual()))
```

Debe pasar a **eliminar `@ResponseStatus`** y devolver `ResponseEntity`:

```kotlin
    @PostMapping
    fun crear(
        @Valid @RequestBody request: CrearEmpresaRequest,
    ): ResponseEntity<ApiResponse<EmpresaDetalleDto>> {
        val resultado = empresaService.crear(request, usuarioProvider.actual())
        // 201 solo si de verdad se creó; si el RUC ya era suyo, 200 (reglas §2.1).
        val status = if (resultado.creada) HttpStatus.CREATED else HttpStatus.OK
        return ResponseEntity.status(status).body(ApiResponse.ok(resultado.empresa))
    }
```

Respeta la firma real de los parámetros que ya tiene el método (cópiala del archivo; no la inventes).

**Verificación final del agente A:**
```bash
./gradlew test ktlintCheck detekt
```

**En tu informe final, incluye textualmente** este bloque para el agente F:

> `contrato_api.md §8`, `POST /empresas`: la nota *"El backend valida el RUC antes de insertar. Si ya existe → `409 RUC_DUPLICADO`"* pasa a: si el RUC existe y es de **otro** vendedor → `409 RUC_DUPLICADO`; si es del **mismo** vendedor → `200 OK` con la empresa existente, sin crear carpeta de Drive. La respuesta ya no es siempre 201. Mensaje nuevo de `RUC_DUPLICADO`: "Esta empresa ya está registrada en el sistema y la gestiona otro vendedor. Coordina con tu jefe de ventas si necesitas acceder a ella."

---

## Agente B — auth + empleados (hallazgos D1 y D2)

> **Contexto que no debes re-derivar.**
> - `Empleado` tiene `var passwordHash: String?` y `var requiereCambioContrasena: Boolean = true`.
> - `EmpleadoServiceImpl` ya inyecta `passwordEncoder: PasswordEncoder`.
> - Hoy `crear()` pone `requiereCambioContrasena = true` y **no existe ningún endpoint que lo apague**. El flag se publica en el login (`LoginResponse.requiereCambioContrasena`) y nunca se puede cumplir.
> - **PELIGRO:** `SecurityConfig.kt:47` hace `it.requestMatchers("/api/v1/auth/**", "/actuator/health").permitAll()`. Un endpoint nuevo bajo `/api/v1/auth/` nacería **público**. La tarea B.3 lo blinda con un matcher explícito **y** un test que lo verifica. No te saltes ese test: es lo que impide publicar un cambio de contraseña sin autenticación.

### Tarea B.1 — RED: servicio de cambio de contraseña

**Archivo:** `src/test/kotlin/pe/quantum/crm/domain/empleados/EmpleadoServiceTest.kt`

Cuatro tests sobre un método nuevo `cambiarContrasena(idEmpleado, actual, nueva)`:

1. `` `cambiar contrasena con la actual correcta guarda el hash nuevo y apaga el flag` ``
   - Empleado con `passwordHash = encoder.encode("vieja")`, `requiereCambioContrasena = true`.
   - Llama `service.cambiarContrasena(1, "vieja", "NuevaSegura123")`.
   - Afirma que el `passwordHash` guardado **ya no es** el anterior y que `passwordEncoder.matches("NuevaSegura123", nuevoHash)` es `true`.
   - Afirma `requiereCambioContrasena` quedó en `false`.
2. `` `cambiar contrasena con la actual incorrecta lanza CREDENCIALES_INVALIDAS y no guarda` ``
   - `assertThrows<CredencialesInvalidasException>`; `verify(exactly = 0) { empleadoRepository.save(any()) }`.
3. `` `cambiar contrasena rechaza que la nueva sea igual a la actual` ``
   - Espera `ValidacionException` con `field == "password_nueva"`.
4. `` `cambiar contrasena de un empleado inexistente lanza NO_ENCONTRADO` ``
   - `assertThrows<NoEncontradoException>`.

> Usa un `PasswordEncoder` **real** (`BCryptPasswordEncoder()`), no un mock: el hashing es justo lo que se está probando. Mockea solo `empleadoRepository`.

Ejecuta y comprueba el rojo.

### Tarea B.2 — GREEN: servicio

**Archivo:** `src/main/kotlin/pe/quantum/crm/domain/empleados/EmpleadoService.kt` — añade a la interfaz:

```kotlin
    /**
     * Cambia la contraseña del propio empleado y apaga `requiere_cambio_contrasena`
     * (B1.4). Exige la contraseña actual: sin ella, una sesion robada podria
     * apoderarse de la cuenta de forma permanente.
     */
    fun cambiarContrasena(
        idEmpleado: Long,
        actual: String,
        nueva: String,
    )
```

**Archivo:** `EmpleadoServiceImpl.kt` — implementa:

```kotlin
    @Transactional
    override fun cambiarContrasena(
        idEmpleado: Long,
        actual: String,
        nueva: String,
    ) {
        val empleado = porId(idEmpleado)
        val hash = empleado.passwordHash
        if (hash == null || !passwordEncoder.matches(actual, hash)) {
            throw CredencialesInvalidasException()
        }
        if (passwordEncoder.matches(nueva, hash)) {
            throw ValidacionException("La contraseña nueva debe ser distinta de la actual", field = "password_nueva")
        }
        empleado.passwordHash = passwordEncoder.encode(nueva)
        empleado.requiereCambioContrasena = false
        empleadoRepository.save(empleado)
    }
```

Añade los `import` que falten.

### Tarea B.3 — RED: el endpoint exige autenticación

**Archivo:** `src/test/kotlin/pe/quantum/crm/domain/empleados/AuthControllerWebMvcTest.kt`

Tres tests:

1. `` `cambiar contrasena sin autenticacion responde 401` `` ← **el test de seguridad; no lo omitas**
2. `` `cambiar contrasena autenticado con body valido responde 200 y llama al servicio` ``
3. `` `cambiar contrasena con password_nueva corta responde 400 VALIDACION` ``

> Este archivo debe cargar la cadena de filtros real de Spring Security para que el test 1 signifique algo. Copia el montaje (`@WebMvcTest` + `@Import(SecurityConfig::class)` o el que ya use) de los tests existentes del propio archivo. Si el montaje actual **no** aplica seguridad, el test 1 pasaría en falso: en ese caso, móntalo con `springSecurity()` en el `MockMvc` y **dilo en tu informe**.

### Tarea B.4 — GREEN: DTO, endpoint y matcher de seguridad

**DTO** — en el archivo de DTOs de empleados que ya contiene `LoginRequest`:

```kotlin
/** Cambio de contraseña del propio usuario (contrato_api.md §6). */
data class CambiarContrasenaRequest(
    @field:NotBlank(message = "password_actual es obligatorio")
    val passwordActual: String,
    @field:NotBlank(message = "password_nueva es obligatorio")
    @field:Size(min = 8, max = 72, message = "password_nueva debe tener entre 8 y 72 caracteres")
    val passwordNueva: String,
)
```

> El máximo de 72 no es arbitrario: BCrypt trunca silenciosamente por encima de 72 bytes.

**Endpoint** — en `AuthController.kt`:

```kotlin
    /**
     * Cambio de contraseña del usuario autenticado. Vive bajo `/auth` por afinidad
     * de dominio, pero a diferencia del resto de `/auth/**` EXIGE autenticacion:
     * ver el matcher explicito en SecurityConfig.
     */
    @PostMapping("/cambiar-contrasena")
    fun cambiarContrasena(
        @Valid @RequestBody request: CambiarContrasenaRequest,
        authentication: Authentication,
    ): ApiResponse<Unit> {
        empleadoService.cambiarContrasena(
            authentication.principal as Long,
            request.passwordActual,
            request.passwordNueva,
        )
        return ApiResponse.ok(Unit)
    }
```

**Seguridad** — en `SecurityConfig.kt`, dentro de `authorizeHttpRequests`, la línea nueva va **ANTES** de la de `permitAll` (Spring Security aplica el primer matcher que casa):

```kotlin
                // ANTES del permitAll de /auth/**: este endpoint es el unico de la
                // familia que exige sesion. Invertir el orden lo dejaria publico.
                it.requestMatchers(HttpMethod.POST, "/api/v1/auth/cambiar-contrasena").authenticated()
                it.requestMatchers("/api/v1/auth/**", "/actuator/health").permitAll()
                it.anyRequest().authenticated()
```

Añade `import org.springframework.http.HttpMethod`.

**Verifica:** el test `` `cambiar contrasena sin autenticacion responde 401` `` debe pasar. Si pasa **antes** de añadir el matcher, tu montaje de test no aplica seguridad: arréglalo.

### Tarea B.5 — RED + GREEN: cobertura HTTP del CRUD de empleados (D2)

**Archivo:** `src/test/kotlin/pe/quantum/crm/domain/empleados/EmpleadoCrudControllerWebMvcTest.kt`

Este archivo hoy solo cubre la validación del `PUT`. Añade:

1. `` `GET empleados devuelve la lista` `` — 200 y el JSON esperado.
2. `` `POST empleados con body valido responde 201` ``
3. `` `POST empleados con email duplicado responde 409 EMAIL_DUPLICADO` `` — el servicio mockeado lanza la excepción correspondiente (localízala con `grep -rn "EMAIL_DUPLICADO" src/main`).
4. `` `GET empleados con rol vendedor responde 403` `` — `@PreAuthorize("hasAnyRole('admin','gerencia','jdv')")`.
5. `` `POST empleados con rol gerencia responde 403` `` — solo `admin`.
6. `` `PUT empleados con rol gerencia responde 403` ``
7. `` `PATCH empleados activo con rol gerencia responde 403` ``

> Los cuatro tests de `403` son el corazón de este hallazgo: hoy **ningún** test protege los `@PreAuthorize`. Deben montarse con la seguridad real; si el montaje del archivo no la aplica, arréglalo y dilo.
>
> **B1.4** es la regla *"todo empleado nuevo nace con `requiere_cambio_contrasena = true`"*. Cúbrela en el test 2 afirmando que el DTO devuelto la refleja.

**Verificación final del agente B:**
```bash
./gradlew test ktlintCheck detekt
```

**Informe para el agente F** (inclúyelo textualmente):

> `contrato_api.md §6`: endpoint nuevo `POST /api/v1/auth/cambiar-contrasena`. Requiere autenticación (único de `/auth/**` que la exige). Body: `{"password_actual": "...", "password_nueva": "..."}` (8–72 caracteres). Respuestas: `200` sin datos; `401 CREDENCIALES_INVALIDAS` si `password_actual` no coincide; `400 VALIDACION` con `field: "password_nueva"` si la nueva es igual a la actual o no cumple longitud. Al completarse, `requiere_cambio_contrasena` pasa a `false` y el siguiente login lo refleja.

---

## Agente C — oportunidades (hallazgos F1 y A1)

> **Contexto que no debes re-derivar.** Este es el núcleo del negocio y hoy **no tiene una sola aserción** sobre sus invariantes. Las reglas están implementadas en `OportunidadServiceImpl.cambiarEstado` y son correctas; lo que falta es lo que impida que alguien las borre sin darse cuenta.

### Tarea C.1 — F1: `id_modelo` no anulable

**RED** — en `src/test/kotlin/pe/quantum/crm/domain/oportunidades/OportunidadServiceImplTest.kt`:

`` `toda oportunidad persistida lleva id_modelo` `` — captura la `Oportunidad` que se pasa a `save` al crear y afirma que `idModelo` no es nulo.

**GREEN** — en `src/main/kotlin/pe/quantum/crm/domain/oportunidades/Oportunidad.kt`, sustituye:

```kotlin
    @Column(name = "id_modelo")
    var idModelo: Long? = null,
```

por:

```kotlin
    // NOT NULL en la tabla desde su creacion; la entidad lo declaraba opcional y
    // varios puntos de lectura lo trataban como tal. La columna manda.
    @Column(name = "id_modelo", nullable = false)
    var idModelo: Long,
```

**Propagación obligatoria.** Al quitar el `?`, el compilador señalará cada punto que lo trataba como opcional. Corrige **todos** así:

| Archivo:línea (referencia) | Hoy | Debe quedar |
|---|---|---|
| `OportunidadServiceImpl.kt:190` | `oportunidad.idModelo?.let { modeloService.resumen(it).precioBase }` | `modeloService.resumen(oportunidad.idModelo).precioBase` |
| `OportunidadServiceImpl.kt:575` | `oportunidad.idModelo?.let { modeloService.resumen(it).codigo }` | `modeloService.resumen(oportunidad.idModelo).codigo` |
| `OportunidadServiceImpl.kt:679` | `oportunidades.mapNotNull { it.idModelo }` | `oportunidades.map { it.idModelo }` |
| `OportunidadServiceImpl.kt:694` | `op.idModelo?.let { modelos[it] }?.let {` | `modelos[op.idModelo]?.let {` |
| `OportunidadesDeContacto.kt:65` | `oportunidades.values.mapNotNull { it.idModelo }` | `oportunidades.values.map { it.idModelo }` |
| `OportunidadesDeContacto.kt:72` | `op.idModelo?.let { modelos[it] }?.let {` | `modelos[op.idModelo]?.let {` |

> Las líneas son de referencia y pueden haberse movido. Localízalas con `grep -n "idModelo"`.
>
> **NO toques `OportunidadDtos.kt`.** `OportunidadDto.idModelo: Long?` (línea ~42) y `ActualizarOportunidadRequest.idModelo: Long? = null` (línea ~166) siguen siendo nullable a propósito: el primero es contrato hacia el frontend y el segundo es un PATCH parcial. Solo `CrearOportunidadRequest.idModelo: Long` (línea ~106) es no-nulo, y ya lo era.

### Tarea C.2 — A1: invariantes de `cambiarEstado`

**Archivo:** `OportunidadServiceImplTest.kt`. Un test por invariante, todos sobre `service.cambiarEstado(...)`:

1. `` `cerrar sin motivo_cierre lanza MOTIVO_CIERRE_REQUERIDO` `` — `assertThrows<MotivoCierreRequeridoException>`.
2. `` `cerrar con motivo lo persiste en la oportunidad` `` — afirma `oportunidad.motivoCierre` quedó con el valor enviado.
3. `` `retroceder desde cerrado limpia motivo_cierre` `` — pasa de `cerrado` a otro estado; afirma que `motivoCierre` quedó en `null` (reglas §13.4).
4. `` `un vendedor no puede pasar a facturado` `` — `UsuarioActual(rol = "vendedor")`; `assertThrows<PermisoInsuficienteException>`.
5. `` `un jdv tampoco puede pasar a facturado` `` — mismo patrón. *(`jdv` es supervisor pero **no** valida facturado: `UsuarioActual.puedeValidarFacturado` solo admite `admin`, `gerencia`, `analista`. Es el caso que más fácil se rompe.)*
6. `` `admin, gerencia y analista si pueden pasar a facturado` `` — los tres, sin excepción, y afirma que `facturadoEn` quedó no nulo.
7. `` `es_retroceso viene en true al volver a un estado anterior` `` — afirma `resultado.esRetroceso`.
8. `` `es_retroceso viene en false al avanzar` ``.
9. `` `cambiar al mismo estado lanza ESTADO_INVALIDO` ``.
10. `` `un estado desconocido lanza ESTADO_INVALIDO` `` — `request.estado = "perdido"` (no existe en el enum).
11. `` `cambiarEstado registra una fila en el log con el estado anterior y el nuevo` `` — captura el `OportunidadEstadoLog` guardado y afirma `estadoAnterior`/`estadoNuevo`.

Y sobre `MONTO_NO_EDITABLE` (localiza dónde se lanza con `grep -rn "MontoNoEditableException" src/main`):

12. `` `enviar monto_total en el body lanza MONTO_NO_EDITABLE` `` — en crear **y** en actualizar, un test cada uno.

### Tarea C.3 — A1: reescribir el test que no prueba nada

En `OportunidadServiceImplTest.kt` hay un test llamado aproximadamente *"excluyendo al actor si es supervisor"* (búscalo con `grep -n "excluyendo al actor"`). **Afirma sobre los argumentos del mock, no sobre el comportamiento**: no detectaría que se borre la exclusión real.

Reescríbelo para que afirme sobre el **resultado observable**: que el actor **no** aparece entre los destinatarios de la notificación emitida. Debe fallar si alguien elimina la lógica de exclusión.

> Comprueba que discrimina: quita mentalmente la exclusión en el código y confirma que tu test se pondría rojo. Si no, no sirve.

### Tarea C.4 — A1: tests de `EstadoCarteraService`

**Archivo nuevo:** `src/test/kotlin/pe/quantum/crm/domain/oportunidades/EstadoCarteraServiceTest.kt`

`EstadoCarteraService` es la **única** vía por la que el sistema modifica `estado_cartera` (CLAUDE.md regla 3) y no tiene tests. Mockea `oportunidadRepository` y `empresaService`:

1. `` `con una oportunidad facturada el estado derivado es cliente` ``
2. `` `con una oportunidad activa y ninguna facturada el derivado es oportunidad_activa` ``
3. `` `sin oportunidades activas ni facturadas el derivado es null` ``
4. `` `facturado gana sobre activa` `` — ambas existen; debe salir `cliente`.
5. `` `el resultado es el que devuelve aplicarEstadoDerivado` `` — afirma que se propaga el `CambioEstadoCartera` de `empresaService`.

**Verificación final del agente C:**
```bash
./gradlew test ktlintCheck detekt
```

---

## Agente D — catálogos (hallazgo F2, parte c)

> **Contexto.** `modelos`, `financiadoras` y `catalogoeventos` tienen **cobertura cero**. Cada uno es `Controller → Service → ServiceImpl → Repository` con lógica pequeña pero real.
>
> Creas **tres archivos nuevos** y no modificas ninguno existente. Si un test revela un bug de producción, **NO lo arregles**: anótalo en tu informe. Tu tarea es cobertura, no corrección — un fix sin decisión previa se sale del alcance.

Lee cada `*ServiceImpl.kt` antes de escribir sus tests y cubre, como mínimo:

### D.1 — `ModeloServiceImplTest.kt`
- `crear` con código duplicado → el error que lance hoy.
- `crear` sin aplicaciones → `ModeloSinAplicacionesException` (reglas §2.4).
- `crear` happy path → persiste y devuelve el DTO.
- `actualizar` de un id inexistente → `NoEncontradoException`.
- `listar` → mapea a DTO.

### D.2 — `FinanciadoraServiceImplTest.kt`
- `crear` marcando `es_default` desmarca la anterior default.
- `actualizar` marcando `es_default` desmarca la anterior.
- `listar` → mapea a DTO.
- `porId` inexistente → `NoEncontradoException`.

> El caso *"más de una default"* ya está bien cubierto por el código. El caso *"pasar de una default a cero"* es un hallazgo [Medio] **abierto**: no lo corrijas, pero si escribes un test que lo documente, márcalo `@Disabled` con un comentario que explique por qué, o simplemente anótalo en el informe.

### D.3 — `CatalogoEventoServiceImplTest.kt`
- `crear` con nombre duplicado → el error que lance hoy.
- `hitosProspeccion()` devuelve solo los de `esHitoProspeccion = true`, **en orden**.
- `todosPorId()` indexa por id.
- `listar(etapaAsociada)` filtra.
- `actualizar` de un id inexistente → `NoEncontradoException`.

**Verificación final del agente D:**
```bash
./gradlew test ktlintCheck detekt
```

---

## Agente E — reportes (hallazgo F2 parte d, y E2)

> **LEE ESTO PRIMERO.** `ReporteService` es ~900 líneas de **SQL nativo agregado**. No se puede probar con mocks sin convertir el test en una tautología: mockear el `JdbcTemplate` probaría el mock, no la consulta. La única prueba honesta es contra Postgres real, o sea `@Tag("integration")`.
>
> **Esos tests NO se pueden ejecutar en esta máquina** (Testcontainers roto por Docker 29). Los escribes, **no los ejecutas**, y en tu informe dices literalmente que no los has ejecutado y que solo se validarán en CI. **No afirmes que pasan.**

**Archivo nuevo:** `src/test/kotlin/pe/quantum/crm/domain/reportes/ReporteServiceSqlIntegrationTest.kt`

Móntalo copiando la estructura de `ReporteServiceIntegrationTest.kt`, que ya existe (`@Tag("integration")`, `@SpringBootTest`, `IntegrationTestBase`, fixtures de `SeedFixtures`).

Cubre, con datos sembrados y aserciones sobre cifras concretas:

1. **`/reportes/ventas`** — una oportunidad facturada dentro del rango y otra fuera; afirma que solo cuenta la primera y que el monto agregado es el esperado.
2. **`/reportes/ventas` sin log de estados** — una oportunidad facturada **sin** fila en `oportunidad_estado_log`. Debe aparecer igual (usa `oportunidades.facturado_en`). *Es la regresión del hallazgo [Crítico] ya corregido; este test lo blinda.*
3. **`/reportes/equipo`** — dos vendedores con ventas distintas; afirma el desglose.
4. **`/reportes/prospeccion`** — el embudo cuenta como *ingresada* solo lo que cumple `estado = 'ocurrido'` **y** `id_oportunidad IS NULL`. Siembra un caso de cada tipo y afirma que el excluido no suma.
5. **`/reportes/descuentos`** — el promedio sobre un conjunto conocido.

> Los hallazgos [Medio] de `/reportes/descuentos` (criterio de NULL inconsistente) y de los índices por `ROW_NUMBER()` siguen **abiertos**. No los corrijas. Escribe los tests contra el comportamiento **actual** y anota en el informe cuál es ese comportamiento, para que la decisión se tome con datos.

**Verificación del agente E:**
```bash
./gradlew test ktlintCheck detekt     # los @Tag("integration") NO se ejecutan aquí; deben quedar excluidos
```
Comprueba que el nuevo archivo **no** rompe `./gradlew test` (debe quedar filtrado por el tag). Si `test` intenta ejecutarlo, el tag está mal puesto.

---

# OLA 2 — solo cuando A, B, C, D y E estén terminados y en verde

---

## Agente F — documentación de contrato

**Archivos:** `docs/contrato_api.md`, `docs/code-review-pendientes.md`. **Ningún archivo `.kt`.**

### F.1 — Aplicar los deltas de contrato
Toma los bloques *"Informe para el agente F"* de los agentes A y B y aplícalos a `contrato_api.md`:
- **§8 `POST /empresas`**: respuesta 200 vs 201 y el mensaje nuevo de `RUC_DUPLICADO`.
- **§6**: el endpoint nuevo `POST /auth/cambiar-contrasena`, con su tabla de respuestas.

### F.2 — Reconciliar la contradicción de la regla 2.1
`contrato_api.md` (nota de `POST /empresas`) decía *"Si ya existe → 409 RUC_DUPLICADO"* mientras `reglas_negocio.md §2.1` decía que el mismo vendedor recibe 200. **Ya no se contradicen**: el código implementa la regla. Deja el contrato alineado con `reglas_negocio.md §2.1` y añade una nota cruzada para que no vuelva a divergir.

### F.3 — Enums de notificación desactualizados
Hallazgo [Medio] abierto: `contrato_api.md §19` documenta 9 tipos de notificación y el enum real tiene 16. Cuenta los valores reales:
```bash
grep -c "" src/main/kotlin/pe/quantum/crm/domain/notificaciones/NotificacionEnums.kt
```
Abre `NotificacionEnums.kt`, lista los valores reales de `TipoNotificacion` y sincroniza §19 con ellos. **Solo documentación**; no toques el enum.

### F.4 — Actualizar el registro de hallazgos
En `docs/code-review-pendientes.md`, marca como ✅ los hallazgos cerrados por esta ronda (B1, D1, D2, F1, A1, F2, E2 y el [Medio] de §19), con una línea que explique **qué** se hizo, no solo que se hizo. Actualiza la línea de **Resumen** con el recuento nuevo.

---

## Agente G — trinquete de Kover (hallazgo F3) — **EL ÚLTIMO DE TODOS**

**Archivos:** `build.gradle.kts`, `src/test/kotlin/pe/quantum/crm/config/QualityGatesConfigTest.kt`.

> **Contexto.** El gate real hoy es `minBound(63)` global y `minBound(58)` de dominio. `TESTING-backend.md §8` fija el objetivo en 75/90. La sesión anterior ya corrigió la *desinformación* (nada afirma ya 75/90 como si fuera el gate). Lo que queda es **subir el suelo** ahora que las olas 1 han añadido tests.

### G.1 — Medir la cobertura real
```bash
./gradlew koverVerify
./gradlew koverHtmlReport
```
Abre `build/reports/kover/html/index.html` y anota los dos porcentajes: global y variante `domain`.

### G.2 — Subir el trinquete al valor medido
En `build.gradle.kts`, sube `minBound(63)` y `minBound(58)` a los valores reales medidos, **restando 1 punto de margen** y redondeando hacia abajo.

> **Es un trinquete, no un objetivo.** Fija lo que la suite alcanza **hoy**, para que no pueda bajar. **No pongas 75/90 si la cobertura real no llega**: dejarías el build rojo y encadenarías corridas fallidas.
>
> El margen de 1 punto es deliberado: la medición local **no incluye los `@Tag("integration")`**, así que la cifra de CI será algo mayor. Sin margen, cualquier refactor menor pone el build en rojo por decimales.

### G.3 — Actualizar los comentarios
El bloque de comentarios sobre `kover { ... }` cita "63/58" en varios sitios. Actualiza **todas** las cifras a las nuevas. Que ningún comentario quede mintiendo: fue exactamente el hallazgo original.

### G.4 — Ajustar el test del trinquete
`QualityGatesConfigTest.kt` es un trinquete real que se pone rojo si el `minBound` baja. Actualiza sus cifras esperadas a las nuevas.

> **No lo conviertas en un `contains("75")` sobre el texto del build.** Esa era exactamente la trampa del hallazgo original: dos tests verdes que "probaban" un umbral que el build no exigía, porque el texto casaba con un comentario. Debe seguir leyendo el `minBound` efectivo.

### G.5 — Verificación final de todo el trabajo
```bash
./gradlew test ktlintCheck detekt koverVerify
```
Los cuatro en verde. Reporta los porcentajes antes/después y **cuánta deuda queda hasta 75/90**.

---

## 2. Informe final esperado

Cada agente entrega:

1. **Tareas completadas**, con el número de tests añadidos.
2. **Evidencia del rojo**: para cada fix, qué falló el test antes del cambio de producción. Sin esto no hubo TDD.
3. **Salida literal** de la última corrida de `./gradlew test ktlintCheck detekt`.
4. **Tests escritos pero NO ejecutados** (`@Tag("integration")`), nombrados uno a uno. Obligatorio para el agente E.
5. **Bugs encontrados y NO corregidos** por quedar fuera de alcance.
6. **Archivos tocados fuera de tu lista de propiedad**, si los hubo, y por qué.

---

## 3. Resumen de trazabilidad

| Hallazgo | Sev. | Agente | Tareas |
|---|---|---|---|
| B1 · RUC del mismo vendedor → 200 | Alto | A | A.1 – A.5 |
| D1 · `requiere_cambio_contrasena` sin salida | Alto | B | B.1 – B.4 |
| D2 · CRUD empleados sin test HTTP | Alto | B | B.5 |
| F1 · `id_modelo` nullable en la entidad | Alto | C | C.1 |
| A1 · invariantes del pipeline sin test | Alto | C | C.2 – C.4 |
| F2 · módulos sin ningún test | Alto | D, E | D.1 – D.3, E |
| E2 · SQL crudo sin cobertura | Alto 🟡 | E | E |
| §19 · enums de notificación | Medio | F | F.3 |
| F3 · trinquete de Kover | Alto 🟡 | G | G.1 – G.5 |

**Fuera de alcance de este plan** (siguen ⬜ en `code-review-pendientes.md`): todos los [Medio] no listados arriba, y **todos los 🗄️**, que son cambios de esquema gestionados a mano por el dueño en Supabase.
