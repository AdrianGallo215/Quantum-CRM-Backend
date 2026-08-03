package pe.quantum.crm.domain.tareas

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
import pe.quantum.crm.domain.tareas.dto.TareaDto
import pe.quantum.crm.support.SinBaseDeDatosMocks
import java.time.LocalDateTime

/**
 * Validacion del body de tareas (contrato_api.md §12): el borde HTTP debe
 * rechazar ids no positivos y descripciones desmedidas antes de tocar el
 * servicio, con el envelope de error `VALIDACION`.
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
class TareaControllerWebMvcTest {
    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var jwtService: JwtService

    @MockkBean
    lateinit var tareaService: TareaService

    private fun token() = jwtService.generateAccessToken(empleadoId = 7, rol = "vendedor")

    private fun tareaDto() =
        TareaDto(
            id = 12, idEmpresa = 3, empresa = null, idOportunidad = null, idContacto = null,
            contacto = null, idAsignado = 7, asignado = null, idsColaboradores = emptyList(),
            colaboradores = emptyList(), tipoAccion = "llamada", estadoAccion = "pendiente",
            descripcion = "Llamar al cliente", fechaEjecucion = null, createdAt = LocalDateTime.now(),
        )

    private fun postTarea(cuerpo: String) =
        mockMvc.post("/api/v1/tareas") {
            header(HttpHeaders.AUTHORIZATION, "Bearer ${token()}")
            contentType = MediaType.APPLICATION_JSON
            content = cuerpo
        }

    @Test
    fun `POST tareas con id_empresa no positivo devuelve 400 VALIDACION`() {
        postTarea("""{"id_empresa":0,"tipo_accion":"llamada"}""").andExpect {
            status { isBadRequest() }
            jsonPath("$.error.code") { value("VALIDACION") }
        }
        verify(exactly = 0) { tareaService.crear(any(), any()) }
    }

    @Test
    fun `POST tareas con descripcion desmedida devuelve 400 VALIDACION`() {
        val descripcion = "x".repeat(5001)
        postTarea("""{"id_empresa":3,"tipo_accion":"llamada","descripcion":"$descripcion"}""").andExpect {
            status { isBadRequest() }
            jsonPath("$.error.code") { value("VALIDACION") }
            jsonPath("$.error.field") { value("descripcion") }
        }
        verify(exactly = 0) { tareaService.crear(any(), any()) }
    }

    /**
     * Los ids de colaborador NO se validan en el DTO: `List<@Positive Long>` no
     * llega a aplicarse en Kotlin (ver la nota en `CrearTareaRequest`). El body
     * pasa la validacion y es el servicio quien decide sobre cada id.
     */
    @Test
    fun `POST tareas deja pasar los ids de colaborador al servicio`() {
        every { tareaService.crear(any(), any()) } returns tareaDto()

        postTarea("""{"id_empresa":3,"tipo_accion":"llamada","ids_colaboradores":[4,0]}""").andExpect {
            status { isCreated() }
        }

        verify(exactly = 1) { tareaService.crear(match { it.idsColaboradores == listOf(4L, 0L) }, any()) }
    }

    @Test
    fun `POST tareas valida el body y sigue llegando al servicio`() {
        every { tareaService.crear(any(), any()) } returns tareaDto()

        postTarea("""{"id_empresa":3,"tipo_accion":"llamada","descripcion":"Llamar al cliente"}""").andExpect {
            status { isCreated() }
            jsonPath("$.data.id") { value(12) }
        }
        verify { tareaService.crear(any(), any()) }
    }

    @Test
    fun `PUT tareas con descripcion desmedida devuelve 400 VALIDACION`() {
        val descripcion = "x".repeat(5001)
        mockMvc.put("/api/v1/tareas/12") {
            header(HttpHeaders.AUTHORIZATION, "Bearer ${token()}")
            contentType = MediaType.APPLICATION_JSON
            content = """{"descripcion":"$descripcion"}"""
        }.andExpect {
            status { isBadRequest() }
            jsonPath("$.error.code") { value("VALIDACION") }
        }
        verify(exactly = 0) { tareaService.actualizar(any(), any(), any()) }
    }
}
