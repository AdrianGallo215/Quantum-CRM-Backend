package pe.quantum.crm.domain.solicitudes

import com.ninjasquad.springmockk.MockkBean
import io.mockk.every
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
import pe.quantum.crm.config.security.JwtService
import pe.quantum.crm.domain.solicitudes.dto.SolicitudDto
import pe.quantum.crm.shared.Paginacion
import pe.quantum.crm.shared.Paginado
import pe.quantum.crm.support.SinBaseDeDatosMocks
import java.time.Instant

/** Tests de los endpoints de solicitudes via MockMvc, sin base de datos. */
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
class SolicitudControllerWebMvcTest {
    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var jwtService: JwtService

    @MockkBean
    lateinit var solicitudService: SolicitudService

    private fun tokenVendedor() = jwtService.generateAccessToken(empleadoId = 5, rol = "vendedor")

    private fun tokenGerencia() = jwtService.generateAccessToken(empleadoId = 1, rol = "gerencia")

    private fun solicitudDto(estado: String = "pendiente") =
        SolicitudDto(
            id = 7,
            tipo = "descuento",
            estado = estado,
            rolAprobador = "jdv",
            entidadTipo = "oportunidad",
            entidadId = 45,
            entidadDescripcion = "ABC — Oportunidad #45",
            dctoSolicitado = "5.00",
            idVendedorNuevo = null,
            vendedorNuevo = null,
            motivo = "Cliente frecuente",
            solicitante = null,
            resolutor = null,
            motivoResolucion = null,
            resolvedAt = null,
            createdAt = Instant.now(),
        )

    @Test
    fun `POST solicitudes responde 201 con el envelope`() {
        every { solicitudService.crear(any(), any()) } returns solicitudDto()
        mockMvc.post("/api/v1/solicitudes") {
            header(HttpHeaders.AUTHORIZATION, "Bearer ${tokenVendedor()}")
            contentType = MediaType.APPLICATION_JSON
            content =
                """{"tipo":"descuento","entidad_tipo":"oportunidad","entidad_id":45,
                   "dcto_solicitado":"5.00","motivo":"Cliente frecuente"}"""
        }.andExpect {
            status { isCreated() }
            jsonPath("$.data.id") { value(7) }
            jsonPath("$.data.rol_aprobador") { value("jdv") }
        }
    }

    @Test
    fun `POST solicitudes sin motivo responde 400 VALIDACION`() {
        mockMvc.post("/api/v1/solicitudes") {
            header(HttpHeaders.AUTHORIZATION, "Bearer ${tokenVendedor()}")
            contentType = MediaType.APPLICATION_JSON
            content = """{"tipo":"descuento","entidad_tipo":"oportunidad","entidad_id":45,"dcto_solicitado":"5.00"}"""
        }.andExpect {
            status { isBadRequest() }
            jsonPath("$.error.code") { value("VALIDACION") }
        }
    }

    @Test
    fun `GET solicitudes responde 200 paginado`() {
        every { solicitudService.listar(any(), any(), any(), any(), any(), any()) } returns
            Paginado(listOf(solicitudDto()), Paginacion.meta(1, 20, 1))
        mockMvc.get("/api/v1/solicitudes?estado=pendiente") {
            header(HttpHeaders.AUTHORIZATION, "Bearer ${tokenGerencia()}")
        }.andExpect {
            status { isOk() }
            jsonPath("$.meta.page") { value(1) }
            jsonPath("$.data[0].id") { value(7) }
        }
    }

    @Test
    fun `PATCH aprobar responde 200 con estado aprobada`() {
        every { solicitudService.aprobar(7, any()) } returns solicitudDto(estado = "aprobada")
        mockMvc.patch("/api/v1/solicitudes/7/aprobar") {
            header(HttpHeaders.AUTHORIZATION, "Bearer ${tokenGerencia()}")
        }.andExpect {
            status { isOk() }
            jsonPath("$.data.estado") { value("aprobada") }
        }
    }

    @Test
    fun `PATCH denegar sin motivo responde 400`() {
        mockMvc.patch("/api/v1/solicitudes/7/denegar") {
            header(HttpHeaders.AUTHORIZATION, "Bearer ${tokenGerencia()}")
            contentType = MediaType.APPLICATION_JSON
            content = """{"motivo":""}"""
        }.andExpect { status { isBadRequest() } }
    }

    @Test
    fun `sin token responde 401`() {
        mockMvc.get("/api/v1/solicitudes").andExpect { status { isUnauthorized() } }
    }
}
