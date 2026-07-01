# Quantum CRM Backend — Product Requirements Document

> **Documento vivo.** Fuente de verdad para el desarrollo del backend. Repo: `quantum-crm-backend`. Cualquier contradicción entre este documento y una conversación anterior: este documento gana.

---

## 1. Contexto y visión

**Producto:** API REST del CRM a medida para Quantum Investment, representante exclusivo de la marca de buses KinWin en Perú. Modelo de venta tripartita: Quantum + cliente + entidad financiadora (Calidda como default).

**Rol de este repo:** el backend es el dueño de la lógica de negocio, el modelo de datos y el contrato de API. Expone una API REST consumida por el frontend (repo separado `quantum-crm-frontend`).

**Decisión de negocio crítica:** el cierre de una venta ocurre únicamente cuando el depósito de Calidda se refleja en las cuentas de Quantum. No con el contrato firmado, no con la orden de compra. Esto se modela como la transición a estado `facturado`.

**Usuarios del sistema (roles):**
- **admin** — Configura usuarios, catálogos y financiadoras.
- **gerente** (Gustavo) — Visibilidad estratégica total. Lee reportes.
- **jdv** (Aldo) — Jefe de ventas. Gestiona equipo y pipeline completo.
- **vendedor** — Opera sus propios registros.
- **analista** — Visibilidad de vendedor + puede confirmar paso a Facturado.

---

## 2. Documentos de referencia (en este repo)

Claude Code debe leer estos documentos antes de escribir cualquier código. Están en `docs/`:

| Archivo | Contenido |
|---|---|
| `docs/schema.sql` | Schema completo de PostgreSQL con comentarios |
| `docs/reglas_negocio.md` | Lógica de negocio que el backend debe implementar exactamente |
| `docs/contrato_api.md` | Endpoints, bodies, responses y códigos de error (DUEÑO de este contrato) |
| `docs/matriz_permisos.md` | Qué puede ver y hacer cada rol |
| `docs/TESTING-backend.md` | Estrategia TDD obligatoria del backend |
| `docs/SECURITY-backend.md` | Requisitos de seguridad del backend |
| `docs/DEVOPS-backend.md` | Pipeline CI/CD y deploy del backend |
| `docs/migrations/` | 19 migraciones Flyway en orden |

**Este repo es el dueño del contrato de API y la matriz de permisos.** El frontend los consume como referencia externa. Si el contrato cambia, se actualiza aquí primero y se comunica al equipo de frontend.

**Regla:** si algo no está en estos documentos y no está en este PRD, preguntar antes de implementar. No inventar comportamiento.

---

## 3. Stack tecnológico

```
Lenguaje:       Kotlin 1.9.x
Framework:      Spring Boot 3.2.x
Persistencia:   Spring Data JPA + Hibernate
Migraciones:    Flyway (archivos .sql explícitos, sin Hibernate auto-DDL)
Seguridad:      Spring Security 6 + JWT (cookie httpOnly)
Async:          Spring @Async para tareas de background
Base de datos:  PostgreSQL 16
Build:          Gradle con Kotlin DSL
JDK:            21 (Temurin)
```

**Configuración obligatoria en application.properties:**
```properties
spring.jpa.hibernate.ddl-auto=validate
spring.flyway.enabled=true
spring.flyway.locations=classpath:db/migration
```

Hibernate valida el schema pero nunca lo modifica. Flyway es el único que toca la base de datos.

---

## 4. Comandos de desarrollo

```bash
./gradlew build              # Compilar
./gradlew test               # Ejecutar tests (con Testcontainers)
./gradlew bootRun            # Levantar en local (puerto 8080)
./gradlew flywayMigrate      # Correr migraciones manualmente
./gradlew ktlintCheck        # Verificar formato
./gradlew ktlintFormat       # Autoformatear
./gradlew detekt             # Análisis estático
./gradlew koverVerify        # Verificar umbral de cobertura
./gradlew dependencyCheckAnalyze  # Escaneo de vulnerabilidades

# PostgreSQL local
docker-compose up -d         # Levantar PostgreSQL
docker-compose down          # Bajar
docker-compose logs -f       # Logs

psql -h localhost -U quantum -d quantum_crm
```

---

## 5. Estructura del proyecto

```
quantum-crm-backend/
├── src/main/kotlin/pe/quantum/crm/
│   ├── config/          # Spring Security, CORS, async config
│   ├── domain/          # Una carpeta por módulo de negocio
│   │   ├── empresas/
│   │   ├── contactos/
│   │   ├── oportunidades/
│   │   ├── eventos/
│   │   ├── tareas/
│   │   ├── financiadoras/
│   │   ├── modelos/
│   │   ├── empleados/
│   │   └── reportes/
│   ├── shared/          # Excepciones, enums, respuestas genéricas, ApiResponse
│   └── CrmApplication.kt
├── src/main/resources/
│   ├── db/migration/    # Archivos Flyway V1__ a V19__
│   └── application.properties
├── src/test/kotlin/     # Tests (espejo de la estructura de main)
├── docs/                # schema, reglas, contrato_api, matriz_permisos, migrations
├── docker-compose.yml
├── Dockerfile
├── .env.example
└── build.gradle.kts
```

Cada módulo de dominio sigue la estructura de capas descrita en la sección 6.

---

## 6. Convenciones de código

### 6.1 Arquitectura en capas por módulo

La dependencia fluye en una sola dirección: `Controller → Service → Repository`. Nunca al revés. El Controller no conoce entidades JPA; el Repository no conoce DTOs.

```
domain/empresas/
├── EmpresaController.kt      # HTTP. Recibe Request, devuelve Response. Sin lógica.
├── EmpresaService.kt         # Interfaz del servicio (contrato del módulo)
├── EmpresaServiceImpl.kt     # Lógica de negocio. @Transactional aquí.
├── EmpresaRepository.kt      # Spring Data JPA. Solo acceso a datos.
├── Empresa.kt                # Entity JPA
├── EmpresaMapper.kt          # Entity ↔ DTO
└── dto/
    ├── CrearEmpresaRequest.kt
    ├── ActualizarEmpresaRequest.kt
    └── EmpresaResponse.kt
```

### 6.2 Inmutabilidad y null-safety

```kotlin
// DTOs SIEMPRE inmutables — val, nunca var. data class.
data class CrearEmpresaRequest(
    @field:NotBlank
    @field:Pattern(regexp = "\\d{11}", message = "El RUC debe tener 11 dígitos")
    val ruc: String,

    @field:NotBlank
    val razonSocial: String,

    @field:NotNull
    val segmentos: List<SegmentoEnum>,

    val idVendedor: Long?,
)

// Aprovechar el sistema de tipos de Kotlin. Nunca tipos plataforma de Java sin anotar.
// Nunca '!!' salvo que sea imposible que sea null y esté comentado el porqué.
// Null-safety idiomático: ?., ?:, let, requireNotNull con mensaje.

val vendedor = empresa.idVendedor ?: throw EmpresaSinVendedorException(empresa.id)
```

### 6.3 Entidades JPA

```kotlin
@Entity
@Table(name = "empresas")
class Empresa(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @Column(nullable = false, unique = true, length = 11)
    var ruc: String,

    @Enumerated(EnumType.STRING)
    @Column(name = "estado_cartera", nullable = false)
    var estadoCartera: EstadoCarteraEnum = EstadoCarteraEnum.NO_CONTACTADO,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "id_vendedor")
    var vendedor: Empleado? = null,
)
```

- **Todas las relaciones `@ManyToOne` y `@OneToMany` son `LAZY`.** EAGER causa el problema N+1. Para cargar relaciones, usar `JOIN FETCH` explícito o `@EntityGraph`.
- Nunca exponer entidades JPA en los controllers. Siempre mapear a DTO.
- Enums con `@Enumerated(EnumType.STRING)`, nunca `ORDINAL`.

### 6.4 Servicios y transacciones

```kotlin
@Service
class OportunidadServiceImpl(
    private val oportunidadRepository: OportunidadRepository,
    private val estadoCarteraService: EstadoCarteraService,
    private val estadoLogRepository: OportunidadEstadoLogRepository,
) : OportunidadService {

    @Transactional(readOnly = true)
    override fun obtenerPorId(id: Long): OportunidadResponse { ... }

    @Transactional
    override fun cambiarEstado(
        id: Long,
        request: CambiarEstadoRequest,
        empleado: EmpleadoAutenticado,
    ): OportunidadResponse {
        val oportunidad = oportunidadRepository.findByIdOrThrow(id)
        validarTransicion(oportunidad, request.estado, empleado)
        oportunidad.estado = request.estado
        estadoLogRepository.save(crearLog(oportunidad, request))
        estadoCarteraService.actualizar(oportunidad.idEmpresa)  // misma transacción
        return oportunidad.toResponse()
    }
}
```

- **Inyección por constructor, nunca `@Autowired` en campos.** Permite `private val` y testeo sin Spring.
- `@Transactional(readOnly = true)` en toda lectura. `@Transactional` en escrituras, cubriendo toda la operación de negocio.
- La capa de servicio se programa contra interfaz, con la implementación inyectada.
- Nunca llamar a un repository desde un controller. Nunca lógica de negocio en un controller.

### 6.5 Controllers

```kotlin
@RestController
@RequestMapping("/api/v1/oportunidades")
class OportunidadController(
    private val oportunidadService: OportunidadService,
) {
    @PatchMapping("/{id}/estado")
    @PreAuthorize("@authz.puedeEditarOportunidad(#id, authentication)")
    fun cambiarEstado(
        @PathVariable id: Long,
        @Valid @RequestBody request: CambiarEstadoRequest,
        @AuthenticationPrincipal empleado: EmpleadoAutenticado,
    ): ResponseEntity<ApiResponse<OportunidadResponse>> {
        val resultado = oportunidadService.cambiarEstado(id, request, empleado)
        return ResponseEntity.ok(ApiResponse.success(resultado))
    }
}
```

- El controller solo recibe, valida (`@Valid`), delega y envuelve la respuesta. Cero lógica.
- Autorización declarativa con `@PreAuthorize` apuntando a un bean centralizado (ver `matriz_permisos.md` §3).

### 6.6 Respuesta estándar y errores

```kotlin
data class ApiResponse<T>(
    val data: T?,
    val meta: PaginationMeta? = null,
    val error: ApiError? = null,
) {
    companion object {
        fun <T> success(data: T, meta: PaginationMeta? = null) = ApiResponse(data, meta, null)
        fun <T> failure(error: ApiError) = ApiResponse<T>(null, null, error)
    }
}

sealed class BusinessException(
    val codigo: String,
    val httpStatus: HttpStatus,
    override val message: String,
) : RuntimeException(message)

class RucDuplicadoException :
    BusinessException("RUC_DUPLICADO", HttpStatus.CONFLICT, "Esta empresa ya está registrada en el sistema")

class MotivoCierreRequeridoException :
    BusinessException("MOTIVO_CIERRE_REQUERIDO", HttpStatus.BAD_REQUEST, "El motivo de cierre es obligatorio")
```

- **Manejo de excepciones centralizado con `@RestControllerAdvice`.** Un solo `GlobalExceptionHandler` traduce cada `BusinessException` a su `ApiResponse.failure`. Nunca try-catch repartidos para formatear respuestas.
- Excepciones de negocio `sealed` para exhaustividad.
- Nunca devolver stack traces ni mensajes internos al cliente. El handler loguea el detalle y devuelve un mensaje seguro con un ID de correlación.

### 6.7 Logging

```kotlin
companion object {
    private val log = LoggerFactory.getLogger(OportunidadServiceImpl::class.java)
}

log.info("Cambio de estado de oportunidad {} a {}", id, request.estado)
```

- SLF4J con logback. Nunca `println`.
- Placeholders `{}`, nunca concatenación.
- Nunca loguear datos sensibles (contraseñas, tokens, PII).
- Niveles: ERROR (acción requerida), WARN (anomalía recuperable), INFO (evento de negocio), DEBUG (desarrollo).

### 6.8 Reglas generales

- Clases: `PascalCase`. Funciones y variables: `camelCase`. Constantes: `UPPER_SNAKE_CASE`.
- Tablas y columnas SQL: `snake_case`. Endpoints: `kebab-case`.
- Formateo con **ktlint** (el build falla si no se respeta). Análisis estático con **detekt**.
- Funciones cortas, una responsabilidad. Si un método de servicio supera ~40 líneas, extraer.
- Sin números ni strings mágicos. Constantes con nombre (ej: `VENTANA_PRONTA_FACTURACION_DIAS = 30`).
- Comentarios solo para el *por qué*, no el *qué*.

---

## 7. Arquitectura: monolito modular desacoplado

La aplicación es un **monolito modular**: un solo deployable, con módulos de dominio internamente desacoplados.

**Reglas de desacoplamiento entre módulos:**

1. **Cada módulo expone una interfaz de servicio pública.** Otros módulos dependen de esa interfaz, nunca de la implementación ni del repository ajeno. Si `oportunidades` necesita actualizar el estado de cartera, llama a `EstadoCarteraService` (interfaz pública de `empresas`), no al `EmpresaRepository`.

2. **Un módulo nunca accede directamente a las tablas de otro módulo.** El acceso cruzado pasa siempre por la capa de servicio del módulo dueño.

3. **Las entidades JPA no se comparten entre módulos.** Si `oportunidades` necesita datos de una empresa, recibe un DTO, no la entidad `Empresa`.

4. **Comunicación entre módulos vía interfaces inyectadas o eventos de dominio.** Para operaciones síncronas, inyección de interfaz. Para efectos secundarios desacoplados, `ApplicationEventPublisher`.

5. **Dependencias acíclicas.** Si `A` depende de `B`, `B` no puede depender de `A`. Para comunicación bidireccional, usar eventos.

**Validación automatizada con ArchUnit** en los tests:

```kotlin
@Test
fun `los controllers no acceden directamente a repositories`() {
    noClasses().that().haveSimpleNameEndingWith("Controller")
        .should().dependOnClassesThat().haveSimpleNameEndingWith("Repository")
        .check(clasesDelProyecto)
}

@Test
fun `los modulos no dependen de entidades de otros modulos`() {
    slices().matching("..domain.(*)..")
        .should().notDependOnEachOther()
        .check(clasesDelProyecto)
}
```

---

## 8. Sincronización 360 — fuente única de verdad

**Principio inviolable:** un dato vive en exactamente un lugar. Si se modifica, el cambio es visible en toda la aplicación. No existe ninguna ruta de código por la que dos consultas devuelvan valores distintos del mismo dato.

**Nivel base de datos:**
- Ningún dato derivado se almacena duplicado. `monto_total` se calcula, los términos de financiadora se leen por JOIN, `estado_cartera` se deriva del conjunto de oportunidades. Ver `reglas_negocio.md` §1.1.
- Las operaciones que afectan múltiples tablas son atómicas (`@Transactional`).

**Nivel backend:**
- Un solo servicio es responsable de cada dato derivado. `estado_cartera` solo se modifica vía `EstadoCarteraService.actualizar()`. Ningún otro código toca ese campo.
- Los cálculos derivados (monto, pronta facturación) se computan en un solo lugar reutilizable, nunca reimplementados en distintos endpoints.

El frontend tiene su propia responsabilidad de sincronización (cache invalidation), documentada en su propio PRD. El backend garantiza que la fuente de verdad sea consistente.

---

## 9. Reglas de implementación no negociables

Vienen de `reglas_negocio.md`. Se repiten por su criticidad:

1. **`monto_total` nunca se acepta como input.** Se calcula: `cantidad × precio_unitario × (1 − dcto/100)`. Si viene en el body, ignorarlo.
2. **`estado_cartera` nunca se actualiza directamente.** Solo vía `actualizarEstadoCartera()` en la misma transacción que el evento que lo dispara.
3. **Los eventos no ejecutan cambios de estado automáticamente.** Solo devuelven una sugerencia. El cambio requiere una segunda llamada HTTP confirmada por el usuario.
4. **El primer log en `oportunidad_estados_log` tiene `estado_anterior = NULL`.**
5. **`motivo_cierre` es obligatorio cuando `estado = 'cerrado'`.** Verificar en backend además del CHECK constraint.
6. **El paso a `facturado` solo para admin, gerente y analista.** Verificar rol en el servicio.
7. **No existe `perdido`.** El enum tiene cuatro valores. Si se necesita un quinto, preguntar.
8. **Un solo registro con `es_default = true` en financiadoras.** Validar en backend antes del INSERT.

---

## 10. Límites del MVP — qué NO implementar

| No implementar | Razón |
|---|---|
| Módulo financiero (cuota Quantum, balloon, TEA pactada) | Post-MVP. Tabla existe sin endpoints. |
| Endpoints de `buses_entregados` | Post-MVP. Tabla existe sin uso. |
| Import masivo de Excel/CSV | Post-MVP. Creación manual. |
| Cálculo de comisiones | Post-MVP. |
| Pronta facturación expuesta en API | Se calcula y almacena, pero no se devuelve en respuestas del MVP. |
| "Olvidé mi contraseña" por email | El admin resetea manualmente. |
| Panel de permisos granulares | El analista tiene visibilidad de vendedor en MVP. |
| Notificaciones push o email | Sin integración de email en MVP. |

Si parece necesario agregar algo no listado, **pausar y preguntar**.

---

## 11. Plan de implementación por fases

Construir en este orden. No avanzar sin validar la fase anterior. Cada tarea es TDD: test primero (ver `TESTING-backend.md`).

### Fase 0 — Infraestructura base
- [ ] Proyecto Spring Boot con Kotlin, Gradle, Flyway, Spring Security
- [ ] Ejecutar las 19 migraciones y verificar el schema
- [ ] `docker-compose.yml` con PostgreSQL local
- [ ] `POST /auth/login` y `POST /auth/refresh` con JWT en cookie httpOnly
- [ ] `GET /empleados/me`
- [ ] CORS configurado para el origen del frontend
- [ ] Pipeline CI con todos los gates (ver `DEVOPS-backend.md`)

### Fase 1 — Módulos simples
- [ ] CRUD de Empleados
- [ ] CRUD de Modelos + aplicaciones
- [ ] CRUD de Financiadoras
- [ ] CRUD de Catálogo de eventos
- [ ] Endpoints de auth: cambiar contraseña, reset por admin

### Fase 2 — Empresas y contactos
- [ ] CRUD de Empresas con segmentos
- [ ] Check de RUC duplicado
- [ ] CRUD de Contactos
- [ ] Vinculación empresa ↔ contacto
- [ ] PATCH de estado_cartera manual
- [ ] PATCH de reasignación de vendedor

### Fase 3 — Pipeline y oportunidades (la más crítica)
- [ ] POST de oportunidades con toda la lógica (snapshot vendedor, financiadora default, cálculo de monto, primer log, actualizarEstadoCartera)
- [ ] PUT de campos negociables con recálculo de monto
- [ ] PATCH de estado con validaciones (motivo_cierre, permiso facturado, retroceso, advertencias)
- [ ] Log de estados
- [ ] Traspaso de oportunidad
- [ ] Gestión de contactos en oportunidad

### Fase 4 — Eventos y tareas
- [ ] CRUD de eventos (catálogo/personalizado)
- [ ] PATCH de evento ocurrido con lógica de sugerencia (no ejecución)
- [ ] CRUD de tareas (prospección/oportunidad)
- [ ] PATCH de tarea completada/cancelada

### Fase 5 — Prospección e Inicio
- [ ] `GET /prospeccion` con cálculo de hitos y ordenamiento
- [ ] `GET /inicio` agregado

### Fase 6 — Reportes
- [ ] Los seis endpoints de reportes

---

## 12. Variables de entorno

Ver `.env.example`. Mínimas requeridas:

```
DB_URL=jdbc:postgresql://localhost:5432/quantum_crm
DB_USERNAME=quantum
DB_PASSWORD=...
JWT_SECRET=...                      # openssl rand -hex 32
JWT_EXPIRATION_MS=3600000
JWT_REFRESH_EXPIRATION_MS=604800000
CORS_ALLOWED_ORIGINS=http://localhost:5173
SPRING_PROFILES_ACTIVE=local
```

Nunca hardcodear credenciales. Nunca commitear `.env` (solo `.env.example`).

---

## 13. Testing

Desarrollo estrictamente **TDD**. Estrategia completa en `docs/TESTING-backend.md`. Regla resumida: ninguna tarea se considera completa sin sus tests escritos primero y pasando.

```bash
./gradlew test    # debe pasar antes de cada commit
```

---

## 14. Decisiones pendientes para Claude Code

Antes de implementar, preguntar al equipo:

1. **Contraseñas en `empleados`:** ¿campo `password_hash` en la tabla `empleados`, o tabla separada `credenciales`? Afecta la migración V20.
2. **Reseteo de contraseña por admin:** ¿contraseña temporal aleatoria mostrada una vez, o el admin ingresa la nueva directamente?
3. **Idioma de mensajes de error del sistema:** ¿todo en español, o los mensajes técnicos pueden estar en inglés?
