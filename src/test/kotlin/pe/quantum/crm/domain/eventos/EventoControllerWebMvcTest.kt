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
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.patch
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.put
import pe.quantum.crm.config.security.JwtService
import pe.quantum.crm.domain.eventos.dto.EventoDto
import pe.quantum.crm.domain.eventos.dto.EventoOcurridoDto
import pe.quantum.crm.domain.eventos.dto.EventosAgrupadosDto
import pe.quantum.crm.domain.eventos.dto.SugerenciaDto
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

    @Test
    fun `PUT eventos valido llega al servicio con el id del path`() {
        every { eventoService.actualizar(4, any(), any()) } returns eventoDto()

        mockMvc.put("/api/v1/eventos/4") {
            header(HttpHeaders.AUTHORIZATION, "Bearer ${token()}")
            contentType = MediaType.APPLICATION_JSON
            content = """{"fecha_estimada":"2026-08-01"}"""
        }.andExpect {
            status { isOk() }
            jsonPath("$.data.id") { value(4) }
        }
        verify { eventoService.actualizar(4, any(), any()) }
    }

    // ── listados (contrato §11) ────────────────────────────────

    private fun agrupados() = EventosAgrupadosDto(pendientes = listOf(eventoDto()), ocurridos = emptyList(), descartados = emptyList())

    @Test
    fun `GET eventos de una oportunidad devuelve los tres grupos`() {
        every { eventoService.listarPorOportunidad(50, any()) } returns agrupados()

        mockMvc.get("/api/v1/oportunidades/50/eventos") {
            header(HttpHeaders.AUTHORIZATION, "Bearer ${token()}")
        }.andExpect {
            status { isOk() }
            jsonPath("$.data.pendientes[0].id") { value(4) }
            jsonPath("$.data.ocurridos") { isEmpty() }
            jsonPath("$.data.descartados") { isEmpty() }
        }
        verify { eventoService.listarPorOportunidad(50, any()) }
    }

    @Test
    fun `GET eventos de una empresa usa la ruta de prospeccion`() {
        every { eventoService.listarPorEmpresa(10, any()) } returns agrupados()

        mockMvc.get("/api/v1/empresas/10/eventos") {
            header(HttpHeaders.AUTHORIZATION, "Bearer ${token()}")
        }.andExpect {
            status { isOk() }
            jsonPath("$.data.pendientes[0].id") { value(4) }
        }
        verify { eventoService.listarPorEmpresa(10, any()) }
    }

    @Test
    fun `POST eventos de empresa crea el hito de prospeccion`() {
        every { eventoService.crearEnEmpresa(10, any(), any()) } returns eventoDto()

        mockMvc.post("/api/v1/empresas/10/eventos") {
            header(HttpHeaders.AUTHORIZATION, "Bearer ${token()}")
            contentType = MediaType.APPLICATION_JSON
            content = """{"id_catalogo_evento":2}"""
        }.andExpect {
            status { isCreated() }
            jsonPath("$.data.id") { value(4) }
        }
        verify { eventoService.crearEnEmpresa(10, any(), any()) }
    }

    // ── transiciones (contrato §11) ────────────────────────────

    @Test
    fun `PATCH ocurrido devuelve la sugerencia tal cual la da el servicio`() {
        val sugerencia =
            SugerenciaDto(dispara = true, estadoDestino = "facturado", mensaje = "¿Deseas mover la oportunidad a Facturado?")
        every { eventoService.marcarOcurrido(4, any(), any()) } returns
            EventoOcurridoDto(id = 4, estado = "ocurrido", fechaOcurrencia = null, sugerencia = sugerencia)

        mockMvc.patch("/api/v1/eventos/4/ocurrido") {
            header(HttpHeaders.AUTHORIZATION, "Bearer ${token()}")
            contentType = MediaType.APPLICATION_JSON
            content = """{"descripcion":"Calidda desembolsó"}"""
        }.andExpect {
            status { isOk() }
            jsonPath("$.data.sugerencia.dispara") { value(true) }
            jsonPath("$.data.sugerencia.estado_destino") { value("facturado") }
        }
        verify { eventoService.marcarOcurrido(4, any(), any()) }
    }

    @Test
    fun `PATCH ocurrido sin body usa un request vacio`() {
        every { eventoService.marcarOcurrido(4, any(), any()) } returns
            EventoOcurridoDto(id = 4, estado = "ocurrido", fechaOcurrencia = null, sugerencia = null)

        mockMvc.patch("/api/v1/eventos/4/ocurrido") {
            header(HttpHeaders.AUTHORIZATION, "Bearer ${token()}")
        }.andExpect {
            status { isOk() }
            jsonPath("$.data.estado") { value("ocurrido") }
        }
        verify { eventoService.marcarOcurrido(4, match { it.descripcion == null && it.fechaOcurrencia == null }, any()) }
    }

    @Test
    fun `PATCH descartado sin body descarta igualmente`() {
        every { eventoService.marcarDescartado(4, any(), any()) } returns eventoDto()

        mockMvc.patch("/api/v1/eventos/4/descartado") {
            header(HttpHeaders.AUTHORIZATION, "Bearer ${token()}")
        }.andExpect {
            status { isOk() }
            jsonPath("$.data.id") { value(4) }
        }
        verify { eventoService.marcarDescartado(4, match { it.descripcion == null }, any()) }
    }
}
