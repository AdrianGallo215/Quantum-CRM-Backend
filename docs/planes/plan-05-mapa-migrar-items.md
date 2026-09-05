# Mapa — Plan B: el dominio migra a `oportunidad_items`

> Documento de coordinación del "migrate" de la estrategia expand → migrate →
> contract (`plan-03-mapa-oportunidad-items.md` §4). No contiene tareas
> ejecutables: fija los hallazgos de la investigación de código y las
> decisiones de diseño que `plan-06-migrar-dominio-items.md` da por cerradas.
>
> Redactado: 2026-09-03, tras cerrar el Plan A (`plan-04-fundacion-items.md`).
> `oportunidad_items` ya existe en producción, poblada, pero **nada la lee
> todavía**. Este plan es lo que empieza a leerla.

---

## 1. Qué cambia de comportamiento (a diferencia del Plan A)

El Plan A no cambiaba nada observable. **Este sí.** Es el punto de corte donde
`oportunidades` deja de ser plana y pasa a exponer `items: [...]`. Rompe el
contrato de API (decisión D6 de `plan-03`, ya aceptada).

---

## 2. Hallazgos de la investigación de código

| # | Hallazgo | Evidencia | Consecuencia |
|---|---|---|---|
| J1 | `OportunidadServiceImpl` es un archivo de 787 líneas con 8+ responsabilidades entrelazadas en la misma clase: alta transaccional (crea Drive, log, notifica), edición, cambio de estado, event listener de traspaso de vendedor, contactos, descuento aprobado, Drive | Lectura completa del archivo | No se puede tocar en una sola tarea. Se reparte por método/responsabilidad |
| J2 | `crear()` hace **8 pasos en una sola transacción**: snapshot de vendedor, resolver modelo/financiadora, guardar oportunidad, guardar log, vincular contactos, crear carpeta Drive, actualizar estado de cartera, notificar | `crear()` líneas 76-170 | El alta de ítems entra en medio de esta secuencia, no antes ni después: debe ir donde hoy se construye la entidad `Oportunidad`, dentro de la misma transacción |
| J3 | `actualizar()` reescribe campos con `?.let` y **recalcula `monto_total` siempre**, incluso si no cambió nada del precio | líneas 175-221 | El nuevo `actualizar()` ya no calcula nada él mismo: delega a la agregación de ítems |
| J4 | `aplicarDescuentoAprobado(id, dcto, idAprobador)` recibe el **id de la oportunidad**, lo llama `SolicitudServiceImpl` al aprobar (línea 280-284) | `OportunidadServiceImpl.kt:444-464`, `SolicitudServiceImpl.kt:279-284` | Cambia de firma a nivel de ítem. Cruza módulo: hay que coordinar ambos archivos en la misma tarea o en tareas consecutivas con contrato ya fijado |
| J5 | `solicitudes` usa un patrón polimórfico ya existente (`entidad_tipo` + `entidad_id`, valores hoy `oportunidad`/`empresa`) para las tres cosas que puede aprobar | `Solicitud.kt`, `SolicitudEnums.kt:27-30` | **Decisión del dueño del producto (2026-09-03): se añade el valor `oportunidad_item`** al enum en vez de una columna nueva. Ver D12 |
| J6 | `validarDescuento()` en `SolicitudServiceImpl` resuelve la oportunidad con `oportunidadService.vinculoVisible(entidadId, usuario)` para el chequeo IDOR | `SolicitudServiceImpl.kt:104-107` | Necesita el equivalente a nivel de ítem: `OportunidadItemService` debe exponer un `vinculoVisible(idItem, usuario)` que resuelva IDOR igual que hoy (ajeno → 404, `CLAUDE.md` regla 14) |
| J7 | `CAMPOS_ORDENABLES` permite `sort=cantidad`, `sort=precioUnitario`, `sort=montoTotal` — hoy son columnas simples de `oportunidades` | `OportunidadServiceImpl.kt:774-785` | Con los campos en `oportunidad_items`, ordenar por un agregado exige subconsulta. `precioUnitario` se retira (D9 de `plan-03`); `cantidad` y `montoTotal` se mantienen vía subconsulta |
| J8 | El nombre de la carpeta de Drive usa `nombreCarpetaDrive(id, codigoModelo)`, y `codigoModelo` sale de `modeloService.resumen(oportunidad.idModelo)` — la columna vieja | `OportunidadServiceImpl.kt:589-604, 619-622` | Con varios ítems no hay un único código de modelo. D9 de `plan-03` ya decidió: las carpetas nuevas dejan de llevar el código; las 5 existentes no se tocan |
| J9 | `toDtos()` ensambla el DTO por lotes, ya sin N+1, cargando financiadora/vendedor/empresa/modelo/tareas/eventos por `resumenPorIds` | `OportunidadServiceImpl.kt:726-771` | Los ítems se cargan igual: `OportunidadItemRepository.findByIdOportunidadInOrderByIdAsc(ids)` (ya existe desde el Plan A, hecho justo para esto) |
| J10 | Otro consumidor no listado en `plan-03`: `OportunidadesDeContacto.kt` construye `OportunidadResumenParaContacto` con `montoTotal` e `idModelo` propios, para el resumen de oportunidades de un contacto | `OportunidadesDeContacto.kt:84-95`, `dto/OportunidadResumenParaContacto.kt:12` | Se suma a la lista de archivos a tocar. **No estaba en el I7 de `plan-03`** — la investigación de código de esta fase lo encontró |
| J11 | `PoliticaDescuento.excedeLimite`/`aprobadorPara` reciben el `rol` y el `dcto` como `BigDecimal?`, sin conocer oportunidades ni ítems | `shared/PoliticaDescuento.kt` | No cambia. Se sigue llamando igual, solo que ahora una vez por ítem en vez de una vez por oportunidad |
| J12 | El contrato documentado (`contrato_api.md §10`) expone `id_modelo`, `modelo`, `cantidad`, `precio_unitario`, `dcto`, `monto_total` **en la raíz** del objeto oportunidad, tanto en `POST` (body) como en las respuestas | `contrato_api.md:925-1020` | Confirma el alcance de la reescritura de contrato que ya anticipaba `plan-03 §5` |
| J13 | 13 archivos de test tocan estos campos (`plan-03` I8); a eso se suma que `OportunidadServiceImplTest.kt` (743 líneas) y `OportunidadControllerWebMvcTest.kt` (459 líneas) son los dos test que más van a doler, por volumen | `grep` sobre `src/test/` | Repartir la reescritura de tests por archivo, no en un tarea única |

---

## 3. Decisiones de diseño

### D12 · `solicitudes` referencia al ítem vía enum, no vía columna

**Decisión del dueño del producto, 2026-09-03.** Se añade `oportunidad_item` a
`entidad_solicitud_enum` (migración `V44`, `ALTER TYPE ... ADD VALUE`, sin
backfill: no hay solicitudes de descuento pendientes hoy — I6 de `plan-03`
confirmó 0 pendientes). `TipoSolicitud.descuento` exige desde ahora
`entidadTipo == EntidadSolicitud.oportunidad_item` y `entidadId` = id del ítem.

Sigue exactamente el patrón ya existente (mismo mecanismo que `empresa` para
`reasignacion_cliente`), sin agregar una columna que solo tendría sentido para
un tipo de solicitud.

### D13 · Sort por agregado: subconsulta, no columna

`sort=cantidad` y `sort=monto_total` (J7) se resuelven con una subconsulta
correlacionada de suma sobre `oportunidad_items`, añadida como
`orderBy`/`Expression` en la misma `Specification<Oportunidad>` que ya existe.
`sort=precio_unitario` se retira del contrato (D9 de `plan-03`: no significa
nada con varios modelos).

### D14 · `OportunidadItemService` expone su propio `vinculoVisible`

Resuelve J6. Misma forma que `OportunidadService.vinculoVisible` (IDOR: ajeno →
404, nunca 403 — `CLAUDE.md` regla 14), pero a nivel de ítem. Devuelve un DTO
mínimo con `id`, `idOportunidad`, `idEmpresa` (para el mensaje de la solicitud)
y `descuento` actual.

### D15 · `MontoTotal` gana una segunda función: sumar ítems

`MontoTotal.calcular(cantidad, precio, descuento)` (la fórmula por ítem) **no
cambia** — sigue siendo la fórmula base, ahora aplicada por ítem. Se añade
`MontoTotal.sumarItems(items: List<OportunidadItem>): BigDecimal?` que suma los
subtotales, tratando cualquier ítem con `cantidad`/`precioVenta` nulo como
`0` en la suma (igual que hoy un solo campo nulo da `monto_total = null` a
nivel de oportunidad — aquí un ítem incompleto no debe tumbar el total de los
demás ítems que sí están completos). **Null solo si TODOS los ítems son
incompletos o no hay ítems.**

### D16 · `crear()` sigue creando exactamente un ítem

El Plan B **no** añade todavía el endpoint para que `POST /oportunidades`
reciba un array de ítems: eso es explícitamente **fuera de alcance** (el
propósito original del cambio — "las oportunidades pasan a aceptar varias
unidades distintas", `Instrucciones_simulaciones.md`) pero **no** es lo que
este plan resuelve. Este plan hace la migración estructural: `oportunidades`
pasa a **leer y escribir a través de ítems** en vez de columnas planas, con
exactamente un ítem por oportunidad (igual que hoy), preservando el
comportamiento observable salvo por la forma del JSON.

> **Por qué se separa así:** meter "múltiples ítems en la creación" en el mismo
> plan que "migrar el storage" duplica el riesgo. Primero se prueba que el
> nuevo storage funciona exactamente igual que el viejo (mismo comportamiento,
> forma de contrato distinta), y **luego**, en un plan D posterior, se habilita
> agregar/quitar ítems de una oportunidad existente vía los endpoints CRUD que
> este plan sí construye (`POST/PUT/DELETE /oportunidades/:id/items`) — la
> capacidad de tener varios ítems por oportunidad queda **disponible** al
> cerrar este plan, pero **crear una oportunidad con más de uno de entrada**
> quedaría fuera si el frontend no lo pide todavía. Confirmar con el dueño del
> producto si hace falta ese paso extra al cerrar este plan.

### D17 · El endpoint de ítems es un sub-recurso de oportunidad

`POST /oportunidades/:id/items`, `PUT /oportunidades/:id/items/:itemId`,
`DELETE /oportunidades/:id/items/:itemId`. Mismo patrón que
`/oportunidades/:id/contactos`, ya existente en el propio controller.
**Restricción de negocio:** una oportunidad no puede quedarse con **cero**
ítems — `DELETE` del último ítem de una oportunidad → `409` con
`ConflictoException("ULTIMO_ITEM_NO_ELIMINABLE", ...)`, la excepción genérica
que ya existe en `shared/exception/NegocioExceptions.kt` (mismo patrón que
`SOLICITUD_NO_APLICABLE` en `aplicarDescuentoAprobado`). No hace falta una
clase de excepción nueva.

### D18 · Drive: nombre de carpeta para ítems nuevos

`nombreCarpetaDrive(idOportunidad, codigoModelo)` se simplifica a
`nombreCarpetaDrive(idOportunidad)` → `"OP-{id}"`, sin código de modelo (J8,
D9 de `plan-03`). Las carpetas ya creadas (`drive_folder_id` ya poblado) no se
tocan: la función solo se invoca cuando `driveFolderId` es `null`.

### D19 · Forma final del contrato de `POST`/`PUT /oportunidades`

Derivada de D16 y D17, para que ningún ejecutor tenga que inventarla:

- **`POST /oportunidades`** sigue aceptando los campos del ítem **planos en el
  body**, igual de ergonómico que hoy — no obliga al cliente a mandar un array
  de un elemento para el caso común. Solo cambian los **nombres**, para dejar
  de tener dos nombres distintos para el mismo concepto en el mismo código:
  `precio_unitario` → `precio_venta`, `dcto` → `descuento` (alineados con
  `oportunidad_items` desde V42). `id_modelo` y `cantidad` no cambian de
  nombre. Internamente, `crear()` construye **un** `OportunidadItem` con esos
  campos en vez de escribirlos en la propia `Oportunidad`.
- **`PUT /oportunidades/:id`** **deja de aceptar** `id_modelo`, `cantidad`,
  `precio_venta`/`precio_unitario`, `descuento`/`dcto` y `monto_total` por
  completo. Edición de esos campos pasa exclusivamente por
  `PUT /oportunidades/:id/items/:itemId` (D17). `ActualizarOportunidadRequest`
  se queda solo con `garantia`, `finc_paralelo`, `ficha_venta`, `notas`,
  `fecha_cierre_estimado`. **Ya no hace falta el guard de `montoTotal` en el
  body** (`MontoNoEditableException`): como el campo ni siquiera está en el
  DTO, Jackson lo ignora solo con `@JsonIgnoreProperties(ignoreUnknown = true)`
  — mismo mecanismo que ya usa `CrearOportunidadItemRequest`. **`MontoNoEditableException`
  ya no se usa en ningún sitio tras este cambio: se elimina de `NegocioExceptions.kt`
  si detekt/ktlint marcan código muerto, o se deja si algo más la referencia —
  verificar antes de borrar.**
- **Respuesta** (`GET`/`POST`/`PUT`, listado y detalle): `OportunidadDto`
  pierde `id_modelo`, `modelo`, `cantidad`, `precio_unitario`, `dcto` de la
  raíz. Gana `items: List<OportunidadItemDto>` (el DTO ya existe desde el
  Plan A, en `OportunidadItemDtos.kt`). `monto_total` **se queda en la raíz**,
  pero ahora es la suma de los ítems (D15), nunca columna.

### D20 · `cuota_financiadora` no se expone todavía a nivel de oportunidad

`reglas_simulaciones.md §6.2` describe una agregación de `cuota_financiadora`
a nivel de oportunidad (`cuota_total`), pero eso requiere el módulo
`simulaciones` (Plan 3 de `plan-00-mapa-simulaciones.md`), que no existe
todavía. Este plan expone `cuota_financiadora` **dentro de cada
`OportunidadItemDto`** (ya está en el DTO del Plan A) y no agrega ninguna
agregación a nivel de oportunidad. Eso es trabajo del plan de persistencia de
simulaciones, no de este.

### D21 · Sincronización de las columnas viejas mientras `reportes`/`inicio` no migran (decisión del dueño del producto, 2026-09-03, mecánica derivada)

El dueño del producto decidió: el Plan B **sí** incluye CRUD de ítems
(crear/editar/borrar sobre una oportunidad ya existente, no solo al crearla), y
**cada escritura sobre un ítem sincroniza las columnas viejas de
`oportunidades`**, para que `reportes`/`inicio` (que siguen leyendo esas
columnas hasta el Plan C) no muestren números desactualizados en el hueco entre
planes.

Verificado en V10: de las columnas que se sincronizan, **solo `id_modelo` es
`NOT NULL`**; `cantidad`, `precio_unitario`, `dcto` y `monto_total` son
nullable. Regla de sincronización, ejecutada después de cada
creación/edición/borrado de ítem, sobre el estado resultante de **todos** los
ítems de la oportunidad:

```
cantidad      = Σ item.cantidad                    (SUM tiene sentido: total de unidades)
monto_total   = MontoTotal.sumarItems(items)        (ya es la formula de D15)
precio_unitario, dcto:
    si hay exactamente 1 ítem → los valores de ESE ítem (precioVenta, descuento)
    si hay 0 o 2+ ítems       → NULL (un "precio unitario" no significa nada
                                 con varios modelos, y NULL es honesto — nunca
                                 se inventa un promedio que reportes leería
                                 como si fuera un precio real)
id_modelo:
    si hay 1+ ítems → el id_modelo del ítem de MENOR id (el primero creado,
                       estable ante ediciones posteriores)
    si hay 0 ítems  → imposible: D17 prohíbe dejar una oportunidad sin ítems
```

**Corrección tras la ejecución de B3 (2026-09-03):** esta función quedó
implementada **dentro de `OportunidadItemServiceImpl`** (privada, invocada al
final de `crear`/`actualizar`/`eliminar`), no en `OportunidadServiceImpl` como
decía la redacción original de este párrafo. Es la ubicación correcta: ambos
Services viven en el mismo módulo (`domain.oportunidades`, sin cruce de
frontera de ArchUnit), y mantener escritura + sincronización en la misma clase
transaccional evita el patrón `estadoCarteraService.actualizar()` (un servicio
disparando efectos en otro) para algo que es, en realidad, una sola operación
atómica sobre el mismo agregado. Queda documentado aquí para que ningún plan
posterior busque la función en el lugar equivocado.

**Es código puente, no arquitectura final.** El comentario en el código debe
decir explícitamente que esta sincronización se **elimina por completo** al
cerrar el Plan C (cuando `reportes`/`inicio` empiecen a leer `oportunidad_items`
directamente y las columnas viejas se retiren). No es una decisión de diseño
para quedarse: es el costo de mantener los reportes correctos durante la
transición.

---

## 4. Migración necesaria: V44

Única migración de este plan. Minúscula comparada con V42/V43:

```sql
ALTER TYPE entidad_solicitud_enum ADD VALUE 'oportunidad_item';
```

Sin backfill (I6: 0 solicitudes de descuento pendientes). Sin `DO $$` de
verificación: `ALTER TYPE ... ADD VALUE` no puede fallar a medias.

> **Nota de Postgres:** `ALTER TYPE ... ADD VALUE` no puede ejecutarse dentro
> de la misma transacción que un `INSERT`/`UPDATE` que ya use el enum, pero
> **sí** puede aplicarse sola, como esta migración. No combinar con nada más
> en el mismo archivo.

---

## 5. Alcance — lista de archivos que este plan toca

**Nuevos:**
```
src/main/resources/db/migration/V44__solicitudes_entidad_item.sql
src/main/kotlin/pe/quantum/crm/domain/oportunidades/OportunidadItemService.kt
src/main/kotlin/pe/quantum/crm/domain/oportunidades/OportunidadItemServiceImpl.kt
src/main/kotlin/pe/quantum/crm/domain/oportunidades/OportunidadItemController.kt
src/test/kotlin/pe/quantum/crm/domain/oportunidades/OportunidadItemServiceImplTest.kt
src/test/kotlin/pe/quantum/crm/domain/oportunidades/OportunidadItemControllerWebMvcTest.kt
```

**Modificados:**
```
src/main/kotlin/pe/quantum/crm/domain/oportunidades/OportunidadServiceImpl.kt
src/main/kotlin/pe/quantum/crm/domain/oportunidades/OportunidadService.kt          (firma de aplicarDescuentoAprobado)
src/main/kotlin/pe/quantum/crm/domain/oportunidades/MontoTotal.kt
src/main/kotlin/pe/quantum/crm/domain/oportunidades/dto/OportunidadDtos.kt
src/main/kotlin/pe/quantum/crm/domain/oportunidades/OportunidadesDeContacto.kt
src/main/kotlin/pe/quantum/crm/domain/oportunidades/dto/OportunidadResumenParaContacto.kt
src/main/kotlin/pe/quantum/crm/shared/enums/SolicitudEnums.kt                     (+ oportunidad_item)
src/main/kotlin/pe/quantum/crm/domain/solicitudes/SolicitudServiceImpl.kt
src/test/kotlin/pe/quantum/crm/domain/oportunidades/*.kt                          (los 13 de plan-03 I8, menos reportes)
docs/contrato_api.md, docs/matriz_permisos.md
```

**Fuera de alcance explícito de este plan:** `domain/reportes/`, `domain/inicio/`
(son del Plan C, agrupan por ítem — D7 de `plan-03`, requiere que este plan
cierre primero). Ningún `DROP COLUMN` sobre `oportunidades` (Plan C).

---

## 6. Reparto en tareas — ver `plan-06-migrar-dominio-items.md`

Este mapa no numera tareas atómicas: eso vive en el documento hermano, para
que un ejecutor no tenga que leer dos veces la misma decisión repartida entre
"qué" y "cómo".
