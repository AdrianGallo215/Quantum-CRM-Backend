package pe.quantum.crm.domain.empresas

import com.ninjasquad.springmockk.MockkBean
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.verify
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.http.HttpHeaders
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.get
import pe.quantum.crm.config.security.JwtService
import pe.quantum.crm.domain.contactos.ContactoService
import pe.quantum.crm.domain.empresas.dto.ContactoDeEmpresaDto
import pe.quantum.crm.domain.empresas.dto.EmpresaDetalleDto
import pe.quantum.crm.domain.empresas.dto.EmpresaListaDto
import pe.quantum.crm.shared.Paginacion
import pe.quantum.crm.shared.Paginado
import pe.quantum.crm.shared.exception.NoEncontradoException
import pe.quantum.crm.support.SinBaseDeDatosMocks
import java.time.LocalDateTime

/** Tests de `DELETE /empresas/:id` (contrato_api.md §8): exclusivo admin, sin cuerpo. */
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
class EmpresaControllerWebMvcTest {
    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var jwtService: JwtService

    @MockkBean
    lateinit var empresaService: EmpresaService

    @MockkBean
    lateinit var contactoService: ContactoService

    @Test
    fun `DELETE empresas id como admin devuelve 204`() {
        every { empresaService.eliminar(7) } just Runs
        val token = jwtService.generateAccessToken(empleadoId = 1, rol = "admin")

        mockMvc.delete("/api/v1/empresas/7") {
            header(HttpHeaders.AUTHORIZATION, "Bearer $token")
        }.andExpect {
            status { isNoContent() }
        }
        verify { empresaService.eliminar(7) }
    }

    @Test
    fun `DELETE empresas id como no-admin devuelve 403`() {
        val token = jwtService.generateAccessToken(empleadoId = 2, rol = "gerencia")

        mockMvc.delete("/api/v1/empresas/7") {
            header(HttpHeaders.AUTHORIZATION, "Bearer $token")
        }.andExpect {
            status { isForbidden() }
            jsonPath("$.error.code") { value("PERMISO_INSUFICIENTE") }
        }
        verify(exactly = 0) { empresaService.eliminar(any()) }
    }

    @Test
    fun `GET empresas id serializa contactos con toma_decision tal como los devuelve el servicio`() {
        every { empresaService.detalle(3, any()) } returns
            EmpresaDetalleDto(
                id = 3,
                ruc = "20260426827",
                razonSocial = "Transp. Negociaciones Sta. Anita S.A.",
                actividadEcon = null,
                ciiu = null,
                sectorIndustrial = null,
                estadoSunat = null,
                condicionSunat = null,
                direccionFiscal = null,
                ubicacionReal = null,
                distrito = null,
                provincia = null,
                departamento = null,
                avalFiador = null,
                origenLead = null,
                estadoCartera = "prospeccion",
                fileDrive = null,
                driveFolderId = null,
                sitioWeb = null,
                notas = null,
                idVendedor = null,
                vendedor = null,
                segmentos = emptyList(),
                contactos =
                    listOf(
                        ContactoDeEmpresaDto(
                            id = 5,
                            nombres = "Hugo",
                            apellidos = "Rodríguez",
                            cargo = "Gerente",
                            tomaDecision = true,
                            esPrincipal = true,
                            email_1 = null,
                            tlf_1 = "964415122",
                        ),
                    ),
                createdAt = LocalDateTime.of(2026, 5, 1, 10, 0),
                createdBy = 1,
            )
        val token = jwtService.generateAccessToken(empleadoId = 1, rol = "admin")

        mockMvc.get("/api/v1/empresas/3") {
            header(HttpHeaders.AUTHORIZATION, "Bearer $token")
        }.andExpect {
            status { isOk() }
            jsonPath("$.data.contactos[0].id") { value(5) }
            jsonPath("$.data.contactos[0].cargo") { value("Gerente") }
            jsonPath("$.data.contactos[0].toma_decision") { value(true) }
        }
        // El detalle ya viene completo del servicio: el controller no recompone contactos.
        verify(exactly = 0) { contactoService.contactosDeEmpresa(any()) }
    }

    @Test
    fun `GET empresas devuelve contactos_count tal como lo calcula el servicio, sin N+1 en el controller`() {
        every { empresaService.listar(any(), any(), any(), any(), any(), any()) } returns
            Paginado(
                items =
                    listOf(
                        EmpresaListaDto(
                            id = 3,
                            ruc = "20260426827",
                            razonSocial = "Transp. Negociaciones Sta. Anita S.A.",
                            estadoSunat = null,
                            condicionSunat = null,
                            estadoCartera = "prospeccion",
                            distrito = null,
                            idVendedor = null,
                            vendedor = null,
                            segmentos = emptyList(),
                            contactosCount = 2,
                        ),
                    ),
                meta = Paginacion.meta(1, 20, 1),
            )
        val token = jwtService.generateAccessToken(empleadoId = 1, rol = "admin")

        mockMvc.get("/api/v1/empresas") {
            header(HttpHeaders.AUTHORIZATION, "Bearer $token")
        }.andExpect {
            status { isOk() }
            jsonPath("$.data[0].contactos_count") { value(2) }
        }
        verify(exactly = 0) { contactoService.countPorEmpresa(any()) }
    }

    @Test
    fun `DELETE empresas id inexistente devuelve 404`() {
        every { empresaService.eliminar(99) } throws NoEncontradoException("La empresa no existe")
        val token = jwtService.generateAccessToken(empleadoId = 1, rol = "admin")

        mockMvc.delete("/api/v1/empresas/99") {
            header(HttpHeaders.AUTHORIZATION, "Bearer $token")
        }.andExpect {
            status { isNotFound() }
            jsonPath("$.error.code") { value("NO_ENCONTRADO") }
        }
    }
}
