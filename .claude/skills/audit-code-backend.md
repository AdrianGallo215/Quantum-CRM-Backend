# SKILL: audit-code-backend

## Propósito
Auditoría de código limpio, buenas prácticas y arquitectura para el backend Kotlin + Spring Boot de Quantum CRM. Ejecutar al cierre de cada hito indicado en el plan maestro.

## Cómo ejecutar
Recorrer cada ítem del checklist sobre el código del hito auditado. Por cada ítem que falla, reportar: archivo, línea (si aplica), descripción del problema, y corrección requerida. Al terminar, emitir el reporte de auditoría y la lista de correcciones pendientes antes de continuar.

## Cómo reportar
```
## AUDIT REPORT — Código Backend — [nombre del hito]

### PASA ✓
- [lista de categorías que pasan completamente]

### FALLA ✗
- [Archivo:Línea] Descripción del problema → Corrección requerida

### CORRECCIONES PENDIENTES ANTES DE CONTINUAR
1. ...
2. ...

### PARA REVISIÓN MANUAL DEL DESARROLLADOR
- [qué debe revisar el humano en código y qué puede ejecutar para verificar]
```

---

## Checklist

### 1. Arquitectura y dependencias entre capas
- [ ] Ningún `Controller` importa un `Repository` directamente
- [ ] Ningún `Repository` importa un `Service` ni un `Controller`
- [ ] Los `Controller` solo reciben `Request` DTO, delegan al `Service` y devuelven `ApiResponse<ResponseDTO>` — sin lógica
- [ ] Los `Service` programan contra la interfaz (`OportunidadService`), no contra la implementación (`OportunidadServiceImpl`)
- [ ] Ningún módulo de dominio importa entidades JPA de otro módulo — solo DTOs o interfaces
- [ ] No hay ciclos de dependencia entre módulos (`slices().matching("..domain.(*)..").should().beFreeOfCycles()` pasa)
- [ ] Los tests de ArchUnit corren y pasan

### 2. Kotlin idiomático
- [ ] Los DTOs son `data class` con `val` (nunca `var`)
- [ ] No se usa `!!` sin un comentario que justifique por qué es imposible que sea null
- [ ] Se usa null-safety idiomático: `?.`, `?:`, `let`, `requireNotNull` con mensaje — nunca `if (x != null)`  estilo Java
- [ ] Las constantes usan `UPPER_SNAKE_CASE` (no hay números ni strings mágicos hardcodeados)
- [ ] Las excepciones de negocio extienden `BusinessException` y están en `sealed class` o jerarquía clara
- [ ] No se usa `var` donde `val` es suficiente

### 3. Entidades JPA
- [ ] Toda relación `@ManyToOne` y `@OneToMany` tiene `fetch = FetchType.LAZY` explícito
- [ ] Ninguna entidad JPA se devuelve directamente desde un `Controller` — siempre se mapea a DTO
- [ ] Los enums se persisten con `@Enumerated(EnumType.STRING)`, nunca `ORDINAL`
- [ ] Las entidades no tienen lógica de negocio — esa vive en el `ServiceImpl`

### 4. Transacciones
- [ ] Todos los métodos de lectura en `ServiceImpl` tienen `@Transactional(readOnly = true)`
- [ ] Todos los métodos de escritura tienen `@Transactional` cubriendo la operación completa (incluyendo llamadas a otros servicios dentro de la misma transacción cuando aplica)
- [ ] No hay métodos `@Transactional` en `Controller`
- [ ] No hay operaciones de escritura en repositories llamadas fuera de un contexto `@Transactional`

### 5. Inyección de dependencias
- [ ] Toda inyección es por constructor con `private val` — cero `@Autowired` en campos
- [ ] No hay `@Autowired` en ningún archivo del proyecto

### 6. Manejo de errores
- [ ] Existe un único `@RestControllerAdvice` (`GlobalExceptionHandler`) que maneja todas las excepciones
- [ ] No hay `try-catch` en `Controller` ni en `Service` para formatear respuestas — solo el handler global
- [ ] Los errores devuelven siempre `ApiResponse.failure(error)` — nunca un objeto distinto
- [ ] Ningún stack trace ni mensaje interno llega al cliente — el handler loguea el detalle y devuelve un mensaje seguro con ID de correlación
- [ ] Los IDs de correlación se generan y se incluyen tanto en el log como en la respuesta al cliente

### 7. Respuesta estándar
- [ ] Todo endpoint devuelve `ResponseEntity<ApiResponse<T>>` — nunca un objeto crudo ni una lista sin envelope
- [ ] `ApiResponse.success()` para éxitos, `ApiResponse.failure()` para errores — nada más
- [ ] La paginación usa `PaginationMeta` incluida en el `meta` del envelope

### 8. Logging
- [ ] Se usa SLF4J con `LoggerFactory.getLogger(ClassName::class.java)` — nunca `println`
- [ ] Los mensajes de log usan placeholders `{}` — nunca concatenación de strings
- [ ] No se loguea ningún dato sensible (contraseñas, tokens, RUC en producción si es considerado PII)
- [ ] Los eventos de negocio importantes (cambios de estado, creación de oportunidades) tienen un `log.info`

### 9. Calidad de funciones y clases
- [ ] Ninguna función de `ServiceImpl` supera ~40 líneas — si las supera, extraer métodos privados
- [ ] Los nombres de funciones describen lo que hacen (verbos: `calcularMontoTotal`, `validarTransicion`, `actualizarEstadoCartera`)
- [ ] No hay código muerto (funciones, imports o clases sin uso)
- [ ] No hay bloques comentados de código — si se necesita conservar algo, usar git

### 10. Tests (TDD compliance)
- [ ] Toda lógica de negocio del hito tiene tests unitarios escritos
- [ ] Los tests unitarios fallan sin el código de producción (verificar que no hay tests que siempre pasan)
- [ ] Existe al menos un test de integración por endpoint nuevo del hito
- [ ] Los nombres de tests describen el comportamiento: `` `cerrar oportunidad sin motivo lanza excepcion` ``
- [ ] La estructura de cada test es Arrange-Act-Assert con separación visual clara
- [ ] `./gradlew test` pasa al 100%
- [ ] `./gradlew koverVerify` pasa (cobertura ≥ 90% en servicios de dominio, ≥ 75% global)

### 11. Formato y análisis estático
- [ ] `./gradlew ktlintCheck` pasa sin errores
- [ ] `./gradlew detekt` pasa sin issues configurados como errores
