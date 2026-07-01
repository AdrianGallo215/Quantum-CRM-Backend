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

```dockerfile
# Build
FROM gradle:8-jdk21 AS build
WORKDIR /app
COPY . .
RUN ./gradlew bootJar --no-daemon

# Runtime — imagen mínima, usuario no-root
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY --from=build /app/build/libs/*.jar app.jar
RUN addgroup -S app && adduser -S app -G app
USER app
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
```

Multi-stage para que la imagen final solo tenga el JAR, no Gradle ni el código fuente. Usuario no-root por seguridad (ver `SECURITY-backend.md`).

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

Dos entornos bastan para el MVP. `staging` se puede agregar después si se necesita QA previo a producción.

---

## 8. Observabilidad

### 8.1 Logs
- Logs estructurados (JSON en producción) para que sean parseables.
- Render/Railway capturan stdout/stderr.
- Cada error de servidor genera un ID de correlación devuelto al cliente (sin detalle) y logueado con el detalle completo.

### 8.2 Health checks
- `GET /actuator/health` (Spring Boot Actuator) reporta el estado de la app y la conexión a la DB.
- La plataforma de deploy usa este endpoint para decidir si un deploy es saludable.

### 8.3 Métricas (post-MVP)
- Para el MVP, logs y health checks bastan. Después se puede agregar Actuator + Prometheus + Grafana, o Sentry para tracking de errores.

---

## 9. Estrategia de rollback

- **Deploy fallido (health check no pasa):** rollback automático al deploy anterior.
- **Bug en producción tras deploy exitoso:** revertir el commit en `main`, lo que dispara un nuevo deploy con el código anterior. Si el bug involucró un cambio de schema, crear una nueva migración correctiva (nunca revertir una migración aplicada).
- Mantener el deploy anterior disponible para rollback inmediato durante las primeras horas tras cada release.

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
