package pe.quantum.crm.domain.metasventa

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
import pe.quantum.crm.domain.metasventa.dto.MetaVentaDto
import pe.quantum.crm.shared.Paginacion
import pe.quantum.crm.shared.Paginado
import pe.quantum.crm.support.SinBaseDeDatosMocks
import java.time.Instant

/** Tests de los endpoints de metas de venta via MockMvc, sin base de datos. */
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
class MetaVentaControllerWebMvcTest {
    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var jwtService: JwtService

    @MockkBean
    lateinit var metaVentaService: MetaVentaService

    private fun tokenJdv() = jwtService.generateAccessToken(empleadoId = 2, rol = "jdv")

    private fun tokenGerencia() = jwtService.generateAccessToken(empleadoId = 1, rol = "gerencia")

    private fun metaVentaDto(estado: String = "propuesta") =
        MetaVentaDto(
            id = 9, idEmpleado = 5, empleado = null, anio = 2027,
            metaEnero = 10, metaFebrero = 10, metaMarzo = 10, metaAbril = 10, metaMayo = 10, metaJunio = 10,
            metaJulio = 10, metaAgosto = 10, metaSeptiembre = 10, metaOctubre = 10, metaNoviembre = 10, metaDiciembre = 10,
            metaAnual = 120, estado = estado, propuestoPor = null, resolutor = null, motivoRechazo = null,
            resolvedAt = null, createdAt = Instant.now(),
        )

    private val bodyAnioCompleto =
        """{"id_empleado":5,"anio":2027,"meta_enero":10,"meta_febrero":10,"meta_marzo":10,"meta_abril":10,
           "meta_mayo":10,"meta_junio":10,"meta_julio":10,"meta_agosto":10,"meta_septiembre":10,
           "meta_octubre":10,"meta_noviembre":10,"meta_diciembre":10}"""

    @Test
    fun `POST metas-venta responde 201 con el envelope`() {
        every { metaVentaService.crear(any(), any()) } returns metaVentaDto()
        mockMvc.post("/api/v1/metas-venta") {
            header(HttpHeaders.AUTHORIZATION, "Bearer ${tokenJdv()}")
            contentType = MediaType.APPLICATION_JSON
            content = bodyAnioCompleto
        }.andExpect {
            status { isCreated() }
            jsonPath("$.data.id") { value(9) }
            jsonPath("$.data.meta_anual") { value(120) }
        }
    }

    @Test
    fun `POST metas-venta sin meta_marzo responde 400 VALIDACION`() {
        mockMvc.post("/api/v1/metas-venta") {
            header(HttpHeaders.AUTHORIZATION, "Bearer ${tokenJdv()}")
            contentType = MediaType.APPLICATION_JSON
            content =
                """{"id_empleado":5,"anio":2027,"meta_enero":10,"meta_febrero":10,"meta_abril":10,
                   "meta_mayo":10,"meta_junio":10,"meta_julio":10,"meta_agosto":10,"meta_septiembre":10,
                   "meta_octubre":10,"meta_noviembre":10,"meta_diciembre":10}"""
        }.andExpect {
            status { isBadRequest() }
            jsonPath("$.error.code") { value("VALIDACION") }
        }
    }

    @Test
    fun `GET metas-venta responde 200 paginado`() {
        every { metaVentaService.listar(any(), any(), any(), any(), any(), any()) } returns
            Paginado(listOf(metaVentaDto()), Paginacion.meta(1, 20, 1))
        mockMvc.get("/api/v1/metas-venta?estado=propuesta") {
            header(HttpHeaders.AUTHORIZATION, "Bearer ${tokenGerencia()}")
        }.andExpect {
            status { isOk() }
            jsonPath("$.meta.page") { value(1) }
            jsonPath("$.data[0].id") { value(9) }
        }
    }

    @Test
    fun `PATCH aprobar responde 200 con estado aprobada`() {
        every { metaVentaService.aprobar(9, any()) } returns metaVentaDto(estado = "aprobada")
        mockMvc.patch("/api/v1/metas-venta/9/aprobar") {
            header(HttpHeaders.AUTHORIZATION, "Bearer ${tokenGerencia()}")
        }.andExpect {
            status { isOk() }
            jsonPath("$.data.estado") { value("aprobada") }
        }
    }

    @Test
    fun `PATCH rechazar sin motivo responde 400`() {
        mockMvc.patch("/api/v1/metas-venta/9/rechazar") {
            header(HttpHeaders.AUTHORIZATION, "Bearer ${tokenGerencia()}")
            contentType = MediaType.APPLICATION_JSON
            content = """{"motivo":""}"""
        }.andExpect { status { isBadRequest() } }
    }

    @Test
    fun `sin token responde 401`() {
        mockMvc.get("/api/v1/metas-venta").andExpect { status { isUnauthorized() } }
    }
}
