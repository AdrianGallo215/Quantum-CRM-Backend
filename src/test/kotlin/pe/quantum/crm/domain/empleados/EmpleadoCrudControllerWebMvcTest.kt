package pe.quantum.crm.domain.empleados

import com.ninjasquad.springmockk.MockkBean
import io.mockk.every
import io.mockk.slot
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.patch
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.put
import pe.quantum.crm.config.security.JwtService
import pe.quantum.crm.domain.empleados.dto.ActualizarEmpleadoRequest
import pe.quantum.crm.domain.empleados.dto.EmpleadoDto
import pe.quantum.crm.shared.exception.ConflictoException
import pe.quantum.crm.support.SinBaseDeDatosMocks

/**
 * Tests de validacion de `PUT /empleados/:id` (contrato_api.md §7).
 *
 * El body llegaba sin `@Valid` y sin constraints, asi que un email malformado se
 * persistia con 200: a partir de ahi ese empleado no podia volver a entrar nunca,
 * porque `LoginRequest.email` si valida el formato y su login moria en 400 antes
 * de llegar al servicio. Cuenta inutilizable salvo acceso directo a la base.
 *
 * La otra mitad del contrato es igual de importante: los campos son opcionales,
 * un `null` significa "no tocar este campo" y debe seguir siendo valido.
 */
@SpringBootTest(
    properties = [
        "spring.autoconfigure.exclude=" +
            "org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration," +
            "org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration," +
            "org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration",
    ],
)
@AutoConfigureMockMvc
@Import(SinBaseDeDatosMocks::class)
class EmpleadoCrudControllerWebMvcTest {
    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var jwtService: JwtService

    @MockkBean
    lateinit var empleadoService: EmpleadoService

    private fun tokenAdmin() = jwtService.generateAccessToken(empleadoId = 1, rol = "admin")

    private fun tokenGerencia() = jwtService.generateAccessToken(empleadoId = 2, rol = "gerencia")

    private fun tokenVendedor() = jwtService.generateAccessToken(empleadoId = 3, rol = "vendedor")

    private fun dto(email: String = "pepe@quantum.pe") =
        EmpleadoDto(
            id = 7,
            nombres = "Pepe",
            apellidos = "Quispe",
            email = email,
            rol = "vendedor",
            area = null,
            puesto = "Ejecutivo",
            activo = true,
        )

    @Test
    fun `PUT empleados con email malformado devuelve 400 y no llega al servicio`() {
        // Se stubea a proposito: sin validacion el endpoint respondia 200 y
        // persistia el email invalido, que es exactamente el fallo a evitar.
        every { empleadoService.actualizar(any(), any(), any()) } returns dto(email = "pepe")

        mockMvc.put("/api/v1/empleados/7") {
            header(HttpHeaders.AUTHORIZATION, "Bearer ${tokenAdmin()}")
            contentType = MediaType.APPLICATION_JSON
            content = """{"email":"pepe"}"""
        }.andExpect {
            status { isBadRequest() }
            jsonPath("$.error.code") { value("VALIDACION") }
            jsonPath("$.error.field") { value("email") }
        }

        verify(exactly = 0) { empleadoService.actualizar(any(), any(), any()) }
    }

    @Test
    fun `PUT empleados con nombres en blanco devuelve 400 y no llega al servicio`() {
        every { empleadoService.actualizar(any(), any(), any()) } returns dto()

        mockMvc.put("/api/v1/empleados/7") {
            header(HttpHeaders.AUTHORIZATION, "Bearer ${tokenAdmin()}")
            contentType = MediaType.APPLICATION_JSON
            content = """{"nombres":"   "}"""
        }.andExpect {
            status { isBadRequest() }
            jsonPath("$.error.code") { value("VALIDACION") }
            jsonPath("$.error.field") { value("nombres") }
        }

        verify(exactly = 0) { empleadoService.actualizar(any(), any(), any()) }
    }

    @Test
    fun `PUT empleados con un solo campo actualiza parcialmente y deja el resto en null`() {
        val request = slot<ActualizarEmpleadoRequest>()
        every { empleadoService.actualizar(7, capture(request), 1) } returns dto()

        mockMvc.put("/api/v1/empleados/7") {
            header(HttpHeaders.AUTHORIZATION, "Bearer ${tokenAdmin()}")
            contentType = MediaType.APPLICATION_JSON
            content = """{"puesto":"Ejecutivo"}"""
        }.andExpect {
            status { isOk() }
            jsonPath("$.data.id") { value(7) }
        }

        assertThat(request.captured.puesto).isEqualTo("Ejecutivo")
        assertThat(request.captured.nombres).isNull()
        assertThat(request.captured.apellidos).isNull()
        assertThat(request.captured.email).isNull()
    }

    @Test
    fun `PUT empleados con email valido presente si llega al servicio`() {
        every { empleadoService.actualizar(7, any(), 1) } returns dto(email = "nuevo@quantum.pe")

        mockMvc.put("/api/v1/empleados/7") {
            header(HttpHeaders.AUTHORIZATION, "Bearer ${tokenAdmin()}")
            contentType = MediaType.APPLICATION_JSON
            content = """{"email":"nuevo@quantum.pe"}"""
        }.andExpect {
            status { isOk() }
            jsonPath("$.data.email") { value("nuevo@quantum.pe") }
        }

        verify(exactly = 1) { empleadoService.actualizar(7, any(), 1) }
    }

    // ── D2: cobertura HTTP del CRUD (GET/POST) y de los @PreAuthorize ────────
    // Hasta ahora ningun test HTTP protegia estos endpoints: un @PreAuthorize
    // borrado por error habria pasado inadvertido. Se montan con la seguridad real
    // (igual que el resto del archivo: @SpringBootTest + @AutoConfigureMockMvc).

    private val crearEmpleadoBody =
        """{"nombres":"Luis","apellidos":"Rojas","email":"luis@quantum.pe","password":"contrasena-larga","rol":"vendedor"}"""

    @Test
    fun `GET empleados devuelve la lista`() {
        every { empleadoService.listar(true, null) } returns listOf(dto())

        mockMvc.get("/api/v1/empleados") {
            header(HttpHeaders.AUTHORIZATION, "Bearer ${tokenAdmin()}")
        }.andExpect {
            status { isOk() }
            jsonPath("$.data[0].id") { value(7) }
            jsonPath("$.data[0].email") { value("pepe@quantum.pe") }
        }
    }

    @Test
    fun `POST empleados con body valido responde 201`() {
        // B1.4: todo empleado nuevo nace con requiere_cambio_contrasena = true. El
        // DTO expuesto no lleva ese campo (contrato_api.md §7); lo que se verifica
        // aqui es que la request llega intacta al servicio, que es quien la aplica.
        every { empleadoService.crear(any(), 1) } returns dto(email = "luis@quantum.pe")

        mockMvc.post("/api/v1/empleados") {
            header(HttpHeaders.AUTHORIZATION, "Bearer ${tokenAdmin()}")
            contentType = MediaType.APPLICATION_JSON
            content = crearEmpleadoBody
        }.andExpect {
            status { isCreated() }
            jsonPath("$.data.email") { value("luis@quantum.pe") }
        }

        verify(exactly = 1) { empleadoService.crear(any(), 1) }
    }

    @Test
    fun `POST empleados con email duplicado responde 409 EMAIL_DUPLICADO`() {
        every { empleadoService.crear(any(), 1) } throws ConflictoException("EMAIL_DUPLICADO", "Ya existe un empleado con ese email")

        mockMvc.post("/api/v1/empleados") {
            header(HttpHeaders.AUTHORIZATION, "Bearer ${tokenAdmin()}")
            contentType = MediaType.APPLICATION_JSON
            content = crearEmpleadoBody
        }.andExpect {
            status { isConflict() }
            jsonPath("$.error.code") { value("EMAIL_DUPLICADO") }
        }
    }

    @Test
    fun `GET empleados con rol vendedor responde 403`() {
        mockMvc.get("/api/v1/empleados") {
            header(HttpHeaders.AUTHORIZATION, "Bearer ${tokenVendedor()}")
        }.andExpect {
            status { isForbidden() }
        }
    }

    @Test
    fun `POST empleados con rol gerencia responde 403`() {
        mockMvc.post("/api/v1/empleados") {
            header(HttpHeaders.AUTHORIZATION, "Bearer ${tokenGerencia()}")
            contentType = MediaType.APPLICATION_JSON
            content = crearEmpleadoBody
        }.andExpect {
            status { isForbidden() }
        }

        verify(exactly = 0) { empleadoService.crear(any(), any()) }
    }

    @Test
    fun `PUT empleados con rol gerencia responde 403`() {
        mockMvc.put("/api/v1/empleados/7") {
            header(HttpHeaders.AUTHORIZATION, "Bearer ${tokenGerencia()}")
            contentType = MediaType.APPLICATION_JSON
            content = """{"puesto":"Ejecutivo"}"""
        }.andExpect {
            status { isForbidden() }
        }

        verify(exactly = 0) { empleadoService.actualizar(any(), any(), any()) }
    }

    @Test
    fun `PATCH empleados activo con rol gerencia responde 403`() {
        mockMvc.patch("/api/v1/empleados/7/activo") {
            header(HttpHeaders.AUTHORIZATION, "Bearer ${tokenGerencia()}")
            contentType = MediaType.APPLICATION_JSON
            content = """{"activo":false}"""
        }.andExpect {
            status { isForbidden() }
        }

        verify(exactly = 0) { empleadoService.cambiarActivo(any(), any(), any()) }
    }
}
