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
- Test: `src/test/kotlin/pe/quantum/crm/shared/security/UsuarioActualTest.kt` (crear si no existe)

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
- Test: `src/test/kotlin/pe/quantum/crm/shared/PoliticaDescuentoTest.kt` (crear si no existe; si existe, agregar los tests)

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
        assertThatThrownBy { service.cambiarEstado(1L, "documentos_legales", null, analista) }
            .isInstanceOf(PermisoInsuficienteException::class.java)
    }
}
```

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
        assertThatThrownBy { service.cambiarEstadoCartera(1L, "prospeccion", analista) }
            .isInstanceOf(PermisoInsuficienteException::class.java)
    }
```

Ajustar nombres de método y construcción de requests a las firmas reales de `EmpresaService.kt`.

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

Además, en el filtro de la bandeja (línea ~241), quitar `|| usuario.rol == "analista"` de la rama de "solicitudes propias": un rol de apoyo ya no genera solicitudes, pero debe poder seguir viendo las históricas suyas si las hubiera. **Verificar antes de tocar:** si quitarlo deja a un analista con solicitudes viejas sin poder verlas, dejar la condición como está y anotarlo. En caso de duda, DETENERSE y preguntar.

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

## Task 10: Verificación final y PR

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
