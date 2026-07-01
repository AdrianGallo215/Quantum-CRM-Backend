# Quantum CRM Backend — Estrategia de Testing (TDD)

> El backend se desarrolla **estrictamente Test-Driven**. Este documento define cómo. Claude Code lo lee antes de escribir código y lo sigue en cada tarea.

---

## 1. Por qué TDD es obligatorio

Un agente de IA genera código que *parece* correcto pero puede fallar por supuestos incorrectos sobre tipos, contratos o reglas de negocio. TDD invierte el orden: primero el comportamiento esperado como test que falla, luego el mínimo código para que pase. Esto hace que:

- Cada pieza de lógica tenga una especificación ejecutable antes de existir.
- Los errores se detecten al escribirlos, no al integrar.
- Las reglas de `reglas_negocio.md` se traduzcan en tests verificables.
- Refactorizar sea seguro.

**Regla dura:** no se escribe código de producción sin un test que falle apuntándolo. Ninguna tarea termina sin sus tests pasando.

---

## 2. El ciclo Red-Green-Refactor

```
1. RED    — Test que describe el comportamiento. Ejecutar. DEBE fallar.
2. GREEN  — Mínimo código para que pase. Nada más. Ejecutar. DEBE pasar.
3. REFACTOR — Mejorar sin cambiar comportamiento. Tests siguen verdes.
```

**Ejemplo — cálculo de `monto_total`:**

```kotlin
// RED — no compila/falla porque calcularMontoTotal no existe.
@Test
fun `monto total es cantidad por precio unitario con descuento aplicado`() {
    val resultado = calcularMontoTotal(cantidad = 8, precioUnitario = "92000.00".toBigDecimal(), dcto = "3.00".toBigDecimal())
    assertEquals("713920.00".toBigDecimal(), resultado)
}

@Test
fun `monto total trata descuento null como cero`() {
    val resultado = calcularMontoTotal(cantidad = 10, precioUnitario = "92000.00".toBigDecimal(), dcto = null)
    assertEquals("920000.00".toBigDecimal(), resultado)
}

@Test
fun `monto total es null cuando cantidad es null`() {
    val resultado = calcularMontoTotal(cantidad = null, precioUnitario = "92000.00".toBigDecimal(), dcto = null)
    assertNull(resultado)
}

// GREEN — mínimo para que los tres pasen.
fun calcularMontoTotal(cantidad: Int?, precioUnitario: BigDecimal, dcto: BigDecimal?): BigDecimal? {
    if (cantidad == null) return null
    val descuento = dcto ?: BigDecimal.ZERO
    val factor = BigDecimal.ONE.minus(descuento.divide(BigDecimal(100)))
    return precioUnitario.multiply(BigDecimal(cantidad)).multiply(factor)
}

// REFACTOR — extraer constantes, precisión, etc. Tests verdes.
```

---

## 3. Pirámide de tests

```
        ╱╲          Integración (algunos) — endpoints con DB real
       ╱  ╲
      ╱────╲        Arquitectura (ArchUnit) — reglas del monolito modular
     ╱      ╲
    ╱────────╲      Unitarios (muchos) — lógica de negocio aislada
   ╱──────────╲
```

---

## 4. Stack de testing

```
JUnit 5              — framework base
MockK                — mocking idiomático para Kotlin (preferido sobre Mockito)
Spring Boot Test     — @SpringBootTest, @WebMvcTest, @DataJpaTest
Testcontainers       — PostgreSQL real en contenedor para integración
AssertJ              — aserciones fluidas
ArchUnit             — validación de reglas arquitectónicas
Kover                — cobertura de Kotlin
```

**Por qué Testcontainers y no H2:** H2 no es PostgreSQL. Difiere en tipos (enums, arrays), funciones y transacciones, haciendo que un test pase en H2 y falle en producción. Testcontainers levanta un PostgreSQL real idéntico al de producción. Crítico para un sistema donde la sincronización depende del comportamiento exacto de la base de datos.

---

## 5. Tests unitarios — servicios y lógica de negocio

El servicio se prueba con sus dependencias mockeadas (repositories, otros servicios).

```kotlin
@ExtendWith(MockKExtension::class)
class OportunidadServiceTest {

    @MockK lateinit var oportunidadRepository: OportunidadRepository
    @MockK lateinit var estadoCarteraService: EstadoCarteraService
    @MockK lateinit var estadoLogRepository: OportunidadEstadoLogRepository

    @InjectMockKs lateinit var service: OportunidadServiceImpl

    @Test
    fun `cerrar oportunidad sin motivo lanza MotivoCierreRequeridoException`() {
        val oportunidad = unaOportunidad(estado = EVALUACION_CALIDDA)
        every { oportunidadRepository.findByIdOrNull(1) } returns oportunidad

        assertThrows<MotivoCierreRequeridoException> {
            service.cambiarEstado(1, CambiarEstadoRequest(estado = CERRADO, motivoCierre = null), unEmpleadoJdV())
        }
    }

    @Test
    fun `pasar a facturado con rol vendedor lanza PermisoInsuficienteException`() {
        val oportunidad = unaOportunidad(estado = DOCUMENTOS_LEGALES)
        every { oportunidadRepository.findByIdOrNull(1) } returns oportunidad

        assertThrows<PermisoInsuficienteException> {
            service.cambiarEstado(1, CambiarEstadoRequest(estado = FACTURADO), unEmpleadoVendedor())
        }
    }

    @Test
    fun `cambiar estado registra en el log y actualiza estado de cartera`() {
        val oportunidad = unaOportunidad(estado = EVALUACION_CALIDDA)
        every { oportunidadRepository.findByIdOrNull(1) } returns oportunidad
        every { estadoLogRepository.save(any()) } returnsArgument 0
        every { estadoCarteraService.actualizar(any()) } just Runs

        service.cambiarEstado(1, CambiarEstadoRequest(estado = DOCUMENTOS_LEGALES), unEmpleadoJdV())

        verify { estadoLogRepository.save(any()) }
        verify { estadoCarteraService.actualizar(oportunidad.idEmpresa) }
    }
}
```

**Cobertura obligatoria de lógica de negocio** — estos casos DEBEN tener tests unitarios:

- `calcularMontoTotal`: descuento null, cantidad null, descuento aplicado
- `actualizarEstadoCartera`: guarda de entrada, transición a cliente, a oportunidad_activa, respeto del estado manual, no-escritura cuando no cambia
- `cambiarEstado`: motivo de cierre obligatorio, permiso de facturado, detección de retroceso, advertencias de eventos recomendados
- Pronta facturación desde el log: dentro de ventana, fuera de ventana, múltiples entradas a documentos_legales
- Validación de RUC duplicado
- Snapshot de `id_vendedor` al crear oportunidad
- Evento personalizado nunca dispara cambio de estado
- Evento del catálogo que dispara: devuelve sugerencia, NO ejecuta el cambio

---

## 6. Tests de integración — endpoints con DB real

Flujo completo desde el HTTP request hasta la base de datos, con Testcontainers.

```kotlin
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class OportunidadControllerIntegrationTest {

    companion object {
        @Container
        val postgres = PostgreSQLContainer("postgres:16")

        @JvmStatic
        @DynamicPropertySource
        fun props(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url", postgres::getJdbcUrl)
            registry.add("spring.datasource.username", postgres::getUsername)
            registry.add("spring.datasource.password", postgres::getPassword)
        }
    }

    @Autowired lateinit var mockMvc: MockMvc

    @Test
    fun `crear oportunidad eleva estado_cartera de la empresa a oportunidad_activa`() {
        val empresaId = crearEmpresaEnProspeccion()

        mockMvc.post("/api/v1/oportunidades") {
            contentType = APPLICATION_JSON
            content = """{"idEmpresa": $empresaId, "idModelo": 1, "cantidad": 8}"""
            header("Authorization", "Bearer ${tokenJdV()}")
        }.andExpect { status { isCreated() } }

        mockMvc.get("/api/v1/empresas/$empresaId") {
            header("Authorization", "Bearer ${tokenJdV()}")
        }.andExpect {
            jsonPath("$.data.estadoCartera") { value("oportunidad_activa") }
        }
    }

    @Test
    fun `crear empresa con RUC duplicado devuelve 409`() {
        crearEmpresa(ruc = "20260426827")

        mockMvc.post("/api/v1/empresas") {
            contentType = APPLICATION_JSON
            content = """{"ruc": "20260426827", ...}"""
            header("Authorization", "Bearer ${tokenJdV()}")
        }.andExpect {
            status { isConflict() }
            jsonPath("$.error.code") { value("RUC_DUPLICADO") }
        }
    }
}
```

**Flujos de integración obligatorios:**

- Crear oportunidad → snapshot de vendedor, financiadora default, monto calculado, primer log, estado_cartera elevado
- Cambiar estado a facturado → estado_cartera pasa a cliente
- Retroceder desde facturado con otra oportunidad facturada → la empresa sigue siendo cliente
- Marcar evento ocurrido que dispara cambio → el estado NO cambió automáticamente (solo se devolvió sugerencia)
- Permisos: vendedor accediendo a recurso ajeno → 404 (ver SECURITY-backend.md sobre IDOR)
- Permisos: vendedor intentando pasar a facturado → 403

---

## 7. Tests de arquitectura — ArchUnit

Hacen cumplir las reglas del monolito modular (PRD §7). Corren en la suite de tests.

```kotlin
@AnalyzeClasses(packages = ["pe.quantum.crm"])
class ArquitecturaTest {

    @ArchTest
    val controllersNoAccedenRepositories =
        noClasses().that().haveSimpleNameEndingWith("Controller")
            .should().dependOnClassesThat().haveSimpleNameEndingWith("Repository")

    @ArchTest
    val sinCiclosEntreModulos =
        slices().matching("..domain.(*)..").should().beFreeOfCycles()
}
```

---

## 8. Gates de cobertura

```
Servicios de dominio (lógica de negocio): mínimo 90%
Global del backend: mínimo 75%
```

La cobertura de servicios de dominio es alta y no negociable porque ahí vive la lógica de `reglas_negocio.md`. Un bug ahí corrompe datos.

Herramienta: **Kover**. El CI falla si la cobertura baja del umbral (`./gradlew koverVerify`).

---

## 9. Convenciones de nombres

Nombres de test que describen el comportamiento, no el método. Se leen como especificación.

```kotlin
@Test
fun `cerrar oportunidad sin motivo lanza excepcion`()

@Test
fun `actualizarEstadoCartera no escribe cuando el estado no cambia`()
```

Estructura **Arrange-Act-Assert** (o Given-When-Then) con separación visual clara.

---

## 10. Tests por fase

| Fase | Tests escritos primero |
|---|---|
| Fase 0 | Login devuelve token; credenciales inválidas dan 401 genérico; `/me` requiere auth |
| Fase 1 | CRUD de catálogos; crear modelo sin aplicaciones falla; solo admin accede a admin |
| Fase 2 | RUC duplicado da 409; contacto multi-empresa no se duplica; estado_cartera manual rechaza derivados |
| Fase 3 | Toda la lógica de oportunidades: creación, monto, cambio de estado, permisos, retroceso |
| Fase 4 | Evento ocurrido sugiere pero no ejecuta; tarea de prospección con id_oportunidad null; evento personalizado no dispara |
| Fase 5 | Cálculo de hitos de prospección; ordenamiento; agregación del inicio |
| Fase 6 | Cada reporte con datos conocidos; permisos (vendedor recibe 403) |

---

## 11. Reglas finales

1. **Nunca escribir código de producción sin un test que falle primero.**
2. **Nunca commitear con tests en rojo.** `./gradlew test` debe pasar.
3. **Un test que no ha fallado nunca no es confiable.** Si pasa sin código nuevo, está mal escrito.
4. **Los tests son código de producción.** Mismo estándar: legibles, sin duplicación, bien nombrados.
5. **Ante un bug, primero el test que lo reproduce (falla), luego la corrección.** Así no vuelve.
