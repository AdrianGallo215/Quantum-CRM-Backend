# Mapa maestro — Oportunidades multi-modelo (`oportunidad_items`)

> Documento de coordinación del rediseño que convierte una oportunidad de
> "un modelo por fila" a "varios ítems por oportunidad". No contiene tareas
> ejecutables: fija hallazgos, decisiones y el reparto en planes.
>
> Redactado: 2026-09-01. Desbloquea la V40 de simulaciones (hallazgo H1 del
> `plan-00-mapa-simulaciones.md`).

---

## 1. Por qué este cambio es grande

`Instrucciones_simulaciones.md` decía, sobre esta misma tabla: *"No la crees tú
ni modifiques `oportunidades`"*, porque no es un `CREATE TABLE` aislado. Hoy
`oportunidades` guarda `id_modelo`, `cantidad`, `precio_unitario`, `dcto` y
`monto_total` **en la propia fila** (V10). Repartir eso en ítems toca cinco
módulos, el contrato de API, dos bloques de SQL nativo y ~3 000 líneas de tests.

---

## 2. Hallazgos de la investigación previa

Verificados contra el repo **y contra Supabase producción** (`fmkqomwyakxeblfinkuy`).

| # | Hallazgo | Evidencia | Consecuencia |
|---|---|---|---|
| I1 | **Solo hay 5 oportunidades en producción** | `count(*)` sobre `oportunidades` | La migración de datos es trivial y verificable fila a fila. Cambia el perfil de riesgo por completo |
| I2 | Las 5 tienen datos completos y **coherentes** | `monto_total` coincide **exactamente** con `cantidad × precio_unitario × (1 − dcto/100)` en los 5 casos | El backfill a ítems es determinista, sin casos sucios que arbitrar |
| I3 | **Ninguna facturada**; 2 cerradas, 3 en `evaluacion_calidda` | `estado` de las 5 filas | No hay operaciones cerradas contablemente cuyo monto no se pueda recalcular |
| I4 | **Ninguna FK apunta a las columnas que se mueven** | `pg_constraint` sobre `confrelid = oportunidades`: `eventos`, `tareas`, `buses_entregados`, `oportunidad_contactos`, `oportunidad_estados_log` — todas a `oportunidades(id)` | Mover los campos no rompe integridad referencial |
| I5 | **Las 5 oportunidades ya tienen carpeta de Drive creada** | `drive_folder_id IS NOT NULL` en las 5 | El nombre de carpeta usa hoy el código del modelo. Las existentes **no se renombran** |
| I6 | Solo **1 solicitud de descuento** histórica y **0 pendientes** | `solicitudes` con `dcto_solicitado` | El flujo de descuento aprobado no tiene nada en vuelo que migrar |
| I7 | El radio de impacto cruza **5 módulos** | `OportunidadServiceImpl` (39 refs), sus DTOs (28), `ReporteService` (22), `MontoTotal` (10), `PoliticaDescuento`, `InicioDao`, `SolicitudServiceImpl`, `OportunidadesDeContacto` | Justifica repartir en varios planes |
| I8 | **13 archivos de test** tocan estos campos | `grep` sobre `src/test/` | Los tests se rompen en masa; hay que planificarlo, no descubrirlo |
| I9 | `reportes` e `inicio` agregan con **SQL nativo** sin cobertura unitaria | `ReporteService.kt:92-98,273-286`, `InicioDao.kt:76-77,107` | Zona de mayor riesgo: solo la cubren tests `@Tag("integration")`, hoy bloqueados por Docker 29 |

### Tres acoplamientos que no son obvios

- **`CAMPOS_ORDENABLES`** (`OportunidadServiceImpl.kt:775-784`) permite ordenar el
  listado por `cantidad`, `precioUnitario` y `montoTotal`. Si esos campos dejan
  de estar en la fila, **el `sort` del contrato deja de funcionar**: ordenar por
  un derivado exige subconsulta agregada, no un `ORDER BY` de columna.
- **El nombre de la carpeta de Drive** se compone con el código del modelo
  (`OportunidadServiceImpl.kt:593`). Con varios modelos no hay un código único.
- **`aplicarDescuentoAprobado(id, dcto, idAprobador)`** recibe el id de la
  **oportunidad**. Con descuento por ítem, la firma cambia — y quien la llama es
  `solicitudes` (`SolicitudServiceImpl.kt:280`), que además guarda
  `dcto_solicitado` sin referencia a ítem.

---

## 3. Decisiones (tomadas por el dueño del producto el 2026-09-01)

### D6 · Las columnas viejas se eliminan; `monto_total` se deriva

`oportunidad_items` es la **única** fuente de `id_modelo`, `cantidad`,
`precio_unitario` y `descuento`. El `monto_total` de la oportunidad se calcula
como la suma de sus ítems **al vuelo en el DTO, sin persistirse**.

Coherente con `CLAUDE.md` regla 2 (*"`monto_total` se calcula, nunca se acepta
como input"*) llevada a su conclusión: si es derivable, no se guarda. Es
**breaking** para el frontend y obliga a reescribir el SQL nativo de reportes e
inicio. I1/I2 hacen la migración de datos barata.

### D7 · Los reportes agrupan por ítem

Cada ítem cuenta en su modelo: una oportunidad con 2 modelos aporta a ambos, con
su cantidad y su monto. Es lo correcto de negocio y **cambia los números que el
reporte devuelve hoy** — hay que avisarlo al equipo de frontend.

### D8 · El descuento vive en el ítem y se valida ítem por ítem

Cada ítem lleva su `descuento`, validado contra el límite del rol
(`PoliticaDescuento`: vendedor 3 %, jdv 7 %, admin/gerencia sin límite).
Coherente con `reglas_simulaciones.md`, que ya asume el descuento a nivel de
ítem. `solicitudes.dcto_solicitado` pasa a referenciar el ítem concreto.

> **Consecuencia que el Plan C debe cerrar:** validar ítem por ítem deja abierto
> que alguien reparta un descuento alto entre varios ítems. Con el límite por
> ítem eso **no** evade nada (cada ítem se compara contra el mismo tope), pero sí
> cambia el monto agregado. Se documenta explícitamente en `reglas_negocio.md`
> para que no se lea como un descuido.

### D9 · Decisiones de arquitecto derivadas (no requieren consulta)

| Punto | Decisión | Razón |
|---|---|---|
| Sort por `montoTotal` | **Se mantiene**, resuelto con subconsulta agregada sobre ítems | Quitarlo sería un breaking extra del contrato sin necesidad; el coste es una subconsulta |
| Sort por `cantidad` / `precioUnitario` | **Se mantiene** `cantidad` (suma de ítems); **se retira** `precioUnitario` | La suma de cantidades tiene sentido; "el precio unitario" de una oportunidad con varios modelos no significa nada |
| Carpetas de Drive existentes | **No se renombran** | I5: las 5 ya existen; `drive_folder_id` ya está persistido y el nombre solo se usa al crear |
| Carpetas nuevas | El nombre deja de depender del modelo | Con varios ítems no hay código único; se usa id de oportunidad + empresa |
| Estrategia de despliegue | **Expand → migrate → contract** en 3 planes | Nunca hay un instante en que el esquema y el código estén desalineados en producción |

---

## 4. Reparto en planes

**Estrategia expand/contract**: el esquema crece primero, el código migra
después, y solo al final se retiran las columnas viejas. En ningún punto
intermedio la aplicación queda rota si hay que parar.

| Plan | Alcance | Migración | Estado |
|---|---|---|---|
| **A** (`plan-04`) | Crear `oportunidad_items` + backfill de las 5 filas + entidad/repo/DTOs. **Sin cambiar comportamiento observable** | V42 (crear + backfill) | Cerrado |
| **B** (`plan-05`/`plan-06`) | El dominio pasa a ítems: CRUD de ítems, `monto_total` derivado, sort, Drive, descuento por ítem + `solicitudes` | V44 (enum de solicitudes) | Cerrado 2026-09-03 |
| **C** (plan aún sin escribir) | Consumidores (`reportes`, `inicio`), retirada de columnas viejas, contrato y documentación | Drop de columnas (V45+, el número real se fija al escribir el plan — V43 ya la ocupó `create_simulaciones`) | Se detalla al cerrar B |

### Por qué A no detalla B y C

El detalle de B depende de la forma final que tome la entidad en A (nombres de
campo, si el ítem expone `montoTotal` propio, cómo se ordena la colección).
Escribir ahora tareas atómicas para B contra una entidad todavía no escrita
produce tareas que hay que reescribir enteras — lo contrario de lo que un plan
para subagentes debe ser. Mismo criterio que se aplicó en `plan-00` §3.

### Punto de corte seguro

**Al terminar el Plan A la aplicación sigue funcionando exactamente igual que
hoy.** `oportunidad_items` existe y está poblada, pero nadie la lee todavía: las
columnas viejas siguen siendo la fuente de verdad. Si el trabajo se detiene ahí,
no queda nada roto — y **V40 (simulaciones) ya se puede aplicar**, porque su FK
a `oportunidad_items(id)` queda satisfecha.

Ese es el motivo de ordenar los planes así: el Plan A, que es el más barato y el
menos arriesgado, es el que desbloquea el módulo de simulaciones.

---

## 5. Lo que este rediseño rompe para el frontend

A registrar en `contrato_api.md §26` cuando el Plan C cierre. Se adelanta aquí
para que el equipo de frontend lo sepa antes, no después:

- `oportunidades` deja de tener `id_modelo`, `cantidad`, `precio_unitario` y
  `dcto` en la raíz del objeto; pasan a vivir en un array `items`.
- `monto_total` sigue existiendo en el response (suma de los ítems), pero deja de
  ser persistido y **ya no se puede filtrar ni ordenar por él con la semántica
  anterior** (el orden se mantiene, ver D9).
- `sort=precio_unitario` desaparece del listado.
- `POST` y `PUT /oportunidades` cambian de forma: los campos de modelo/cantidad/
  precio/descuento viajan dentro de `items`.
- Los números de `GET /reportes` agrupados por modelo cambian (D7).

---

## 6. Estado de producción al abrir este mapa

| Elemento | Estado |
|---|---|
| `flyway_schema_history` | V41 aplicada y registrada (`installed_rank` 40, checksum `-201211154`) |
| `tipo_cambio` | Creada, con CHECKs y RLS verificados |
| `oportunidad_items` | **No existe** — la crea el Plan A |
| V40 (`simulaciones`) | Escrita, **sin aplicar**, en `docs/migrations/`. Se aplica al cerrar el Plan A |

Ninguna migración se aplica a producción sin confirmación explícita del dueño
del producto.
