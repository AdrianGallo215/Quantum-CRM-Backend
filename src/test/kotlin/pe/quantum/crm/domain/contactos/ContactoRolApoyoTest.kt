package pe.quantum.crm.domain.contactos

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.data.domain.PageImpl
import org.springframework.data.domain.PageRequest
import org.springframework.data.jpa.domain.Specification
import pe.quantum.crm.domain.contactos.dto.ContextoBusquedaContacto
import pe.quantum.crm.domain.empresas.EmpresaService
import pe.quantum.crm.domain.tareas.TareaService
import pe.quantum.crm.shared.security.UsuarioActual
import java.time.LocalDateTime

/**
 * Visibilidad de contactos para los roles de apoyo (`analista`/`otro`), que no
 * tienen cartera propia y solo alcanzan lo que colaboran via tarea
 * (matriz_permisos.md §1).
 *
 * Archivo aparte de `ContactoServiceImplTest` (lectura) y
 * `ContactoServiceImplEscrituraTest` (escritura), mismo criterio que
 * `EmpresaRolApoyoTest`: la regla de visibilidad es su propia unidad y merece un
 * sitio donde se lea entera.
 */
class ContactoRolApoyoTest {
    private val contactoRepository = mockk<ContactoRepository>()
    private val empresaContactoRepository = mockk<EmpresaContactoRepository>()
    private val empresaService = mockk<EmpresaService>()
    private val tareaService = mockk<TareaService>()
    private val service =
        ContactoServiceImpl(contactoRepository, empresaContactoRepository, empresaService, tareaService)

    private val analista = UsuarioActual(id = 7, rol = "analista")
    private val otro = UsuarioActual(id = 8, rol = "otro")
    private val vendedor = UsuarioActual(id = 42, rol = "vendedor")
    private val admin = UsuarioActual(id = 1, rol = "admin")

    private fun contacto(id: Long = 1) =
        Contacto(
            id = id,
            nombres = "Hugo",
            apellidos = "Rodríguez",
            email_1 = "hugo@transportes.pe",
            tlf_1 = "964415122",
            notas = "Prefiere WhatsApp",
            createdAt = LocalDateTime.now(),
            createdBy = 1,
            updatedAt = LocalDateTime.now(),
            updatedBy = 1,
        )

    /** Devuelve una pagina con un contacto y sin vinculos, para los casos que llegan al repositorio. */
    private fun paginaConUnContacto() {
        every { contactoRepository.findAll(any<Specification<Contacto>>(), any<PageRequest>()) } returns
            PageImpl(listOf(contacto()), PageRequest.of(0, 20), 1)
        every { empresaContactoRepository.findByIdIdContacto(1) } returns emptyList()
        every { empresaService.resumenPorIds(emptyList()) } returns emptyMap()
    }

    private fun buscarListado(usuario: UsuarioActual) =
        service.buscar(
            q = null,
            idEmpresa = null,
            usuario = usuario,
            page = null,
            perPage = null,
            sort = null,
            dir = null,
            contexto = ContextoBusquedaContacto.listado,
        )

    // ── R1: el listado consulta la colaboracion ────────────────

    @Test
    fun `el listado de un analista resuelve sus contactos desde las empresas donde colabora`() {
        every { tareaService.idsEmpresasDondeColabora(7) } returns setOf(3L, 4L)
        every { empresaContactoRepository.findByIdIdEmpresaIn(setOf(3L, 4L)) } returns
            listOf(
                EmpresaContacto(id = EmpresaContactoId(idEmpresa = 3, idContacto = 1)),
                EmpresaContacto(id = EmpresaContactoId(idEmpresa = 4, idContacto = 2)),
            )
        paginaConUnContacto()

        buscarListado(analista)

        verify(exactly = 1) { tareaService.idsEmpresasDondeColabora(7) }
        verify(exactly = 1) { empresaContactoRepository.findByIdIdEmpresaIn(setOf(3L, 4L)) }
    }

    @Test
    fun `el rol otro recibe el mismo tratamiento que analista`() {
        every { tareaService.idsEmpresasDondeColabora(8) } returns setOf(5L)
        every { empresaContactoRepository.findByIdIdEmpresaIn(setOf(5L)) } returns
            listOf(EmpresaContacto(id = EmpresaContactoId(idEmpresa = 5, idContacto = 1)))
        paginaConUnContacto()

        buscarListado(otro)

        verify(exactly = 1) { tareaService.idsEmpresasDondeColabora(8) }
    }

    /**
     * Sin colaboraciones no hay ni una consulta a `empresa_contactos`: el conjunto
     * ya se sabe vacio. La Specification resultante debe filtrar todo, no dejar
     * pasar el listado completo — eso lo verifica ContactoBusquedaSpecificationTest.
     */
    @Test
    fun `un rol de apoyo sin colaboraciones no consulta los vinculos`() {
        every { tareaService.idsEmpresasDondeColabora(7) } returns emptySet()
        every { contactoRepository.findAll(any<Specification<Contacto>>(), any<PageRequest>()) } returns
            PageImpl(emptyList(), PageRequest.of(0, 20), 0)

        assertThat(buscarListado(analista).items).isEmpty()

        verify(exactly = 0) { empresaContactoRepository.findByIdIdEmpresaIn(any()) }
    }

    // ── R4: los demas roles no cambian ─────────────────────────

    @Test
    fun `un vendedor no arrastra el filtro de colaboracion`() {
        paginaConUnContacto()

        assertThat(buscarListado(vendedor).items).hasSize(1)

        verify(exactly = 0) { tareaService.idsEmpresasDondeColabora(any()) }
        verify(exactly = 0) { empresaContactoRepository.findByIdIdEmpresaIn(any()) }
    }

    @Test
    fun `un admin no arrastra el filtro de colaboracion`() {
        paginaConUnContacto()

        assertThat(buscarListado(admin).items).hasSize(1)

        verify(exactly = 0) { tareaService.idsEmpresasDondeColabora(any()) }
    }

    /**
     * El listado de un rol de apoyo sigue devolviendo la fila completa: el recorte
     * de campos es exclusivo del modo `vincular`, no de este.
     */
    @Test
    fun `el listado de un rol de apoyo devuelve la fila completa, no reducida`() {
        every { tareaService.idsEmpresasDondeColabora(7) } returns setOf(3L)
        every { empresaContactoRepository.findByIdIdEmpresaIn(setOf(3L)) } returns
            listOf(EmpresaContacto(id = EmpresaContactoId(idEmpresa = 3, idContacto = 1)))
        paginaConUnContacto()

        val fila = buscarListado(analista).items.first()

        assertThat(fila.tlf_1).isEqualTo("964415122")
        assertThat(fila.email_1).isEqualTo("hugo@transportes.pe")
        assertThat(fila.notas).isEqualTo("Prefiere WhatsApp")
    }
}
