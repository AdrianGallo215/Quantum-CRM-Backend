package pe.quantum.crm.domain.oportunidades

import com.ninjasquad.springmockk.MockkBean
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.verify
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.put
import pe.quantum.crm.config.security.JwtService
import pe.quantum.crm.domain.oportunidades.dto.ContactoVinculoRequest
import pe.quantum.crm.domain.oportunidades.dto.OportunidadDto
import pe.quantum.crm.shared.exception.NoEncontradoException
import pe.quantum.crm.support.SinBaseDeDatosMocks
import java.time.Instant

/**
 * Tests de `DELETE /oportunidades/:id` (contrato_api.md §10, exclusivo admin, sin cuerpo)
 * y de la validacion de los campos numericos del body (dcto, cantidad, precio_unitario).
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
class OportunidadControllerWebMvcTest {
    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var jwtService: JwtService

    @MockkBean
    lateinit var oportunidadService: OportunidadService

    @Test
    fun `DELETE oportunidades id como admin devuelve 204`() {
        every { oportunidadService.eliminar(50) } just Runs
        val token = jwtService.generateAccessToken(empleadoId = 1, rol = "admin")

        mockMvc.delete("/api/v1/oportunidades/50") {
            header(HttpHeaders.AUTHORIZATION, "Bearer $token")
        }.andExpect {
            status { isNoContent() }
        }
        verify { oportunidadService.eliminar(50) }
    }

    @Test
    fun `DELETE oportunidades id como no-admin devuelve 403`() {
        val token = jwtService.generateAccessToken(empleadoId = 2, rol = "vendedor")

        mockMvc.delete("/api/v1/oportunidades/50") {
            header(HttpHeaders.AUTHORIZATION, "Bearer $token")
        }.andExpect {
            status { isForbidden() }
            jsonPath("$.error.code") { value("PERMISO_INSUFICIENTE") }
        }
        verify(exactly = 0) { oportunidadService.eliminar(any()) }
    }

    @Test
    fun `DELETE oportunidades id inexistente devuelve 404`() {
        every { oportunidadService.eliminar(999) } throws NoEncontradoException("La oportunidad no existe")
        val token = jwtService.generateAccessToken(empleadoId = 1, rol = "admin")

        mockMvc.delete("/api/v1/oportunidades/999") {
            header(HttpHeaders.AUTHORIZATION, "Bearer $token")
        }.andExpect {
            status { isNotFound() }
            jsonPath("$.error.code") { value("NO_ENCONTRADO") }
        }
    }

    // ---------------------------------------------------------------------
    // Validacion de campos numericos del body.
    //
    // Un dcto negativo invierte el factor de descuento en MontoTotal.calcular
    // (1 - (-100/100) = 2) y duplica el monto_total, ademas de colarse por
    // PoliticaDescuento.excedeLimite, que solo compara dcto > limite. Debe
    // rechazarse en el borde, antes de llegar al servicio.
    // ---------------------------------------------------------------------

    private fun tokenVendedor() = jwtService.generateAccessToken(empleadoId = 7, rol = "vendedor")

    private fun oportunidadDto() =
        OportunidadDto(
            id = 50, idEmpresa = 3, empresa = null, idVendedor = 7, vendedor = null,
            idFinanciadora = 1, financiadora = null, idModelo = 1, modelo = null,
            estado = "evaluacion_calidda", cantidad = 8, precioUnitario = "92000.00",
            dcto = "3.00", montoTotal = "714080.00", garantia = true, fincParalelo = false,
            fichaVenta = null, driveFolderId = null, notas = null, motivoCierre = null,
            fechaCierreEstimado = null, createdAt = Instant.now(),
        )

    private fun postOportunidad(cuerpo: String) =
        mockMvc.post("/api/v1/oportunidades") {
            header(HttpHeaders.AUTHORIZATION, "Bearer ${tokenVendedor()}")
            contentType = MediaType.APPLICATION_JSON
            content = cuerpo
        }

    private fun putOportunidad(cuerpo: String) =
        mockMvc.put("/api/v1/oportunidades/50") {
            header(HttpHeaders.AUTHORIZATION, "Bearer ${tokenVendedor()}")
            contentType = MediaType.APPLICATION_JSON
            content = cuerpo
        }

    @Test
    fun `POST oportunidades con dcto negativo devuelve 400 VALIDACION`() {
        postOportunidad("""{"id_empresa":3,"id_modelo":1,"cantidad":8,"dcto":-100}""").andExpect {
            status { isBadRequest() }
            jsonPath("$.error.code") { value("VALIDACION") }
            jsonPath("$.error.field") { value("dcto") }
        }
        verify(exactly = 0) { oportunidadService.crear(any(), any()) }
    }

    @Test
    fun `POST oportunidades con dcto mayor a 100 devuelve 400 VALIDACION`() {
        postOportunidad("""{"id_empresa":3,"id_modelo":1,"cantidad":8,"dcto":150}""").andExpect {
            status { isBadRequest() }
            jsonPath("$.error.code") { value("VALIDACION") }
            jsonPath("$.error.field") { value("dcto") }
        }
        verify(exactly = 0) { oportunidadService.crear(any(), any()) }
    }

    @Test
    fun `POST oportunidades con cantidad cero devuelve 400 VALIDACION`() {
        postOportunidad("""{"id_empresa":3,"id_modelo":1,"cantidad":0}""").andExpect {
            status { isBadRequest() }
            jsonPath("$.error.code") { value("VALIDACION") }
            jsonPath("$.error.field") { value("cantidad") }
        }
        verify(exactly = 0) { oportunidadService.crear(any(), any()) }
    }

    @Test
    fun `POST oportunidades con cantidad negativa devuelve 400 VALIDACION`() {
        postOportunidad("""{"id_empresa":3,"id_modelo":1,"cantidad":-5}""").andExpect {
            status { isBadRequest() }
            jsonPath("$.error.code") { value("VALIDACION") }
        }
        verify(exactly = 0) { oportunidadService.crear(any(), any()) }
    }

    @Test
    fun `PUT oportunidades con dcto negativo devuelve 400 VALIDACION`() {
        putOportunidad("""{"dcto":-100}""").andExpect {
            status { isBadRequest() }
            jsonPath("$.error.code") { value("VALIDACION") }
            jsonPath("$.error.field") { value("dcto") }
        }
        verify(exactly = 0) { oportunidadService.actualizar(any(), any(), any()) }
    }

    @Test
    fun `PUT oportunidades con dcto mayor a 100 devuelve 400 VALIDACION`() {
        putOportunidad("""{"dcto":100.01}""").andExpect {
            status { isBadRequest() }
            jsonPath("$.error.code") { value("VALIDACION") }
        }
        verify(exactly = 0) { oportunidadService.actualizar(any(), any(), any()) }
    }

    @Test
    fun `PUT oportunidades con cantidad cero devuelve 400 VALIDACION`() {
        putOportunidad("""{"cantidad":0}""").andExpect {
            status { isBadRequest() }
            jsonPath("$.error.code") { value("VALIDACION") }
        }
        verify(exactly = 0) { oportunidadService.actualizar(any(), any(), any()) }
    }

    @Test
    fun `PUT oportunidades con precio_unitario negativo devuelve 400 VALIDACION`() {
        putOportunidad("""{"precio_unitario":-1}""").andExpect {
            status { isBadRequest() }
            jsonPath("$.error.code") { value("VALIDACION") }
        }
        verify(exactly = 0) { oportunidadService.actualizar(any(), any(), any()) }
    }

    @Test
    fun `PUT oportunidades con valores en rango sigue llegando al servicio`() {
        every { oportunidadService.actualizar(50, any(), any()) } returns oportunidadDto()

        putOportunidad("""{"cantidad":8,"precio_unitario":92000.00,"dcto":3.00}""").andExpect {
            status { isOk() }
            jsonPath("$.data.id") { value(50) }
        }
        verify { oportunidadService.actualizar(50, any(), any()) }
    }

    @Test
    fun `PUT oportunidades acepta los extremos del rango de dcto`() {
        every { oportunidadService.actualizar(50, any(), any()) } returns oportunidadDto()

        putOportunidad("""{"dcto":0}""").andExpect { status { isOk() } }
        putOportunidad("""{"dcto":100}""").andExpect { status { isOk() } }
    }

    /**
     * Contrato §10, `PUT /oportunidades/:id/contactos/:contacto_id`:
     * Body `{ "rol_en_oportunidad": "Aprobador" }`, respuesta 200. El id del
     * contacto va en la URL y el servicio lo toma de ahi; el body NO lo lleva.
     *
     * Reutilizar el DTO del POST de vinculacion metia un `id_contacto` no-nulo,
     * sin default y con `@Positive` en un body donde el contrato no lo pone: el
     * cliente que seguia el contrato recibia 400 y el endpoint solo funcionaba
     * si adivinaba que debia mandar un id que el backend descarta.
     */
    @Test
    fun `PUT contacto de oportunidad acepta el body del contrato sin id_contacto`() {
        every { oportunidadService.actualizarContacto(50, 5, "Aprobador", any()) } returns
            ContactoVinculoRequest(idContacto = 5, rolEnOportunidad = "Aprobador")

        mockMvc.put("/api/v1/oportunidades/50/contactos/5") {
            header(HttpHeaders.AUTHORIZATION, "Bearer ${tokenVendedor()}")
            contentType = MediaType.APPLICATION_JSON
            content = """{"rol_en_oportunidad":"Aprobador"}"""
        }.andExpect {
            status { isOk() }
            jsonPath("$.data.id_contacto") { value(5) }
            jsonPath("$.data.rol_en_oportunidad") { value("Aprobador") }
        }
        verify { oportunidadService.actualizarContacto(50, 5, "Aprobador", any()) }
    }

    /** El rol puede limpiarse mandando el body vacio: `rol_en_oportunidad` es opcional. */
    @Test
    fun `PUT contacto de oportunidad con body vacio limpia el rol`() {
        every { oportunidadService.actualizarContacto(50, 5, null, any()) } returns
            ContactoVinculoRequest(idContacto = 5, rolEnOportunidad = null)

        mockMvc.put("/api/v1/oportunidades/50/contactos/5") {
            header(HttpHeaders.AUTHORIZATION, "Bearer ${tokenVendedor()}")
            contentType = MediaType.APPLICATION_JSON
            content = "{}"
        }.andExpect {
            status { isOk() }
        }
        verify { oportunidadService.actualizarContacto(50, 5, null, any()) }
    }

    /** El POST de vinculacion sigue exigiendo `id_contacto`: ahi si es parte del body. */
    @Test
    fun `POST contacto de oportunidad sigue rechazando un id_contacto no positivo`() {
        mockMvc.post("/api/v1/oportunidades/50/contactos") {
            header(HttpHeaders.AUTHORIZATION, "Bearer ${tokenVendedor()}")
            contentType = MediaType.APPLICATION_JSON
            content = """{"id_contacto":0,"rol_en_oportunidad":"Aprobador"}"""
        }.andExpect {
            status { isBadRequest() }
            jsonPath("$.error.code") { value("VALIDACION") }
        }
        verify(exactly = 0) { oportunidadService.vincularContacto(any(), any(), any()) }
    }
}
