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
    private val tareaService = mockk<pe.quantum.crm.domain.tareas.TareaService>()
    private val service =
        ContactoServiceImpl(contactoRepository, empresaContactoRepository, empresaService, tareaService)

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

        val resultado = service.detalle(1, usuario)

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

        assertThatThrownBy { service.detalle(99, usuario) }
            .isInstanceOf(pe.quantum.crm.shared.exception.NoEncontradoException::class.java)
    }

    /**
     * Cada fila del listado arrastra sus empresas con el cargo del vinculo. La
     * empresa que el modulo de empresas no devuelve (borrada o fuera del alcance
     * del usuario) se cae de la lista en vez de reventar el listado entero.
     */
    @Test
    fun `buscar arma las empresas de cada fila y descarta las que empresas no resuelve`() {
        every { contactoRepository.findAll(any(), any<PageRequest>()) } returns
            PageImpl(listOf(contacto()), PageRequest.of(0, 20), 1)
        every { empresaContactoRepository.findByIdIdContacto(1) } returns
            listOf(
                EmpresaContacto(id = EmpresaContactoId(idEmpresa = 3, idContacto = 1), cargo = "Gerente"),
                EmpresaContacto(id = EmpresaContactoId(idEmpresa = 4, idContacto = 1), cargo = "Asesor"),
            )
        every { empresaService.resumenPorIds(listOf(3L, 4L)) } returns
            mapOf(3L to EmpresaResumen(id = 3, razonSocial = "Transp. Sta. Anita S.A.", distrito = "Ate"))

        val resultado = service.buscar(q = null, idEmpresa = null, usuario = usuario, page = null, perPage = null, sort = null, dir = null)

        val fila = resultado.items.first()
        assertThat(fila.nombres).isEqualTo("Hugo")
        assertThat(fila.apellidos).isEqualTo("Rodríguez")
        assertThat(fila.tlf_1).isEqualTo("964415122")
        assertThat(fila.empresas).hasSize(1)
        assertThat(fila.empresas.first().id).isEqualTo(3)
        assertThat(fila.empresas.first().razonSocial).isEqualTo("Transp. Sta. Anita S.A.")
        assertThat(fila.empresas.first().cargo).isEqualTo("Gerente")
    }

    /** El detalle descarta igual que el listado la empresa que no se resuelve. */
    @Test
    fun `detalle omite la empresa que el modulo de empresas no resuelve`() {
        every { contactoRepository.findById(1) } returns Optional.of(contacto())
        every { empresaContactoRepository.findByIdIdContacto(1) } returns
            listOf(EmpresaContacto(id = EmpresaContactoId(idEmpresa = 3, idContacto = 1)))
        every { empresaService.resumenPorIds(listOf(3L)) } returns emptyMap()
        every { empresaService.segmentosPorIds(listOf(3L)) } returns emptyMap()

        assertThat(service.detalle(1, usuario).empresas).isEmpty()
    }

    /** El principal va primero: es el contacto que el vendedor ve al abrir la empresa. */
    @Test
    fun `contactosDeEmpresa pone el contacto principal al principio`() {
        every { empresaContactoRepository.findByIdIdEmpresa(3) } returns
            listOf(
                EmpresaContacto(id = EmpresaContactoId(idEmpresa = 3, idContacto = 1), esPrincipal = false),
                EmpresaContacto(id = EmpresaContactoId(idEmpresa = 3, idContacto = 2), esPrincipal = true),
            )
        every { contactoRepository.findAllById(listOf(1L, 2L)) } returns listOf(contacto(1), contacto(2))

        val resultado = service.contactosDeEmpresa(3)

        assertThat(resultado.map { it.id }).containsExactly(2, 1)
    }

    @Test
    fun `contactosDeEmpresa sin vinculos devuelve vacio sin consultar contactos`() {
        every { empresaContactoRepository.findByIdIdEmpresa(3) } returns emptyList()

        assertThat(service.contactosDeEmpresa(3)).isEmpty()

        verify(exactly = 0) { contactoRepository.findAllById(any()) }
    }

    @Test
    fun `countPorEmpresa devuelve el conteo del repositorio como Int`() {
        every { empresaContactoRepository.countByIdIdEmpresa(3) } returns 4L

        assertThat(service.countPorEmpresa(3)).isEqualTo(4)
    }

    @Test
    fun `existe delega en el repositorio`() {
        every { contactoRepository.existsById(1) } returns true
        every { contactoRepository.existsById(99) } returns false

        assertThat(service.existe(1)).isTrue()
        assertThat(service.existe(99)).isFalse()
    }

    /** `resumenPorIds` deduplica antes de consultar: lo llaman tareas y oportunidades con ids repetidos. */
    @Test
    fun `resumenPorIds deduplica los ids y devuelve el resumen indexado`() {
        every { contactoRepository.findAllById(setOf(1L, 2L)) } returns listOf(contacto(1), contacto(2))

        val resultado = service.resumenPorIds(listOf(1L, 2L, 1L))

        assertThat(resultado.keys).containsExactlyInAnyOrder(1L, 2L)
        assertThat(resultado[1]?.nombres).isEqualTo("Hugo")
        assertThat(resultado[1]?.apellidos).isEqualTo("Rodríguez")
        assertThat(resultado[1]?.tlf_1).isEqualTo("964415122")
        verify(exactly = 1) { contactoRepository.findAllById(any()) }
    }
}
