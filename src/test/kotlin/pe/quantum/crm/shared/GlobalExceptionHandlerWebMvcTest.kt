package pe.quantum.crm.shared

import com.ninjasquad.springmockk.MockkBean
import jakarta.validation.ConstraintViolationException
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.not
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.http.HttpHeaders
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.get
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseBody
import org.springframework.web.multipart.MaxUploadSizeExceededException
import pe.quantum.crm.config.security.JwtService
import pe.quantum.crm.domain.empleados.EmpleadoService
import pe.quantum.crm.support.SinBaseDeDatosMocks

/**
 * El handler solo cubria un punado de excepciones; el resto de los errores mas
 * comunes de Spring MVC (id no numerico, metodo no soportado, query param
 * ausente, subida demasiado grande, violacion de integridad) caian en el handler
 * generico y respondian 500 ERROR_INTERNO, ensuciando los logs con stacktraces y
 * enmascarando los 500 de verdad. Cada uno debe devolver su 4xx del contrato.
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
@Import(SinBaseDeDatosMocks::class, GlobalExceptionHandlerWebMvcTest.ControladorDeErroresConfig::class)
class GlobalExceptionHandlerWebMvcTest {
    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var jwtService: JwtService

    // Sin DataSource no hay repositorios JPA; se mockea el servicio que los usa.
    @MockkBean
    lateinit var empleadoService: EmpleadoService

    private fun token() = jwtService.generateAccessToken(empleadoId = 1, rol = "admin")

    @Test
    fun `recurso estatico inexistente devuelve 404 NO_ENCONTRADO, no 500`() {
        mockMvc.get("/favicon.ico") {
            header(HttpHeaders.AUTHORIZATION, "Bearer ${token()}")
        }.andExpect {
            status { isNotFound() }
            jsonPath("$.error.code") { value("NO_ENCONTRADO") }
        }
    }

    @Test
    fun `un id de path no numerico devuelve 400 VALIDACION, no 500`() {
        mockMvc.get("/api/v1/oportunidades/abc") {
            header(HttpHeaders.AUTHORIZATION, "Bearer ${token()}")
        }.andExpect {
            status { isBadRequest() }
            jsonPath("$.error.code") { value("VALIDACION") }
            jsonPath("$.error.field") { value("id") }
        }
    }

    @Test
    fun `un metodo HTTP no soportado devuelve 405 METODO_NO_PERMITIDO, no 500`() {
        mockMvc.delete("/api/v1/oportunidades") {
            header(HttpHeaders.AUTHORIZATION, "Bearer ${token()}")
        }.andExpect {
            status { isMethodNotAllowed() }
            jsonPath("$.error.code") { value("METODO_NO_PERMITIDO") }
        }
    }

    @Test
    fun `falta un query param obligatorio y devuelve 400 VALIDACION con el campo`() {
        mockMvc.get("$RUTA_ERRORES/param-obligatorio") {
            header(HttpHeaders.AUTHORIZATION, "Bearer ${token()}")
        }.andExpect {
            status { isBadRequest() }
            jsonPath("$.error.code") { value("VALIDACION") }
            jsonPath("$.error.field") { value("obligatorio") }
        }
    }

    @Test
    fun `una subida que supera el maximo devuelve 413 ARCHIVO_DEMASIADO_GRANDE`() {
        mockMvc.get("$RUTA_ERRORES/subida-grande") {
            header(HttpHeaders.AUTHORIZATION, "Bearer ${token()}")
        }.andExpect {
            status { isEqualTo(HTTP_PAYLOAD_TOO_LARGE) }
            jsonPath("$.error.code") { value("ARCHIVO_DEMASIADO_GRANDE") }
        }
    }

    @Test
    fun `una violacion de integridad devuelve 409 sin filtrar el detalle de la base de datos`() {
        // El nombre de la constraint y el texto del driver son informacion del esquema.
        mockMvc.get("$RUTA_ERRORES/integridad") {
            header(HttpHeaders.AUTHORIZATION, "Bearer ${token()}")
        }.andExpect {
            status { isConflict() }
            jsonPath("$.error.code") { value("CONFLICTO_DATOS") }
            jsonPath("$.error.message", not(containsString("empresas_ruc_key")))
            jsonPath("$.error.message", not(containsString("duplicate key")))
        }
    }

    @Test
    fun `una ConstraintViolationException de jakarta devuelve 400 VALIDACION, no 500`() {
        mockMvc.get("$RUTA_ERRORES/constraint") {
            header(HttpHeaders.AUTHORIZATION, "Bearer ${token()}")
        }.andExpect {
            status { isBadRequest() }
            jsonPath("$.error.code") { value("VALIDACION") }
        }
    }

    @Test
    fun `un sort fuera de la allowlist devuelve 400 VALIDACION, no 500`() {
        mockMvc.get("$RUTA_ERRORES/orden") {
            param("sort", "cualquiercosa")
            header(HttpHeaders.AUTHORIZATION, "Bearer ${token()}")
        }.andExpect {
            status { isBadRequest() }
            jsonPath("$.error.code") { value("VALIDACION") }
            jsonPath("$.error.field") { value("sort") }
        }
    }

    /**
     * Endpoints que provocan a proposito cada excepcion que el handler debe
     * traducir. Se registran como `@Bean` y NO como `@RestController` para que el
     * component scan del resto de los tests no los recoja: `@RequestMapping` a
     * nivel de tipo basta para que Spring MVC los mapee.
     */
    @RequestMapping(RUTA_ERRORES)
    @ResponseBody
    class ControladorDeErrores {
        @GetMapping("/param-obligatorio")
        fun paramObligatorio(
            @RequestParam("obligatorio") obligatorio: String,
        ): String = obligatorio

        @GetMapping("/subida-grande")
        fun subidaGrande(): String = throw MaxUploadSizeExceededException(MAX_BYTES)

        @GetMapping("/integridad")
        fun integridad(): String =
            throw DataIntegrityViolationException(
                "ERROR: duplicate key value violates unique constraint \"empresas_ruc_key\"",
            )

        @GetMapping("/constraint")
        fun constraint(): String = throw ConstraintViolationException("listar.page: debe ser mayor que 0", emptySet())

        @GetMapping("/orden")
        fun orden(
            @RequestParam(required = false) sort: String?,
        ): String = Paginacion.pageRequest(null, null, sort, null, CAMPOS).sort.toString()
    }

    @TestConfiguration
    class ControladorDeErroresConfig {
        @Bean
        fun controladorDeErrores() = ControladorDeErrores()
    }

    companion object {
        const val RUTA_ERRORES = "/api/v1/test-errores"
        const val MAX_BYTES = 1_024L
        const val HTTP_PAYLOAD_TOO_LARGE = 413
        val CAMPOS = CamposOrdenables("id", "createdAt")
    }
}
