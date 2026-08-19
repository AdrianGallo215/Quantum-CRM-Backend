# Roles de apoyo (analista, otro) — solo lectura en oportunidades y empresas

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `analista` y `otro` pasan a ser roles de apoyo sin cartera propia: no crean ni editan empresas ni oportunidades, no confirman `facturado`, no aplican descuentos ni crean solicitudes, y solo ven las entidades donde son colaboradores de alguna tarea.

**Architecture:** Se agrega el predicado `esRolApoyo` a `UsuarioActual` como única fuente de verdad del rol. Los servicios de `oportunidades` y `empresas` lo consultan para (a) rechazar toda escritura con un 403 de mensaje específico y (b) sustituir el filtro de visibilidad `idVendedor = yo` por un filtro de pertenencia a un conjunto de ids que provee el módulo `tareas` a través de su interfaz pública. `solicitudes` rechaza la creación por el mismo predicado.

**Tech Stack:** Kotlin 1.9 · Spring Boot 3.2 · Spring Data JPA (Specifications + JPQL) · JUnit 5 · MockK · AssertJ

**Spec:** `docs/requerimientos/2026-08-18-permisos-analista-otro.json` (R1–R8) + triage del 2026-08-18 en la conversación de origen.

## Global Constraints

- TDD obligatorio: test que falla ANTES del código. Ninguna tarea cierra sin `./gradlew test` en verde.
- Un módulo NUNCA accede a entidades ni repositorios de otro módulo. `oportunidades` y `empresas` obtienen los ids de colaboración **solo** vía la interfaz `TareaService`. ArchUnit (`ArquitecturaModulosTest`) lo verifica en CI.
- IDOR: entidad no visible → **404** (`NoEncontradoException`). Entidad visible pero sin permiso de escritura → **403** (`PermisoInsuficienteException`). Esta distinción es el corazón del cambio: no confundirlas.
- Inyección por constructor con `private val`. Nunca `@Autowired` en campo.
- `@Transactional(readOnly = true)` en lecturas.
- Los filtros de visibilidad se resuelven **en la query**, nunca en memoria.
- Rama: `feature/roles-apoyo-solo-lectura`. Nunca commit directo a `main`.
- No hay migración de schema en este plan. Si aparece la necesidad de una, DETENERSE y consultar.
- Formato: `./gradlew ktlintFormat` antes de cada commit.

---

## Task 0: Verificar datos de producción — ✅ COMPLETADA 2026-08-18

**Resultado: 0 empresas y 0 oportunidades** con `id_vendedor` apuntando a un empleado de rol `analista` u `otro`. No se requiere remediación de datos y el plan procede tal cual (Tasks 1-10).

Consecuencia registrada en el ticket: la ambigüedad entre "mantienen visibilidad actual" y "sin cartera propia" queda sin efecto práctico — ningún usuario pierde acceso a registros que vea hoy. La rama `esRolApoyo` de las Tasks 5 y 7 sigue siendo un **reemplazo** de la regla de visibilidad, no un agregado: sin ella estos roles verían cero, ni siquiera sus colaboraciones.

<details>
<summary>Consulta original (histórico)</summary>

**Esta tarea NO la ejecuta un subagente.** Es una consulta de solo lectura contra la base de producción que debe autorizar y correr el dueño del repo.

**Por qué existe:** el plan asume que `analista`/`otro` no deben tener cartera. Si en producción ya existen `empresas` u `oportunidades` con `id_vendedor` apuntando a un empleado de esos roles (posible vía auto-asignación al crear, `EmpresaServiceImpl.vendedorAlCrear`), el cambio de visibilidad de la Task 5/7 hará que esos registros dejen de ser visibles para ese usuario. Los supervisores los seguirán viendo, así que no se pierde nada — pero hay que saberlo antes, no descubrirlo en producción.

- [ ] **Paso 1: Correr la consulta de diagnóstico (solo lectura)**

```sql
SELECT e.rol, COUNT(DISTINCT em.id) AS empresas, COUNT(DISTINCT o.id) AS oportunidades
FROM empleados e
LEFT JOIN empresas em       ON em.id_vendedor = e.id
LEFT JOIN oportunidades o   ON o.id_vendedor  = e.id
WHERE e.rol IN ('analista', 'otro')
GROUP BY e.rol;
```

- [ ] **Paso 2: Decidir según el resultado**

| Resultado | Qué hacer |
|---|---|
| 0 empresas y 0 oportunidades | Nada. El plan procede tal cual. |
| Hay registros | DETENERSE y decidir con producto a qué vendedor se reasignan. Eso es un ticket aparte con el flujo de datos de `DEVOPS-backend.md §7` y §9.2 (restaurar dump en local + backup manual). No mezclar con este PR. |

- [ ] **Paso 3: Registrar el resultado en el ticket**

Anotar el conteo obtenido en `docs/requerimientos/2026-08-18-permisos-analista-otro.json` bajo una clave nueva `verificacion_datos_prod` con la fecha.

</details>

---

## Task 1: Predicado de rol de apoyo en `UsuarioActual`

**Files:**
- Modify: `src/main/kotlin/pe/quantum/crm/shared/security/UsuarioActual.kt`
- Modify: `src/test/kotlin/pe/quantum/crm/shared/security/UsuarioActualTest.kt` (YA EXISTE — agregar una clase de tests nueva al archivo, no sobreescribirlo)

**Ruling de preflight:** el archivo ya existe con 4 tests sobre `esSupervisor`/`puedeAprobar`/el `gerente` viejo. Ninguno de ellos asume que `analista.puedeValidarFacturado` sea `true`, así que no hay conflicto que corregir — agregar los tests nuevos del Step 1 al final del `class UsuarioActualTest { ... }` existente, respetando su estilo (nombres de test entre backticks, `assertThat`).

**Interfaces:**
- Produces: `UsuarioActual.esRolApoyo: Boolean` — `true` para `analista` y `otro`. Lo consumen las Tasks 2, 4, 5, 6, 7, 8.
- Produces: `UsuarioActual.puedeValidarFacturado: Boolean` — ahora solo `admin` y `gerencia`.

- [ ] **Step 1: Escribir el test que falla**

Crear `src/test/kotlin/pe/quantum/crm/shared/security/UsuarioActualTest.kt`:

```kotlin
package pe.quantum.crm.shared.security

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class UsuarioActualTest {
    @Test
    fun `analista y otro son roles de apoyo`() {
        assertThat(UsuarioActual(1L, "analista").esRolApoyo).isTrue()
        assertThat(UsuarioActual(2L, "otro").esRolApoyo).isTrue()
    }

    @Test
    fun `los roles comerciales y supervisores no son de apoyo`() {
        assertThat(UsuarioActual(3L, "vendedor").esRolApoyo).isFalse()
        assertThat(UsuarioActual(4L, "jdv").esRolApoyo).isFalse()
        assertThat(UsuarioActual(5L, "gerencia").esRolApoyo).isFalse()
        assertThat(UsuarioActual(6L, "admin").esRolApoyo).isFalse()
    }

    @Test
    fun `el analista ya no puede validar el paso a facturado`() {
        assertThat(UsuarioActual(1L, "analista").puedeValidarFacturado).isFalse()
        assertThat(UsuarioActual(2L, "otro").puedeValidarFacturado).isFalse()
    }

    @Test
    fun `admin y gerencia siguen validando el paso a facturado`() {
        assertThat(UsuarioActual(5L, "gerencia").puedeValidarFacturado).isTrue()
        assertThat(UsuarioActual(6L, "admin").puedeValidarFacturado).isTrue()
    }
}
```

- [ ] **Step 2: Correr el test y verificar que falla**

Run: `./gradlew test --tests "pe.quantum.crm.shared.security.UsuarioActualTest"`
Expected: FAIL — `esRolApoyo` no existe (error de compilación) y `puedeValidarFacturado` devuelve `true` para analista.

- [ ] **Step 3: Implementar**

En `UsuarioActual.kt`, reemplazar el bloque de `puedeValidarFacturado` (líneas 20-22) por:

```kotlin
    /** Roles que pueden confirmar el paso a `facturado` (matriz_permisos.md). */
    val puedeValidarFacturado: Boolean
        get() = rol == "admin" || rol == "gerencia"

    /**
     * Roles de apoyo: sin cartera propia, solo lectura sobre empresas y
     * oportunidades. Solo ven aquello en lo que colaboran via una tarea
     * (matriz_permisos.md). Unica fuente de verdad de esta condicion: el resto
     * de modulos consulta este predicado, nunca compara el string del rol.
     */
    val esRolApoyo: Boolean
        get() = rol == "analista" || rol == "otro"
```

- [ ] **Step 4: Correr el test y verificar que pasa**

Run: `./gradlew test --tests "pe.quantum.crm.shared.security.UsuarioActualTest"`
Expected: PASS

- [ ] **Step 5: Correr la suite completa**

Run: `./gradlew test`
Expected: Van a fallar tests existentes que asumen que `analista` valida `facturado`. Anotar cuáles. **NO arreglarlos todavía**: se arreglan en la Task 4, que es donde cambia ese comportamiento. Si fallan tests fuera de `oportunidades`, reportarlo antes de seguir.

- [ ] **Step 6: Commit**

```bash
./gradlew ktlintFormat
git add src/main/kotlin/pe/quantum/crm/shared/security/UsuarioActual.kt src/test/kotlin/pe/quantum/crm/shared/security/UsuarioActualTest.kt
git commit -m "feat(security): agrega esRolApoyo y retira analista de puedeValidarFacturado"
```

---

## Task 2: Los roles de apoyo no aplican descuento

**Files:**
- Modify: `src/main/kotlin/pe/quantum/crm/shared/PoliticaDescuento.kt`
- Modify: `src/test/kotlin/pe/quantum/crm/shared/PoliticaDescuentoTest.kt` (YA EXISTE — ver ruling de preflight abajo, no es un archivo nuevo)

**Ruling de preflight:** el archivo ya existe y tiene assertions que codifican el comportamiento VIEJO de `analista`/`otro` (limite de 3%). Este cambio de comportamiento es exactamente lo que Task 2 implementa, así que estas assertions no son casos aparte a preservar — son el mismo comportamiento que se está reemplazando, y hay que actualizarlas en el mismo Step 3, no dejarlas rotas. Las 6 líneas exactas a corregir, con su valor nuevo:

| Línea aprox. | Assertion vieja | Corrección |
|---|---|---|
| 12 | `limitePara("analista")` == `BigDecimal(3)` | == `BigDecimal.ZERO` |
| 32 | `limitePara("otro")` == `BigDecimal(3)` | **Eliminar esta línea.** Ya no aplica: `otro` deja de caer en "rol desconocido → límite más bajo" porque ahora tiene su propia regla explícita (`ROLES_SIN_DESCUENTO`). El resto del test (`"gerente"`, `""`, `"rol_que_no_existe"`) sigue intacto — esos SÍ siguen cayendo en el límite más bajo (3%), que es el comportamiento fail-closed que el comentario del código describe y que este plan no toca. |
| 40 | `excedeLimite("otro", BigDecimal("90"))` es `true` | Sigue siendo `true` (0 < 90 igual excede) — sin cambio, pero ahora por la regla nueva, no la de fallback |
| 44 | `excedeLimite("otro", BigDecimal("3.00"))` es `false` | → `true` (con límite 0, hasta 3.00 excede) |
| 50-51 | `aprobadorPara("otro", "90")` y `("otro", "5")` → `gerencia` | Sin cambio en el valor esperado, pero ahora la razón es la regla explícita, no el fallback |
| 53 | `aprobadorPara("otro", BigDecimal("2"))` es `null` | → `AprobadorSolicitud.gerencia` (con límite 0, hasta 2% ya excede) |
| 61 | `aprobadorPara("analista", BigDecimal("5"))` == `AprobadorSolicitud.jdv` | → `AprobadorSolicitud.gerencia` (analista ya no cae en la rama `rol == "vendedor"` de `aprobadorPara`) |

No lo trates como una lista opcional: correr `./gradlew test` sin aplicar estas correcciones deja el Step 4 en rojo por razones ajenas a lo que se acaba de implementar, y un implementador sin este contexto puede interpretarlo como que su cambio está mal cuando en realidad es el test viejo el que quedó desactualizado.

**Interfaces:**
- Consumes: nada de tareas anteriores.
- Produces: `PoliticaDescuento.limitePara("analista")` y `("otro")` devuelven `BigDecimal.ZERO`; `aprobadorPara` deja de derivar `jdv` para `analista`.

**Nota de diseño:** es defensa en profundidad. Tras la Task 4 un rol de apoyo ni siquiera llega a crear o editar una oportunidad, así que este camino no debería ejecutarse nunca. Se cambia igual para que la política no mienta si mañana alguien la consulta desde otro lugar.

- [ ] **Step 1: Escribir el test que falla**

Crear `src/test/kotlin/pe/quantum/crm/shared/PoliticaDescuentoTest.kt`:

```kotlin
package pe.quantum.crm.shared

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class PoliticaDescuentoTest {
    @Test
    fun `los roles de apoyo no tienen margen de descuento`() {
        assertThat(PoliticaDescuento.limitePara("analista")).isEqualByComparingTo(BigDecimal.ZERO)
        assertThat(PoliticaDescuento.limitePara("otro")).isEqualByComparingTo(BigDecimal.ZERO)
    }

    @Test
    fun `cualquier descuento de un rol de apoyo excede su limite`() {
        assertThat(PoliticaDescuento.excedeLimite("analista", BigDecimal("0.01"))).isTrue()
        assertThat(PoliticaDescuento.excedeLimite("otro", BigDecimal("1"))).isTrue()
    }

    @Test
    fun `un descuento de cero no excede el limite de un rol de apoyo`() {
        assertThat(PoliticaDescuento.excedeLimite("analista", BigDecimal.ZERO)).isFalse()
        assertThat(PoliticaDescuento.excedeLimite("analista", null)).isFalse()
    }

    @Test
    fun `el vendedor conserva su limite de 3 por ciento`() {
        assertThat(PoliticaDescuento.limitePara("vendedor")).isEqualByComparingTo(BigDecimal(3))
        assertThat(PoliticaDescuento.excedeLimite("vendedor", BigDecimal(3))).isFalse()
        assertThat(PoliticaDescuento.excedeLimite("vendedor", BigDecimal("3.01"))).isTrue()
    }

    @Test
    fun `el jdv conserva su limite de 7 por ciento`() {
        assertThat(PoliticaDescuento.limitePara("jdv")).isEqualByComparingTo(BigDecimal(7))
    }

    @Test
    fun `admin y gerencia siguen sin limite`() {
        assertThat(PoliticaDescuento.limitePara("admin")).isNull()
        assertThat(PoliticaDescuento.limitePara("gerencia")).isNull()
    }
}
```

- [ ] **Step 2: Correr el test y verificar que falla**

Run: `./gradlew test --tests "pe.quantum.crm.shared.PoliticaDescuentoTest"`
Expected: FAIL — `limitePara("analista")` devuelve `3`, no `0`.

- [ ] **Step 3: Implementar**

En `PoliticaDescuento.kt`, después de la línea `private val ROLES_SIN_LIMITE = setOf("admin", "gerencia")`, agregar:

```kotlin
    /**
     * Roles de apoyo: no aplican descuento por ninguna via. No editan
     * oportunidades (matriz_permisos.md), asi que este limite es defensa en
     * profundidad, no la puerta principal.
     */
    private val ROLES_SIN_DESCUENTO = setOf("analista", "otro")
```

Reemplazar el cuerpo de `limitePara` por:

```kotlin
    fun limitePara(rol: String): BigDecimal? =
        when (rol) {
            in ROLES_SIN_LIMITE -> null
            in ROLES_SIN_DESCUENTO -> BigDecimal.ZERO
            "jdv" -> LIMITE_JDV
            else -> LIMITE_VENDEDOR
        }
```

En `aprobadorPara`, reemplazar la línea que menciona `analista`:

```kotlin
            dcto <= LIMITE_JDV && rol == "vendedor" -> AprobadorSolicitud.jdv
```

- [ ] **Step 4: Correr el test y verificar que pasa**

Run: `./gradlew test --tests "pe.quantum.crm.shared.PoliticaDescuentoTest"`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
./gradlew ktlintFormat
git add src/main/kotlin/pe/quantum/crm/shared/PoliticaDescuento.kt src/test/kotlin/pe/quantum/crm/shared/PoliticaDescuentoTest.kt
git commit -m "feat(descuentos): los roles de apoyo no aplican descuento por ninguna via"
```

---

## Task 3: `TareaService` expone los ids donde el empleado colabora

**Files:**
- Modify: `src/main/kotlin/pe/quantum/crm/domain/tareas/TareaRepository.kt`
- Modify: `src/main/kotlin/pe/quantum/crm/domain/tareas/TareaService.kt`
- Modify: `src/main/kotlin/pe/quantum/crm/domain/tareas/TareaServiceImpl.kt`
- Test: `src/test/kotlin/pe/quantum/crm/domain/tareas/TareaColaboracionTest.kt` (crear)

**Interfaces:**
- Produces (las consumen Tasks 5 y 7):
  - `TareaService.idsOportunidadesDondeColabora(idEmpleado: Long): Set<Long>`
  - `TareaService.idsEmpresasDondeColabora(idEmpleado: Long): Set<Long>`

**Nota de diseño:** devuelve `Set<Long>` y no una Specification a propósito. `oportunidades` y `empresas` no pueden tocar la entidad `Tarea` (ArchUnit), así que la frontera se cruza con ids planos. El volumen esperado es bajo (un rol de apoyo colabora en decenas de tareas, no en miles).

- [ ] **Step 1: Escribir el test que falla**

Crear `src/test/kotlin/pe/quantum/crm/domain/tareas/TareaColaboracionTest.kt`:

```kotlin
package pe.quantum.crm.domain.tareas

import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * La frontera entre `tareas` y los modulos que consultan colaboracion:
 * devuelve ids planos, nunca entidades ni Specifications (ArquitecturaModulosTest).
 */
class TareaColaboracionTest {
    private val tareaRepository = mockk<TareaRepository>()

    // Construir TareaServiceImpl con el mismo bloque de mocks que use el test
    // existente del modulo (ver TareaServiceImplTest.kt): este test ejerce el
    // SERVICIO, no el repositorio, porque lo que se prueba es la conversion a
    // Set que el servicio garantiza a sus consumidores.
    private val service = tareaServiceConMocks(tareaRepository)

    @Test
    fun `el servicio deduplica los ids de oportunidad que devuelve el repositorio`() {
        every { tareaRepository.idsOportunidadConColaborador(7L) } returns listOf(10L, 20L, 10L)

        assertThat(service.idsOportunidadesDondeColabora(7L)).containsExactlyInAnyOrder(10L, 20L)
    }

    @Test
    fun `el servicio devuelve vacio cuando el empleado no colabora en nada`() {
        every { tareaRepository.idsEmpresaConColaborador(9L) } returns emptyList()

        assertThat(service.idsEmpresasDondeColabora(9L)).isEmpty()
    }

    @Test
    fun `el servicio delega en el repositorio y no filtra en memoria`() {
        every { tareaRepository.idsEmpresaConColaborador(7L) } returns listOf(1L, 2L)

        assertThat(service.idsEmpresasDondeColabora(7L)).containsExactlyInAnyOrder(1L, 2L)
        io.mockk.verify(exactly = 1) { tareaRepository.idsEmpresaConColaborador(7L) }
    }
}
```

**Antes de escribir el test:** abrir `src/test/kotlin/pe/quantum/crm/domain/tareas/TareaServiceImplTest.kt`, copiar su bloque de declaración de mocks y de construcción de `TareaServiceImpl`, y reemplazar con él la línea `private val service = tareaServiceConMocks(tareaRepository)` — ese helper no existe, es un marcador de dónde va el bloque copiado.

- [ ] **Step 2: Correr el test y verificar que falla**

Run: `./gradlew test --tests "pe.quantum.crm.domain.tareas.TareaColaboracionTest"`
Expected: FAIL — los métodos del repositorio no existen (error de compilación).

- [ ] **Step 3: Agregar las queries al repositorio**

En `TareaRepository.kt`, agregar el import `org.springframework.data.repository.query.Param` y dentro de la interfaz `TareaRepository`, antes de la llave de cierre:

```kotlin
    /**
     * Ids de oportunidad de las tareas donde `idEmpleado` figura como colaborador
     * (tabla `tarea_responsables`). Excluye las tareas de prospeccion, que no
     * tienen oportunidad. Se resuelve en SQL, no en memoria.
     */
    @Query(
        """
        SELECT DISTINCT t.idOportunidad FROM Tarea t
        WHERE t.idOportunidad IS NOT NULL
          AND t.id IN (SELECT r.id.idTarea FROM TareaResponsable r WHERE r.id.idEmpleado = :idEmpleado)
        """,
    )
    fun idsOportunidadConColaborador(
        @Param("idEmpleado") idEmpleado: Long,
    ): List<Long>

    /** Ids de empresa de las tareas donde `idEmpleado` figura como colaborador. */
    @Query(
        """
        SELECT DISTINCT t.idEmpresa FROM Tarea t
        WHERE t.id IN (SELECT r.id.idTarea FROM TareaResponsable r WHERE r.id.idEmpleado = :idEmpleado)
        """,
    )
    fun idsEmpresaConColaborador(
        @Param("idEmpleado") idEmpleado: Long,
    ): List<Long>
```

- [ ] **Step 4: Agregar los métodos a la interfaz de servicio**

En `TareaService.kt`, antes de la llave de cierre de la interfaz:

```kotlin
    /**
     * Ids de oportunidad donde `idEmpleado` colabora en alguna tarea. Es la API
     * publica que usan `oportunidades` y `empresas` para su filtro de visibilidad
     * de roles de apoyo: cruzan la frontera con ids, nunca con entidades.
     */
    fun idsOportunidadesDondeColabora(idEmpleado: Long): Set<Long>

    /** Ids de empresa donde `idEmpleado` colabora en alguna tarea. */
    fun idsEmpresasDondeColabora(idEmpleado: Long): Set<Long>
```

- [ ] **Step 5: Implementar en el servicio**

En `TareaServiceImpl.kt`, agregar dentro de la clase (junto a los demás `override`):

```kotlin
    @Transactional(readOnly = true)
    override fun idsOportunidadesDondeColabora(idEmpleado: Long): Set<Long> =
        tareaRepository.idsOportunidadConColaborador(idEmpleado).toSet()

    @Transactional(readOnly = true)
    override fun idsEmpresasDondeColabora(idEmpleado: Long): Set<Long> =
        tareaRepository.idsEmpresaConColaborador(idEmpleado).toSet()
```

Si `org.springframework.transaction.annotation.Transactional` no está importado en ese archivo, agregarlo.

- [ ] **Step 6: Correr los tests**

Run: `./gradlew test --tests "pe.quantum.crm.domain.tareas.*"`
Expected: PASS

- [ ] **Step 7: Commit**

```bash
./gradlew ktlintFormat
git add src/main/kotlin/pe/quantum/crm/domain/tareas/ src/test/kotlin/pe/quantum/crm/domain/tareas/TareaColaboracionTest.kt
git commit -m "feat(tareas): expone los ids de entidades donde un empleado colabora"
```

---

## Task 4: Oportunidades — bloquear toda escritura para roles de apoyo

**Files:**
- Modify: `src/main/kotlin/pe/quantum/crm/domain/oportunidades/OportunidadServiceImpl.kt`
- Test: `src/test/kotlin/pe/quantum/crm/domain/oportunidades/OportunidadRolApoyoTest.kt` (crear)
- Fix: tests existentes que asumían que `analista` valida `facturado` (identificados en Task 1, Step 5)

**Interfaces:**
- Consumes: `UsuarioActual.esRolApoyo` (Task 1).
- Produces: `OportunidadServiceImpl.rechazarSiEsApoyo(usuario)` — método privado, no lo consume nadie fuera.

**Nota crítica:** este guard lanza **403** (`PermisoInsuficienteException`), no 404. El 404 es para entidad no visible (Task 5). Un rol de apoyo que ve una oportunidad porque colabora en ella y trata de editarla debe recibir un mensaje que explique exactamente eso.

- [ ] **Step 1: Escribir el test que falla**

Crear `src/test/kotlin/pe/quantum/crm/domain/oportunidades/OportunidadRolApoyoTest.kt`. Copiar el bloque de mocks y construcción del servicio tal cual aparece en `OportunidadListadoSpecificationTest.kt` líneas 44-70, y agregar:

```kotlin
package pe.quantum.crm.domain.oportunidades

import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.assertj.core.api.Assertions.catchThrowable
import org.junit.jupiter.api.Test
import pe.quantum.crm.domain.contactos.ContactoService
import pe.quantum.crm.domain.empleados.EmpleadoService
import pe.quantum.crm.domain.empresas.EmpresaService
import pe.quantum.crm.domain.financiadoras.FinanciadoraService
import pe.quantum.crm.domain.modelos.ModeloService
import pe.quantum.crm.domain.notificaciones.NotificacionService
import pe.quantum.crm.domain.oportunidades.dto.ActualizarOportunidadRequest
import pe.quantum.crm.integracion.drive.DriveStorageService
import pe.quantum.crm.shared.exception.PermisoInsuficienteException
import pe.quantum.crm.shared.security.UsuarioActual

class OportunidadRolApoyoTest {
    private val oportunidadRepository = mockk<OportunidadRepository>()
    private val logRepository = mockk<OportunidadEstadoLogRepository>()
    private val contactoOportunidadRepository = mockk<OportunidadContactoRepository>()
    private val estadoCarteraService = mockk<EstadoCarteraService>()
    private val empresaService = mockk<EmpresaService>()
    private val empleadoService = mockk<EmpleadoService>()
    private val financiadoraService = mockk<FinanciadoraService>()
    private val modeloService = mockk<ModeloService>()
    private val contactoService = mockk<ContactoService>()
    private val consultas = mockk<OportunidadConsultas>()
    private val notificacionService = mockk<NotificacionService>(relaxed = true)
    private val driveStorageService = mockk<DriveStorageService>(relaxed = true)
    private val service =
        OportunidadServiceImpl(
            oportunidadRepository,
            logRepository,
            contactoOportunidadRepository,
            estadoCarteraService,
            empresaService,
            empleadoService,
            financiadoraService,
            modeloService,
            contactoService,
            consultas,
            notificacionService,
            driveStorageService,
        )

    private val analista = UsuarioActual(id = 7L, rol = "analista")
    private val otro = UsuarioActual(id = 8L, rol = "otro")

    @Test
    fun `un rol de apoyo no puede actualizar una oportunidad`() {
        assertThatThrownBy { service.actualizar(1L, ActualizarOportunidadRequest(), analista) }
            .isInstanceOf(PermisoInsuficienteException::class.java)
            .hasMessageContaining("apoyo")
    }

    @Test
    fun `el mensaje de error explica que el rol es de apoyo y solo consulta`() {
        val error = catchThrowable { service.actualizar(1L, ActualizarOportunidadRequest(), otro) }

        assertThat(error).isInstanceOf(PermisoInsuficienteException::class.java)
        assertThat(error.message)
            .contains("apoyo")
            .contains("consultar")
    }

    @Test
    fun `un rol de apoyo no puede cambiar el estado de una oportunidad`() {
        assertThatThrownBy {
            service.cambiarEstado(1L, CambiarEstadoRequest(estado = "documentos_legales"), analista)
        }.isInstanceOf(PermisoInsuficienteException::class.java)
    }
}
```

Agregar `import pe.quantum.crm.domain.oportunidades.dto.CambiarEstadoRequest`.

**Verificado contra el código real (`OportunidadService.kt:35-39`, `OportunidadDtos.kt:199-204`):** `cambiarEstado(id, request: CambiarEstadoRequest, usuario)` — no toma el estado como string suelto. `eliminar(id: Long)` **no tiene parámetro `usuario`** — ya está restringido a admin en el controller, así que no necesita (ni puede recibir) el guard de rol de apoyo. No agregar `rechazarSiEsApoyo` a `eliminar`.

**Antes de escribir el test, verificar la firma real de `actualizar` y `cambiarEstado` en `OportunidadService.kt` y ajustar los argumentos a la firma exacta.** Si `ActualizarOportunidadRequest` no tiene constructor sin argumentos, pasar todos los campos como `null`.

- [ ] **Step 2: Correr el test y verificar que falla**

Run: `./gradlew test --tests "pe.quantum.crm.domain.oportunidades.OportunidadRolApoyoTest"`
Expected: FAIL — no se lanza `PermisoInsuficienteException`.

- [ ] **Step 3: Implementar el guard**

En `OportunidadServiceImpl.kt`, agregar como método privado junto a `visible` (cerca de la línea 614):

```kotlin
    /**
     * Roles de apoyo: solo lectura sobre oportunidades (matriz_permisos.md).
     * 403 y no 404 a proposito: la entidad puede ser perfectamente visible para
     * el (colabora en una tarea suya); lo que no tiene es permiso de escritura, y
     * el mensaje debe decirlo para que el cliente no lo confunda con "no existe".
     */
    private fun rechazarSiEsApoyo(usuario: UsuarioActual) {
        if (usuario.esRolApoyo) {
            throw PermisoInsuficienteException(
                "Tu rol es de apoyo: puedes consultar esta oportunidad, pero no modificarla",
            )
        }
    }
```

Verificar que `PermisoInsuficienteException` esté importado en el archivo; si no, agregar `import pe.quantum.crm.shared.exception.PermisoInsuficienteException`.

Llamar a `rechazarSiEsApoyo(usuario)` como **primera línea** del cuerpo de estos métodos públicos:
- `crear`
- `actualizar`
- `cambiarEstado`
- `eliminar` (si existe)
- cualquier método que vincule/desvincule contactos o modifique campos

Buscar con: `grep -n "override fun" src/main/kotlin/pe/quantum/crm/domain/oportunidades/OportunidadServiceImpl.kt` y aplicar el guard a **todos los que escriben**. NO aplicarlo a `listar`, `detalle`, ni a los métodos de solo lectura ni a los de Drive de lectura.

- [ ] **Step 4: Correr el test y verificar que pasa**

Run: `./gradlew test --tests "pe.quantum.crm.domain.oportunidades.OportunidadRolApoyoTest"`
Expected: PASS

- [ ] **Step 5: Arreglar los tests existentes que asumían el comportamiento viejo**

Run: `./gradlew test --tests "pe.quantum.crm.domain.oportunidades.*"`

Para cada test que falle porque usaba un `UsuarioActual(rol = "analista")` esperando que pudiera escribir o validar `facturado`: cambiar el rol del usuario del test a `"gerencia"` si lo que se probaba era la operación en sí, o mover el test a `OportunidadRolApoyoTest` invirtiendo la aserción si lo que se probaba era el permiso. **No borrar tests.** Si algún caso no encaja en ninguna de las dos opciones, DETENERSE y reportar.

- [ ] **Step 6: Correr la suite completa**

Run: `./gradlew test`
Expected: PASS

- [ ] **Step 7: Commit**

```bash
./gradlew ktlintFormat
git add src/main/kotlin/pe/quantum/crm/domain/oportunidades/ src/test/kotlin/pe/quantum/crm/domain/oportunidades/
git commit -m "feat(oportunidades): los roles de apoyo no escriben; mensaje 403 especifico"
```

---

## Task 5: Oportunidades — visibilidad por colaboración

**Files:**
- Modify: `src/main/kotlin/pe/quantum/crm/domain/oportunidades/OportunidadServiceImpl.kt`
- Test: `src/test/kotlin/pe/quantum/crm/domain/oportunidades/OportunidadRolApoyoTest.kt` (agregar tests)

**Interfaces:**
- Consumes: `TareaService.idsOportunidadesDondeColabora(idEmpleado)` (Task 3), `UsuarioActual.esRolApoyo` (Task 1).

**Nota crítica — el bug clásico:** si el conjunto de ids viene vacío, `root.get<Long>("id").in(emptySet())` genera SQL inválido o devuelve todo según el dialecto. Hay que devolver `cb.disjunction()` (siempre falso) explícitamente. Sin eso, un rol de apoyo sin colaboraciones vería **todas** las oportunidades: es exactamente el hueco de seguridad que este ticket viene a cerrar.

- [ ] **Step 1: Escribir los tests que fallan**

Agregar a `OportunidadRolApoyoTest.kt`:

```kotlin
    @Test
    fun `un rol de apoyo sin colaboraciones no ve ninguna oportunidad`() {
        every { tareaService.idsOportunidadesDondeColabora(7L) } returns emptySet()
        every { oportunidadRepository.findAll(any<Specification<Oportunidad>>(), any<PageRequest>()) } returns
            PageImpl(emptyList())

        val resultado = service.listar(OportunidadFiltros(), analista, null, null, null, null)

        assertThat(resultado.datos).isEmpty()
        verify { tareaService.idsOportunidadesDondeColabora(7L) }
    }

    @Test
    fun `un rol de apoyo no ve una oportunidad en la que no colabora`() {
        every { tareaService.idsOportunidadesDondeColabora(7L) } returns setOf(99L)
        every { oportunidadRepository.findById(1L) } returns Optional.of(oportunidadDe(id = 1L, idVendedor = 3L))

        assertThatThrownBy { service.detalle(1L, analista) }
            .isInstanceOf(NoEncontradoException::class.java)
    }

    @Test
    fun `un rol de apoyo si ve una oportunidad en la que colabora`() {
        every { tareaService.idsOportunidadesDondeColabora(7L) } returns setOf(1L)
        every { oportunidadRepository.findById(1L) } returns Optional.of(oportunidadDe(id = 1L, idVendedor = 3L))

        assertThatCode { service.detalle(1L, analista) }.doesNotThrowAnyException()
    }
```

Añadir un helper `oportunidadDe(id, idVendedor)` que construya una `Oportunidad` mínima válida — copiar la construcción que ya usan los otros tests del módulo (`OportunidadLecturasTest.kt` es el mejor punto de referencia). Añadir los imports de `Specification`, `PageImpl`, `PageRequest`, `Optional`, `NoEncontradoException`, `OportunidadFiltros`, `every`, `verify`, `assertThatCode`.

- [ ] **Step 2: Correr y verificar que falla**

Run: `./gradlew test --tests "pe.quantum.crm.domain.oportunidades.OportunidadRolApoyoTest"`
Expected: FAIL — el servicio no tiene `tareaService` inyectado.

- [ ] **Step 3: Inyectar `TareaService`**

En el constructor de `OportunidadServiceImpl`, agregar como último parámetro:

```kotlin
    private val tareaService: TareaService,
```

Agregar `import pe.quantum.crm.domain.tareas.TareaService`. Actualizar **todos** los sitios que construyen `OportunidadServiceImpl` en tests con un `mockk<TareaService>()` adicional.

- [ ] **Step 4: Cambiar la Specification**

Reemplazar el bloque de visibilidad dentro de `especificacion` (líneas 667-671) por:

```kotlin
            if (usuario.esRolApoyo) {
                val ids = tareaService.idsOportunidadesDondeColabora(usuario.id)
                // Conjunto vacio: `in(emptySet())` es SQL invalido o, peor, un
                // predicado que no filtra nada. Falso explicito.
                predicados +=
                    if (ids.isEmpty()) cb.disjunction() else root.get<Long>("id").`in`(ids)
            } else if (usuario.visibilidadRestringida) {
                predicados += cb.equal(root.get<Long>("idVendedor"), usuario.id)
            } else if (filtros.idVendedor != null) {
                predicados += cb.equal(root.get<Long>("idVendedor"), filtros.idVendedor)
            }
```

- [ ] **Step 5: Cambiar el chequeo de detalle**

Reemplazar el cuerpo de `visible` (líneas 614-623) por:

```kotlin
    private fun visible(
        id: Long,
        usuario: UsuarioActual,
    ): Oportunidad {
        val oportunidad = entidad(id)
        if (!alcanza(oportunidad, usuario)) {
            throw NoEncontradoException("La oportunidad no existe")
        }
        return oportunidad
    }

    /**
     * Visibilidad unificada para detalle y listado. Rol de apoyo: solo donde
     * colabora via tarea (no tiene cartera propia). Vendedor: solo lo suyo.
     * Supervisor: todo.
     */
    private fun alcanza(
        oportunidad: Oportunidad,
        usuario: UsuarioActual,
    ): Boolean =
        when {
            usuario.esRolApoyo ->
                oportunidad.id in tareaService.idsOportunidadesDondeColabora(usuario.id)
            usuario.visibilidadRestringida -> oportunidad.idVendedor == usuario.id
            else -> true
        }
```

Y en `visibleBloqueando` (línea 638), reemplazar la condición por:

```kotlin
        if (!alcanza(oportunidad, usuario)) {
```

- [ ] **Step 6: Correr los tests**

Run: `./gradlew test`
Expected: PASS. Si falla `ArquitecturaModulosTest`, significa que se importó una entidad de `tareas` en vez de solo la interfaz de servicio — corregirlo.

- [ ] **Step 7: Commit**

```bash
./gradlew ktlintFormat
git add src/main/kotlin/pe/quantum/crm/domain/oportunidades/ src/test/kotlin/pe/quantum/crm/domain/oportunidades/
git commit -m "feat(oportunidades): visibilidad por colaboracion para roles de apoyo"
```

---

## Task 6: Empresas — bloquear toda escritura para roles de apoyo

**Files:**
- Modify: `src/main/kotlin/pe/quantum/crm/domain/empresas/EmpresaServiceImpl.kt`
- Test: `src/test/kotlin/pe/quantum/crm/domain/empresas/EmpresaRolApoyoTest.kt` (crear)

**Interfaces:**
- Consumes: `UsuarioActual.esRolApoyo` (Task 1).

Réplica exacta de la Task 4, sobre empresas.

- [ ] **Step 1: Escribir el test que falla**

Crear `EmpresaRolApoyoTest.kt` copiando el patrón de mocks de un test existente de empresas (`EmpresaServiceImplTest.kt` o equivalente — usar `ls src/test/kotlin/pe/quantum/crm/domain/empresas/` para elegir el más cercano) y agregar:

```kotlin
    private val analista = UsuarioActual(id = 7L, rol = "analista")

    @Test
    fun `un rol de apoyo no puede crear una empresa`() {
        assertThatThrownBy { service.crear(crearEmpresaRequestValido(), analista) }
            .isInstanceOf(PermisoInsuficienteException::class.java)
            .hasMessageContaining("apoyo")
    }

    @Test
    fun `un rol de apoyo no puede actualizar una empresa`() {
        assertThatThrownBy { service.actualizar(1L, actualizarEmpresaRequestValido(), analista) }
            .isInstanceOf(PermisoInsuficienteException::class.java)
    }

    @Test
    fun `un rol de apoyo no puede cambiar el estado de cartera`() {
        assertThatThrownBy { service.cambiarEstadoCarteraManual(1L, "prospeccion", analista) }
            .isInstanceOf(PermisoInsuficienteException::class.java)
    }
```

**Verificado contra el código real (`EmpresaService.kt:75-79, 165`):** el método se llama `cambiarEstadoCarteraManual(id, estadoCartera: String, usuario)`, no `cambiarEstadoCartera`. `eliminar(id: Long)` **no tiene parámetro `usuario`** — igual que en oportunidades, ya restringido a admin en el controller; no agregar el guard ahí. `reasignarVendedor` ya está fuera del alcance de `analista`/`otro` hoy (admin/gerencia únicamente, verificado en controller) — no requiere el guard nuevo, pero no hace daño agregarlo si el implementador ya está ahí.

- [ ] **Step 2: Correr y verificar que falla**

Run: `./gradlew test --tests "pe.quantum.crm.domain.empresas.EmpresaRolApoyoTest"`
Expected: FAIL

- [ ] **Step 3: Implementar el guard**

En `EmpresaServiceImpl.kt`, agregar el método privado:

```kotlin
    /**
     * Roles de apoyo: solo lectura sobre empresas (matriz_permisos.md). 403 y no
     * 404: la empresa puede ser visible para el si colabora en una tarea suya.
     */
    private fun rechazarSiEsApoyo(usuario: UsuarioActual) {
        if (usuario.esRolApoyo) {
            throw PermisoInsuficienteException(
                "Tu rol es de apoyo: puedes consultar esta empresa, pero no modificarla",
            )
        }
    }
```

Llamarlo como primera línea de todos los métodos de escritura: `crear`, `actualizar`, `cambiarEstadoCartera`, `reasignarVendedor`, `moverACarteraMaestra`/`liberar`, `eliminar`, vinculación de contactos. Identificarlos con `grep -n "override fun" src/main/kotlin/pe/quantum/crm/domain/empresas/EmpresaServiceImpl.kt`. NO tocar los de lectura.

- [ ] **Step 4: Correr los tests**

Run: `./gradlew test --tests "pe.quantum.crm.domain.empresas.*"`
Expected: PASS. Arreglar los tests existentes que asumían que `analista` escribía, con el mismo criterio de la Task 4 Step 5.

- [ ] **Step 5: Commit**

```bash
./gradlew ktlintFormat
git add src/main/kotlin/pe/quantum/crm/domain/empresas/ src/test/kotlin/pe/quantum/crm/domain/empresas/
git commit -m "feat(empresas): los roles de apoyo no escriben; mensaje 403 especifico"
```

---

## Task 7: Empresas — visibilidad por colaboración

**Files:**
- Modify: `src/main/kotlin/pe/quantum/crm/domain/empresas/EmpresaServiceImpl.kt`
- Test: `src/test/kotlin/pe/quantum/crm/domain/empresas/EmpresaRolApoyoTest.kt` (agregar)

**Interfaces:**
- Consumes: `TareaService.idsEmpresasDondeColabora(idEmpleado)` (Task 3).

Réplica de la Task 5, sobre empresas. **Mismo cuidado con el conjunto vacío → `cb.disjunction()`.**

- [ ] **Step 1: Escribir los tests que fallan**

```kotlin
    @Test
    fun `un rol de apoyo sin colaboraciones no ve ninguna empresa`() {
        every { tareaService.idsEmpresasDondeColabora(7L) } returns emptySet()
        every { empresaRepository.findAll(any<Specification<Empresa>>(), any<PageRequest>()) } returns
            PageImpl(emptyList())

        assertThat(service.listar(EmpresaFiltros(), analista, null, null, null, null).datos).isEmpty()
    }

    @Test
    fun `un rol de apoyo no ve una empresa en la que no colabora`() {
        every { tareaService.idsEmpresasDondeColabora(7L) } returns setOf(99L)
        every { empresaRepository.findById(1L) } returns Optional.of(empresaDe(id = 1L, idVendedor = 3L))

        assertThatThrownBy { service.detalle(1L, analista) }
            .isInstanceOf(NoEncontradoException::class.java)
    }
```

Ajustar firmas a las reales de `EmpresaService.kt`.

- [ ] **Step 2: Correr y verificar que falla**

Run: `./gradlew test --tests "pe.quantum.crm.domain.empresas.EmpresaRolApoyoTest"`
Expected: FAIL

- [ ] **Step 3: Inyectar `TareaService` y aplicar el filtro**

Agregar `private val tareaService: TareaService,` al constructor de `EmpresaServiceImpl` y actualizar todos los sitios de construcción en tests.

En el bloque de visibilidad de la Specification de `listar` (alrededor de la línea 554) y en `visible` (alrededor de la 520), aplicar exactamente el mismo patrón de la Task 5: rama `esRolApoyo` con `cb.disjunction()` para conjunto vacío, luego `visibilidadRestringida`, luego supervisor.

**Cartera Maestra:** el filtro de Cartera Maestra que ya existe se mantiene tal cual y se combina con `and`. Un rol de apoyo nunca ve Cartera Maestra — verificar que el predicado existente sigue aplicándose.

- [ ] **Step 4: Correr la suite**

Run: `./gradlew test`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
./gradlew ktlintFormat
git add src/main/kotlin/pe/quantum/crm/domain/empresas/ src/test/kotlin/pe/quantum/crm/domain/empresas/
git commit -m "feat(empresas): visibilidad por colaboracion para roles de apoyo"
```

---

## Task 8: Solicitudes — los roles de apoyo no crean solicitudes

**Files:**
- Modify: `src/main/kotlin/pe/quantum/crm/domain/solicitudes/SolicitudServiceImpl.kt`
- Test: `src/test/kotlin/pe/quantum/crm/domain/solicitudes/SolicitudRolApoyoTest.kt` (crear)

**Interfaces:**
- Consumes: `UsuarioActual.esRolApoyo` (Task 1).

- [ ] **Step 1: Escribir el test que falla**

```kotlin
    @Test
    fun `un rol de apoyo no puede crear una solicitud de descuento`() {
        assertThatThrownBy { service.crear(solicitudDescuentoRequest(), UsuarioActual(7L, "analista")) }
            .isInstanceOf(PermisoInsuficienteException::class.java)
            .hasMessageContaining("apoyo")
    }

    @Test
    fun `un rol de apoyo no puede crear una solicitud de reasignacion`() {
        assertThatThrownBy { service.crear(solicitudReasignacionRequest(), UsuarioActual(8L, "otro")) }
            .isInstanceOf(PermisoInsuficienteException::class.java)
    }
```

Copiar el patrón de mocks de un test existente de solicitudes y ajustar los constructores de request a las firmas reales.

- [ ] **Step 2: Correr y verificar que falla**

Run: `./gradlew test --tests "pe.quantum.crm.domain.solicitudes.SolicitudRolApoyoTest"`
Expected: FAIL

- [ ] **Step 3: Implementar**

En `SolicitudServiceImpl.crear`, como primera línea del cuerpo:

```kotlin
        if (usuario.esRolApoyo) {
            throw PermisoInsuficienteException(
                "Tu rol es de apoyo: no puedes crear solicitudes de aprobación",
            )
        }
```

**Ruling de preflight (no requiere confirmación, ya decidido):** el filtro de la bandeja (línea ~241, `filtros.mias || usuario.rol == "vendedor" || usuario.rol == "analista"`) **NO se toca**. R7 retira la capacidad de *crear* solicitudes, no la de *ver* las que ya existen — dejar a un analista sin poder consultar una solicitud histórica suya sería una regresión no pedida por nadie. El guard de creación del Step 3 es suficiente y es la única modificación de este archivo fuera de imports.

- [ ] **Step 4: Correr los tests**

Run: `./gradlew test --tests "pe.quantum.crm.domain.solicitudes.*"`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
./gradlew ktlintFormat
git add src/main/kotlin/pe/quantum/crm/domain/solicitudes/ src/test/kotlin/pe/quantum/crm/domain/solicitudes/
git commit -m "feat(solicitudes): los roles de apoyo no crean solicitudes"
```

---

## Task 9: Documentación — matriz de permisos y contrato

**Files:**
- Modify: `docs/matriz_permisos.md`
- Modify: `docs/contrato_api.md`

**Interfaces:** ninguna — es documentación, pero es parte del entregable: el frontend consume estos documentos.

- [ ] **Step 1: Agregar la columna `otro` y corregir `analista` en la matriz**

En `docs/matriz_permisos.md`:

1. En la tabla "Roles del sistema", corregir la fila de `analista` y agregar la de `otro`:

```markdown
| `analista` | Analista financiero | **Rol de apoyo: sin cartera propia.** Solo lectura sobre empresas y oportunidades, y únicamente las que colabora vía tarea. No confirma `facturado`, no aplica descuentos, no crea solicitudes. |
| `otro` | Roles de apoyo no comerciales | Mismos permisos que `analista`: sin cartera propia, solo lectura por colaboración. |
```

2. Agregar una columna `otro` a **todas** las tablas de la §1 y la §2, con los mismos valores que `analista`.

3. Actualizar cada fila de las §1, §2.2, §2.4, §2.12 según el nuevo comportamiento: `analista` y `otro` con `—` en toda operación de escritura de empresas/oportunidades/solicitudes, y visibilidad "Solo donde colabora vía tarea".

4. Corregir la nota de §2.4 sobre `facturado`: ahora solo `admin` y `gerencia`.

5. Corregir el ejemplo de código de §3.4, que menciona `ANALISTA` en la lista de roles que validan `facturado`.

6. En §4.3 ("Analista financiero en fases futuras"), reescribir para que refleje que hoy es un rol de apoyo de solo lectura.

- [ ] **Step 2: Corregir la deriva preexistente `gerente` → `gerencia`**

En `docs/contrato_api.md`, línea ~1140, el endpoint `PATCH /oportunidades/:id/estado` dice "`admin`, `gerente` y `analista`". Reemplazar por "`admin` y `gerencia`". (El nombre `gerente` quedó obsoleto desde la migración V25; se corrige aquí porque estamos tocando exactamente esa línea.)

- [ ] **Step 3: Actualizar los límites de descuento en el contrato**

En `docs/contrato_api.md` línea ~180, reemplazar:

```markdown
**Límites de descuento** (por encima del límite, el cambio requiere una solicitud — ver §19): `vendedor` hasta 3%, `jdv` hasta 7%, `gerencia`/`admin` sin límite. Los roles de apoyo (`analista`, `otro`) no aplican descuentos por ninguna vía.
```

- [ ] **Step 4: Agregar la fila al changelog del contrato (§25)**

En `docs/contrato_api.md` §25, agregar a la tabla:

```markdown
| 2026-08-18 | `GET /oportunidades`, `GET /empresas`, `PATCH /oportunidades/:id/estado`, `POST /oportunidades`, `POST /empresas`, `POST /solicitudes` | **Breaking** | `analista` y `otro` pasan a roles de apoyo de solo lectura: los listados solo devuelven las entidades donde el usuario colabora vía tarea; toda escritura responde `403 PERMISO_INSUFICIENTE`; `analista` deja de poder confirmar `facturado`. | Ocultar en el cliente las acciones de escritura para estos roles y no asumir que "lo que veo, lo puedo editar". El 403 trae un mensaje específico que se puede mostrar tal cual. |
```

- [ ] **Step 5: Commit**

```bash
git add docs/matriz_permisos.md docs/contrato_api.md
git commit -m "docs: roles de apoyo en matriz de permisos y changelog del contrato"
```

---

## Task 10: Solicitudes — visibilidad para el rol `otro` (agregada 2026-08-19)

**Por qué existe:** al documentar Task 9 se encontró que `SolicitudServiceImpl.especificacion()` (el filtro del **listado**, `GET /solicitudes`) nunca tuvo una rama de visibilidad para el rol `otro` — solo para `admin`, `gerencia`, `jdv`, y `vendedor`/`analista` (línea `filtros.mias || usuario.rol == "vendedor" || usuario.rol == "analista"`). `otro` cae fuera de todas las ramas del `when`, igual que `admin`, y por lo tanto **no recibe ningún predicado de alcance**: ve todas las solicitudes de la empresa, incluidos montos de descuento y motivos de reasignación ajenos. Es un hallazgo preexistente (no introducido por este plan), pero el usuario decidió corregirlo ahora en vez de diferirlo a un ticket aparte. El **detalle** (`visible()`, `GET /solicitudes/:id`) ya está bien — su `else -> solicitud.idSolicitante == usuario.id` cubre a `otro` correctamente por ser el fallback por defecto.

**Files:**
- Modify: `src/main/kotlin/pe/quantum/crm/domain/solicitudes/SolicitudServiceImpl.kt`
- Test: `src/test/kotlin/pe/quantum/crm/domain/solicitudes/SolicitudRolApoyoTest.kt` (agregar)

**Interfaces:**
- Consumes: `UsuarioActual.esRolApoyo` (Task 1) — reemplaza la comparación de string `usuario.rol == "analista"` por el predicado ya establecido en el resto del plan, lo cual además cierra el hueco de `otro` de forma consistente con Tasks 4/5/6/7.

- [ ] **Step 1: Escribir el test que falla**

Agregar a `SolicitudRolApoyoTest.kt`:

```kotlin
    @Test
    fun `un rol de apoyo solo ve sus propias solicitudes en el listado`() {
        val otro = UsuarioActual(id = 9L, rol = "otro")
        val solicitudAjena = solicitudDe(id = 1L, idSolicitante = 99L)
        every {
            solicitudRepository.findAll(any<Specification<Solicitud>>(), any<PageRequest>())
        } answers {
            val spec = firstArg<Specification<Solicitud>>()
            val incluye = compilarYEvaluar(spec, solicitudAjena)
            PageImpl(if (incluye) listOf(solicitudAjena) else emptyList())
        }

        val resultado = service.listar(SolicitudFiltros(), otro, null, null, null, null)

        assertThat(resultado.items).isEmpty()
    }
```

**Antes de escribir el test:** revisar si el módulo `solicitudes` ya tiene un test que compile la `Specification` contra un metamodelo real de Hibernate, del mismo tipo que `OportunidadListadoSpecificationTest.kt`/`EmpresaBusquedaSpecificationTest.kt` (buscar `SolicitudBusquedaSpecificationTest.kt` o similar en `src/test/kotlin/pe/quantum/crm/domain/solicitudes/`). **Si existe, usar ESE archivo y ESE mecanismo, no un mock.** La lección de Tasks 5 y 7 de este mismo plan: un test que mockea `findAll` sin evaluar la `Specification` real no protege contra el bug de seguridad que se está corrigiendo — puede pasar en verde con el bug presente. El snippet de arriba es un placeholder de la intención (verificar que `otro` NO ve una solicitud ajena en el listado); adaptalo al mecanismo real que ya exista en el módulo.

- [ ] **Step 2: Correr el test y verificar que falla**

Run: `./gradlew test --tests "pe.quantum.crm.domain.solicitudes.*"`
Expected: FAIL — el test nuevo detecta que `otro` ve la solicitud ajena.

- [ ] **Step 3: Implementar**

En `SolicitudServiceImpl.kt`, línea 246, reemplazar:

```kotlin
                filtros.mias || usuario.rol == "vendedor" || usuario.rol == "analista" ->
```

por:

```kotlin
                filtros.mias || usuario.rol == "vendedor" || usuario.esRolApoyo ->
```

`esRolApoyo` ya cubre `analista` y `otro` (Task 1), así que este único cambio cierra el hueco para los dos sin duplicar la condición.

- [ ] **Step 4: Correr el test y verificar que pasa**

Run: `./gradlew test --tests "pe.quantum.crm.domain.solicitudes.*"`
Expected: PASS

- [ ] **Step 5: Correr la suite completa**

Run: `./gradlew test`
Expected: PASS, sin nuevas fallas.

- [ ] **Step 6: Commit**

```bash
./gradlew ktlintFormat
git add src/main/kotlin/pe/quantum/crm/domain/solicitudes/SolicitudServiceImpl.kt src/test/kotlin/pe/quantum/crm/domain/solicitudes/SolicitudRolApoyoTest.kt
git commit -m "fix(solicitudes): el rol otro ya no ve solicitudes ajenas en el listado"
```

- [ ] **Step 7: Actualizar la documentación que este mismo plan dejó marcada como hallazgo pendiente**

En `docs/matriz_permisos.md` §2.12 y la nota de §1: cambiar la celda `otro` de "⚠️ Sin restricción explícita en código" a "✓ Solo las propias, igual que analista", y quitar/actualizar la nota del hallazgo de seguridad (ya no aplica — se corrigió acá). Mismo ajuste en `docs/contrato_api.md` (`GET /solicitudes`, el bloque de advertencia agregado en Task 9).

```bash
git add docs/matriz_permisos.md docs/contrato_api.md
git commit -m "docs: refleja el fix de visibilidad de solicitudes para el rol otro"
```

---

## Task 11: Dividir `OportunidadServiceImpl` — falla `detekt` LargeClass (agregada 2026-08-19)

**Por qué existe:** al correr los gates finales, `./gradlew detekt` falla: `OportunidadServiceImpl is too large. Consider splitting it into smaller pieces. [LargeClass]`. El archivo creció de sus ~700 líneas originales a 827 por las Tasks 4 y 5 de este plan (el guard `rechazarSiEsApoyo` y la lógica de visibilidad por colaboración). El umbral de `LargeClass` es 600 líneas (default de detekt, sin override en `config/detekt/detekt.yml`), así que hay que sacar más de 227 líneas del archivo.

**Patrón ya establecido en el proyecto, replicar exactamente:** `OportunidadConsultas.kt` ya existe como un `@Component` separado al que `OportunidadServiceImpl` delega consultas de solo lectura — es la extracción de responsabilidad que el proyecto ya usa quandeo un service crece. Este task hace lo mismo con la lógica de visibilidad/autorización.

**Files:**
- Create: `src/main/kotlin/pe/quantum/crm/domain/oportunidades/OportunidadVisibilidad.kt`
- Modify: `src/main/kotlin/pe/quantum/crm/domain/oportunidades/OportunidadServiceImpl.kt`
- Test: mover (no reescribir) los tests que ejercitan visibilidad si quedan más claros en un archivo propio; si `OportunidadRolApoyoTest.kt` y `OportunidadListadoSpecificationTest.kt` siguen construyendo `OportunidadServiceImpl` completo (no la clase nueva por separado), no hace falta moverlos — verificar cuál es más simple.

**Interfaces:**
- Produces: `OportunidadVisibilidad` — un `@Component` con los métodos `alcanza(oportunidad, usuario): Boolean`, `especificacionVisibilidad(usuario): (predicados: MutableList<Predicate>, root, cb) -> Unit` o firma equivalente que `especificacion()` pueda seguir usando sin duplicar lógica. La firma exacta queda a criterio del implementador — lo que importa es que `alcanza()` sea la única fuente de verdad reutilizada tanto por `visible()`/`visibleBloqueando()` como por la Specification del listado, exactamente como hoy.
- Consumes: `TareaService` (ya inyectado con `@Lazy` en `OportunidadServiceImpl` por Task 5 — mover esa dependencia a la clase nueva, ya no hace falta en `OportunidadServiceImpl` si toda la lógica de visibilidad se delega).

- [ ] **Step 1: Confirmar el umbral y medir el archivo actual**

```bash
wc -l src/main/kotlin/pe/quantum/crm/domain/oportunidades/OportunidadServiceImpl.kt
./gradlew detekt
```

Confirmar que el único issue es `LargeClass` en este archivo (no otro nuevo). Anotar la línea exacta del mensaje.

- [ ] **Step 2: Extraer la lógica de visibilidad**

Mover a `OportunidadVisibilidad.kt` (nuevo `@Component`, inyección por constructor con `private val tareaService: TareaService`):
- `rechazarSiEsApoyo(usuario)` (línea ~636 del archivo original)
- `alcanza(oportunidad, usuario)` (línea ~660)
- La construcción del predicado de visibilidad que hoy vive dentro de `especificacion()` (líneas ~706-722): extraer a un método que devuelva el/los `Predicate` para que `especificacion()` en `OportunidadServiceImpl` siga orquestando el resto de sus filtros (estado, empresa, financiadora) sin duplicar la regla de colaboración.

`OportunidadServiceImpl` pasa a **inyectar `OportunidadVisibilidad`** (constructor) y llamarla donde antes llamaba a sus propios métodos privados: `visibilidad.rechazarSiEsApoyo(usuario)`, `visibilidad.alcanza(oportunidad, usuario)`, etc. `visible()` y `visibleBloqueando()` se quedan en `OportunidadServiceImpl` (son cortos y specíficos de esos dos flujos) pero llaman a `visibilidad.alcanza(...)` en vez de a un método propio.

**No cambiar comportamiento.** Este es un refactor puro — mismos guards, mismo orden, mismos mensajes de excepción, misma regla de `cb.disjunction()` para conjunto vacío. Si algún test cambia su resultado, es una señal de que el refactor alteró comportamiento por accidente — parar y revisar.

- [ ] **Step 3: Ajustar los constructores en los tests**

Todos los tests que construyen `OportunidadServiceImpl(...)` directamente (grep `OportunidadServiceImpl(` en `src/test`) necesitan agregar un `mockk<OportunidadVisibilidad>()` (o construir la clase real, según convenga al test) y quitar el mock de `TareaService` del constructor de `OportunidadServiceImpl` si ya no lo recibe. Los tests que hoy hacen `every { tareaService.idsOportunidadesDondeColabora(...) }` pasan a mockear `OportunidadVisibilidad` en su lugar, o mantienen el mock de `TareaService` pero inyectado en `OportunidadVisibilidad` — elegir el que requiera menos cambios en los asserts existentes.

- [ ] **Step 4: Correr los gates**

```bash
./gradlew ktlintCheck
./gradlew detekt
./gradlew test
```

Los tres deben pasar. `detekt` en particular: confirmar que `LargeClass` ya no aparece para `OportunidadServiceImpl` ni aparece para el archivo nuevo (si `OportunidadVisibilidad.kt` también creciera por encima de 600 líneas, cosa improbable dado el tamaño de lo movido, dividir de nuevo).

- [ ] **Step 5: Commit**

```bash
./gradlew ktlintFormat
git add src/main/kotlin/pe/quantum/crm/domain/oportunidades/ src/test/kotlin/pe/quantum/crm/domain/oportunidades/
git commit -m "refactor(oportunidades): extrae OportunidadVisibilidad para bajar OportunidadServiceImpl del umbral de LargeClass"
```

---

## Task 12: Verificación final y PR

- [ ] **Step 1: Gates locales completos**

```bash
./gradlew ktlintCheck
./gradlew detekt
./gradlew test
./gradlew koverVerify
```

Los cuatro deben pasar. Si `koverVerify` falla por cobertura, agregar los tests que falten — no bajar el umbral.

- [ ] **Step 2: Verificar la frontera de módulos explícitamente**

Run: `./gradlew test --tests "pe.quantum.crm.arquitectura.ArquitecturaModulosTest"`
Expected: PASS. Si falla, se importó una entidad o repositorio de `tareas` desde `oportunidades`/`empresas` — corregirlo usando solo `TareaService`.

- [ ] **Step 3: Levantar la app contra el docker-compose local**

```bash
docker-compose up -d
./gradlew bootRun
```

Verificar manualmente con un usuario de rol `analista`: que `GET /oportunidades` devuelve solo donde colabora, que un `PUT` responde 403 con el mensaje específico, y que `GET /oportunidades/:id` de una ajena responde 404 (no 403).

- [ ] **Step 4: Abrir el PR**

```bash
git push -u origin feature/roles-apoyo-solo-lectura
gh pr create --base develop --title "feat: analista y otro pasan a roles de apoyo de solo lectura" --body "Implementa docs/requerimientos/2026-08-18-permisos-analista-otro.json (R1-R8).

Plan: docs/superpowers/plans/2026-08-18-roles-apoyo-solo-lectura.md

Cambio breaking del contrato, registrado en contrato_api.md §25.

🤖 Generated with [Claude Code](https://claude.com/claude-code)"
```
