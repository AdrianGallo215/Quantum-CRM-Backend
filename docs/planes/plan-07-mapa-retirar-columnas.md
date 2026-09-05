# Mapa — Plan C: retirar las columnas viejas de `oportunidades`

> Documento de coordinación del "contract" de la estrategia expand → migrate →
> contract (`plan-03-mapa-oportunidad-items.md` §4). Cierra lo que el Plan B
> dejó pendiente: migrar `reportes`/`inicio` a leer `oportunidad_items`
> directamente, retirar la sincronización puente de D21, y eliminar las
> columnas planas de `oportunidades`.
>
> Redactado: 2026-09-04, tras cerrar el Plan B (PR #12, CI en verde) y aplicar
> V41-V45 a producción.

---

## 1. Qué cambia de comportamiento

**Nada en el contrato de API.** Los DTOs de `reportes` (`ReporteVentasDto`,
`ReportePipelineDto`, `ReporteEquipoItemDto`, `ReporteDescuentosDto`, etc.) no
cambian de forma — es un cambio puro de **fuente de datos**: las mismas
consultas pasan de leer columnas sincronizadas de `oportunidades` a leer
`oportunidad_items` directamente. Verificado leyendo cada DTO: ningún campo se
agrega, quita ni renombra.

**Sí puede cambiar el valor numérico** que devuelven los reportes, pero solo
para datos que hoy no existen (0 oportunidades multi-ítem en producción,
verificado — ver §3). El día que una oportunidad tenga 2+ ítems, `porModelo`
en `/reportes/ventas` empezará a contarla en dos modelos en vez de uno solo
(que es justo el comportamiento correcto, no un bug — D7 de `plan-03` ya lo
decidió). Se documenta en el changelog aunque hoy no cambie ningún número real.

---

## 2. Hallazgos de la investigación de código

| # | Hallazgo | Evidencia | Consecuencia |
|---|---|---|---|
| K1 | Solo 4 métodos de `ReporteService` leen las columnas viejas: `ventas()`, `pipeline()`, `equipo()`, `descuentos()`. `velocidadEtapas()` y `prospeccion()` no las tocan | Lectura completa de `ReporteService.kt` (589 líneas) | Alcance acotado: 4 de 6 reportes |
| K2 | `InicioDao` tiene 2 de 3 métodos afectados: `resumenPipeline()` y `unidadesFacturadasPorVendedor()`. `eventosPorSeguir()` no toca las columnas | Lectura completa de `InicioDao.kt` | Alcance acotado |
| K3 | Ningún otro módulo del dominio lee las columnas viejas — verificado con `grep` sobre todo `src/main/kotlin/` excluyendo `domain/oportunidades`, `domain/reportes`, `domain/inicio` | Un único falso positivo (comentario en `MetaVenta.kt`), sin dependencia real | El Plan C no toca ningún otro módulo |
| K4 | Los DTOs de `reportes` no exponen `id_modelo`/`cantidad`/`precio_unitario`/`dcto` como campos propios de la oportunidad — los usan para **calcular** montos/unidades agregados, nunca los devuelven tal cual | Lectura completa de `dto/*.kt` de `reportes` | Confirma H1: cambio de fuente de datos, no de contrato |
| K5 | `ReporteServiceIntegrationTest.kt` (305 líneas) y `ReporteServiceSqlIntegrationTest.kt` (444 líneas), ambos `@Tag("integration")`, siembran datos con **SQL crudo directo sobre `oportunidades`** (`INSERT INTO oportunidades (..., cantidad, precio_unitario, dcto, monto_total, id_modelo, ...)`), sin pasar por `OportunidadItemService` ni crear ninguna fila en `oportunidad_items` | Lectura completa de ambos archivos | Estos fixtures dejan de alimentar las consultas reescritas si no se actualizan — **es exactamente el tipo de sorpresa que ya costó dos rondas de CI en el Plan B** (`SchemaMigrationIntegrationTest`, `chk_solicitud_payload`). Se leyeron enteros esta vez, antes de escribir una sola tarea |
| K6 | En los fixtures actuales, **cada oportunidad tiene exactamente un ítem conceptual** (un `id_modelo`, una `cantidad`, un `dcto` por fila) | Mismo K5 | El backfill de fixtures es 1:1, sin ambigüedad — cada `crearOportunidad(...)` necesita **una** fila hermana en `oportunidad_items` con los mismos valores |
| K7 | `descuentos trata un dcto NULL como cero` y `descuentos excluye las oportunidades cerradas` están marcados explícitamente como **comportamiento confirmado por el dueño del producto**, no bugs | Comentarios KDoc en `ReporteServiceSqlIntegrationTest.kt:371-376, 417-421` | Estas dos reglas de negocio deben preservarse exactas en la reescritura — no son negociables ni objeto de nueva decisión |
| K8 | La sincronización puente de D21 (`OportunidadItemServiceImpl.sincronizarColumnasViejas`) es la **única** razón por la que las columnas viejas siguen existiendo. Una vez que `reportes`/`inicio` no las lean, no tiene ningún consumidor | `plan-05-mapa-migrar-items.md` D21, `OportunidadItemServiceImpl.kt` | Se retira en este plan, junto con las columnas |
| K9 | `OportunidadRepository` no tiene ninguna query (`@Query`, derivada o `Specification`) que use las columnas viejas — ya lo confirmó el audit de B10/B17 del Plan B | Lectura completa de `OportunidadRepository.kt` | Nada que tocar ahí |

---

## 3. Estado real verificado en producción (2026-09-04)

```sql
SELECT count(*) FROM oportunidades;                                    -- 5
SELECT count(*) FROM oportunidad_items;                                -- 5
SELECT count(*) FROM oportunidades o
  WHERE (SELECT count(*) FROM oportunidad_items i
         WHERE i.id_oportunidad = o.id) > 1;                           -- 0
```

Sigue habiendo exactamente una oportunidad por ítem (nadie ha usado todavía
`POST /oportunidades/:id/items` para añadir un segundo modelo). El `DROP
COLUMN` no pierde ningún dato: todo lo que hay en las columnas viejas ya está
duplicado en `oportunidad_items` por el backfill de V42 y mantenido
sincronizado por D21 desde entonces.

---

## 4. Decisiones de diseño

### D22 · Fórmula de dinero duplicada en SQL nativo, documentada explícitamente

`reportes` es, por diseño explícito (KDoc del propio `ReporteService.kt`),
*"SQL nativo parametrizado + agregación en memoria"* — no pasa por Services ni
por JPA. No puede llamar a `MontoTotal.calcular` (vive en
`domain.oportunidades`, cruzar el módulo violaría `CLAUDE.md` regla 12 y
ArchUnit, que solo permite cruzar hacia interfaces/DTOs/enums — `MontoTotal`
es un `object` con lógica, no contrato público).

La fórmula se **duplica en SQL**, igual que ya duplica la agregación de fechas,
estados, etc. — mismo patrón arquitectónico que el resto del módulo:

```sql
ROUND(cantidad * precio_venta * (1 - COALESCE(descuento, 0) / 100), 2)
```

Cada consulta que la use debe llevar un comentario apuntando a
`MontoTotal.calcular` como la fuente de verdad de la fórmula, para que un
cambio futuro en una no se olvide de la otra.

### D23 · `operacionesCount` cuenta oportunidades, no ítems; monto/unidades suman ítems

Decisión derivada (mismo criterio que D9 de `plan-03`, no requiere consulta):
una "operación" es un trato comercial (una oportunidad), no una unidad
vendida. `ReporteVentasDto.operacionesCount`, `ReporteEquipoItemDto.oportunidadesActivas`
y `.oportunidadesCerradasMes` siguen contando **oportunidades distintas**
(`COUNT(DISTINCT o.id)` o equivalente), mientras que `montoTotal`,
`unidadesTotal`, `porModelo`, `porVendedor` (en la parte de monto/unidades)
**suman sobre los ítems** de esas oportunidades.

Esto requiere que las consultas de `ventas()`, `pipeline()` y `equipo()` no
aplanen a "una fila por ítem" cuando necesitan contar oportunidades: agregan
los ítems de cada oportunidad primero (subconsulta o `JOIN` + `GROUP BY o.id`
antes del agregado final), y solo `porModelo`/`descuentos` (que son
inherentemente por unidad, ver D24) trabajan a nivel de ítem.

### D24 · `descuentos()` pasa a nivel de ítem

El reporte de descuentos existe para vigilar el patrón de descuentos que
aplican los vendedores. Con descuento por ítem (D8 de `plan-03`, ya
implementado en el Plan B), la unidad de análisis correcta es **el ítem**, no
la oportunidad: dos modelos de la misma oportunidad pueden llevar descuentos
distintos, y promediarlos a nivel de oportunidad ocultaría exactamente el
patrón que este reporte busca vigilar.

`descuentos()` pasa a `SELECT ... FROM oportunidad_items i JOIN oportunidades o
ON o.id = i.id_oportunidad WHERE o.estado != 'cerrado' AND o.created_at ...`,
una fila por ítem. **K7 se preserva exacto**: `descuento IS NULL` sigue
contando como 0 en el promedio (ahora a nivel de `i.descuento`), y
`o.estado = 'cerrado'` sigue excluyendo (el filtro se mantiene igual, ahora
sobre la oportunidad dueña del ítem).

### D25 · `porModelo` de `/reportes/ventas` pasa a una fila por ítem

Consecuencia directa de D7 (`plan-03`) + D23: la sub-lista `porModelo` deja de
agruparse desde una fila por oportunidad (con su único `id_modelo`) a
agruparse desde una fila por ítem — cada ítem aporta su modelo, cantidad y
monto de forma independiente. `porVendedor` y `porMes` **no cambian de
granularidad**: siguen agregando a nivel de oportunidad (D23), porque
"ventas por vendedor en el mes" es sobre operaciones, no sobre unidades por
modelo.

### D26 · Los fixtures de test crean el ítem en el mismo `INSERT`

`crearOportunidad`/`crearOportunidadFacturada` (K5, K6) ganan un `INSERT INTO
oportunidad_items` inmediatamente después de crear la oportunidad, con los
mismos valores (`id_modelo`, `cantidad`, `precio_venta = precio_unitario`,
`descuento = dcto`). Un ítem por oportunidad, exactamente como hoy — **no se
inventan escenarios multi-ítem en estos tests** (eso es cobertura nueva,
opcional, no una migración de lo existente).

### D27 · La sincronización D21 se retira por completo

Una vez que `reportes`/`inicio` leen ítems directamente, nada depende de que
`oportunidades.cantidad/precio_unitario/dcto/monto_total/id_modelo` estén
sincronizadas. `OportunidadItemServiceImpl.sincronizarColumnasViejas()` y sus
4 puntos de llamada (`crear`, `actualizar`, `eliminar`, `aplicarDescuentoAprobado`)
se eliminan. `OportunidadServiceImpl.crear()` deja de sembrar `idModelo` en la
entidad `Oportunidad` (ya no hace falta, y la columna se elimina en la misma
migración).

### D28 · Migración V46: `DROP COLUMN` + actualizar constantes del test canario

```sql
ALTER TABLE oportunidades
    DROP COLUMN cantidad,
    DROP COLUMN precio_unitario,
    DROP COLUMN dcto,
    DROP COLUMN monto_total,
    DROP COLUMN id_modelo;
```

Sin backfill (§3: los datos ya están duplicados en `oportunidad_items`, y no
se pierde nada). `SchemaMigrationIntegrationTest` no necesita cambios en la
lista de tablas ni de enums (`DROP COLUMN` no afecta ninguna de las dos), pero
**sí** en `SeedFixtures.MIGRACIONES_TOTAL`/`MIGRACION_VERSION_MAX` (ambas pasan
a 46, sin hueco nuevo — la numeración de esta migración es correlativa).

> **Verificar antes de aplicar** (D4 de `plan-00`, ya conocido): releer
> `flyway_schema_history` contra producción inmediatamente antes de aplicar,
> por si algo tomó el número V46 mientras tanto.

### D29 · Sort por agregado tras retirar D21: subconsulta SQL nativa (decisión del dueño del producto, 2026-09-04, mecánica derivada)

Hallazgo que abre este plan: `sort=cantidad`/`sort=monto_total` en
`GET /oportunidades` (retenidos por D9 de `plan-03` y confirmados sin
subconsulta por B10 del Plan B) **solo funcionaban porque D21 los mantenía
sincronizados**. D27 retira D21 — así que este plan tiene que resolver el
sort de verdad, no heredar la solución barata que B10 encontró.

**Decisión del dueño del producto: se implementa la subconsulta**, no se
retira la capacidad de ordenar.

**Mecánica (decisión de arquitecto, no requiere otra consulta):** la
aritmética de `monto_total` (`cantidad × precio_venta × (1 − descuento/100)`,
con nulos vía `COALESCE`) es incómoda de expresar con `CriteriaBuilder` — no
hay un idiom limpio de coalesce-y-multiplicar-encadenado como en SQL, y
forzarlo ahí sería la **tercera** reimplementación de la misma fórmula
(Kotlin en `MontoTotal.calcular`, SQL en `reportes` por D22, y ahora Criteria
API aquí) con una técnica *distinta* cada vez. En vez de eso, `listar()` gana
una **rama de consulta nativa** exclusiva para cuando `sort` es `cantidad` o
`montoTotal`:

- Si `sort` es uno de los campos simples (`id`, `estado`, `fechaCierreEstimado`,
  `createdAt`, `updatedAt`): sigue el camino actual sin cambios,
  `Specification` + `Sort` de Spring Data.
- Si `sort` es `cantidad` o `montoTotal`: `listar()` usa una query SQL nativa
  paginada (mismo patrón que `reportes`/`inicio`, `NamedParameterJdbcTemplate`)
  que aplica los mismos filtros de visibilidad/estado que `especificacion()`
  ya construye, más un `ORDER BY` sobre una subconsulta correlacionada:

  ```sql
  ORDER BY (
      SELECT COALESCE(SUM(i.cantidad * i.precio_venta * (1 - COALESCE(i.descuento, 0) / 100)), 0)
      FROM oportunidad_items i
      WHERE i.id_oportunidad = o.id
  ) {ASC|DESC}
  ```
  (para `cantidad`, la subconsulta es `SELECT COALESCE(SUM(i.cantidad), 0) ...`,
  sin la fórmula de dinero).
- Los ids de la página resultante se usan para recuperar las entidades JPA
  completas (`oportunidadRepository.findAllById(ids)`, reordenadas según el
  orden de la query nativa — `findAllById` no garantiza orden) y pasarlas por
  el mismo `toDtos()` de siempre. **No se duplica la construcción del DTO.**

Mismo comentario obligatorio que D22: apuntar a `MontoTotal.calcular` como
fuente de verdad de la fórmula, para que un cambio futuro no la actualice en
un solo sitio de los tres.

---

## 5. Alcance — lista de archivos que este plan toca

**Nuevos:**
```
src/main/resources/db/migration/V46__drop_columnas_planas_oportunidades.sql
```

**Modificados:**
```
src/main/kotlin/pe/quantum/crm/domain/reportes/ReporteService.kt
src/main/kotlin/pe/quantum/crm/domain/inicio/InicioDao.kt
src/main/kotlin/pe/quantum/crm/domain/oportunidades/OportunidadItemServiceImpl.kt   (retira D21)
src/main/kotlin/pe/quantum/crm/domain/oportunidades/OportunidadServiceImpl.kt       (deja de sembrar idModelo en crear())
src/main/kotlin/pe/quantum/crm/domain/oportunidades/Oportunidad.kt                  (entidad JPA: retira las 5 columnas)
src/test/kotlin/pe/quantum/crm/domain/reportes/ReporteServiceIntegrationTest.kt
src/test/kotlin/pe/quantum/crm/domain/reportes/ReporteServiceSqlIntegrationTest.kt
src/test/kotlin/pe/quantum/crm/domain/oportunidades/OportunidadItemServiceImplTest.kt (quita los tests de sincronización de D21)
src/test/kotlin/pe/quantum/crm/domain/oportunidades/OportunidadListadoSpecificationTest.kt (reescribe el test de sort de D21 → D29)
src/test/kotlin/pe/quantum/crm/support/SeedFixtures.kt                              (bump de constantes)
docs/contrato_api.md                                                                (nota de changelog, non-breaking)
docs/reglas_negocio.md                                                              (§7/§12: quitar la nota de "columna puente")
```

**Además, 11 archivos de test que instancian `Oportunidad(...)` directamente
con los 5 campos que se eliminan del constructor** (verificado con `grep`,
2026-09-04): `OportunidadActualizarTest.kt`, `OportunidadCambiarEstadoInvariantesTest.kt`,
`OportunidadContactosTest.kt`, `OportunidadControllerWebMvcTest.kt`,
`OportunidadCrearTest.kt`, `OportunidadesDeContactoImplTest.kt`,
`OportunidadItemServiceImplTest.kt`, `OportunidadLecturasTest.kt`,
`OportunidadListadoSpecificationTest.kt`, `OportunidadRolApoyoTest.kt`,
`OportunidadServiceImplTest.kt` — todos en `src/test/kotlin/pe/quantum/crm/domain/oportunidades/`.
Cada uno tiene su propio fixture privado `oportunidad(...)`/`oportunidadDto(...)`
(no hay un helper compartido); el arreglo es mecánico: quitar los 5 argumentos
nombrados de cada llamada al constructor, sin tocar el resto del fixture.

**Fuera de alcance explícito:** ningún endpoint nuevo, ningún cambio de rol o
permiso, `domain/solicitudes/` no se toca (ya migrado en el Plan B).

---

## 6. Reparto en tareas — ver `plan-08-retirar-columnas-tareas.md`
