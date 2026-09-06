package pe.quantum.crm.domain.simulaciones

import com.ninjasquad.springmockk.MockkBean
import io.mockk.every
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.patch
import org.springframework.test.web.servlet.post
import pe.quantum.crm.config.security.JwtService
import pe.quantum.crm.domain.simulaciones.dto.CampoDiffDto
import pe.quantum.crm.domain.simulaciones.dto.CrearSimulacionRequest
import pe.quantum.crm.domain.simulaciones.dto.CronogramaDto
import pe.quantum.crm.domain.simulaciones.dto.EventoHistorialDto
import pe.quantum.crm.domain.simulaciones.dto.FilaCronogramaDto
import pe.quantum.crm.domain.simulaciones.dto.SimulacionDto
import pe.quantum.crm.shared.Paginacion
import pe.quantum.crm.shared.Paginado
import pe.quantum.crm.support.SinBaseDeDatosMocks
import java.math.BigDecimal
import java.time.Instant

/** Tests de los endpoints de simulaciones via MockMvc, sin base de datos. */
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
class SimulacionControllerWebMvcTest {
    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var jwtService: JwtService

    @MockkBean
    lateinit var simulacionService: SimulacionService

    private fun tokenAnalista() = jwtService.generateAccessToken(empleadoId = 1, rol = "analista")

    private fun simulacionDto(id: Long = 9) =
        SimulacionDto(
            id = id,
            nombre = "Transportes Lima SAC · MB-O500 · Leasing · #1",
            nombreEsManual = false,
            modo = "leasing",
            idOportunidadItem = 3,
            idModelo = 7,
            modelo = null,
            idSimulacionOrigen = null,
            precioVenta = "110000.00",
            descuento = "0.00",
            cuotaInicial = "56000.00",
            plazoMeses = 48,
            tea = "18.00",
            valorResidual = "0.00",
            diasTrabajados = 22,
            comisionEstructuracion = "1180.00",
            cuotaFinal = "1548.86",
            esPrincipal = true,
            createdAt = Instant.now(),
            updatedAt = Instant.now(),
        )

    private val bodyCreacionValido =
        """{"modo":"leasing","precio_venta":110000,"cuota_inicial":56000,"plazo_meses":48,"tea":18}"""

    @Test
    fun `POST simulaciones responde 201 con el envelope`() {
        every { simulacionService.crear(any(), any()) } returns simulacionDto()
        mockMvc.post("/api/v1/simulaciones") {
            header(HttpHeaders.AUTHORIZATION, "Bearer ${tokenAnalista()}")
            contentType = MediaType.APPLICATION_JSON
            content = bodyCreacionValido
        }.andExpect {
            status { isCreated() }
            jsonPath("$.data.id") { value(9) }
            jsonPath("$.data.cuota_final") { value("1548.86") }
        }
    }

    @Test
    fun `POST simulaciones con precio_venta negativo responde 400 VALIDACION`() {
        mockMvc.post("/api/v1/simulaciones") {
            header(HttpHeaders.AUTHORIZATION, "Bearer ${tokenAnalista()}")
            contentType = MediaType.APPLICATION_JSON
            content = """{"modo":"leasing","precio_venta":-1,"cuota_inicial":56000,"plazo_meses":48,"tea":18}"""
        }.andExpect {
            status { isBadRequest() }
            jsonPath("$.error.code") { value("VALIDACION") }
        }
    }

    @Test
    fun `POST simulaciones ignora cuota_final si viene en el body`() {
        val slot = slot<CrearSimulacionRequest>()
        every { simulacionService.crear(capture(slot), any()) } returns simulacionDto()
        mockMvc.post("/api/v1/simulaciones") {
            header(HttpHeaders.AUTHORIZATION, "Bearer ${tokenAnalista()}")
            contentType = MediaType.APPLICATION_JSON
            content =
                """{"modo":"leasing","precio_venta":110000,"cuota_inicial":56000,"plazo_meses":48,
                   "tea":18,"cuota_final":999.99}"""
        }.andExpect { status { isCreated() } }
        // CrearSimulacionRequest no declara cuotaFinal (restriccion 2 del encargo):
        // el request que llega al servicio solo puede traer los campos del DTO.
        verify(exactly = 1) { simulacionService.crear(any(), any()) }
        val capturado = slot.captured
        assert(capturado.precioVenta == BigDecimal("110000")) { "precioVenta debe deserializarse igual" }
        assert(capturado.plazoMeses == 48)
    }

    @Test
    fun `GET simulaciones-id responde 200`() {
        every { simulacionService.detalle(9, any()) } returns simulacionDto()
        mockMvc.get("/api/v1/simulaciones/9") {
            header(HttpHeaders.AUTHORIZATION, "Bearer ${tokenAnalista()}")
        }.andExpect {
            status { isOk() }
            jsonPath("$.data.id") { value(9) }
        }
    }

    @Test
    fun `GET simulaciones responde 200 paginado`() {
        every { simulacionService.listar(any(), any(), any(), any(), any(), any()) } returns
            Paginado(listOf(simulacionDto()), Paginacion.meta(1, 20, 1))
        mockMvc.get("/api/v1/simulaciones") {
            header(HttpHeaders.AUTHORIZATION, "Bearer ${tokenAnalista()}")
        }.andExpect {
            status { isOk() }
            jsonPath("$.meta.page") { value(1) }
            jsonPath("$.data[0].id") { value(9) }
        }
    }

    @Test
    fun `GET simulaciones-id-cronograma responde 200 con las filas`() {
        val cronograma =
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
            )
        every { simulacionService.cronograma(9, any()) } returns cronograma
        mockMvc.get("/api/v1/simulaciones/9/cronograma") {
            header(HttpHeaders.AUTHORIZATION, "Bearer ${tokenAnalista()}")
        }.andExpect {
            status { isOk() }
            jsonPath("$.data.cuota_final") { value("1548.86") }
            jsonPath("$.data.filas[0].mes") { value(0) }
        }
    }

    @Test
    fun `PATCH simulaciones-id responde 200`() {
        every { simulacionService.actualizar(9, any(), any()) } returns simulacionDto()
        mockMvc.patch("/api/v1/simulaciones/9") {
            header(HttpHeaders.AUTHORIZATION, "Bearer ${tokenAnalista()}")
            contentType = MediaType.APPLICATION_JSON
            content = """{"nombre":"Nueva simulacion"}"""
        }.andExpect {
            status { isOk() }
            jsonPath("$.data.id") { value(9) }
        }
    }

    @Test
    fun `DELETE simulaciones-id responde 204 sin body`() {
        every { simulacionService.eliminar(9, any()) } returns Unit
        mockMvc.delete("/api/v1/simulaciones/9") {
            header(HttpHeaders.AUTHORIZATION, "Bearer ${tokenAnalista()}")
        }.andExpect {
            status { isNoContent() }
            content { string("") }
        }
    }

    @Test
    fun `GET simulaciones acepta los query params del listado en snake_case`() {
        every { simulacionService.listar(any(), any(), any(), any(), any(), any()) } returns
            Paginado(listOf(simulacionDto()), Paginacion.meta(1, 20, 1))
        mockMvc.get("/api/v1/simulaciones") {
            header(HttpHeaders.AUTHORIZATION, "Bearer ${tokenAnalista()}")
            param("id_oportunidad_item", "3")
            param("id_modelo", "7")
            param("modo", "leasing")
            param("page", "2")
            param("per_page", "10")
            param("sort", "created_at")
            param("dir", "desc")
        }.andExpect { status { isOk() } }

        verify(exactly = 1) {
            simulacionService.listar(
                match { it.idOportunidadItem == 3L && it.idModelo == 7L && it.modo == "leasing" },
                any(),
                2,
                10,
                "created_at",
                "desc",
            )
        }
    }

    @Test
    fun `sin token responde 401`() {
        mockMvc.get("/api/v1/simulaciones").andExpect { status { isUnauthorized() } }
    }

    @Test
    fun `GET simulaciones-id-historial responde 200 con la lista`() {
        val evento =
            EventoHistorialDto(
                idEventoLog = 55,
                tipoEvento = "editada",
                createdAt = Instant.now(),
                createdBy = 1,
                diff = listOf(CampoDiffDto(campo = "tea", valorAnterior = "18.00", valorNuevo = "20.00")),
            )
        every { simulacionService.historial(9, any()) } returns listOf(evento)
        mockMvc.get("/api/v1/simulaciones/9/historial") {
            header(HttpHeaders.AUTHORIZATION, "Bearer ${tokenAnalista()}")
        }.andExpect {
            status { isOk() }
            jsonPath("$.data[0].id_evento_log") { value(55) }
            jsonPath("$.data[0].diff[0].campo") { value("tea") }
        }
    }

    @Test
    fun `POST simulaciones-id-restaurar responde 200`() {
        every { simulacionService.restaurar(9, 55, any()) } returns simulacionDto()
        mockMvc.post("/api/v1/simulaciones/9/restaurar") {
            header(HttpHeaders.AUTHORIZATION, "Bearer ${tokenAnalista()}")
            contentType = MediaType.APPLICATION_JSON
            content = """{"id_evento_log":55}"""
        }.andExpect {
            status { isOk() }
            jsonPath("$.data.id") { value(9) }
        }
    }

    @Test
    fun `POST simulaciones-id-bifurcar responde 201 con la simulacion nueva`() {
        every { simulacionService.bifurcar(9, any(), any()) } returns simulacionDto(id = 10)
        mockMvc.post("/api/v1/simulaciones/9/bifurcar") {
            header(HttpHeaders.AUTHORIZATION, "Bearer ${tokenAnalista()}")
            contentType = MediaType.APPLICATION_JSON
            content = """{"precio_venta":120000}"""
        }.andExpect {
            status { isCreated() }
            jsonPath("$.data.id") { value(10) }
        }
    }

    @Test
    fun `PATCH simulaciones-id-principal responde 200`() {
        every { simulacionService.marcarPrincipal(9, any()) } returns simulacionDto()
        mockMvc.patch("/api/v1/simulaciones/9/principal") {
            header(HttpHeaders.AUTHORIZATION, "Bearer ${tokenAnalista()}")
        }.andExpect {
            status { isOk() }
            jsonPath("$.data.id") { value(9) }
        }
    }

    @Test
    fun `POST simulaciones-id-bifurcar con precio_venta negativo responde 400 VALIDACION`() {
        mockMvc.post("/api/v1/simulaciones/9/bifurcar") {
            header(HttpHeaders.AUTHORIZATION, "Bearer ${tokenAnalista()}")
            contentType = MediaType.APPLICATION_JSON
            content = """{"precio_venta":-1}"""
        }.andExpect {
            status { isBadRequest() }
            jsonPath("$.error.code") { value("VALIDACION") }
        }
    }

    @Test
    fun `POST simulaciones-id-restaurar sin id_evento_log responde 400`() {
        mockMvc.post("/api/v1/simulaciones/9/restaurar") {
            header(HttpHeaders.AUTHORIZATION, "Bearer ${tokenAnalista()}")
            contentType = MediaType.APPLICATION_JSON
            content = """{}"""
        }.andExpect {
            status { isBadRequest() }
        }
    }
}
