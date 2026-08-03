# =============================================================================
# Imagen de produccion del backend (DEVOPS-backend.md §5.2).
#
# Multi-stage: la imagen final contiene el JAR y un JRE, ni Gradle ni el codigo
# fuente. Usuario no-root (SECURITY-backend.md).
#
# El contexto que llega hasta aqui lo acota `.dockerignore`; en particular el
# `.env` NUNCA entra, ni siquiera al stage de build.
# =============================================================================

# ── Stage 1: build ────────────────────────────────────────────────────────────
FROM eclipse-temurin:21-jdk-alpine AS build
WORKDIR /app

# El wrapper y los scripts de build van primero y solos. Mientras no cambien,
# Docker reutiliza la capa de dependencias y no vuelve a descargar Gradle ni el
# arbol de dependencias en cada cambio de codigo.
COPY gradlew ./
COPY gradle gradle
COPY settings.gradle.kts build.gradle.kts ./
COPY config config
RUN chmod +x gradlew && ./gradlew --no-daemon dependencies

# El codigo va despues: es lo que cambia en cada commit.
COPY src src

# Sin tests: los de integracion levantan Testcontainers y no hay un daemon de
# Docker dentro de este build. La suite es responsabilidad de CI, que gatea el
# merge a main antes de que se construya esta imagen.
RUN ./gradlew --no-daemon bootJar -x test \
    && mv build/libs/*.jar /app/app.jar

# ── Stage 2: runtime ──────────────────────────────────────────────────────────
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

# Usuario sin privilegios: si alguien logra ejecucion remota, no es root.
RUN addgroup -S app && adduser -S app -G app
COPY --from=build --chown=app:app /app/app.jar app.jar
USER app

# UTC explicito. Las fechas de tareas y eventos se normalizan a UTC al
# guardarlas (`LocalDateTime.ofInstant(instante, ZoneOffset.UTC)`) pero se
# comparan contra `LocalDateTime.now()`, que usa la zona por defecto de la JVM.
# Fijarla aqui hace que ambas coincidan siempre, en vez de depender de como
# venga configurado el host.
ENV TZ=UTC

# MaxRAMPercentage en lugar de un -Xmx fijo: la JVM se ajusta al limite de
# memoria del contenedor, que en Render/Railway depende del plan contratado.
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75.0 -XX:+UseContainerSupport"

EXPOSE 8080

# `sh -c` para que $JAVA_OPTS se expanda; sin el, la JVM recibiria la variable
# literal como un unico argumento.
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
