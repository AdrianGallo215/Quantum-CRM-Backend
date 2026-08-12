# Hito de pausa — ejecución de hallazgos [Medio]/[Bajo]

**Pausado el 2026-08-12**, a petición explícita del usuario, para evitar un corte abrupto por límite de sesión. Los 5 agentes de la Ola 1 se detuvieron en un punto seguro (ningún fichero quedó a medio editar; cada worktree compila hasta donde se pudo verificar manualmente por inspección de diff, aunque **ninguno tiene una verificación de `./gradlew test ktlintCheck detekt` en verde confirmada por esta sesión** — ver "Qué falta verificar" más abajo).

**Documento origen:** `docs/plan-ejecucion-medios-bajos.md` (el plan completo, con las 27 tareas atómicas). Este hito es solo el estado de ejecución; para el contenido de cada tarea, ir al plan.

**Estado base cuando arrancó todo:** `main` @ `9ec40aa`, árbol limpio, `./gradlew test ktlintCheck detekt --rerun-tasks` verde.

---

## Resumen ejecutivo

| Agente | Worktree | Rama | Estado real (por inspección de diff) |
|---|---|---|---|
| **A** — oportunidades | `.worktrees-manual/agente-a` | `medios-bajos/agente-a` | A.1 **completo** (producción + test). A.2 **en curso**: producción probablemente sin tocar aún (no se ve `findByIdBloqueando` en `OportunidadRepository.kt` ni `visibleBloqueando` en el servicio), pero los tests YA migraron sus stubs de `findById`→`findByIdBloqueando` en 2 ficheros. **Esto significa que ahora mismo el árbol de A no compila** (los tests referencian un método que la producción no tiene todavía) — es el ROJO esperado de TDD, no un error, pero hay que saberlo al retomar. A.3 y A.4 sin empezar. |
| **C** — tareas/eventos/jobs | `.worktrees-manual/agente-c` | `medios-bajos/agente-c` | C.1 **completo** (producción + test unitario + test de integración nuevo, sin ejecutar por Testcontainers roto). C.2, C.3, C.4 sin empezar. |
| **D** — empleados/security | `.worktrees-manual/agente-d` | `medios-bajos/agente-d` | D.1: **solo el test está escrito** (`AuthControllerWebMvcTest.kt`), la producción (`AuthController.kt`) **no** se tocó todavía. D.2, D.3, D.4 sin empezar. Hay una carpeta suelta `.gradle-home-d/` (untracked, un intento fallido de aislar el `GRADLE_USER_HOME`; se puede borrar sin riesgo). |
| **E** — reportes/prospección/metas | `.worktrees-manual/agente-e` | `medios-bajos/agente-e` | E.1: **solo el test está escrito** (`MetaVentaServiceImplTest.kt`), la producción (`MetaVentaServiceImpl.kt`) **no** se tocó todavía. E.2, E.3, E.4 sin empezar. |
| **F** — catálogos/import CSV | `.worktrees-manual/agente-f` | `medios-bajos/agente-f` | **F.1, F.2 y F.3 completos** (producción + tests, en los 3). Es el agente más avanzado con diferencia. Pendiente solo verificar build en verde. |

**Ninguno hizo `git commit`** (correcto, conforme a las reglas del plan). Todos los cambios están en el working tree de su worktree respectivo, sin stagear.

---

## Por qué se pausó aquí y no antes: la causa raíz de los cortes repetidos

Antes de llegar a este punto hubo **muchísimos cortes** (decenas de notificaciones de tareas). Vale la pena documentar la causa, porque si se repite el mismo patrón de "5 agentes en paralelo, cada uno con gradle" en la próxima sesión, va a volver a pasar:

1. **La herramienta de aislamiento automática (`isolation: "worktree"`) está rota en este entorno.** Falla con `Refusing to use ... as an isolation worktree: git resolves its working tree to ... (a core.worktree redirect...)`. Se verificó manualmente con `git rev-parse --show-toplevel` que el worktree resuelve perfectamente bien — es un falso positivo de la herramienta, probablemente por los espacios en la ruta (`CRM BackEnd - copia`). **Solución aplicada:** los 5 worktrees se crearon a mano con `git worktree add .worktrees-manual/<nombre> -b medios-bajos/<nombre> main`, y los agentes se lanzaron con `Agent()` **sin** el parámetro `isolation`, instruyéndolos por prompt a hacer `cd` a la ruta exacta como primer comando.

2. **El timeout por defecto de la herramienta de shell es de 2 minutos, incluso en "primer plano".** Un build de Gradle con daemon frío en este proyecto tarda ~3 minutos. Cualquier comando de gradle sin un `timeout` explícito se auto-segundo-planea a mitad, y el turno del agente terminaba antes de leer el resultado — generando el patrón "lancé gradle, espero notificación" una y otra vez. **Mitigación aplicada:** se instruyó a los agentes a pasar `timeout: 300000` (5 min) o más, explícito, en cada llamada a Bash/PowerShell para comandos de gradle.

3. **5 daemons de Gradle compitiendo por el mismo `GRADLE_USER_HOME` compartido se mataban entre sí.** Varios agentes reportaron "stop command received" o "busy daemon... killed instead of queued" — el registro de daemons de Gradle es compartido por todo el usuario del sistema, así que 5 builds simultáneos desde 5 worktrees distintos chocan. Uno de los agentes (D) intentó mitigar esto creando un `GRADLE_USER_HOME` propio (`.gradle-home-d/`, quedó como carpeta suelta sin commitear) pero eso **multiplica el tiempo de build** porque re-descarga todas las dependencias — no es la solución correcta.

4. **Presión de memoria real:** en un momento dado quedaban solo ~1.1–1.4 GB libres de 24 GB con los 5 builds corriendo a la vez (verificado también desde la sesión principal: 5.53 GB libres en un momento de menor carga). Esto por sí solo puede causar OOM kills de daemons Gradle, que es probablemente la causa de al menos uno de los "corrupted incremental build cache" que reportó el agente C.

### Recomendación para la próxima sesión

**No relanzar los 5 agentes en paralelo verdadero.** La opción más robusta es:
- **Serializar la verificación de gradle**: dejar que máximo 2 agentes corran `./gradlew` a la vez (los otros 3 escriben/editan mientras esperan su turno), o
- **Un solo `GRADLE_USER_HOME` compartido pero builds secuenciales** para la fase de verificación, aunque la escritura de tests/código siga en paralelo, o
- Aceptar que cada verificación de gradle puede tardar 3-6 minutos bajo contención y **siempre** pasar `timeout: 600000` (el máximo permitido) en cada llamada de gradle, sin excepción.

---

## Detalle por agente — qué hay exactamente en cada worktree

### Agente A — `.worktrees-manual/agente-a`

Ficheros modificados:
- `src/main/kotlin/pe/quantum/crm/domain/oportunidades/OportunidadServiceImpl.kt` — **A.1 completo**: añadido `estadoFiltro(...)` privado y `especificacion(...)` actualizada para recibir el enum ya resuelto. Coincide exactamente con el Paso 3 del plan.
- `src/test/kotlin/pe/quantum/crm/domain/oportunidades/OportunidadListadoSpecificationTest.kt` — test de A.1 sustituido correctamente (el que documentaba el bug fue reemplazado, no duplicado).
- `src/test/kotlin/pe/quantum/crm/domain/oportunidades/OportunidadCambiarEstadoInvariantesTest.kt` — cambios de A.2 en marcha (+51/-líneas).
- `src/test/kotlin/pe/quantum/crm/domain/oportunidades/OportunidadServiceImplTest.kt` — 3 stubs ya migrados de `findById(100)` → `findByIdBloqueando(100)` (líneas ~225, ~490, ~518), tal como pide el aviso crítico de A.2.
- `docs/plan-ejecucion-medios-bajos.md` — sin trackear en este worktree (normal, es untracked porque el worktree se creó antes de... no, en realidad SÍ estaba en el commit base `9ec40aa`; que aparezca como `??` es raro — **verificar al retomar si es una copia duplicada o un problema del branch**).

**Lo que falta para A.2:** en `OportunidadRepository.kt` **no** se ve el método `findByIdBloqueando` todavía, y en `OportunidadServiceImpl.kt` no se ve `visibleBloqueando`. Es decir: **los tests ya esperan la nueva API pero la producción todavía no la tiene** — el árbol no compila ahora mismo, que es el ROJO correcto de TDD, pero hay que completar el Paso 3 de A.2 (añadir el método al repository + `@Lock(LockModeType.PESSIMISTIC_WRITE)` + el privado `visibleBloqueando`) antes de poder verificar nada.

**Para retomar A:** ir a `docs/plan-ejecucion-medios-bajos.md` sección "Agente A", tarea A.2, Paso 3 en adelante. A.3 y A.4 no se han tocado.

### Agente C — `.worktrees-manual/agente-c`

Ficheros modificados:
- `src/main/kotlin/pe/quantum/crm/domain/tareas/TareaRepository.kt` — **C.1 completo**: `findByEstadoAccionAndIdAsignadoIsNotNullAndFechaEjecucionBetween` añadido, sustituyendo al método viejo. Coincide con el plan.
- `src/main/kotlin/pe/quantum/crm/domain/tareas/TareaServiceImpl.kt` — `pendientesParaRecordatorio()` reescrito con la ventana de 30 días atrás / 24h adelante, más las constantes `DIAS_VENCIDO_NOTIFICABLE`/`HORAS_VENTANA_PROXIMO`.
- `src/test/kotlin/pe/quantum/crm/domain/tareas/TareaServiceImplTest.kt` — test de C.1 añadido.
- `src/test/kotlin/pe/quantum/crm/domain/tareas/TareaRepositoryVentanaIntegrationTest.kt` (nuevo, untracked) — test `@Tag("integration")` de C.1, **NO ejecutado** (Testcontainers roto), tal como exige el plan.

**C.1 parece completo y consistente.** No se ve todavía nada de C.2 (no existe `shared/ZonaHoraria.kt`, `RecordatorioJob.kt` no está en la lista de modificados), C.3 ni C.4.

**Para retomar C:** verificar primero `./gradlew test ktlintCheck detekt` (con timeout largo) para confirmar que C.1 está en VERDE, y si es así, seguir con C.2 (Clock inyectable + `ZonaHoraria.kt`), C.3 (solo-test) y C.4, en ese orden — C.2 antes que C.4 es obligatorio según el plan.

### Agente D — `.worktrees-manual/agente-d`

Ficheros modificados:
- `src/test/kotlin/pe/quantum/crm/domain/empleados/AuthControllerWebMvcTest.kt` — test de D.1 añadido y bien formado: usa `jwtService.generateRefreshToken(empleadoId = 99)` real (no un helper `principalDe` inexistente) y `empleadoService.porId(99) throws NoEncontradoException`, esperando `401` + `CREDENCIALES_INVALIDAS`. Coincide con la intención del plan, adaptado correctamente al patrón real del fichero.

**Nada de producción tocado.** `AuthController.kt` sigue como en `main`. Esto significa que el test está en ROJO ahora mismo (fallará con 404, no 401) — es el estado esperado antes del Paso 3 de D.1.

Carpeta suelta: `.gradle-home-d/` (untracked) — intento de un `GRADLE_USER_HOME` aislado que no ayudó (ver causa raíz #3 arriba). **Se puede borrar sin riesgo** (`rm -rf .worktrees-manual/agente-d/.gradle-home-d`) antes de retomar, para no confundir a la siguiente sesión.

**Para retomar D:** implementar el Paso 3 de D.1 (el `try/catch` en `AuthController.refresh` que traduce `NoEncontradoException` a `CredencialesInvalidasException`), verificar VERDE, y seguir con D.2 (solo-test), D.3 y D.4.

### Agente E — `.worktrees-manual/agente-e`

Ficheros modificados:
- `src/test/kotlin/pe/quantum/crm/domain/metasventa/MetaVentaServiceImplTest.kt` — los dos tests de E.1 añadidos (`gerencia creando una meta nueva notifica que la estableció...` y `...sobre una meta existente sigue notificando que la modificó`), más el ajuste de un test preexistente que esperaba `meta_modificada` y ahora se corrigió a `meta_aprobada` para el caso de meta nueva. Bien alineado con el plan.

**Nada de producción tocado.** `MetaVentaServiceImpl.kt` sigue como en `main`. El árbol está en ROJO ahora mismo (esperado): los tests nuevos fallarán porque el servicio siempre notifica `meta_modificada`/"modificó".

**Para retomar E:** implementar el Paso 3 de E.1 (el `if (esNueva)` en `MetaVentaServiceImpl.crear`), verificar VERDE, y seguir con E.2 (`dias_sin_actividad` en `ProspeccionDao`), E.3 (`posicionesDeHito` — **recordar**: función top-level `internal` junto a `promedio`, NO en el companion object privado) y E.4 (`promedioDeDescuentos`, con el supuesto explícito sobre NULL que hay que anotar para veto del dueño), en ese orden.

### Agente F — `.worktrees-manual/agente-f`

**El más avanzado.** Ficheros modificados, con las tres tareas aparentemente completas:

- **F.1** (`ImportCsvTempServiceImpl.kt`): reescrito el parseo — `parsearRegistros(texto)` en vez de `readLines()` línea a línea, con `FilaCsv(linea, campos)` que preserva el número de línea físico, `esCabecera(...)` que detecta la cabecera por si la primera columna no es un RUC de 11 dígitos, y manejo de saltos de línea dentro de campos entrecomillados. Coincide con el diseño del plan. Test correspondiente en `ImportCsvTempServiceImplTest.kt` (+72 líneas).
- **F.2** (`NegocioExceptions.kt`, `ModeloServiceImpl.kt`, `ModeloRepository.kt`, `CatalogoEventoServiceImpl.kt`, `CatalogoEventoRepository.kt`): `ConflictoException` ganó el parámetro opcional `field`; `ModeloServiceImpl.actualizar` y `CatalogoEventoServiceImpl.actualizar` (falta confirmar si este último se tocó — revisar `CatalogoEventoServiceImpl.kt` no apareció en el diff de producción listado arriba pero SÍ su repository y su test) validan duplicados antes de guardar. **Verificar al retomar** si `CatalogoEventoServiceImpl.kt` (el `.kt` de producción, no el repository) quedó realmente modificado — el `git diff --stat` inicial no lo listó como tocado a pesar de que `CatalogoEventoRepository.kt` y el test sí lo están; podría faltar ese último paso de F.2 para catálogo de eventos.
- **F.3** (`FinanciadoraServiceImpl.kt`): el guard `FINANCIADORA_DEFAULT_REQUERIDA` al desmarcar la única default, exactamente como especifica el plan. Depende de `field` en `ConflictoException` (F.2), que ya está.

**Para retomar F:** primero un `./gradlew test ktlintCheck detekt` completo (timeout largo) para ver dónde está realmente el árbol. Si algo falla, es probablemente el hueco de `CatalogoEventoServiceImpl.kt` mencionado arriba — comparar contra la Tarea F.2 del plan (bloque de `actualizar` en `CatalogoEventoServiceImpl`).

---

## Qué falta verificar (nadie lo confirmó todavía)

**Ningún worktree tiene una corrida de `./gradlew test ktlintCheck detekt` verde confirmada por esta sesión.** Todo lo anterior es lectura de diff, no ejecución. Al retomar, el primer paso en CADA worktree debe ser correr el build completo (con `timeout: 600000` explícito, sin `--no-daemon`, sin `GRADLE_USER_HOME` aislado) y corregir lo que salga.

## Limpieza pendiente (opcional, no bloqueante)

- `rm -rf .worktrees-manual/agente-d/.gradle-home-d` — carpeta suelta sin uso.
- Confirmar por qué `docs/plan-ejecucion-medios-bajos.md` aparece como `??` (untracked) en los worktrees de A y F pese a estar commiteado en `main` @ `9ec40aa` — posible causa: el fichero se creó/escribió en el worktree principal DESPUÉS de que esos worktrees ya se hubiesen creado como copia de `9ec40aa`, así que en realidad el plan no estaba en ese commit cuando se hizo el `git worktree add`, y cada agente debió haber recibido el contenido de otra forma (probablemente copiado a mano o vía symlink del sistema de ficheros compartido). **No es un problema real** — el fichero existe y es legible en cada worktree — pero conviene no confundirlo con un cambio real de los agentes al revisar diffs.

---

## Cómo retomar (instrucciones para la próxima sesión)

1. Verificar que los 5 worktrees siguen existiendo: `git worktree list` desde la raíz del repo.
2. Para cada agente, en orden de menor a mayor riesgo de tiempo (F ya casi terminado → verificar y cerrar primero; D y E con producción pendiente → siguen; A y C a medio camino):
   - `cd .worktrees-manual/agente-<X>`
   - `git status` / `git diff --stat` para confirmar que coincide con lo descrito aquí (nadie más debería haber tocado nada).
   - Continuar la tarea desde el punto exacto indicado arriba, siguiendo `docs/plan-ejecucion-medios-bajos.md`.
3. Aplicar la recomendación de la sección "causa raíz" para evitar los mismos cortes: timeouts largos explícitos, sin gradle-home aislado, y considerar no correr los 5 en paralelo verdadero si la máquina vuelve a mostrar presión de memoria.
4. Cuando los 5 estén en verde, fusionar al árbol principal (`main`) y proceder con la **Ola 2** tal como describe `docs/plan-ejecucion-medios-bajos.md`: grupo B (empresas), grupo G (transversales), actualización de `contrato_api.md` y `code-review-pendientes.md`, y el smoke test end-to-end contra Postgres real (aprobado por el dueño, avisar antes de arrancar `bootRun`).
