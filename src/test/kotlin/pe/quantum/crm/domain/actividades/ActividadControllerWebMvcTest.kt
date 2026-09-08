package pe.quantum.crm.domain.actividades

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
import org.springframework.test.web.servlet.post
import pe.quantum.crm.config.security.JwtService
import pe.quantum.crm.domain.actividades.dto.ActividadDto
import pe.quantum.crm.domain.actividades.dto.ComentarioDto
import pe.quantum.crm.domain.actividades.dto.HistorialFiltros
import pe.quantum.crm.shared.PageMeta
import pe.quantum.crm.shared.Paginado
import pe.quantum.crm.support.SinBaseDeDatosMocks
import java.time.Instant

/**
 * Borde HTTP del historial de actividades. Comprueba que los query params y el
 * path llegan al servicio tal y como los define el contrato, y que el tipo de
 * actividad desconocido se rechaza con 400 antes de tocar el servicio.
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
class ActividadControllerWebMvcTest {
    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var jwtService: JwtService

    @MockkBean
    lateinit var historialService: HistorialActividadService

    @MockkBean
    lateinit var comentarioService: ComentarioActividadService

    private fun token(rol: String = "gerencia") = jwtService.generateAccessToken(empleadoId = 1, rol = rol)

    private fun paginaVacia() = Paginado<ActividadDto>(emptyList(), PageMeta(1, 20, 0, 0))

    private fun comentarioDto() =
        ComentarioDto(
            id = 1,
            tipo = "tarea",
            idActividad = 5,
            texto = "Llame y no contesto",
            createdAt = Instant.now(),
            createdBy = 1,
            autor = null,
        )

    @Test
    fun `GET actividades pasa los filtros al servicio tal como llegan`() {
        every { historialService.historial(any(), any(), any(), any()) } returns paginaVacia()

        mockMvc
            .get("/api/v1/actividades") {
                header(HttpHeaders.AUTHORIZATION, "Bearer ${token()}")
                param("id_empleado", "7")
                param("tipo", "tarea")
                param("id_empresa", "3")
                param("per_page", "50")
            }.andExpect {
                status { isOk() }
                jsonPath("$.error") { value(null) }
            }

        verify {
            historialService.historial(
                HistorialFiltros(idEmpleado = 7, tipo = "tarea", idEmpresa = 3),
                any(),
                null,
                50,
            )
        }
    }

    @Test
    fun `GET actividades sin id_empleado devuelve 400`() {
        mockMvc
            .get("/api/v1/actividades") {
                header(HttpHeaders.AUTHORIZATION, "Bearer ${token()}")
            }.andExpect {
                status { isBadRequest() }
            }

        verify(exactly = 0) { historialService.historial(any(), any(), any(), any()) }
    }

    @Test
    fun `un tipo de actividad desconocido es 400 VALIDACION y no llega al servicio`() {
        mockMvc
            .get("/api/v1/actividades/inventado/5/comentarios") {
                header(HttpHeaders.AUTHORIZATION, "Bearer ${token()}")
            }.andExpect {
                status { isBadRequest() }
                jsonPath("$.error.code") { value("VALIDACION") }
                jsonPath("$.error.field") { value("tipo") }
            }

        verify(exactly = 0) { comentarioService.listar(any(), any(), any()) }
    }

    @Test
    fun `POST comentario devuelve 201 y traduce el tipo del path`() {
        every { comentarioService.crear(TipoActividad.tarea, 5, "Llame y no contesto", any()) } returns comentarioDto()

        mockMvc
            .post("/api/v1/actividades/tarea/5/comentarios") {
                header(HttpHeaders.AUTHORIZATION, "Bearer ${token()}")
                contentType = MediaType.APPLICATION_JSON
                content = """{"texto":"Llame y no contesto"}"""
            }.andExpect {
                status { isCreated() }
                jsonPath("$.data.texto") { value("Llame y no contesto") }
            }
    }

    @Test
    fun `POST comentario con texto vacio devuelve 400 VALIDACION`() {
        mockMvc
            .post("/api/v1/actividades/tarea/5/comentarios") {
                header(HttpHeaders.AUTHORIZATION, "Bearer ${token()}")
                contentType = MediaType.APPLICATION_JSON
                content = """{"texto":"   "}"""
            }.andExpect {
                status { isBadRequest() }
                jsonPath("$.error.code") { value("VALIDACION") }
            }

        verify(exactly = 0) { comentarioService.crear(any(), any(), any(), any()) }
    }

    @Test
    fun `POST comentario con texto desmedido devuelve 400 VALIDACION`() {
        mockMvc
            .post("/api/v1/actividades/evento/4/comentarios") {
                header(HttpHeaders.AUTHORIZATION, "Bearer ${token()}")
                contentType = MediaType.APPLICATION_JSON
                content = """{"texto":"${"x".repeat(5001)}"}"""
            }.andExpect {
                status { isBadRequest() }
                jsonPath("$.error.code") { value("VALIDACION") }
                jsonPath("$.error.field") { value("texto") }
            }

        verify(exactly = 0) { comentarioService.crear(any(), any(), any(), any()) }
    }

    @Test
    fun `GET auditoria traduce el tipo evento del path`() {
        every { historialService.auditoria(TipoActividad.evento, 4, any()) } returns emptyList()

        mockMvc
            .get("/api/v1/actividades/evento/4/auditoria") {
                header(HttpHeaders.AUTHORIZATION, "Bearer ${token()}")
            }.andExpect {
                status { isOk() }
            }

        verify { historialService.auditoria(TipoActividad.evento, 4, any()) }
    }

    @Test
    fun `sin token la peticion es 401`() {
        mockMvc.get("/api/v1/actividades") { param("id_empleado", "7") }.andExpect {
            status { isUnauthorized() }
        }
    }
}
