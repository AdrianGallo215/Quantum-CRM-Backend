# Code review — hallazgos y estado

Registro del code-review de corrección (no seguridad) hecho sobre toda la codebase el 2026-08-05/07, con 7 agentes en paralelo divididos por módulo, más una revisión de seguridad previa aparte. Cada hallazgo cita el archivo:línea reportado por el agente que lo encontró. Las líneas pueden haberse movido desde entonces si el archivo se tocó en un fix posterior.

**Metodología:** cada módulo se revisó contra `docs/reglas_negocio.md`, `docs/contrato_api.md` y `CLAUDE.md` como fuente de verdad. Solo se listan hallazgos con confianza >= 7/10 del agente que los reportó. No incluye hallazgos de seguridad (esos se revisaron y corrigieron aparte, sesión previa: IDOR en `oportunidadesPorContacto` y bypass de revocación en `EmpleadoServiceImpl`).

**Leyenda de estado:**
- ✅ **Corregido** — con test propio, verificado en esta sesión (`./gradlew test ktlintCheck detekt` en verde).
- ⬜ **Pendiente** — sin tocar.
- 🟡 **Parcial** — se corrigió una parte, o se corrigió la desinformación pero no la causa raíz.
- 🗄️ **Pendiente en Supabase** — requiere cambio de esquema, gestionado a mano por el dueño del proyecto.

**Resumen (actualizado tras la Ola 1 de `docs/plan-ejecucion-subagentes.md`):** 28 corregidos · 27 pendientes · 1 parcial · 1 pendiente en Supabase. Sin [Crítico] pendientes. De los [Alto], solo quedan dos abiertos: el de `POST /empresas` insertando NULL en columnas `NOT NULL` (🗄️, bloqueado por una decisión de esquema en Supabase) y el trinquete de Kover (🟡, F3 — a cargo del agente G, que corre después de esta ronda). Todo lo demás que era [Alto] en la ronda anterior (B1, D1, D2, F1, A1, F2, E2) quedó cerrado esta sesión. Plan de ataque en `docs/plan-correccion-code-review.md`.

---

## Módulo: oportunidades (núcleo del pipeline)

### [Alto] `dcto`/`precio_unitario` sin límite de escala — `OportunidadDtos.kt:98-100,131-135`
✅ **Corregido.** Postgres redondeaba `dcto` a `NUMERIC(5,2)` pero el cálculo usaba el valor crudo del request, dejando `monto_total` sin cuadrar con su propia fórmula. Fix: `@field:Digits` en `dcto` y `precioUnitario` en ambos DTOs.

### [Alto] `PUT /oportunidades/:id/contactos/:contacto_id` rechaza el body del contrato — `OportunidadDtos.kt:114-119`
✅ **Corregido.** Reutilizaba `ContactoVinculoRequest` (con `idContacto` obligatorio y `@Positive`), así que el body exacto del contrato (`{"rol_en_oportunidad":"Aprobador"}`) daba 400. Fix: DTO propio `ActualizarRolContactoRequest` solo para este endpoint.

### [Alto] Ningún invariante del núcleo del pipeline tiene test (A1)
✅ **Corregido.** Las reglas ya estaban bien implementadas en `OportunidadServiceImpl.cambiarEstado`; lo que faltaba era la aserción que impidiera borrarlas sin darse cuenta. `OportunidadCambiarEstadoInvariantesTest.kt` (nuevo, 12 tests) cubre: `motivo_cierre` obligatorio al cerrar (`MOTIVO_CIERRE_REQUERIDO`) y que se limpia al retroceder desde `cerrado`; guard de rol en `facturado` (vendedor y jdv no pueden, admin/gerencia/analista sí); `es_retroceso` en `true`/`false` según la dirección del cambio; `cambiar al mismo estado` y `estado desconocido` → `ESTADO_INVALIDO`; el log de estados registra estado anterior y nuevo; `MONTO_NO_EDITABLE` si `actualizar` recibe `monto_total`. `EstadoCarteraServiceTest.kt` (nuevo, 5 tests) cubre `EstadoCarteraService` en aislamiento: facturado gana sobre activa, sin oportunidades el derivado es `null`, etc.

### [Medio] `estado` inválido en `GET /oportunidades` se traga en silencio — `OportunidadServiceImpl.kt:626-633`
⬜ **Pendiente.** `?estado=perdido` (estado que no existe) devuelve 200 con todas las oportunidades, incluidas las cerradas, en vez de 400. Inconsistente con `cambiarEstado`, que sí valida el mismo enum.

### [Medio] `archivosDrive` retiene conexión JDBC durante llamada a Drive — `OportunidadServiceImpl.kt:586-590`
✅ **Corregido** (junto con el fix de resiliencia de Drive — encontrado por el agente como caso adicional no pedido explícitamente).

### [Medio] `cambiarEstado` no bloquea la fila: carrera en transición de estado — `OportunidadServiceImpl.kt:229,261-273`
⬜ **Pendiente.** Dos `PATCH` concurrentes sobre la misma oportunidad pueden dejar dos filas con el mismo `estado_anterior` en el log, corrompiendo el historial que usa la pronta facturación.

### [Medio] `POST /oportunidades/:id/contactos` sobre vínculo existente hace UPDATE silencioso y responde 201 — `OportunidadServiceImpl.kt:369-374`
⬜ **Pendiente.** Vincular dos veces el mismo contacto sobreescribe el rol anterior sin aviso y responde `201 Created` como si fuera nuevo.

---

## Módulo: empresas + contactos

### [Alto] `GET /contactos?q=...` devuelve 500 siempre — `ContactoServiceImpl.kt:269`
✅ **Corregido.** La Specification pedía `root.get("tlf1")`/`"tlf2"` pero la entidad declara `tlf_1`/`tlf_2`. Como los tres predicados iban en un solo `cb.or`, moría también la búsqueda por nombre.

### [Alto] `POST /empresas` inserta NULL en columnas `NOT NULL` — `EmpresaServiceImpl.kt:126`
🗄️ **Pendiente en Supabase.** `actividad_econ`, `estado_sunat`, `condicion_sunat`, `direccion_fiscal` son `NOT NULL` en `V6__create_empresas.sql` pero nullable en DTO y entidad. `ddl-auto=validate` no lo detecta al arrancar; explota en el primer `POST` con body mínimo. SQL ya entregado para aplicar a mano.

### [Alto] RUC del mismo vendedor devuelve 409 en vez de la empresa con 200 — `EmpresaServiceImpl.kt:118` (B1)
✅ **Corregido.** `EmpresaServiceImpl.crear` ahora usa `EmpresaRepository.findByRuc` (antes código muerto): si el RUC existe y pertenece al mismo vendedor, devuelve la empresa existente con `200 OK` sin insertar fila ni crear carpeta de Drive (`AltaEmpresaResultado.creada = false`); si pertenece a otro vendedor, sigue lanzando `RucDuplicadoException` → `409 RUC_DUPLICADO`, ahora con un mensaje que no culpa al usuario. El camino `crearSinCarpetaDrive` (usado por el import CSV) queda sin tocar a propósito: sigue lanzando siempre ante duplicado, porque el import construye su reporte de errores a partir de esa excepción. `contrato_api.md §8` se actualizó (tareas F.1/F.2 del plan de ejecución) para documentar el 200/201 y el mensaje nuevo, y dejó de contradecir `reglas_negocio.md §2.1`.

**Bug real encontrado en el smoke test end-to-end (2026-08-10), invisible a los tests unitarios:** la rama "mismo vendedor → 200" hacía `existente.conContactos()` fuera de transacción (`alta()` no lleva `@Transactional` a propósito, por la llamada a Drive), y `conContactos()` lee `segmentos`, una colección `LAZY`. Con un `Empresa` real de Hibernate eso revienta con `LazyInitializationException` → 500; con el mock de los tests unitarios (`Empresa` en memoria, no un proxy de Hibernate) nunca se manifestaba — los 7 tests de A.3/A.4/A.5 pasaban en verde sobre un endpoint roto. Encontrado corriendo el flujo real contra Postgres con `curl`, no por revisión de código. Fix: `EmpresaRepository.findByRuc` pasó a `@Query` con `left join fetch e.segmentos`, así la empresa reutilizada llega con `segmentos` ya inicializado y no necesita sesión abierta. Reverificado con el mismo smoke test: 16/16 en verde. Script en `scripts/smoke-test-b1-d1.sh` (crea y borra sus propios datos de prueba; requiere backend + Postgres corriendo).

### [Alto] `PUT /empresas/:id` exige `ruc` aunque el contrato dice "todos opcionales" — `EmpresaDtos.kt:100`
✅ **Corregido.** `ruc` era el único campo no-nulo sin default; cualquier edición parcial sin reenviar el RUC daba 400.

### [Medio] Reasignar vendedor de empresa en Cartera Maestra viola el CHECK de V27 — `EmpresaServiceImpl.kt:303`
⬜ **Pendiente.** `PATCH .../vendedor` sobre una empresa con `en_cartera_maestra = true` revienta con `DataIntegrityViolationException` → 409 genérico sin explicar por qué. También le falta fijar `updatedBy`.

### [Medio] Llamada a Google Drive dentro de transacción y bajo lock pesimista — `EmpresaServiceImpl.kt:209`
✅ **Corregido.**

### [Medio] N+1 en `GET /contactos`: hasta ~301 consultas por página — `ContactoController.kt:47`
⬜ **Pendiente.** `oportunidadesDeContacto.contar(...)` se llama una vez por fila del listado en vez de por lote; existe ya `findByIdIdContactoIn` sin usar en producción.

### [Medio] Eliminar empresa no elimina la carpeta del Drive
⬜ **Pendiente.** 

### [Medio] `estado_cartera` inválido en filtro se descarta en silencio — `EmpresaServiceImpl.kt:463`
⬜ **Pendiente.** Mismo patrón que el de oportunidades: `?estado_cartera=perdido` devuelve todo sin filtrar en vez de 400.

---

## Módulo: eventos + tareas + notificaciones (incl. jobs)

### [Alto] Reprogramar una tarea o evento la deja sin recordatorios para siempre — `TareaServiceImpl.kt:190`, `EventoServiceImpl.kt:166`
✅ **Corregido.** La clave de dedup `(origen, id_origen, umbral)` sigue sin incluir la fecha (eso sería cambio de esquema), pero ahora se reinicia: `NotificacionService.reiniciarRecordatorios(origen, idOrigen)` borra las filas del origen, y `actualizar` de tareas y eventos la llama **solo cuando la fecha se mueve de verdad** — reiniciar en cada edición reenviaría recordatorios ya entregados. La llamada va dentro de la transacción de la reprogramación: si esta se revierte, el dedup queda intacto. Cruzar a `notificaciones` se hace por su interfaz pública + el enum `OrigenRecordatorio`, así que ArchUnit sigue en verde.

6 tests unitarios nuevos (3 en tareas, 3 en eventos: reprograma / edita sin mover la fecha / reenvía la misma fecha). Se añadió también un test `@Tag("integration")` para la query derivada `deleteByOrigenAndIdOrigen` en `RecordatorioEnviadoRepositoryIntegrationTest` — **no ejecutado en esta máquina** (Testcontainers/Docker 29), pendiente de CI.

### [Alto] Fechas `Instant` en request pero `LocalDateTime` en respuesta — `TareaDtos.kt:27,54`
✅ **Corregido** (junto con el barrido completo de fechas en toda la API — ver el mensaje enviado al frontend).

### [Medio] El job de recordatorios escanea la tabla entera cada hora, con query por ítem — `TareaServiceImpl.kt:309`
⬜ **Pendiente.** Sin cota temporal en la query; cada tarea vencida genera una consulta `exists` por hora indefinidamente, aunque su recordatorio se enviara hace meses.

### [Medio] Recordatorios de eventos usan `LocalDate.now()` en UTC pero `fecha_estimada` es calendario peruano — `RecordatorioJob.kt:65`
⬜ **Pendiente.** A las 19:00 de Lima la fecha UTC ya es la del día siguiente; el vendedor puede recibir "evento vencido" con 5 horas del día hábil aún por delante, y por el dedup permanente nunca se repite.

### [Medio] `contrato_api.md §19` desactualizado: los enums de notificación crecieron — `NotificacionEnums.kt:15`
✅ **Corregido (solo documentación).** `TipoNotificacion` tiene 16 valores reales (9 del set original + `solicitud_creada`/`solicitud_aprobada`/`solicitud_denegada` + `meta_propuesta`/`meta_aprobada`/`meta_rechazada`/`meta_modificada`, usados por `SolicitudServiceImpl` y `MetaVentaServiceImpl` respectivamente). `contrato_api.md §19` ahora lista los 16. De paso se corrigió `entidad_tipo`: el contrato solo mencionaba `oportunidad`/`empresa`, pero `EntidadNotificacion` tiene 4 valores (`solicitud`, `meta_venta` también). No se tocó el enum ni ningún archivo `.kt`.

### [Medio] La invariante #4 de CLAUDE.md (eventos no cambian estado) no tiene test que la proteja — `EventoServiceImplTest.kt:138`
⬜ **Pendiente.** Solo existe el test negativo; no hay ninguno que verifique el camino positivo (`sugerencia.dispara = true`) ni que afirme `verify(exactly = 0) { oportunidadService.cambiarEstado(...) }`.

### [Medio] Umbrales de `RecordatorioJob` solo testeados en el caso trivial — `RecordatorioJobTest.kt:39`
⬜ **Pendiente.** Sin cobertura de los bordes reales (24h ±1min, el propio día del evento, destino nulo). `LimpiezaNotificacionesJobTest.kt:18` afirma solo sobre el argumento capturado del mock, no sobre el criterio real de borrado.

---

## Módulo: empleados + config/security + shared

### [Alto] `PoliticaDescuento` fail-open para rol `otro` y roles desconocidos — `PoliticaDescuento.kt:18-23`
✅ **Corregido.** `else -> null` significaba "sin límite"; ahí caían `otro` y cualquier string de rol no reconocido.

### [Alto] `PUT /empleados/:id` no valida nada — `EmpleadoController.kt:61`
✅ **Corregido.** Podía dejar una cuenta permanentemente sin acceso (`{"email":"pepe"}` → 200, pero el login sí valida formato).

### [Alto] `requiere_cambio_contrasena` se enciende al crear y no hay forma de apagarlo — `EmpleadoServiceImpl.kt:83` (D1)
✅ **Corregido.** Endpoint nuevo `POST /auth/cambiar-contrasena` (único de `/auth/**` que exige autenticación): valida `password_actual` contra el hash vigente (`401 CREDENCIALES_INVALIDAS` si no coincide), rechaza que la nueva sea igual a la actual (`400 VALIDACION`, `field: "password_nueva"`), y al persistir el nuevo hash pone `requiereCambioContrasena = false`. Documentado en `contrato_api.md §6`.

### [Alto] La regla B1.4 y el CRUD de empleados no tienen test a nivel HTTP (D2)
✅ **Corregido.** `EmpleadoCrudControllerWebMvcTest.kt` (nuevo, 11 tests): `GET /empleados` (lista y 403 con rol `vendedor`), `POST /empleados` happy path (201) y `EMAIL_DUPLICADO` (409), `PUT /empleados/:id` con email malformado (400, no llega al servicio), con nombres en blanco (400), con un solo campo (actualización parcial dejando el resto en `null`), con email válido (sí llega al servicio); y los `@PreAuthorize` de `POST`/`PUT`/`PATCH activo` devolviendo 403 con rol `gerencia`.

### [Medio] La guarda de último admin quedó inalcanzable tras el fix de revocación — `EmpleadoServiceImpl.kt:188-192`
⬜ **Pendiente (informacional).** No es un bug — es código muerto tras el fix de seguridad de la sesión previa (`verificarSolicitanteVigente` ya garantiza que siempre queda otro admin). Vale la pena documentarlo o retirarlo para que nadie lo "arregle" a ciegas.

### [Medio] `LoginRateLimiter` puede evaporarse por desalojo LRU — `LoginRateLimiter.kt:44`
⬜ **Pendiente.** El bloqueo depende de que la clave atacada se siga consultando; un flood con 10.000 emails distintos puede desalojarla antes de que expire la ventana de 15 min.

### [Medio] `Paginacion.meta` (fabrica `total_pages` para toda la API paginada) sin test propio — `Paginacion.kt:86-93`
⬜ **Pendiente.** La aritmética se verificó correcta manualmente, pero ningún test la ejercita como sujeto — un off-by-one futuro rompería la paginación de 6+ módulos en silencio.

### [Medio] `POST /auth/refresh` devuelve 404 en vez de 401 si el empleado del token fue borrado — `AuthController.kt:80`
⬜ **Pendiente.**

---

## Módulo: solicitudes + metas de venta + prospección + inicio + reportes

### [Crítico] `GET /reportes/ventas` y `/reportes/equipo` pierden ventas si falta el log de estados — `ReporteService.kt:86,280`
✅ **Corregido.** Derivaban la fecha de facturación de un `JOIN LATERAL` sobre el log de estados, que actuaba como INNER JOIN y descartaba en silencio toda venta sin fila en el log. Unificado con `InicioDao`, que ya usaba `oportunidades.facturado_en` (la fuente documentada en V33).

### [Alto] `hitosOcurridos` puede lanzar NPE si un hito `ocurrido` no tiene `fecha_ocurrencia` — `ProspeccionDao.kt:70`
✅ **Corregido.** Confirmado que el CHECK `chk_evento_fecha_ocurrencia` de V14 (`estado = 'ocurrido' OR fecha_ocurrencia IS NULL`) permite ese dato, y que la caída alcanza a `/inicio` porque `InicioService` llama a `prospeccionService.resumen()`.

El fix **no es solo un `?.`**: el mapa pasó a `Map<Pair<Long, Long>, LocalDateTime?>` y el avance del hito lo marca ahora `containsKey`, no `fecha != null`. Descartar el hito por no tener fecha habría sido una segunda regresión silenciosa — el evento ocurrió, solo se desconoce cuándo, y contarlo como no cumplido falsearía `checkpoints_completados` y `lista_para_convertir`. 6 tests nuevos entre `ProspeccionDaoTest` (mapeo de filas, mock en la frontera del driver) y `ProspeccionServiceImplTest` (comportamiento del embudo), en un módulo que antes tenía cobertura cero.

### [Alto] `/reportes/prospeccion` infla `ingresadas` y subestima la tasa de conversión — `ReporteService.kt:399`
✅ **Corregido.** El criterio de entrada al embudo no filtraba por `estado='ocurrido'` ni `id_oportunidad IS NULL`, a diferencia del conteo de hitos.

### [Alto] Cero cobertura sobre el SQL crudo de agregación (`ReporteService`, `ProspeccionDao`, `InicioDao`, `OportunidadConsultas` — ~900 líneas) (E2)
✅ **Corregido** (para `ReporteService`, el bloque más grande; `ProspeccionDao` ya se cubrió en la sesión previa). Se añadieron los tests unitarios de los helpers de dinero puros (`sumMonto`, `promedio`, `porcentaje`) y, sobre todo, `ReporteServiceSqlIntegrationTest.kt` (nuevo, 6 tests `@Tag("integration")` contra Postgres real vía Testcontainers, porque mockear el `JdbcTemplate` solo probaría el mock): rango de fechas de `ventas` con una operación dentro y otra fuera; regresión del hallazgo [Crítico] ya corregido (una oportunidad facturada sin fila en `oportunidad_estados_log` debe seguir contando, la fuente real es `oportunidades.facturado_en`); `equipo` no mezcla cifras entre dos vendedores; `prospeccion` exige `estado = 'ocurrido'` Y `id_oportunidad IS NULL` para contar como ingresada; `descuentos` documenta (sin corregir) el comportamiento actual frente a `NULL`. **No se pudieron ejecutar en esta máquina** (bloqueo de Testcontainers/Docker 29, ver memoria del proyecto) — las aserciones se derivaron leyendo `ReporteService.kt` línea por línea; se verificarán en CI vía `integrationTest`. `InicioDao` y `OportunidadConsultas` siguen sin test propio.

### [Medio] `/reportes/descuentos` mezcla "sin descuento" como 0% y descarta las cerradas — `ReporteService.kt:474,481`
⬜ **Pendiente.** Criterio de NULL inconsistente con `/reportes/ventas`: dos endpoints, dos respuestas distintas para "cuál es el descuento promedio".

### [Medio] Los índices de hito del embudo se calculan por `ROW_NUMBER()` y se desalinean al tocar el catálogo — `ReporteService.kt:425`
⬜ **Pendiente.** Un cuarto hito creado en `catalogo_eventos` nunca aparecería en el reporte; desactivar el hito 2 cambiaría retroactivamente el significado de `hito_2_completado`.

### [Medio] `dias_sin_actividad` usa `updated_at` y admite fechas futuras — `ProspeccionDao.kt:88`
⬜ **Pendiente.** Puede dar `dias_sin_actividad = 0` durante meses en tareas completadas con `fecha_ejecucion` futura, ocultando empresas abandonadas del indicador `requieren_atencion`.

### [Medio] `POST /metas-venta` de gerencia sobre una meta nueva notifica como "modificó" — `MetaVentaServiceImpl.kt:64`
⬜ **Pendiente.** No corrompe números, pero envía un mensaje factualmente falso al vendedor y a gerencia.

---

## Módulo: catálogos (modelos, financiadoras, catalogoeventos) + Drive + import CSV + mantenimiento

### [Alto] El import CSV llama a Drive una vez por fila — `ImportCsvTempServiceImpl.kt:50`
✅ **Corregido.**

### [Alto] El bloqueo pesimista se mantiene durante la llamada de red a Drive — `EmpresaServiceImpl.kt:206-213`, `OportunidadServiceImpl.kt:571-584`
✅ **Corregido.**

### [Medio] El parser CSV no soporta saltos de línea dentro de campos entrecomillados — `ImportCsvTempServiceImpl.kt:35,106-129`
⬜ **Pendiente.** Un campo exportado por Excel con salto de línea interno produce dos filas fantasma con errores confusos, y con una columna de más puede corromper la razón social en silencio.

### [Medio] La primera línea siempre se descarta como cabecera, sin validarla — `ImportCsvTempServiceImpl.kt:44`
⬜ **Pendiente.** Un archivo exportado sin fila de títulos pierde su primera empresa en silencio.

### [Medio] Los números de fila reportados no coinciden con el archivo si hay líneas en blanco — `ImportCsvTempServiceImpl.kt:37,51`
⬜ **Pendiente.** El reporte de errores del import —su único entregable útil— queda inutilizable para corregir el origen.

### [Medio] Cambiar el código de un modelo (o nombre de evento) a uno existente da `CONFLICTO_DATOS` genérico — `ModeloServiceImpl.kt:51`, `CatalogoEventoServiceImpl.kt:53`
⬜ **Pendiente.** Se valida en `crear` pero no en `actualizar`; el frontend recibe un código de error distinto al de creación y sin `field`.

### [Medio] Se puede dejar el sistema sin ninguna financiadora default — `FinanciadoraServiceImpl.kt:59`
⬜ **Pendiente.** El caso "más de una default" está bien cubierto; el caso "pasar de una a cero" no. Rompe la creación de oportunidades sin `id_financiadora` explícito hasta que alguien lo note.

### [Medio] Cobertura cero en modelos, financiadoras, catalogoeventos (F2)
✅ **Corregido.** Tres archivos de test nuevos: `ModeloServiceImplTest.kt` (6 tests: código duplicado → `ConflictoException`, sin aplicaciones/aplicaciones `null` → `ModeloSinAplicacionesException`, happy path, `actualizar` de id inexistente → `NoEncontradoException`, `listar` ordenado por código); `FinanciadoraServiceImplTest.kt` (6 tests: marcar `es_default` con otra ya default → `ConflictoException` en `crear` y en `actualizar`, crear sin marcar default persiste sin validar unicidad, desmarcar la default deja el sistema sin ninguna — documenta el hallazgo abierto de abajo sin corregirlo, `listar`, `porId` inexistente); `CatalogoEventoServiceImplTest.kt` (8 tests: nombre duplicado, evento que dispara cambio de estado sin estado destino → `ValidacionException`, happy path, `hitosProspeccion` filtra y ordena, `todosPorId` indexa, `listar` con/sin `etapaAsociada`, `actualizar` de id inexistente).

---

## Migraciones y salud de la suite de tests

### [Crítico] `OrigenLead.otro` existe en Kotlin pero no en el enum de Postgres — `Enums.kt:65`
✅ **Corregido.** Migración `V37__add_otro_origen_lead.sql` creada (idempotente con `IF NOT EXISTS`, porque ya se aplicó a mano en Supabase antes de crear la migración).

### [Alto] Dos tests de Kover verdes "prueban" un umbral que el build no exige — `QualityGatesConfigTest.kt:49-62`
✅ **Corregido.** Hacían `contains("75")`/`contains("90")` sobre el texto de `build.gradle.kts`, que pasaban por comentarios mientras el gate real era `minBound(63)`/`minBound(58)`. Reescritos como trinquete real que sí se pone rojo si el `minBound` baja.

### [Alto] `id_modelo` es `NOT NULL` en la tabla y nullable en la entidad — `Oportunidad.kt:38-39` (F1)
✅ **Corregido.** Se tomó la decisión de alinear el código con el esquema (la columna ya era `NOT NULL` en Supabase, no al revés): `Oportunidad.idModelo` pasó de `Long?` a `Long` no-nulo. El código de lectura que lo trataba como opcional en varios puntos dejó de necesitar esos chequeos.

### [Alto] 5 módulos completos sin un solo test (reportes, prospección, catalogoeventos, financiadoras, modelos) (F2)
✅ **Corregido.** `prospección` ya se cerró en la sesión previa (`ProspeccionDaoTest`, `ProspeccionServiceImplTest`, ver el fix de `hitosOcurridos` más abajo). Esta ronda cierra el resto: `modelos`, `financiadoras`, `catalogoeventos` (ver el hallazgo de arriba, mismo módulo) y `reportes` (ver E2, más arriba) — los 5 módulos tienen ahora al menos un archivo de test.

### [Alto] El trinquete de Kover está 32 puntos por debajo de lo que exige `TESTING-backend.md` (63%/58% real vs. 75%/90% objetivo)
🟡 **Parcial.** Se corrigió la desinformación (el CI y los comentarios ya no afirman 75/90 como si fuera el gate real). La brecha de cobertura en sí — los módulos sin test de arriba — sigue abierta.

### [Medio] Cuatro tests de "configuración" no ejercitaban código de producción — `CiPipelineConfigTest.kt`, `SecurityScanWorkflowConfigTest.kt`, `LocalEnvironmentConfigTest.kt`
✅ **Corregido.** Evaluados uno por uno: `LocalEnvironmentConfigTest` ya estaba bien y no se tocó; los otros dos tenían la misma debilidad (`contains` sobre substrings triviales) y se corrigieron para parsear YAML real en vez de borrarse, porque documentaban invariantes legítimos.

### Regla 12 de CLAUDE.md ("ArchUnit lo verifica") — ArchUnit no existía en el proyecto
✅ **Corregido.** Se instaló `archunit-junit5` y se escribió la regla real sobre bytecode. Verificado explícitamente: **0 violaciones** — la codebase ya respetaba la frontera entre módulos, solo que nadie lo comprobaba.

---

## Otros hallazgos (encontrados durante los fixes, fuera del alcance original de cada agente)

### [Medio] `error.field` se devuelve en camelCase, no snake_case — `GlobalExceptionHandler.kt:60` (y otras líneas del mismo patrón)
⬜ **Pendiente — verificado de nuevo el 2026-08-07, sigue así.** `fieldError.field` es el nombre de la propiedad Kotlin, sin pasar por la estrategia `SNAKE_CASE` de Jackson. Afecta a todo 400 `VALIDACION` con campo compuesto (`id_contacto`, `precio_unitario`, `rol_en_oportunidad`, etc.) — el frontend no puede casar el `field` recibido con el que envió.

### [Bajo] `EmpresaDriveControllerTest.kt:105` es un falso positivo — usa `standaloneSetup`
⬜ **Pendiente.** No carga el `ObjectMapper` de la app, así que afirma `driveFolderId` en camelCase cuando el contrato real exige `drive_folder_id`. El test pasaría igual si la serialización real se rompiera.

### [Medio] La garantía de que las columnas `TIMESTAMP` contienen UTC depende únicamente de `ENV TZ=UTC` en el Dockerfile
⬜ **Pendiente.** Cualquier escritura hecha fuera de ese contenedor (`bootRun` local, un script de mantenimiento corrido en Lima) mete hora local en columnas que el código ahora asume UTC sin comprobarlo. Lo robusto sería `TIMESTAMPTZ` en el esquema o `Instant` en las entidades — ambas cosas tocan a Supabase, no se aplicó ninguna.

---

## Riesgo residual introducido por el fix de Drive (D1)

**No es un hallazgo del review original** — lo señaló el propio agente que hizo el fix, honestamente, en su informe:

`POST /empresas` ensancha ligeramente la ventana entre el chequeo de RUC duplicado y el insert (por la latencia de Drive, que ahora ocurre entre ambos pasos, ~300ms). Dos `POST` **simultáneos** con el mismo RUC podrían pasar ambos el chequeo y el segundo chocaría con la constraint única, dando 500 en vez de 409. La ventana ya existía antes del fix (el chequeo no es atómico con el insert bajo `READ COMMITTED`) pero era mínima; ahora es un poco mayor. No se blindó con un catch de `DataIntegrityViolationException` a propósito, porque el caso frecuente de duplicado (reintento de CSV, doble submit) es secuencial y sigue devolviendo 409 correctamente.
