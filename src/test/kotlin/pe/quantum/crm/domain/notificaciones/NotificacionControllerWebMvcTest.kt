package pe.quantum.crm.domain.notificaciones

import com.ninjasquad.springmockk.MockkBean
import io.mockk.every
import io.mockk.verify
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.http.HttpHeaders
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.patch
import pe.quantum.crm.config.security.JwtService
import pe.quantum.crm.domain.notificaciones.dto.NotificacionDto
import pe.quantum.crm.shared.exception.NoEncontradoException
import pe.quantum.crm.support.SinBaseDeDatosMocks
import java.time.LocalDateTime

/**
 * Tests de los 4 endpoints de notificaciones (contrato_api.md §19) via MockMvc,
 * sin base de datos: se mockea NotificacionService. Mismo patron que
 * AuthControllerWebMvcTest.kt/EmpleadoMeControllerTest.kt.
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
class NotificacionControllerWebMvcTest {
    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var jwtService: JwtService

    @MockkBean
    lateinit var notificacionService: NotificacionService

    private fun bearer(): String = "Bearer " + jwtService.generateAccessToken(empleadoId = 1, rol = "vendedor")

    @Test
    fun `GET no-leidas-count devuelve el envelope estandar`() {
        every { notificacionService.contarNoLeidas(any()) } returns 5L

        mockMvc.get("/api/v1/notificaciones/no-leidas/count") {
            header(HttpHeaders.AUTHORIZATION, bearer())
        }.andExpect {
            status { isOk() }
            jsonPath("$.data.count") { value(5) }
            jsonPath("$.error") { isEmpty() }
        }
    }

    @Test
    fun `GET notificaciones devuelve la lista`() {
        every { notificacionService.listar(any()) } returns
            listOf(
                NotificacionDto(
                    id = 1,
                    tipo = "tarea_creada",
                    mensaje = "msg",
                    entidadTipo = "empresa",
                    entidadId = 1,
                    leida = false,
                    createdAt = LocalDateTime.now(),
                    actor = null,
                ),
            )

        mockMvc.get("/api/v1/notificaciones") {
            header(HttpHeaders.AUTHORIZATION, bearer())
        }.andExpect {
            status { isOk() }
            jsonPath("$.data.length()") { value(1) }
        }
    }

    @Test
    fun `PATCH notificaciones-id-leida sobre una ajena o inexistente devuelve 404 NO_ENCONTRADO`() {
        every { notificacionService.marcarLeida(99, any()) } throws NoEncontradoException("La notificación no existe")

        mockMvc.patch("/api/v1/notificaciones/99/leida") {
            header(HttpHeaders.AUTHORIZATION, bearer())
        }.andExpect {
            status { isNotFound() }
            jsonPath("$.error.code") { value("NO_ENCONTRADO") }
        }
    }

    @Test
    fun `PATCH notificaciones-leidas marca todas como leidas`() {
        every { notificacionService.marcarTodasLeidas(any()) } returns Unit

        mockMvc.patch("/api/v1/notificaciones/leidas") {
            header(HttpHeaders.AUTHORIZATION, bearer())
        }.andExpect {
            status { isOk() }
        }

        verify { notificacionService.marcarTodasLeidas(any()) }
    }

    @Test
    fun `sin token devuelve 401`() {
        mockMvc.get("/api/v1/notificaciones/no-leidas/count").andExpect {
            status { isUnauthorized() }
        }
    }
}
