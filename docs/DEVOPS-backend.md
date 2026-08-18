# Quantum CRM Backend — CI/CD y DevOps

> Pipeline de integración y despliegue del backend desde el inicio. Repo: `quantum-crm-backend`. La automatización de calidad es parte del setup de la Fase 0.

---

## 1. Filosofía

- **Todo cambio pasa por el pipeline.** Ningún código llega a `main` sin pasar los gates.
- **El pipeline es el guardián de la calidad.** Si tests, lint, cobertura o escaneo de seguridad fallan, el merge se bloquea.
- **Fail fast.** Los checks rápidos (lint, compilación) corren primero.
- **Reproducibilidad.** Build idéntico en local y CI gracias a Docker y versiones fijadas.

---

## 2. Estrategia de ramas

```
main          → producción. Siempre deployable. Protegida.
develop       → integración.
feature/xxx   → features (desde develop).
fix/xxx       → bugfixes (desde develop o main si es hotfix).
```

**Protección de ramas (GitHub):**
- `main` y `develop` no aceptan push directo. Solo merge vía PR.
- Un PR no se mergea si el CI falla.
- `main` requiere que el PR venga de `develop` o un `hotfix/`.

**Quién trabaja aquí:** el único desarrollador humano es el dueño del repo, así que `enforce_admins` está apagado a propósito y sus commits directos a `main` son válidos — no son un hueco de proceso. Esta disciplina de rama propia + PR + CI en verde aplica en particular a **Claude Code**: cualquier funcionalidad, fix o cambio de esquema que implemente Claude Code va en su propia `feature/xxx` o `fix/xxx`, nunca commiteado directo a `main`.

**Commits — Conventional Commits:**
```
feat(empresas): agregar endpoint de búsqueda por RUC
fix(oportunidades): corregir cálculo de monto_total con dcto null
chore(migrations): agregar V20 para requiere_cambio_contrasena
test(oportunidades): cubrir retroceso de estado
```

---

## 3. Pipeline de CI (GitHub Actions)

Corre en cada push a un PR y en cada merge a `develop`/`main`.

```yaml
# .github/workflows/ci.yml (esquema, no copiar literal)
name: Backend CI
on:
  pull_request:
  push:
    branches: [develop, main]

jobs:
  build:
    runs-on: ubuntu-latest
    steps:
      - checkout
      - setup JDK 21 (Temurin)
      - cache de Gradle

      # Fail fast: lo más rápido primero
      - name: Lint (ktlint)
        run: ./gradlew ktlintCheck

      - name: Static analysis (detekt)
        run: ./gradlew detekt

      - name: Compile
        run: ./gradlew compileKotlin

      # Tests con Testcontainers (Docker disponible en el runner de GitHub)
      - name: Tests
        run: ./gradlew test

      # Cobertura — falla si baja del umbral (ver TESTING-backend.md §8)
      - name: Coverage check (Kover)
        run: ./gradlew koverVerify

      - name: Dependency vulnerability scan
        run: ./gradlew dependencyCheckAnalyze

      - name: Upload coverage report
        uses: actions/upload-artifact@v4
```

---

## 4. Gates de calidad

Un PR solo se mergea si **todos** pasan:

| Gate | Herramienta | Falla si... |
|---|---|---|
| Formato | ktlint | El código no respeta el formato |
| Análisis estático | detekt | Hay code smells configurados |
| Compilación | Gradle | El código no compila |
| Tests | JUnit + Testcontainers | Cualquier test falla |
| Cobertura | Kover | Cobertura < umbral (90% dominio, 75% global) |
| Arquitectura | ArchUnit | Se viola una regla del monolito modular |
| Vulnerabilidades | OWASP Dependency-Check | CVE crítico/alto sin mitigar |

---

## 5. Containerización

### 5.1 docker-compose para desarrollo local

```yaml
# docker-compose.yml
services:
  postgres:
    image: postgres:16
    environment:
      POSTGRES_DB: quantum_crm
      POSTGRES_USER: quantum
      POSTGRES_PASSWORD: ${DB_PASSWORD}
    ports:
      - "5432:5432"
    volumes:
      - postgres_data:/var/lib/postgresql/data
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U quantum"]
      interval: 10s
      timeout: 5s
      retries: 5

volumes:
  postgres_data:
```

El backend corre en local con `./gradlew bootRun` contra este PostgreSQL.

### 5.2 Dockerfile (multi-stage)

El `Dockerfile` vive en la raíz del repositorio. Multi-stage, para que la imagen final solo tenga el JAR y un JRE, no Gradle ni el código fuente. Usuario no-root por seguridad (ver `SECURITY-backend.md`).

Cuatro decisiones que no son cosméticas:

**El contexto se acota con `.dockerignore`.** Un `COPY . .` sin filtro mete el `.env` —con `JWT_SECRET`, `DB_PASSWORD` y las credenciales de Drive en base64— dentro de la capa del stage de build. Que la imagen final sea multi-stage no basta: la capa intermedia conserva lo copiado y puede acabar en una caché remota o en un registry.

**Las dependencias se copian antes que el código.** El wrapper y los scripts de build van en su propia capa y se resuelven las dependencias ahí. Mientras `build.gradle.kts` no cambie, Docker reutiliza esa capa y un cambio de código no vuelve a descargar el árbol entero.

**La imagen no corre tests.** Los de integración levantan Testcontainers y dentro del build no hay un daemon de Docker. La suite es responsabilidad de CI, que gatea el merge a `main` antes de que esta imagen llegue a construirse.

**`TZ=UTC` explícito.** Las fechas de tareas y eventos se normalizan a UTC al guardarlas pero se comparan contra `LocalDateTime.now()`, que usa la zona por defecto de la JVM. Fijarla en la imagen hace que ambas coincidan siempre, en vez de depender de cómo venga configurado el host.

Se acompaña de `.gitattributes`, que fuerza LF en `gradlew`: con CRLF el kernel no encuentra el intérprete y el build muere con `bad interpreter: /bin/sh^M`.

---

## 6. Despliegue (Fase 1 — MVP)

### 6.1 Plataforma

**Render** o **Railway**. Ambos soportan:
- Deploy automático desde GitHub al merge a `main`.
- PostgreSQL gestionado con backups automáticos.
- TLS automático.
- Variables de entorno gestionadas en panel.

### 6.2 Flujo de deploy

```
merge a main
     │
     ▼
CI pasa todos los gates
     │
     ▼
Render/Railway detecta el push a main
     │
     ▼
Build de la imagen Docker
     │
     ▼
Flyway corre las migraciones automáticamente al arrancar
     │
     ▼
Health check (GET /actuator/health)
     │
     ├─ OK  → tráfico al nuevo deploy
     └─ Falla → rollback automático al deploy anterior
```

### 6.3 Migraciones en deploy

- Flyway corre automáticamente al arrancar (`spring.flyway.enabled=true`).
- Las migraciones son **forward-only**. Nunca editar una migración aplicada; siempre crear una nueva.
- Una migración mal hecha rompe producción, por eso se validan en CI contra el PostgreSQL de Testcontainers antes de llegar a `main`.

### 6.4 Variables de entorno en producción

Configuradas en el panel de Render/Railway, nunca en código:

```
DB_URL, DB_USERNAME, DB_PASSWORD     → del PostgreSQL gestionado
JWT_SECRET                           → openssl rand -hex 32
JWT_EXPIRATION_MS, JWT_REFRESH_...
CORS_ALLOWED_ORIGINS                 → dominio del frontend en producción
SPRING_PROFILES_ACTIVE=production
```

---

## 7. Entornos

| Entorno | Propósito | Base de datos | Deploy |
|---|---|---|---|
| **local** | Desarrollo | PostgreSQL en docker-compose | Manual (`bootRun`) |
| **production** | Usuarios reales | PostgreSQL gestionado | Automático al merge a `main` |

No hay un tercer entorno desplegado (`staging`) y no se va a agregar solo por tener un ambiente intermedio — significaría un segundo deploy remoto que mantener sincronizado, sin beneficio real para un equipo de un desarrollador. En su lugar, **"staging" es levantar la rama en cuestión en local** (`git checkout feature/xxx && ./gradlew bootRun`) contra el docker-compose de siempre. Para cambios que solo agregan comportamiento, eso basta.

Para cambios que tocan datos existentes — una migración que altera o borra filas, no solo agrega columnas — levantar la rama en local no es suficiente porque el docker-compose parte de una DB vacía. En ese caso, antes de mergear a `main`:
1. Restaurar un dump reciente de producción en el PostgreSQL local (ver §9 para cómo sacarlo).
2. Correr la rama contra ese dump y verificar que la migración y el código nuevo dejan los datos en el estado esperado.
3. Recién entonces abrir el PR.

---

## 8. Observabilidad

### 8.1 Logs
- Logs estructurados (JSON en producción) para que sean parseables.
- Render/Railway capturan stdout/stderr.
- Cada error de servidor genera un ID de correlación devuelto al cliente (sin detalle) y logueado con el detalle completo.

### 8.2 Logging técnico — Better Stack

Agregador de logs técnicos vía Logback (Better Stack / Logtail).

**Archivos modificados/creados:**
- `build.gradle.kts` — dependencia `com.logtail:logback-logtail`.
- `src/main/resources/logback-spring.xml` — appender `Console` (siempre activo) y appender `Logtail` (solo perfil `production`), con `mdcFields` `traceId,usuario,modulo`.
- `src/main/kotlin/pe/quantum/crm/config/security/MdcLoggingFilter.kt` — puebla el MDC (`traceId` por request, `usuario` desde el `SecurityContext` o `anonimo`, `modulo` desde el segundo segmento de la URI tras `/api/`). Registrado en `SecurityConfig` con `addFilterAfter(MdcLoggingFilter(), JwtAuthenticationFilter::class.java)`, para que el `SecurityContext` ya esté poblado cuando corre.

**Variables de entorno (solo producción):**

| Variable | De dónde sale |
|---|---|
| `LOGTAIL_SOURCE_TOKEN` | Dashboard de Better Stack, al crear la fuente (Source) de logs del proyecto |
| `LOGTAIL_INGEST_HOST` | Mismo dashboard, host de ingesta de esa fuente |

Se configuran manualmente en el panel de Render/Railway al desplegar — igual que el resto de variables de §6.4. **Nunca** en código ni en `.env` local.

**En local:** el perfil activo es `local` (`SPRING_PROFILES_ACTIVE=local` en `.env`), que no es `production`, así que `logback-spring.xml` nunca instancia el appender `Logtail` — los logs solo van a consola. No hace falta ninguna configuración adicional para desarrollar ni para correr los tests.

### 8.3 Health checks
- `GET /actuator/health` (Spring Boot Actuator) reporta el estado de la app y la conexión a la DB.
- La plataforma de deploy usa este endpoint para decidir si un deploy es saludable.

### 8.4 Métricas (post-MVP)
- Para el MVP, logs y health checks bastan. Después se puede agregar Actuator + Prometheus + Grafana, o Sentry para tracking de errores.

---

## 9. Estrategia de rollback

### 9.1 Rollback de código

- **Deploy fallido (health check no pasa):** rollback automático al deploy anterior.
- **Bug en producción tras deploy exitoso, sin datos afectados:** revertir el commit en `main`, lo que dispara un nuevo deploy con el código anterior. Si el bug involucró un cambio de schema, crear una nueva migración correctiva (nunca revertir una migración aplicada).
- Mantener el deploy anterior disponible para rollback inmediato durante las primeras horas tras cada release.

### 9.2 Rollback de datos

Revertir el código no revierte lo que ya se escribió en la base de datos. Con la app en producción, un bug puede haber quedado guardado (montos mal calculados, `estado_cartera` inconsistente, filas de más o de menos) antes de detectarse. **Restaurar un backup completo a un punto anterior no es la respuesta por defecto:** entre el bug y su detección hubo transacciones legítimas de otros registros que ese restore también borraría. Un restore ciego cambia un bug conocido y acotado por pérdida de datos real de usuarios.

Procedimiento:

1. **Backups automáticos** del PostgreSQL gestionado (Render/Railway) son la red de seguridad para un desastre total (corrupción, borrado masivo por error operativo), no la herramienta para corregir un bug de negocio puntual. Verificar en el panel la retención vigente al menos una vez por trimestre — no asumir que "automático" significa "suficiente".
2. **Antes de cualquier migración que altere o borre datos existentes** (no las que solo agregan columnas/tablas), tomar además un backup manual on-demand desde el panel, aparte del automático. Es el punto de restore si la migración sale mal a mitad de aplicarse.
3. **Corregir datos ya corrompidos en producción se hace con un fix dirigido, no con un restore:**
   - Diagnosticar primero en modo solo-lectura contra la DB de producción (nunca escribir directo sin confirmar el diagnóstico).
   - Si el fix es puntual y no se repetirá, un script ad-hoc documentado (qué filas, por qué, qué UPDATE) basta — no requiere ser una migración de Flyway.
   - Si el bug es de un tipo que puede volver a ocurrir (ej. una migración que se re-ejecutará en otro ambiente, o un backfill que debe quedar registrado), sí va como migración Flyway forward-only nueva, igual que una corrección de schema.
   - Cualquier UPDATE/DELETE contra producción se corre manualmente y con confirmación explícita antes de ejecutar — nunca automatizado sin revisión previa del diagnóstico.

---

## 10. Setup de la Fase 0 (checklist DevOps backend)

- [ ] Repositorio en GitHub con protección de ramas en `main` y `develop`
- [ ] `docker-compose.yml` con PostgreSQL local funcionando
- [ ] `.env.example` con todas las variables documentadas
- [ ] `.gitignore` que excluye `.env`, `build/`, `.gradle/`
- [ ] Workflow de GitHub Actions con todos los gates
- [ ] Dockerfile multi-stage
- [ ] ktlint y detekt configurados
- [ ] Kover configurado con umbrales de cobertura
- [ ] OWASP Dependency-Check configurado
- [ ] Proyecto conectado a Render/Railway con deploy automático desde `main`
- [ ] Variables de entorno de producción en el panel
- [ ] PostgreSQL gestionado provisionado
- [ ] Primer deploy de prueba exitoso, verificando que Flyway corre las 19 migraciones

Completado esto, el backend tiene un pipeline que protege la calidad desde el primer commit de lógica.
