# Mapa — Módulo Simulaciones, fases 2 a 6 del encargo

> **Documento de investigación y decisiones.** No es una lista de tareas: las
> tareas viven en `plan-10-dominio-crud-tareas.md` (Plan D) y en los documentos
> equivalentes de los Planes E y F, que se redactan cuando el anterior cierre.
>
> Continúa la numeración de hallazgos y decisiones de los planes 00-08:
> hallazgos **K10+**, decisiones **D30+**.

---

## 0. Punto de partida real (verificado contra el código, no contra la memoria)

`Instrucciones_simulaciones.md` define 6 fases. **Solo la Fase 1 está hecha.**

| Fase | Estado | Evidencia |
|---|---|---|
| 1. Motor de cálculo puro | **Hecha** (Plan 1, commit `287f36d`) | `shared/simulacion/` — `MotorSimulacion.kt`, `AritmeticaFinanciera.kt`, `ParametrosSimulacion.kt`, `ResultadoSimulacion.kt`; casos dorados §3.6 verificados al centavo |
| 2. Persistencia y dominio | **Sin empezar** | `domain/simulaciones/` **no existe** |
| 3. Endpoints y permisos | **Sin empezar** | Sin controller; cero endpoints `/simulaciones` |
| 4. Calculadora Financiera | **Sin empezar** | — |
| 5. Jobs programados | **Parcial** | El job de tipo de cambio §12 **ya está** (Plan 2: `domain/tipocambio/`, `integracion/sunat/`). Faltan purga a 30 días y aviso a 3 días |
| 6. Documentación | **Sin empezar** | `contrato_api.md`, `matriz_permisos.md` y `CLAUDE.md` no mencionan `simulaciones` como módulo activo |

El schema **sí** está aplicado en producción: `V43__create_simulaciones.sql`
(tablas `simulaciones` y `simulacion_log`, enums `modo_simulacion_enum` y
`tipo_evento_simulacion_enum`, trigger `trg_simulacion_modo_inmutable`, RLS).

Consecuencia práctica: **el motor existe y está probado, pero no es alcanzable
desde la API.** Ningún usuario puede crear, leer ni consultar una simulación.

---

## 1. Documentos que gobiernan este plan

Exigido por `CLAUDE.md` §"Cómo escribir un plan de implementación en este repo":
qué documento manda y **qué dice exactamente** sobre este cambio.

| Documento | Qué manda aquí |
|---|---|
| `Instrucciones_simulaciones.md` | El encargo. Orden de fases inalterable; "para y resume al terminar cada fase". Lista cerrada de restricciones no negociables (§"Restricciones que no se negocian") |
| `docs/reglas_simulaciones.md` | **Fuente de verdad del comportamiento.** §1 fronteras · §2 modos · §3 motor · §4 qué se persiste · §5 purga · §6 cuota en oportunidad · §7 bitácora · §8 nombre · §9 Calculadora · §10 permisos · §11 exportaciones · §13 validaciones |
| `src/main/resources/db/migration/V43__create_simulaciones.sql` | El modelo de datos, ya aplicado. Sus CHECK y su índice único son parte del contrato |
| `docs/contrato_api.md` | Estilo y formato de los endpoints; §24 tabla de enums; §26 changelog |
| `docs/matriz_permisos.md` | Reparto de permisos vigente; §4.3 el analista como rol de apoyo |
| `CLAUDE.md` | Reglas 1, 8, 9, 10, 11, 12, 14 (detalle abajo) |
| `docs/TESTING-backend.md` | Cómo se escriben los tests; TDD obligatorio |

### Reglas de `CLAUDE.md` que tocan este plan, y cómo aplican

| Regla | Cómo aplica exactamente |
|---|---|
| **1. TDD siempre** | Test que falla antes del código, en cada tarea de servicio. Los tests `@Tag("integration")` **no se pueden correr en local** (Docker 29): se verifican por lectura y se reportan como tales, nunca en falso verde |
| **8. Inyección por constructor** (`private val`), nunca `@Autowired` en campos | Todo `Service`/`Impl`/`Component` nuevo |
| **9. Relaciones JPA siempre `LAZY`; nunca exponer entidades en controllers** | `Simulacion` y `SimulacionLog` usan columnas `Long` simples (`idOportunidadItem`, `idModelo`, `idSimulacionOrigen`), **no** `@ManyToOne`. Mismo patrón que `OportunidadItem.idOportunidad` y `MetaVenta.idEmpleado` |
| **10. `@Transactional(readOnly = true)` en lecturas, `@Transactional` en escrituras** cubriendo toda la operación | Crear/editar/eliminar escriben en `simulaciones` **y** en `simulacion_log` en la misma transacción |
| **11. Queries parametrizadas siempre** | Spring Data o `NamedParameterJdbcTemplate`; nunca concatenación |
| **12. Un módulo nunca accede a tablas ni entidades de otro módulo** | `simulaciones` solo habla con `oportunidades`, `modelos`, `empresas` y `notificaciones` por sus interfaces públicas y DTOs. Lo verifica ArchUnit sobre bytecode |
| **14. IDOR: recurso ajeno → 404, no 403** | Vendedor que pide una simulación de otro → **404**. Rol sin acceso al módulo (`jdv`, `otro`) → **403**: no es una pregunta sobre existencia del recurso, es que el rol no tiene la función |

### Restricciones del encargo que se convierten en criterio de aceptación

De `Instrucciones_simulaciones.md` §"Restricciones que no se negocian":

1. **No persistir lo derivable**: ni cronograma, ni diff, ni nombre autogenerado.
2. **No aceptar `cuota_final` del cliente.** Siempre server-side.
3. **No agregar fila extra para el balloon** ni forzar la última amortización.
4. **La Calculadora no escribe nada.** Ni siquiera auditoría.
5. **No tocar `financiadoras`, `oportunidades`, `oportunidad_items`** ni ninguna
   tabla existente, salvo la migración de los enums de notificaciones.
6. Mantener el coverage sobre el trinquete del build (85 % global / 84 % dominio).

---

## 2. Hallazgos

### K10 — El motor está cerrado y no se toca

`MotorSimulacion.calcular(ParametrosSimulacion): ResultadoSimulacion` es una
función pura en `shared/simulacion/`, sin Spring ni JPA. Vive fuera de
`domain/simulaciones/` **a propósito** (§9: lo consumen dos flujos, el que
persiste y la Calculadora que no). `ResultadoSimulacion` ya expone
`cuotaFinal`, `cuotaFinanciera`, `valorVenta`, `igv`, `principal`,
`tasaNominalMensual` y `cronograma`.

**Ninguna tarea de estos planes modifica el motor.** Si alguna parece
necesitarlo, es señal de que el diseño se desvió: parar y consultar.

### K11 — `OportunidadItemVinculo` se queda corto para simulaciones

`OportunidadItemService.vinculoVisible(idItem, usuario)` devuelve hoy:

```kotlin
data class OportunidadItemVinculo(
    val id: Long, val idOportunidad: Long, val idEmpresa: Long, val descuento: BigDecimal?,
)
```

Simulaciones necesita además **`precioVenta`** e **`idModelo`** (para prellenar
los campos esenciales, §6.1) y, para la agregación §6.2, **`cantidad`** y
**`cuotaFinanciadora`**. La API pública de `oportunidades` hay que ampliarla.

### K12 — **El conflicto de permisos: la visibilidad de simulaciones NO es la de oportunidades**

Este es el hallazgo que más cambia el diseño. `reglas_simulaciones.md` §10:

| Rol | Módulo Simulaciones | Simulador en su oportunidad | Calculadora |
|---|---|---|---|
| `admin` | Total | Sí | Sí |
| `analista` | **Total** | Sí | Sí |
| `gerencia` | Total | Sí | Sí |
| `vendedor` | **Sin acceso** | Solo donde es el vendedor asignado | Sí |
| `jdv`, `otro` | Sin acceso | No | No |

Y el texto lo remacha: *"`analista` es de solo lectura en oportunidades pero
tiene **escritura completa** en simulaciones: es el rol dueño de este módulo."*

Choca de frente con dos cosas ya vigentes en el repo:

1. **`UsuarioActual.esRolApoyo` agrupa `analista` + `otro`.** En simulaciones
   esos dos roles están en extremos opuestos: `analista` tiene acceso total,
   `otro` no tiene ninguno. **`SimulacionPermisos` no puede usar ese predicado.**
2. **`OportunidadVisibilidad.alcanza` restringe al rol de apoyo a las
   oportunidades donde colabora vía tarea.** Si simulaciones delegara su
   autorización en `vinculoVisible`, un `analista` solo vería las simulaciones
   de los ítems donde colabora — contradiciendo el "Total" de §10.

Además `jdv` es supervisor en oportunidades (`esSupervisor` lo incluye) pero
**no tiene acceso** al módulo de simulaciones. O sea: ninguno de los predicados
de rol que ya existen en `UsuarioActual` sirve tal cual aquí.

### K13 — Dependencia circular `oportunidades` ↔ `simulaciones`

`simulaciones` necesita `OportunidadItemService` (datos del ítem); `oportunidades`
necesitará `SimulacionService` para la cuota de §6.2 (Plan F). Spring Boot 3
rechaza ciclos de constructor.

**Precedente ya en el repo:** `OportunidadVisibilidad` inyecta
`@Lazy private val tareaService: TareaService` exactamente por esto, con el
comentario que lo explica. Mismo patrón, misma justificación.

### K14 — `es_principal` tiene un índice único parcial que hay que respetar

```sql
CREATE UNIQUE INDEX uq_simulacion_principal
    ON simulaciones(id_oportunidad_item)
    WHERE es_principal = true AND id_oportunidad_item IS NOT NULL;
```

Más el CHECK `chk_simulacion_principal_requiere_item` (`es_principal = false OR
id_oportunidad_item IS NOT NULL`). Consecuencias:

- Una simulación **sin ítem nunca puede ser principal**.
- Marcar una como principal exige **desmarcar la anterior en la misma
  transacción**, y en ese orden: si se inserta la nueva antes de desmarcar la
  vieja, el índice único revienta.

### K15 — `simulacion_log` tiene un CHECK que condiciona qué evento lleva qué

`chk_simulacion_log_snapshot` exige:

- `creada` / `editada` / `restaurada` / `eliminada` → snapshot completo
  (`modo`, `precio_venta`, `cuota_inicial`, `plazo_meses`, `tea`,
  `valor_residual`, `cuota_final` todos NOT NULL).
- `marcada_principal` / `enlazada_a_item` → `id_oportunidad_item` NOT NULL.

Un INSERT que no cumpla explota con `DataIntegrityViolationException` (500). El
servicio tiene que construir cada evento con lo que su rama del CHECK pide.

`created_by` es **nullable a propósito**: null cuando el evento lo genera un job
sin actor humano (la purga a 30 días).

### K16 — El trigger de `modo` devuelve 500 si se llega a él

`trg_simulacion_modo_inmutable` lanza `RAISE EXCEPTION`, que Hibernate traduce a
`DataIntegrityViolationException` → 500. Es la **tercera** línea de defensa (§2),
no la primera. El Service tiene que rechazar el cambio de `modo` antes, con un
error de negocio limpio.

### K17 — El correlativo `#{n}` del nombre tiene índice de soporte

`idx_simulacion_correlativo ON simulaciones(id_oportunidad_item, id_modelo, modo, created_at)`.
§8.1: el correlativo cuenta dentro del mismo ítem; para las no enlazadas el
scope es `modelo + modo` y **no es un dato crítico**.

### K18 — Los enums de notificaciones necesitan valores nuevos (y migración propia)

Para el aviso de purga (§5) hacen falta un valor en `tipo_notificacion_enum` y
otro en `entidad_notificacion_enum`. El encargo lo autoriza explícitamente
("salvo la migración de los enums de notificaciones").

**Lección ya pagada en el Plan B (V44/V45):** `ALTER TYPE ... ADD VALUE` **no
puede combinarse** con el uso de ese mismo valor en la misma transacción. La
migración del enum va sola, separada de cualquier uso. Eso es Plan F.

### K19 — La numeración de migraciones se asigna al desplegar, no al escribir

Producción va por **V46** (aplicada y registrada el 2026-09-05). Cualquier
migración de estos planes toma su número **en el momento de desplegar**, tras
releer `flyway_schema_history`. Flyway corre con `out-of-order = false`: una
versión menor que la máxima ya aplicada no entra nunca. Ya costó una renumeración
(V40 → V43) en este mismo módulo.

### K20 — §11 (propuesta, PDF, Excel) queda fuera del backend

Decidido con el usuario. §11 describe `<PropuestaFinanciera/>` como componente
que renderiza en HTML **desde los datos de la simulación**, y el PDF como
descarga on demand desde esa vista. Las 6 fases del encargo no listan
exportaciones como trabajo de backend. El backend entrega todo lo necesario vía
el endpoint de cronograma; **no se implementa generación de PDF ni de Excel**, y
no se añade ninguna dependencia nueva al build por ese motivo.

### K21 — El coverage es un trinquete, no un objetivo

85 % global / 84 % dominio, en `build.gradle.kts`. Un módulo nuevo grande y mal
cubierto **baja el porcentaje global y rompe el build**. Cada tarea de servicio
trae sus tests; no se dejan para el final.

---

## 3. Decisiones

### D30 — `simulaciones` tiene su propia regla de visibilidad, centralizada en `SimulacionPermisos`

Consecuencia directa de **K12**. Un único `@Component` en
`domain/simulaciones/SimulacionPermisos.kt` es el **único punto de decisión** de
autorización del módulo, tal como exige §10 ("debe estar centralizado en un solo
punto de decisión, no disperso en condicionales por endpoint") y el encargo
(Fase 3).

**No usa** `UsuarioActual.esRolApoyo`, `esSupervisor` ni `visibilidadRestringida`:
ninguno parte los roles como los parte §10. Compara el rol explícitamente, con el
KDoc que explica por qué se aparta del predicado compartido.

Matriz que implementa:

| Operación | admin | gerencia | analista | vendedor | jdv | otro |
|---|---|---|---|---|---|---|
| Listar el módulo (`GET /simulaciones`) | ✓ | ✓ | ✓ | ✗ 403 | ✗ 403 | ✗ 403 |
| Ver / crear / editar / eliminar / cronograma | ✓ | ✓ | ✓ | solo las suyas (ver D31) | ✗ 403 | ✗ 403 |

### D31 — Qué es "suya" para un vendedor

Dos casos, porque `id_oportunidad_item` es opcional (§5):

- **Simulación enlazada**: es suya si el ítem pertenece a una oportunidad donde
  él es el vendedor asignado (§9: *"solo puede enlazar a ítems de oportunidades
  donde él es el vendedor asignado"*).
- **Simulación sin ítem**: es suya si `created_by == usuario.id`. Sin ítem no hay
  cadena a oportunidad ni a empresa, así que la autoría es el único vínculo
  posible.

Un vendedor que pide una que no es suya recibe **404** (regla 14), no 403.

### D32 — `oportunidades` expone los datos del ítem **sin** chequeo de visibilidad, y quien llama decide

Consecuencia de **K11 + K12**: si el método aplicara la visibilidad de
oportunidades, `analista` perdería el acceso total que §10 le da.

Se añade a `OportunidadItemService`:

```kotlin
fun datosParaSimulacion(idsItem: Collection<Long>): Map<Long, OportunidadItemParaSimulacion>
```

con el DTO en `domain/oportunidades/dto/`:

```kotlin
data class OportunidadItemParaSimulacion(
    val id: Long,
    val idOportunidad: Long,
    val idEmpresa: Long,
    val idVendedor: Long,   // para que SimulacionPermisos aplique D31 sin otra ida a BD
    val idModelo: Long,
    val cantidad: Int?,
    val precioVenta: BigDecimal?,
    val descuento: BigDecimal?,
    val cuotaFinanciadora: BigDecimal,
)
```

**Documentado en el KDoc como "sin chequeo de visibilidad"**, igual que
`porOportunidades` y `montoTotalPorOportunidades` ya lo están. Por lotes
(`Collection` → `Map`) para no abrir un N+1 en el listado.

`vinculoVisible` **no se toca**: sigue siendo el método con visibilidad de
oportunidades, que usa `solicitudes`.

### D33 — El ciclo se corta con `@Lazy`, siguiendo el precedente del repo

Cuando el Plan F haga que `oportunidades` consuma `SimulacionService`, la
inyección va `@Lazy`, con el mismo comentario que ya lleva
`OportunidadVisibilidad.tareaService`. En Plan D la dependencia es en un solo
sentido (`simulaciones` → `oportunidades`) y no hace falta.

### D34 — `TipoEventoSimulacion` va en `shared/enums/`, junto a `ModoSimulacion`

`shared/enums/` ya contiene `Enums.kt`, `EstadoMeta.kt`, `ModoSimulacion.kt` y
`SolicitudEnums.kt`: es la convención dominante para enums de BD.
`ModoSimulacion` ya vive ahí (lo necesita el motor, que está en `shared`). El
enum del log va al lado, en su propio archivo `TipoEventoSimulacion.kt` —
precedente `EstadoMeta.kt`, un enum por archivo cuando el nombre del archivo
puede coincidir con el de la clase (regla ktlint `standard:filename`, que ya nos
mordió en la tarea T1 del Plan 1).

### D35 — El `principal` de la validación §13 sale del motor, no se recalcula

§13 exige validar `valor_residual < Principal`. Pero `Principal` **difiere por
modo** (leasing: `VV − CuotaInicial_sinIGV`; crédito: `PV_efectivo − cuota_inicial`).
Recalcularlo en el Service sería una **tercera copia** de una fórmula que ya vive
en el motor — el mismo error que el Plan C tuvo que limpiar con `MontoTotal`.

Orden en el Service:

1. Validar `cuota_inicial < PV_efectivo` (fórmula directa, no depende del modo).
2. Ejecutar el motor **una vez**.
3. Validar `valor_residual < resultado.principal`.
4. Persistir con `resultado.cuotaFinal`.

Correr el motor antes de la validación 3 cuesta 48 iteraciones de `BigDecimal`:
despreciable frente a duplicar la fórmula.

### D36 — `modo` se acepta en el PATCH y se rechaza en el Service

§2 exige tres niveles de defensa, uno de ellos **el Service**. Si
`ActualizarSimulacionRequest` no declarara `modo`, no habría nada que validar
ahí y la única defensa de backend sería el trigger, que devuelve 500 (**K16**).

Por tanto `ActualizarSimulacionRequest.modo: String?`:
- ausente o igual al actual → sigue,
- distinto → `ConflictoException("MODO_INMUTABLE", …)` → **409** limpio.

### D37 — El nombre autogenerado se compone al leer, en un helper propio

§8.1 y la restricción 1 del encargo: **nunca se persiste**. Vive en
`domain/simulaciones/NombreSimulacion.kt`, función pura, testeable sin Spring.

```
{Empresa} · {Modelo} · {Modo} · #{n}
```

- `{Empresa}`: razón social vía `EmpresaService.resumenPorIds`; **`Sin enlazar`**
  cuando no hay ítem.
- `{Modelo}`: `codigo` vía `ModeloService.resumenPorIds`.
- `{Modo}`: `Leasing` / `Crédito Directo` (etiqueta legible, no el valor del enum).
- `#{n}`: correlativo por `id_oportunidad_item`; para las no enlazadas, por
  `id_modelo + modo` (**K17**, §8.1 dice explícitamente que no es crítico).

Nombre manual = **pegajoso**: si `nombre IS NOT NULL`, ese manda y no se
regenera nunca, ni al editar parámetros ni al enlazar a un ítem.

### D38 — `es_principal` en la creación: la nueva gana, dentro de la transacción

§6.3: *"Por defecto es la última creada para ese ítem."* Con ítem:

1. `UPDATE simulaciones SET es_principal = false WHERE id_oportunidad_item = :item AND es_principal = true`
2. Insertar la nueva con `es_principal = true`
3. Registrar `creada` (y, en el Plan E, `marcada_principal` cuando el cambio sea
   explícito del usuario)

**El orden importa** (**K14**): desmarcar primero, o el índice único parcial
aborta la transacción. Sin ítem, `es_principal = false` siempre (CHECK).

### D39 — El listado del módulo no necesita filtro por vendedor

Cae de **D30**: `GET /simulaciones` (listado del módulo) es 403 para `vendedor`,
`jdv` y `otro`, y los tres roles que sí entran (`admin`, `gerencia`, `analista`)
ven todo. Así que **no hay filtro de visibilidad en la query del listado** — algo
que sería un error grave copiar por inercia de `oportunidades`.

Filtros del listado, todos resolubles contra la propia tabla:
`id_oportunidad_item`, `id_modelo`, `modo`, más paginación estándar
(`Paginacion` / `CamposOrdenables`).

El vendedor llega a sus simulaciones por el contexto de la oportunidad (Plan F) y
por la Calculadora (Plan E), nunca por este listado.

### D40 — El cronograma es un endpoint aparte, no un campo del DTO

§4: no se persiste, se recalcula al leer. Meterlo en `SimulacionDto` obligaría a
correr el motor por cada fila de un listado de 20. Va en
`GET /simulaciones/:id/cronograma`, que corre el motor una vez y devuelve el
resultado completo.

### D41 — El reparto en tres planes

| Plan | Alcance | Documento de tareas |
|---|---|---|
| **D** | Fundación del módulo: enums, entidades, repositorios, `SimulacionPermisos`, DTOs, nombre autogenerado, Service + CRUD, cronograma, Controller | `plan-10-dominio-crud-tareas.md` |
| **E** | Historial con diff (§7.1), ventana de restauración (§7.2), bifurcar (§7.3), marcar principal (§6.3), Calculadora Financiera (§9) | pendiente, se redacta al cerrar D |
| **F** | Integración §6.2 con `oportunidades` (cuota por ítem y agregada), migración de enums de notificaciones, job de purga a 30 días, aviso a 3 días, documentación (§6 del encargo) | pendiente, se redacta al cerrar F |

Cada plan cierra con build verde, PR propio y CI en verde antes de empezar el
siguiente — igual que los Planes A/B/C, y coherente con el "para y resume al
terminar cada fase" del encargo.

### D42 — Lo que Plan D deliberadamente **no** hace

Para que ninguna tarea se salga de carril:

- No toca el motor (**K10**).
- No implementa historial, diff, restaurar, bifurcar ni marcar principal
  explícito (Plan E). El evento `creada` sí se registra desde D9; los demás tipos
  de evento del enum llegan en E y F.
- No implementa la Calculadora (Plan E).
- No toca `OportunidadDto` ni la cuota agregada §6.2 (Plan F).
- No escribe ninguna migración: Plan D no necesita cambios de schema. La única
  migración de todo el ciclo es la de enums de notificaciones, en Plan F
  (**K18**, **K19**).
- No genera PDF ni Excel (**K20**).

---

## 4. Alcance de Plan D — lista cerrada de archivos

Cualquier archivo fuera de esta lista: **parar y consultar.**

### Nuevos

```
src/main/kotlin/pe/quantum/crm/shared/enums/TipoEventoSimulacion.kt
src/main/kotlin/pe/quantum/crm/domain/simulaciones/Simulacion.kt
src/main/kotlin/pe/quantum/crm/domain/simulaciones/SimulacionLog.kt
src/main/kotlin/pe/quantum/crm/domain/simulaciones/SimulacionRepository.kt
src/main/kotlin/pe/quantum/crm/domain/simulaciones/SimulacionLogRepository.kt
src/main/kotlin/pe/quantum/crm/domain/simulaciones/SimulacionPermisos.kt
src/main/kotlin/pe/quantum/crm/domain/simulaciones/NombreSimulacion.kt
src/main/kotlin/pe/quantum/crm/domain/simulaciones/SimulacionService.kt
src/main/kotlin/pe/quantum/crm/domain/simulaciones/SimulacionServiceImpl.kt
src/main/kotlin/pe/quantum/crm/domain/simulaciones/SimulacionController.kt
src/main/kotlin/pe/quantum/crm/domain/simulaciones/dto/SimulacionDtos.kt
src/main/kotlin/pe/quantum/crm/domain/oportunidades/dto/OportunidadItemParaSimulacion.kt
src/test/kotlin/pe/quantum/crm/domain/simulaciones/SimulacionPermisosTest.kt
src/test/kotlin/pe/quantum/crm/domain/simulaciones/NombreSimulacionTest.kt
src/test/kotlin/pe/quantum/crm/domain/simulaciones/SimulacionServiceImplTest.kt
src/test/kotlin/pe/quantum/crm/domain/simulaciones/SimulacionCronogramaTest.kt
src/test/kotlin/pe/quantum/crm/domain/simulaciones/SimulacionControllerWebMvcTest.kt
src/test/kotlin/pe/quantum/crm/domain/simulaciones/SimulacionRepositoryTest.kt   [@Tag("integration")]
```

### Modificados

```
src/main/kotlin/pe/quantum/crm/domain/oportunidades/OportunidadItemService.kt      (+ datosParaSimulacion, D32)
src/main/kotlin/pe/quantum/crm/domain/oportunidades/OportunidadItemServiceImpl.kt  (implementación)
src/test/kotlin/pe/quantum/crm/domain/oportunidades/OportunidadItemServiceImplTest.kt (test del método nuevo)
```

`SchemaMigrationIntegrationTest.kt` **no se toca**: Plan D no añade migraciones,
así que las cuentas de tablas (24), enums (21) y migraciones (45/46) no cambian.
Ese archivo vive en `src/test/kotlin/pe/quantum/crm/db/` y **no aparece en
ningún grep de `domain/`** — fue justo el que costó dos rondas de CI en el
Plan B. Si alguna tarea cree que hay que tocarlo, es señal de que se coló una
migración: parar y consultar.

---

## 5. Riesgos conocidos y cómo los ataja el plan

| Riesgo | Mitigación en el plan |
|---|---|
| Copiar por inercia la visibilidad de `oportunidades` (K12) | D30/D31/D39 lo dicen explícitamente; la tarea de `SimulacionPermisos` es Opus/High y trae la matriz completa; la auditoría final lo verifica |
| Violar la frontera de módulos (regla 12) | D32 fija el único punto de contacto; ArchUnit corre en `./gradlew test` |
| Romper el índice único de `es_principal` (K14) | D38 fija el orden de las operaciones |
| INSERT de log que viola el CHECK (K15) | Cada tarea que registra un evento dice qué campos lleva |
| Bajar el coverage y romper el build (K21) | Cada tarea de servicio trae sus tests; la verificación final corre `koverVerify`… **salvo que arrastra `integrationTest`**, bloqueado en local por Docker 29 → se comprueba en CI |
| Falso verde sobre `@Tag("integration")` | Regla explícita en el encabezado del documento de tareas y punto propio en la auditoría final |
