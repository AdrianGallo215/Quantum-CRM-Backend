package pe.quantum.crm.domain.contactos

import com.ninjasquad.springmockk.MockkBean
import io.mockk.every
import io.mockk.slot
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.put
import pe.quantum.crm.config.security.JwtService
import pe.quantum.crm.domain.contactos.dto.ActualizarVinculoRequest
import pe.quantum.crm.domain.contactos.dto.VincularContactoRequest
import pe.quantum.crm.domain.contactos.dto.VinculoDto
import pe.quantum.crm.domain.oportunidades.OportunidadesDeContacto
import pe.quantum.crm.shared.exception.ConflictoException
import pe.quantum.crm.shared.exception.NoEncontradoException
import pe.quantum.crm.shared.security.UsuarioActual
import pe.quantum.crm.support.SinBaseDeDatosMocks

/**
 * Rutas de vinculacion contacto-empresa (`/empresas/:id/contactos`, contrato_api.md §9).
 * Sin base de datos: el `ContactoService` esta mockeado; lo que se fija aqui es el
 * contrato HTTP (codigos, nombres snake_case del body y propagacion del usuario).
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
class EmpresaContactoControllerWebMvcTest {
    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var jwtService: JwtService

    @MockkBean
    lateinit var contactoService: ContactoService

    @MockkBean
    lateinit var oportunidadesDeContacto: OportunidadesDeContacto

    @MockkBean
    lateinit var tareaService: pe.quantum.crm.domain.tareas.TareaService

    private fun token(rol: String = "vendedor") = jwtService.generateAccessToken(empleadoId = 42, rol = rol)

    @Test
    fun `POST vincula un contacto existente y devuelve 201 con el vinculo`() {
        val request = slot<VincularContactoRequest>()
        every { contactoService.vincular(3, capture(request), any()) } returns
            VinculoDto(idEmpresa = 3, idContacto = 1, cargo = "Jefe de flota", tomaDecision = true, esPrincipal = true)

        mockMvc.post("/api/v1/empresas/3/contactos") {
            header(HttpHeaders.AUTHORIZATION, "Bearer ${token()}")
            contentType = MediaType.APPLICATION_JSON
            content = """{"id_contacto":1,"cargo":"Jefe de flota","toma_decision":true,"es_principal":true}"""
        }.andExpect {
            status { isCreated() }
            jsonPath("$.data.id_empresa") { value(3) }
            jsonPath("$.data.id_contacto") { value(1) }
            jsonPath("$.data.cargo") { value("Jefe de flota") }
            jsonPath("$.data.toma_decision") { value(true) }
            jsonPath("$.data.es_principal") { value(true) }
        }

        assertThat(request.captured.idContacto).isEqualTo(1)
        assertThat(request.captured.tomaDecision).isTrue()
        assertThat(request.captured.esPrincipal).isTrue()
    }

    @Test
    fun `POST de un vinculo duplicado devuelve 409 VINCULO_DUPLICADO`() {
        every { contactoService.vincular(3, any(), any()) } throws
            ConflictoException("VINCULO_DUPLICADO", "El contacto ya está vinculado a esta empresa")

        mockMvc.post("/api/v1/empresas/3/contactos") {
            header(HttpHeaders.AUTHORIZATION, "Bearer ${token()}")
            contentType = MediaType.APPLICATION_JSON
            content = """{"id_contacto":1}"""
        }.andExpect {
            status { isConflict() }
            jsonPath("$.error.code") { value("VINCULO_DUPLICADO") }
        }
    }

    /** IDOR: empresa ajena o inexistente -> 404, nunca 403 (CLAUDE.md regla 14). */
    @Test
    fun `POST sobre una empresa no visible devuelve 404`() {
        every { contactoService.vincular(77, any(), any()) } throws NoEncontradoException("La empresa no existe")

        mockMvc.post("/api/v1/empresas/77/contactos") {
            header(HttpHeaders.AUTHORIZATION, "Bearer ${token()}")
            contentType = MediaType.APPLICATION_JSON
            content = """{"id_contacto":1}"""
        }.andExpect {
            status { isNotFound() }
            jsonPath("$.error.code") { value("NO_ENCONTRADO") }
        }
    }

    @Test
    fun `PUT actualiza el vinculo y propaga el usuario autenticado`() {
        val request = slot<ActualizarVinculoRequest>()
        every { contactoService.actualizarVinculo(3, 1, capture(request), any()) } returns
            VinculoDto(idEmpresa = 3, idContacto = 1, cargo = "Gerente general", tomaDecision = true, esPrincipal = false)

        mockMvc.put("/api/v1/empresas/3/contactos/1") {
            header(HttpHeaders.AUTHORIZATION, "Bearer ${token()}")
            contentType = MediaType.APPLICATION_JSON
            content = """{"cargo":"Gerente general","toma_decision":true}"""
        }.andExpect {
            status { isOk() }
            jsonPath("$.data.cargo") { value("Gerente general") }
        }

        assertThat(request.captured.cargo).isEqualTo("Gerente general")
        assertThat(request.captured.esPrincipal).isNull()
        verify { contactoService.actualizarVinculo(3, 1, any(), UsuarioActual(id = 42, rol = "vendedor")) }
    }

    @Test
    fun `DELETE desvincula y devuelve 204`() {
        every { contactoService.desvincular(3, 1, any()) } returns Unit

        mockMvc.delete("/api/v1/empresas/3/contactos/1") {
            header(HttpHeaders.AUTHORIZATION, "Bearer ${token()}")
        }.andExpect { status { isNoContent() } }

        verify(exactly = 1) { contactoService.desvincular(3, 1, UsuarioActual(id = 42, rol = "vendedor")) }
    }

    @Test
    fun `DELETE de un vinculo inexistente devuelve 404`() {
        every { contactoService.desvincular(3, 1, any()) } throws
            NoEncontradoException("El contacto no está vinculado a esta empresa")

        mockMvc.delete("/api/v1/empresas/3/contactos/1") {
            header(HttpHeaders.AUTHORIZATION, "Bearer ${token()}")
        }.andExpect {
            status { isNotFound() }
            jsonPath("$.error.code") { value("NO_ENCONTRADO") }
        }
    }
}
