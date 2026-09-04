package pe.quantum.crm.domain.tipocambio

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
import pe.quantum.crm.domain.tipocambio.dto.TipoCambioDto
import pe.quantum.crm.support.SinBaseDeDatosMocks
import java.math.BigDecimal
import java.time.LocalDate

/** Tests del endpoint de tipo de cambio via MockMvc, sin base de datos. */
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
class TipoCambioControllerWebMvcTest {
    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var jwtService: JwtService

    @MockkBean
    lateinit var tipoCambioService: TipoCambioService

    private fun tokenJdv() = jwtService.generateAccessToken(empleadoId = 2, rol = "jdv")

    @Test
    fun `GET tipo-cambio responde 200 con el valor vigente`() {
        every { tipoCambioService.vigente() } returns
            TipoCambioDto(fecha = LocalDate.of(2026, 9, 1), compra = BigDecimal("3.750"), venta = BigDecimal("3.760"))
        mockMvc.get("/api/v1/tipo-cambio") {
            header(HttpHeaders.AUTHORIZATION, "Bearer ${tokenJdv()}")
        }.andExpect {
            status { isOk() }
            jsonPath("$.data.venta") { value(3.760) }
        }
    }

    @Test
    fun `GET tipo-cambio responde 200 con data null cuando no hay valor guardado`() {
        every { tipoCambioService.vigente() } returns null
        mockMvc.get("/api/v1/tipo-cambio") {
            header(HttpHeaders.AUTHORIZATION, "Bearer ${tokenJdv()}")
        }.andExpect {
            status { isOk() }
            jsonPath("$.data") { isEmpty() }
        }
    }

    @Test
    fun `sin token responde 401`() {
        mockMvc.get("/api/v1/tipo-cambio").andExpect { status { isUnauthorized() } }
    }
}
