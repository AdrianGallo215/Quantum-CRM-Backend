package pe.quantum.crm.domain.contactos

import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.data.domain.PageImpl
import org.springframework.data.domain.PageRequest
import pe.quantum.crm.domain.empresas.EmpresaService
import pe.quantum.crm.shared.security.UsuarioActual
import java.time.LocalDateTime

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
            tlf1 = "964415122",
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
            pe.quantum.crm.domain.empresas.dto.EmpresaVinculo(id = 10, razonSocial = "ABC", idVendedor = null, estadoCartera = "prospeccion")
        every { empresaContactoRepository.findByIdIdEmpresa(10) } returns
            listOf(EmpresaContacto(id = EmpresaContactoId(idEmpresa = 10, idContacto = 1)))
        every { contactoRepository.findAll(any(), any<PageRequest>()) } returns
            PageImpl(listOf(contacto()), PageRequest.of(0, 20), 1)
        every { empresaContactoRepository.findByIdIdContacto(1) } returns emptyList()
        every { empresaService.resumenPorIds(emptyList()) } returns emptyMap()

        val resultado = service.buscar(q = null, idEmpresa = 10, usuario = usuario, page = null, perPage = null, sort = null, dir = null)

        assertThat(resultado.items).hasSize(1)
    }
}
