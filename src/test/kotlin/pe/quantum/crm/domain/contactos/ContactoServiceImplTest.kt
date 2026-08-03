package pe.quantum.crm.domain.contactos

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.data.domain.PageImpl
import org.springframework.data.domain.PageRequest
import pe.quantum.crm.domain.empresas.EmpresaService
import pe.quantum.crm.domain.empresas.dto.EmpresaResumen
import pe.quantum.crm.domain.empresas.dto.EmpresaVinculo
import pe.quantum.crm.shared.security.UsuarioActual
import java.time.LocalDateTime
import java.util.Optional

class ContactoServiceImplTest {
    private val contactoRepository = mockk<ContactoRepository>()
    private val empresaContactoRepository = mockk<EmpresaContactoRepository>()
    private val empresaService = mockk<EmpresaService>()
    private val service = ContactoServiceImpl(contactoRepository, empresaContactoRepository, empresaService)

    private fun contacto(id: Long = 1) =
        Contacto(
            id = id,
            nombres = "Hugo",
            apellidos = "Rodríguez",
            tlf_1 = "964415122",
            createdAt = LocalDateTime.now(),
            createdBy = 1,
            updatedAt = LocalDateTime.now(),
            updatedBy = 1,
        )

    private val usuario = UsuarioActual(id = 1, rol = "admin")

    @Test
    fun `buscar sin filtros devuelve Paginado con meta correcto`() {
        val entidad = contacto()
        every { contactoRepository.findAll(any(), any<PageRequest>()) } returns
            PageImpl(listOf(entidad), PageRequest.of(0, 20), 1)
        every { empresaContactoRepository.findByIdIdContacto(1) } returns emptyList()
        every { empresaService.resumenPorIds(emptyList()) } returns emptyMap()

        val resultado = service.buscar(q = null, idEmpresa = null, usuario = usuario, page = null, perPage = null, sort = null, dir = null)

        assertThat(resultado.items).hasSize(1)
        assertThat(resultado.items.first().id).isEqualTo(1)
        assertThat(resultado.items.first().oportunidadesCount).isEqualTo(0)
        assertThat(resultado.meta.page).isEqualTo(1)
        assertThat(resultado.meta.perPage).isEqualTo(20)
        assertThat(resultado.meta.total).isEqualTo(1)
    }

    @Test
    fun `buscar con id_empresa valida visibilidad y filtra por contactos vinculados`() {
        every { empresaService.vinculoVisible(10, usuario) } returns
            EmpresaVinculo(id = 10, razonSocial = "ABC", idVendedor = null, estadoCartera = "prospeccion")
        every { empresaContactoRepository.findByIdIdEmpresa(10) } returns
            listOf(EmpresaContacto(id = EmpresaContactoId(idEmpresa = 10, idContacto = 1)))
        every { contactoRepository.findAll(any(), any<PageRequest>()) } returns
            PageImpl(listOf(contacto()), PageRequest.of(0, 20), 1)
        every { empresaContactoRepository.findByIdIdContacto(1) } returns emptyList()
        every { empresaService.resumenPorIds(emptyList()) } returns emptyMap()

        val resultado = service.buscar(q = null, idEmpresa = 10, usuario = usuario, page = null, perPage = null, sort = null, dir = null)

        assertThat(resultado.items).hasSize(1)
    }

    @Test
    fun `detalle arma empresas con cargo, toma_decision, es_principal y segmentos`() {
        val entidad = contacto()
        every { contactoRepository.findById(1) } returns Optional.of(entidad)
        every { empresaContactoRepository.findByIdIdContacto(1) } returns
            listOf(
                EmpresaContacto(
                    id = EmpresaContactoId(idEmpresa = 3, idContacto = 1),
                    cargo = "Gerente",
                    tomaDecision = true,
                    esPrincipal = true,
                ),
            )
        every { empresaService.resumenPorIds(listOf(3L)) } returns
            mapOf(3L to EmpresaResumen(id = 3, razonSocial = "Transp. Sta. Anita S.A.", distrito = null))
        every { empresaService.segmentosPorIds(listOf(3L)) } returns mapOf(3L to listOf("interprovincial"))

        val resultado = service.detalle(1)

        assertThat(resultado.empresas).hasSize(1)
        val empresa = resultado.empresas.first()
        assertThat(empresa.cargo).isEqualTo("Gerente")
        assertThat(empresa.tomaDecision).isTrue()
        assertThat(empresa.esPrincipal).isTrue()
        assertThat(empresa.segmentos).containsExactly("interprovincial")
    }

    @Test
    fun `buscar con un sort fuera de la allowlist lanza ValidacionException, no revienta con 500`() {
        assertThatThrownBy {
            service.buscar(q = null, idEmpresa = null, usuario = usuario, page = null, perPage = null, sort = "drop", dir = null)
        }.isInstanceOf(pe.quantum.crm.shared.exception.ValidacionException::class.java)
            .hasMessageContaining("nombres")
            .hasMessageContaining("apellidos")
    }

    @Test
    fun `buscar acepta un sort de la allowlist en snake_case`() {
        every { contactoRepository.findAll(any(), any<PageRequest>()) } returns
            PageImpl(listOf(contacto()), PageRequest.of(0, 20), 1)
        every { empresaContactoRepository.findByIdIdContacto(1) } returns emptyList()
        every { empresaService.resumenPorIds(emptyList()) } returns emptyMap()

        val resultado =
            service.buscar(q = null, idEmpresa = null, usuario = usuario, page = null, perPage = null, sort = "created_at", dir = "asc")

        assertThat(resultado.items).hasSize(1)
    }

    @Test
    fun `countPorEmpresas resuelve todas las empresas de la pagina en una sola consulta`() {
        every { empresaContactoRepository.findByIdIdEmpresaIn(setOf(3L, 7L)) } returns
            listOf(
                EmpresaContacto(id = EmpresaContactoId(idEmpresa = 3, idContacto = 1)),
                EmpresaContacto(id = EmpresaContactoId(idEmpresa = 3, idContacto = 2)),
                EmpresaContacto(id = EmpresaContactoId(idEmpresa = 7, idContacto = 1)),
            )

        val resultado = service.countPorEmpresas(listOf(3L, 7L))

        assertThat(resultado).containsExactlyInAnyOrderEntriesOf(mapOf(3L to 2, 7L to 1))
        verify(exactly = 1) { empresaContactoRepository.findByIdIdEmpresaIn(any()) }
    }

    @Test
    fun `countPorEmpresas con lista vacia no toca el repositorio`() {
        assertThat(service.countPorEmpresas(emptyList())).isEmpty()

        verify(exactly = 0) { empresaContactoRepository.findByIdIdEmpresaIn(any()) }
    }

    @Test
    fun `contactosDeEmpresa devuelve cargo y toma_decision del vinculo, no del contacto`() {
        every { empresaContactoRepository.findByIdIdEmpresa(3) } returns
            listOf(
                EmpresaContacto(
                    id = EmpresaContactoId(idEmpresa = 3, idContacto = 1),
                    cargo = "Gerente",
                    tomaDecision = true,
                    esPrincipal = true,
                ),
            )
        every { contactoRepository.findAllById(listOf(1L)) } returns listOf(contacto())

        val resultado = service.contactosDeEmpresa(3)

        assertThat(resultado).hasSize(1)
        assertThat(resultado.first().cargo).isEqualTo("Gerente")
        assertThat(resultado.first().tomaDecision).isTrue()
        assertThat(resultado.first().esPrincipal).isTrue()
    }

    @Test
    fun `detalle de un contacto inexistente lanza NoEncontradoException`() {
        every { contactoRepository.findById(99) } returns Optional.empty()

        assertThatThrownBy { service.detalle(99) }
            .isInstanceOf(pe.quantum.crm.shared.exception.NoEncontradoException::class.java)
    }
}
