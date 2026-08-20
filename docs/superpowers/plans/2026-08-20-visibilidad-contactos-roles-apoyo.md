# Visibilidad de contactos para roles de apoyo — Plan de implementación

> **Para agentes:** este plan se ejecuta con `superpowers:subagent-driven-development`. Un subagente por tarea, revisión entre tareas. Los pasos usan checkbox (`- [ ]`).
>
> **REGLA DURA PARA EL SUBAGENTE:** ejecuta **exactamente** lo que dice tu tarea. No decidas, no "mejores", no agregues comportamiento que no esté escrito aquí. Todo el código que necesitas está transcrito literal en los pasos. Si algo no compila o un test no falla/pasa como dice el paso esperado, **detente y reporta** — no improvises una corrección.

**Goal:** cerrar la fuga de PII en el módulo de contactos — hoy cualquier cuenta autenticada (incluidos los roles de apoyo `analista`/`otro`, que no tienen cartera propia) puede listar, ver en detalle y **editar** nombre, teléfono y correo de todos los contactos del CRM.

**Architecture:** se replica el patrón de visibilidad que `EmpresaServiceImpl` ya usa desde el PR #9: `ContactoServiceImpl` pide a `TareaService.idsEmpresasDondeColabora(idEmpleado)` las empresas donde el usuario colabora, las traduce a ids de contacto vía `empresa_contactos`, y aplica esos ids como predicado **dentro de la query** (`Specification`), nunca en memoria. Un parámetro nuevo `?contexto=` distingue las dos pantallas que hoy comparten `GET /contactos` (listado vs. buscador de vinculación), porque tienen reglas de visibilidad opuestas para el mismo rol.

**Tech Stack:** Kotlin 1.9.25 · Spring Boot 3.2 · Spring Data JPA (Criteria/`Specification`) · JUnit 5 · MockK · AssertJ · ArchUnit · ktlint · detekt · Kover.

**Spec:** [`docs/requerimientos/2026-08-20-visibilidad-contactos-analista-otro.json`](../../requerimientos/2026-08-20-visibilidad-contactos-analista-otro.json) — requisitos R1–R10, `preguntas_abiertas: []`.

---

## Fase de investigación — qué dicen los documentos del repo sobre este cambio

Exigido por `CLAUDE.md § "Cómo escribir un plan de implementación en este repo"`. Esto no es decoración: son las reglas concretas que este diff puede romper.

### Documentos de referencia consultados (tabla de `CLAUDE.md`)

| Documento | Qué dice **exactamente** sobre este cambio |
|---|---|
| `docs/matriz_permisos.md` §1 (línea 3) | *"Los filtros de visibilidad se aplican automáticamente en el backend — el frontend no puede sobreescribirlos."* → la mitigación de UI que ya aplicó el frontend **no cierra el bug**; el filtro tiene que estar acá. |
| `docs/matriz_permisos.md` §1 (línea 30) | Fila **Contactos**: `analista` = *"Todos (sin cambios — este plan no tocó el módulo contactos; ver nota)"*. Es la fila que este plan corrige. |
| `docs/matriz_permisos.md` §1 (líneas 38–44) | Nota del 2026-08-18: Contactos queda como deuda conocida, *"candidatos a un ticket aparte vía `redactar-requerimiento`"*. Este plan **es** ese ticket; la nota debe actualizarse (Tarea 7). |
| `docs/matriz_permisos.md` §1 (línea 46) | La visibilidad de un rol de apoyo depende **únicamente de `ids_colaboradores`**, no de `id_asignado`. Ser dueño de una tarea no da visibilidad. Se hereda tal cual: no se toca esa semántica. |
| `docs/matriz_permisos.md` §2.3 (líneas 86–101) | Tabla de Contactos: `Buscar contactos` ✓, `Editar contacto` ✓ para `analista`/`otro`, sin restricción. Más la nota: *"Sin guard propio en este módulo (2026-08-18): el cambio de `analista`/`otro` a rol de apoyo no tocó `ContactoServiceImpl`"*. Se actualiza en Tarea 7. |
| `docs/contrato_api.md` §9 (líneas 745–857) | `GET /contactos`, `GET /contactos/:id`, `PUT /contactos/:id` documentan **`Roles: todos`** sin filtro. `GET /contactos` acepta `q` (*"nombre o teléfono"*). Se actualiza en Tarea 6. |
| `docs/contrato_api.md` §25 (líneas 2254–2267) | *"Todo PR que modifique la forma de un request/response, un código de error, la semántica de un campo, o agregue/quite un endpoint documentado aquí, agrega una entrada a esta tabla **en el mismo PR**. Sin entrada, el PR no se considera completo aunque el código y los tests pasen."* → Tarea 6 es obligatoria. |
| `docs/TESTING-backend.md` §2, §5, §11 | Ciclo RED→GREEN obligatorio. *"Nunca escribir código de producción sin un test que falle primero."* *"Un test que no ha fallado nunca no es confiable."* Cada tarea de código de este plan tiene su paso RED explícito. |
| `docs/TESTING-backend.md` §6 | Flujo de integración obligatorio: *"Permisos: vendedor accediendo a recurso ajeno → 404"*. Se cubre con tests de servicio + WebMvc (sin Testcontainers: ver Restricciones globales). |
| `docs/TESTING-backend.md` §8 | Cobertura de servicios de dominio ≥ 90%, global ≥ 75%. `./gradlew koverVerify` en la Tarea 8. |
| `docs/TESTING-backend.md` §9 | Nombres de test en backticks describiendo comportamiento, estructura Arrange-Act-Assert. |
| `docs/DEVOPS-backend.md` §2 | *"cualquier funcionalidad, fix o cambio de esquema que implemente Claude Code va en su propia `feature/xxx` o `fix/xxx`, nunca commiteado directo a `main`"*. Rama: `fix/visibilidad-contactos-roles-apoyo`. Commits en Conventional Commits. |
| `docs/reglas_negocio.md` §11.1/§11.2 | `cargo`/`toma_decision` viven en la relación, no en el contacto; no se puede borrar un contacto vinculado. **Este plan no toca ninguna de las dos.** Se cita para dejar constancia de que se revisó y no aplica. |
| `docs/schema.sql` / `src/main/resources/db/migration/` | **No se necesita migración.** El cambio es puramente de lógica de visibilidad; `contactos` y `empresa_contactos` (V8/V9) quedan igual. |
| `docs/PRD-backend.md` §11 | Fase 2 (Empresas y contactos) ya está construida y en producción. No hay dependencia de fase pendiente. |

### Reglas de `CLAUDE.md` que este diff toca

| # | Regla | Cómo la respeta este plan |
|---|---|---|
| **1** | **TDD siempre.** Test que falla ANTES del código. | Cada tarea de código tiene paso RED (`Expected: FAIL con <mensaje>`) antes del GREEN. |
| **8** | Inyección por constructor (`private val`), nunca `@Autowired` en campos. | `TareaService` entra como `@Lazy private val tareaService: TareaService` en el constructor (Tarea 2). |
| **9** | Relaciones JPA `LAZY`; nunca exponer entidades en controllers, siempre DTOs. | No se agregan relaciones JPA. Los modos reducidos devuelven `ContactoListaDto`/`ContactoDetalleDto`, nunca `Contacto`. |
| **10** | `@Transactional(readOnly = true)` en lecturas, `@Transactional` en escrituras. | `buscar`/`detalle` conservan `readOnly = true`; `actualizar` conserva `@Transactional`. No se cambia ninguna anotación. |
| **11** | Queries parametrizadas siempre. Nunca SQL por concatenación. | Todo el filtrado va por Criteria API (`root.get<Long>("id").in(ids)`). Cero SQL en string. |
| **12** | **Un módulo nunca accede a tablas ni entidades de otro módulo.** Solo vía API pública. Lo verifica ArchUnit. | `contactos` consume `TareaService` (interfaz pública) y recibe `Set<Long>` de ids. Nunca toca `TareaRepository` ni la entidad `Tarea`. `ArquitecturaModulosTest` lo verifica en `./gradlew test`. |
| **14** | **IDOR: recurso ajeno → 404, no 403.** | `GET /contactos/:id` fuera de alcance → `NoEncontradoException` (404). **Excepción deliberada y aprobada por producto (R10):** `PUT /contactos/:id` fuera de alcance → 403, mismo criterio que `EmpresaServiceImpl.rechazarSiEsApoyo` ("puedes consultarlo, no editarlo"). Esta desviación está justificada abajo. |

**Por qué el 403 del `PUT` no rompe la regla 14:** la regla protege contra *enumeración* — que un 403 confirme la existencia de un recurso que el usuario no debería saber que existe. Acá no aplica: en modo `vincular` el mismo usuario **puede ver legítimamente** ese contacto por nombre (R5/R8), así que su existencia no es secreta para él. Devolver 404 al editar mentiría sobre algo que el propio sistema le acaba de mostrar. Es exactamente el razonamiento que `EmpresaServiceImpl.rechazarSiEsApoyo` ya documenta en su KDoc (`EmpresaServiceImpl.kt:524-534`): *"403 y no 404: la empresa puede ser visible para él si colabora en una tarea suya."*

### Reglas de `CLAUDE.md` que este diff NO toca (verificado, no asumido)

Reglas **2** (`monto_total`), **3** (`estado_cartera`), **4** (eventos no cambian estado), **5** (`motivo_cierre`), **6** (paso a `facturado`), **7** (no existe `perdido`), **13** (secretos). Ninguna línea de este plan las roza: no se toca `oportunidades`, ni `empresas`, ni el schema, ni configuración.

### Deriva de documentación detectada durante el triage

`docs/contrato_api.md:855` documenta `DELETE /contactos/:id` con **`Roles: admin gerente jdv`**. El rol se llama **`gerencia`** desde V25, y el código ya lo dice bien: `@PreAuthorize("hasAnyRole('admin', 'gerencia', 'jdv')")` en [`ContactoController.kt:79`](../../../src/main/kotlin/pe/quantum/crm/domain/contactos/ContactoController.kt#L79). Es la misma deriva que `CLAUDE.md` regla 6 cita como ejemplo histórico. Se corrige en la Tarea 6 (una línea, en la misma sección del doc que ya estamos editando).

---

## Restricciones globales

Aplican a **todas** las tareas. Ningún paso las repite; se dan por incluidas.

- **JDK 21, Kotlin 1.9.25, Spring Boot 3.2.** No se sube ninguna versión.
- **Sin migración de base de datos.** Ninguna tarea crea archivos en `src/main/resources/db/migration/`. Si crees que necesitas una, **detente y reporta**.
- **Sin Testcontainers.** Los tests de integración (`@Tag("integration")`) **no corren en local** en esta máquina (Docker 29 incompatible). Ninguna tarea de este plan escribe tests `@Tag("integration")`. Todo se cubre con tests unitarios (MockK) y de contexto sin base de datos (`@Import(SinBaseDeDatosMocks::class)`).
- **Comando de verificación por tarea:** `./gradlew test` (unitarios + ArchUnit, rápido, sin Docker). El gate completo (`ktlintFormat`, `detekt`, `koverVerify`) corre en la Tarea 8.
- **ktlint:** longitud máxima de línea **140** (`config/detekt/detekt.yml:8`). Si una línea se pasa, córtala; `./gradlew ktlintFormat` arregla la mayoría.
- **detekt** corre con `buildUponDefaultConfig = true`. Umbral de `LongParameterList` = 6 parámetros por función. `ContactoServiceImpl.buscar` va a tener 8 → la Tarea 2 agrega `"LongParameterList"` al `@Suppress` de la clase. **No lo quites.**
- **Enums en minúsculas:** este repo nombra las entradas de enum en `snake_case` minúscula porque son valores del contrato/DB. Eso exige `@Suppress("ktlint:standard:enum-entry-name-case", "EnumNaming", "EnumEntryName")` sobre la declaración del enum (patrón establecido en `src/main/kotlin/pe/quantum/crm/shared/enums/Enums.kt`). **No lo quites.**
- **Nombres de campo con guion bajo** (`email_1`, `tlf_1`) son intencionales: son columnas de V8 y campos públicos del contrato. `config/detekt/detekt.yml` ya tiene la excepción. **Nunca los renombres a `email1`.**
- **Rama:** todo el trabajo va en `fix/visibilidad-contactos-roles-apoyo`. **Nunca commits directos a `main`** (`DEVOPS-backend.md` §2).
- **Commits:** Conventional Commits en español, como el resto del repo (`fix(contactos): ...`, `test(contactos): ...`, `docs(contrato): ...`).
- **Idioma del código:** comentarios y KDoc en español **sin tildes** (patrón del repo: `vinculacion`, `busqueda`, `transaccion`). Los nombres de test en backticks **sí** llevan tildes normales.

### Decisión técnica tomada al escribir el plan (leer antes de la Tarea 3)

La pregunta P1 del ticket nació de este riesgo concreto, textual del reporte de frontend:

> *"hoy ese mismo endpoint permite además buscar por teléfono, así que sin filtro un usuario podría inferir coincidencias de un número aunque el frontend deje de mostrarlo."*

Producto respondió que en modo `vincular` el rol de apoyo **busca sobre todo el CRM pero solo ve el nombre** (R5). Si `q` siguiera matcheando contra `tlf_1`/`tlf_2` en ese modo, el endpoint seguiría siendo un **oráculo de teléfonos**: escribo un número, y si vuelve una fila sé de quién es — el dato "oculto" se filtra igual por el canal de búsqueda.

**Por eso, en modo reducido `q` matchea solo contra nombre y apellidos, nunca contra teléfonos** (Tarea 3, parámetro `soloPorNombre`). Es la lectura estricta de *"solo debe poder ver su nombre"* y cierra el canal que originó la pregunta. Es también la dirección conservadora: restringe, no abre. Queda registrado acá y en el changelog del contrato (Tarea 6) para que sea visible y reversible si producto prefiere otra cosa.

---

## Mapa de archivos

### Se crean

| Archivo | Responsabilidad |
|---|---|
| `src/main/kotlin/pe/quantum/crm/domain/contactos/dto/ContextoBusquedaContacto.kt` | Enum del parámetro `?contexto=` + los dos predicados que deciden el modo (`esReducidoPara`, `aplicaFiltroDeVisibilidadPara`). **Única fuente de verdad de la regla de modo**: ni el controller ni el servicio la reimplementan. |
| `src/test/kotlin/pe/quantum/crm/domain/contactos/ContextoBusquedaContactoTest.kt` | Tests del enum: parsing, default restrictivo, valor inválido, y los dos predicados por rol. |
| `src/test/kotlin/pe/quantum/crm/domain/contactos/ContactoRolApoyoTest.kt` | Tests de comportamiento del servicio para `analista`/`otro`: filtro del listado, modo reducido, 404 del detalle, 403 del `PUT`. Archivo aparte para no engordar los dos ya existentes (mismo criterio que `EmpresaRolApoyoTest`). |

### Se modifican

| Archivo | Cambio |
|---|---|
| `src/main/kotlin/pe/quantum/crm/domain/contactos/ContactoService.kt` | Firmas de `buscar` (+`contexto`) y `detalle` (+`usuario`, +`contexto`). |
| `src/main/kotlin/pe/quantum/crm/domain/contactos/ContactoServiceImpl.kt` | `@Lazy TareaService` en el constructor; helper `idsContactosVisiblesPara`; filtro en `buscar`; modo reducido en `buscar`/`detalle`; 404 en `detalle`; 403 en `actualizar`. |
| `src/main/kotlin/pe/quantum/crm/domain/contactos/ContactoController.kt` | Parámetro `contexto` en los dos `GET`; no enriquecer con `oportunidades_count`/`oportunidades`/`actividades` en modo reducido. |
| `src/test/kotlin/pe/quantum/crm/domain/contactos/ContactoServiceImplTest.kt` | Constructor (+`tareaService`) y llamadas a `detalle`. |
| `src/test/kotlin/pe/quantum/crm/domain/contactos/ContactoServiceImplEscrituraTest.kt` | Constructor (+`tareaService`). |
| `src/test/kotlin/pe/quantum/crm/domain/contactos/ContactoBusquedaSpecificationTest.kt` | Constructor (+`tareaService`); helper `buscar()` acepta usuario/contexto; tests nuevos de HQL para el filtro y para `soloPorNombre`. |
| `src/test/kotlin/pe/quantum/crm/domain/contactos/ContactoControllerWebMvcTest.kt` | Stubs de `detalle` con la firma nueva; tests WebMvc de `?contexto=`. |
| `docs/contrato_api.md` | §9 (tres endpoints) + §25 (changelog) + fix `gerente`→`gerencia` en línea 855. |
| `docs/matriz_permisos.md` | §1 fila Contactos + notas 38/44; §2.3 tabla + nota final. |

---

## Tarea 0 — Preparar la rama

> **Modelo: `sonnet` · Effort: `low`.** Es mecánico.

- [ ] **Paso 1: Verificar que no hay trabajo sin guardar que se pueda perder**

```bash
git status --short
```

Esperado: solo `M build.gradle.kts` y `?? docs/requerimientos/...json` y `?? docs/superpowers/...md`. **Si aparece cualquier otra cosa, DETENTE y reporta.**

- [ ] **Paso 2: Crear la rama desde `main`**

```bash
git checkout -b fix/visibilidad-contactos-roles-apoyo
```

- [ ] **Paso 3: Confirmar la rama**

```bash
git branch --show-current
```

Esperado: `fix/visibilidad-contactos-roles-apoyo`

- [ ] **Paso 4: Dejar constancia del baseline verde**

```bash
./gradlew test
```

Esperado: `BUILD SUCCESSFUL`. **Si falla, DETENTE y reporta** — no arregles nada: el baseline roto invalida todos los pasos RED de este plan.

---

## Tarea 1 — Enum `ContextoBusquedaContacto`

> **Modelo: `sonnet` · Effort: `medium`.** Archivo nuevo, autocontenido, sin tocar nada existente.

**Files:**
- Create: `src/main/kotlin/pe/quantum/crm/domain/contactos/dto/ContextoBusquedaContacto.kt`
- Test: `src/test/kotlin/pe/quantum/crm/domain/contactos/ContextoBusquedaContactoTest.kt`

**Interfaces:**
- Consumes: `pe.quantum.crm.shared.security.UsuarioActual` (propiedad `esRolApoyo: Boolean`, ya existe), `pe.quantum.crm.shared.exception.ValidacionException(message: String, field: String?)`.
- Produces (lo usan las Tareas 2–5):
  - `enum class ContextoBusquedaContacto { listado, vincular }`
  - `fun ContextoBusquedaContacto.esReducidoPara(usuario: UsuarioActual): Boolean`
  - `fun ContextoBusquedaContacto.aplicaFiltroDeVisibilidadPara(usuario: UsuarioActual): Boolean`
  - `fun ContextoBusquedaContacto.Companion.desde(valor: String?): ContextoBusquedaContacto`

- [ ] **Paso 1: Escribir el test que falla**

Crea `src/test/kotlin/pe/quantum/crm/domain/contactos/ContextoBusquedaContactoTest.kt` con exactamente este contenido:

```kotlin
package pe.quantum.crm.domain.contactos

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import pe.quantum.crm.domain.contactos.dto.ContextoBusquedaContacto
import pe.quantum.crm.shared.exception.ValidacionException
import pe.quantum.crm.shared.security.UsuarioActual

/**
 * El enum es la unica fuente de verdad de "en que modo esta esta peticion".
 * Si el controller o el servicio reimplementaran la regla, los dos modos podrian
 * divergir y el reducido dejaria de serlo por una de las dos puertas.
 */
class ContextoBusquedaContactoTest {
    private val analista = UsuarioActual(id = 7, rol = "analista")
    private val otro = UsuarioActual(id = 8, rol = "otro")
    private val vendedor = UsuarioActual(id = 42, rol = "vendedor")
    private val jdv = UsuarioActual(id = 3, rol = "jdv")
    private val admin = UsuarioActual(id = 1, rol = "admin")

    /**
     * El default NO puede ser `vincular`: un frontend viejo que todavia no manda
     * el parametro abriria la busqueda global sin que nadie lo pidiera.
     */
    @Test
    fun `contexto ausente, vacio o en blanco cae en listado, que es el modo restrictivo`() {
        assertThat(ContextoBusquedaContacto.desde(null)).isEqualTo(ContextoBusquedaContacto.listado)
        assertThat(ContextoBusquedaContacto.desde("")).isEqualTo(ContextoBusquedaContacto.listado)
        assertThat(ContextoBusquedaContacto.desde("   ")).isEqualTo(ContextoBusquedaContacto.listado)
    }

    @Test
    fun `contexto vincular se reconoce`() {
        assertThat(ContextoBusquedaContacto.desde("vincular")).isEqualTo(ContextoBusquedaContacto.vincular)
    }

    @Test
    fun `contexto listado explicito se reconoce`() {
        assertThat(ContextoBusquedaContacto.desde("listado")).isEqualTo(ContextoBusquedaContacto.listado)
    }

    @Test
    fun `contexto con espacios alrededor se normaliza`() {
        assertThat(ContextoBusquedaContacto.desde("  vincular  ")).isEqualTo(ContextoBusquedaContacto.vincular)
    }

    /**
     * Un valor fuera del enum es un error del cliente (400), no un filtro que se
     * ignora: mismo criterio que `?estado_cartera=` en EmpresaServiceImpl.
     */
    @Test
    fun `un contexto fuera del enum lanza ValidacionException y nombra los permitidos`() {
        assertThatThrownBy { ContextoBusquedaContacto.desde("global") }
            .isInstanceOf(ValidacionException::class.java)
            .hasMessageContaining("listado")
            .hasMessageContaining("vincular")
    }

    @Test
    fun `el contexto invalido apunta al campo contexto`() {
        val error = assertThatThrownBy { ContextoBusquedaContacto.desde("VINCULAR") }
        error.isInstanceOf(ValidacionException::class.java)
        assertThat((org.assertj.core.api.Assertions.catchThrowable { ContextoBusquedaContacto.desde("VINCULAR") } as ValidacionException).field)
            .isEqualTo("contexto")
    }

    @Test
    fun `solo un rol de apoyo en contexto vincular recibe la respuesta reducida`() {
        assertThat(ContextoBusquedaContacto.vincular.esReducidoPara(analista)).isTrue()
        assertThat(ContextoBusquedaContacto.vincular.esReducidoPara(otro)).isTrue()
        assertThat(ContextoBusquedaContacto.vincular.esReducidoPara(vendedor)).isFalse()
        assertThat(ContextoBusquedaContacto.vincular.esReducidoPara(jdv)).isFalse()
        assertThat(ContextoBusquedaContacto.vincular.esReducidoPara(admin)).isFalse()
        assertThat(ContextoBusquedaContacto.listado.esReducidoPara(analista)).isFalse()
        assertThat(ContextoBusquedaContacto.listado.esReducidoPara(otro)).isFalse()
    }

    @Test
    fun `solo un rol de apoyo en contexto listado arrastra el filtro de visibilidad`() {
        assertThat(ContextoBusquedaContacto.listado.aplicaFiltroDeVisibilidadPara(analista)).isTrue()
        assertThat(ContextoBusquedaContacto.listado.aplicaFiltroDeVisibilidadPara(otro)).isTrue()
        assertThat(ContextoBusquedaContacto.listado.aplicaFiltroDeVisibilidadPara(vendedor)).isFalse()
        assertThat(ContextoBusquedaContacto.listado.aplicaFiltroDeVisibilidadPara(jdv)).isFalse()
        assertThat(ContextoBusquedaContacto.listado.aplicaFiltroDeVisibilidadPara(admin)).isFalse()
        assertThat(ContextoBusquedaContacto.vincular.aplicaFiltroDeVisibilidadPara(analista)).isFalse()
    }

    /** Los dos modos son excluyentes: ninguna combinacion activa ambos a la vez. */
    @Test
    fun `ningun usuario cae a la vez en modo reducido y en filtro de visibilidad`() {
        listOf(analista, otro, vendedor, jdv, admin).forEach { usuario ->
            ContextoBusquedaContacto.entries.forEach { contexto ->
                assertThat(contexto.esReducidoPara(usuario) && contexto.aplicaFiltroDeVisibilidadPara(usuario))
                    .describedAs("rol %s en contexto %s", usuario.rol, contexto.name)
                    .isFalse()
            }
        }
    }
}
```

- [ ] **Paso 2: Ejecutar el test y verificar que falla**

```bash
./gradlew test --tests "pe.quantum.crm.domain.contactos.ContextoBusquedaContactoTest"
```

Esperado: **FAIL de compilación**, con un error tipo `Unresolved reference: ContextoBusquedaContacto`. Ese es el RED correcto.

- [ ] **Paso 3: Escribir la implementación mínima**

Crea `src/main/kotlin/pe/quantum/crm/domain/contactos/dto/ContextoBusquedaContacto.kt` con exactamente este contenido:

```kotlin
package pe.quantum.crm.domain.contactos.dto

import pe.quantum.crm.shared.exception.ValidacionException
import pe.quantum.crm.shared.security.UsuarioActual

/**
 * Contexto de `GET /contactos` y `GET /contactos/:id` (contrato_api.md §9).
 *
 * El mismo endpoint sirve dos pantallas con reglas de visibilidad OPUESTAS para
 * los roles de apoyo (`analista`/`otro`), y hasta ahora nada en el request las
 * distinguia:
 *
 *  - `listado`  — vista de Contactos. Un rol de apoyo solo alcanza los contactos
 *    de las empresas donde colabora via tarea, y los ve completos.
 *  - `vincular` — buscador de "vincular contacto existente" a una empresa. Un rol
 *    de apoyo busca sobre TODO el CRM (si no, no podria vincular un contacto que
 *    todavia no conoce), pero la respuesta solo expone el nombre.
 *
 * Ausente => `listado`, que es el modo restrictivo: un cliente que todavia no
 * manda el parametro nunca abre la busqueda global por omision.
 */
@Suppress("ktlint:standard:enum-entry-name-case", "EnumNaming", "EnumEntryName")
enum class ContextoBusquedaContacto {
    listado,
    vincular,
    ;

    /**
     * true si a este usuario, en este contexto, solo se le expone el nombre del
     * contacto — sin telefonos, correos, notas ni empresas — y la busqueda NO se
     * restringe a su alcance de colaboracion.
     */
    fun esReducidoPara(usuario: UsuarioActual): Boolean = this == vincular && usuario.esRolApoyo

    /**
     * true si el resultado debe restringirse a los contactos que este usuario
     * alcanza por colaboracion (matriz_permisos.md §1).
     */
    fun aplicaFiltroDeVisibilidadPara(usuario: UsuarioActual): Boolean = this == listado && usuario.esRolApoyo

    companion object {
        /**
         * `?contexto=` fuera del enum es un error del cliente (400), no un valor
         * que se ignora: mismo criterio que `?estado_cartera=` en empresas.
         * Ausente, vacio o en blanco cae en `listado`.
         */
        fun desde(valor: String?): ContextoBusquedaContacto {
            val pedido = valor?.trim()?.takeIf { it.isNotEmpty() } ?: return listado
            return entries.firstOrNull { it.name == pedido }
                ?: throw ValidacionException(
                    "El contexto '$pedido' no es válido. Contextos permitidos: " +
                        entries.joinToString(", ") { it.name },
                    field = "contexto",
                )
        }
    }
}
```

- [ ] **Paso 4: Ejecutar el test y verificar que pasa**

```bash
./gradlew test --tests "pe.quantum.crm.domain.contactos.ContextoBusquedaContactoTest"
```

Esperado: **PASS**, 9 tests.

- [ ] **Paso 5: Ejecutar la suite completa**

```bash
./gradlew test
```

Esperado: `BUILD SUCCESSFUL`. Nada existente se rompió (archivo nuevo, sin call-sites).

- [ ] **Paso 6: Commit**

```bash
git add src/main/kotlin/pe/quantum/crm/domain/contactos/dto/ContextoBusquedaContacto.kt src/test/kotlin/pe/quantum/crm/domain/contactos/ContextoBusquedaContactoTest.kt
git commit -m "feat(contactos): agregar ContextoBusquedaContacto para distinguir listado de vinculacion

El mismo GET /contactos sirve la vista de listado y el buscador de vincular,
con reglas de visibilidad opuestas para analista/otro. El enum centraliza en un
solo sitio que modo aplica; ausente cae en el modo restrictivo.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

## Tarea 2 — Filtro de visibilidad en `GET /contactos` (R1, R3, R4)

> **Modelo: `sonnet` · Effort: `medium`.** Toca 5 archivos pero todos los cambios están transcritos literalmente.

**Files:**
- Modify: `src/main/kotlin/pe/quantum/crm/domain/contactos/ContactoService.kt`
- Modify: `src/main/kotlin/pe/quantum/crm/domain/contactos/ContactoServiceImpl.kt`
- Modify: `src/test/kotlin/pe/quantum/crm/domain/contactos/ContactoServiceImplTest.kt` (solo el constructor)
- Modify: `src/test/kotlin/pe/quantum/crm/domain/contactos/ContactoServiceImplEscrituraTest.kt` (solo el constructor)
- Modify: `src/test/kotlin/pe/quantum/crm/domain/contactos/ContactoBusquedaSpecificationTest.kt` (constructor + helper + tests nuevos)
- Test: `src/test/kotlin/pe/quantum/crm/domain/contactos/ContactoRolApoyoTest.kt` (nuevo)

**Interfaces:**
- Consumes: `ContextoBusquedaContacto` (Tarea 1); `TareaService.idsEmpresasDondeColabora(idEmpleado: Long): Set<Long>`; `EmpresaContactoRepository.findByIdIdEmpresaIn(idsEmpresa: Collection<Long>): List<EmpresaContacto>` (**ya existe**, `ContactoRepository.kt:14`).
- Produces (lo usan las Tareas 3–5):
  - `ContactoService.buscar(q, idEmpresa, usuario, page, perPage, sort, dir, contexto = ContextoBusquedaContacto.listado): Paginado<ContactoListaDto>`
  - `ContactoServiceImpl` privado: `fun idsContactosVisiblesPara(usuario: UsuarioActual): Set<Long>`
  - `ContactoServiceImpl` privado: `fun restriccionPorIds(ids: Collection<Long>?, root: Root<Contacto>, cb: CriteriaBuilder): Predicate?`
  - `ContactoServiceImpl` constructor: `(ContactoRepository, EmpresaContactoRepository, EmpresaService, TareaService)` — **4 parámetros, en ese orden**.

- [ ] **Paso 1: Escribir el test que falla — comportamiento del servicio**

Crea `src/test/kotlin/pe/quantum/crm/domain/contactos/ContactoRolApoyoTest.kt` con exactamente este contenido:

```kotlin
package pe.quantum.crm.domain.contactos

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.data.domain.PageImpl
import org.springframework.data.domain.PageRequest
import org.springframework.data.jpa.domain.Specification
import pe.quantum.crm.domain.contactos.dto.ContextoBusquedaContacto
import pe.quantum.crm.domain.empresas.EmpresaService
import pe.quantum.crm.domain.tareas.TareaService
import pe.quantum.crm.shared.security.UsuarioActual
import java.time.LocalDateTime

/**
 * Visibilidad de contactos para los roles de apoyo (`analista`/`otro`), que no
 * tienen cartera propia y solo alcanzan lo que colaboran via tarea
 * (matriz_permisos.md §1).
 *
 * Archivo aparte de `ContactoServiceImplTest` (lectura) y
 * `ContactoServiceImplEscrituraTest` (escritura), mismo criterio que
 * `EmpresaRolApoyoTest`: la regla de visibilidad es su propia unidad y merece un
 * sitio donde se lea entera.
 */
class ContactoRolApoyoTest {
    private val contactoRepository = mockk<ContactoRepository>()
    private val empresaContactoRepository = mockk<EmpresaContactoRepository>()
    private val empresaService = mockk<EmpresaService>()
    private val tareaService = mockk<TareaService>()
    private val service =
        ContactoServiceImpl(contactoRepository, empresaContactoRepository, empresaService, tareaService)

    private val analista = UsuarioActual(id = 7, rol = "analista")
    private val otro = UsuarioActual(id = 8, rol = "otro")
    private val vendedor = UsuarioActual(id = 42, rol = "vendedor")
    private val admin = UsuarioActual(id = 1, rol = "admin")

    private fun contacto(id: Long = 1) =
        Contacto(
            id = id,
            nombres = "Hugo",
            apellidos = "Rodríguez",
            email_1 = "hugo@transportes.pe",
            tlf_1 = "964415122",
            notas = "Prefiere WhatsApp",
            createdAt = LocalDateTime.now(),
            createdBy = 1,
            updatedAt = LocalDateTime.now(),
            updatedBy = 1,
        )

    /** Devuelve una pagina con un contacto y sin vinculos, para los casos que llegan al repositorio. */
    private fun paginaConUnContacto() {
        every { contactoRepository.findAll(any<Specification<Contacto>>(), any<PageRequest>()) } returns
            PageImpl(listOf(contacto()), PageRequest.of(0, 20), 1)
        every { empresaContactoRepository.findByIdIdContacto(1) } returns emptyList()
        every { empresaService.resumenPorIds(emptyList()) } returns emptyMap()
    }

    private fun buscarListado(usuario: UsuarioActual) =
        service.buscar(
            q = null,
            idEmpresa = null,
            usuario = usuario,
            page = null,
            perPage = null,
            sort = null,
            dir = null,
            contexto = ContextoBusquedaContacto.listado,
        )

    // ── R1: el listado consulta la colaboracion ────────────────

    @Test
    fun `el listado de un analista resuelve sus contactos desde las empresas donde colabora`() {
        every { tareaService.idsEmpresasDondeColabora(7) } returns setOf(3L, 4L)
        every { empresaContactoRepository.findByIdIdEmpresaIn(setOf(3L, 4L)) } returns
            listOf(
                EmpresaContacto(id = EmpresaContactoId(idEmpresa = 3, idContacto = 1)),
                EmpresaContacto(id = EmpresaContactoId(idEmpresa = 4, idContacto = 2)),
            )
        paginaConUnContacto()

        buscarListado(analista)

        verify(exactly = 1) { tareaService.idsEmpresasDondeColabora(7) }
        verify(exactly = 1) { empresaContactoRepository.findByIdIdEmpresaIn(setOf(3L, 4L)) }
    }

    @Test
    fun `el rol otro recibe el mismo tratamiento que analista`() {
        every { tareaService.idsEmpresasDondeColabora(8) } returns setOf(5L)
        every { empresaContactoRepository.findByIdIdEmpresaIn(setOf(5L)) } returns
            listOf(EmpresaContacto(id = EmpresaContactoId(idEmpresa = 5, idContacto = 1)))
        paginaConUnContacto()

        buscarListado(otro)

        verify(exactly = 1) { tareaService.idsEmpresasDondeColabora(8) }
    }

    /**
     * Sin colaboraciones no hay ni una consulta a `empresa_contactos`: el conjunto
     * ya se sabe vacio. La Specification resultante debe filtrar todo, no dejar
     * pasar el listado completo — eso lo verifica ContactoBusquedaSpecificationTest.
     */
    @Test
    fun `un rol de apoyo sin colaboraciones no consulta los vinculos`() {
        every { tareaService.idsEmpresasDondeColabora(7) } returns emptySet()
        every { contactoRepository.findAll(any<Specification<Contacto>>(), any<PageRequest>()) } returns
            PageImpl(emptyList(), PageRequest.of(0, 20), 0)

        assertThat(buscarListado(analista).items).isEmpty()

        verify(exactly = 0) { empresaContactoRepository.findByIdIdEmpresaIn(any()) }
    }

    // ── R4: los demas roles no cambian ─────────────────────────

    @Test
    fun `un vendedor no arrastra el filtro de colaboracion`() {
        paginaConUnContacto()

        assertThat(buscarListado(vendedor).items).hasSize(1)

        verify(exactly = 0) { tareaService.idsEmpresasDondeColabora(any()) }
        verify(exactly = 0) { empresaContactoRepository.findByIdIdEmpresaIn(any()) }
    }

    @Test
    fun `un admin no arrastra el filtro de colaboracion`() {
        paginaConUnContacto()

        assertThat(buscarListado(admin).items).hasSize(1)

        verify(exactly = 0) { tareaService.idsEmpresasDondeColabora(any()) }
    }

    /**
     * El listado de un rol de apoyo sigue devolviendo la fila completa: el recorte
     * de campos es exclusivo del modo `vincular`, no de este.
     */
    @Test
    fun `el listado de un rol de apoyo devuelve la fila completa, no reducida`() {
        every { tareaService.idsEmpresasDondeColabora(7) } returns setOf(3L)
        every { empresaContactoRepository.findByIdIdEmpresaIn(setOf(3L)) } returns
            listOf(EmpresaContacto(id = EmpresaContactoId(idEmpresa = 3, idContacto = 1)))
        paginaConUnContacto()

        val fila = buscarListado(analista).items.first()

        assertThat(fila.tlf_1).isEqualTo("964415122")
        assertThat(fila.email_1).isEqualTo("hugo@transportes.pe")
        assertThat(fila.notas).isEqualTo("Prefiere WhatsApp")
    }
}
```

- [ ] **Paso 2: Ejecutar el test y verificar que falla**

```bash
./gradlew test --tests "pe.quantum.crm.domain.contactos.ContactoRolApoyoTest"
```

Esperado: **FAIL de compilación** — `Too many arguments for constructor ContactoServiceImpl` y `No value passed for parameter 'contexto'`. Ese es el RED correcto.

- [ ] **Paso 3: Cambiar la firma de `buscar` en la interfaz**

En `src/main/kotlin/pe/quantum/crm/domain/contactos/ContactoService.kt`:

**3a.** Agrega este import, en orden alfabético junto a los demás `import pe.quantum.crm.domain.contactos.dto.*`:

```kotlin
import pe.quantum.crm.domain.contactos.dto.ContextoBusquedaContacto
```

**3b.** Reemplaza el bloque de `buscar` (líneas 23–31) por:

```kotlin
    /**
     * Busqueda paginada de contactos. `contexto` decide el modo de visibilidad
     * para los roles de apoyo (ver `ContextoBusquedaContacto`); el default es el
     * restrictivo, asi que un llamante que no lo pase nunca abre la busqueda
     * global por descuido.
     */
    fun buscar(
        q: String?,
        idEmpresa: Long?,
        usuario: UsuarioActual,
        page: Int?,
        perPage: Int?,
        sort: String?,
        dir: String?,
        contexto: ContextoBusquedaContacto = ContextoBusquedaContacto.listado,
    ): Paginado<ContactoListaDto>
```

- [ ] **Paso 4: Implementar el filtro en `ContactoServiceImpl`**

En `src/main/kotlin/pe/quantum/crm/domain/contactos/ContactoServiceImpl.kt`:

**4a.** Agrega estos imports (respetando el orden alfabético del bloque de imports existente):

```kotlin
import jakarta.persistence.criteria.CriteriaBuilder
import jakarta.persistence.criteria.Root
import org.springframework.context.annotation.Lazy
import pe.quantum.crm.domain.contactos.dto.ContextoBusquedaContacto
import pe.quantum.crm.domain.tareas.TareaService
```

**4b.** Reemplaza la anotación `@Suppress` de la clase y su constructor (líneas 30–36) por:

```kotlin
@Service
// TooManyFunctions: vinculacion con empresas y oportunidades; igual que su interfaz.
// LongParameterList: `buscar` arrastra los 4 parametros de paginacion del contrato
// mas el contexto de visibilidad; son el contrato del endpoint, no una firma suelta.
@Suppress("TooManyFunctions", "LongParameterList")
class ContactoServiceImpl(
    private val contactoRepository: ContactoRepository,
    private val empresaContactoRepository: EmpresaContactoRepository,
    private val empresaService: EmpresaService,
    // Solo la interfaz publica de tareas (CLAUDE.md regla 12): contactos nunca toca
    // `tareas` ni `tarea_responsables`, recibe ids. `@Lazy` porque tareas ya depende
    // de ContactoService (existe/resumenPorIds) y Spring Boot 3 rechaza los ciclos
    // de constructor; el proxy corta el ciclo al arrancar. Mismo patron que
    // EmpresaServiceImpl con este mismo colaborador.
    @Lazy private val tareaService: TareaService,
) : ContactoService {
```

**4c.** Reemplaza el método `buscar` completo (líneas 37–57 del original) por:

```kotlin
    @Transactional(readOnly = true)
    override fun buscar(
        q: String?,
        idEmpresa: Long?,
        usuario: UsuarioActual,
        page: Int?,
        perPage: Int?,
        sort: String?,
        dir: String?,
        contexto: ContextoBusquedaContacto,
    ): Paginado<ContactoListaDto> {
        val idsDeLaEmpresa =
            idEmpresa?.let {
                empresaService.vinculoVisible(it, usuario)
                empresaContactoRepository.findByIdIdEmpresa(it).map { vinculo -> vinculo.id.idContacto }
            }
        // Resuelto ANTES de construir la Specification, no dentro de su lambda:
        // Spring Data JPA evalua `toPredicate` dos veces por pagina (contenido y
        // conteo), y esto son dos consultas, no un `equal` gratis. Mismo criterio
        // que EmpresaServiceImpl.especificacion.
        val idsVisibles =
            if (contexto.aplicaFiltroDeVisibilidadPara(usuario)) idsContactosVisiblesPara(usuario) else null
        val pageRequest = Paginacion.pageRequest(page, perPage, sort, dir, CAMPOS_ORDENABLES)
        val resultado = contactoRepository.findAll(especificacion(q, idsDeLaEmpresa, idsVisibles), pageRequest)
        val items = resultado.content.map { it.toListaDto() }
        val meta = Paginacion.meta(pageRequest.pageNumber + 1, pageRequest.pageSize, resultado.totalElements)
        return Paginado(items, meta)
    }
```

**4d.** Reemplaza el método privado `especificacion` completo (líneas 251–276 del original) por:

```kotlin
    private fun especificacion(
        q: String?,
        idsDeLaEmpresa: List<Long>?,
        idsVisibles: Set<Long>?,
    ): Specification<Contacto> =
        Specification { root, _, cb ->
            val predicados = mutableListOf<Predicate>()
            restriccionPorIds(idsDeLaEmpresa, root, cb)?.let { predicados += it }
            restriccionPorIds(idsVisibles, root, cb)?.let { predicados += it }
            q?.takeIf { it.isNotBlank() }?.let { texto ->
                val patron = "%${texto.lowercase()}%"
                predicados +=
                    cb.or(
                        cb.like(cb.lower(cb.concat(cb.concat(root.get("nombres"), " "), root.get("apellidos"))), patron),
                        // Los atributos JPA se llaman como el campo de la entidad
                        // (`tlf_1`/`tlf_2`), no como su version sin guion bajo.
                        cb.like(root.get("tlf_1"), "%${texto.trim()}%"),
                        cb.like(root.get("tlf_2"), "%${texto.trim()}%"),
                    )
            }
            cb.and(*predicados.toTypedArray())
        }

    /**
     * `null` = sin restriccion. Coleccion vacia = falso explicito: `in(emptySet())`
     * es SQL invalido o, peor, un predicado que no filtra nada — y ahi es justo
     * donde se colaria el listado completo.
     */
    private fun restriccionPorIds(
        ids: Collection<Long>?,
        root: Root<Contacto>,
        cb: CriteriaBuilder,
    ): Predicate? {
        if (ids == null) {
            return null
        }
        return if (ids.isEmpty()) cb.disjunction() else root.get<Long>("id").`in`(ids)
    }

    /**
     * Contactos que un rol de apoyo alcanza: los vinculados a alguna empresa donde
     * colabora via tarea (matriz_permisos.md §1). Un contacto sin ninguna empresa
     * vinculada no lo alcanza nadie por esta via, y es lo correcto: el huerfano no
     * pertenece a ninguna cartera.
     *
     * Cruza la frontera del modulo tareas solo con ids, por su interfaz publica
     * (CLAUDE.md regla 12).
     */
    private fun idsContactosVisiblesPara(usuario: UsuarioActual): Set<Long> {
        val empresas = tareaService.idsEmpresasDondeColabora(usuario.id)
        if (empresas.isEmpty()) {
            return emptySet()
        }
        return empresaContactoRepository.findByIdIdEmpresaIn(empresas).map { it.id.idContacto }.toSet()
    }
```

- [ ] **Paso 5: Arreglar los constructores de los tests existentes**

**5a.** En `src/test/kotlin/pe/quantum/crm/domain/contactos/ContactoServiceImplTest.kt`, reemplaza las líneas 19–22 por:

```kotlin
    private val contactoRepository = mockk<ContactoRepository>()
    private val empresaContactoRepository = mockk<EmpresaContactoRepository>()
    private val empresaService = mockk<EmpresaService>()
    private val tareaService = mockk<pe.quantum.crm.domain.tareas.TareaService>()
    private val service =
        ContactoServiceImpl(contactoRepository, empresaContactoRepository, empresaService, tareaService)
```

**5b.** En `src/test/kotlin/pe/quantum/crm/domain/contactos/ContactoServiceImplEscrituraTest.kt`, reemplaza las líneas 36–39 por:

```kotlin
    private val contactoRepository = mockk<ContactoRepository>()
    private val empresaContactoRepository = mockk<EmpresaContactoRepository>()
    private val empresaService = mockk<EmpresaService>()
    private val tareaService = mockk<pe.quantum.crm.domain.tareas.TareaService>()
    private val service =
        ContactoServiceImpl(contactoRepository, empresaContactoRepository, empresaService, tareaService)
```

**5c.** En `src/test/kotlin/pe/quantum/crm/domain/contactos/ContactoBusquedaSpecificationTest.kt`, reemplaza las líneas 38–41 por:

```kotlin
    private val contactoRepository = mockk<ContactoRepository>()
    private val empresaContactoRepository = mockk<EmpresaContactoRepository>()
    private val empresaService = mockk<EmpresaService>()
    private val tareaService = mockk<pe.quantum.crm.domain.tareas.TareaService>()
    private val service =
        ContactoServiceImpl(contactoRepository, empresaContactoRepository, empresaService, tareaService)
```

- [ ] **Paso 6: Ejecutar los tests y verificar que pasan**

```bash
./gradlew test --tests "pe.quantum.crm.domain.contactos.*"
```

Esperado: **PASS**. Si `ContactoRolApoyoTest` falla, revisa que copiaste el paso 4 literal.

- [ ] **Paso 7: Escribir el test que falla — la Specification realmente filtra**

En `src/test/kotlin/pe/quantum/crm/domain/contactos/ContactoBusquedaSpecificationTest.kt`:

**7a.** Agrega estos imports junto a los existentes:

```kotlin
import pe.quantum.crm.domain.contactos.dto.ContextoBusquedaContacto
```

**7b.** Reemplaza el método privado `buscar` completo (líneas 119–138) por:

```kotlin
    private fun buscar(
        q: String?,
        sort: String? = null,
        idEmpresa: Long? = null,
        contactosDeLaEmpresa: List<Long> = emptyList(),
        quien: UsuarioActual = usuario,
        contexto: ContextoBusquedaContacto = ContextoBusquedaContacto.listado,
        empresasDondeColabora: Set<Long> = emptySet(),
        contactosDeEsasEmpresas: List<Long> = emptyList(),
    ): String {
        var hql = ""
        idEmpresa?.let { id ->
            every { empresaService.vinculoVisible(id, quien) } returns
                EmpresaVinculo(id = id, razonSocial = "Transp. Sta. Anita S.A.", idVendedor = null, estadoCartera = "prospeccion")
            every { empresaContactoRepository.findByIdIdEmpresa(id) } returns
                contactosDeLaEmpresa.map { EmpresaContacto(id = EmpresaContactoId(idEmpresa = id, idContacto = it)) }
        }
        if (quien.esRolApoyo) {
            every { tareaService.idsEmpresasDondeColabora(quien.id) } returns empresasDondeColabora
            if (empresasDondeColabora.isNotEmpty()) {
                every { empresaContactoRepository.findByIdIdEmpresaIn(empresasDondeColabora) } returns
                    contactosDeEsasEmpresas.map {
                        EmpresaContacto(id = EmpresaContactoId(idEmpresa = empresasDondeColabora.first(), idContacto = it))
                    }
            }
        }
        every { contactoRepository.findAll(any<Specification<Contacto>>(), any<PageRequest>()) } answers {
            hql = compilar(firstArg(), secondArg())
            PageImpl(emptyList(), PageRequest.of(0, 20), 0)
        }
        service.buscar(
            q = q,
            idEmpresa = idEmpresa,
            usuario = quien,
            page = null,
            perPage = null,
            sort = sort,
            dir = null,
            contexto = contexto,
        )
        return hql
    }
```

**7c.** Agrega estos tests **al final de la clase, justo antes del comentario `// ── privados ───`**:

```kotlin
    // ── visibilidad de roles de apoyo ──────────────────────────

    private val analista = UsuarioActual(id = 7, rol = "analista")

    /**
     * La fuga que cierra este cambio: sin el predicado por ids, un rol de apoyo
     * recibia exactamente el mismo HQL que un admin — o sea, todos los contactos
     * del CRM con telefono y correo.
     */
    @Test
    fun `el listado de un rol de apoyo NO produce el mismo HQL que el de un admin`() {
        val comoAdmin = buscar(q = null)
        val comoAnalista =
            buscar(
                q = null,
                quien = analista,
                empresasDondeColabora = setOf(3L),
                contactosDeEsasEmpresas = listOf(1L, 2L),
            )

        assertThat(comoAnalista).isNotEqualTo(comoAdmin)
        assertThat(comoAnalista).containsIgnoringCase("where").containsIgnoringCase(" in ")
    }

    /**
     * Sin colaboraciones el filtro debe cerrar, no desaparecer: si el predicado se
     * evaporara al quedarse vacio, un analista recien creado veria el CRM entero.
     */
    @Test
    fun `un rol de apoyo sin colaboraciones no cae en el listado completo`() {
        val sinFiltro = buscar(q = null)
        val vacio = buscar(q = null, quien = analista, empresasDondeColabora = emptySet())

        assertThat(vacio).isNotEqualTo(sinFiltro)
        assertThat(vacio).containsIgnoringCase("where")
    }

    /** El filtro de visibilidad y el de `id_empresa` se combinan, no se pisan. */
    @Test
    fun `el filtro de visibilidad convive con el filtro por id_empresa`() {
        val hql =
            buscar(
                q = "Hugo",
                idEmpresa = 3,
                contactosDeLaEmpresa = listOf(1L, 5L),
                quien = analista,
                empresasDondeColabora = setOf(3L),
                contactosDeEsasEmpresas = listOf(1L),
            )

        assertThat(hql).containsIgnoringCase("where").contains("nombres", "apellidos")
    }
```

- [ ] **Paso 8: Ejecutar y verificar que pasan**

```bash
./gradlew test --tests "pe.quantum.crm.domain.contactos.ContactoBusquedaSpecificationTest"
```

Esperado: **PASS**.

- [ ] **Paso 9: Verificar que no se rompió la frontera de módulos (ArchUnit)**

```bash
./gradlew test --tests "pe.quantum.crm.arquitectura.ArquitecturaModulosTest"
```

Esperado: **PASS**. `contactos` depende de la interfaz `TareaService`, que es API pública. Si falla, **DETENTE y reporta** — significa que se coló una entidad o repositorio de otro módulo.

- [ ] **Paso 10: Verificar que el contexto de Spring arranca (no hay ciclo de constructor)**

```bash
./gradlew test --tests "pe.quantum.crm.CrmApplicationTests"
```

Esperado: **PASS**. Si falla con `Circular reference` / `BeanCurrentlyInCreationException`, el `@Lazy` del paso 4b no se aplicó. **DETENTE y reporta.**

- [ ] **Paso 11: Suite completa**

```bash
./gradlew test
```

Esperado: `BUILD SUCCESSFUL`.

- [ ] **Paso 12: Commit**

```bash
git add src/main/kotlin/pe/quantum/crm/domain/contactos/ src/test/kotlin/pe/quantum/crm/domain/contactos/
git commit -m "fix(contactos): filtrar GET /contactos por colaboracion para analista y otro

Los roles de apoyo no tienen cartera propia y listaban nombre, telefono y correo
de todos los contactos del CRM. Ahora el listado se restringe, dentro de la query,
a los contactos vinculados a empresas donde el usuario colabora via tarea; el
contacto huerfano (sin empresa) queda fuera. Mismo criterio que GET /empresas.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

## Tarea 3 — Modo reducido en `GET /contactos` (R5, R6, R9)

> **Modelo: `sonnet` · Effort: `medium`.**

**Files:**
- Modify: `src/main/kotlin/pe/quantum/crm/domain/contactos/ContactoServiceImpl.kt`
- Modify: `src/main/kotlin/pe/quantum/crm/domain/contactos/ContactoController.kt`
- Modify: `src/test/kotlin/pe/quantum/crm/domain/contactos/ContactoRolApoyoTest.kt`
- Modify: `src/test/kotlin/pe/quantum/crm/domain/contactos/ContactoBusquedaSpecificationTest.kt`
- Modify: `src/test/kotlin/pe/quantum/crm/domain/contactos/ContactoControllerWebMvcTest.kt`

**Interfaces:**
- Consumes: `ContextoBusquedaContacto.esReducidoPara(usuario)` (Tarea 1); `ContactoServiceImpl.especificacion(...)` (Tarea 2).
- Produces: `especificacion(q, idsDeLaEmpresa, idsVisibles, soloPorNombre: Boolean)`; `ContactoController.buscar` acepta `@RequestParam contexto: String?`.

- [ ] **Paso 1: Escribir los tests que fallan — servicio**

Agrega estos tests **al final** de `src/test/kotlin/pe/quantum/crm/domain/contactos/ContactoRolApoyoTest.kt`, justo antes de la llave de cierre de la clase:

```kotlin
    // ── R5: modo vincular, respuesta reducida ──────────────────

    private fun buscarParaVincular(
        usuario: UsuarioActual,
        q: String? = null,
    ) = service.buscar(
        q = q,
        idEmpresa = null,
        usuario = usuario,
        page = null,
        perPage = null,
        sort = null,
        dir = null,
        contexto = ContextoBusquedaContacto.vincular,
    )

    /**
     * En el buscador de vinculacion el rol de apoyo alcanza todo el CRM — si no,
     * no podria vincular un contacto que todavia no conoce — asi que aqui NO se
     * consulta la colaboracion.
     */
    @Test
    fun `en modo vincular un rol de apoyo no arrastra el filtro de colaboracion`() {
        paginaConUnContacto()

        assertThat(buscarParaVincular(analista).items).hasSize(1)

        verify(exactly = 0) { tareaService.idsEmpresasDondeColabora(any()) }
    }

    /** Lo que se recorta es el contenido: solo el nombre, nada mas. */
    @Test
    fun `en modo vincular la fila de un rol de apoyo solo lleva el nombre`() {
        every { contactoRepository.findAll(any<Specification<Contacto>>(), any<PageRequest>()) } returns
            PageImpl(listOf(contacto()), PageRequest.of(0, 20), 1)

        val fila = buscarParaVincular(analista).items.first()

        assertThat(fila.id).isEqualTo(1)
        assertThat(fila.nombres).isEqualTo("Hugo")
        assertThat(fila.apellidos).isEqualTo("Rodríguez")
        assertThat(fila.tlf_1).isNull()
        assertThat(fila.tlf_2).isNull()
        assertThat(fila.email_1).isNull()
        assertThat(fila.email_2).isNull()
        assertThat(fila.notas).isNull()
        assertThat(fila.empresas).isEmpty()
        assertThat(fila.oportunidadesCount).isZero()
    }

    /**
     * La fila reducida no consulta los vinculos por fila: ademas de no exponerlos,
     * se ahorra la consulta por contacto que hace `toListaDto`.
     */
    @Test
    fun `la fila reducida no consulta los vinculos del contacto`() {
        every { contactoRepository.findAll(any<Specification<Contacto>>(), any<PageRequest>()) } returns
            PageImpl(listOf(contacto()), PageRequest.of(0, 20), 1)

        buscarParaVincular(analista)

        verify(exactly = 0) { empresaContactoRepository.findByIdIdContacto(any()) }
    }

    /** R4/R6: el modo vincular no cambia nada para quien si tiene cartera. */
    @Test
    fun `en modo vincular un vendedor sigue viendo la fila completa`() {
        paginaConUnContacto()

        val fila = buscarParaVincular(vendedor).items.first()

        assertThat(fila.tlf_1).isEqualTo("964415122")
        assertThat(fila.email_1).isEqualTo("hugo@transportes.pe")
    }

    @Test
    fun `en modo vincular un admin sigue viendo la fila completa`() {
        paginaConUnContacto()

        assertThat(buscarParaVincular(admin).items.first().tlf_1).isEqualTo("964415122")
    }
```

- [ ] **Paso 2: Escribir el test que falla — `q` no busca por teléfono en modo reducido**

Agrega este test a `src/test/kotlin/pe/quantum/crm/domain/contactos/ContactoBusquedaSpecificationTest.kt`, junto a los que agregaste en la Tarea 2 (antes de `// ── privados ───`):

```kotlin
    /**
     * El canal que motivo la pregunta P1 del requerimiento: si `q` siguiera
     * matcheando `tlf_1`/`tlf_2` en modo reducido, el endpoint seria un oraculo de
     * telefonos — escribo un numero, vuelve una fila, ya se de quien es. Ocultar
     * el campo en la respuesta no cierra ese canal; quitarlo del WHERE si.
     */
    @Test
    fun `en modo vincular la busqueda de un rol de apoyo no toca los telefonos`() {
        val hql = buscar(q = "964415122", quien = analista, contexto = ContextoBusquedaContacto.vincular)

        assertThat(hql).contains("nombres", "apellidos")
        assertThat(hql).doesNotContain("tlf_1").doesNotContain("tlf_2")
    }

    /** El resto de roles conserva la busqueda por telefono documentada en §9. */
    @Test
    fun `en modo vincular un vendedor sigue buscando por telefono`() {
        val hql =
            buscar(
                q = "964415122",
                quien = UsuarioActual(id = 42, rol = "vendedor"),
                contexto = ContextoBusquedaContacto.vincular,
            )

        assertThat(hql).contains("tlf_1", "tlf_2")
    }
```

- [ ] **Paso 3: Ejecutar y verificar que fallan**

```bash
./gradlew test --tests "pe.quantum.crm.domain.contactos.ContactoRolApoyoTest" --tests "pe.quantum.crm.domain.contactos.ContactoBusquedaSpecificationTest"
```

Esperado: **FAIL**. Los mensajes típicos: `expected: null but was: "964415122"` (fila no reducida) y `Expecting actual not to contain "tlf_1"`.

- [ ] **Paso 4: Implementar el modo reducido en el servicio**

En `src/main/kotlin/pe/quantum/crm/domain/contactos/ContactoServiceImpl.kt`:

**4a.** Reemplaza el método `buscar` completo por:

```kotlin
    @Transactional(readOnly = true)
    override fun buscar(
        q: String?,
        idEmpresa: Long?,
        usuario: UsuarioActual,
        page: Int?,
        perPage: Int?,
        sort: String?,
        dir: String?,
        contexto: ContextoBusquedaContacto,
    ): Paginado<ContactoListaDto> {
        val reducido = contexto.esReducidoPara(usuario)
        val idsDeLaEmpresa =
            idEmpresa?.let {
                empresaService.vinculoVisible(it, usuario)
                empresaContactoRepository.findByIdIdEmpresa(it).map { vinculo -> vinculo.id.idContacto }
            }
        // Resuelto ANTES de construir la Specification, no dentro de su lambda:
        // Spring Data JPA evalua `toPredicate` dos veces por pagina (contenido y
        // conteo), y esto son dos consultas, no un `equal` gratis. Mismo criterio
        // que EmpresaServiceImpl.especificacion.
        val idsVisibles =
            if (contexto.aplicaFiltroDeVisibilidadPara(usuario)) idsContactosVisiblesPara(usuario) else null
        val pageRequest = Paginacion.pageRequest(page, perPage, sort, dir, CAMPOS_ORDENABLES)
        val resultado =
            contactoRepository.findAll(
                especificacion(q, idsDeLaEmpresa, idsVisibles, soloPorNombre = reducido),
                pageRequest,
            )
        val items = resultado.content.map { if (reducido) it.toListaReducidoDto() else it.toListaDto() }
        val meta = Paginacion.meta(pageRequest.pageNumber + 1, pageRequest.pageSize, resultado.totalElements)
        return Paginado(items, meta)
    }
```

**4b.** Reemplaza el método privado `especificacion` completo por:

```kotlin
    private fun especificacion(
        q: String?,
        idsDeLaEmpresa: List<Long>?,
        idsVisibles: Set<Long>?,
        soloPorNombre: Boolean,
    ): Specification<Contacto> =
        Specification { root, _, cb ->
            val predicados = mutableListOf<Predicate>()
            restriccionPorIds(idsDeLaEmpresa, root, cb)?.let { predicados += it }
            restriccionPorIds(idsVisibles, root, cb)?.let { predicados += it }
            q?.takeIf { it.isNotBlank() }?.let { texto ->
                val patron = "%${texto.lowercase()}%"
                val porNombre =
                    cb.like(cb.lower(cb.concat(cb.concat(root.get("nombres"), " "), root.get("apellidos"))), patron)
                predicados +=
                    if (soloPorNombre) {
                        // Modo reducido: buscar por telefono seria un oraculo. La
                        // respuesta oculta el numero, pero un `like` sobre `tlf_1`
                        // devolveria igual el nombre del dueño de ese numero.
                        porNombre
                    } else {
                        cb.or(
                            porNombre,
                            // Los atributos JPA se llaman como el campo de la entidad
                            // (`tlf_1`/`tlf_2`), no como su version sin guion bajo.
                            cb.like(root.get("tlf_1"), "%${texto.trim()}%"),
                            cb.like(root.get("tlf_2"), "%${texto.trim()}%"),
                        )
                    }
            }
            cb.and(*predicados.toTypedArray())
        }
```

**4c.** Agrega este método privado justo **después** de `private fun Contacto.toListaDto()` (el que ya existe):

```kotlin
    /**
     * Fila del buscador de vinculacion para un rol de apoyo: solo el nombre.
     * Ni telefonos, ni correos, ni notas, ni las empresas del contacto — saber a
     * que empresas pertenece es justo el dato que no tiene por que ver de una
     * empresa donde no colabora. De paso evita la consulta de vinculos por fila
     * que hace `toListaDto`.
     */
    private fun Contacto.toListaReducidoDto(): ContactoListaDto =
        ContactoListaDto(
            id = requireNotNull(id),
            nombres = nombres,
            apellidos = apellidos,
            email_1 = null,
            email_2 = null,
            tlf_1 = null,
            tlf_2 = null,
            notas = null,
            empresas = emptyList(),
        )
```

- [ ] **Paso 5: Ejecutar y verificar que pasan**

```bash
./gradlew test --tests "pe.quantum.crm.domain.contactos.ContactoRolApoyoTest" --tests "pe.quantum.crm.domain.contactos.ContactoBusquedaSpecificationTest"
```

Esperado: **PASS**.

- [ ] **Paso 6: Escribir el test WebMvc que falla — el controller acepta `?contexto=`**

Agrega estos tests **al final** de `src/test/kotlin/pe/quantum/crm/domain/contactos/ContactoControllerWebMvcTest.kt`, antes de la llave de cierre de la clase:

```kotlin
    // ── contexto de visibilidad (contrato_api.md §9) ───────────

    private fun filaReducida() =
        ContactoListaDto(
            id = 5,
            nombres = "Hugo",
            apellidos = "Rodríguez",
            email_1 = null,
            email_2 = null,
            tlf_1 = null,
            tlf_2 = null,
            notas = null,
            empresas = emptyList(),
        )

    /**
     * `oportunidades_count` es pipeline: enriquecer la fila reducida reabriria por
     * otra puerta justo el dato que el modo esconde.
     */
    @Test
    fun `GET contactos en modo vincular con rol de apoyo no consulta oportunidades_count`() {
        every {
            contactoService.buscar(null, null, any(), null, null, null, null, ContextoBusquedaContacto.vincular)
        } returns Paginado(listOf(filaReducida()), PageMeta(page = 1, perPage = 20, total = 1, totalPages = 1))
        val token = jwtService.generateAccessToken(empleadoId = 7, rol = "analista")

        mockMvc.get("/api/v1/contactos?contexto=vincular") {
            header(HttpHeaders.AUTHORIZATION, "Bearer $token")
        }.andExpect {
            status { isOk() }
            jsonPath("$.data[0].id") { value(5) }
            jsonPath("$.data[0].oportunidades_count") { value(0) }
            jsonPath("$.data[0].tlf_1") { doesNotExist() }
        }

        verify(exactly = 0) { oportunidadesDeContacto.contarPorContactos(any(), any()) }
    }

    /** Un rol con cartera propia no entra en modo reducido aunque pida `vincular`. */
    @Test
    fun `GET contactos en modo vincular con vendedor sigue enriqueciendo el conteo`() {
        val item =
            ContactoListaDto(
                id = 5, nombres = "Hugo", apellidos = "Rodríguez",
                email_1 = null, email_2 = null, tlf_1 = "964415122", tlf_2 = null, notas = null,
                empresas = emptyList(),
            )
        every {
            contactoService.buscar(null, null, any(), null, null, null, null, ContextoBusquedaContacto.vincular)
        } returns Paginado(listOf(item), PageMeta(page = 1, perPage = 20, total = 1, totalPages = 1))
        every { oportunidadesDeContacto.contarPorContactos(listOf(5L), any()) } returns mapOf(5L to 2)
        val token = jwtService.generateAccessToken(empleadoId = 42, rol = "vendedor")

        mockMvc.get("/api/v1/contactos?contexto=vincular") {
            header(HttpHeaders.AUTHORIZATION, "Bearer $token")
        }.andExpect {
            status { isOk() }
            jsonPath("$.data[0].oportunidades_count") { value(2) }
        }
    }

    /** R9: sin el parametro se cae en `listado`, el modo restrictivo. */
    @Test
    fun `GET contactos sin contexto usa el modo listado`() {
        every {
            contactoService.buscar(null, null, any(), null, null, null, null, ContextoBusquedaContacto.listado)
        } returns Paginado(emptyList(), PageMeta(page = 1, perPage = 20, total = 0, totalPages = 0))
        val token = jwtService.generateAccessToken(empleadoId = 7, rol = "analista")

        mockMvc.get("/api/v1/contactos") {
            header(HttpHeaders.AUTHORIZATION, "Bearer $token")
        }.andExpect { status { isOk() } }

        verify {
            contactoService.buscar(null, null, any(), null, null, null, null, ContextoBusquedaContacto.listado)
        }
    }

    @Test
    fun `GET contactos con un contexto invalido devuelve 400 sin llegar al servicio`() {
        val token = jwtService.generateAccessToken(empleadoId = 7, rol = "analista")

        mockMvc.get("/api/v1/contactos?contexto=global") {
            header(HttpHeaders.AUTHORIZATION, "Bearer $token")
        }.andExpect {
            status { isBadRequest() }
            jsonPath("$.error.code") { value("VALIDACION") }
        }

        verify(exactly = 0) { contactoService.buscar(any(), any(), any(), any(), any(), any(), any(), any()) }
    }
```

Y agrega este import al bloque de imports del archivo:

```kotlin
import pe.quantum.crm.domain.contactos.dto.ContextoBusquedaContacto
```

- [ ] **Paso 7: Ejecutar y verificar que fallan**

```bash
./gradlew test --tests "pe.quantum.crm.domain.contactos.ContactoControllerWebMvcTest"
```

Esperado: **FAIL** — el controller todavía no lee `contexto`, así que llama con `listado` siempre y el stub de `vincular` no matchea (`no answer found for ContactoService.buscar(...)`).

- [ ] **Paso 8: Implementar el controller**

En `src/main/kotlin/pe/quantum/crm/domain/contactos/ContactoController.kt`:

**8a.** Agrega este import junto a los demás `import pe.quantum.crm.domain.contactos.dto.*`:

```kotlin
import pe.quantum.crm.domain.contactos.dto.ContextoBusquedaContacto
```

**8b.** Reemplaza el método `buscar` completo (líneas 38–50) por:

```kotlin
    @GetMapping
    fun buscar(
        @RequestParam(required = false) q: String?,
        @RequestParam(name = "id_empresa", required = false) idEmpresa: Long?,
        @RequestParam(required = false) contexto: String?,
        @RequestParam(required = false) page: Int?,
        @RequestParam(name = "per_page", required = false) perPage: Int?,
    ): ApiResponse<List<ContactoListaDto>> {
        val usuario = usuarioProvider.actual()
        val modo = ContextoBusquedaContacto.desde(contexto)
        val resultado = contactoService.buscar(q, idEmpresa, usuario, page, perPage, null, null, modo)
        // En modo reducido la fila solo lleva el nombre: enriquecerla con
        // `oportunidades_count` reabriria por otra puerta el dato que el modo
        // esconde (cuanto pipeline arrastra ese contacto).
        if (modo.esReducidoPara(usuario)) {
            return ApiResponse.ok(resultado.items, resultado.meta)
        }
        val conteos = oportunidadesDeContacto.contarPorContactos(resultado.items.map { it.id }, usuario)
        val conConteo = resultado.items.map { it.copy(oportunidadesCount = conteos[it.id] ?: 0) }
        return ApiResponse.ok(conConteo, resultado.meta)
    }
```

- [ ] **Paso 9: Ejecutar y verificar que pasan**

```bash
./gradlew test --tests "pe.quantum.crm.domain.contactos.ContactoControllerWebMvcTest"
```

Esperado: **PASS**.

- [ ] **Paso 10: Suite completa**

```bash
./gradlew test
```

Esperado: `BUILD SUCCESSFUL`.

- [ ] **Paso 11: Commit**

```bash
git add src/main/kotlin/pe/quantum/crm/domain/contactos/ src/test/kotlin/pe/quantum/crm/domain/contactos/
git commit -m "feat(contactos): modo vincular en GET /contactos con respuesta reducida

El buscador de 'vincular contacto existente' necesita alcanzar todo el CRM, asi
que ?contexto=vincular levanta el filtro de colaboracion para analista/otro pero
recorta la respuesta al nombre. La busqueda por q deja de mirar los telefonos en
ese modo: ocultar el campo no cierra el canal si el WHERE sigue matcheandolo.
Sin el parametro se cae en el modo restrictivo.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

## Tarea 4 — Visibilidad en `GET /contactos/:id` (R2, R8)

> **Modelo: `sonnet` · Effort: `medium`.**

**Files:**
- Modify: `src/main/kotlin/pe/quantum/crm/domain/contactos/ContactoService.kt`
- Modify: `src/main/kotlin/pe/quantum/crm/domain/contactos/ContactoServiceImpl.kt`
- Modify: `src/main/kotlin/pe/quantum/crm/domain/contactos/ContactoController.kt`
- Modify: `src/test/kotlin/pe/quantum/crm/domain/contactos/ContactoServiceImplTest.kt` (3 call-sites de `detalle`)
- Modify: `src/test/kotlin/pe/quantum/crm/domain/contactos/ContactoRolApoyoTest.kt`
- Modify: `src/test/kotlin/pe/quantum/crm/domain/contactos/ContactoControllerWebMvcTest.kt` (3 stubs de `detalle` + tests nuevos)

**Interfaces:**
- Consumes: `idsContactosVisiblesPara(usuario)` (Tarea 2); `ContextoBusquedaContacto` (Tarea 1).
- Produces: `ContactoService.detalle(id: Long, usuario: UsuarioActual, contexto: ContextoBusquedaContacto = ContextoBusquedaContacto.listado): ContactoDetalleDto`

**Regla de comportamiento (transcrita del ticket, R2/R8):**

| Rol | Contexto | Contacto dentro de alcance | Contacto fuera de alcance | Contacto inexistente |
|---|---|---|---|---|
| `analista`/`otro` | `listado` | detalle completo | **404** | 404 |
| `analista`/`otro` | `vincular` | detalle **reducido** | detalle **reducido** | 404 |
| resto de roles | cualquiera | detalle completo | detalle completo | 404 |

En modo `vincular` **nunca hay 404 por alcance**: el rol de apoyo alcanza todo el CRM, y lo que se recorta es el contenido. El 404 por inexistente se mantiene siempre.

- [ ] **Paso 1: Escribir los tests que fallan**

Agrega estos tests **al final** de `src/test/kotlin/pe/quantum/crm/domain/contactos/ContactoRolApoyoTest.kt`, antes de la llave de cierre:

```kotlin
    // ── R2 / R8: detalle ───────────────────────────────────────

    private fun contactoExiste(id: Long = 1) {
        every { contactoRepository.findById(id) } returns java.util.Optional.of(contacto(id))
    }

    private fun colaboraEn(
        idEmpleado: Long,
        empresas: Set<Long>,
        contactos: List<Long>,
    ) {
        every { tareaService.idsEmpresasDondeColabora(idEmpleado) } returns empresas
        if (empresas.isNotEmpty()) {
            every { empresaContactoRepository.findByIdIdEmpresaIn(empresas) } returns
                contactos.map { EmpresaContacto(id = EmpresaContactoId(idEmpresa = empresas.first(), idContacto = it)) }
        }
    }

    /** IDOR (CLAUDE.md regla 14): contacto fuera de alcance -> 404, nunca 403. */
    @Test
    fun `el detalle de un contacto fuera de alcance devuelve 404 para un rol de apoyo`() {
        contactoExiste(1)
        colaboraEn(idEmpleado = 7, empresas = setOf(99L), contactos = listOf(50L))

        org.assertj.core.api.Assertions
            .assertThatThrownBy { service.detalle(1, analista, ContextoBusquedaContacto.listado) }
            .isInstanceOf(pe.quantum.crm.shared.exception.NoEncontradoException::class.java)
    }

    @Test
    fun `el detalle de un rol de apoyo sin colaboraciones devuelve 404`() {
        contactoExiste(1)
        every { tareaService.idsEmpresasDondeColabora(7) } returns emptySet()

        org.assertj.core.api.Assertions
            .assertThatThrownBy { service.detalle(1, analista, ContextoBusquedaContacto.listado) }
            .isInstanceOf(pe.quantum.crm.shared.exception.NoEncontradoException::class.java)
    }

    @Test
    fun `el detalle de un contacto dentro de alcance se devuelve completo`() {
        contactoExiste(1)
        colaboraEn(idEmpleado = 7, empresas = setOf(3L), contactos = listOf(1L))
        every { empresaContactoRepository.findByIdIdContacto(1) } returns emptyList()
        every { empresaService.resumenPorIds(emptyList()) } returns emptyMap()
        every { empresaService.segmentosPorIds(emptyList()) } returns emptyMap()

        val detalle = service.detalle(1, analista, ContextoBusquedaContacto.listado)

        assertThat(detalle.tlf_1).isEqualTo("964415122")
        assertThat(detalle.email_1).isEqualTo("hugo@transportes.pe")
    }

    /** R8: en modo vincular el detalle llega, pero recortado. */
    @Test
    fun `en modo vincular el detalle de un contacto fuera de alcance llega reducido, no 404`() {
        contactoExiste(1)

        val detalle = service.detalle(1, analista, ContextoBusquedaContacto.vincular)

        assertThat(detalle.id).isEqualTo(1)
        assertThat(detalle.nombres).isEqualTo("Hugo")
        assertThat(detalle.apellidos).isEqualTo("Rodríguez")
        assertThat(detalle.tlf_1).isNull()
        assertThat(detalle.email_1).isNull()
        assertThat(detalle.notas).isNull()
        assertThat(detalle.empresas).isEmpty()
        assertThat(detalle.oportunidades).isEmpty()
        assertThat(detalle.actividades).isEmpty()
    }

    @Test
    fun `en modo vincular el detalle no consulta la colaboracion ni los vinculos`() {
        contactoExiste(1)

        service.detalle(1, analista, ContextoBusquedaContacto.vincular)

        verify(exactly = 0) { tareaService.idsEmpresasDondeColabora(any()) }
        verify(exactly = 0) { empresaContactoRepository.findByIdIdContacto(any()) }
    }

    /** El 404 por inexistente se mantiene en los dos modos. */
    @Test
    fun `en modo vincular un contacto inexistente sigue siendo 404`() {
        every { contactoRepository.findById(99) } returns java.util.Optional.empty()

        org.assertj.core.api.Assertions
            .assertThatThrownBy { service.detalle(99, analista, ContextoBusquedaContacto.vincular) }
            .isInstanceOf(pe.quantum.crm.shared.exception.NoEncontradoException::class.java)
    }

    /** R4: un vendedor no pierde nada. */
    @Test
    fun `el detalle de un vendedor no consulta la colaboracion`() {
        contactoExiste(1)
        every { empresaContactoRepository.findByIdIdContacto(1) } returns emptyList()
        every { empresaService.resumenPorIds(emptyList()) } returns emptyMap()
        every { empresaService.segmentosPorIds(emptyList()) } returns emptyMap()

        assertThat(service.detalle(1, vendedor, ContextoBusquedaContacto.listado).tlf_1).isEqualTo("964415122")

        verify(exactly = 0) { tareaService.idsEmpresasDondeColabora(any()) }
    }
```

- [ ] **Paso 2: Ejecutar y verificar que falla**

```bash
./gradlew test --tests "pe.quantum.crm.domain.contactos.ContactoRolApoyoTest"
```

Esperado: **FAIL de compilación** — `Too many arguments for method detalle`.

- [ ] **Paso 3: Cambiar la firma de `detalle` en la interfaz**

En `src/main/kotlin/pe/quantum/crm/domain/contactos/ContactoService.kt`, reemplaza la línea 48–49 (`/** Detalle... */ fun detalle(id: Long): ContactoDetalleDto`) por:

```kotlin
    /**
     * Detalle del contacto: empresas con segmentos. `oportunidades`/`actividades`
     * los completa el controller.
     *
     * Aplica el filtro de visibilidad por rol (matriz_permisos.md §1): un rol de
     * apoyo en contexto `listado` solo alcanza los contactos de las empresas donde
     * colabora, y lo que queda fuera responde 404 — nunca 403 (CLAUDE.md regla 14).
     * En contexto `vincular` alcanza todo el CRM, pero solo se le devuelve el nombre.
     */
    fun detalle(
        id: Long,
        usuario: UsuarioActual,
        contexto: ContextoBusquedaContacto = ContextoBusquedaContacto.listado,
    ): ContactoDetalleDto
```

- [ ] **Paso 4: Implementar en `ContactoServiceImpl`**

**4a.** Reemplaza el método `detalle` completo (líneas 121–150 del original) por:

```kotlin
    @Transactional(readOnly = true)
    override fun detalle(
        id: Long,
        usuario: UsuarioActual,
        contexto: ContextoBusquedaContacto,
    ): ContactoDetalleDto {
        val contacto = entidad(id)
        // Modo reducido: el buscador de vinculacion alcanza todo el CRM, asi que
        // aqui no hay 404 por alcance — lo que se recorta es el contenido.
        if (contexto.esReducidoPara(usuario)) {
            return contacto.toDetalleReducido()
        }
        // IDOR: contacto fuera de alcance -> 404, no 403 (CLAUDE.md regla 14). El
        // mensaje es identico al del inexistente a proposito: no debe poder
        // distinguirse un contacto ajeno de uno que no existe.
        if (contexto.aplicaFiltroDeVisibilidadPara(usuario) && id !in idsContactosVisiblesPara(usuario)) {
            throw NoEncontradoException("El contacto no existe")
        }
        val vinculos = empresaContactoRepository.findByIdIdContacto(id)
        val empresas = empresaService.resumenPorIds(vinculos.map { it.id.idEmpresa })
        val segmentos = empresaService.segmentosPorIds(vinculos.map { it.id.idEmpresa })
        return ContactoDetalleDto(
            id = requireNotNull(contacto.id),
            nombres = contacto.nombres,
            apellidos = contacto.apellidos,
            email_1 = contacto.email_1,
            email_2 = contacto.email_2,
            tlf_1 = contacto.tlf_1,
            tlf_2 = contacto.tlf_2,
            notas = contacto.notas,
            empresas =
                vinculos.mapNotNull { vinculo ->
                    empresas[vinculo.id.idEmpresa]?.let {
                        EmpresaDeContactoDetalleDto(
                            id = it.id,
                            razonSocial = it.razonSocial,
                            cargo = vinculo.cargo,
                            tomaDecision = vinculo.tomaDecision,
                            esPrincipal = vinculo.esPrincipal,
                            segmentos = segmentos[vinculo.id.idEmpresa].orEmpty(),
                        )
                    }
                },
        )
    }
```

**4b.** Agrega este método privado justo **después** de `private fun Contacto.toListaReducidoDto()` (el de la Tarea 3):

```kotlin
    /**
     * Detalle del buscador de vinculacion para un rol de apoyo: solo el nombre.
     * `oportunidades` y `actividades` quedan vacias — el controller ni siquiera
     * las consulta en este modo.
     */
    private fun Contacto.toDetalleReducido(): ContactoDetalleDto =
        ContactoDetalleDto(
            id = requireNotNull(id),
            nombres = nombres,
            apellidos = apellidos,
            email_1 = null,
            email_2 = null,
            tlf_1 = null,
            tlf_2 = null,
            notas = null,
            empresas = emptyList(),
        )
```

- [ ] **Paso 5: Arreglar los 3 call-sites de `detalle` en `ContactoServiceImplTest`**

En `src/test/kotlin/pe/quantum/crm/domain/contactos/ContactoServiceImplTest.kt`, haz estos 3 reemplazos exactos:

| Línea aprox. | Antes | Después |
|---|---|---|
| 89 | `val resultado = service.detalle(1)` | `val resultado = service.detalle(1, usuario)` |
| 168 | `assertThatThrownBy { service.detalle(99) }` | `assertThatThrownBy { service.detalle(99, usuario) }` |
| 210 | `assertThat(service.detalle(1).empresas).isEmpty()` | `assertThat(service.detalle(1, usuario).empresas).isEmpty()` |

(`usuario` en ese archivo es `UsuarioActual(id = 1, rol = "admin")`, así que el filtro no se activa y el comportamiento no cambia.)

- [ ] **Paso 6: Ejecutar y verificar que pasan los tests de servicio**

```bash
./gradlew test --tests "pe.quantum.crm.domain.contactos.ContactoRolApoyoTest" --tests "pe.quantum.crm.domain.contactos.ContactoServiceImplTest"
```

Esperado: **PASS**.

- [ ] **Paso 7: Escribir el test WebMvc que falla**

En `src/test/kotlin/pe/quantum/crm/domain/contactos/ContactoControllerWebMvcTest.kt`:

**7a.** Arregla los 3 stubs existentes de `detalle` (líneas 96, 115, 144):

| Antes | Después |
|---|---|
| `every { contactoService.detalle(99) } throws ...` | `every { contactoService.detalle(99, any(), any()) } throws ...` |
| `every { contactoService.detalle(5) } returns detalle` (línea 115) | `every { contactoService.detalle(5, any(), any()) } returns detalle` |
| `every { contactoService.detalle(5) } returns detalle` (línea 144) | `every { contactoService.detalle(5, any(), any()) } returns detalle` |

**7b.** Agrega estos tests al final de la clase:

```kotlin
    /**
     * En modo reducido el detalle no embebe oportunidades ni actividades: son el
     * pipeline y la agenda de una empresa donde este rol no colabora.
     */
    @Test
    fun `GET contactos por id en modo vincular con rol de apoyo no embebe oportunidades ni actividades`() {
        val reducido =
            pe.quantum.crm.domain.contactos.dto.ContactoDetalleDto(
                id = 5, nombres = "Hugo", apellidos = "Rodríguez",
                email_1 = null, email_2 = null, tlf_1 = null, tlf_2 = null, notas = null,
                empresas = emptyList(),
            )
        every { contactoService.detalle(5, any(), ContextoBusquedaContacto.vincular) } returns reducido
        val token = jwtService.generateAccessToken(empleadoId = 7, rol = "analista")

        mockMvc.get("/api/v1/contactos/5?contexto=vincular") {
            header(HttpHeaders.AUTHORIZATION, "Bearer $token")
        }.andExpect {
            status { isOk() }
            jsonPath("$.data.id") { value(5) }
            jsonPath("$.data.tlf_1") { doesNotExist() }
            jsonPath("$.data.oportunidades") { isEmpty() }
            jsonPath("$.data.actividades") { isEmpty() }
        }

        verify(exactly = 0) { oportunidadesDeContacto.listar(any(), any()) }
        verify(exactly = 0) { tareaService.actividadesPorContacto(any(), any()) }
    }

    /** El controller propaga el usuario autenticado al filtro de visibilidad del servicio. */
    @Test
    fun `GET contactos por id propaga el usuario autenticado al servicio`() {
        val detalle =
            pe.quantum.crm.domain.contactos.dto.ContactoDetalleDto(
                id = 5, nombres = "Hugo", apellidos = "Rodríguez",
                email_1 = null, email_2 = null, tlf_1 = "964415122", tlf_2 = null, notas = null,
                empresas = emptyList(),
            )
        every { contactoService.detalle(5, any(), any()) } returns detalle
        every { oportunidadesDeContacto.listar(5, any()) } returns emptyList()
        every { tareaService.actividadesPorContacto(5, any()) } returns emptyList()
        val token = jwtService.generateAccessToken(empleadoId = 7, rol = "analista")

        mockMvc.get("/api/v1/contactos/5") {
            header(HttpHeaders.AUTHORIZATION, "Bearer $token")
        }.andExpect { status { isOk() } }

        verify {
            contactoService.detalle(
                5,
                UsuarioActual(id = 7, rol = "analista"),
                ContextoBusquedaContacto.listado,
            )
        }
    }

    /** El 404 del servicio (contacto fuera de alcance) llega al cliente como 404. */
    @Test
    fun `GET contactos por id fuera de alcance devuelve 404 NO_ENCONTRADO`() {
        every { contactoService.detalle(5, any(), any()) } throws
            pe.quantum.crm.shared.exception.NoEncontradoException("El contacto no existe")
        val token = jwtService.generateAccessToken(empleadoId = 7, rol = "analista")

        mockMvc.get("/api/v1/contactos/5") {
            header(HttpHeaders.AUTHORIZATION, "Bearer $token")
        }.andExpect {
            status { isNotFound() }
            jsonPath("$.error.code") { value("NO_ENCONTRADO") }
        }
    }
```

- [ ] **Paso 8: Ejecutar y verificar que fallan**

```bash
./gradlew test --tests "pe.quantum.crm.domain.contactos.ContactoControllerWebMvcTest"
```

Esperado: **FAIL** — el controller aún llama a `detalle(id)` con la firma vieja (error de compilación) o no lee `contexto`.

- [ ] **Paso 9: Implementar el controller**

En `src/main/kotlin/pe/quantum/crm/domain/contactos/ContactoController.kt`, reemplaza el método `detalle` completo (líneas 52–64) por:

```kotlin
    @GetMapping("/{id}")
    fun detalle(
        @PathVariable id: Long,
        @RequestParam(required = false) contexto: String?,
    ): ApiResponse<ContactoDetalleDto> {
        val usuario = usuarioProvider.actual()
        val modo = ContextoBusquedaContacto.desde(contexto)
        val contacto = contactoService.detalle(id, usuario, modo)
        // En modo reducido no se embeben oportunidades ni actividades: son el
        // pipeline y la agenda de una empresa donde este rol no colabora.
        if (modo.esReducidoPara(usuario)) {
            return ApiResponse.ok(contacto)
        }
        val completo =
            contacto.copy(
                oportunidades = oportunidadesDeContacto.listar(id, usuario),
                actividades = tareaService.actividadesPorContacto(id, usuario),
            )
        return ApiResponse.ok(completo)
    }
```

- [ ] **Paso 10: Ejecutar y verificar que pasan**

```bash
./gradlew test --tests "pe.quantum.crm.domain.contactos.ContactoControllerWebMvcTest"
```

Esperado: **PASS**.

- [ ] **Paso 11: Suite completa**

```bash
./gradlew test
```

Esperado: `BUILD SUCCESSFUL`.

- [ ] **Paso 12: Commit**

```bash
git add src/main/kotlin/pe/quantum/crm/domain/contactos/ src/test/kotlin/pe/quantum/crm/domain/contactos/
git commit -m "fix(contactos): aplicar visibilidad por rol en GET /contactos/:id

El detalle no recibia siquiera la identidad del llamante: cualquier cuenta abria
cualquier contacto con telefono, correo y notas. Ahora un rol de apoyo fuera de
su alcance recibe 404 (IDOR, regla 14) y en modo vincular recibe el detalle
recortado al nombre, sin oportunidades ni actividades.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

## Tarea 5 — Guard de escritura en `PUT /contactos/:id` (R7, R10)

> **Modelo: `sonnet` · Effort: `medium`.**

**Files:**
- Modify: `src/main/kotlin/pe/quantum/crm/domain/contactos/ContactoServiceImpl.kt`
- Modify: `src/test/kotlin/pe/quantum/crm/domain/contactos/ContactoRolApoyoTest.kt`
- Modify: `src/test/kotlin/pe/quantum/crm/domain/contactos/ContactoControllerWebMvcTest.kt`

**Interfaces:**
- Consumes: `idsContactosVisiblesPara(usuario)` (Tarea 2); `PermisoInsuficienteException(message: String)` → `403 PERMISO_INSUFICIENTE`.
- Produces: `ContactoServiceImpl` privado `fun rechazarSiFueraDeAlcance(idContacto: Long, usuario: UsuarioActual)`.

**Orden de los guards (importante, no lo cambies):** primero `entidad(id)` → 404 si el contacto no existe; **después** el guard de permiso → 403. Así un contacto inexistente sigue dando 404 para todos los roles, que es lo que ya verifica el test `actualizar un contacto inexistente lanza NoEncontradoException sin guardar`.

- [ ] **Paso 1: Escribir los tests que fallan**

Agrega estos tests **al final** de `src/test/kotlin/pe/quantum/crm/domain/contactos/ContactoRolApoyoTest.kt`, antes de la llave de cierre:

```kotlin
    // ── R7 / R10: escritura ────────────────────────────────────

    private fun requestDeActualizacion() =
        pe.quantum.crm.domain.contactos.dto.ActualizarContactoRequest(tlf_1 = "999888777")

    /**
     * 403 y no 404 a proposito (decision de producto, R10): en modo vincular este
     * mismo usuario puede ver el contacto por nombre, asi que su existencia no es
     * secreta para el — esconderlo al editar mentiria. Mismo criterio que
     * `EmpresaServiceImpl.rechazarSiEsApoyo`.
     */
    @Test
    fun `un rol de apoyo no puede editar un contacto fuera de su alcance`() {
        contactoExiste(1)
        colaboraEn(idEmpleado = 7, empresas = setOf(99L), contactos = listOf(50L))

        org.assertj.core.api.Assertions
            .assertThatThrownBy { service.actualizar(1, requestDeActualizacion(), analista) }
            .isInstanceOf(pe.quantum.crm.shared.exception.PermisoInsuficienteException::class.java)
            .hasMessageContaining("apoyo")

        verify(exactly = 0) { contactoRepository.save(any()) }
    }

    @Test
    fun `el rol otro tampoco puede editar un contacto fuera de su alcance`() {
        contactoExiste(1)
        every { tareaService.idsEmpresasDondeColabora(8) } returns emptySet()

        org.assertj.core.api.Assertions
            .assertThatThrownBy { service.actualizar(1, requestDeActualizacion(), otro) }
            .isInstanceOf(pe.quantum.crm.shared.exception.PermisoInsuficienteException::class.java)

        verify(exactly = 0) { contactoRepository.save(any()) }
    }

    /** Dentro de su alcance sigue pudiendo editar: R7 restringe, no prohibe. */
    @Test
    fun `un rol de apoyo puede editar un contacto de una empresa donde colabora`() {
        contactoExiste(1)
        colaboraEn(idEmpleado = 7, empresas = setOf(3L), contactos = listOf(1L))
        every { contactoRepository.save(any()) } answers { firstArg() }
        every { empresaContactoRepository.findByIdIdContacto(1) } returns emptyList()
        every { empresaService.resumenPorIds(emptyList()) } returns emptyMap()

        val dto = service.actualizar(1, requestDeActualizacion(), analista)

        assertThat(dto.tlf_1).isEqualTo("999888777")
        verify(exactly = 1) { contactoRepository.save(any()) }
    }

    /** R4: vendedor y supervisores no pierden nada. */
    @Test
    fun `un vendedor edita sin que se consulte la colaboracion`() {
        contactoExiste(1)
        every { contactoRepository.save(any()) } answers { firstArg() }
        every { empresaContactoRepository.findByIdIdContacto(1) } returns emptyList()
        every { empresaService.resumenPorIds(emptyList()) } returns emptyMap()

        service.actualizar(1, requestDeActualizacion(), vendedor)

        verify(exactly = 0) { tareaService.idsEmpresasDondeColabora(any()) }
    }

    /**
     * El 404 del inexistente gana al 403 del permiso: si el contacto no existe, la
     * respuesta es la misma para todos los roles y no revela nada.
     */
    @Test
    fun `editar un contacto inexistente sigue siendo 404 tambien para un rol de apoyo`() {
        every { contactoRepository.findById(99) } returns java.util.Optional.empty()

        org.assertj.core.api.Assertions
            .assertThatThrownBy { service.actualizar(99, requestDeActualizacion(), analista) }
            .isInstanceOf(pe.quantum.crm.shared.exception.NoEncontradoException::class.java)

        verify(exactly = 0) { tareaService.idsEmpresasDondeColabora(any()) }
    }
```

- [ ] **Paso 2: Ejecutar y verificar que falla**

```bash
./gradlew test --tests "pe.quantum.crm.domain.contactos.ContactoRolApoyoTest"
```

Esperado: **FAIL** con `Expecting code to raise a throwable` — hoy `actualizar` no lanza nada, guarda.

- [ ] **Paso 3: Implementar el guard**

En `src/main/kotlin/pe/quantum/crm/domain/contactos/ContactoServiceImpl.kt`:

**3a.** Agrega este import:

```kotlin
import pe.quantum.crm.shared.exception.PermisoInsuficienteException
```

**3b.** En el método `actualizar`, agrega **una sola línea** justo después de `val contacto = entidad(id)`:

```kotlin
        rechazarSiFueraDeAlcance(id, usuario)
```

El método queda así (para que puedas comparar):

```kotlin
    @Transactional
    override fun actualizar(
        id: Long,
        request: ActualizarContactoRequest,
        usuario: UsuarioActual,
    ): ContactoDto {
        val contacto = entidad(id)
        rechazarSiFueraDeAlcance(id, usuario)
        request.nombres?.let { contacto.nombres = it }
        request.apellidos?.let { contacto.apellidos = it }
        request.email_1?.let { contacto.email_1 = it }
        request.email_2?.let { contacto.email_2 = it }
        request.tlf_1?.let { contacto.tlf_1 = it }
        request.tlf_2?.let { contacto.tlf_2 = it }
        request.notas?.let { contacto.notas = it }
        contacto.updatedAt = LocalDateTime.now()
        contacto.updatedBy = usuario.id
        return contactoRepository.save(contacto).toDto()
    }
```

**3c.** Agrega este método privado justo **después** de `private fun idsContactosVisiblesPara(...)`:

```kotlin
    /**
     * Escritura de un rol de apoyo sobre un contacto que no alcanza: 403, no 404.
     *
     * Es una desviacion deliberada de CLAUDE.md regla 14, aprobada por producto
     * (R10 del requerimiento). La regla existe para no confirmar la existencia de
     * un recurso que el usuario no deberia poder enumerar — y aqui no aplica: en
     * contexto `vincular` este mismo usuario ve legitimamente ese contacto por
     * nombre, asi que su existencia no es secreta para el. Devolver 404 al editar
     * mentiria sobre algo que el sistema le acaba de mostrar. Mismo razonamiento
     * (y mismo status) que `EmpresaServiceImpl.rechazarSiEsApoyo`.
     */
    private fun rechazarSiFueraDeAlcance(
        idContacto: Long,
        usuario: UsuarioActual,
    ) {
        if (!usuario.esRolApoyo) {
            return
        }
        if (idContacto !in idsContactosVisiblesPara(usuario)) {
            throw PermisoInsuficienteException(
                "Tu rol es de apoyo: solo puedes editar contactos de las empresas donde colaboras",
            )
        }
    }
```

- [ ] **Paso 4: Ejecutar y verificar que pasan**

```bash
./gradlew test --tests "pe.quantum.crm.domain.contactos.ContactoRolApoyoTest" --tests "pe.quantum.crm.domain.contactos.ContactoServiceImplEscrituraTest"
```

Esperado: **PASS**.

- [ ] **Paso 5: Escribir el test WebMvc que falla**

Agrega este test al final de `src/test/kotlin/pe/quantum/crm/domain/contactos/ContactoControllerWebMvcTest.kt`:

```kotlin
    /** matriz_permisos.md §2.3: un rol de apoyo solo edita contactos donde colabora. */
    @Test
    fun `PUT contactos fuera del alcance de un rol de apoyo devuelve 403 PERMISO_INSUFICIENTE`() {
        every { contactoService.actualizar(5, any(), any()) } throws
            pe.quantum.crm.shared.exception.PermisoInsuficienteException(
                "Tu rol es de apoyo: solo puedes editar contactos de las empresas donde colaboras",
            )
        val token = jwtService.generateAccessToken(empleadoId = 7, rol = "analista")

        mockMvc.put("/api/v1/contactos/5") {
            header(HttpHeaders.AUTHORIZATION, "Bearer $token")
            contentType = MediaType.APPLICATION_JSON
            content = """{"tlf_1":"999888777"}"""
        }.andExpect {
            status { isForbidden() }
            jsonPath("$.error.code") { value("PERMISO_INSUFICIENTE") }
        }
    }
```

- [ ] **Paso 6: Ejecutar y verificar que pasa**

```bash
./gradlew test --tests "pe.quantum.crm.domain.contactos.ContactoControllerWebMvcTest"
```

Esperado: **PASS** (el controller no necesita cambios: `GlobalExceptionHandler` ya traduce `PermisoInsuficienteException` a 403).

Si falla, **DETENTE y reporta** — significa que el handler global no cubre esta excepción, lo cual sería una sorpresa que debe revisarse a mano.

- [ ] **Paso 7: Suite completa**

```bash
./gradlew test
```

Esperado: `BUILD SUCCESSFUL`.

- [ ] **Paso 8: Commit**

```bash
git add src/main/kotlin/pe/quantum/crm/domain/contactos/ src/test/kotlin/pe/quantum/crm/domain/contactos/
git commit -m "fix(contactos): PUT /contactos/:id exige alcance para los roles de apoyo

actualizar() no tenia ningun control de visibilidad: analista/otro podian editar
nombre, telefono y correo de cualquier contacto del CRM. Ahora fuera de su
alcance de colaboracion responde 403 (no 404: el contacto puede serles visible
por nombre en el buscador de vinculacion, mismo criterio que empresas).

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

## Tarea 6 — Actualizar `contrato_api.md` (§9 + §25)

> **Modelo: `sonnet` · Effort: `medium`.** Solo edición de Markdown; no toques código.
>
> **Obligatoria:** `contrato_api.md` §25 dice literalmente *"Sin entrada, el PR no se considera completo aunque el código y los tests pasen."*

**Files:**
- Modify: `docs/contrato_api.md`

- [ ] **Paso 1: Reemplazar la cabecera de `GET /contactos` (§9, líneas 745–750)**

Sustituye desde `### GET /contactos` hasta la línea de `**Query params:**` inclusive por:

```markdown
### GET /contactos
> Busca contactos. Usado para vincular un contacto existente a una empresa, y para la vista de listado de Contactos.

**Roles:** todos — **con filtro automático por rol** para `analista` y `otro` (ver nota de visibilidad abajo)

**Query params:** `q` (nombre o teléfono), `id_empresa` (contactos de una empresa específica), `contexto` (`listado` | `vincular`), `page`, `per_page`
```

- [ ] **Paso 2: Agregar la nota de visibilidad después del bloque JSON de respuesta de `GET /contactos`**

Inserta este bloque **justo antes** del `---` que cierra la sección de `GET /contactos` (después del bloque ```` ```json ```` de la respuesta 200):

```markdown
**Visibilidad por rol (`analista` y `otro`):** estos roles no tienen cartera propia. Lo que devuelve el endpoint depende de `contexto`:

| `contexto` | Qué contactos devuelve | Qué campos devuelve |
|---|---|---|
| `listado` (default) | Solo los vinculados a empresas donde el usuario colabora vía tarea (`ids_colaboradores`). Un contacto sin ninguna empresa vinculada nunca aparece. | Todos, igual que el resto de roles. |
| `vincular` | Todos los del CRM, incluidos los que no tienen empresa. | **Solo `id`, `nombres` y `apellidos`.** `email_*`, `tlf_*`, `notas`, `empresas` y `oportunidades_count` vienen vacíos/nulos. |

**Notas sobre `contexto`:**
- **Es el parámetro que distingue las dos pantallas que comparten este endpoint:** la vista de listado de Contactos (`listado`) y el buscador de "vincular contacto existente" dentro de una empresa (`vincular`). Sin él, el backend no puede aplicar la regla correcta, porque son opuestas para el mismo rol.
- **Si no se envía, se asume `listado`** — el modo restrictivo. Un cliente que todavía no adoptó el parámetro nunca abre la búsqueda global por omisión.
- Un valor fuera de `listado`/`vincular` devuelve `400 VALIDACION` con `field: "contexto"`. No se ignora silenciosamente.
- **En `contexto=vincular`, para `analista`/`otro`, `q` busca solo por nombre y apellidos — no por teléfono.** Ocultar el teléfono en la respuesta no bastaría: un `LIKE` sobre el número convertiría el endpoint en un oráculo (escribo un teléfono, me devuelve de quién es). Para el resto de roles `q` sigue buscando por nombre y por los dos teléfonos, como siempre.
- Para `admin`, `gerencia`, `jdv` y `vendedor` el parámetro no cambia nada: ven todo, con todos los campos, en cualquier contexto.
```

- [ ] **Paso 3: Reemplazar la cabecera de `GET /contactos/:id` (§9, líneas 774–777)**

Sustituye desde `### GET /contactos/:id` hasta `**Roles:** todos` inclusive por:

```markdown
### GET /contactos/:id
> Detalle completo del contacto: empresas vinculadas, oportunidades vinculadas y su línea de tiempo de actividades.

**Roles:** todos — **con filtro automático por rol** para `analista` y `otro`

**Query params:** `contexto` (`listado` | `vincular`) — misma semántica que en `GET /contactos`
```

- [ ] **Paso 4: Reemplazar el bloque `**Notas:**` de `GET /contactos/:id` (líneas 803–808)**

Sustituye el bloque completo de notas por:

```markdown
**Notas:**
- `actividades[]` incluye solo tareas por ahora. `eventos` no tiene columna `id_contacto` en el schema actual y no existe una entidad de notas — se agregarán cuando el schema lo soporte.
- `oportunidades[].modelo.codigo` usa el mismo campo que el resto del contrato (§10), no `nombre`.
- `actividades[].titulo` es el valor de `tipo_accion` (`llamada`, `correo`, `reunion`, `whatsapp`, `otro`) — `Tarea` no tiene un campo de título libre.
- `actividades[]` respeta la visibilidad de tareas: vendedor/analista solo ven las tareas asignadas a sí mismos.
- **Visibilidad para `analista`/`otro`:** en `contexto=listado` (default), un contacto que no esté vinculado a ninguna empresa donde el usuario colabora devuelve `404 NO_ENCONTRADO` — indistinguible de un contacto inexistente, a propósito. En `contexto=vincular` el detalle sí se devuelve para cualquier contacto, pero recortado: solo `id`, `nombres` y `apellidos`; `empresas`, `oportunidades` y `actividades` vienen vacíos.
- Errores: `404 NO_ENCONTRADO` si el contacto no existe o está fuera del alcance del rol. `400 VALIDACION` si `contexto` trae un valor desconocido.
```

- [ ] **Paso 5: Reemplazar la sección de `PUT /contactos/:id` (líneas 841–848)**

Sustituye la sección completa por:

```markdown
### PUT /contactos/:id
> Actualiza datos propios del contacto (no los de su vinculación a empresa).

**Roles:** todos — `analista` y `otro` solo sobre contactos vinculados a empresas donde colaboran

**Body:** `nombres`, `apellidos`, `email_1`, `email_2`, `tlf_1`, `tlf_2`, `notas` — todos opcionales.

**Respuesta 200:** el contacto actualizado.

**Notas:**
- **`analista`/`otro` sobre un contacto fuera de su alcance:** `403 PERMISO_INSUFICIENTE`, no 404. Es una excepción deliberada al criterio IDOR del resto del contrato (§4): en `contexto=vincular` estos roles pueden ver ese mismo contacto por nombre, así que esconderlo al editar mentiría sobre algo que el sistema ya les mostró. El mensaje del error se puede mostrar tal cual al usuario.
- Un contacto inexistente devuelve `404 NO_ENCONTRADO` para todos los roles, incluidos los de apoyo: el 404 se evalúa antes que el permiso.
```

- [ ] **Paso 6: Corregir la deriva de `DELETE /contactos/:id` (línea ~855)**

Reemplaza:

```markdown
**Roles:** `admin` `gerente` `jdv`
```

por:

```markdown
**Roles:** `admin` `gerencia` `jdv`
```

> Contexto: el rol se llama `gerencia` desde V25 y el código ya lo dice bien (`@PreAuthorize("hasAnyRole('admin', 'gerencia', 'jdv')")`). Era deriva de documentación pura, detectada durante el triage.

- [ ] **Paso 7: Agregar la entrada al changelog §25**

Agrega esta fila **al final** de la tabla de §25 (después de la fila de `2026-08-19 | GET /metas-venta`):

```markdown
| 2026-08-20 | `GET /contactos`, `GET /contactos/:id`, `PUT /contactos/:id` | **Breaking** | Cierre de la última fuga de visibilidad del cambio de roles de apoyo del 2026-08-18: el módulo `contactos` no se había tocado y `analista`/`otro` listaban, abrían y **editaban** nombre, teléfono y correo de todos los contactos del CRM. Ahora: (1) `GET /contactos` y `GET /contactos/:id` solo devuelven, para esos roles, los contactos vinculados a empresas donde colaboran vía tarea — el contacto sin empresa (huérfano) queda fuera; el que queda fuera de alcance en el detalle responde `404 NO_ENCONTRADO`. (2) Se agrega el query param `contexto` (`listado` \| `vincular`) a ambos GET: `vincular` levanta el filtro para que el buscador de "vincular contacto existente" siga alcanzando todo el CRM, pero recorta la respuesta a `id`/`nombres`/`apellidos` y hace que `q` busque solo por nombre, no por teléfono. Ausente ⇒ `listado`; valor desconocido ⇒ `400 VALIDACION`. (3) `PUT /contactos/:id` responde `403 PERMISO_INSUFICIENTE` para `analista`/`otro` sobre un contacto fuera de su alcance. El resto de roles no cambia en nada. Ver `matriz_permisos.md §1` y `§2.3`. | **Enviar `contexto=vincular` en el buscador de vincular contacto** — sin él ese buscador deja de encontrar contactos fuera del alcance del usuario de apoyo y el flujo se rompe para esos roles. La vista de listado no necesita cambios (el default ya es el correcto). Para `analista`/`otro` el cliente debe tolerar filas con `tlf_*`/`email_*` nulos y `empresas`/`oportunidades_count` vacíos en modo `vincular`, y un `403` con mensaje mostrable al editar. La mitigación de UI que ocultaba la sección Contactos para estos roles ya puede retirarse: el control ahora está en el backend. |
```

- [ ] **Paso 8: Verificar que no se rompió nada**

```bash
./gradlew test
```

Esperado: `BUILD SUCCESSFUL` (solo se tocó Markdown; es una comprobación de que no editaste código por error).

- [ ] **Paso 9: Commit**

```bash
git add docs/contrato_api.md
git commit -m "docs(contrato): documentar el filtro de visibilidad de contactos y el param contexto

Actualiza §9 (los tres endpoints afectados) y agrega la entrada obligatoria al
changelog §25. De paso corrige la deriva de DELETE /contactos/:id, que seguia
diciendo 'gerente' en vez de 'gerencia' (renombrado en V25).

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

## Tarea 7 — Actualizar `matriz_permisos.md`

> **Modelo: `sonnet` · Effort: `medium`.** Solo edición de Markdown.

**Files:**
- Modify: `docs/matriz_permisos.md`

- [ ] **Paso 1: Actualizar la fila Contactos de §1 (línea 30)**

Reemplaza:

```markdown
| **Contactos** | Todos | Todos | Todos | Todos (búsqueda global para vincular) | Todos (sin cambios — este plan no tocó el módulo contactos; ver nota) | Igual que analista |
```

por:

```markdown
| **Contactos** | Todos | Todos | Todos | Todos (búsqueda global para vincular) | **Solo los vinculados a empresas donde colabora vía tarea** (`empresa_contactos.id_empresa ∈ idsEmpresasDondeColabora`). El contacto sin empresa (huérfano) no lo alcanza. Excepción: con `?contexto=vincular` alcanza todo el CRM pero solo ve el nombre — ver §2.3 | Igual que analista |
```

- [ ] **Paso 2: Reemplazar el primer bullet de la nota del 2026-08-18 (línea 39)**

Reemplaza el bullet que empieza con `- **Contactos:** el permiso de vinculación...` por:

```markdown
- **Contactos:** ~~el módulo no se tocó y `analista`/`otro` listaban, abrían y editaban todos los contactos del CRM~~ — **corregido 2026-08-20**: `GET /contactos`, `GET /contactos/:id` y `PUT /contactos/:id` ya aplican el filtro de colaboración; ver §2.3. El permiso de **vinculación** a empresas sigue siendo el heredado vía `EmpresaService.vinculoVisible` (igual que Eventos, §2.5) y no cambió: un rol de apoyo puede vincular/desvincular contactos en las empresas donde colabora. La vinculación a **oportunidades** sigue bloqueada con 403 (`rechazarSiEsApoyo` en `OportunidadServiceImpl`) — la asimetría de §2.3 sigue vigente y sin decidir.
```

- [ ] **Paso 3: Reemplazar el párrafo de cierre de la nota (línea 44)**

Reemplaza:

```markdown
Las fugas de **Solicitudes** y **Metas de venta** ya se corrigieron. Los puntos restantes (Prospección, Inicio, Contactos) quedan fuera de este cambio — candidatos a un ticket aparte vía `redactar-requerimiento`.
```

por:

```markdown
Las fugas de **Solicitudes**, **Metas de venta** y **Contactos** ya se corrigieron. El punto restante es **Prospección e Inicio**, que siguen filtrando por `id_vendedor = usuario.id` en vez de por colaboración: en la práctica devuelven vacío para un rol de apoyo (fallan cerrado), así que no son una fuga, pero tampoco reflejan el modelo de visibilidad vigente — candidato a un ticket aparte vía `redactar-requerimiento`.
```

- [ ] **Paso 4: Reemplazar la nota de cabecera de §2.3 (línea 86)**

Reemplaza el bloque `> **Sin guard propio en este módulo (2026-08-18):** ...` completo por:

```markdown
> **Guard propio desde 2026-08-20.** El módulo dejó de heredar toda su visibilidad de otros: `ContactoServiceImpl` resuelve por su cuenta qué contactos alcanza un rol de apoyo (los vinculados a empresas donde colabora vía tarea, consultando `TareaService.idsEmpresasDondeColabora`) y lo aplica dentro de la query, en la búsqueda, el detalle y la edición. Las operaciones de **vinculación** siguen resolviendo visibilidad vía `EmpresaService.vinculoVisible`/`OportunidadService.vinculoVisible`, que significan cosas distintas para empresas y para oportunidades — ver cada fila y la nota final.
```

- [ ] **Paso 5: Reemplazar las tres filas afectadas de la tabla de §2.3**

Reemplaza:

```markdown
| Buscar contactos | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
```

por:

```markdown
| Buscar contactos (`GET /contactos`) | ✓ Todos | ✓ Todos | ✓ Todos | ✓ Todos | ✓ Solo donde colabora; con `?contexto=vincular` busca en todo el CRM pero solo recibe el nombre | Igual que analista |
| Ver detalle de contacto (`GET /contactos/:id`) | ✓ Cualquiera | ✓ Cualquiera | ✓ Cualquiera | ✓ Cualquiera | ✓ Solo donde colabora (fuera de alcance → **404**); con `?contexto=vincular` cualquiera, recortado al nombre | Igual que analista |
```

Y reemplaza:

```markdown
| Editar contacto | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
```

por:

```markdown
| Editar contacto (`PUT /contactos/:id`) | ✓ Cualquiera | ✓ Cualquiera | ✓ Cualquiera | ✓ Cualquiera | ✓ Solo donde colabora (fuera de alcance → **403**, no 404) | Igual que analista |
```

- [ ] **Paso 6: Reemplazar la nota final de §2.3 (línea 101)**

Reemplaza el párrafo `**Nota:** la asimetría es real...` completo por:

```markdown
**Nota sobre el 403 de editar (2026-08-20):** `PUT /contactos/:id` responde **403** y no 404 sobre un contacto fuera de alcance, al revés que el `GET`. Es deliberado y aprobado por producto: en `?contexto=vincular` ese mismo usuario ve legítimamente el contacto por su nombre, así que su existencia no es secreta para él y esconderlo al editar mentiría sobre algo que el sistema ya le mostró. Es el mismo razonamiento que `EmpresaServiceImpl.rechazarSiEsApoyo` documenta para empresas. El criterio IDOR general (recurso ajeno → 404) sigue vigente en todo lo demás.

**Nota sobre la asimetría de vinculación:** sigue siendo real y sin decidir — `EmpresaServiceImpl.vinculoVisible` no tiene guard de escritura (solo resuelve visibilidad), mientras que `OportunidadServiceImpl` sí bloquea con `rechazarSiEsApoyo` en sus métodos de vinculación de contacto. Un `analista` puede vincular/desvincular contactos de una empresa donde colabora, pero no de una oportunidad donde colabora. El ticket de Contactos del 2026-08-20 cubrió la visibilidad de lectura y la edición del contacto, **no** esta asimetría de vinculación: sigue abierta.
```

- [ ] **Paso 7: Verificar**

```bash
./gradlew test
```

Esperado: `BUILD SUCCESSFUL`.

- [ ] **Paso 8: Commit**

```bash
git add docs/matriz_permisos.md
git commit -m "docs(permisos): actualizar la matriz tras el filtro de visibilidad de contactos

Contactos deja de ser 'Todos' para los roles de apoyo en §1 y §2.3, con el 404
del detalle y el 403 de la edicion documentados. Cierra la deuda que la nota del
2026-08-18 dejaba abierta; Prospeccion e Inicio siguen pendientes.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

## Tarea 8 — Revisión final contra los documentos, gates completos y PR

> **Modelo: `opus` · Effort: `high`.** Esta tarea **sí requiere juicio**: es la relectura crítica que exige `CLAUDE.md`, y su valor está en detectar lo que las tareas anteriores pisaron sin darse cuenta. **No la delegues a un modelo ligero.**

**Files:** ninguno de código; posibles correcciones puntuales en `docs/`.

- [ ] **Paso 1: Ejecutar el gate completo**

```bash
./gradlew ktlintFormat
```

Luego:

```bash
./gradlew ktlintCheck detekt test koverVerify
```

Esperado: `BUILD SUCCESSFUL` en los cuatro.

**Si `detekt` falla por `LongParameterList` en `ContactoServiceImpl`:** confirma que el `@Suppress("TooManyFunctions", "LongParameterList")` de la Tarea 2 paso 4b está puesto en la clase. No agregues supresiones nuevas en otros sitios sin reportarlo.

**Si `koverVerify` falla por cobertura de servicios de dominio (<90%):** los métodos nuevos que puedan quedar sin cubrir son `toListaReducidoDto`, `toDetalleReducido`, `restriccionPorIds` y `rechazarSiFueraDeAlcance`. Todos tienen tests en `ContactoRolApoyoTest`; si aun así falta, reporta el informe de Kover antes de escribir tests de relleno.

- [ ] **Paso 2: Leer el diff completo de la rama**

```bash
git diff main...HEAD
```

Léelo entero. No lo hojees.

- [ ] **Paso 3: Re-verificar el diff contra los documentos citados en la fase de investigación**

Esto es lo que pide `CLAUDE.md`: *"una tarea dedicada a re-leer el diff completo de la rama contra esos mismos documentos citados al principio, no solo contra lo que el plan pedía implementar. Busca específicamente contradicciones con algo que **ya estaba escrito correctamente** antes de empezar."*

Abre cada documento y compáralo contra el diff, uno por uno:

- [ ] **`docs/contrato_api.md`** — ¿queda alguna afirmación **fuera de §9** que este diff contradiga? Busca menciones de contactos en otras secciones (`grep -n "contacto" docs/contrato_api.md`). En particular: §4 (criterio de errores/IDOR) — el 403 del `PUT` es una excepción documentada; verifica que §4 no afirme algo absoluto que ahora sea falso sin matizar.
- [ ] **`docs/matriz_permisos.md`** — ¿queda alguna fila o nota que siga diciendo que `analista`/`otro` ven "Todos" los contactos? (`grep -n "Contactos" docs/matriz_permisos.md`). ¿La nota de §2.5 sobre Eventos sigue siendo cierta?
- [ ] **`docs/reglas_negocio.md`** — `grep -n -i "contacto" docs/reglas_negocio.md`. §11.1/§11.2 no debieron cambiar. Si alguna regla de negocio de contactos quedó contradicha, es un hallazgo: **repórtalo, no lo edites en silencio.**
- [ ] **`CLAUDE.md` reglas 1–14** — recorre la tabla de la fase de investigación de este plan y confirma cada fila contra el diff real. La regla 14 (IDOR) es la que tiene la excepción; verifica que esté documentada en los **tres** sitios: KDoc de `rechazarSiFueraDeAlcance`, `contrato_api.md` §9 y `matriz_permisos.md` §2.3.
- [ ] **`docs/TESTING-backend.md`** — ¿todos los tests nuevos siguen §9 (nombres descriptivos en backticks, Arrange-Act-Assert)? ¿Hay algún test que nunca haya estado en rojo?

- [ ] **Paso 4: Verificar que no quedó ningún call-site sin migrar**

```bash
grep -rn "contactoService.detalle\|contactoService.buscar\|service.detalle(\|service.buscar(" src/ --include=*.kt
```

Cada resultado debe usar la firma nueva. Cualquiera con la vieja no habría compilado, pero esto confirma que no quedó un `@Suppress` o un test ignorado tapándolo.

- [ ] **Paso 5: Verificar que el filtro no se puede saltar por otra puerta**

```bash
grep -rn "contactoRepository\." src/main/kotlin --include=*.kt
```

Esperado: **solo** dentro de `ContactoServiceImpl.kt`. Si otro archivo de producción usa `ContactoRepository` directamente, ese es un camino que se salta todo el filtro nuevo. **Repórtalo.**

- [ ] **Paso 6: Reportar hallazgos ANTES de abrir el PR**

Escribe un resumen con:
1. Resultado de cada gate del paso 1.
2. Cualquier contradicción encontrada en el paso 3, con archivo y línea.
3. Cualquier resultado inesperado de los pasos 4 y 5.

**Si encontraste una contradicción con documentación ya vigente, arréglala en esta rama y en un commit propio** (`docs(...): corregir ...`), y déjala anotada en el cuerpo del PR.

- [ ] **Paso 7: Empujar la rama**

```bash
git push -u origin fix/visibilidad-contactos-roles-apoyo
```

- [ ] **Paso 8: Abrir el PR**

```bash
gh pr create --base main --title "fix(contactos): filtro de visibilidad por rol en contactos para analista y otro" --body "$(cat <<'EOF'
## Qué

Cierra la última fuga de visibilidad del cambio de roles de apoyo del 2026-08-18. El módulo `contactos` nunca se tocó en el PR #9, así que `analista` y `otro` — que no tienen cartera propia — podían **listar, abrir y editar** nombre, teléfono y correo de todos los contactos del CRM, sin relación con ninguna empresa donde colaboren.

Es la misma clase de bug ya corregido el 2026-08-19 en `GET /solicitudes` y `GET /metas-venta`. `matriz_permisos.md` ya lo tenía anotado como deuda conocida desde el 2026-08-18.

## Cambios

- **`GET /contactos`** — para `analista`/`otro`, restringe a los contactos vinculados a empresas donde colaboran vía tarea. Filtro en la query (`Specification`), nunca en memoria. El contacto huérfano (sin empresa) queda fuera.
- **`GET /contactos/:id`** — mismo filtro; fuera de alcance responde `404`, indistinguible de inexistente (IDOR, CLAUDE.md regla 14).
- **`PUT /contactos/:id`** — no tenía **ningún** control de visibilidad. Ahora fuera de alcance responde `403` (decisión de producto: el contacto puede serles visible por nombre en el buscador de vinculación, así que un 404 mentiría; mismo criterio que `EmpresaServiceImpl.rechazarSiEsApoyo`).
- **Nuevo query param `contexto`** (`listado` | `vincular`) en los dos `GET`. El mismo endpoint sirve la vista de listado y el buscador de "vincular contacto existente", con reglas opuestas para el mismo rol. `vincular` levanta el filtro (el buscador necesita alcanzar todo el CRM) pero recorta la respuesta a `id`/`nombres`/`apellidos`. Ausente ⇒ `listado`, el modo restrictivo. Valor desconocido ⇒ `400 VALIDACION`.
- **En modo reducido, `q` busca solo por nombre, no por teléfono.** Ocultar el número en la respuesta no cerraba el canal: un `LIKE` sobre `tlf_1` seguía funcionando como oráculo. Era el riesgo concreto que planteó el reporte de frontend.
- `admin`, `gerencia`, `jdv` y `vendedor` no cambian en nada.

## Documentación

- `contrato_api.md` §9 (los tres endpoints) y §25 (entrada de changelog, **Breaking**).
- `matriz_permisos.md` §1 y §2.3.
- Corregida de paso la deriva de `DELETE /contactos/:id`, que documentaba el rol como `gerente` en vez de `gerencia` (renombrado en V25).

## Acción requerida del frontend

**Enviar `contexto=vincular` en el buscador de vincular contacto existente.** Sin ese parámetro, el buscador deja de encontrar contactos fuera del alcance del usuario de apoyo y ese flujo se rompe para `analista`/`otro`. La vista de listado no necesita cambios. La mitigación de UI que ocultaba la sección Contactos ya puede retirarse: el control está en el backend.

## Verificación

- `./gradlew ktlintCheck detekt test koverVerify` en verde.
- TDD: cada cambio de comportamiento tiene su test en rojo antes del código.
- ArchUnit verde: `contactos` consume `TareaService` por su interfaz pública, nunca sus entidades ni su repositorio (CLAUDE.md regla 12).
- Sin migración de base de datos.

Requerimiento: `docs/requerimientos/2026-08-20-visibilidad-contactos-analista-otro.json`
Plan: `docs/superpowers/plans/2026-08-20-visibilidad-contactos-roles-apoyo.md`

🤖 Generated with [Claude Code](https://claude.com/claude-code)
EOF
)"
```

- [ ] **Paso 9: Confirmar que el CI arranca**

```bash
gh pr checks --watch
```

Esperado: todos los checks en verde. Si alguno falla, reporta el log — **no** hagas `--force` ni saltes hooks.

---

## Auto-revisión del plan

**1. Cobertura del spec.** Cada requisito del ticket tiene su tarea:

| Req | Tarea | Verificado por |
|---|---|---|
| R1 (filtro en `GET /contactos`) | 2 | `ContactoRolApoyoTest`, `ContactoBusquedaSpecificationTest` |
| R2 (404 en `GET /contactos/:id`) | 4 | `ContactoRolApoyoTest`, `ContactoControllerWebMvcTest` |
| R3 (huérfanos ocultos) | 2 | Consecuencia del mecanismo: el huérfano no tiene fila en `empresa_contactos`. Cubierto por `un rol de apoyo sin colaboraciones no cae en el listado completo` |
| R4 (otros roles sin cambios) | 2, 3, 4, 5 | Un test por rol y por endpoint en `ContactoRolApoyoTest` |
| R5 (modo vincular, solo nombre) | 3 | `ContactoRolApoyoTest`, `ContactoControllerWebMvcTest` |
| R6 (parámetro de contexto) | 1, 3, 4 | `ContextoBusquedaContactoTest`, tests WebMvc |
| R7 (`PUT` sujeto al filtro) | 5 | `ContactoRolApoyoTest` |
| R8 (contexto en el detalle) | 4 | `ContactoRolApoyoTest`, `ContactoControllerWebMvcTest` |
| R9 (default restrictivo) | 1, 3 | `contexto ausente... cae en listado`, `GET contactos sin contexto usa el modo listado` |
| R10 (`PUT` → 403) | 5 | `ContactoRolApoyoTest`, `ContactoControllerWebMvcTest` |

**2. Placeholders.** Ninguna tarea dice "TBD", "similar a la tarea N", "agregar manejo de errores" ni "escribe tests para lo anterior". Todo el código está transcrito.

**3. Consistencia de tipos.** Verificado a mano: `ContextoBusquedaContacto` se declara en la Tarea 1 con los métodos `esReducidoPara`/`aplicaFiltroDeVisibilidadPara`/`desde`, y esos son exactamente los nombres usados en las Tareas 2–5. `idsContactosVisiblesPara(usuario: UsuarioActual): Set<Long>` se define en la Tarea 2 y se consume con esa firma en las Tareas 4 y 5. `restriccionPorIds(ids, root, cb)` se define y consume en la Tarea 2. El constructor de 4 parámetros de la Tarea 2 es el que usan los tests de las Tareas 3–5.

**4. Riesgo residual conocido, fuera del alcance de este plan.** La asimetría de vinculación de contactos (`analista` puede vincular a una empresa donde colabora, pero no a una oportunidad) sigue abierta y sin decidir — se documenta en `matriz_permisos.md` §2.3 (Tarea 7 paso 6) pero **no se resuelve acá**. Es candidata a su propio ticket.
