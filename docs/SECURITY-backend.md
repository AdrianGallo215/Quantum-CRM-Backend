# Quantum CRM Backend — Especificación de Seguridad

> API empresarial en producción. La seguridad se construye desde la Fase 0, no se agrega al final. El backend es la frontera de seguridad real del sistema — el frontend nunca es de confianza.

---

## 1. Principios

1. **Defensa en profundidad.** El backend valida todo aunque el frontend ya lo haya hecho.
2. **Menor privilegio.** Cada rol tiene el mínimo acceso. Ver `matriz_permisos.md`.
3. **Nunca confiar en el cliente.** Toda validación de seguridad ocurre aquí.
4. **Fallar de forma segura.** Ante error o duda, denegar.
5. **No exponer información.** Los errores no revelan detalles internos ni si un recurso existe.

---

## 2. Autenticación

### 2.1 JWT en cookie httpOnly

El backend emite el token y lo setea en una cookie con estos flags:

```
Set-Cookie: access_token=...; HttpOnly; Secure; SameSite=Strict; Path=/; Max-Age=3600
```

- **HttpOnly:** inaccesible desde JavaScript. Mitiga robo de token por XSS.
- **Secure:** solo sobre HTTPS. Obligatorio en producción.
- **SameSite=Strict:** no se envía en requests cross-site. Mitiga CSRF.
- Access token: 1 hora. Refresh token (también cookie httpOnly): 7 días.

### 2.2 Estructura del token

```
Claims del access token:
  sub: id del empleado
  rol: rol del empleado
  iat, exp

NUNCA incluir: contraseña, datos sensibles, PII innecesaria.
```

- Firmar con **HS256** usando secreto de mínimo 256 bits (`openssl rand -hex 32`). El secreto viene de `JWT_SECRET`, nunca hardcodeado.
- Validar `exp` en cada request. Token expirado → 401.

### 2.3 Contraseñas

- Hash con **BCrypt** (cost factor mínimo 12), vía `BCryptPasswordEncoder`.
- Nunca texto plano ni hashes débiles (MD5, SHA1).
- Política mínima: 10 caracteres, una mayúscula, una minúscula, un número. Validada en backend.
- Reseteo por admin: generar contraseña temporal aleatoria criptográficamente segura, forzar cambio en el primer login (`requiere_cambio_contrasena = true`).
- Nunca loguear contraseñas.

### 2.4 Mensajes de login

Ante credenciales inválidas, mensaje genérico sin indicar qué falló: `"Email o contraseña incorrectos"`. Revelar cuál falló permite enumeración de usuarios.

Aplicar el mismo tiempo de respuesta ante usuario inexistente y contraseña incorrecta (evitar timing attacks).

### 2.5 Logout y revocación de sesiones

El sistema es JWT stateless: no hay sesión en servidor, así que "revocar" significa invalidar el refresh token, no borrar un registro.

- `empleados.token_version` (V39) es un contador por empleado. Cada refresh token lleva la versión vigente al emitirse (claim `tv`). `POST /auth/refresh` compara la versión del token contra la de la base; si no coincide, `401 CREDENCIALES_INVALIDAS`, igual que un empleado inactivo.
- Se incrementa en `POST /auth/logout` (revoca esa sesión) y en `POST /auth/cambiar-contrasena` (revoca cualquier otra sesión abierta con la cuenta; la sesión que hizo el cambio recibe cookies nuevas con la versión ya vigente, así que no se corta a sí misma).
- **Alcance de la revocación:** solo se revisa en `/auth/refresh`, no en cada request autenticado — mismo compromiso que la desactivación de cuenta (`activo`, ver `EmpleadoServiceImpl`). Evita una lectura a base de datos en cada endpoint a cambio de que la revocación tarde como máximo lo que dure el access token vigente (1h por defecto): tras un logout, un access token ya emitido sigue siendo válido hasta que expira; lo que deja de funcionar de inmediato es el refresh, así que la sesión no puede renovarse más allá de esa hora.
- `POST /auth/logout` es público (no exige sesión) e idempotente: limpia las cookies con los mismos flags de §2.1 y responde `204` siempre, exista o no una sesión válida. Nunca debe poder fallar — un cierre de sesión que devuelve error dejaría al usuario sin saber si sigue autenticado.

---

## 3. Autorización

### 3.1 En cada endpoint

- Toda ruta excepto `/auth/login` y `/auth/refresh` requiere autenticación.
- Autorización con `@PreAuthorize` a nivel de método de **servicio**, no solo de controller. Ver `matriz_permisos.md` §3.
- El filtro por rol se aplica en las **queries** (no en memoria). Un vendedor nunca recibe datos que no le corresponden, ni siquiera para filtrarlos después.

### 3.2 IDOR (Insecure Direct Object Reference)

El ataque más común en CRUDs: cambiar un ID en la URL para acceder a datos ajenos (`GET /oportunidades/999` de otro vendedor).

**Mitigación obligatoria:** cada acceso por ID verifica la pertenencia antes de devolver datos. No basta con autenticar — hay que autorizar el recurso específico.

```kotlin
@GetMapping("/{id}")
@PreAuthorize("@authz.puedeVerOportunidad(#id, authentication)")
fun obtener(@PathVariable id: Long): ...
```

**Decisión:** ante un recurso que existe pero no pertenece al usuario, devolver **404, no 403**. Un 403 confirma que el recurso existe, lo que filtra información. El 404 no distingue entre "no existe" y "no es tuyo".

---

## 4. Validación de inputs (OWASP A03: Injection)

### 4.1 Inyección SQL

- **Solo queries parametrizadas.** Spring Data JPA y `@Query` con parámetros nombrados lo hacen por defecto. Nunca SQL por concatenación.

```kotlin
// CORRECTO
@Query("SELECT e FROM Empresa e WHERE e.ruc = :ruc")
fun findByRuc(@Param("ruc") ruc: String): Empresa?

// PROHIBIDO — concatenación de variables del usuario
```

### 4.2 Bean Validation

- Todo `@RequestBody` se valida con `@Valid` y anotaciones (`@NotNull`, `@Pattern`, `@Size`, `@Email`).
- Validar formato, longitud y rango. RUC matchea `\d{11}`. Montos no negativos. Enums solo valores del catálogo.
- La validación del backend es la autoritativa.

### 4.3 Sanitización de texto libre

- Campos de texto libre (notas, descripciones) se validan/sanitizan antes de almacenar. Rechazar payloads con tags HTML o scripts si el campo no los espera. Esto protege a los clientes que rendericen ese contenido.

---

## 5. CSRF

- Con `SameSite=Strict` el riesgo de CSRF se reduce drásticamente.
- Para operaciones de escritura, implementar el token CSRF de Spring Security o el patrón double-submit cookie si se requiere defensa adicional.
- Los GET no mutan estado y no requieren protección CSRF.

---

## 6. Cabeceras de seguridad HTTP

Configurar en Spring Security para que toda respuesta incluya:

```
Content-Security-Policy: default-src 'self'; ...
X-Content-Type-Options: nosniff
X-Frame-Options: DENY
Strict-Transport-Security: max-age=31536000
Referrer-Policy: strict-origin-when-cross-origin
Permissions-Policy: geolocation=(), microphone=(), camera=()
```

`X-Frame-Options: DENY` previene clickjacking. `HSTS` fuerza HTTPS.

---

## 7. CORS

- Restrictivo. Solo el origen del frontend, definido en `CORS_ALLOWED_ORIGINS`.
- Nunca `Access-Control-Allow-Origin: *` en producción.
- `allowCredentials = true` (se usan cookies), pero solo para el origen específico permitido.

```kotlin
// CORRECTO
configuration.allowedOrigins = corsAllowedOrigins.split(",")
configuration.allowCredentials = true

// PROHIBIDO en producción: allowedOrigins = listOf("*")
```

---

## 8. Rate limiting

- **Login:** máximo 5 intentos fallidos por IP/email en 15 minutos, luego bloqueo temporal con backoff.
- **API general:** límite por usuario autenticado por minuto (ej: 100/min).
- Implementar con **Bucket4j** o filtro de Spring, store en memoria (MVP) o Redis (escala).
- Responder `429 Too Many Requests` con `Retry-After`.

---

## 9. Gestión de secretos

- **Ningún secreto en código ni repositorio.** Ni claves JWT, ni contraseñas de DB.
- Todos los secretos vienen de variables de entorno. `.env` en `.gitignore`. Solo se commitea `.env.example` con placeholders.
- En producción (Render/Railway), secretos en el panel de variables de entorno.
- Rotar `JWT_SECRET` y credenciales de DB si se sospecha exposición.

---

## 10. Protección de datos

### 10.1 Datos sensibles

- RUC, datos de contacto y `aval_fiador` son información comercial sensible. No exponer en logs de producción ni en mensajes de error.
- Las respuestas solo incluyen los campos necesarios. No devolver objetos completos con datos que la vista no usa.

### 10.2 En tránsito y en reposo

- **HTTPS obligatorio en producción.** TLS provisto por Render/Railway.
- Conexión a la base de datos cifrada (SSL).
- Backups cifrados.

---

## 11. Auditoría y logging seguro

- Toda tabla tiene `created_by`, `created_at`, `updated_by`, `updated_at`.
- Los cambios de estado quedan en `oportunidad_estados_log` con autor y timestamp.
- Loguear eventos de seguridad: logins fallidos, accesos denegados (403), reseteos de contraseña.
- **Nunca loguear:** contraseñas, tokens completos, datos sensibles. Usar el ID interno, no el RUC.
- Un error interno devuelve un mensaje genérico al cliente con un ID de correlación; el detalle queda solo en el log del servidor.

---

## 12. Dependencias

- Escanear con **OWASP Dependency-Check** en el CI (`./gradlew dependencyCheckAnalyze`).
- El build falla ante CVE crítico/alto sin mitigar.
- Mantener dependencias actualizadas. No introducir dependencias sin evaluar mantenimiento y reputación.

---

## 13. Checklist OWASP Top 10 (2021)

| # | Categoría | Mitigación |
|---|---|---|
| A01 | Broken Access Control | `@PreAuthorize` en servicio, verificación de pertenencia (IDOR → 404), filtro por rol en queries |
| A02 | Cryptographic Failures | BCrypt, TLS, secretos en env, JWT firmado |
| A03 | Injection | Queries parametrizadas, Bean Validation |
| A04 | Insecure Design | Permisos explícitos, reglas documentadas, fail-safe defaults |
| A05 | Security Misconfiguration | Cabeceras de seguridad, CORS restrictivo, ddl-auto=validate |
| A06 | Vulnerable Components | OWASP Dependency-Check en CI |
| A07 | Auth Failures | Rate limiting en login, mensajes genéricos, BCrypt, política de contraseñas |
| A08 | Data Integrity Failures | Validación de inputs, transacciones atómicas, sin deserialización insegura |
| A09 | Logging Failures | Eventos de seguridad logueados, sin datos sensibles, IDs de correlación |
| A10 | SSRF | No se hacen requests a URLs del usuario en MVP. Si se agregan, allowlist |

---

## 14. Requisitos por fase

| Fase | Seguridad a implementar |
|---|---|
| Fase 0 | JWT en cookie httpOnly, BCrypt, CORS restrictivo, cabeceras de seguridad, secretos en env, login genérico, rate limiting |
| Fase 1+ | `@PreAuthorize` en cada endpoint según matriz, validación de pertenencia (IDOR), Bean Validation en cada request |
| Todas | Queries parametrizadas, sin secretos en código, logging seguro |
| CI desde Fase 0 | OWASP Dependency-Check |

La seguridad se implementa **desde la Fase 0**. Cada endpoint nace con su autorización; cada input nace validado.
