# CLAUDE.md — Quantum CRM Backend

Contexto de arranque para Claude Code. Léelo al inicio de cada sesión. Para detalle profundo, ve a los documentos en `docs/`.

---

## Qué es esto

API REST en Kotlin + Spring Boot para el CRM de Quantum Investment, representante exclusivo de buses **KinWin** en Perú. Venta tripartita: Quantum + cliente + financiadora (Calidda por defecto). **El backend es dueño de la lógica de negocio, el modelo de datos y el contrato de API.** El frontend es un repo separado que consume esta API.

**Regla de oro del negocio:** una venta cierra solo cuando Calidda desembolsa (estado `facturado`). No con contrato ni orden de compra.

---

## Stack

Kotlin 1.9 · Spring Boot 3.2 · Spring Data JPA · Flyway · Spring Security 6 + JWT · PostgreSQL 16 · Gradle (Kotlin DSL) · JDK 21.

`spring.jpa.hibernate.ddl-auto=validate` — Hibernate NUNCA toca el schema. Solo Flyway.

---

## Comandos

```bash
./gradlew bootRun                 # levantar (puerto 8080)
./gradlew test                    # tests unitarios + ArchUnit (rápidos, sin Docker)
./gradlew integrationTest         # tests @Tag("integration") con Testcontainers
./gradlew ktlintCheck             # formato
./gradlew ktlintFormat            # autoformatear
./gradlew detekt                  # análisis estático
./gradlew koverVerify             # cobertura
./gradlew dependencyCheckAnalyze  # vulnerabilidades
docker-compose up -d              # PostgreSQL local
```

Antes de cada commit: `./gradlew test` debe pasar.

---

## Estructura

```
src/main/kotlin/pe/quantum/crm/
├── config/         # Security, CORS, async
├── domain/         # un módulo por dominio; los 15 que existen hoy:
│   │                 catalogoeventos, contactos, empleados, empresas, eventos,
│   │                 financiadoras, inicio, metasventa, modelos, notificaciones,
│   │                 oportunidades, prospeccion, reportes, solicitudes, tareas
│   └── <modulo>/  Controller, Service (interfaz), ServiceImpl, Repository, Entity, Mapper, dto/
├── integracion/    # drive/ — Google Drive como almacén de documentos
├── importcsvtemp/  # import CSV temporal (utilidad de carga, no es el import de Excel del PRD)
├── mantenimiento/  # backfills operativos (p. ej. carpetas de Drive)
├── shared/         # ApiResponse, excepciones, enums
└── CrmApplication.kt
src/main/resources/db/migration/   # V1__ a V37__ (37 migraciones)
docs/                              # referencia (ver abajo)
```

Cada módulo: `Controller → Service → Repository`. La dependencia fluye en una dirección. El controller no conoce entidades JPA; el repository no conoce DTOs.

---

## Documentos de referencia (en docs/)

| Archivo | Cuándo leerlo |
|---|---|
| `PRD-backend.md` | Visión, fases de implementación, alcance del MVP |
| `reglas_negocio.md` | **Antes de implementar cualquier lógica.** La fuente de verdad del comportamiento |
| `contrato_api.md` | Endpoints, requests, responses, errores. Este repo es su dueño |
| `matriz_permisos.md` | Qué rol puede ver/hacer qué |
| `schema.sql` | Modelo de datos completo |
| `TESTING-backend.md` | **Cómo escribir tests. TDD obligatorio** |
| `SECURITY-backend.md` | Requisitos de seguridad |
| `DEVOPS-backend.md` | CI/CD, deploy |
| `migrations/` | Copia de referencia de las migraciones. **Está desactualizada: solo llega a V19 y el repo va por V37.** La fuente de verdad es `src/main/resources/db/migration/` |

---

## Reglas que NUNCA debes romper

1. **TDD siempre.** Escribe el test que falla ANTES del código. Ninguna tarea termina sin tests pasando. Ver `TESTING-backend.md`.
2. **`monto_total` se calcula, nunca se acepta como input.** `cantidad × precio_unitario × (1 − dcto/100)`. Si viene en el body, ignóralo.
3. **`estado_cartera` solo se modifica vía `actualizarEstadoCartera()`**, dentro de la transacción del evento que lo dispara. Ningún otro código lo toca.
4. **Los eventos no cambian el estado automáticamente.** Devuelven una sugerencia; el cambio es una segunda llamada confirmada.
5. **`motivo_cierre` obligatorio cuando `estado = 'cerrado'`.** Validar en backend + CHECK constraint.
6. **El paso a `facturado` solo para admin, gerencia.** Verificar en el servicio. (El rol se llama `gerencia`, no `gerente`: lo renombró V25. `analista` perdió este privilegio el 2026-08-18 al pasar a rol de apoyo de solo lectura — ver `docs/matriz_permisos.md`.)
7. **No existe estado `perdido`.** El enum tiene 4 valores. ¿Necesitas otro? Pregunta.
8. **Inyección por constructor** (`private val`), nunca `@Autowired` en campos.
9. **Relaciones JPA siempre `LAZY`.** Nunca exponer entidades en controllers — siempre DTOs.
10. **`@Transactional(readOnly = true)` en lecturas**, `@Transactional` en escrituras cubriendo toda la operación.
11. **Queries parametrizadas siempre.** Nunca SQL por concatenación.
12. **Un módulo nunca accede a tablas ni entidades de otro módulo.** Solo vía su API pública: interfaces de servicio, DTOs, enums de contrato y eventos publicados. Lo verifica ArchUnit sobre el bytecode, en `src/test/kotlin/pe/quantum/crm/arquitectura/ArquitecturaModulosTest.kt` (corre en `./gradlew test`).
13. **Nunca secretos en código.** Todo desde variables de entorno. `.env` en `.gitignore`.
14. **IDOR:** recurso ajeno → devolver 404, no 403.

---

## Plan de fases (resumen)

0. Infra base: Spring Boot, migraciones, auth/JWT, `/me`, CI
1. Módulos simples: empleados, modelos, financiadoras, catálogo de eventos
2. Empresas y contactos
3. **Pipeline y oportunidades** (la fase más crítica — toda la lógica de negocio densa)
4. Eventos y tareas
5. Prospección e Inicio
6. Reportes

Construir en orden. No avanzar sin validar la fase anterior. Detalle en `PRD-backend.md §11`.

---

## Fuera del MVP — no implementar

Módulo financiero (comisiones, cuotas, balloon) · endpoints de `buses_entregados` · import de Excel · pronta facturación expuesta en API (se calcula y guarda, no se devuelve) · "olvidé mi contraseña". Las tablas pueden existir en el schema; eso no significa que se implementen ahora.

**Notificaciones YA NO está fuera de alcance:** el módulo existe y está en producción (`domain/notificaciones/`, 12 archivos, 4 endpoints, 2 jobs `@Scheduled`, migraciones V22/V28/V34). Se trata como cualquier otro módulo; no lo borres ni te niegues a tocarlo.

**Si parece necesario algo no listado en el PRD, pausa y pregunta. No inventes comportamiento.**

---

## Coordinación con el frontend (repo separado)

- Este repo es dueño de `contrato_api.md` y `matriz_permisos.md`. Si cambian, se comunica al equipo de frontend.
- Todo cambio a un endpoint documentado (breaking o no) se registra en `contrato_api.md §25 Changelog del contrato`, en el mismo PR que lo hace. Sin esa entrada, el cambio de contrato no está terminado.
- El backend debe tener el dominio del frontend en `CORS_ALLOWED_ORIGINS`.
- API versionada en `/api/v1`.
