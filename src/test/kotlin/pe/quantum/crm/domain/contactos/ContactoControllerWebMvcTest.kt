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
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.put
import pe.quantum.crm.config.security.JwtService
import pe.quantum.crm.domain.contactos.dto.ActualizarContactoRequest
import pe.quantum.crm.domain.contactos.dto.ContactoDto
import pe.quantum.crm.domain.contactos.dto.ContactoListaDto
import pe.quantum.crm.domain.contactos.dto.ContextoBusquedaContacto
import pe.quantum.crm.domain.contactos.dto.CrearContactoRequest
import pe.quantum.crm.domain.oportunidades.OportunidadesDeContacto
import pe.quantum.crm.shared.PageMeta
import pe.quantum.crm.shared.Paginado
import pe.quantum.crm.shared.exception.ContactoVinculadoException
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
        every { oportunidadesDeContacto.contarPorContactos(listOf(5L), any()) } returns mapOf(5L to 3)
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
        every { contactoService.detalle(99, any(), any()) } throws
            pe.quantum.crm.shared.exception.NoEncontradoException("El contacto no existe")
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
        every { contactoService.detalle(5, any(), any()) } returns detalle
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
        every { contactoService.detalle(5, any(), any()) } returns detalle
        every { oportunidadesDeContacto.listar(5, any()) } returns emptyList()
        every { tareaService.actividadesPorContacto(5, any()) } returns emptyList()
        val token = jwtService.generateAccessToken(empleadoId = 42, rol = "vendedor")

        mockMvc.get("/api/v1/contactos/5") {
            header(HttpHeaders.AUTHORIZATION, "Bearer $token")
        }.andExpect { status { isOk() } }

        verify { oportunidadesDeContacto.listar(5, UsuarioActual(id = 42, rol = "vendedor")) }
    }

    // ── escritura ──────────────────────────────────────────────

    /**
     * El body viaja en snake_case (`spring.jackson.property-naming-strategy`), asi
     * que este test tambien fija el contrato de nombres de `POST /contactos`: si
     * `id_empresa` o `toma_decision` se renombraran, el request llegaria con los
     * campos a null y el 400 saltaria en produccion, no aqui.
     */
    @Test
    fun `POST contactos deserializa el body en snake_case y devuelve 201`() {
        val creado = slot<CrearContactoRequest>()
        every { contactoService.crear(capture(creado), any()) } returns
            ContactoDto(
                id = 9, nombres = "Hugo", apellidos = "Rodríguez",
                email_1 = "hugo@transportes.pe", email_2 = null, tlf_1 = "964415122", tlf_2 = null,
                notas = "Prefiere WhatsApp", empresas = emptyList(),
            )
        val token = jwtService.generateAccessToken(empleadoId = 42, rol = "vendedor")

        mockMvc.post("/api/v1/contactos") {
            header(HttpHeaders.AUTHORIZATION, "Bearer $token")
            contentType = MediaType.APPLICATION_JSON
            content =
                """
                {"nombres":"Hugo","apellidos":"Rodríguez","email_1":"hugo@transportes.pe","tlf_1":"964415122",
                 "notas":"Prefiere WhatsApp","id_empresa":3,"cargo":"Gerente","toma_decision":true,"es_principal":true}
                """.trimIndent()
        }.andExpect {
            status { isCreated() }
            jsonPath("$.data.id") { value(9) }
            jsonPath("$.data.email_1") { value("hugo@transportes.pe") }
            jsonPath("$.data.tlf_1") { value("964415122") }
        }

        assertThat(creado.captured.idEmpresa).isEqualTo(3)
        assertThat(creado.captured.cargo).isEqualTo("Gerente")
        assertThat(creado.captured.tomaDecision).isTrue()
        assertThat(creado.captured.esPrincipal).isTrue()
    }

    @Test
    fun `POST contactos sin nombres devuelve 400 sin llegar al servicio`() {
        val token = jwtService.generateAccessToken(empleadoId = 42, rol = "vendedor")

        mockMvc.post("/api/v1/contactos") {
            header(HttpHeaders.AUTHORIZATION, "Bearer $token")
            contentType = MediaType.APPLICATION_JSON
            content = """{"nombres":"  ","apellidos":"Rodríguez","id_empresa":3}"""
        }.andExpect { status { isBadRequest() } }

        verify(exactly = 0) { contactoService.crear(any(), any()) }
    }

    @Test
    fun `PUT contactos actualiza solo los campos enviados`() {
        val actualizado = slot<ActualizarContactoRequest>()
        every { contactoService.actualizar(5, capture(actualizado), any()) } returns
            ContactoDto(
                id = 5, nombres = "Hugo", apellidos = "Rodríguez",
                email_1 = null, email_2 = null, tlf_1 = "999888777", tlf_2 = null, notas = null,
                empresas = emptyList(),
            )
        val token = jwtService.generateAccessToken(empleadoId = 42, rol = "vendedor")

        mockMvc.put("/api/v1/contactos/5") {
            header(HttpHeaders.AUTHORIZATION, "Bearer $token")
            contentType = MediaType.APPLICATION_JSON
            content = """{"tlf_1":"999888777"}"""
        }.andExpect {
            status { isOk() }
            jsonPath("$.data.tlf_1") { value("999888777") }
        }

        assertThat(actualizado.captured.tlf_1).isEqualTo("999888777")
        assertThat(actualizado.captured.nombres).isNull()
    }

    @Test
    fun `DELETE contactos devuelve 204 para un rol autorizado`() {
        every { contactoService.eliminar(5) } returns Unit
        val token = jwtService.generateAccessToken(empleadoId = 1, rol = "admin")

        mockMvc.delete("/api/v1/contactos/5") {
            header(HttpHeaders.AUTHORIZATION, "Bearer $token")
        }.andExpect { status { isNoContent() } }

        verify(exactly = 1) { contactoService.eliminar(5) }
    }

    /** matriz_permisos.md: borrar contactos es de admin/gerencia/jdv; el vendedor no. */
    @Test
    fun `DELETE contactos por un vendedor devuelve 403 sin llegar al servicio`() {
        val token = jwtService.generateAccessToken(empleadoId = 42, rol = "vendedor")

        mockMvc.delete("/api/v1/contactos/5") {
            header(HttpHeaders.AUTHORIZATION, "Bearer $token")
        }.andExpect { status { isForbidden() } }

        verify(exactly = 0) { contactoService.eliminar(any()) }
    }

    /** reglas_negocio.md §11.2: contacto vinculado a una empresa -> 409 CONTACTO_VINCULADO. */
    @Test
    fun `DELETE de un contacto vinculado devuelve 409 CONTACTO_VINCULADO`() {
        every { contactoService.eliminar(5) } throws ContactoVinculadoException()
        val token = jwtService.generateAccessToken(empleadoId = 1, rol = "admin")

        mockMvc.delete("/api/v1/contactos/5") {
            header(HttpHeaders.AUTHORIZATION, "Bearer $token")
        }.andExpect {
            status { isConflict() }
            jsonPath("$.error.code") { value("CONTACTO_VINCULADO") }
        }
    }

    // ── contexto de visibilidad (contrato_api.md §9) ───────────

    private fun filaReducida() =
        ContactoListaDto(
            id = 5,
            nombres = "Hugo",
            apellidos = "Rodríguez",
            email_1 = null,
            email_2 = null,
            tlf_1 = null,
            tlf_2 = null,
            notas = null,
            empresas = emptyList(),
        )

    /**
     * `oportunidades_count` es pipeline: enriquecer la fila reducida reabriria por
     * otra puerta justo el dato que el modo esconde.
     */
    @Test
    fun `GET contactos en modo vincular con rol de apoyo no consulta oportunidades_count`() {
        every {
            contactoService.buscar(null, null, any(), null, null, null, null, ContextoBusquedaContacto.vincular)
        } returns Paginado(listOf(filaReducida()), PageMeta(page = 1, perPage = 20, total = 1, totalPages = 1))
        val token = jwtService.generateAccessToken(empleadoId = 7, rol = "analista")

        mockMvc.get("/api/v1/contactos?contexto=vincular") {
            header(HttpHeaders.AUTHORIZATION, "Bearer $token")
        }.andExpect {
            status { isOk() }
            jsonPath("$.data[0].id") { value(5) }
            jsonPath("$.data[0].oportunidades_count") { value(0) }
            jsonPath("$.data[0].tlf_1") { doesNotExist() }
        }

        verify(exactly = 0) { oportunidadesDeContacto.contarPorContactos(any(), any()) }
    }

    /** Un rol con cartera propia no entra en modo reducido aunque pida `vincular`. */
    @Test
    fun `GET contactos en modo vincular con vendedor sigue enriqueciendo el conteo`() {
        val item =
            ContactoListaDto(
                id = 5, nombres = "Hugo", apellidos = "Rodríguez",
                email_1 = null, email_2 = null, tlf_1 = "964415122", tlf_2 = null, notas = null,
                empresas = emptyList(),
            )
        every {
            contactoService.buscar(null, null, any(), null, null, null, null, ContextoBusquedaContacto.vincular)
        } returns Paginado(listOf(item), PageMeta(page = 1, perPage = 20, total = 1, totalPages = 1))
        every { oportunidadesDeContacto.contarPorContactos(listOf(5L), any()) } returns mapOf(5L to 2)
        val token = jwtService.generateAccessToken(empleadoId = 42, rol = "vendedor")

        mockMvc.get("/api/v1/contactos?contexto=vincular") {
            header(HttpHeaders.AUTHORIZATION, "Bearer $token")
        }.andExpect {
            status { isOk() }
            jsonPath("$.data[0].oportunidades_count") { value(2) }
        }
    }

    /** R9: sin el parametro se cae en `listado`, el modo restrictivo. */
    @Test
    fun `GET contactos sin contexto usa el modo listado`() {
        every {
            contactoService.buscar(null, null, any(), null, null, null, null, ContextoBusquedaContacto.listado)
        } returns Paginado(emptyList(), PageMeta(page = 1, perPage = 20, total = 0, totalPages = 0))
        // Modo listado no es reducido: el controller igual llama a enriquecer con
        // oportunidades_count, aunque la pagina venga vacia.
        every { oportunidadesDeContacto.contarPorContactos(emptyList(), any()) } returns emptyMap()
        val token = jwtService.generateAccessToken(empleadoId = 7, rol = "analista")

        mockMvc.get("/api/v1/contactos") {
            header(HttpHeaders.AUTHORIZATION, "Bearer $token")
        }.andExpect { status { isOk() } }

        verify {
            contactoService.buscar(null, null, any(), null, null, null, null, ContextoBusquedaContacto.listado)
        }
    }

    @Test
    fun `GET contactos con un contexto invalido devuelve 400 sin llegar al servicio`() {
        val token = jwtService.generateAccessToken(empleadoId = 7, rol = "analista")

        mockMvc.get("/api/v1/contactos?contexto=global") {
            header(HttpHeaders.AUTHORIZATION, "Bearer $token")
        }.andExpect {
            status { isBadRequest() }
            jsonPath("$.error.code") { value("VALIDACION") }
        }

        verify(exactly = 0) { contactoService.buscar(any(), any(), any(), any(), any(), any(), any(), any()) }
    }

    /**
     * En modo reducido el detalle no embebe oportunidades ni actividades: son el
     * pipeline y la agenda de una empresa donde este rol no colabora.
     */
    @Test
    fun `GET contactos por id en modo vincular con rol de apoyo no embebe oportunidades ni actividades`() {
        val reducido =
            pe.quantum.crm.domain.contactos.dto.ContactoDetalleDto(
                id = 5, nombres = "Hugo", apellidos = "Rodríguez",
                email_1 = null, email_2 = null, tlf_1 = null, tlf_2 = null, notas = null,
                empresas = emptyList(),
            )
        every { contactoService.detalle(5, any(), ContextoBusquedaContacto.vincular) } returns reducido
        val token = jwtService.generateAccessToken(empleadoId = 7, rol = "analista")

        mockMvc.get("/api/v1/contactos/5?contexto=vincular") {
            header(HttpHeaders.AUTHORIZATION, "Bearer $token")
        }.andExpect {
            status { isOk() }
            jsonPath("$.data.id") { value(5) }
            jsonPath("$.data.tlf_1") { doesNotExist() }
            jsonPath("$.data.oportunidades") { isEmpty() }
            jsonPath("$.data.actividades") { isEmpty() }
        }

        verify(exactly = 0) { oportunidadesDeContacto.listar(any(), any()) }
        verify(exactly = 0) { tareaService.actividadesPorContacto(any(), any()) }
    }

    /** El controller propaga el usuario autenticado al filtro de visibilidad del servicio. */
    @Test
    fun `GET contactos por id propaga el usuario autenticado al servicio`() {
        val detalle =
            pe.quantum.crm.domain.contactos.dto.ContactoDetalleDto(
                id = 5, nombres = "Hugo", apellidos = "Rodríguez",
                email_1 = null, email_2 = null, tlf_1 = "964415122", tlf_2 = null, notas = null,
                empresas = emptyList(),
            )
        every { contactoService.detalle(5, any(), any()) } returns detalle
        every { oportunidadesDeContacto.listar(5, any()) } returns emptyList()
        every { tareaService.actividadesPorContacto(5, any()) } returns emptyList()
        val token = jwtService.generateAccessToken(empleadoId = 7, rol = "analista")

        mockMvc.get("/api/v1/contactos/5") {
            header(HttpHeaders.AUTHORIZATION, "Bearer $token")
        }.andExpect { status { isOk() } }

        verify {
            contactoService.detalle(
                5,
                UsuarioActual(id = 7, rol = "analista"),
                ContextoBusquedaContacto.listado,
            )
        }
    }

    /** El 404 del servicio (contacto fuera de alcance) llega al cliente como 404. */
    @Test
    fun `GET contactos por id fuera de alcance devuelve 404 NO_ENCONTRADO`() {
        every { contactoService.detalle(5, any(), any()) } throws
            pe.quantum.crm.shared.exception.NoEncontradoException("El contacto no existe")
        val token = jwtService.generateAccessToken(empleadoId = 7, rol = "analista")

        mockMvc.get("/api/v1/contactos/5") {
            header(HttpHeaders.AUTHORIZATION, "Bearer $token")
        }.andExpect {
            status { isNotFound() }
            jsonPath("$.error.code") { value("NO_ENCONTRADO") }
        }
    }

    /** matriz_permisos.md §2.3: un rol de apoyo solo edita contactos donde colabora. */
    @Test
    fun `PUT contactos fuera del alcance de un rol de apoyo devuelve 403 PERMISO_INSUFICIENTE`() {
        every { contactoService.actualizar(5, any(), any()) } throws
            pe.quantum.crm.shared.exception.PermisoInsuficienteException(
                "Tu rol es de apoyo: solo puedes editar contactos de las empresas donde colaboras",
            )
        val token = jwtService.generateAccessToken(empleadoId = 7, rol = "analista")

        mockMvc.put("/api/v1/contactos/5") {
            header(HttpHeaders.AUTHORIZATION, "Bearer $token")
            contentType = MediaType.APPLICATION_JSON
            content = """{"tlf_1":"999888777"}"""
        }.andExpect {
            status { isForbidden() }
            jsonPath("$.error.code") { value("PERMISO_INSUFICIENTE") }
        }
    }
}
