package pe.quantum.crm.domain.eventos

import com.ninjasquad.springmockk.MockkBean
import io.mockk.every
import io.mockk.verify
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.put
import pe.quantum.crm.config.security.JwtService
import pe.quantum.crm.domain.eventos.dto.EventoDto
import pe.quantum.crm.support.SinBaseDeDatosMocks

/**
 * Validacion del body de eventos (contrato_api.md §11): el borde HTTP acota los
 * textos libres y el id de catalogo antes de llegar al servicio.
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
class EventoControllerWebMvcTest {
    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var jwtService: JwtService

    @MockkBean
    lateinit var eventoService: EventoService

    private fun token() = jwtService.generateAccessToken(empleadoId = 7, rol = "vendedor")

    private fun eventoDto() =
        EventoDto(
            id = 4, idOportunidad = 50, idEmpresa = null, idCatalogoEvento = 2,
            nombre = "Firma de contrato", esPersonalizado = false, descripcion = null,
            estado = "pendiente", fechaEstimada = null, fechaSeguimiento = null,
            fechaOcurrencia = null, disparaCambioEstado = false, estadoDestino = null,
            esRecomendado = false, etapaAsociada = null, esHitoProspeccion = false,
        )

    private fun postEvento(cuerpo: String) =
        mockMvc.post("/api/v1/oportunidades/50/eventos") {
            header(HttpHeaders.AUTHORIZATION, "Bearer ${token()}")
            contentType = MediaType.APPLICATION_JSON
            content = cuerpo
        }

    @Test
    fun `POST eventos con id_catalogo_evento no positivo devuelve 400 VALIDACION`() {
        postEvento("""{"id_catalogo_evento":0}""").andExpect {
            status { isBadRequest() }
            jsonPath("$.error.code") { value("VALIDACION") }
        }
        verify(exactly = 0) { eventoService.crearEnOportunidad(any(), any(), any()) }
    }

    @Test
    fun `POST eventos con nombre_personalizado desmedido devuelve 400 VALIDACION`() {
        val nombre = "x".repeat(201)
        postEvento("""{"es_personalizado":true,"nombre_personalizado":"$nombre"}""").andExpect {
            status { isBadRequest() }
            jsonPath("$.error.code") { value("VALIDACION") }
            jsonPath("$.error.field") { value("nombrePersonalizado") }
        }
        verify(exactly = 0) { eventoService.crearEnOportunidad(any(), any(), any()) }
    }

    @Test
    fun `POST eventos valido sigue llegando al servicio`() {
        every { eventoService.crearEnOportunidad(50, any(), any()) } returns eventoDto()

        postEvento("""{"id_catalogo_evento":2}""").andExpect {
            status { isCreated() }
            jsonPath("$.data.id") { value(4) }
        }
        verify { eventoService.crearEnOportunidad(50, any(), any()) }
    }

    @Test
    fun `PUT eventos con descripcion desmedida devuelve 400 VALIDACION`() {
        val descripcion = "x".repeat(5001)
        mockMvc.put("/api/v1/eventos/4") {
            header(HttpHeaders.AUTHORIZATION, "Bearer ${token()}")
            contentType = MediaType.APPLICATION_JSON
            content = """{"descripcion":"$descripcion"}"""
        }.andExpect {
            status { isBadRequest() }
            jsonPath("$.error.code") { value("VALIDACION") }
        }
        verify(exactly = 0) { eventoService.actualizar(any(), any(), any()) }
    }
}
