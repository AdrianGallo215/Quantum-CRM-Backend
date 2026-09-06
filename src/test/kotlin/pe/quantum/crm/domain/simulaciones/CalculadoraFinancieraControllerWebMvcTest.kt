package pe.quantum.crm.domain.simulaciones

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
import org.springframework.test.web.servlet.post
import pe.quantum.crm.config.security.JwtService
import pe.quantum.crm.domain.simulaciones.dto.CalculadoraDto
import pe.quantum.crm.domain.simulaciones.dto.CronogramaDto
import pe.quantum.crm.domain.simulaciones.dto.FilaCronogramaDto
import pe.quantum.crm.shared.exception.ValidacionException
import pe.quantum.crm.support.SinBaseDeDatosMocks

/** Tests del endpoint de la Calculadora Financiera via MockMvc, sin base de datos. */
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
class CalculadoraFinancieraControllerWebMvcTest {
    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var jwtService: JwtService

    @MockkBean
    lateinit var calculadoraFinancieraService: CalculadoraFinancieraService

    private fun tokenAnalista() = jwtService.generateAccessToken(empleadoId = 1, rol = "analista")

    private fun calculadoraDto() =
        CalculadoraDto(
            empresa = null,
            modelo = null,
            cronograma =
                CronogramaDto(
                    cuotaFinal = "1548.86",
                    cuotaFinanciera = "1548.86",
                    valorVenta = "93220.34",
                    igv = "16779.66",
                    principal = "54000.00",
                    tasaNominalMensual = "1.39",
                    filas =
                        listOf(
                            FilaCronogramaDto(
                                mes = 0,
                                saldoInicial = "54000.00",
                                amortizacion = "0.00",
                                interes = null,
                                igv = null,
                                saldoFinal = "54000.00",
                                cuota = null,
                                cuotaConIgv = null,
                            ),
                        ),
                ),
        )

    private val bodyValido =
        """{"modo":"leasing","precio_venta":110000,"cuota_inicial":56000,"plazo_meses":48,"tea":18}"""

    @Test
    fun `POST calculadora responde 200 con el envelope`() {
        every { calculadoraFinancieraService.calcular(any(), any()) } returns calculadoraDto()
        mockMvc.post("/api/v1/calculadora") {
            header(HttpHeaders.AUTHORIZATION, "Bearer ${tokenAnalista()}")
            contentType = MediaType.APPLICATION_JSON
            content = bodyValido
        }.andExpect {
            status { isOk() }
            jsonPath("$.data.cronograma.cuota_final") { value("1548.86") }
        }
    }

    @Test
    fun `POST calculadora con precio_venta negativo responde 400 VALIDACION`() {
        mockMvc.post("/api/v1/calculadora") {
            header(HttpHeaders.AUTHORIZATION, "Bearer ${tokenAnalista()}")
            contentType = MediaType.APPLICATION_JSON
            content = """{"modo":"leasing","precio_venta":-1,"cuota_inicial":56000,"plazo_meses":48,"tea":18}"""
        }.andExpect {
            status { isBadRequest() }
            jsonPath("$.error.code") { value("VALIDACION") }
        }
    }

    @Test
    fun `POST calculadora cuando el servicio lanza ValidacionException responde 400 VALIDACION`() {
        every { calculadoraFinancieraService.calcular(any(), any()) } throws
            ValidacionException("cuota_inicial debe ser menor al precio efectivo", field = "cuota_inicial")
        mockMvc.post("/api/v1/calculadora") {
            header(HttpHeaders.AUTHORIZATION, "Bearer ${tokenAnalista()}")
            contentType = MediaType.APPLICATION_JSON
            content = bodyValido
        }.andExpect {
            status { isBadRequest() }
            jsonPath("$.error.code") { value("VALIDACION") }
            jsonPath("$.error.field") { value("cuota_inicial") }
        }
    }

    @Test
    fun `sin token responde 401`() {
        mockMvc.post("/api/v1/calculadora") {
            contentType = MediaType.APPLICATION_JSON
            content = bodyValido
        }.andExpect { status { isUnauthorized() } }
    }
}
