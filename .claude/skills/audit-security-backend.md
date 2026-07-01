# SKILL: audit-security-backend

## Propósito
Auditoría de seguridad del backend Kotlin + Spring Boot de Quantum CRM. Ejecutar al cierre de cada hito indicado en el plan maestro. Cubre OWASP Top 10 aplicado al stack específico.

## Cómo ejecutar
Recorrer cada ítem del checklist sobre el código del hito auditado. Por cada ítem que falla, reportar: archivo, línea (si aplica), descripción de la vulnerabilidad, nivel de severidad (CRÍTICO / ALTO / MEDIO) y corrección requerida. Al terminar, emitir el reporte. Los ítems CRÍTICO y ALTO bloquean continuar — deben corregirse antes de avanzar.

## Cómo reportar
```
## SECURITY AUDIT REPORT — Backend — [nombre del hito]

### SIN HALLAZGOS ✓
- [categorías sin vulnerabilidades]

### HALLAZGOS
- [CRÍTICO/ALTO/MEDIO] [Archivo:Línea] Descripción → Corrección requerida

### BLOQUEANTES (CRÍTICO / ALTO) — corregir antes de continuar
1. ...

### PARA REVISIÓN MANUAL DEL DESARROLLADOR
- [qué verificar manualmente, qué endpoints probar, qué configuración revisar]
```

---

## Checklist

### 1. Autenticación y JWT
- [ ] El JWT se setea como cookie con `HttpOnly; Secure; SameSite=Strict` — nunca en el body de respuesta para almacenar en localStorage
- [ ] El secreto JWT viene de la variable de entorno `JWT_SECRET` — nunca hardcodeado en código ni en `application.properties` commiteado
- [ ] El token tiene `exp` y se valida en cada request — tokens expirados generan `401`
- [ ] El refresh token también es cookie httpOnly con expiración mayor
- [ ] Los claims del JWT no incluyen datos sensibles (contraseñas, información personal innecesaria)
- [ ] Los endpoints `/auth/login` y `/auth/refresh` son los únicos accesibles sin autenticación — todo lo demás requiere token válido

### 2. Contraseñas
- [ ] Las contraseñas se hashean con BCrypt (`BCryptPasswordEncoder`, cost factor ≥ 12) — nunca MD5, SHA1, SHA256, ni texto plano
- [ ] El endpoint de login devuelve el mismo mensaje genérico ante usuario inexistente y contraseña incorrecta: `"Email o contraseña incorrectos"`
- [ ] No se loguea ninguna contraseña en ningún nivel (DEBUG, INFO, ERROR)
- [ ] El reseteo genera una contraseña temporal aleatoria con `SecureRandom`, nunca predecible
- [ ] `requiere_cambio_contrasena = true` se setea al crear un empleado y al resetear su contraseña

### 3. Autorización y control de acceso (OWASP A01)
- [ ] Cada endpoint nuevo tiene `@PreAuthorize` con la restricción correcta según `matriz_permisos.md`
- [ ] La autorización se aplica en el `Service`, no solo en el `Controller`
- [ ] El filtro de visibilidad por rol se aplica en la **query** (cláusula WHERE), no filtrando en memoria después de traer todos los registros
- [ ] Ante un recurso que existe pero no pertenece al usuario: se devuelve `404`, no `403` (previene enumeración de recursos — IDOR)
- [ ] No hay endpoint que devuelva datos de todos los usuarios sin restricción de rol
- [ ] El paso a `facturado` solo está permitido para `admin`, `gerente`, `analista` — verificado en el `Service`

### 4. Validación de inputs (OWASP A03)
- [ ] Todo `@RequestBody` tiene `@Valid` en el parámetro del controller
- [ ] Todos los campos de los DTOs tienen anotaciones de Bean Validation apropiadas (`@NotNull`, `@NotBlank`, `@Pattern`, `@Size`, `@Min`, `@Max`)
- [ ] El formato del RUC está validado: `@Pattern(regexp = "\\d{11}")`
- [ ] Los montos no aceptan valores negativos
- [ ] Los campos enum solo aceptan valores del catálogo — un valor inválido genera `400`
- [ ] Los campos de texto libre (notas, descripciones) tienen longitud máxima definida
- [ ] No hay ningún campo del body que se use directamente en una query sin pasar por validación

### 5. Inyección SQL (OWASP A03)
- [ ] No existe ninguna query construida por concatenación o interpolación de strings con datos del usuario
- [ ] Todas las queries con datos dinámicos usan parámetros nombrados (`@Param`) o los mecanismos de Spring Data JPA (métodos de repositorio, `Specification`)
- [ ] Si hay `@Query` nativas (SQL), usan `:paramName` — nunca interpolación
- [ ] No hay `JdbcTemplate` con strings construidos dinámicamente sin parametrización

### 6. CORS
- [ ] `Access-Control-Allow-Origin` NO es `"*"` en ninguna configuración
- [ ] El origen permitido viene de `CORS_ALLOWED_ORIGINS` (variable de entorno) — nunca hardcodeado
- [ ] `allowCredentials = true` está configurado (necesario para cookies)
- [ ] Los métodos HTTP permitidos son solo los necesarios (no incluir `*`)

### 7. Gestión de secretos (OWASP A02)
- [ ] No hay contraseñas, claves JWT, credenciales de DB ni API keys en ningún archivo del repo (`.properties`, `.yml`, `.kt`, `.gradle`)
- [ ] El `.gitignore` excluye `.env` y cualquier archivo de configuración local con secretos
- [ ] El `.env.example` contiene solo placeholders, nunca valores reales
- [ ] No hay secretos hardcodeados en los tests

### 8. Cabeceras de seguridad HTTP
- [ ] Spring Security está configurado para agregar: `X-Content-Type-Options: nosniff`, `X-Frame-Options: DENY`, `Strict-Transport-Security` (en producción), `Referrer-Policy`
- [ ] `Content-Security-Policy` está configurada (al menos con `default-src 'self'`)

### 9. Rate limiting
- [ ] El endpoint `/auth/login` tiene rate limiting (máx. intentos por IP/email en ventana de tiempo)
- [ ] La respuesta ante rate limit excedido es `429 Too Many Requests` con `Retry-After`

### 10. Logging seguro (OWASP A09)
- [ ] Los logs registran eventos de seguridad: logins fallidos, accesos denegados (403/404 por IDOR), reseteos de contraseña
- [ ] Los logs NO contienen: contraseñas, tokens JWT, datos personales sensibles
- [ ] Los errores internos loguean el detalle completo (con stack trace) pero devuelven al cliente solo un mensaje genérico con ID de correlación
- [ ] No hay `e.printStackTrace()` — solo `log.error("mensaje", e)`

### 11. Configuración segura (OWASP A05)
- [ ] `spring.jpa.hibernate.ddl-auto=validate` — nunca `create`, `create-drop`, `update`
- [ ] Los actuator endpoints expuestos están restringidos (solo `/actuator/health` público, el resto protegido o deshabilitado)
- [ ] No hay endpoints de debug o admin accidentalmente expuestos sin autenticación

### 12. Dependencias (OWASP A06)
- [ ] `./gradlew dependencyCheckAnalyze` pasa sin CVEs de severidad ALTA o CRÍTICA sin mitigar
