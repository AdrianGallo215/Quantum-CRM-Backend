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
import pe.quantum.crm.domain.oportunidades.dto.OportunidadItemDto
import pe.quantum.crm.shared.exception.ConflictoException
import pe.quantum.crm.support.SinBaseDeDatosMocks

/**
 * Tests de `/oportunidades/:id/items` (plan-06-migrar-dominio-items.md, B4).
 * Sigue el mismo patron que `OportunidadControllerWebMvcTest`.
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
class OportunidadItemControllerWebMvcTest {
    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var jwtService: JwtService

    @MockkBean
    lateinit var oportunidadItemService: OportunidadItemService

    private fun tokenVendedor() = jwtService.generateAccessToken(empleadoId = 7, rol = "vendedor")

    private fun itemDto() =
        OportunidadItemDto(
            id = 5,
            idModelo = 1,
            modelo = null,
            cantidad = 8,
            precioVenta = "92000.00",
            descuento = "3.00",
            cuotaFinanciadora = "0.00",
            montoItem = "714080.00",
        )

    @Test
    fun `POST oportunidades id items con body valido devuelve 201`() {
        every { oportunidadItemService.crear(50, any(), any()) } returns itemDto()

        mockMvc.post("/api/v1/oportunidades/50/items") {
            header(HttpHeaders.AUTHORIZATION, "Bearer ${tokenVendedor()}")
            contentType = MediaType.APPLICATION_JSON
            content = """{"id_modelo":1,"cantidad":8,"precio_venta":92000.00,"descuento":3.00}"""
        }.andExpect {
            status { isCreated() }
            jsonPath("$.data.id") { value(5) }
        }
        verify { oportunidadItemService.crear(50, any(), any()) }
    }

    @Test
    fun `PUT oportunidades id items itemId con body valido devuelve 200`() {
        every { oportunidadItemService.actualizar(5, any(), any()) } returns itemDto()

        mockMvc.put("/api/v1/oportunidades/50/items/5") {
            header(HttpHeaders.AUTHORIZATION, "Bearer ${tokenVendedor()}")
            contentType = MediaType.APPLICATION_JSON
            content = """{"cantidad":8}"""
        }.andExpect {
            status { isOk() }
            jsonPath("$.data.id") { value(5) }
        }
        verify { oportunidadItemService.actualizar(5, any(), any()) }
    }

    @Test
    fun `DELETE oportunidades id items itemId devuelve 204`() {
        every { oportunidadItemService.eliminar(5, any()) } just Runs

        mockMvc.delete("/api/v1/oportunidades/50/items/5") {
            header(HttpHeaders.AUTHORIZATION, "Bearer ${tokenVendedor()}")
        }.andExpect {
            status { isNoContent() }
        }
        verify { oportunidadItemService.eliminar(5, any()) }
    }

    /** D17: el ultimo item de una oportunidad no se puede eliminar (409). */
    @Test
    fun `DELETE oportunidades id items itemId del ultimo item devuelve 409`() {
        every { oportunidadItemService.eliminar(5, any()) } throws
            ConflictoException("ULTIMO_ITEM_NO_ELIMINABLE", "La oportunidad debe tener al menos un ítem")

        mockMvc.delete("/api/v1/oportunidades/50/items/5") {
            header(HttpHeaders.AUTHORIZATION, "Bearer ${tokenVendedor()}")
        }.andExpect {
            status { isConflict() }
            jsonPath("$.error.code") { value("ULTIMO_ITEM_NO_ELIMINABLE") }
        }
    }

    @Test
    fun `POST oportunidades id items sin token responde 401`() {
        mockMvc.post("/api/v1/oportunidades/50/items") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"id_modelo":1,"cantidad":8}"""
        }.andExpect { status { isUnauthorized() } }
    }

    @Test
    fun `PUT oportunidades id items itemId sin token responde 401`() {
        mockMvc.put("/api/v1/oportunidades/50/items/5") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"cantidad":8}"""
        }.andExpect { status { isUnauthorized() } }
    }

    @Test
    fun `DELETE oportunidades id items itemId sin token responde 401`() {
        mockMvc.delete("/api/v1/oportunidades/50/items/5").andExpect { status { isUnauthorized() } }
    }

    // ---------------------------------------------------------------------
    // Validacion de campos numericos del body del PUT de item.
    //
    // Movidos desde OportunidadControllerWebMvcTest en B9: hasta D19 estos
    // campos viajaban en `PUT /oportunidades/:id`; ahora el unico sitio donde
    // se editan es el item, y el borde debe seguir rechazandolos igual.
    //
    // Un descuento negativo invierte el factor de MontoTotal.calcular
    // (1 - (-100/100) = 2) y duplica el monto, ademas de colarse por
    // PoliticaDescuento.excedeLimite, que solo compara descuento > limite.
    // ---------------------------------------------------------------------

    private fun putItem(cuerpo: String) =
        mockMvc.put("/api/v1/oportunidades/50/items/5") {
            header(HttpHeaders.AUTHORIZATION, "Bearer ${tokenVendedor()}")
            contentType = MediaType.APPLICATION_JSON
            content = cuerpo
        }

    @Test
    fun `PUT item con descuento negativo devuelve 400 VALIDACION`() {
        putItem("""{"descuento":-100}""").andExpect {
            status { isBadRequest() }
            jsonPath("$.error.code") { value("VALIDACION") }
            jsonPath("$.error.field") { value("descuento") }
        }
        verify(exactly = 0) { oportunidadItemService.actualizar(any(), any(), any()) }
    }

    @Test
    fun `PUT item con descuento mayor a 100 devuelve 400 VALIDACION`() {
        putItem("""{"descuento":100.01}""").andExpect {
            status { isBadRequest() }
            jsonPath("$.error.code") { value("VALIDACION") }
        }
        verify(exactly = 0) { oportunidadItemService.actualizar(any(), any(), any()) }
    }

    @Test
    fun `PUT item con cantidad cero devuelve 400 VALIDACION`() {
        putItem("""{"cantidad":0}""").andExpect {
            status { isBadRequest() }
            jsonPath("$.error.code") { value("VALIDACION") }
        }
        verify(exactly = 0) { oportunidadItemService.actualizar(any(), any(), any()) }
    }

    @Test
    fun `PUT item con precio_venta negativo devuelve 400 VALIDACION`() {
        putItem("""{"precio_venta":-1}""").andExpect {
            status { isBadRequest() }
            jsonPath("$.error.code") { value("VALIDACION") }
        }
        verify(exactly = 0) { oportunidadItemService.actualizar(any(), any(), any()) }
    }

    @Test
    fun `PUT item con valores en rango sigue llegando al servicio`() {
        every { oportunidadItemService.actualizar(5, any(), any()) } returns itemDto()

        putItem("""{"cantidad":8,"precio_venta":92000.00,"descuento":3.00}""").andExpect {
            status { isOk() }
            jsonPath("$.data.id") { value(5) }
        }
        verify { oportunidadItemService.actualizar(5, any(), any()) }
    }

    @Test
    fun `PUT item acepta los extremos del rango de descuento`() {
        every { oportunidadItemService.actualizar(5, any(), any()) } returns itemDto()

        putItem("""{"descuento":0}""").andExpect { status { isOk() } }
        putItem("""{"descuento":100}""").andExpect { status { isOk() } }
    }
}
