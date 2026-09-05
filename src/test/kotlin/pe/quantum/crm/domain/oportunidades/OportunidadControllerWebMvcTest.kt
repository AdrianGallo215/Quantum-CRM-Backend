package pe.quantum.crm.domain.oportunidades

import com.ninjasquad.springmockk.MockkBean
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
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
import org.springframework.test.web.servlet.patch
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.put
import pe.quantum.crm.config.security.JwtService
import pe.quantum.crm.domain.empleados.dto.EmpleadoResumen
import pe.quantum.crm.domain.oportunidades.dto.CambioEstadoDto
import pe.quantum.crm.domain.oportunidades.dto.ContactoVinculoRequest
import pe.quantum.crm.domain.oportunidades.dto.LogEstadoDto
import pe.quantum.crm.domain.oportunidades.dto.OportunidadDto
import pe.quantum.crm.domain.oportunidades.dto.OportunidadFiltros
import pe.quantum.crm.domain.oportunidades.dto.OportunidadItemDto
import pe.quantum.crm.shared.PageMeta
import pe.quantum.crm.shared.Paginado
import pe.quantum.crm.shared.exception.NoEncontradoException
import pe.quantum.crm.support.SinBaseDeDatosMocks
import java.time.Instant

/**
 * Tests de `DELETE /oportunidades/:id` (contrato_api.md §10, exclusivo admin, sin cuerpo)
 * y de la validacion de los campos numericos del body del POST (descuento, cantidad).
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
class OportunidadControllerWebMvcTest {
    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var jwtService: JwtService

    @MockkBean
    lateinit var oportunidadService: OportunidadService

    @Test
    fun `DELETE oportunidades id como admin devuelve 204`() {
        every { oportunidadService.eliminar(50) } just Runs
        val token = jwtService.generateAccessToken(empleadoId = 1, rol = "admin")

        mockMvc.delete("/api/v1/oportunidades/50") {
            header(HttpHeaders.AUTHORIZATION, "Bearer $token")
        }.andExpect {
            status { isNoContent() }
        }
        verify { oportunidadService.eliminar(50) }
    }

    @Test
    fun `DELETE oportunidades id como no-admin devuelve 403`() {
        val token = jwtService.generateAccessToken(empleadoId = 2, rol = "vendedor")

        mockMvc.delete("/api/v1/oportunidades/50") {
            header(HttpHeaders.AUTHORIZATION, "Bearer $token")
        }.andExpect {
            status { isForbidden() }
            jsonPath("$.error.code") { value("PERMISO_INSUFICIENTE") }
        }
        verify(exactly = 0) { oportunidadService.eliminar(any()) }
    }

    @Test
    fun `DELETE oportunidades id inexistente devuelve 404`() {
        every { oportunidadService.eliminar(999) } throws NoEncontradoException("La oportunidad no existe")
        val token = jwtService.generateAccessToken(empleadoId = 1, rol = "admin")

        mockMvc.delete("/api/v1/oportunidades/999") {
            header(HttpHeaders.AUTHORIZATION, "Bearer $token")
        }.andExpect {
            status { isNotFound() }
            jsonPath("$.error.code") { value("NO_ENCONTRADO") }
        }
    }

    // ---------------------------------------------------------------------
    // Validacion de campos numericos del body del POST.
    //
    // Un descuento negativo invierte el factor de descuento en MontoTotal.calcular
    // (1 - (-100/100) = 2) y duplica el monto_total, ademas de colarse por
    // PoliticaDescuento.excedeLimite, que solo compara descuento > limite. Debe
    // rechazarse en el borde, antes de llegar al servicio.
    //
    // Los mismos campos en el PUT viven ahora en `/oportunidades/:id/items/:itemId`
    // (D19): su validacion de borde esta en OportunidadItemControllerWebMvcTest.
    // ---------------------------------------------------------------------

    private fun tokenVendedor() = jwtService.generateAccessToken(empleadoId = 7, rol = "vendedor")

    private fun oportunidadDto() =
        OportunidadDto(
            id = 50, idEmpresa = 3, empresa = null, idVendedor = 7, vendedor = null,
            idFinanciadora = 1, financiadora = null,
            estado = "evaluacion_calidda",
            items =
                listOf(
                    OportunidadItemDto(
                        id = 5,
                        idModelo = 1,
                        modelo = null,
                        cantidad = 8,
                        precioVenta = "92000.00",
                        descuento = "3.00",
                        cuotaFinanciadora = "0.00",
                        montoItem = "714080.00",
                    ),
                ),
            montoTotal = "714080.00", garantia = true, fincParalelo = false,
            fichaVenta = null, driveFolderId = null, notas = null, motivoCierre = null,
            fechaCierreEstimado = null, createdAt = Instant.now(),
        )

    private fun postOportunidad(cuerpo: String) =
        mockMvc.post("/api/v1/oportunidades") {
            header(HttpHeaders.AUTHORIZATION, "Bearer ${tokenVendedor()}")
            contentType = MediaType.APPLICATION_JSON
            content = cuerpo
        }

    @Test
    fun `POST oportunidades con descuento negativo devuelve 400 VALIDACION`() {
        postOportunidad("""{"id_empresa":3,"id_modelo":1,"cantidad":8,"descuento":-100}""").andExpect {
            status { isBadRequest() }
            jsonPath("$.error.code") { value("VALIDACION") }
            jsonPath("$.error.field") { value("descuento") }
        }
        verify(exactly = 0) { oportunidadService.crear(any(), any()) }
    }

    @Test
    fun `POST oportunidades con descuento mayor a 100 devuelve 400 VALIDACION`() {
        postOportunidad("""{"id_empresa":3,"id_modelo":1,"cantidad":8,"descuento":150}""").andExpect {
            status { isBadRequest() }
            jsonPath("$.error.code") { value("VALIDACION") }
            jsonPath("$.error.field") { value("descuento") }
        }
        verify(exactly = 0) { oportunidadService.crear(any(), any()) }
    }

    @Test
    fun `POST oportunidades con cantidad cero devuelve 400 VALIDACION`() {
        postOportunidad("""{"id_empresa":3,"id_modelo":1,"cantidad":0}""").andExpect {
            status { isBadRequest() }
            jsonPath("$.error.code") { value("VALIDACION") }
            jsonPath("$.error.field") { value("cantidad") }
        }
        verify(exactly = 0) { oportunidadService.crear(any(), any()) }
    }

    @Test
    fun `POST oportunidades con cantidad negativa devuelve 400 VALIDACION`() {
        postOportunidad("""{"id_empresa":3,"id_modelo":1,"cantidad":-5}""").andExpect {
            status { isBadRequest() }
            jsonPath("$.error.code") { value("VALIDACION") }
        }
        verify(exactly = 0) { oportunidadService.crear(any(), any()) }
    }

    /**
     * Contrato §10, `PUT /oportunidades/:id/contactos/:contacto_id`:
     * Body `{ "rol_en_oportunidad": "Aprobador" }`, respuesta 200. El id del
     * contacto va en la URL y el servicio lo toma de ahi; el body NO lo lleva.
     *
     * Reutilizar el DTO del POST de vinculacion metia un `id_contacto` no-nulo,
     * sin default y con `@Positive` en un body donde el contrato no lo pone: el
     * cliente que seguia el contrato recibia 400 y el endpoint solo funcionaba
     * si adivinaba que debia mandar un id que el backend descarta.
     */
    @Test
    fun `PUT contacto de oportunidad acepta el body del contrato sin id_contacto`() {
        every { oportunidadService.actualizarContacto(50, 5, "Aprobador", any()) } returns
            ContactoVinculoRequest(idContacto = 5, rolEnOportunidad = "Aprobador")

        mockMvc.put("/api/v1/oportunidades/50/contactos/5") {
            header(HttpHeaders.AUTHORIZATION, "Bearer ${tokenVendedor()}")
            contentType = MediaType.APPLICATION_JSON
            content = """{"rol_en_oportunidad":"Aprobador"}"""
        }.andExpect {
            status { isOk() }
            jsonPath("$.data.id_contacto") { value(5) }
            jsonPath("$.data.rol_en_oportunidad") { value("Aprobador") }
        }
        verify { oportunidadService.actualizarContacto(50, 5, "Aprobador", any()) }
    }

    /** El rol puede limpiarse mandando el body vacio: `rol_en_oportunidad` es opcional. */
    @Test
    fun `PUT contacto de oportunidad con body vacio limpia el rol`() {
        every { oportunidadService.actualizarContacto(50, 5, null, any()) } returns
            ContactoVinculoRequest(idContacto = 5, rolEnOportunidad = null)

        mockMvc.put("/api/v1/oportunidades/50/contactos/5") {
            header(HttpHeaders.AUTHORIZATION, "Bearer ${tokenVendedor()}")
            contentType = MediaType.APPLICATION_JSON
            content = "{}"
        }.andExpect {
            status { isOk() }
        }
        verify { oportunidadService.actualizarContacto(50, 5, null, any()) }
    }

    /** El POST de vinculacion sigue exigiendo `id_contacto`: ahi si es parte del body. */
    @Test
    fun `POST contacto de oportunidad sigue rechazando un id_contacto no positivo`() {
        mockMvc.post("/api/v1/oportunidades/50/contactos") {
            header(HttpHeaders.AUTHORIZATION, "Bearer ${tokenVendedor()}")
            contentType = MediaType.APPLICATION_JSON
            content = """{"id_contacto":0,"rol_en_oportunidad":"Aprobador"}"""
        }.andExpect {
            status { isBadRequest() }
            jsonPath("$.error.code") { value("VALIDACION") }
        }
        verify(exactly = 0) { oportunidadService.vincularContacto(any(), any(), any()) }
    }

    // ---------------------------------------------------------------------
    // Ruteo del resto de endpoints del contrato §10: que cada uno llegue al
    // servicio con los parametros que la URL y el body traen, y que la
    // respuesta salga en el envelope { data, meta, error } con snake_case.
    // ---------------------------------------------------------------------

    @Test
    fun `GET oportunidades traduce los query params a filtros y paginacion`() {
        val filtros = slot<OportunidadFiltros>()
        every { oportunidadService.listar(capture(filtros), any(), 2, 50, "monto_total", "asc") } returns
            Paginado(listOf(oportunidadDto()), PageMeta(page = 2, perPage = 50, total = 120, totalPages = 3))

        mockMvc.get("/api/v1/oportunidades") {
            header(HttpHeaders.AUTHORIZATION, "Bearer ${tokenVendedor()}")
            param("estado", "facturado")
            param("id_empresa", "3")
            param("id_vendedor", "7")
            param("id_financiadora", "1")
            param("incluir_cerradas", "true")
            param("page", "2")
            param("per_page", "50")
            param("sort", "monto_total")
            param("dir", "asc")
        }.andExpect {
            status { isOk() }
            jsonPath("$.data[0].id") { value(50) }
            jsonPath("$.data[0].monto_total") { value("714080.00") }
            jsonPath("$.meta.page") { value(2) }
            jsonPath("$.meta.per_page") { value(50) }
            jsonPath("$.meta.total_pages") { value(3) }
        }

        assertThat(filtros.captured).isEqualTo(
            OportunidadFiltros(estado = "facturado", idEmpresa = 3, idVendedor = 7, idFinanciadora = 1, incluirCerradas = true),
        )
    }

    /** Sin query params: filtros vacios e `incluir_cerradas` en false (contrato §10). */
    @Test
    fun `GET oportunidades sin filtros excluye las cerradas por defecto`() {
        val filtros = slot<OportunidadFiltros>()
        every { oportunidadService.listar(capture(filtros), any(), null, null, null, null) } returns
            Paginado(emptyList(), PageMeta(page = 1, perPage = 20, total = 0, totalPages = 0))

        mockMvc.get("/api/v1/oportunidades") {
            header(HttpHeaders.AUTHORIZATION, "Bearer ${tokenVendedor()}")
        }.andExpect {
            status { isOk() }
            jsonPath("$.error") { value(null) }
        }

        assertThat(filtros.captured).isEqualTo(OportunidadFiltros())
    }

    @Test
    fun `GET oportunidades id devuelve el detalle`() {
        every { oportunidadService.detalle(50, any()) } returns oportunidadDto()

        mockMvc.get("/api/v1/oportunidades/50") {
            header(HttpHeaders.AUTHORIZATION, "Bearer ${tokenVendedor()}")
        }.andExpect {
            status { isOk() }
            jsonPath("$.data.id") { value(50) }
            jsonPath("$.data.id_empresa") { value(3) }
            jsonPath("$.data.estado") { value("evaluacion_calidda") }
        }
        verify { oportunidadService.detalle(50, any()) }
    }

    @Test
    fun `GET oportunidades id inexistente devuelve 404`() {
        every { oportunidadService.detalle(999, any()) } throws NoEncontradoException("La oportunidad no existe")

        mockMvc.get("/api/v1/oportunidades/999") {
            header(HttpHeaders.AUTHORIZATION, "Bearer ${tokenVendedor()}")
        }.andExpect {
            status { isNotFound() }
            jsonPath("$.error.code") { value("NO_ENCONTRADO") }
        }
    }

    @Test
    fun `POST oportunidades valido devuelve 201`() {
        every { oportunidadService.crear(any(), any()) } returns oportunidadDto()

        postOportunidad("""{"id_empresa":3,"id_modelo":1,"cantidad":8,"descuento":3.00}""").andExpect {
            status { isCreated() }
            jsonPath("$.data.id") { value(50) }
        }
        verify { oportunidadService.crear(any(), any()) }
    }

    @Test
    fun `PATCH estado devuelve el estado nuevo, el retroceso y las advertencias`() {
        every { oportunidadService.cambiarEstado(50, any(), any()) } returns
            CambioEstadoDto(estado = "documentos_legales", esRetroceso = false, advertencias = listOf("Visita técnica no fue registrado"))

        mockMvc.patch("/api/v1/oportunidades/50/estado") {
            header(HttpHeaders.AUTHORIZATION, "Bearer ${tokenVendedor()}")
            contentType = MediaType.APPLICATION_JSON
            content = """{"estado":"documentos_legales"}"""
        }.andExpect {
            status { isOk() }
            jsonPath("$.data.estado") { value("documentos_legales") }
            jsonPath("$.data.es_retroceso") { value(false) }
            jsonPath("$.data.advertencias[0]") { value("Visita técnica no fue registrado") }
        }
        verify { oportunidadService.cambiarEstado(50, any(), any()) }
    }

    @Test
    fun `PATCH estado sin estado en el body devuelve 400 VALIDACION`() {
        mockMvc.patch("/api/v1/oportunidades/50/estado") {
            header(HttpHeaders.AUTHORIZATION, "Bearer ${tokenVendedor()}")
            contentType = MediaType.APPLICATION_JSON
            content = """{"estado":"  "}"""
        }.andExpect {
            status { isBadRequest() }
            jsonPath("$.error.code") { value("VALIDACION") }
        }
        verify(exactly = 0) { oportunidadService.cambiarEstado(any(), any(), any()) }
    }

    @Test
    fun `GET log devuelve el historial de estados`() {
        every { oportunidadService.log(50, any()) } returns
            listOf(
                LogEstadoDto(
                    estadoAnterior = null,
                    estadoNuevo = "evaluacion_calidda",
                    changedAt = Instant.parse("2026-01-15T09:30:00Z"),
                    changedBy = EmpleadoResumen(id = 7, nombres = "Ana", apellidos = "Diaz"),
                ),
            )

        mockMvc.get("/api/v1/oportunidades/50/log") {
            header(HttpHeaders.AUTHORIZATION, "Bearer ${tokenVendedor()}")
        }.andExpect {
            status { isOk() }
            jsonPath("$.data[0].estado_anterior") { value(null) }
            jsonPath("$.data[0].estado_nuevo") { value("evaluacion_calidda") }
            jsonPath("$.data[0].changed_by.nombres") { value("Ana") }
        }
        verify { oportunidadService.log(50, any()) }
    }

    @Test
    fun `POST contacto de oportunidad valido devuelve 201`() {
        every { oportunidadService.vincularContacto(50, any(), any()) } returns
            ContactoVinculoRequest(idContacto = 5, rolEnOportunidad = "Aprobador")

        mockMvc.post("/api/v1/oportunidades/50/contactos") {
            header(HttpHeaders.AUTHORIZATION, "Bearer ${tokenVendedor()}")
            contentType = MediaType.APPLICATION_JSON
            content = """{"id_contacto":5,"rol_en_oportunidad":"Aprobador"}"""
        }.andExpect {
            status { isCreated() }
            jsonPath("$.data.id_contacto") { value(5) }
        }
        verify { oportunidadService.vincularContacto(50, any(), any()) }
    }

    @Test
    fun `DELETE contacto de oportunidad devuelve 204 sin cuerpo`() {
        every { oportunidadService.desvincularContacto(50, 5, any()) } just Runs

        mockMvc.delete("/api/v1/oportunidades/50/contactos/5") {
            header(HttpHeaders.AUTHORIZATION, "Bearer ${tokenVendedor()}")
        }.andExpect {
            status { isNoContent() }
        }
        verify { oportunidadService.desvincularContacto(50, 5, any()) }
    }
}
