# Simulaciones Financieras + Calculadora — Encargo para el FrontEnd

> Documento dirigido al agente/equipo de FrontEnd. El backend del módulo de financiamiento propio de Quantum está **terminado y en contrato**; falta toda la capa de interfaz. Extiende `contrato_api.md`: las convenciones generales (envelope `{data, meta, error}`, paginación, `snake_case`, montos como **string**, base `/api/v1`) aplican igual y no se repiten aquí.

**Fecha:** 2026-09-07 · **Estado del backend:** implementado, CI en verde (PR #15). Contrato estable.
**Alcance de este documento:** lo que el frontend tiene que construir, cómo consumir la API, y **cómo trabajar** (§1 no es opcional).

---

## 0. Resumen ejecutivo

1. Nace el módulo **Simulaciones**: el CRM reemplaza las corridas manuales en Excel (`LEASING.xlsx`, `RECONOCIMIENTO_DE_DEUDA.xlsx`) del financiamiento que da **Quantum** (no el de Calidda ni el de terceros). Dos modos: **Leasing** y **Crédito Directo**.
2. Una simulación es de **una unidad**, cuelga de un `oportunidad_item`, no de la oportunidad. Una oportunidad con 3 modelos lleva 3 simulaciones.
3. Nace la **Calculadora Financiera**: mismo motor, cero persistencia, para estimar durante la prospección sin que exista todavía una oportunidad.
4. `GET /oportunidades` y `GET /oportunidades/:id` ganan **5 campos de cuota** que el frontend debe mostrar. Son opcionales y pueden venir `null` de forma legítima.
5. Hay **11 endpoints nuevos** (10 de `/simulaciones` + `POST /calculadora`), todos ya documentados en `contrato_api.md` §23 y §24.
6. **Lo que el backend deliberadamente NO hace y es responsabilidad exclusiva del frontend:** la propuesta (`<PropuestaFinanciera/>`), la exportación a **PDF** y la exportación a **Excel** del cronograma (`reglas_simulaciones.md` §11). No existe ni existirá endpoint para eso.

---

## 1. Cómo trabajar — pautas de proceso (no negociables)

Estas son las reglas de trabajo que se aplicaron en el backend y que quiero replicadas acá. No son sugerencias de estilo: cada una nació de un error concreto que costó tiempo.

### 1.1 Los planes los redacta **Opus 5**, siempre

**Nunca Sonnet.** Un plan redactado en Sonnet arrastra sus errores de diseño a *todas* las tareas que se derivan de él, y para cuando aparecen ya hay diez archivos escritos encima. Esto se comprobó en la práctica: un plan del backend se redactó en Sonnet, se rehízo en Opus, y la versión en Opus encontró dos bugs de diseño reales que la anterior había pasado por alto.

Ejecutar las tareas sí puede ser Sonnet. **Redactar el plan, no.**

### 1.2 Dos documentos por plan, no uno

Cada plan son **dos archivos**:

| Archivo | Contenido |
|---|---|
| `plan-NN-mapa-<tema>.md` | Investigación previa, hallazgos numerados (`K1`, `K2`…) y decisiones numeradas (`D1`, `D2`…) con su justificación. Es donde se piensa. |
| `plan-NN+1-<tema>-tareas.md` | Las tareas atómicas de ejecución, numeradas, cada una con **modelo asignado** (Sonnet/Opus) y **nivel de esfuerzo**. Es donde se ejecuta. |

Las decisiones se citan por número (`D17`, `K34`) desde el código y desde las otras tareas. Eso permite que meses después se entienda *por qué* algo es como es sin reconstruir la conversación.

### 1.3 El plan abre citando las fuentes y cierra auditándose contra ellas

**Al principio del plan, antes de la Tarea 1:** una fase de investigación que diga explícitamente **qué documentos** y **qué reglas** tocan el cambio, y **qué dicen exactamente** sobre eso. No basta con "se leyeron los docs". El objetivo es que una regla ya escrita quede citada textualmente en el plan y no se pierda por el volumen de contexto de una sesión larga.

**Al final del plan, como tarea propia:** una tarea dedicada a **releer el diff completo de la rama** contra esos mismos documentos citados al principio — no solo contra lo que el plan pedía implementar. Se busca específicamente contradicciones con algo que **ya estaba escrito correctamente** antes de empezar. Es un caso distinto de "falta documentar X": acá lo que falla es que documentación ya vigente se ignoró o se pisó a mitad de camino.

Esta tarea de auditoría encontró 7 hallazgos reales en el último plan del backend. No es ceremonia.

### 1.4 Flujo de subagentes: uno por tarea, y el arquitecto verifica

- Una tarea = un subagente, con instrucciones autocontenidas (el subagente arranca en frío, no tiene el contexto de la conversación).
- El arquitecto (la sesión principal) **verifica cada tarea de forma independiente**: corre el build, los tests, el linter. **Nunca se da por buena la autoevaluación del subagente.** Más de una vez un subagente reportó verde sobre algo que no compilaba.
- Si un subagente encuentra que el plan estaba equivocado, **debe reportarlo, no decidir en silencio**. En el backend esto pasó tres veces y las tres el subagente tenía razón — pero el valor estuvo en que lo dijo.

### 1.5 Confirmá conmigo el reparto velocidad/rigor **antes** de arrancar

Antes de lanzar un flujo multi-agente con revisión por tarea, preguntame la mezcla de modelos y el nivel de rigor. No asumas Opus en todo (caro y lento) ni Sonnet en todo (arrastra errores). **Preguntá.**

### 1.6 Vistas nuevas → propuesta de diseño con **Google Stitch** primero

**Toda tarea que requiera implementar una vista que NO estaba en los prototipos iniciales debe pasar primero por una propuesta de diseño hecha con el MCP de Google Stitch, y esa propuesta me la presentás a mí para aprobación antes de escribir código de UI.**

- Aplica **solo a vistas nuevas**.
- **NO aplica** a la modificación puntual de vistas que ya existen en los prototipos (agregar un campo, una columna, un badge, una sección dentro de una pantalla ya diseñada). Esas se implementan directo.
- Ante la duda de si algo es "vista nueva" o "modificación", preguntá. El costo de preguntar es un mensaje; el de asumir mal es una pantalla rehecha.

En §5 marco cuáles creo que son nuevas y cuáles modificaciones, pero **contrastalo contra los prototipos reales**, que yo no los tengo a la vista desde el backend.

### 1.7 TDD y "no inventes"

- TDD igual que en el backend: el test que falla antes del código.
- **Si algo parece ambiguo o contradictorio, pará y preguntá.** No infieras comportamiento de negocio. Esto es producción con usuarios reales. Si necesitás un dato que la API no expone, decilo — se agrega al backend, no se inventa en el cliente.

---

## 2. Fuentes de verdad

| Documento | Para qué |
|---|---|
| `docs/reglas_simulaciones.md` | **La fuente de verdad del comportamiento.** Modos, fórmulas, purga, nombre, tarjeta, propuesta, permisos. Leelo entero antes de planificar. |
| `docs/contrato_api.md` §23 y §24 | Endpoints de Simulaciones y Calculadora: requests, responses, errores. |
| `docs/contrato_api.md` §10 | Oportunidades, incluidos los 5 campos de cuota nuevos. |
| `docs/contrato_api.md` §22 | `GET /tipo-cambio`. |
| `docs/matriz_permisos.md` §2.15 | Permisos del módulo (el reparto de roles acá es **atípico**, ver §6). |

El backend es dueño de `contrato_api.md` y `matriz_permisos.md`. Si el frontend necesita un cambio de contrato, se pide — no se parchea en el cliente.

---

## 3. Endpoints disponibles

Los 11 están documentados al detalle en `contrato_api.md` §23–§24. Acá va el mapa para planificar:

| Método | Ruta | Para qué |
|---|---|---|
| `POST` | `/simulaciones` | Crear simulación persistida. También es el botón **"Enlazar a Oportunidad"** de la Calculadora. |
| `GET` | `/simulaciones` | Listado del módulo, paginado. Filtros: `id_oportunidad_item`, `id_modelo`, `modo`. |
| `GET` | `/simulaciones/:id` | Detalle. |
| `GET` | `/simulaciones/:id/cronograma` | Cronograma completo, recalculado al vuelo. |
| `PATCH` | `/simulaciones/:id` | Edición parcial de parámetros. |
| `DELETE` | `/simulaciones/:id` | Borrado manual. |
| `GET` | `/simulaciones/:id/historial` | Ventana de versiones (7 días / 15) con `diff`. |
| `POST` | `/simulaciones/:id/restaurar` | Restaurar una versión del historial. |
| `POST` | `/simulaciones/:id/bifurcar` | **"Guardar como Nueva Simulación"**. Única vía para cambiar de `modo`. |
| `PATCH` | `/simulaciones/:id/principal` | Marcar como principal de su ítem. |
| `POST` | `/calculadora` | Cálculo efímero, sin persistencia. |

Ya existía y se usa acá: `GET /tipo-cambio` (§22).

---

## 4. Cambios en endpoints existentes

### 4.1 `GET /oportunidades` y `GET /oportunidades/:id` — 5 campos nuevos

**Por ítem** (dentro de `items[]`):

| Campo | Tipo | Significado |
|---|---|---|
| `cuota_quantum` | `string \| null` | `cuota_final` de la simulación **principal** del ítem; si no tiene ninguna, un cálculo efímero con los parámetros por defecto. |
| `cuota_total` | `string \| null` | `cuota_quantum + cuota_financiadora` del ítem. |

**A nivel de oportunidad** (raíz):

| Campo | Tipo | Significado |
|---|---|---|
| `cuota_quantum_total` | `string \| null` | Σ (`cuota_quantum` × `cantidad`) de todos los ítems. |
| `cuota_total` | `string \| null` | Σ (`cuota_total_item` × `cantidad`). |
| `cuota_diaria_total` | `string \| null` | `cuota_total / 22`. |

> ⚠️ **Cuidado con el nombre repetido:** `cuota_total` existe **en dos niveles con significados distintos** — dentro de un ítem es la cuota mensual de *una unidad de ese modelo*; en la raíz es el total mensual de *toda la operación, ya multiplicado por cantidades*. No los mezcles al tipar ni al renderizar.

**Reglas de `null` que el frontend debe respetar:**

- Los tres campos de nivel oportunidad son **`null` los tres a la vez** si *cualquier* ítem no tiene cuota calculable. Se muestra como **"todavía no se puede calcular"**, jamás como cero ni como guion suelto que parezca un monto.
- Un `cuota_quantum` de ítem en `null` es degradación silenciosa esperada (ítem incompleto, o precio incompatible con los parámetros por defecto), **nunca un error**. No dispares un toast de error por eso.

### 4.2 `POST /oportunidades/:id/items` y `PUT /oportunidades/:id/items/:item_id`

Estos dos devuelven `cuota_quantum` y `cuota_total` **siempre en `null`** — no porque no sean calculables, sino porque los endpoints de escritura no las resuelven. **Si después de crear o editar un ítem necesitás la cuota real, volvé a pedir la oportunidad.** No interpretes ese `null` como "sin cuota".

---

## 5. Qué hay que construir

Marco mi lectura de qué es vista nueva y qué es modificación, **para contrastar contra los prototipos** (§1.6).

### 5.1 Módulo Simulaciones — *vista nueva* → Stitch

Listado + detalle. Roles `admin`, `gerencia`, `analista` (el `vendedor` **no** entra acá, ver §6).

- **Tarjeta de simulación** (`reglas_simulaciones.md` §8.2), sin necesidad de abrir:

  | Posición | Campo |
  |---|---|
  | Título | `nombre` (real o autogenerado) |
  | Destacado | `cuota_final` |
  | Secundarios | `tea` · `valor_residual` · `cuota_inicial` |
  | Pie | fecha de última edición (`updated_at`) |

- Agrupación por **oportunidad** (usá `id_oportunidad`, que el backend ya deriva por vos) o por **empresa** (ver la limitación en §8.1).
- Badge de **simulación principal** (`es_principal`).
- Aviso visible de **eliminación prevista** en las huérfanas (§7.4).

### 5.2 Simulador dentro de la oportunidad — *modificación* de la vista de oportunidad

Es el flujo principal del vendedor. Sobre la pantalla de oportunidad que ya existe:

- Mostrar los 5 campos de cuota de §4.1.
- Entrar a simular el ítem: formulario de parámetros + cronograma + guardar.
- **Selector de ítem: solo si la oportunidad tiene más de un ítem.** Con un solo ítem se enlaza directo y **el usuario nunca ve un selector** (`reglas_simulaciones.md` §1.1). Esto es explícito y no se negocia.

### 5.3 Calculadora Financiera — *vista nueva* → Stitch

Estimación rápida en prospección. Roles: `admin`, `gerencia`, `analista`, `vendedor`.

- Formulario de parámetros → `POST /calculadora` → cronograma + propuesta.
- Puede jalar **opcionalmente** una empresa (`id_empresa`) o un modelo (`id_modelo`), aunque no exista oportunidad. Son puramente de presentación: no participan del cálculo.
- **No muestra `cuota_total`** (§6.2): sin ítem no hay `cuota_financiadora` que sumar. Solo cuota Quantum.
- Botón **"Enlazar a Oportunidad"** → `POST /simulaciones` con los mismos parámetros + el `id_oportunidad_item` elegido. Recién ahí nace la fila.

### 5.4 Cronograma — *componente nuevo* → Stitch

**Las columnas dependen del modo.** No es la misma tabla con celdas vacías:

- **Leasing:** `# | Saldo Inicial | Amortización | Interés | Saldo Final | Cuota | Cuota con IGV` — **sin columna de IGV**.
- **Crédito Directo:** `# | Saldo Inicial | Amortización | Interés | IGV | Saldo Final | Cuota | Cuota con IGV de Intereses`.

Detalles:
- El **mes 0** es la fila de la cuota inicial: `interes`, `igv`, `cuota` y `cuota_con_igv` vienen `null`. Se renderiza en blanco, no como "0.00".
- **La última celda de saldo final va destacada**: es el balloon (`valor_residual`).
- **No agregues una fila extra para el balloon.** El `LEASING.xlsx` original tenía una "cuota 49"; era un artificio de la hoja. Si el cronograma tiene 48 meses, se muestran 48 filas más el mes 0.
- `tasa_nominal_mensual` viene **sin redondear** a propósito. Mostrala con la precisión que decidas, pero no la trates como un monto de 2 decimales.

### 5.5 Historial de versiones y diff — *vista nueva* (modal/panel) → Stitch

- `GET /simulaciones/:id/historial` devuelve hasta **15 eventos de los últimos 7 días**, más recientes primero, solo de tipo `creada` / `editada` / `restaurada`.
- Cada evento trae `diff`: lista de `{campo, valor_anterior, valor_nuevo}`.
- **Un `diff` vacío es legítimo y frecuente**, no un bug: pasa en el primer evento de la simulación, y en cualquier escritura que no tocó parámetros de cálculo. Mostralo como "sin cambios de parámetros", no como error ni como fila rota.
- `created_by` puede venir `null` cuando el evento lo generó un job automático. Mostrar "Sistema", no "undefined".
- Botón restaurar → `POST /simulaciones/:id/restaurar` con `{id_evento_log}`.

### 5.6 `<PropuestaFinanciera/>` + PDF + Excel — *vista nueva* → Stitch

**Esto es 100% frontend. El backend no lo hace ni lo va a hacer** (`reglas_simulaciones.md` §11).

- **Propuesta:** componente que renderiza en HTML desde los datos de la simulación. Es la previsualización, siempre disponible.
- **PDF:** se genera y descarga **on demand** desde esa misma propuesta. **No se almacena** en ningún lado ni se registra como archivo en Drive.
- **Excel:** exportación del cronograma con el formato de las hojas actuales.
- Es la **misma** `<PropuestaFinanciera/>` para el módulo Simulaciones y para la Calculadora. Un solo componente.
- Debe mostrar la **cantidad de unidades del ítem** y el **modelo**, que no participan del cálculo. Ojo: ver §8.2 — `cantidad` no viene en el DTO de simulación.

### 5.7 Indicador de tipo de cambio — *modificación* del layout global

`GET /tipo-cambio`, mostrado de forma **permanente y discreta en una esquina del layout del CRM**. Es requisito del layout global, no del módulo.

- `data: null` con status **200** es una respuesta válida y esperada (el job diario todavía no pobló ninguna fila). **No lo trates como error ni como 404.** Simplemente no muestres el indicador.
- El valor es global y siempre en vivo; nunca un snapshot por simulación.

---

## 6. Permisos (`matriz_permisos.md` §2.15)

| Rol | Módulo Simulaciones | Simulador en su oportunidad | Calculadora |
|---|---|---|---|
| `admin` | Total | Sí | Sí |
| `analista` | **Total** | Sí | Sí |
| `gerencia` | Total | Sí | Sí |
| `vendedor` | **Sin acceso** | Solo donde es el vendedor asignado | Sí |
| `jdv`, `otro` | Sin acceso | No | No |

> Este es **el único módulo del CRM donde `analista` y `jdv` se invierten** respecto al resto:
> - `analista` es de solo lectura en oportunidades pero tiene **escritura completa** acá — es el rol dueño del módulo.
> - `jdv` es supervisor en oportunidades pero **no tiene ningún acceso** acá.
>
> Si el frontend tiene un helper tipo `esRolApoyo` o `esSupervisor` reutilizado de otros módulos, **no sirve para este**. El backend tuvo exactamente este problema y tuvo que centralizar la decisión en un punto propio. Hacé lo mismo: un solo lugar donde se decide el acceso a Simulaciones, no condicionales de rol repartidas por componente.

**Consecuencias de UI:**
- El `vendedor` **no ve la entrada al módulo Simulaciones** en la navegación. Llega a sus simulaciones por el simulador de su propia oportunidad y por la Calculadora.
- `jdv` y `otro` no ven ni el módulo ni la Calculadora.

---

## 7. Reglas de negocio que el frontend debe respetar

### 7.1 `modo` es inmutable

Una vez creada, una simulación **no puede cambiar de modo** — las fórmulas y las columnas del cronograma difieren entre ambos.

- El campo va **deshabilitado** en el formulario de edición.
- Un `PATCH` que intente cambiarlo responde **`409 MODO_INMUTABLE`**.
- La única vía autorizada es **"Guardar como Nueva Simulación"** (`POST /simulaciones/:id/bifurcar`), que sí lo acepta y lo aplica sobre una fila **nueva**; el origen queda intacto.

### 7.2 `cuota_final` es de solo lectura, siempre

El backend la recalcula server-side al crear, actualizar, restaurar y bifurcar. **Nunca la envíes en un body** — se ignora. No la calcules en el cliente ni la caches: si el usuario edita parámetros, la cuota buena es la que devuelve la respuesta.

### 7.3 El nombre es pegajoso

`nombre` es opcional. Si viene `null`, el backend **autogenera** al leer, con formato `{Empresa} · {Modelo} · {Modo} · #{n}` (ej.: `Transportes Lima SAC · MB-O500 · Leasing · #2`, o `Sin enlazar · MB-O500 · Crédito Directo · #1`). Nunca se persiste.

Si el usuario escribe un nombre manual, **ese manda y no se regenera nunca**, aunque después se editen los parámetros o se enlace a un ítem. El campo `nombre_es_manual` te dice cuál de los dos casos es — usalo para decidir si mostrar el nombre como placeholder editable o como valor.

### 7.4 Simulaciones huérfanas: avisar en la UI

Una simulación sin `id_oportunidad_item` se **borra por hard delete a los 30 días** de creada, con aviso al creador 3 días antes vía notificaciones.

- **La regla debe ser visible en la UI, no solo lógica de servidor** (`reglas_simulaciones.md` §5). La simulación huérfana muestra su **fecha de eliminación prevista**: viene lista en `eliminacion_prevista_el`.
- `eliminacion_prevista_el` es `null` en cuanto la simulación está enlazada — **enlazarla la salva de forma definitiva y permanente**.
- Al guardar una simulación **sin** ítem, el frontend debe **advertir** y ofrecer dos salidas: buscar una oportunidad para enlazar, o confirmar el guardado sin enlace.

### 7.5 Simulación principal

- Por defecto, la principal de un ítem es la **última creada** para ese ítem.
- Enlazar una simulación a un ítem la convierte automáticamente en la principal (desmarca la anterior).
- Se puede cambiar manualmente con `PATCH /simulaciones/:id/principal`.
- **Sin ítem, `es_principal` es siempre `false`** — no ofrezcas el botón en una huérfana; responde `400 VALIDACION`.
- Marcar como principal una que ya lo es es un **no-op exitoso**, no un error.

### 7.6 Validaciones que conviene replicar en el cliente (UX proactiva)

El backend valida y responde `400 VALIDACION`; el frontend **puede** adelantarse para evitar el round-trip, pero **la validación autoritativa es siempre la del backend**:

- `cuota_inicial < precio_venta × (1 − descuento/100)`
- `valor_residual < Principal`

Ojo con la escala de `tea`: acá va de **1 a 100** (ej. `14.00` = 14%). En `financiadoras` es fraccionaria. **No las mezcles.**

### 7.7 Defaults del formulario

Cuando el usuario abre el simulador sin parámetros previos:

| Parámetro | Valor por defecto |
|---|---|
| `plazo_meses` | 48 |
| `tea` | 14 |
| `cuota_inicial` | 45 000 |
| `valor_residual` | 25 000 |
| `dias_trabajados` | 22 |
| `comision_estructuracion` | 1 180 |

`precio_venta` y `descuento` se toman del ítem. Solo `modo`, `precio_venta`, `cuota_inicial`, `plazo_meses` y `tea` son obligatorios en el request; el resto, si se omite, el backend los rellena con esta tabla.

---

## 8. Puntos abiertos y limitaciones conocidas

Los declaro para que no se descubran a mitad de una tarea. Si alguno bloquea, **decímelo y lo resolvemos en el backend** — no lo parchees en el cliente.

### 8.1 Agrupar por empresa requiere una resolución extra

`reglas_simulaciones.md` §8.2 dice que en el módulo completo las simulaciones se agrupan "por oportunidad **o por empresa**".

- Agrupar **por oportunidad**: resuelto. El DTO trae `id_oportunidad` ya derivado del ítem.
- Agrupar **por empresa**: el DTO de simulación **no trae `id_empresa` ni el objeto empresa**. La razón social aparece embebida dentro del `nombre` autogenerado, pero eso es un string de presentación, no un dato para agrupar (y desaparece si el usuario puso nombre manual).

Si el diseño de la vista necesita agrupar por empresa de verdad, **pedime que el backend exponga `id_empresa`/`empresa` en el DTO**. Es un cambio chico. No lo resuelvas parseando el nombre.

### 8.2 La propuesta necesita `cantidad`, que no está en el DTO de simulación

`reglas_simulaciones.md` §11 pide que la propuesta muestre la **cantidad de unidades del ítem**. La simulación es de una unidad y su DTO **no trae `cantidad`** — hay que sacarla del ítem vía la oportunidad (`GET /oportunidades/:id` → `items[]`).

Para una simulación **huérfana** (sin ítem) no hay cantidad que mostrar. Definí en el diseño qué hace la propuesta en ese caso; si preferís que el backend exponga `cantidad` en el DTO de simulación, pedilo.

### 8.3 El aviso de purga es best-effort

El aviso de 3 días antes se manda con un job diario. Si el job no corre un día, esas simulaciones **se quedan sin aviso y aun así se purgan a los 30 días**. Es una decisión consciente del backend, no un bug. La UI no debe prometer al usuario que "siempre te vamos a avisar" — la garantía real es `eliminacion_prevista_el`, que está siempre visible.

---

## 9. Errores a manejar

| Código HTTP | `error.code` | Cuándo | Qué hacer en la UI |
|---|---|---|---|
| 409 | `MODO_INMUTABLE` | `PATCH` intentando cambiar `modo` | No debería ocurrir si el campo está deshabilitado (§7.1). Si ocurre, ofrecer "Guardar como Nueva Simulación". |
| 400 | `VALIDACION` | §13 incumplida, `modo` fuera de enum, principal sin ítem | Mensaje inline en el campo (`error.field` cuando viene). |
| 404 | `NO_ENCONTRADO` | Simulación ajena o inexistente; `id_evento_log` fuera de la ventana | Tratar como "no existe". **No** inferir "existe pero no tenés permiso" — el backend devuelve 404 para recursos ajenos a propósito (IDOR). |
| 403 | `PERMISO_INSUFICIENTE` | `jdv`/`otro` en cualquier función; `vendedor` en `GET /simulaciones` | No debería ocurrir si la navegación respeta §6. Es la red de seguridad. |

Nota sobre el 404 de `restaurar`: cubre cuatro motivos distintos (no existe, no es de esta simulación, fuera de la ventana de 7 días/15 versiones, o tipo no restaurable) y **el backend no distingue cuál falló** a propósito. El mensaje al usuario debe ser genérico: "esa versión ya no se puede restaurar".

---

## 10. Fases sugeridas

Orden propuesto, para validar temprano lo que más riesgo tiene. Ajustalo en el plan si ves mejor camino, pero justificá el cambio.

| Fase | Qué | Por qué acá |
|---|---|---|
| 1 | Los 5 campos de cuota en la vista de oportunidad (§4.1) + indicador de tipo de cambio (§5.7) | Son **modificaciones**, no requieren Stitch. Valor inmediato y contacto temprano con la API. |
| 2 | Componente de cronograma (§5.4) | Es el núcleo visual que reutilizan las otras tres vistas. Hacerlo primero evita rehacerlo tres veces. |
| 3 | Calculadora Financiera (§5.3) | La vista más autocontenida: un `POST` sin persistencia. Buen banco de pruebas del cronograma. |
| 4 | Simulador en la oportunidad (§5.2) | El flujo principal del vendedor. Ya con cronograma probado. |
| 5 | Módulo Simulaciones: listado, tarjetas, principal, huérfanas (§5.1) | Depende de tener el detalle resuelto. |
| 6 | Historial y diff (§5.5) | Independiente y acotado. |
| 7 | `<PropuestaFinanciera/>` + PDF + Excel (§5.6) | Lo último: necesita todo lo anterior estable, y es lo único sin ningún soporte de backend. |

**Al terminar cada fase, pará y resumí qué hiciste antes de seguir.**
