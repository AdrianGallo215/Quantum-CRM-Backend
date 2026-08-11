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
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.patch
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.put
import pe.quantum.crm.config.security.JwtService
import pe.quantum.crm.domain.tareas.dto.TareaDto
import pe.quantum.crm.shared.PageMeta
import pe.quantum.crm.shared.Paginado
import pe.quantum.crm.support.SinBaseDeDatosMocks
import java.time.Instant

/**
 * Validacion del body de tareas (contrato_api.md §12): el borde HTTP debe
 * rechazar ids no positivos y descripciones desmedidas antes de tocar el
 * servicio, con el envelope de error `VALIDACION`. Los tests de listado y de
 * transiciones comprueban ademas que los query params y el path llegan al
 * servicio tal y como los define el contrato.
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
            descripcion = "Llamar al cliente", fechaEjecucion = null, createdAt = Instant.now(),
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

    @Test
    fun `PUT tareas valido llega al servicio con el id del path`() {
        every { tareaService.actualizar(12, any(), any()) } returns tareaDto()

        mockMvc.put("/api/v1/tareas/12") {
            header(HttpHeaders.AUTHORIZATION, "Bearer ${token()}")
            contentType = MediaType.APPLICATION_JSON
            content = """{"descripcion":"Llamar al cliente","tipo_accion":"correo"}"""
        }.andExpect {
            status { isOk() }
            jsonPath("$.data.id") { value(12) }
        }
        verify { tareaService.actualizar(12, match { it.descripcion == "Llamar al cliente" }, any()) }
    }

    // ── listado (contrato §12) ─────────────────────────────────

    private fun paginado() = Paginado(listOf(tareaDto()), PageMeta(page = 2, perPage = 5, total = 7, totalPages = 2))

    @Test
    fun `GET tareas traslada todos los query params del contrato a los filtros`() {
        every { tareaService.listar(any(), any(), any(), any(), any(), any()) } returns paginado()

        mockMvc.get("/api/v1/tareas") {
            header(HttpHeaders.AUTHORIZATION, "Bearer ${token()}")
            param("id_empresa", "3")
            param("id_oportunidad", "50")
            param("estado_accion", "pendiente")
            param("id_asignado", "7")
            param("solo_prospeccion", "true")
            param("vencidas", "true")
            param("page", "2")
            param("per_page", "5")
            param("sort", "fecha_ejecucion")
            param("dir", "asc")
        }.andExpect {
            status { isOk() }
            jsonPath("$.data[0].id") { value(12) }
            jsonPath("$.meta.page") { value(2) }
            jsonPath("$.meta.per_page") { value(5) }
            jsonPath("$.meta.total") { value(7) }
        }

        verify {
            tareaService.listar(
                match {
                    it.idEmpresa == 3L && it.idOportunidad == 50L && it.estadoAccion == "pendiente" &&
                        it.idAsignado == 7L && it.soloProspeccion && it.vencidas
                },
                any(),
                2,
                5,
                "fecha_ejecucion",
                "asc",
            )
        }
    }

    @Test
    fun `GET tareas sin query params usa filtros vacios y paginacion por defecto`() {
        every { tareaService.listar(any(), any(), any(), any(), any(), any()) } returns paginado()

        mockMvc.get("/api/v1/tareas") {
            header(HttpHeaders.AUTHORIZATION, "Bearer ${token()}")
        }.andExpect {
            status { isOk() }
        }

        verify {
            tareaService.listar(
                match {
                    it.idEmpresa == null && it.idOportunidad == null && it.estadoAccion == null &&
                        it.idAsignado == null && !it.soloProspeccion && !it.vencidas
                },
                any(),
                null,
                null,
                null,
                null,
            )
        }
    }

    // ── transiciones (contrato §12) ────────────────────────────

    @Test
    fun `PATCH completada con descripcion la reenvia al servicio`() {
        every { tareaService.completar(12, any(), any()) } returns tareaDto()

        mockMvc.patch("/api/v1/tareas/12/completada") {
            header(HttpHeaders.AUTHORIZATION, "Bearer ${token()}")
            contentType = MediaType.APPLICATION_JSON
            content = """{"descripcion":"Contestó el gerente"}"""
        }.andExpect {
            status { isOk() }
            jsonPath("$.data.id") { value(12) }
        }
        verify { tareaService.completar(12, "Contestó el gerente", any()) }
    }

    @Test
    fun `PATCH completada sin body completa la tarea sin descripcion`() {
        every { tareaService.completar(12, null, any()) } returns tareaDto()

        mockMvc.patch("/api/v1/tareas/12/completada") {
            header(HttpHeaders.AUTHORIZATION, "Bearer ${token()}")
        }.andExpect {
            status { isOk() }
        }
        verify { tareaService.completar(12, null, any()) }
    }

    @Test
    fun `PATCH cancelada llega al servicio con el id del path`() {
        every { tareaService.cancelar(12, any()) } returns tareaDto()

        mockMvc.patch("/api/v1/tareas/12/cancelada") {
            header(HttpHeaders.AUTHORIZATION, "Bearer ${token()}")
        }.andExpect {
            status { isOk() }
        }
        verify { tareaService.cancelar(12, any()) }
    }
}
