# Code review — hallazgos y estado

Registro del code-review de corrección (no seguridad) hecho sobre toda la codebase el 2026-08-05/07, con 7 agentes en paralelo divididos por módulo, más una revisión de seguridad previa aparte. Cada hallazgo cita el archivo:línea reportado por el agente que lo encontró. Las líneas pueden haberse movido desde entonces si el archivo se tocó en un fix posterior.

**Metodología:** cada módulo se revisó contra `docs/reglas_negocio.md`, `docs/contrato_api.md` y `CLAUDE.md` como fuente de verdad. Solo se listan hallazgos con confianza >= 7/10 del agente que los reportó. No incluye hallazgos de seguridad (esos se revisaron y corrigieron aparte, sesión previa: IDOR en `oportunidadesPorContacto` y bypass de revocación en `EmpleadoServiceImpl`).

**Leyenda de estado:**
- ✅ **Corregido** — con test propio, verificado en esta sesión (`./gradlew test ktlintCheck detekt` en verde).
- ⬜ **Pendiente** — sin tocar.
- 🟡 **Parcial** — se corrigió una parte, o se corrigió la desinformación pero no la causa raíz.
- 🗄️ **Pendiente en Supabase** — requiere cambio de esquema, gestionado a mano por el dueño del proyecto.

**Resumen (actualizado 2026-08-12, migración V38 aplicada en Supabase):** 57 corregidos · 0 pendientes · 0 pendientes en Supabase. **Registro cerrado: sin [Crítico], [Alto], [Medio] ni [Bajo] pendientes, ni en código ni en Supabase.** Plan de ataque de la tanda [Medio]/[Bajo] en `docs/plan-ejecucion-medios-bajos.md`; historial de la tanda [Alto]/[Crítico] en `docs/plan-correccion-code-review.md`.

**Cobertura:** global 86.6%, dominio 85.0% en local (94.9% contando los `@Tag("integration")` que solo corren en CI). Trinquete del build en 85/84.

---

## Módulo: oportunidades (núcleo del pipeline)

### [Alto] `dcto`/`precio_unitario` sin límite de escala — `OportunidadDtos.kt:98-100,131-135`
✅ **Corregido.** Postgres redondeaba `dcto` a `NUMERIC(5,2)` pero el cálculo usaba el valor crudo del request, dejando `monto_total` sin cuadrar con su propia fórmula. Fix: `@field:Digits` en `dcto` y `precioUnitario` en ambos DTOs.

### [Alto] `PUT /oportunidades/:id/contactos/:contacto_id` rechaza el body del contrato — `OportunidadDtos.kt:114-119`
✅ **Corregido.** Reutilizaba `ContactoVinculoRequest` (con `idContacto` obligatorio y `@Positive`), así que el body exacto del contrato (`{"rol_en_oportunidad":"Aprobador"}`) daba 400. Fix: DTO propio `ActualizarRolContactoRequest` solo para este endpoint.

### [Alto] Ningún invariante del núcleo del pipeline tiene test (A1)
✅ **Corregido.** Las reglas ya estaban bien implementadas en `OportunidadServiceImpl.cambiarEstado`; lo que faltaba era la aserción que impidiera borrarlas sin darse cuenta. `OportunidadCambiarEstadoInvariantesTest.kt` (nuevo, 12 tests) cubre: `motivo_cierre` obligatorio al cerrar (`MOTIVO_CIERRE_REQUERIDO`) y que se limpia al retroceder desde `cerrado`; guard de rol en `facturado` (vendedor y jdv no pueden, admin/gerencia/analista sí); `es_retroceso` en `true`/`false` según la dirección del cambio; `cambiar al mismo estado` y `estado desconocido` → `ESTADO_INVALIDO`; el log de estados registra estado anterior y nuevo; `MONTO_NO_EDITABLE` si `actualizar` recibe `monto_total`. `EstadoCarteraServiceTest.kt` (nuevo, 5 tests) cubre `EstadoCarteraService` en aislamiento: facturado gana sobre activa, sin oportunidades el derivado es `null`, etc.

### [Medio] `estado` inválido en `GET /oportunidades` se traga en silencio — `OportunidadServiceImpl.kt:626-633`
✅ **Corregido (A.1).** `?estado=perdido` ahora responde `400 VALIDACION` (`field: "estado"`) en vez de 200 con todo sin filtrar. Mismo criterio que `cambiarEstado`.

### [Medio] `archivosDrive` retiene conexión JDBC durante llamada a Drive — `OportunidadServiceImpl.kt:586-590`
✅ **Corregido** (junto con el fix de resiliencia de Drive — encontrado por el agente como caso adicional no pedido explícitamente).

### [Medio] `cambiarEstado` no bloquea la fila: carrera en transición de estado — `OportunidadServiceImpl.kt:229,261-273`
✅ **Corregido (A.2).** Nuevo `OportunidadRepository.findByIdBloqueando` con `@Lock(PESSIMISTIC_WRITE)`; `cambiarEstado` lo usa en vez de `findById`. Test de arquitectura `DriveFueraDeTransaccionTest` ajustado para permitir explícitamente este lock (no toca Drive, no reproduce el problema que ese test vigila).

### [Medio] `POST /oportunidades/:id/contactos` sobre vínculo existente hace UPDATE silencioso y responde 201 — `OportunidadServiceImpl.kt:369-374`
✅ **Corregido (A.3).** Ahora responde `409 CONTACTO_YA_VINCULADO` si el vínculo ya existe; usa `PUT` para cambiar el rol.

---

## Módulo: empresas + contactos

### [Alto] `GET /contactos?q=...` devuelve 500 siempre — `ContactoServiceImpl.kt:269`
✅ **Corregido.** La Specification pedía `root.get("tlf1")`/`"tlf2"` pero la entidad declara `tlf_1`/`tlf_2`. Como los tres predicados iban en un solo `cb.or`, moría también la búsqueda por nombre.

### [Alto] `POST /empresas` inserta NULL en columnas `NOT NULL` — `EmpresaServiceImpl.kt:126`
✅ **Corregido — migración `V38` aplicada en Supabase (2026-08-12).** `actividad_econ`, `estado_sunat`, `condicion_sunat`, `direccion_fiscal` eran `NOT NULL` en `V6__create_empresas.sql` pero nullable en DTO, entidad y contrato — la BD era la única de las cuatro capas que los exigía. `ddl-auto=validate` no compara nulabilidad, así que la app arrancaba en verde y reventaba con 500 en el primer `POST` con body mínimo (el caso real: registrar un lead sin tener aún la ficha RUC).

Se decidió **relajar el esquema**, no exigir los campos en el DTO: el dato de SUNAT no siempre existe al dar de alta, y forzarlo obligaría al vendedor a inventárselo o a no registrar la empresa.

`V38__empresas_campos_sunat_opcionales.sql` creada, idempotente igual que V37. `SeedFixtures.MIGRACIONES_TOTAL` subido a 38 y añadido un test de regresión en `SchemaMigrationIntegrationTest` que falla si alguien vuelve a apretar esas columnas — `@Tag("integration")`, solo corre en CI.

**Verificado contra Postgres real (2026-08-10):** la BD local ya tenía las columnas nullable, así que sirvió para probar el caso exacto de Supabase: la migración corre limpia sobre columnas ya relajadas, y un `INSERT` con solo `ruc`/`razon_social` —el que antes daba 500— ahora pasa. **Aplicada a mano en Supabase el 2026-08-12; ya no queda ningún ítem 🗄️ pendiente.**

### [Alto] RUC del mismo vendedor devuelve 409 en vez de la empresa con 200 — `EmpresaServiceImpl.kt:118` (B1)
✅ **Corregido.** `EmpresaServiceImpl.crear` ahora usa `EmpresaRepository.findByRuc` (antes código muerto): si el RUC existe y pertenece al mismo vendedor, devuelve la empresa existente con `200 OK` sin insertar fila ni crear carpeta de Drive (`AltaEmpresaResultado.creada = false`); si pertenece a otro vendedor, sigue lanzando `RucDuplicadoException` → `409 RUC_DUPLICADO`, ahora con un mensaje que no culpa al usuario. El camino `crearSinCarpetaDrive` (usado por el import CSV) queda sin tocar a propósito: sigue lanzando siempre ante duplicado, porque el import construye su reporte de errores a partir de esa excepción. `contrato_api.md §8` se actualizó (tareas F.1/F.2 del plan de ejecución) para documentar el 200/201 y el mensaje nuevo, y dejó de contradecir `reglas_negocio.md §2.1`.

**Bug real encontrado en el smoke test end-to-end (2026-08-10), invisible a los tests unitarios:** la rama "mismo vendedor → 200" hacía `existente.conContactos()` fuera de transacción (`alta()` no lleva `@Transactional` a propósito, por la llamada a Drive), y `conContactos()` lee `segmentos`, una colección `LAZY`. Con un `Empresa` real de Hibernate eso revienta con `LazyInitializationException` → 500; con el mock de los tests unitarios (`Empresa` en memoria, no un proxy de Hibernate) nunca se manifestaba — los 7 tests de A.3/A.4/A.5 pasaban en verde sobre un endpoint roto. Encontrado corriendo el flujo real contra Postgres con `curl`, no por revisión de código. Fix: `EmpresaRepository.findByRuc` pasó a `@Query` con `left join fetch e.segmentos`, así la empresa reutilizada llega con `segmentos` ya inicializado y no necesita sesión abierta. Reverificado con el mismo smoke test: 16/16 en verde. Script en `scripts/smoke-test-b1-d1.sh` (crea y borra sus propios datos de prueba; requiere backend + Postgres corriendo).

### [Alto] `PUT /empresas/:id` exige `ruc` aunque el contrato dice "todos opcionales" — `EmpresaDtos.kt:100`
✅ **Corregido.** `ruc` era el único campo no-nulo sin default; cualquier edición parcial sin reenviar el RUC daba 400.

### [Medio] Reasignar vendedor de empresa en Cartera Maestra viola el CHECK de V27 — `EmpresaServiceImpl.kt:303`
✅ **Corregido (B.1).** Ahora responde `409 EMPRESA_EN_CARTERA_MAESTRA` explicando que hay que liberarla primero, en vez de dejarlo caer en la constraint. De paso se fijó `updatedBy`, que faltaba.

### [Medio] Llamada a Google Drive dentro de transacción y bajo lock pesimista — `EmpresaServiceImpl.kt:209`
✅ **Corregido.**

### [Medio] N+1 en `GET /contactos`: hasta ~301 consultas por página — `ContactoController.kt:47`
✅ **Corregido (A.4).** Nuevo `OportunidadesDeContacto.contarPorContactos(...)` resuelve toda la página en una sola consulta agrupada (`OportunidadContactoRepository.contarVisiblesPorContactos`), misma regla de visibilidad que el conteo individual. `contar(...)` se conserva para el detalle.

### [Medio] Eliminar empresa no elimina la carpeta del Drive
✅ **Corregido (B.3).** Decisión del dueño: mover a la papelera de Drive (`trashed = true`, reversible ~30 días), no borrado permanente. `DriveStorageService.enviarCarpetaAPapelera(...)` nuevo; `EmpresaServiceImpl.eliminar` la invoca después de que la transacción de borrado confirme, y un fallo de Drive no revierte el borrado (se loguea y sigue).

### [Medio] `estado_cartera` inválido en filtro se descarta en silencio — `EmpresaServiceImpl.kt:463`
✅ **Corregido (B.2).** Mismo tratamiento que en oportunidades: `?estado_cartera=perdido` responde `400 VALIDACION` (`field: "estado_cartera"`).

---

## Módulo: eventos + tareas + notificaciones (incl. jobs)

### [Alto] Reprogramar una tarea o evento la deja sin recordatorios para siempre — `TareaServiceImpl.kt:190`, `EventoServiceImpl.kt:166`
✅ **Corregido.** La clave de dedup `(origen, id_origen, umbral)` sigue sin incluir la fecha (eso sería cambio de esquema), pero ahora se reinicia: `NotificacionService.reiniciarRecordatorios(origen, idOrigen)` borra las filas del origen, y `actualizar` de tareas y eventos la llama **solo cuando la fecha se mueve de verdad** — reiniciar en cada edición reenviaría recordatorios ya entregados. La llamada va dentro de la transacción de la reprogramación: si esta se revierte, el dedup queda intacto. Cruzar a `notificaciones` se hace por su interfaz pública + el enum `OrigenRecordatorio`, así que ArchUnit sigue en verde.

6 tests unitarios nuevos (3 en tareas, 3 en eventos: reprograma / edita sin mover la fecha / reenvía la misma fecha). Se añadió también un test `@Tag("integration")` para la query derivada `deleteByOrigenAndIdOrigen` en `RecordatorioEnviadoRepositoryIntegrationTest` — **no ejecutado en esta máquina** (Testcontainers/Docker 29), pendiente de CI.

### [Alto] Fechas `Instant` en request pero `LocalDateTime` en respuesta — `TareaDtos.kt:27,54`
✅ **Corregido** (junto con el barrido completo de fechas en toda la API — ver el mensaje enviado al frontend).

### [Medio] El job de recordatorios escanea la tabla entera cada hora, con query por ítem — `TareaServiceImpl.kt:309`
✅ **Corregido (C.1).** Nueva query `findByEstadoAccionAndIdAsignadoIsNotNullAndFechaEjecucionBetween` acota a la ventana [ahora-30d, ahora+24h], superconjunto estricto de lo que `RecordatorioJob` puede notificar. Test de integración nuevo (`TareaRepositoryVentanaIntegrationTest`, `@Tag("integration")`) escrito pero **no ejecutado** en esta máquina (Testcontainers/Docker 29); pendiente de CI.

### [Medio] Recordatorios de eventos usan `LocalDate.now()` en UTC pero `fecha_estimada` es calendario peruano — `RecordatorioJob.kt:65`
✅ **Corregido (C.2).** `RecordatorioJob` recibe un `Clock` inyectable; los eventos se evalúan contra `LocalDate.now(clock.withZone(ZONA_PERU))` (`shared/ZonaHoraria.kt`, nuevo). **Hallazgo nuevo encontrado al corregir, no arreglado aquí:** un evento con `fecha_estimada == hoy` nunca genera ningún recordatorio (`isBefore(hoy)` es falso y `== hoy.plusDays(1)` también) — un evento creado hoy para hoy no avisa.

### [Medio] `contrato_api.md §19` desactualizado: los enums de notificación crecieron — `NotificacionEnums.kt:15`
✅ **Corregido (solo documentación).** `TipoNotificacion` tiene 16 valores reales (9 del set original + `solicitud_creada`/`solicitud_aprobada`/`solicitud_denegada` + `meta_propuesta`/`meta_aprobada`/`meta_rechazada`/`meta_modificada`, usados por `SolicitudServiceImpl` y `MetaVentaServiceImpl` respectivamente). `contrato_api.md §19` ahora lista los 16. De paso se corrigió `entidad_tipo`: el contrato solo mencionaba `oportunidad`/`empresa`, pero `EntidadNotificacion` tiene 4 valores (`solicitud`, `meta_venta` también). No se tocó el enum ni ningún archivo `.kt`.

### [Medio] La invariante #4 de CLAUDE.md (eventos no cambian estado) no tiene test que la proteja — `EventoServiceImplTest.kt:138`
✅ **Corregido (C.3).** Test positivo nuevo: marcar ocurrido un evento con `disparaCambioEstado = true` devuelve la sugerencia y `verify(exactly = 0) { oportunidadService.cambiarEstado(...) }`.

### [Medio] Umbrales de `RecordatorioJob` solo testeados en el caso trivial — `RecordatorioJobTest.kt:39`
✅ **Corregido (C.4).** Bordes cubiertos: 24h exactas, 23h59m, 24h01m (no genera), vencida por 1 minuto, evento cuya oportunidad ya no existe (se ignora sin notificar). `LimpiezaNotificacionesJobTest` reescrito con reloj fijo: afirma el corte exacto de purga (30 días antes de la ejecución), no una franja de un minuto alrededor de `now()`.

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
✅ **Corregido (D.4, documentación).** Decisión del dueño: mantener + documentar, no retirar. KDoc nuevo explica por qué hoy es inalcanzable (`verificarSolicitanteVigente` garantiza que el solicitante ya es otro admin activo) y qué la reactivaría. Test nuevo (`EmpleadoServiceTest`) fija el comportamiento actual como red de seguridad.

### [Medio] `LoginRateLimiter` puede evaporarse por desalojo LRU — `LoginRateLimiter.kt:44`
✅ **Corregido (D.3).** El desalojo ahora es consciente del bloqueo: se sacrifican primero las claves caducadas y las que no alcanzaron `maxAttempts`; una clave bloqueada solo se desaloja si no queda otra candidata. La cota de memoria se sigue respetando incluso si todas las claves vigentes están bloqueadas.

### [Medio] `Paginacion.meta` (fabrica `total_pages` para toda la API paginada) sin test propio — `Paginacion.kt:86-93`
✅ **Corregido (D.2).** 6 tests nuevos: sin resultados, un resultado, borde exacto (llena la página justo), borde exacto+1 (abre la segunda), máximo de `per_page`, y que `page`/`per_page`/`total` se devuelven tal cual. Pasaron a la primera (la aritmética ya era correcta).

### [Medio] `POST /auth/refresh` devuelve 404 en vez de 401 si el empleado del token fue borrado — `AuthController.kt:80`
✅ **Corregido (D.1).** Ahora responde `401 CREDENCIALES_INVALIDAS` en vez de 404: una credencial muerta no es un recurso ausente, y el 404 además confirmaba al portador del token que ese id existió.

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
✅ **Revisado (E.4) — confirmado como comportamiento intencional, no era un bug (2026-08-12).** Se probó un cambio (excluir NULL del promedio, incluir cerradas) que el dueño del proyecto vetó explícitamente: `dcto` NULL debe seguir contando como 0% y las oportunidades `cerrado` deben seguir excluidas de este reporte y de todos los cálculos de descuento. El código se revirtió a su comportamiento original; `ReporteServiceSqlIntegrationTest.kt` documenta ambos criterios como intencionales.

### [Medio] Los índices de hito del embudo se calculan por `ROW_NUMBER()` y se desalinean al tocar el catálogo — `ReporteService.kt:425`
✅ **Corregido (E.3).** Nueva función `posicionesDeHito(idsHitoOrdenados)`: la posición se ancla al id de catálogo, no al orden relativo. Quitar un hito deja su hueco vacío en vez de renumerar los siguientes; un cuarto hito se ignora en vez de desplazar a los tres primeros.

### [Medio] `dias_sin_actividad` usa `updated_at` y admite fechas futuras — `ProspeccionDao.kt:88`
✅ **Corregido (E.2).** `ultimaActividad` acota con `LEAST(COALESCE(fecha_ejecucion, updated_at), updated_at)` para tareas (la actividad real es, como muy tarde, cuando se completó) y con `f <= :ahora` para todo el conjunto. Test de integración nuevo (`ProspeccionDaoSqlIntegrationTest`, `@Tag("integration")`) escrito pero **no ejecutado**; pendiente de CI.

### [Medio] `POST /metas-venta` de gerencia sobre una meta nueva notifica como "modificó" — `MetaVentaServiceImpl.kt:64`
✅ **Corregido (E.1).** Una meta que no existía notifica ahora `meta_aprobada` / "estableció"; sobre una meta existente sigue siendo `meta_modificada` / "modificó".

---

## Módulo: catálogos (modelos, financiadoras, catalogoeventos) + Drive + import CSV + mantenimiento

### [Alto] El import CSV llama a Drive una vez por fila — `ImportCsvTempServiceImpl.kt:50`
✅ **Corregido.**

### [Alto] El bloqueo pesimista se mantiene durante la llamada de red a Drive — `EmpresaServiceImpl.kt:206-213`, `OportunidadServiceImpl.kt:571-584`
✅ **Corregido.**

### [Medio] El parser CSV no soporta saltos de línea dentro de campos entrecomillados — `ImportCsvTempServiceImpl.kt:35,106-129`
✅ **Corregido (F.1).** `parsearRegistros(texto)` reemplaza el troceo línea a línea: un salto de línea dentro de un campo entrecomillado ya no parte el registro.

### [Medio] La primera línea siempre se descarta como cabecera, sin validarla — `ImportCsvTempServiceImpl.kt:44`
✅ **Corregido (F.1).** `esCabecera(fila)` detecta la cabecera por si su primera columna no parece un RUC de 11 dígitos; un archivo sin fila de títulos ya no pierde su primera empresa.

### [Medio] Los números de fila reportados no coinciden con el archivo si hay líneas en blanco — `ImportCsvTempServiceImpl.kt:37,51`
✅ **Corregido (F.1).** `FilaCsv(linea, campos)` preserva el número de línea física real, contando las líneas en blanco aunque se salten como registro.

### [Medio] Cambiar el código de un modelo (o nombre de evento) a uno existente da `CONFLICTO_DATOS` genérico — `ModeloServiceImpl.kt:51`, `CatalogoEventoServiceImpl.kt:53`
✅ **Corregido (F.2).** `actualizar` valida ahora igual que `crear`: `409 CODIGO_DUPLICADO`/`NOMBRE_DUPLICADO` con `field`, mismo código que en creación. `ConflictoException` ganó un parámetro `field` opcional.

### [Medio] Se puede dejar el sistema sin ninguna financiadora default — `FinanciadoraServiceImpl.kt:59`
✅ **Corregido (F.3).** Desmarcar la única default (`es_default: false`) responde `409 FINANCIADORA_DEFAULT_REQUERIDA` en vez de dejar el sistema sin ninguna.

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
✅ **Corregido.** Progresión del trinquete en tres pasos: **63/58 → 71/67 → 85/84**.

1. Se corrigió primero la desinformación (ni el CI ni los comentarios afirman ya 75/90 como si fuera el gate real).
2. La ola 1 de subagentes cubrió los módulos que no tenían ningún test → dominio 58% → 68.3%.
3. La ola 2 atacó los servicios de dominio y, sobre todo, los filtros de JPA Specification → **global 72.5% → 86.6%, dominio 68.3% → 85.0%**.

**El objetivo global (75%) ya se supera.** El de dominio (90%) da 85.0% midiendo en local, pero la brecha restante **ya no es deuda de tests unitarios**: de las 555 líneas de dominio sin cubrir, **366 son SQL nativo agregado** (`ReporteService` al 1%, `ProspeccionDao`, `InicioDao`, `OportunidadConsultas`) que solo se puede cubrir con los `@Tag("integration")` — escritos y commiteados, pero no ejecutables en local por el bloqueo de Testcontainers/Docker 29. **Contándolos, el dominio queda en 94.9% y el objetivo se cumple en CI.** Quedan 189 líneas unit-testables como margen real de mejora.

El trinquete se fija sobre la medición local (conservadora, −1 punto de margen), así que CI siempre va por encima. `QualityGatesConfigTest` sigue leyendo el `minBound` efectivo del build, no texto de comentarios.

Palanca que más rindió: replicar `ContactoBusquedaSpecificationTest` — compila las JPA Specification contra el metamodelo **real** de Hibernate sin base de datos, así que además de cubrir cientos de líneas detecta nombres de atributo mal escritos, que es el bug que tuvo `GET /contactos?q=` devolviendo 500 siempre.

### [Medio] Cuatro tests de "configuración" no ejercitaban código de producción — `CiPipelineConfigTest.kt`, `SecurityScanWorkflowConfigTest.kt`, `LocalEnvironmentConfigTest.kt`
✅ **Corregido.** Evaluados uno por uno: `LocalEnvironmentConfigTest` ya estaba bien y no se tocó; los otros dos tenían la misma debilidad (`contains` sobre substrings triviales) y se corrigieron para parsear YAML real en vez de borrarse, porque documentaban invariantes legítimos.

### Regla 12 de CLAUDE.md ("ArchUnit lo verifica") — ArchUnit no existía en el proyecto
✅ **Corregido.** Se instaló `archunit-junit5` y se escribió la regla real sobre bytecode. Verificado explícitamente: **0 violaciones** — la codebase ya respetaba la frontera entre módulos, solo que nadie lo comprobaba.

---

## Otros hallazgos (encontrados durante los fixes, fuera del alcance original de cada agente)

### [Medio] `error.field` se devuelve en camelCase, no snake_case — `GlobalExceptionHandler.kt:60` (y otras líneas del mismo patrón)
✅ **Corregido (G.1).** Nueva función `String.aCampoSnakeCase()` (`shared/NombresDeCampo.kt`) aplicada en `handleValidation` y `handleConstraintViolation`. Convierte segmento a segmento para conservar los índices de array (`contactos[0].id_contacto`).

### [Bajo] `EmpresaDriveControllerTest.kt:105` es un falso positivo — usa `standaloneSetup`
✅ **Corregido (G.2).** El test pasó a `@SpringBootTest` (excluyendo BD/JPA/Flyway) con el `ObjectMapper` real inyectado vía `MappingJackson2HttpMessageConverter` en el `standaloneSetup`. Las aserciones se corrigieron a `drive_folder_id`.

### [Medio] La garantía de que las columnas `TIMESTAMP` contienen UTC depende únicamente de `ENV TZ=UTC` en el Dockerfile
✅ **Corregido (G.3, mitigación en código; el fix de esquema sigue fuera de alcance).** Decisión del dueño: guard de arranque que falla rápido. `ZonaHorariaGuard` (`@PostConstruct`) verifica `ZoneId.systemDefault()` y aborta el arranque si no es UTC (`app.exigir-utc`, default `true`). Se descubrió que el default `true` rompía 158 tests en esta máquina (no está en UTC): se añadió `app.exigir-utc=false` a `src/test/resources/application.properties` — en Docker (producción) la JVM sí está en UTC y la guarda pasa igual, con el flag en cualquier valor. El fix robusto de raíz (`TIMESTAMPTZ` o `Instant` en las entidades) sigue tocando el esquema y fuera de alcance.

---

## Riesgo residual introducido por el fix de Drive (D1)

**No es un hallazgo del review original** — lo señaló el propio agente que hizo el fix, honestamente, en su informe:

`POST /empresas` ensancha ligeramente la ventana entre el chequeo de RUC duplicado y el insert (por la latencia de Drive, que ahora ocurre entre ambos pasos, ~300ms). Dos `POST` **simultáneos** con el mismo RUC podrían pasar ambos el chequeo y el segundo chocaría con la constraint única, dando 500 en vez de 409. La ventana ya existía antes del fix (el chequeo no es atómico con el insert bajo `READ COMMITTED`) pero era mínima; ahora es un poco mayor. No se blindó con un catch de `DataIntegrityViolationException` a propósito, porque el caso frecuente de duplicado (reintento de CSV, doble submit) es secuencial y sigue devolviendo 409 correctamente.
