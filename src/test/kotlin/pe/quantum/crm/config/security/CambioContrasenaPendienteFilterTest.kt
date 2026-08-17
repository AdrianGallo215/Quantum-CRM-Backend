package pe.quantum.crm.config.security

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
import org.springframework.test.web.servlet.post
import pe.quantum.crm.domain.empleados.Empleado
import pe.quantum.crm.domain.empleados.EmpleadoService
import pe.quantum.crm.domain.empleados.RolEmpleado
import pe.quantum.crm.support.SinBaseDeDatosMocks

/**
 * Defensa en profundidad de `requiere_cambio_contrasena` (B1.4).
 *
 * Hasta ahora el flag era solo una sugerencia al cliente: el frontend redirigia al
 * cambio de contraseña, pero nada impedia a un usuario con contraseña temporal
 * ignorar la redireccion y seguir usando la API. Estos tests fijan que sea el
 * backend quien corta el paso, y que las exenciones sean exactamente las minimas
 * para poder cumplir con el cambio (cambiar la contraseña, cerrar sesion y leer el
 * propio perfil).
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
class CambioContrasenaPendienteFilterTest {
    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var jwtService: JwtService

    @MockkBean
    lateinit var empleadoService: EmpleadoService

    private fun tokenConCambioPendiente(): String =
        jwtService.generateAccessToken(empleadoId = 7, rol = "jdv", requiereCambioContrasena = true)

    private fun tokenNormal(): String = jwtService.generateAccessToken(empleadoId = 7, rol = "jdv")

    private fun empleado(requiereCambio: Boolean) =
        Empleado(
            id = 7,
            nombres = "Aldo",
            apellidos = "Martinez",
            email = "aldo@quantum.pe",
            rol = RolEmpleado.jdv,
            activo = true,
            requiereCambioContrasena = requiereCambio,
        )

    @Test
    fun `con cambio pendiente un endpoint de negocio responde 403 CAMBIO_CONTRASENA_REQUERIDO`() {
        mockMvc.get("/api/v1/empleados") {
            header(HttpHeaders.AUTHORIZATION, "Bearer ${tokenConCambioPendiente()}")
        }.andExpect {
            status { isForbidden() }
            jsonPath("$.error.code") { value("CAMBIO_CONTRASENA_REQUERIDO") }
        }
    }

    @Test
    fun `sin cambio pendiente el mismo endpoint responde normal`() {
        every { empleadoService.listar(true, null) } returns emptyList()

        mockMvc.get("/api/v1/empleados") {
            header(HttpHeaders.AUTHORIZATION, "Bearer ${tokenNormal()}")
        }.andExpect {
            status { isOk() }
        }
    }

    @Test
    fun `con cambio pendiente se puede leer el propio perfil`() {
        every { empleadoService.porId(7) } returns empleado(requiereCambio = true)

        mockMvc.get("/api/v1/empleados/me") {
            header(HttpHeaders.AUTHORIZATION, "Bearer ${tokenConCambioPendiente()}")
        }.andExpect {
            status { isOk() }
        }
    }

    @Test
    fun `con cambio pendiente se puede cambiar la contrasena`() {
        every { empleadoService.cambiarContrasena(7, "vieja", "NuevaSegura123") } returns Unit
        every { empleadoService.porId(7) } returns empleado(requiereCambio = false)

        mockMvc.post("/api/v1/auth/cambiar-contrasena") {
            header(HttpHeaders.AUTHORIZATION, "Bearer ${tokenConCambioPendiente()}")
            contentType = MediaType.APPLICATION_JSON
            content = """{"password_actual":"vieja","password_nueva":"NuevaSegura123"}"""
        }.andExpect {
            status { isOk() }
        }
    }

    @Test
    fun `con cambio pendiente se puede cerrar sesion`() {
        mockMvc.post("/api/v1/auth/logout") {
            header(HttpHeaders.AUTHORIZATION, "Bearer ${tokenConCambioPendiente()}")
        }.andExpect {
            status { isNoContent() }
        }
    }
}
