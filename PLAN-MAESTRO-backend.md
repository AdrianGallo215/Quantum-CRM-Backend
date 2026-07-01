# Quantum CRM Backend — Plan Maestro

> Plan de ejecución para Claude Code. Repo: `quantum-crm-backend`. Cada tarea es atómica: una sesión, termina con tests pasando y CI verde. **Toda tarea es TDD** — test que falla primero, luego el código (ver `docs/TESTING-backend.md`).

---

## Cómo usar este plan

- Ejecutar las tareas **en orden**. No avanzar a una tarea sin completar sus dependencias.
- Cada tarea indica su **esfuerzo** sugerido para Opus 4.8 en Claude Code: `low` · `medium` · `high` · `extra-high` · `max`.
- Al cerrar cada **hito** (marcado con 🔍 AUDIT) Claude Code:
  1. Ejecuta las skills de auditoría indicadas y emite el reporte.
  2. Corrige los hallazgos bloqueantes (CRÍTICO/ALTO).
  3. Entrega al desarrollador la lista de **revisión manual**: qué mirar en código y qué probar ejecutando.
  4. **Se detiene y espera** la validación del desarrollador antes de continuar al siguiente hito.
- Las skills de auditoría viven en `docs/skills/`. El plan solo dice qué auditar, no cómo.

---

## Niveles de esfuerzo — referencia

| Nivel | Cuándo |
|---|---|
| `low` | Configuración mecánica, boilerplate, CRUD trivial siguiendo un patrón ya establecido |
| `medium` | CRUD con validaciones, lógica simple, primer uso de un patrón |
| `high` | Lógica de negocio con reglas, múltiples validaciones, transacciones |
| `extra-high` | Lógica de negocio crítica con efectos cruzados, sincronización, múltiples reglas interactuando |
| `max` | El núcleo del sistema: oportunidades, estado de cartera, cambios de estado con todos sus efectos |

---

# FASE 0 — Infraestructura base

### B0.1 — Bootstrap del proyecto · `medium`
Crear el proyecto Spring Boot con Kotlin y Gradle (Kotlin DSL). Configurar dependencias base: Spring Web, Spring Data JPA, Spring Security, Flyway, PostgreSQL driver, Spring Boot Actuator. Estructura de paquetes `pe.quantum.crm` con `config/`, `domain/`, `shared/`. Configurar `application.properties` con `ddl-auto=validate` y Flyway habilitado.
- **Depende de:** nada
- **Aceptación:** `./gradlew build` compila; la app levanta y se conecta a PostgreSQL local.

### B0.2 — docker-compose y entorno local · `low`
Crear `docker-compose.yml` con PostgreSQL 16 (con healthcheck), `.env.example` con todas las variables, `.gitignore` que excluye `.env`, `build/`, `.gradle/`.
- **Depende de:** B0.1
- **Aceptación:** `docker-compose up -d` levanta PostgreSQL; la app se conecta usando las variables del `.env`.

### B0.3 — Configuración de calidad: ktlint, detekt, Kover · `medium`
Integrar ktlint, detekt y Kover en el build de Gradle con los umbrales de cobertura de `TESTING-backend.md` (90% dominio, 75% global). Configurar OWASP Dependency-Check.
- **Depende de:** B0.1
- **Aceptación:** `./gradlew ktlintCheck detekt koverVerify dependencyCheckAnalyze` corren (aunque sin código aún todo pasa).

### B0.4 — Pipeline CI (GitHub Actions) · `medium`
Crear el workflow de CI con todos los gates de `DEVOPS-backend.md`: lint, detekt, compile, tests, cobertura, dependency check. Configurar protección de ramas en `main` y `develop`.
- **Depende de:** B0.3
- **Aceptación:** el workflow corre en un PR de prueba y todos los jobs pasan.

### B0.5 — Migraciones Flyway · `medium`
Colocar las 19 migraciones de `docs/migrations/` en `src/main/resources/db/migration/`. Verificar que Flyway las ejecuta en orden contra PostgreSQL. Escribir un test de integración con Testcontainers que verifique que el schema se crea correctamente y los seeds (Calidda, catálogo de eventos) están presentes.
- **Depende de:** B0.1, B0.2
- **Aceptación:** las 19 migraciones corren limpias; el test de Testcontainers confirma el schema y los seeds.

### B0.6 — Setup de Testcontainers · `medium`
Configurar la clase base de tests de integración con Testcontainers (PostgreSQL 16). Crear los helpers de test compartidos (fixtures, factory de entidades de prueba). Verificar que un test de integración trivial levanta el contenedor y se conecta.
- **Depende de:** B0.5
- **Aceptación:** un test de integración de ejemplo levanta Testcontainers y pasa.

### B0.7 — Entidad Empleado y seguridad base (JWT) · `extra-high`
Crear la entidad `Empleado`, su repository, y la infraestructura de Spring Security: filtro JWT, generación y validación de tokens, configuración de cookies httpOnly (`HttpOnly; Secure; SameSite=Strict`), `BCryptPasswordEncoder`. Definir la decisión pendiente de almacenamiento de contraseña (campo en `empleados` vs. tabla separada) — si no hay indicación, usar campo `password_hash` en `empleados` y crear la migración V20. Configurar las cabeceras de seguridad HTTP y CORS restrictivo desde env.
- **Depende de:** B0.6
- **Aceptación:** tests de generación/validación de JWT; las cabeceras de seguridad están presentes; CORS rechaza orígenes no permitidos.

### B0.8 — Endpoints de autenticación · `high`
Implementar `POST /auth/login` (mensaje genérico ante fallo, rate limiting), `POST /auth/refresh`, y `GET /empleados/me`. Implementar el `GlobalExceptionHandler` con `@RestControllerAdvice` y el envelope `ApiResponse`. Implementar el flujo de `requiere_cambio_contrasena`.
- **Depende de:** B0.7
- **Aceptación:** login exitoso setea cookie httpOnly; credenciales inválidas dan 401 genérico; `/me` requiere auth; rate limiting bloquea tras N intentos.

### B0.9 — Dockerfile multi-stage · `medium`
Crear el `Dockerfile` multi-stage de `DEVOPS-backend.md §5.2`: etapa de build con `gradle:8-jdk21` que produce el `bootJar`, y etapa de runtime con `eclipse-temurin:21-jre-alpine`, usuario no-root y `EXPOSE 8080`. La imagen final solo contiene el JAR (ni Gradle ni el código fuente). Agregar `.dockerignore`. Verificar que el contenedor arranca contra el PostgreSQL de `docker-compose` y que Flyway aplica las migraciones al iniciar.
- **Depende de:** B0.5
- **Aceptación:** `docker build` produce una imagen que arranca como usuario no-root, se conecta a PostgreSQL y Flyway aplica las 19 migraciones; la imagen final no contiene código fuente ni Gradle.

> ## 🔍 AUDIT — Hito 0: Infraestructura y seguridad base
> **Ejecutar skills:** `docs/skills/audit-code-backend.md` · `docs/skills/audit-security-backend.md`
> **Auditar:** la configuración de seguridad (JWT, cookies, BCrypt, CORS, cabeceras, rate limiting), el envelope `ApiResponse`, el `GlobalExceptionHandler`, la entidad `Empleado`, el pipeline CI, y el `Dockerfile` (imagen mínima, usuario no-root, sin secretos en capas). Esta es la base de seguridad de todo el sistema — auditar con rigor extra.

### B0.10 — Deploy a Render/Railway (CD) · `medium`
Conectar el repo a Render o Railway con deploy automático desde `main`. Provisionar el PostgreSQL gestionado. Configurar las variables de entorno de producción en el panel (nunca en código): `DB_*`, `JWT_SECRET`, `JWT_EXPIRATION_MS`, `JWT_REFRESH_EXPIRATION_MS`, `CORS_ALLOWED_ORIGINS`, `SPRING_PROFILES_ACTIVE=production`. Configurar el health check (`GET /actuator/health`) como sonda de la plataforma, con rollback automático si falla. Ver `DEVOPS-backend.md §6`.
- **Depende de:** Hito 0, B0.9
- **Aceptación:** un push a `main` dispara el deploy; la app queda accesible por HTTPS; `GET /actuator/health` responde `UP`; Flyway aplicó las 19 migraciones en la BD gestionada; un health check fallido revierte al deploy anterior.

---

# FASE 1 — Módulos simples (catálogos)

### B1.1 — Módulo Modelos · `high`
CRUD completo de `modelos` con la regla de atomicidad: crear modelo + aplicaciones en una sola transacción, rechazar con `MODELO_SIN_APLICACIONES` si el array viene vacío. Endpoints de `contrato_api.md §14`. Autorización: solo admin/gerente para escritura.
- **Depende de:** Hito 0
- **Aceptación:** crear modelo sin aplicaciones falla; con aplicaciones se persiste atómicamente; tests unitarios + integración.

### B1.2 — Módulo Financiadoras · `medium`
CRUD de `financiadoras` con la regla del unique default (validar en backend antes del INSERT, además del índice parcial). Endpoints de `contrato_api.md §13`.
- **Depende de:** Hito 0
- **Aceptación:** no se puede crear una segunda financiadora con `es_default=true`; Calidda ya existe por seed.

### B1.3 — Módulo Catálogo de Eventos · `medium`
CRUD de `catalogo_eventos`. Validar el constraint `dispara_cambio_estado → estado_destino`. Endpoints de `contrato_api.md §15`. Solo admin.
- **Depende de:** Hito 0
- **Aceptación:** crear evento que dispara sin `estado_destino` falla; los 7+3 eventos del seed están presentes.

### B1.4 — Módulo Empleados (CRUD admin) · `high`
CRUD completo de empleados: crear (con `requiere_cambio_contrasena=true`), editar, activar/desactivar, resetear contraseña (genera temporal segura), cambiar contraseña propia. Regla: no desactivar al último admin. Endpoints de `contrato_api.md §7` + los de auth pendientes.
- **Depende de:** Hito 0
- **Aceptación:** crear empleado nace con cambio obligatorio; reset genera temporal; no se puede dejar el sistema sin admin.

> ## 🔍 AUDIT — Hito 1: Catálogos y empleados
> **Ejecutar skills:** `docs/skills/audit-code-backend.md` · `docs/skills/audit-security-backend.md`
> **Auditar:** los cuatro módulos CRUD, la autorización por rol en cada endpoint (admin/gerente para catálogos), la regla de atomicidad de modelos, el reseteo de contraseñas (aleatoriedad segura), y la regla de no-lockout de admin.

---

# FASE 2 — Empresas y contactos

### B2.1 — Módulo Empresas (CRUD + segmentos) · `high`
CRUD de `empresas` con segmentos multi-select (tabla intermedia, reemplazo atómico). `estado_cartera` nace `no_contactado` y NO se acepta como input. RUC como VARCHAR validado. Filtro de visibilidad por rol en las queries (vendedor/analista solo ven las suyas). Endpoints de `contrato_api.md §8`.
- **Depende de:** Hito 1
- **Aceptación:** crear empresa con segmentos atómico; `estado_cartera` ignorado si viene en body; vendedor solo ve sus empresas.

### B2.2 — Check de RUC duplicado · `medium`
`GET /empresas/ruc/:ruc`. Siempre devuelve 200, no expone el vendedor dueño. En el POST de empresa, validar RUC y devolver `409 RUC_DUPLICADO` si existe.
- **Depende de:** B2.1
- **Aceptación:** RUC existente devuelve `existe:true` sin exponer dueño; POST con RUC duplicado da 409.

### B2.3 — Módulo Contactos + vinculación multi-empresa · `high`
CRUD de `contactos` con la tabla `empresa_contactos` (cargo y toma_decision por relación). Búsqueda de contactos existentes. Vincular/desvincular contacto a empresa. `ON DELETE RESTRICT` respetado. Endpoints de `contrato_api.md §9`.
- **Depende de:** B2.1
- **Aceptación:** un contacto se vincula a dos empresas con cargos distintos; no se elimina un contacto vinculado; búsqueda funciona.

### B2.4 — Estado de cartera manual + reasignación · `high`
`PATCH /empresas/:id/estado-cartera` (solo estados manuales; rechaza derivados y rechaza si hay oportunidad activa). `PATCH /empresas/:id/vendedor` (solo admin/gerente/jdv). Aún sin `actualizarEstadoCartera` completo — eso llega con oportunidades; aquí solo la parte manual.
- **Depende de:** B2.1
- **Aceptación:** cambiar a estado derivado falla; reasignación restringida por rol.

> ## 🔍 AUDIT — Hito 2: Empresas y contactos
> **Ejecutar skills:** `docs/skills/audit-code-backend.md` · `docs/skills/audit-security-backend.md`
> **Auditar:** el filtro de visibilidad por rol en queries (IDOR — que un vendedor no acceda a empresas ajenas, 404), la atomicidad de segmentos y vinculaciones, el check de RUC sin fuga de información, y el manejo de `estado_cartera` manual. Verificar que ningún endpoint expone datos de otros vendedores.

---

# FASE 3 — Pipeline y oportunidades (núcleo crítico)

### B3.1 — Servicio actualizarEstadoCartera · `max`
Implementar `EstadoCarteraService.actualizar(idEmpresa)` con su guarda de entrada completa: recalcula mirando TODAS las oportunidades de la empresa, no escribe si no cambia, respeta estados manuales cuando no hay derivado, prioriza derivado sobre manual. Esta es la pieza de sincronización más crítica del sistema. Tests exhaustivos de cada rama.
- **Depende de:** Hito 2
- **Aceptación:** todas las ramas de `reglas_negocio.md §3.2` tienen test; no escribe cuando el estado no cambia; recalcula sobre el conjunto completo.

### B3.2 — Cálculo de monto_total · `high`
Implementar el cálculo `cantidad × precio_unitario × (1 − dcto/100)` como función pura reutilizable. Manejar dcto null (=0) y cantidad null (=null). `monto_total` nunca se acepta como input.
- **Depende de:** Hito 2
- **Aceptación:** todos los casos de borde con test; el cálculo es una función única reutilizable.

### B3.3 — Creación de oportunidades · `max`
`POST /oportunidades` con toda la lógica transaccional: snapshot de `id_vendedor` desde la empresa, financiadora default si no se especifica, `precio_unitario` = `precio_base` del modelo, cálculo de `monto_total`, primer registro en `oportunidad_estados_log` (`estado_anterior=NULL`), y llamada a `actualizarEstadoCartera` en la misma transacción. Modelo obligatorio. Filtro de visibilidad por rol.
- **Depende de:** B3.1, B3.2
- **Aceptación:** crear oportunidad eleva la empresa a `oportunidad_activa`; snapshot de vendedor correcto; monto calculado; primer log con `estado_anterior=NULL`; todo atómico.

### B3.4 — Edición de campos negociables · `high`
`PUT /oportunidades/:id`. Editar modelo, cantidad, precio_unitario, dcto, garantia, finc_paralelo, ficha_venta, notas, cierre estimado. Recalcular `monto_total` al cambiar cantidad/precio/dcto. Rechazar `monto_total` en body (`MONTO_NO_EDITABLE`). Lógica de cambio de modelo (sobrescribe precio solo si no fue editado manualmente; si lo fue, advertencia).
- **Depende de:** B3.3
- **Aceptación:** `monto_total` en body da 400; cambio de modelo respeta precio editado; recálculo correcto.

### B3.5 — Cambio de estado de oportunidad · `max`
`PATCH /oportunidades/:id/estado` con todas las validaciones: `motivo_cierre` obligatorio si `cerrado`, permiso de `facturado` (solo admin/gerente/analista, verificado en servicio), detección de retroceso (`es_retroceso` en respuesta), advertencias de eventos recomendados sin registrar, insert en log, llamada a `actualizarEstadoCartera`. Al retroceder desde cerrado, `motivo_cierre` a null.
- **Depende de:** B3.3
- **Aceptación:** cada validación tiene test; pasar a facturado con rol vendedor da 403; cerrar sin motivo da 400; retroceso marca `es_retroceso`; estado_cartera se recalcula.

### B3.6 — Log de estados y traspaso · `high`
`GET /oportunidades/:id/log` (historial completo). `PATCH /oportunidades/:id/vendedor` (traspaso por mutación de id_vendedor, solo admin/gerente/jdv, sin duplicar). Gestión de contactos en oportunidad (`oportunidad_contactos`).
- **Depende de:** B3.5
- **Aceptación:** el log devuelve el historial ordenado; el traspaso muta el vendedor sin duplicar; contactos de oportunidad con rol.

### B3.7 — Listado de pipeline · `high`
`GET /oportunidades` con filtros (estado, empresa, vendedor, financiadora, incluir_cerradas), paginación, y términos de financiadora por JOIN (no duplicados). Filtro de visibilidad por rol. `GET /oportunidades/:id` con detalle completo (contactos, entrada a etapa actual derivada del log).
- **Depende de:** B3.5
- **Aceptación:** los términos de financiadora vienen por JOIN; vendedor solo ve sus oportunidades; cerradas ocultas por defecto.

> ## 🔍 AUDIT — Hito 3: Núcleo de oportunidades
> **Ejecutar skills:** `docs/skills/audit-code-backend.md` · `docs/skills/audit-security-backend.md`
> **Auditar (auditoría más exhaustiva del proyecto):** `actualizarEstadoCartera` y todas sus ramas, la atomicidad de la creación de oportunidades, el cálculo de `monto_total` (nunca aceptado como input), el snapshot de `id_vendedor`, todas las validaciones de cambio de estado (motivo_cierre, permiso facturado, retroceso), la sincronización de `estado_cartera` en cada transición, la ausencia de duplicación de datos de financiadora, y el filtro de visibilidad por rol. Verificar que ninguna ruta permite desincronizar `estado_cartera`.

---

# FASE 4 — Eventos y tareas

### B4.1 — Módulo Eventos · `extra-high`
CRUD de `eventos` con distinción catálogo/personalizado (constraints respetados: origen mutuamente excluyente, personalizado no dispara estado). Las tres fechas (estimada, seguimiento, ocurrencia). Endpoints de `contrato_api.md §11`.
- **Depende de:** Hito 3
- **Aceptación:** evento personalizado no puede disparar estado; origen mutuamente excluyente validado; las tres fechas se manejan correctamente.

### B4.2 — Evento ocurrido con sugerencia · `extra-high`
`PATCH /eventos/:id/ocurrido`. Marca el evento, setea `fecha_ocurrencia`, y si `dispara_cambio_estado=true` devuelve la **sugerencia** en la respuesta SIN ejecutar el cambio de estado. El cambio es responsabilidad de una segunda llamada al endpoint de estado. `PATCH /eventos/:id/descartado`.
- **Depende de:** B4.1
- **Aceptación:** marcar evento ocurrido NO cambia el estado de la oportunidad; solo devuelve sugerencia; la segunda llamada (estado) es la que cambia.

### B4.3 — Módulo Tareas · `high`
CRUD de `tareas` con distinción prospección (`id_oportunidad=null`) vs. oportunidad. Validar: tarea con `id_oportunidad=null` en empresa con oportunidad activa se rechaza. Estados (completada/cancelada). `id_asignado` default al usuario. Filtro de visibilidad (vendedor solo sus tareas asignadas). Endpoints de `contrato_api.md §12`.
- **Depende de:** Hito 3
- **Aceptación:** tarea de prospección con id_oportunidad null funciona; rechazo si la empresa tiene oportunidad activa; vendedor solo ve sus tareas.

> ## 🔍 AUDIT — Hito 4: Eventos y tareas
> **Ejecutar skills:** `docs/skills/audit-code-backend.md` · `docs/skills/audit-security-backend.md`
> **Auditar:** la regla crítica de que un evento ocurrido NO ejecuta el cambio de estado (solo sugiere), los constraints de eventos (catálogo/personalizado/no-dispara), la lógica de tareas de prospección vs. oportunidad, y el filtro de visibilidad por rol en ambos.

---

# FASE 5 — Prospección e Inicio

### B5.1 — Endpoint de Prospección · `extra-high`
`GET /prospeccion`. Cálculo de hitos (eventos con `es_hito_prospeccion=true` ocurridos sin oportunidad), `dias_sin_actividad` (máx de fecha de tareas completadas o eventos ocurridos sin oportunidad), ordenamiento `checkpoints DESC, dias DESC`, `lista_para_convertir`, `siguiente_tarea`. Solo empresas en `prospeccion`. Filtro por rol.
- **Depende de:** Hito 4
- **Aceptación:** hitos calculados correctamente desde el catálogo; ordenamiento correcto; solo empresas en prospección.

### B5.2 — Endpoint de Inicio agregado · `high`
`GET /inicio`. Una sola llamada que agrega: tareas pendientes ordenadas, eventos por seguir, resumen de pipeline por etapa, resumen de prospección. Filtro por rol en todo. Optimizar para evitar N+1.
- **Depende de:** B5.1
- **Aceptación:** una sola llamada devuelve todo; sin N+1; ordenamientos correctos; respeta rol.

> ## 🔍 AUDIT — Hito 5: Prospección e Inicio
> **Ejecutar skills:** `docs/skills/audit-code-backend.md` · `docs/skills/audit-security-backend.md`
> **Auditar:** la corrección del cálculo de hitos y `dias_sin_actividad`, el ordenamiento de prospección, la ausencia de N+1 en el endpoint de inicio (revisar las queries generadas), y el filtro por rol. Verificar rendimiento de las queries agregadas.

---

# FASE 6 — Reportes

### B6.1 — Reportes estratégicos · `extra-high`
`GET /reportes/ventas`, `/reportes/pipeline` (con concentración Calidda y oportunidades sin actividad), `/reportes/descuentos`. Solo admin/gerente/jdv. Filtros de fecha con default al mes actual.
- **Depende de:** Hito 5
- **Aceptación:** cada reporte con datos de prueba conocidos da el resultado esperado; vendedor recibe 403.

### B6.2 — Reportes operativos · `extra-high`
`GET /reportes/equipo`, `/reportes/velocidad-etapas` (con advertencia de muestra pequeña), `/reportes/prospeccion` (embudo y por origen de lead). Solo admin/gerente/jdv.
- **Depende de:** B6.1
- **Aceptación:** velocidad muestra advertencia con <10 operaciones; embudo correcto; permisos verificados.

> ## 🔍 AUDIT — Hito 6: Reportes y cierre del backend
> **Ejecutar skills:** `docs/skills/audit-code-backend.md` · `docs/skills/audit-security-backend.md`
> **Auditar:** la corrección de los cálculos de cada reporte, el permiso de reportes (ningún vendedor/analista accede), y una **auditoría final integral** del backend completo: cobertura global, ausencia de secretos, todas las reglas de negocio de `reglas_negocio.md` cubiertas por tests, y el pipeline CI verde en todos los gates.

---

## Resumen de hitos y esfuerzo

| Hito | Tareas | Esfuerzo dominante |
|---|---|---|
| 0 — Infra y seguridad base | B0.1–B0.8 | medium/extra-high |
| 1 — Catálogos y empleados | B1.1–B1.4 | medium/high |
| 2 — Empresas y contactos | B2.1–B2.4 | high |
| 3 — Núcleo de oportunidades | B3.1–B3.7 | **max** |
| 4 — Eventos y tareas | B4.1–B4.3 | extra-high |
| 5 — Prospección e Inicio | B5.1–B5.2 | extra-high |
| 6 — Reportes | B6.1–B6.2 | extra-high |

La Fase 3 es el corazón del sistema. Asignar `max` a B3.1, B3.3 y B3.5 sin excepción — son las tareas donde un error corrompe datos en producción.
