# Quantum CRM — Reglas de Negocio

> Documento de referencia para el desarrollo. Toda lógica descrita aquí debe implementarse en el backend (Spring Boot). Claude Code no debe inferir ni inventar comportamiento no documentado — cualquier ambigüedad debe consultarse antes de implementar.

---

## Índice

1. [Principios generales](#1-principios-generales)
2. [Entidades y restricciones base](#2-entidades-y-restricciones-base)
3. [Estado de cartera](#3-estado-de-cartera)
4. [Pipeline de oportunidades](#4-pipeline-de-oportunidades)
5. [Eventos y cambios de estado](#5-eventos-y-cambios-de-estado)
6. [Pronta facturación](#6-pronta-facturación)
7. [Monto total y precio unitario](#7-monto-total-y-precio-unitario)
8. [Vendedor y asignación](#8-vendedor-y-asignación)
9. [Financiadoras](#9-financiadoras)
10. [Prospección](#10-prospección)
11. [Contactos y empresas](#11-contactos-y-empresas)
12. [Modelos de bus](#12-modelos-de-bus)
13. [Retroceso de estado](#13-retroceso-de-estado)

---

## 1. Principios generales

### 1.1 Unicidad de datos
Ningún dato puede vivir en dos lugares con valores que puedan divergir. Si un valor es derivado de otro, se calcula en el backend y se lee vía JOIN. La única excepción aceptada es el snapshot de `id_vendedor` en `oportunidades` (sección 8): se mantiene sincronizado automáticamente con `empresas.id_vendedor` mientras la oportunidad esté activa, así que nunca queda divergente en la práctica.

### 1.2 Atomicidad obligatoria
Toda operación que afecte más de una tabla debe ejecutarse en una única transacción. Si cualquier paso falla, ningún cambio se persiste. Esto aplica especialmente a:
- Cambios de estado de oportunidad
- Actualización de `estado_cartera`
- Registro de eventos que disparan cambio de estado
- Creación de oportunidades

### 1.3 El CRM sirve al vendedor
Ninguna regla debe bloquear el flujo de trabajo del vendedor salvo las excepciones explícitas en este documento (`motivo_cierre`, validación de fee). Si una regla genera más fricción que valor, se implementa como advertencia, no como bloqueo.

### 1.4 Solo personas jurídicas
Quantum vende exclusivamente a empresas (personas jurídicas). Todo cliente es una `empresa` con RUC válido de 11 dígitos. No existe lógica de persona natural en el sistema.

---

## 2. Entidades y restricciones base

### 2.1 RUC
- Almacenado como `VARCHAR(11)`. Nunca como entero.
- Debe ser único en la tabla `empresas`.
- Antes de crear una empresa, el backend valida si el RUC ya existe.
- Si existe y está asignada a otro vendedor, la respuesta es `409 Conflict` con el mensaje: `"Esta empresa ya está registrada en el sistema"`. No se expone a qué vendedor pertenece.
- Si existe y está asignada al mismo vendedor, se retorna la empresa existente con `200 OK`.

### 2.2 Empresas
- `estado_sunat` y `condicion_sunat` vienen de SUNAT. Son campos de texto libre; el sistema no los valida contra un catálogo.
- `estado_cartera` nunca se actualiza directamente desde un endpoint propio. Solo se modifica vía `actualizarEstadoCartera()`. Ver sección 3.
- `id_vendedor` puede ser `NULL` (empresa sin asignar). Solo admin, gerente y JdV pueden asignar o reasignar.

### 2.3 Segmentos de empresa
- Multi-select. Una empresa puede pertenecer a más de un segmento.
- Valores válidos: `urbano`, `personal`, `turismo`, `interprovincial`, `otro`.
- Se gestionan en la tabla `empresa_segmentos`. No hay campo de texto libre para segmentos.

### 2.4 Modelos de bus
- Todo modelo debe tener al menos una `aplicacion` en `modelo_aplicaciones`.
- La creación de un modelo y sus aplicaciones es una operación atómica. Si el array de aplicaciones viene vacío, el backend retorna `400 Bad Request` sin persistir nada.
- `precio_base` es el precio de referencia del catálogo. No es el precio final de una operación.

---

## 3. Estado de cartera

### 3.1 Tipos de estado

| Estado | Tipo | Quién lo establece |
|---|---|---|
| `no_contactado` | Manual | Usuario |
| `no_aplica` | Manual | Usuario |
| `no_interesado` | Manual | Usuario |
| `prospeccion` | Manual | Usuario |
| `oportunidad_activa` | **Derivado** | Sistema |
| `cliente` | **Derivado** | Sistema |

Los estados derivados tienen **siempre** prioridad sobre los manuales mientras exista la condición que los origina. Un usuario no puede establecer `no_interesado` a una empresa que tiene una oportunidad activa — el sistema lo sobreescribirá.

Los estados manuales solo son editables desde la UI cuando el `estado_cartera` actual es manual. Si es derivado, el campo aparece como read-only.

### 3.2 La función `actualizarEstadoCartera`

Esta es la **única** función que puede modificar `estado_cartera`. No existe ningún otro endpoint ni método que lo haga directamente.

```
actualizarEstadoCartera(id_empresa):

  1. Calcular nuevo estado derivado:
     nuevo_derivado = null

     ¿Tiene la empresa alguna oportunidad con estado = 'facturado'?
       → nuevo_derivado = 'cliente'

     Si no, ¿tiene alguna oportunidad con estado IN ('evaluacion_calidda', 'documentos_legales')?
       → nuevo_derivado = 'oportunidad_activa'

  2. Leer estado actual:
     actual = SELECT estado_cartera FROM empresas WHERE id = id_empresa

  3. Guarda de entrada (no escribe si no hay cambio real):
     Si nuevo_derivado == actual → RETURN (sin write, sin log)
     Si nuevo_derivado IS NULL AND actual IN ('no_contactado','no_aplica','no_interesado','prospeccion')
       → RETURN (respeta el estado manual, no lo sobreescribe)

  4. Actualizar:
     UPDATE empresas
     SET estado_cartera = nuevo_derivado, updated_at = NOW(), updated_by = id_sistema
     WHERE id = id_empresa
```

### 3.3 Cuándo se llama

`actualizarEstadoCartera` se llama **dentro de la misma transacción** que el evento que lo puede afectar:

- Al **crear** una oportunidad → potencialmente pasa a `oportunidad_activa`
- Al **cambiar el estado** de una oportunidad → potencialmente pasa a `cliente` o vuelve a `oportunidad_activa`
- Al **eliminar** una oportunidad (`DELETE /oportunidades/:id`, exclusivo `admin`) → recalcular
- Al **retroceder** el estado de una oportunidad → recalcular

La función siempre recalcula mirando **el conjunto completo de oportunidades** de la empresa. Nunca asume el nuevo estado desde la transición individual.

### 3.4 Empresa creada desde una oportunidad

Cuando una empresa se crea directamente desde el formulario de creación de oportunidad, nace con `estado_cartera = 'no_contactado'` pero `actualizarEstadoCartera` se llama inmediatamente después como parte de la misma transacción, elevándola a `oportunidad_activa`.

---

## 4. Pipeline de oportunidades

### 4.1 Etapas

El pipeline tiene cuatro estados posibles para una oportunidad:

```
evaluacion_calidda → documentos_legales → facturado
                  ↘                    ↘
                   cerrado              cerrado
```

- `facturado` es el **único cierre positivo**. Indica que Calidda desembolsó y la operación se concretó. Cuando una empresa tiene al menos una oportunidad en `facturado`, su `estado_cartera` pasa a `cliente`.
- `cerrado` es la **salida negativa recuperable** desde cualquier etapa. Calidda rechazó, el cliente se echó atrás, la operación no prosperó. Una oportunidad cerrada puede retroceder a un estado activo.
- No existe un estado `perdido`. No existen estados permanentes irrecuperables.

### 4.2 Creación de una oportunidad

Al crear una oportunidad, el backend:

1. Verifica que la empresa exista.
2. Copia `empresas.id_vendedor` al campo `oportunidades.id_vendedor` (snapshot).
3. Asigna `id_financiadora` a la financiadora con `es_default = true` si no se especifica otra.
4. Crea el primer ítem (`oportunidad_items`) con `id_modelo`, `cantidad` y `descuento` del body; `precio_venta` se inicializa con `modelos.precio_base`. `monto_total` de la oportunidad se deriva como la suma de los ítems. Ver sección 7.
5. Establece `estado = 'evaluacion_calidda'`.
6. Inserta el primer registro en `oportunidad_estados_log` con `estado_anterior = NULL` y `estado_nuevo = 'evaluacion_calidda'`.
7. Llama a `actualizarEstadoCartera(id_empresa)`.

Todo en una sola transacción.

### 4.3 Cambio de estado

El estado de una oportunidad puede cambiarse manualmente por el vendedor o como consecuencia de confirmar un evento. En ambos casos el backend:

1. Valida que el nuevo estado sea diferente al actual.
2. Si `nuevo_estado = 'cerrado'`, valida que `motivo_cierre` no sea nulo ni vacío. Si lo es → `400 Bad Request`.
3. Actualiza `oportunidades.estado`.
4. Inserta en `oportunidad_estados_log`: `estado_anterior`, `estado_nuevo`, `changed_at = NOW()`, `changed_by`.
5. Llama a `actualizarEstadoCartera(id_empresa)`.

Todo en una sola transacción.

### 4.4 Motivo de cierre

`motivo_cierre` es obligatorio únicamente cuando `estado = 'cerrado'`. El constraint existe a nivel de base de datos (`CHECK`) y debe validarse también en el backend antes del insert/update. Para cualquier otro estado, `motivo_cierre` debe ser `NULL`.

### 4.5 Fee de garantía (confirmación_fee)

El pago del fee de garantía (US$1,000) es el último paso del cliente antes de que Quantum envíe la solicitud de evaluación a Calidda. Se modela como un **evento** del catálogo con nombre `"Fee depositado"`, asociado a la etapa `evaluacion_calidda`, `dispara_cambio_estado = false`, `es_recomendado = true`.

No bloquea el avance de estado. Es responsabilidad del vendedor registrarlo correctamente.

---

## 5. Eventos y cambios de estado

### 5.1 Tipos de evento

- **Del catálogo**: `id_catalogo_evento NOT NULL`, `es_personalizado = false`. Heredan el comportamiento (`dispara_cambio_estado`, `estado_destino`) del catálogo.
- **Personalizados**: `id_catalogo_evento NULL`, `es_personalizado = true`, `nombre_personalizado NOT NULL`. Siempre tienen `dispara_cambio_estado = false`. Los eventos personalizados no pueden disparar cambios de estado.

### 5.2 Catálogo de eventos estándar (seed)

| Nombre | Etapa asociada | Dispara cambio | Estado destino | Recomendado |
|---|---|---|---|---|
| Fee depositado | `evaluacion_calidda` | No | — | Sí |
| Aprobación Calidda | `evaluacion_calidda` | Sí | `documentos_legales` | Sí |
| Rechazo Calidda | `evaluacion_calidda` | Sí | `cerrado` | Sí |
| Desembolso Calidda | `documentos_legales` | Sí | `facturado` | Sí |
| Contrato tripartito firmado | `documentos_legales` | No | — | Sí |
| Propuesta aceptada | `evaluacion_calidda` | No | — | No |
| Documentación cliente recibida | `evaluacion_calidda` | No | — | No |

### 5.3 Flujo al marcar un evento como ocurrido

El backend **no cambia automáticamente** el estado de la oportunidad. El flujo es:

```
1. Vendedor marca evento como 'ocurrido'

2. Backend:
   - UPDATE eventos SET estado = 'ocurrido', fecha_ocurrencia = NOW(), registrado_por = id_usuario
   - Verifica si dispara_cambio_estado = true

3. Si dispara_cambio_estado = true:
   - El endpoint devuelve en la respuesta:
     { ..., sugerencia: { dispara: true, estado_destino: "documentos_legales" } }
   - NO se modifica oportunidades.estado en este paso

4. El frontend muestra el prompt no invasivo:
   "¿Deseas mover la oportunidad a [Documentos Legales]?"

5a. Vendedor confirma:
    - Segunda llamada al endpoint de cambio de estado
    - El backend ejecuta el cambio (sección 4.3)

5b. Vendedor descarta:
    - El evento queda como 'ocurrido'
    - La oportunidad mantiene su estado actual
    - No se escribe nada más
```

### 5.4 Eventos recomendados al avanzar de etapa

Cuando el vendedor cambia el estado de una oportunidad manualmente (sin pasar por un evento), el backend verifica si existen eventos del catálogo con `es_recomendado = true` y `etapa_asociada = etapa_actual` que no hayan sido registrados como `ocurrido` para esa oportunidad.

Si los hay, la respuesta incluye:
```json
{ "advertencias": ["Fee depositado no fue registrado"] }
```

El frontend muestra esta advertencia de forma no invasiva. No bloquea el cambio de estado.

### 5.5 Etapa asociada de un evento

`etapa_asociada` en el catálogo es orientativa, no restrictiva. Un vendedor puede registrar un evento de cualquier etapa en cualquier momento. La UI filtra los eventos del catálogo por `etapa_asociada = etapa_actual` para mostrar los más relevantes primero, pero no oculta los demás.

---

## 6. Pronta facturación

### 6.1 Definición

Una oportunidad califica para pronta facturación si han transcurrido **30 días calendario o menos** desde que entró por primera vez al estado `documentos_legales`.

### 6.2 Cálculo

```sql
SELECT MIN(changed_at) AS entrada_doc_legales
FROM oportunidad_estados_log
WHERE id_oportunidad = :id
  AND estado_nuevo = 'documentos_legales';
```

Si `(NOW() - entrada_doc_legales) <= 30 días` → aplica pronta facturación.

El campo `pronta_facturacion` **no se expone en ninguna pantalla del MVP**. Se almacena únicamente en base de datos para cálculo de comisiones futuro. No hay columna en la tabla de oportunidades para este valor — se deriva siempre de `oportunidad_estados_log` cuando se necesite.

### 6.3 Consideración de diseño

El contador parte desde la primera vez que la oportunidad llega a `documentos_legales`, no desde la creación de la oportunidad. Si una oportunidad retrocede de `documentos_legales` a `evaluacion_calidda` y vuelve, el contador se recalcula desde la primera entrada, no desde la segunda.

---

## 7. Monto total y precio unitario

> **V42 (multi-modelo):** `precio_unitario`, `cantidad` y `dcto` viven únicamente en `oportunidad_items`, uno por modelo vendido dentro de la oportunidad (una oportunidad puede tener varios ítems, uno por modelo). `oportunidades` ya no tiene columnas planas para estos campos — `reportes` e `inicio` leen `oportunidad_items` directamente (V46). La fórmula y la regla de "no pisar un precio editado a mano" no cambian, solo el sujeto: donde antes decía "la oportunidad", ahora es "el ítem". `oportunidades.monto_total` ya no existe como columna — se deriva siempre como la **suma** de `monto_item` de todos los ítems.

### 7.1 Precio de venta del ítem

`precio_venta` en `oportunidad_items` es editable. Al crear el ítem, se inicializa automáticamente con `modelos.precio_base` del modelo seleccionado.

```
oportunidad_items.precio_venta = modelos.precio_base  (al crear el ítem)
```

El vendedor puede modificarlo en casos excepcionales (raros). Cuando se modifica `id_modelo` de un ítem, el backend no sobreescribe automáticamente un `precio_venta` que ya fue editado por el usuario — se requiere confirmación explícita (§12.2).

### 7.2 Monto total

`monto_item` de cada ítem es **calculado y de solo lectura**. Ningún endpoint acepta `monto_item` (ni `monto_total`) como campo de entrada. Si viene en el body, se ignora.

```
monto_item = cantidad × precio_venta × (1 − descuento / 100)      (por ítem)
monto_total = Σ monto_item de todos los ítems de la oportunidad    (por oportunidad)
```

Se recalcula y persiste en el backend cada vez que cambia `cantidad`, `precio_venta` o `descuento` de cualquier ítem de la oportunidad. La UI lo muestra como campo no editable.

Si el `descuento` de un ítem es `NULL`, se trata como `0`. Si su `cantidad` o `precio_venta` es `NULL`, `monto_item` de ese ítem es `NULL` y aporta `0` a la suma — un ítem incompleto no anula el `monto_total` de los demás ítems que sí están completos. `monto_total` solo es `NULL` si ningún ítem de la oportunidad tiene datos completos (o si, en un estado transitorio, no queda ninguno).

---

## 8. Vendedor y asignación

### 8.1 Asignación de empresa

Cada empresa tiene un único `id_vendedor`. Un cliente nuevo trabajado por un vendedor queda asignado a él. Todas las oportunidades posteriores de esa empresa se crean con ese vendedor como snapshot.

### 8.2 Reasignación de empresa (robo de cliente)

La reasignación de `empresas.id_vendedor` es una decisión de Aldo (JdV). Solo los roles `admin`, `gerente` y `jdv` pueden modificar `empresas.id_vendedor`.

Al reasignar una empresa, las oportunidades **ya cerradas** (`facturado` o `cerrado`) conservan su `id_vendedor` original — el snapshot no cambia nunca. Todas las oportunidades **activas** (`evaluacion_calidda`, `documentos_legales`) de esa empresa cambian automáticamente al nuevo vendedor, en la misma transacción que la reasignación. No existe un traspaso manual selectivo por oportunidad individual: el único punto de entrada para cambiar el vendedor de una oportunidad activa es reasignar la empresa.

### 8.3 Cascada automática a oportunidades activas

Cuando se reasigna `empresas.id_vendedor`:

- El backend actualiza `oportunidades.id_vendedor` de todas las oportunidades activas de esa empresa cuyo vendedor difiera del nuevo, en la misma transacción (implementado vía evento de aplicación síncrono — ver `VendedorEmpresaReasignadoEvent`).
- El historial completo (log de estados, eventos, tareas) permanece en la misma oportunidad; no se duplica nada.
- El vendedor anterior deja de ver esas oportunidades en su pipeline (el pipeline filtra por `id_vendedor = usuario_actual`).
- El nuevo vendedor las hereda con todo el historial, y recibe una notificación `oportunidad_traspasada` por cada una.
- **Consecuencia aceptada**: si una oportunidad se factura después de la cascada, la comisión corresponde al vendedor vigente en ese momento, no al original. El módulo de comisiones (post-MVP) deberá tener esto en cuenta.

### 8.4 Snapshot de id_vendedor al crear oportunidad

```
oportunidades.id_vendedor = empresas.id_vendedor  (al momento de crear la oportunidad)
```

Este valor se resincroniza automáticamente ante cualquier reasignación posterior de la empresa, mientras la oportunidad esté activa (sección 8.3). Una vez que la oportunidad cierra (`facturado` o `cerrado`), el valor queda congelado para siempre.

---

## 9. Financiadoras

### 9.1 Financiadora default

Solo puede existir una financiadora con `es_default = true`. Esto está garantizado por un unique index parcial en la base de datos.

Al crear una oportunidad sin especificar `id_financiadora`, el backend asigna automáticamente la financiadora con `es_default = true`.

### 9.2 Calidda — valores fijos

```
nombre:           "Calidda – Fraccionamiento GNV"
monto_por_unidad: 45,000.00 USD
plazo_meses:      48
tea:              0.0000 (0%)
cuota_por_unidad: 937.50 USD
es_default:       true
```

Estos valores son parte del seed inicial de la base de datos. No son modificables desde la UI del MVP.

### 9.3 Financiadoras con términos negociables

Otras financiadoras pueden tener `monto_por_unidad`, `plazo_meses`, `tea` y `cuota_por_unidad` como `NULL`. En esos casos los términos específicos de la operación se registrarán en `oportunidad_financiamiento` (tabla del módulo financiero, fuera del alcance del MVP).

### 9.4 Términos en la vista de oportunidad

Los términos de la financiadora (monto, plazo, TEA, cuota) se obtienen siempre vía JOIN con la tabla `financiadoras`. No se copian a la tabla `oportunidades`. No existe ningún campo de términos financieros en `oportunidades`.

---

## 10. Prospección

### 10.1 Definición

La prospección no es una etapa de `oportunidades`. Es el proceso previo a la creación de una oportunidad. Una empresa en prospección es aquella con `estado_cartera = 'prospeccion'` y sin oportunidades activas.

### 10.2 Tareas de prospección

Las tareas de prospección son registros en la tabla `tareas` con `id_oportunidad = NULL`. Se vinculan directamente a la empresa mediante `id_empresa`.

Al crear una tarea, si `id_oportunidad` es `NULL`, es una tarea de prospección. El backend valida que la empresa exista y que no tenga oportunidades activas — si las tiene, la tarea debe vincularse a una oportunidad, no a la empresa directamente.

### 10.3 Hitos de prospección

Los hitos de prospección son eventos del catálogo marcados con `es_hito_prospeccion = true` (campo a agregar al catálogo). Los tres hitos estándar son:

| Hito | Nombre en catálogo |
|---|---|
| 1 | Reporte Tributario recibido |
| 2 | Sentinel positivo |
| 3 | Reunión inicial realizada |

El avance de prospección de una empresa se calcula contando los eventos con `es_hito_prospeccion = true` registrados como `ocurrido` para esa empresa (sin `id_oportunidad`).

### 10.4 Conversión a oportunidad

Al convertir una empresa prospectada en oportunidad:

1. Se crea la oportunidad (sección 4.2).
2. Las tareas de prospección existentes (`id_oportunidad = NULL`) **no se migran** a la oportunidad — quedan en el historial de la empresa.
3. Las nuevas tareas que se creen quedan vinculadas a la oportunidad.
4. `actualizarEstadoCartera` eleva la empresa a `oportunidad_activa`.

### 10.5 Prospección sin pasos obligatorios

Los hitos de prospección no son obligatorios para crear una oportunidad. Si el vendedor ya trabajó al cliente antes de registrarlo, puede crear la oportunidad directamente sin haber registrado ningún hito. El sistema no bloquea esto.

---

## 11. Contactos y empresas

### 11.1 Contactos multi-empresa

Un contacto puede estar vinculado a más de una empresa. La relación vive en `empresa_contactos`. El `cargo` y `toma_decision` son atributos de la relación, no del contacto — pueden variar por empresa.

Al agregar un contacto a una empresa, el frontend debe ofrecer búsqueda de contactos existentes antes de crear uno nuevo. El backend no impide la creación de contactos duplicados, pero sí provee un endpoint de búsqueda por nombre/teléfono — con una excepción: para los roles de apoyo (`analista`/`otro`) en `contexto=vincular`, la búsqueda es solo por nombre, nunca por teléfono (`contrato_api.md` §9). Ocultar el teléfono en la respuesta no bastaría para proteger el dato: un `LIKE` sobre el número seguiría funcionando como oráculo.

### 11.2 Eliminación de contactos

`ON DELETE RESTRICT` en `empresa_contactos`. No se puede eliminar un contacto que está vinculado a alguna empresa. Se desvincula primero de todas las empresas y luego se elimina.

`DELETE /empresas/:id` (exclusivo `admin`) elimina la empresa en cascada: se eliminan sus oportunidades, las tareas y eventos de esas oportunidades, el log de estados, y las tareas/eventos propios de la empresa. Los contactos vinculados nunca se eliminan — solo se borra la fila de `empresa_contactos` (`ON DELETE CASCADE` desde V29). Sin restricción por estado de las oportunidades (incluye `facturado`).

### 11.3 Contactos en oportunidades

La tabla `oportunidad_contactos` registra qué contactos están involucrados en una oportunidad específica con su rol (`"Contacto Principal"`, `"Aprobador"`, etc.). Son independientes de los contactos generales de la empresa.

---

## 12. Modelos de bus

> **V42 (multi-modelo):** `id_modelo` vive únicamente en `oportunidad_items`. Una oportunidad tiene uno o más ítems, cada uno con su propio `id_modelo`; §12.1 y §12.2 se aplican por ítem. `oportunidades` ya no tiene columna plana `id_modelo` (V46) — `reportes` e `inicio` leen `oportunidad_items` directamente.

### 12.1 id_modelo en el ítem

`id_modelo` en `oportunidad_items` es obligatorio. No se puede guardar un ítem sin modelo seleccionado, y una oportunidad no puede quedarse sin ítems: eliminar el último devuelve `409 ULTIMO_ITEM_NO_ELIMINABLE` (`contrato_api.md §10`), así que siempre tiene al menos uno.

### 12.2 Cambio de modelo

Si se cambia `id_modelo` en un ítem existente:
1. El backend actualiza `precio_venta` del ítem con el nuevo `modelos.precio_base` **solo si** el `precio_venta` actual es igual al `precio_base` del modelo anterior (es decir, no fue editado manualmente).
2. Si fue editado manualmente, el backend devuelve una advertencia y no sobreescribe el precio.
3. Recalcula `monto_item` del ítem y, en cascada, `monto_total` de la oportunidad.

### 12.3 Contrato tripartito

Hay un único contrato tripartito por operación (Calidda + Quantum + cliente). Se referencia desde `oportunidades.ficha_venta` (URL). Adicionalmente, cada bus entregado tiene su contrato individual entre Quantum y el cliente, referenciado en `buses_entregados.url_contrato`.

---

## 13. Retroceso de estado

### 13.1 Definición

Un retroceso ocurre cuando el nuevo estado de una oportunidad es anterior al actual en la secuencia del pipeline, o cuando se pasa de `facturado` a cualquier estado activo, o de `cerrado` a cualquier estado activo.

### 13.2 Comportamiento del backend

El retroceso no está bloqueado a nivel de base de datos ni de backend. Cualquier cambio de estado válido es permitido. Sin embargo, el backend devuelve en la respuesta un flag `es_retroceso: true` cuando detecta que el nuevo estado es un retroceso.

El frontend, al recibir `es_retroceso: true`, debe mostrar un aviso crítico antes de confirmar, con el texto: `"Estás retrocediendo esta oportunidad de [estado_anterior] a [estado_nuevo]. ¿Confirmas?"`. El cambio no se aplica hasta la confirmación.

### 13.3 Impacto en estado de cartera

Cuando una oportunidad retrocede desde `facturado` hacia un estado activo, `actualizarEstadoCartera` se ejecuta. La función recalcula mirando **todas** las oportunidades de la empresa — si hay otra oportunidad en `facturado`, la empresa se mantiene como `cliente`. Si no hay ninguna, baja a `oportunidad_activa`.

### 13.4 Retroceso desde cerrado

Una oportunidad en `cerrado` puede retroceder a cualquier etapa activa. Esto es intencional — no existen estados irrecuperables. Al retroceder, `motivo_cierre` se pone en `NULL` automáticamente.

---

## Apéndice — Comportamientos no implementados en MVP

Los siguientes comportamientos están definidos en el schema o en conversaciones de diseño pero **no se implementan en el MVP**:

- **Módulo financiero**: cuota de Quantum, TEA pactada, Balloon, cálculo de cuotas del cliente. La tabla `oportunidad_financiamiento` existe en el schema pero no tiene endpoints ni UI.
- **Buses entregados**: la tabla `buses_entregados` existe pero no tiene endpoints de escritura. Los registros se crearán manualmente en una fase posterior.
- **Panel de administración de permisos**: el campo `rol` en `empleados` existe y Spring Security lo usa para autorización, pero no hay UI para gestionarlo en el MVP.
- **Pronta facturación en UI**: el cálculo existe en el backend y el campo es derivado, pero no se muestra en ninguna pantalla del MVP.
- **Roles personalizados del analista financiero**: en el MVP el analista tiene el mismo nivel de visibilidad que un vendedor. La extensión de permisos se hace en una fase posterior vía panel de administración.
- **Import masivo de cartera desde Excel**: se implementa en una fase posterior. El MVP admite creación individual de empresas y contactos.
