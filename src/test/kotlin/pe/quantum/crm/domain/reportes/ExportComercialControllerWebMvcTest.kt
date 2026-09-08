package pe.quantum.crm.domain.reportes

import com.ninjasquad.springmockk.MockkBean
import io.mockk.every
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.http.HttpHeaders
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import pe.quantum.crm.config.security.JwtService
import pe.quantum.crm.support.SinBaseDeDatosMocks

/**
 * Borde HTTP del export comercial (plan-15): cabeceras de descarga, tipo de
 * contenido y el reparto de permisos que hereda de `ReporteController`.
 *
 * Sin base de datos: el servicio va mockeado. Lo que se prueba aqui es el
 * contrato HTTP, no el SQL (eso es ExportComercialServiceIntegrationTest).
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
class ExportComercialControllerWebMvcTest {
    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var jwtService: JwtService

    @MockkBean
    lateinit var exportComercialService: ExportComercialService

    @MockkBean
    lateinit var reporteService: ReporteService

    private fun token(rol: String) = jwtService.generateAccessToken(empleadoId = 1, rol = rol)

    @Test
    fun `gerencia descarga el export como archivo xlsx adjunto`() {
        every { exportComercialService.filas(any(), any()) } returns emptyList()

        mockMvc
            .get("/api/v1/reportes/exportar-comercial") {
                header(HttpHeaders.AUTHORIZATION, "Bearer ${token("gerencia")}")
            }.andExpect {
                status { isOk() }
                header {
                    string(
                        HttpHeaders.CONTENT_TYPE,
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                    )
                }
                header { string(HttpHeaders.CONTENT_DISPOSITION, org.hamcrest.Matchers.containsString("attachment")) }
                header { string(HttpHeaders.CONTENT_DISPOSITION, org.hamcrest.Matchers.containsString(".xlsx")) }
            }
    }

    @Test
    fun `admin tambien puede descargar el export`() {
        every { exportComercialService.filas(any(), any()) } returns emptyList()

        mockMvc
            .get("/api/v1/reportes/exportar-comercial") {
                header(HttpHeaders.AUTHORIZATION, "Bearer ${token("admin")}")
            }.andExpect { status { isOk() } }
    }

    @Test
    fun `jdv puede descargar el export con vision total`() {
        // Decision de producto del 2026-09-08: jdv entra, sin acotar a su equipo.
        // Es el mismo acceso que ya tiene a los otros seis reportes.
        every { exportComercialService.filas(any(), any()) } returns emptyList()

        mockMvc
            .get("/api/v1/reportes/exportar-comercial") {
                header(HttpHeaders.AUTHORIZATION, "Bearer ${token("jdv")}")
            }.andExpect { status { isOk() } }
    }

    @Test
    fun `un vendedor no puede descargar el export`() {
        mockMvc
            .get("/api/v1/reportes/exportar-comercial") {
                header(HttpHeaders.AUTHORIZATION, "Bearer ${token("vendedor")}")
            }.andExpect { status { isForbidden() } }
    }

    @Test
    fun `un analista no puede descargar el export`() {
        mockMvc
            .get("/api/v1/reportes/exportar-comercial") {
                header(HttpHeaders.AUTHORIZATION, "Bearer ${token("analista")}")
            }.andExpect { status { isForbidden() } }
    }

    @Test
    fun `sin token el export responde 401`() {
        mockMvc
            .get("/api/v1/reportes/exportar-comercial")
            .andExpect { status { isUnauthorized() } }
    }

    @Test
    fun `las fechas del query llegan al servicio tal como se envian`() {
        every { exportComercialService.filas(any(), any()) } returns emptyList()

        mockMvc
            .get("/api/v1/reportes/exportar-comercial") {
                header(HttpHeaders.AUTHORIZATION, "Bearer ${token("gerencia")}")
                param("fecha_desde", "2026-01-01")
                param("fecha_hasta", "2026-06-30")
            }.andExpect { status { isOk() } }

        io.mockk.verify {
            exportComercialService.filas(
                java.time.LocalDate.of(2026, 1, 1),
                java.time.LocalDate.of(2026, 6, 30),
            )
        }
    }

    @Test
    fun `sin fechas el servicio recibe nulos y devuelve todo el historico`() {
        // A diferencia del resto de reportes (§18, default = mes actual), este
        // export sin fechas trae TODO. Un export de control que por defecto
        // recorta a un mes se lee como datos faltantes.
        every { exportComercialService.filas(any(), any()) } returns emptyList()

        mockMvc
            .get("/api/v1/reportes/exportar-comercial") {
                header(HttpHeaders.AUTHORIZATION, "Bearer ${token("gerencia")}")
            }.andExpect { status { isOk() } }

        io.mockk.verify { exportComercialService.filas(null, null) }
    }
}
