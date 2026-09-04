# Plan A — Fundación de `oportunidad_items` (expand)

> **Destinatario: agentes ejecutores, no humanos.** Cada tarea es autocontenida.
> Ejecutar en orden estricto: cada una asume que la anterior está cerrada y verde.
>
> **Regla para el ejecutor:** si algo de tu tarea es ambiguo, contradice a otra
> tarea, o el repo no coincide con lo que la tarea describe — **detente y
> consulta al arquitecto**. No infieras, no inventes, no "arregles" de paso nada
> que la tarea no te pida. Esto es producción con usuarios reales.

---

## Fase de investigación (leer antes de la Task 1)

### Qué hace y qué NO hace este plan

Este es el **expand** de una estrategia expand → migrate → contract
(`plan-03-mapa-oportunidad-items.md` §4). Crea la tabla, la puebla y añade la
capa de acceso a datos, **sin que nada la lea todavía**.

**Al terminar este plan la aplicación se comporta exactamente igual que hoy.**
Las columnas viejas de `oportunidades` siguen siendo la fuente de verdad. Si el
trabajo se detiene aquí, no queda nada roto.

**Lo que este plan NO hace** (es el Plan B, fuera de alcance):
cambiar `OportunidadServiceImpl` · derivar `monto_total` · tocar los DTOs de
oportunidad · tocar `reportes`, `inicio` o `solicitudes` · eliminar ninguna
columna · cambiar el contrato de API · tocar el nombre de las carpetas de Drive.

### Documentos que gobiernan este plan

| Documento | Qué manda sobre este cambio |
|---|---|
| `docs/planes/plan-03-mapa-oportunidad-items.md` | Hallazgos I1–I9 y decisiones D6–D9. **Léelo entero antes de la Task 1** |
| `docs/reglas_simulaciones.md` §1.1, §1.2, §6.1 | Nombra las columnas del ítem: `precio_venta`, `descuento`, `cuota_financiadora` (default 937.50). **Documento cerrado: esos nombres mandan**, aunque difieran de `precio_unitario`/`dcto` de V10 |
| `src/main/resources/db/migration/V10__create_oportunidades.sql` | Forma y tipos actuales de los campos que se replican |
| `src/main/resources/db/migration/V36__checks_numericos_oportunidades.sql` | Los CHECK a replicar, y **por qué `cantidad`/`precio` son nullable a propósito** |
| `docs/reglas_negocio.md` §7 | `monto_total` calculado; `dcto` null = 0 |
| `CLAUDE.md` | Convenciones; reglas 2, 8, 9, 10, 11 aplican |

### Reglas de `CLAUDE.md` que tocan este cambio

| Regla | Cómo aplica aquí |
|---|---|
| **1. TDD siempre** | Tests antes del código en O3 |
| **2. `monto_total` se calcula, nunca se acepta** | Este plan **no** toca `monto_total`. El ítem tampoco lo persiste: se derivará en el Plan B |
| **8. Inyección por constructor** | `private val` donde haya servicios |
| **9. JPA `LAZY`; nunca exponer entidades** | La relación ítem→oportunidad se modela **sin** `@ManyToOne` navegable: solo `id_oportunidad` como columna. Ver D10 abajo |
| **10. `@Transactional`** | No hay Service en este plan; no aplica todavía |
| **11. Queries parametrizadas** | Sin SQL por concatenación |

### D10 · La entidad del ítem no navega a `Oportunidad`

`OportunidadItem` guarda `idOportunidad: Long` como columna simple, **no** un
`@ManyToOne var oportunidad: Oportunidad`. Motivo: es el patrón que ya usa todo
el repo (`Oportunidad.idEmpresa`, `Oportunidad.idVendedor`, `MetaVenta.idEmpleado`
son `Long`, no relaciones), y evita de raíz el riesgo de lazy-loading fuera de
transacción y de exponer una entidad por serialización accidental (regla 9).

### D11 · Nulabilidad: espejo exacto del comportamiento actual

`cantidad` y `precio_venta` son **nullable**, igual que `cantidad` y
`precio_unitario` en V10. No es un descuido: V36 lo documenta explícitamente
—*"una oportunidad recién creada puede no tener todavía cantidad ni precio"*— y
`POST /oportunidades` acepta hoy `cantidad = null`.

Cambiar esa nulabilidad sería un cambio de comportamiento, y este plan
no cambia comportamiento. Si el Plan B decide endurecerlo, será una decisión
suya, tomada con el dominio ya migrado.

### Alcance — lista cerrada de archivos

```
src/main/resources/db/migration/V42__create_oportunidad_items.sql   (nuevo)
src/main/kotlin/pe/quantum/crm/domain/oportunidades/OportunidadItem.kt            (nuevo)
src/main/kotlin/pe/quantum/crm/domain/oportunidades/OportunidadItemRepository.kt  (nuevo)
src/main/kotlin/pe/quantum/crm/domain/oportunidades/dto/OportunidadItemDtos.kt    (nuevo)
src/test/kotlin/pe/quantum/crm/domain/oportunidades/OportunidadItem*Test.kt       (nuevos)
src/test/kotlin/pe/quantum/crm/support/SinBaseDeDatosMocks.kt        (modificar: +1 mock)
```

Cualquier otro archivo: **detente y consulta**.

> **Aviso de infraestructura, aprendido a golpes en el Plan 2.** Cada repositorio
> JPA nuevo debe registrarse como mock en `SinBaseDeDatosMocks.kt`, o el
> `ApplicationContext` deja de levantar y **cientos** de tests fallan a la vez.
> Un test acotado en verde no prueba nada: hay que correr `./gradlew test` completo.

---

## Tabla de tareas

| ID | Tarea | Modelo | Esfuerzo |
|---|---|---|---|
| O1 | Migración V42: crear tabla + backfill | Opus 5 | High |
| O2 | Entidad `OportunidadItem` + repositorio + mock | Sonnet 5 | Medium |
| O3 | DTOs del ítem + tests | Sonnet 5 | Medium |
| O4 | Verificación de build completa | Sonnet 5 | Low |
| O5 | Auditoría del diff contra los documentos citados | Opus 5 | High |
| O6 | Renumerar la migración de simulaciones (V40 → V43) | Opus 5 | Medium |
| O7 | Despliegue a producción de V42 y V43 | Opus 5 | Extra High |

---

## O1 · Migración V42: crear tabla + backfill

**Modelo:** Opus 5 · **Esfuerzo:** High

**Archivo único a crear:** `src/main/resources/db/migration/V42__create_oportunidad_items.sql`

> **No apliques nada a producción en esta tarea.** Solo se escribe el archivo.
> El despliegue es O7, y requiere confirmación del dueño del producto.

### DDL

Encabeza el archivo con un comentario en el estilo de V32/V36 (mira los dos):
qué crea, por qué, y la referencia a `plan-03-mapa-oportunidad-items.md` D6.

```sql
CREATE TABLE oportunidad_items (
    id                  BIGSERIAL       PRIMARY KEY,
    id_oportunidad      BIGINT          NOT NULL REFERENCES oportunidades(id) ON DELETE CASCADE,
    id_modelo           BIGINT          NOT NULL REFERENCES modelos(id),
    cantidad            INT,
    precio_venta        NUMERIC(12,2),
    descuento           NUMERIC(5,2),
    cuota_financiadora  NUMERIC(12,2)   NOT NULL DEFAULT 937.50,
    created_at          TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by          BIGINT          NOT NULL REFERENCES empleados(id),
    updated_at          TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by          BIGINT          NOT NULL REFERENCES empleados(id),

    CONSTRAINT chk_oportunidad_item_cantidad_positiva      CHECK (cantidad > 0),
    CONSTRAINT chk_oportunidad_item_precio_no_negativo     CHECK (precio_venta >= 0),
    CONSTRAINT chk_oportunidad_item_descuento_rango        CHECK (descuento >= 0 AND descuento <= 100),
    CONSTRAINT chk_oportunidad_item_cuota_finc_no_negativa CHECK (cuota_financiadora >= 0)
);

CREATE INDEX idx_oportunidad_items_oportunidad ON oportunidad_items(id_oportunidad);
CREATE INDEX idx_oportunidad_items_modelo      ON oportunidad_items(id_modelo);

ALTER TABLE oportunidad_items ENABLE ROW LEVEL SECURITY;
```

**Puntos que el comentario del archivo debe dejar escritos** (con tus palabras):

- **`cantidad` y `precio_venta` son nullable a propósito**, espejo de V10/V36: una
  oportunidad recién creada puede no tener todavía cantidad ni precio. Igual que
  en V36, un CHECK que evalúa a `UNKNOWN` no se viola, así que
  `CHECK (cantidad > 0)` deja pasar el `NULL` — **no** escribas `OR ... IS NULL`.
- **Los nombres son `precio_venta` y `descuento`**, no `precio_unitario`/`dcto`,
  porque `docs/reglas_simulaciones.md` (documento cerrado) ya los nombró así y
  `V40__create_simulaciones.sql` depende de esa nomenclatura.
- **`cuota_financiadora` default 937.50**: lo que el cliente paga a terceros
  (Calidda, cajas) por su inicial, editable por el vendedor
  (`reglas_simulaciones.md` §1.2). No participa del `monto_total`.
- **`ON DELETE CASCADE`** hacia `oportunidades`: los ítems no sobreviven a su
  oportunidad. Mismo criterio que `oportunidad_contactos` (V12).
- RLS habilitado sin políticas, igual que el resto del esquema.

### Backfill

Un ítem por cada oportunidad existente, copiando sus valores actuales:

```sql
INSERT INTO oportunidad_items
    (id_oportunidad, id_modelo, cantidad, precio_venta, descuento,
     created_at, created_by, updated_at, updated_by)
SELECT id, id_modelo, cantidad, precio_unitario, dcto,
       created_at, created_by, updated_at, updated_by
FROM oportunidades;
```

`cuota_financiadora` se queda con su default (937.50), que es exactamente lo que
`reglas_simulaciones.md` §1.2 define como valor por defecto.

**Contexto verificado por el arquitecto** (hallazgos I1–I3 del mapa): hay **5**
oportunidades en producción, todas con `id_modelo`, `cantidad`, `precio_unitario`
y `monto_total` poblados y **coherentes** —`monto_total` coincide exactamente con
el recalculado en los 5 casos—, ninguna facturada. El backfill es determinista.

### Verificación dentro de la propia migración

Cierra el archivo con un bloque que aborte la migración si el backfill no cuadra.
No es paranoia: una migración que deja datos a medias en producción es peor que
una que falla.

```sql
DO $$
DECLARE
    faltantes INT;
BEGIN
    SELECT count(*) INTO faltantes
    FROM oportunidades o
    WHERE NOT EXISTS (SELECT 1 FROM oportunidad_items i WHERE i.id_oportunidad = o.id);

    IF faltantes > 0 THEN
        RAISE EXCEPTION 'Backfill incompleto: % oportunidades sin item', faltantes;
    END IF;
END $$;
```

**Restricciones**
- No toques ninguna otra migración, ni `docs/migrations/V40__create_simulaciones.sql`.
- **No** elimines ni modifiques ninguna columna de `oportunidades`. Eso es el Plan C.
- **No apliques nada a producción.** Ni MCP de Supabase, ni `bootRun`.

**Criterio de aceptación:** el archivo existe y el SQL es sintácticamente
coherente con el estilo del repo. Reporta el contenido completo del archivo.

---

## O2 · Entidad `OportunidadItem` + repositorio

**Modelo:** Sonnet 5 · **Esfuerzo:** Medium

Abre primero `src/main/kotlin/pe/quantum/crm/domain/oportunidades/Oportunidad.kt`
y `.../metasventa/MetaVenta.kt`: son la referencia de estilo exacta.

### `OportunidadItem.kt` (en `domain/oportunidades/`)

Entidad JPA sobre `oportunidad_items`. Campos, con sus tipos:

| Campo Kotlin | Columna | Tipo | Notas |
|---|---|---|---|
| `id` | `id` | `Long?` | `@Id`, `@GeneratedValue(IDENTITY)` |
| `idOportunidad` | `id_oportunidad` | `Long` | **columna simple, NO `@ManyToOne`** (decisión D10) |
| `idModelo` | `id_modelo` | `Long` | `var` |
| `cantidad` | `cantidad` | `Int?` | nullable (D11) |
| `precioVenta` | `precio_venta` | `BigDecimal?` | nullable (D11) |
| `descuento` | `descuento` | `BigDecimal?` | nullable |
| `cuotaFinanciadora` | `cuota_financiadora` | `BigDecimal` | NOT NULL, default `BigDecimal("937.50")` |
| `createdAt` / `createdBy` | | `LocalDateTime` / `Long` | `val` |
| `updatedAt` / `updatedBy` | | `LocalDateTime` / `Long` | `var` |

**Sin lógica de negocio en la entidad.** Nada de calcular montos: eso es del Plan B.

### `OportunidadItemRepository.kt`

```kotlin
interface OportunidadItemRepository : JpaRepository<OportunidadItem, Long> {
    fun findByIdOportunidadOrderByIdAsc(idOportunidad: Long): List<OportunidadItem>

    fun findByIdOportunidadInOrderByIdAsc(idsOportunidad: Collection<Long>): List<OportunidadItem>
}
```

El segundo método existe para que el Plan B pueda resolver el listado **sin N+1**
(cargar los ítems de todas las oportunidades de la página en una sola query).
El orden por `id` da una secuencia estable y reproducible.

### `SinBaseDeDatosMocks.kt` — obligatorio

Añade el import y el bean, siguiendo el patrón exacto de los que ya están:

```kotlin
@Bean
fun oportunidadItemRepository(): OportunidadItemRepository = mockk(relaxed = true)
```

**Sin esto el `ApplicationContext` no levanta y cientos de tests fallan.**

**Restricciones**
- Sin Service, sin Controller: no están en el alcance de este plan.
- No toques `Oportunidad.kt` ni `OportunidadRepository.kt`.

**Criterio de aceptación:** `./gradlew compileKotlin ktlintCheck detekt --console=plain -q`
pasa **y** `./gradlew test --console=plain -q` (la suite completa) sigue en verde.
Reporta ambas salidas. La segunda no es opcional: es la que detecta el fallo de
contexto que el mock previene.

---

## O3 · DTOs del ítem + tests

**Modelo:** Sonnet 5 · **Esfuerzo:** Medium

**TDD: escribe primero los tests, luego los DTOs** (`CLAUDE.md` regla 1).

**Archivo:** `src/main/kotlin/pe/quantum/crm/domain/oportunidades/dto/OportunidadItemDtos.kt`

Abre `dto/OportunidadDtos.kt` primero: los montos viajan como **string** en este
contrato (`precioUnitario: String?`, `montoTotal: String?`), y el ítem debe
seguir esa misma convención. Fíjate también en el bloque de constantes de
validación y en el KDoc que explica por qué `@Digits` importa — el mismo
razonamiento aplica aquí.

Tres DTOs:

**`OportunidadItemDto`** — salida. `id`, `idModelo`, `modelo: ModeloEnOportunidadDto?`,
`cantidad: Int?`, `precioVenta: String?`, `descuento: String?`,
`cuotaFinanciadora: String`, `montoItem: String?`.

> `montoItem` es el subtotal del ítem: `cantidad × precio_venta × (1 − descuento/100)`.
> **No se persiste** — el DTO lo recibe ya calculado desde quien lo construya en
> el Plan B. En este plan el DTO solo declara el campo; **no** escribas aquí la
> lógica de cálculo.

**`CrearOportunidadItemRequest`** — entrada. `idModelo: Long` (`@Positive`),
`cantidad: Int?` (`@Positive`), `precioVenta: BigDecimal?`, `descuento: BigDecimal?`,
`cuotaFinanciadora: BigDecimal?`. Replica las anotaciones de rango y `@Digits` de
`OportunidadDtos.kt`: `descuento` en 0..100 con 3 enteros/2 decimales,
`precioVenta` con 10 enteros/2 decimales.

**`ActualizarOportunidadItemRequest`** — igual que el anterior pero con `idModelo`
también nullable (un PATCH parcial).

### Tests

`src/test/kotlin/pe/quantum/crm/domain/oportunidades/OportunidadItemDtosTest.kt`,
siguiendo el estilo de `OportunidadDtosEscalaTest.kt` (ábrelo: es la referencia
directa). Casos obligatorios:

- `descuento` = 100.5 → violación de `@DecimalMax`
- `descuento` = −1 → violación de `@DecimalMin`
- `descuento` = 2.994 → violación de `@Digits` (más de 2 decimales)
- `cantidad` = 0 y = −1 → violación de `@Positive`
- `precioVenta` con 11 dígitos enteros → violación de `@Digits`
- un request válido no produce ninguna violación

**Restricciones**
- Cero lógica en los DTOs: sin `init`, sin propiedades calculadas.
- No toques `OportunidadDtos.kt`.

**Criterio de aceptación:** `./gradlew test --tests '*OportunidadItem*' ktlintCheck detekt --console=plain -q` en verde.

---

## O4 · Verificación de build completa

**Modelo:** Sonnet 5 · **Esfuerzo:** Low

Ejecuta **en este orden** y reporta la salida de cada uno:

```bash
./gradlew ktlintCheck --console=plain -q
./gradlew detekt --console=plain -q
./gradlew test --console=plain -q
```

**Baseline:** verde al abrir este plan. Cualquier rojo lo introdujo el plan.

**Notas de infraestructura de este repo, ya conocidas:**
- **No ejecutes `koverVerify`**: arrastra `integrationTest`, bloqueado localmente
  por incompatibilidad de Testcontainers con Docker 29. No es un fallo del código.
- Si Gradle falla con `CorruptedException`, "Could not delete" o "Failed to clean
  up output files" (locks de Windows): `./gradlew --stop`, y si persiste
  `--no-daemon`. Si aun así falla, hay procesos `java.exe` colgados; repórtalo.

**Si algo falla:**
- `ktlintCheck` rojo → `./gradlew ktlintFormat` y repite la cadena entera.
- `detekt` rojo → arregla el hallazgo si es trivial. **No** edites `config/detekt/detekt.yml`.
- `test` rojo → si es `ApplicationContext` no levantando, revisa el mock de O2.
  Cualquier otro fallo: **no lo arregles**, reporta la salida completa.

---

## O5 · Auditoría del diff contra los documentos citados

**Modelo:** Opus 5 · **Esfuerzo:** High

Tarea exigida por `CLAUDE.md`, apartado *"Cómo escribir un plan de implementación
en este repo"*. **Auditoría del diff completo, no resumen del trabajo.**

Los archivos nuevos aparecen como `??` en `git status` y **no** salen en
`git diff`: hay que leerlos directamente.

Contrasta contra: `plan-03-mapa-oportunidad-items.md` (I1–I9, D6–D11) ·
`reglas_simulaciones.md` §1.1/§1.2/§6.1 · `reglas_negocio.md` §7 ·
`V10` y `V36` · `CLAUDE.md` reglas 1, 2, 8, 9, 11 · la lista cerrada de archivos
de este plan.

Busca en concreto:

1. **Contradicciones con documentación ya vigente y correcta** — el caso que
   `CLAUDE.md` manda cazar por su nombre.
2. **Fugas de alcance.** En particular: que **ninguna columna de `oportunidades`
   se haya modificado o eliminado**, y que `OportunidadServiceImpl.kt`,
   `Oportunidad.kt`, `OportunidadDtos.kt`, `reportes/`, `inicio/` y `solicitudes/`
   **no aparezcan en el diff**. Este plan no debe cambiar comportamiento.
3. **Nombres de columna**: que sean `precio_venta`/`descuento` y no
   `precio_unitario`/`dcto` (`reglas_simulaciones.md` manda).
4. **Que la FK de V40 quede satisfecha**: `oportunidad_items(id)` debe ser
   `BIGSERIAL`/`BIGINT` PK, que es lo que `V40__create_simulaciones.sql` referencia.
5. **Que la entidad no navegue a `Oportunidad`** (D10): `grep -n "ManyToOne\|OneToMany"`
   sobre `OportunidadItem.kt` debe salir vacío.
6. **Nulabilidad** (D11): `cantidad` y `precioVenta` nullable en entidad y tabla.
7. **Que el mock esté registrado** en `SinBaseDeDatosMocks.kt`.
8. **Que no se añadiera ninguna dependencia** a `build.gradle.kts`.
9. **TDD**: que los tests de O3 existan y sean anteriores al DTO (revisa mtimes).

**Entregable:** informe con hallazgos en *bloqueante / menor / ninguno*, con
archivo, línea y la regla o sección concreta. Si no encuentras nada, dilo con la
evidencia de cada verificación. **No arregles nada. No hagas commit.**

---

## O6 · Renumerar la migración de simulaciones (V40 → V43)

**Modelo:** Opus 5 · **Esfuerzo:** Medium

### El problema

`spring.flyway.out-of-order` no está configurado ⇒ **`false`**. La V41
(`tipo_cambio`) **ya está aplicada** en producción (`installed_rank` 40,
`version` 41). Por tanto **una migración con versión 40 ya no puede aplicarse
nunca**: Flyway aborta el arranque al encontrar una versión menor que la máxima
ya aplicada.

`docs/migrations/V40__create_simulaciones.sql` está en esa situación. Renumerarla
no es opcional: es la única forma de que llegue a producción. Es exactamente la
decisión **D4** de `plan-00-mapa-simulaciones.md` en acción (*"la numeración se
asigna al desplegar, no al escribir"*).

### Qué hacer

1. **Renombra** `docs/migrations/V40__create_simulaciones.sql` a
   `docs/migrations/V43__create_simulaciones.sql`. **No cambies ni una línea de
   su contenido SQL** — solo el nombre del archivo.
   > V42 queda ocupada por `oportunidad_items` (O1 de este plan), y V43 debe ser
   > posterior porque su FK depende de esa tabla.
2. **Déjala en `docs/migrations/`**, no la muevas a la carpeta de Flyway todavía:
   eso lo hace O7, junto con el despliegue.
3. **Actualiza las referencias** que quedarían obsoletas. Búscalas con
   `grep -rn "V40" docs/ Instrucciones_simulaciones.md` y actualiza al menos:
   - `docs/reglas_simulaciones.md:5` (`> Migración: V40__create_simulaciones.sql`)
   - `docs/planes/plan-00-mapa-simulaciones.md` (H3, H4, D3, §3, §5)
   - `docs/planes/plan-01-motor-calculo.md:51` y su tarea T8 punto 7
   - `Instrucciones_simulaciones.md:7`

   En cada sitio, además de cambiar el número, **añade una nota breve** de por qué
   se renumeró (V41 ya aplicada + `out-of-order=false`). Un número que cambia sin
   explicación es justo el tipo de deriva que la auditoría final debe poder seguir.

**Restricciones**
- **No modifiques el SQL de la migración de simulaciones.** Solo su nombre y las
  referencias documentales.
- No la muevas a `src/main/resources/db/migration/`.

**Criterio de aceptación:** `grep -rn "V40__create_simulaciones" . --exclude-dir=.git --exclude-dir=build`
no devuelve nada, y el archivo existe como `docs/migrations/V43__create_simulaciones.sql`
con su contenido SQL intacto (verifícalo con `git status` / comparación de tamaño).

---

## O7 · Despliegue a producción de V42 y V43

**Modelo:** Opus 5 · **Esfuerzo:** Extra High

> **Esta es la única tarea del plan que toca producción.** La base está en
> Supabase con usuarios reales. **No la ejecutes sin confirmación explícita del
> dueño del producto**, aunque O1–O6 estén todas en verde.

### Lección aprendida en el Plan 2 — léela antes de empezar

`apply_migration` del MCP de Supabase **ejecuta el DDL pero NO registra la fila
en `flyway_schema_history`**. Si se aplica una migración así y no se registra a
mano, en el siguiente arranque Flyway intenta ejecutarla otra vez, choca con los
objetos ya creados y **tumba el arranque de la aplicación**.

El checksum **no se inventa ni se calcula a mano**: se obtiene ejecutando el
propio Flyway en modo `info()` (solo lectura) contra producción. El arquitecto ya
dejó la herramienta lista y probada en
`<scratchpad>/flyway-check/Check.java`; si no está, reconstrúyela con la API
`Flyway.configure().dataSource(...).locations("filesystem:...").load().info()`.
Las credenciales salen de `.env` (`DB_URL`, `DB_USERNAME`, `DB_PASSWORD`).

> **Trampa de shell verificada:** `DB_PASSWORD` contiene un `$` literal. `source .env`
> lo expande y trunca la contraseña, dando un falso "password authentication
> failed". Extrae los valores con `grep '^DB_X=' .env | cut -d= -f2-`, sin `source`.

### Pasos, en orden estricto

1. **Releer el estado real** vía MCP (proyecto `fmkqomwyakxeblfinkuy`):
   ```sql
   SELECT installed_rank, version, description, success
   FROM flyway_schema_history ORDER BY installed_rank DESC LIMIT 5;
   ```
   Esperado al abrir esta tarea: máximo `version = 41`. **Si no es 41, detente y
   reporta**: alguien aplicó algo entremedias y la numeración de V42/V43 hay que
   recalcularla.
2. **Confirmar que las tablas no existen:**
   `SELECT to_regclass('public.oportunidad_items'), to_regclass('public.simulaciones');`
   → ambas `null`.
3. **Presentar al dueño del producto** el DDL de V42 y de V43 y **esperar su visto
   bueno explícito**, por separado para cada una.
4. **Aplicar V42** (`oportunidad_items`) con `apply_migration`, DDL idéntico al
   archivo local carácter por carácter.
5. **Verificar V42**: tabla creada, los 4 CHECK presentes, RLS habilitado, los dos
   índices, y **el backfill correcto**:
   ```sql
   SELECT (SELECT count(*) FROM oportunidades) AS oportunidades,
          (SELECT count(*) FROM oportunidad_items) AS items,
          (SELECT count(*) FROM oportunidades o
             WHERE NOT EXISTS (SELECT 1 FROM oportunidad_items i WHERE i.id_oportunidad = o.id)) AS sin_item;
   ```
   `sin_item` debe ser **0** y los dos primeros conteos deben coincidir (5 y 5).
   Comprueba además que los valores copiados casan uno a uno con los de origen.
6. **Aplicar V43** (`simulaciones`) — su FK a `oportunidad_items(id)` ya está
   satisfecha. Verifica después: `simulaciones`, `simulacion_log`, los dos enums
   (`modo_simulacion_enum`, `tipo_evento_simulacion_enum`), el trigger
   `trg_simulacion_modo_inmutable`, el índice único `uq_simulacion_principal` y RLS.
7. **Registrar ambas en `flyway_schema_history`.** Obtén el checksum real de cada
   una con la herramienta de Flyway (arriba), y **presenta los dos `INSERT` al
   dueño del producto**: esta escritura está sujeta a aprobación explícita y
   puede requerir que la ejecute él directamente en el SQL Editor de Supabase.
   `installed_rank` continúa desde el máximo actual (41 y 42, si el máximo es 40).
8. **Mover los archivos a la carpeta de Flyway.** Copia
   `docs/migrations/V43__create_simulaciones.sql` a
   `src/main/resources/db/migration/`. V42 ya vive ahí desde O1.
9. **Verificación final:** vuelve a correr Flyway `info()` y confirma que V42 y
   V43 aparecen como `SUCCESS`, no `PENDING` ni `OUT_OF_ORDER`. **Este es el paso
   que prueba que el próximo arranque de la aplicación no va a fallar.**

Si cualquier paso no sale como se describe, **detente y reporta**. No improvises
correcciones sobre la base de producción.

---

## Cierre del plan

Al terminar, **para y resume** qué se hizo.

Estado esperado al cerrar: `oportunidad_items` existe y está poblada con un ítem
por oportunidad; las simulaciones tienen su esquema desplegado; **y la aplicación
se comporta exactamente igual que antes**, porque nadie lee la tabla nueva
todavía. El Plan B (`plan-05`) se redacta a partir de aquí.
