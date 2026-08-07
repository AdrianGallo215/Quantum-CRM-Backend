package pe.quantum.crm.domain.contactos

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
import pe.quantum.crm.config.security.JwtService
import pe.quantum.crm.domain.contactos.dto.ContactoListaDto
import pe.quantum.crm.domain.oportunidades.OportunidadesDeContacto
import pe.quantum.crm.shared.PageMeta
import pe.quantum.crm.shared.Paginado
import pe.quantum.crm.shared.security.UsuarioActual
import pe.quantum.crm.support.SinBaseDeDatosMocks

/**
 * Tests de `GET /contactos` (paginacion + oportunidades_count) y `GET /contactos/:id`
 * (detalle). Sin base de datos (ContactoService/OportunidadService/TareaService mockeados).
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
class ContactoControllerWebMvcTest {
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

    @Test
    fun `GET contactos devuelve meta de paginacion y oportunidades_count por item`() {
        val item =
            ContactoListaDto(
                id = 5,
                nombres = "Hugo",
                apellidos = "Rodríguez",
                email_1 = null,
                email_2 = null,
                tlf_1 = "964415122",
                tlf_2 = null,
                notas = null,
                empresas = emptyList(),
            )
        every { contactoService.buscar(null, null, any(), 2, 10, null, null) } returns
            Paginado(listOf(item), PageMeta(page = 2, perPage = 10, total = 11, totalPages = 2))
        every { oportunidadesDeContacto.contar(5, any()) } returns 3
        val token = jwtService.generateAccessToken(empleadoId = 1, rol = "admin")

        mockMvc.get("/api/v1/contactos?page=2&per_page=10") {
            header(HttpHeaders.AUTHORIZATION, "Bearer $token")
        }.andExpect {
            status { isOk() }
            jsonPath("$.data[0].id") { value(5) }
            jsonPath("$.data[0].oportunidades_count") { value(3) }
            jsonPath("$.meta.page") { value(2) }
            jsonPath("$.meta.per_page") { value(10) }
            jsonPath("$.meta.total") { value(11) }
            jsonPath("$.meta.total_pages") { value(2) }
        }
    }

    @Test
    fun `GET contactos por id inexistente devuelve 404`() {
        every { contactoService.detalle(99) } throws pe.quantum.crm.shared.exception.NoEncontradoException("El contacto no existe")
        val token = jwtService.generateAccessToken(empleadoId = 1, rol = "admin")

        mockMvc.get("/api/v1/contactos/99") {
            header(HttpHeaders.AUTHORIZATION, "Bearer $token")
        }.andExpect {
            status { isNotFound() }
            jsonPath("$.error.code") { value("NO_ENCONTRADO") }
        }
    }

    @Test
    fun `GET contactos por id devuelve empresas, oportunidades y actividades`() {
        val detalle =
            pe.quantum.crm.domain.contactos.dto.ContactoDetalleDto(
                id = 5, nombres = "Hugo", apellidos = "Rodríguez",
                email_1 = null, email_2 = null, tlf_1 = "964415122", tlf_2 = null, notas = null,
                empresas = emptyList(),
            )
        every { contactoService.detalle(5) } returns detalle
        every { oportunidadesDeContacto.listar(5, any()) } returns emptyList()
        every { tareaService.actividadesPorContacto(5, any()) } returns emptyList()
        val token = jwtService.generateAccessToken(empleadoId = 1, rol = "admin")

        mockMvc.get("/api/v1/contactos/5") {
            header(HttpHeaders.AUTHORIZATION, "Bearer $token")
        }.andExpect {
            status { isOk() }
            jsonPath("$.data.id") { value(5) }
            jsonPath("$.data.oportunidades") { isEmpty() }
            jsonPath("$.data.actividades") { isEmpty() }
        }
    }

    /**
     * El detalle de contacto embebe oportunidades: el controller DEBE arrastrar la
     * identidad del llamante para que el servicio aplique el filtro por vendedor.
     * Sin esto un vendedor leia el pipeline completo (montos incluidos) enumerando
     * contactos, que son globales por diseño.
     */
    @Test
    fun `GET contactos por id propaga el usuario autenticado al filtro de oportunidades`() {
        val detalle =
            pe.quantum.crm.domain.contactos.dto.ContactoDetalleDto(
                id = 5, nombres = "Hugo", apellidos = "Rodríguez",
                email_1 = null, email_2 = null, tlf_1 = "964415122", tlf_2 = null, notas = null,
                empresas = emptyList(),
            )
        every { contactoService.detalle(5) } returns detalle
        every { oportunidadesDeContacto.listar(5, any()) } returns emptyList()
        every { tareaService.actividadesPorContacto(5, any()) } returns emptyList()
        val token = jwtService.generateAccessToken(empleadoId = 42, rol = "vendedor")

        mockMvc.get("/api/v1/contactos/5") {
            header(HttpHeaders.AUTHORIZATION, "Bearer $token")
        }.andExpect { status { isOk() } }

        verify { oportunidadesDeContacto.listar(5, UsuarioActual(id = 42, rol = "vendedor")) }
    }
}
