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

    // ── R5: modo vincular, respuesta reducida ──────────────────

    private fun buscarParaVincular(
        usuario: UsuarioActual,
        q: String? = null,
    ) = service.buscar(
        q = q,
        idEmpresa = null,
        usuario = usuario,
        page = null,
        perPage = null,
        sort = null,
        dir = null,
        contexto = ContextoBusquedaContacto.vincular,
    )

    /**
     * En el buscador de vinculacion el rol de apoyo alcanza todo el CRM — si no,
     * no podria vincular un contacto que todavia no conoce — asi que aqui NO se
     * consulta la colaboracion.
     */
    @Test
    fun `en modo vincular un rol de apoyo no arrastra el filtro de colaboracion`() {
        paginaConUnContacto()

        assertThat(buscarParaVincular(analista).items).hasSize(1)

        verify(exactly = 0) { tareaService.idsEmpresasDondeColabora(any()) }
    }

    /** Lo que se recorta es el contenido: solo el nombre, nada mas. */
    @Test
    fun `en modo vincular la fila de un rol de apoyo solo lleva el nombre`() {
        every { contactoRepository.findAll(any<Specification<Contacto>>(), any<PageRequest>()) } returns
            PageImpl(listOf(contacto()), PageRequest.of(0, 20), 1)

        val fila = buscarParaVincular(analista).items.first()

        assertThat(fila.id).isEqualTo(1)
        assertThat(fila.nombres).isEqualTo("Hugo")
        assertThat(fila.apellidos).isEqualTo("Rodríguez")
        assertThat(fila.tlf_1).isNull()
        assertThat(fila.tlf_2).isNull()
        assertThat(fila.email_1).isNull()
        assertThat(fila.email_2).isNull()
        assertThat(fila.notas).isNull()
        assertThat(fila.empresas).isEmpty()
        assertThat(fila.oportunidadesCount).isZero()
    }

    /**
     * La fila reducida no consulta los vinculos por fila: ademas de no exponerlos,
     * se ahorra la consulta por contacto que hace `toListaDto`.
     */
    @Test
    fun `la fila reducida no consulta los vinculos del contacto`() {
        every { contactoRepository.findAll(any<Specification<Contacto>>(), any<PageRequest>()) } returns
            PageImpl(listOf(contacto()), PageRequest.of(0, 20), 1)

        buscarParaVincular(analista)

        verify(exactly = 0) { empresaContactoRepository.findByIdIdContacto(any()) }
    }

    /** R4/R6: el modo vincular no cambia nada para quien si tiene cartera. */
    @Test
    fun `en modo vincular un vendedor sigue viendo la fila completa`() {
        paginaConUnContacto()

        val fila = buscarParaVincular(vendedor).items.first()

        assertThat(fila.tlf_1).isEqualTo("964415122")
        assertThat(fila.email_1).isEqualTo("hugo@transportes.pe")
    }

    @Test
    fun `en modo vincular un admin sigue viendo la fila completa`() {
        paginaConUnContacto()

        assertThat(buscarParaVincular(admin).items.first().tlf_1).isEqualTo("964415122")
    }

    // ── R2 / R8: detalle ───────────────────────────────────────

    private fun contactoExiste(id: Long = 1) {
        every { contactoRepository.findById(id) } returns java.util.Optional.of(contacto(id))
    }

    private fun colaboraEn(
        idEmpleado: Long,
        empresas: Set<Long>,
        contactos: List<Long>,
    ) {
        every { tareaService.idsEmpresasDondeColabora(idEmpleado) } returns empresas
        if (empresas.isNotEmpty()) {
            every { empresaContactoRepository.findByIdIdEmpresaIn(empresas) } returns
                contactos.map { EmpresaContacto(id = EmpresaContactoId(idEmpresa = empresas.first(), idContacto = it)) }
        }
    }

    /** IDOR (CLAUDE.md regla 14): contacto fuera de alcance -> 404, nunca 403. */
    @Test
    fun `el detalle de un contacto fuera de alcance devuelve 404 para un rol de apoyo`() {
        contactoExiste(1)
        colaboraEn(idEmpleado = 7, empresas = setOf(99L), contactos = listOf(50L))

        org.assertj.core.api.Assertions
            .assertThatThrownBy { service.detalle(1, analista, ContextoBusquedaContacto.listado) }
            .isInstanceOf(pe.quantum.crm.shared.exception.NoEncontradoException::class.java)
    }

    @Test
    fun `el detalle de un rol de apoyo sin colaboraciones devuelve 404`() {
        contactoExiste(1)
        every { tareaService.idsEmpresasDondeColabora(7) } returns emptySet()

        org.assertj.core.api.Assertions
            .assertThatThrownBy { service.detalle(1, analista, ContextoBusquedaContacto.listado) }
            .isInstanceOf(pe.quantum.crm.shared.exception.NoEncontradoException::class.java)
    }

    @Test
    fun `el detalle de un contacto dentro de alcance se devuelve completo`() {
        contactoExiste(1)
        colaboraEn(idEmpleado = 7, empresas = setOf(3L), contactos = listOf(1L))
        every { empresaContactoRepository.findByIdIdContacto(1) } returns emptyList()
        every { empresaService.resumenPorIds(emptyList()) } returns emptyMap()
        every { empresaService.segmentosPorIds(emptyList()) } returns emptyMap()

        val detalle = service.detalle(1, analista, ContextoBusquedaContacto.listado)

        assertThat(detalle.tlf_1).isEqualTo("964415122")
        assertThat(detalle.email_1).isEqualTo("hugo@transportes.pe")
    }

    /** R8: en modo vincular el detalle llega, pero recortado. */
    @Test
    fun `en modo vincular el detalle de un contacto fuera de alcance llega reducido, no 404`() {
        contactoExiste(1)

        val detalle = service.detalle(1, analista, ContextoBusquedaContacto.vincular)

        assertThat(detalle.id).isEqualTo(1)
        assertThat(detalle.nombres).isEqualTo("Hugo")
        assertThat(detalle.apellidos).isEqualTo("Rodríguez")
        assertThat(detalle.tlf_1).isNull()
        assertThat(detalle.email_1).isNull()
        assertThat(detalle.notas).isNull()
        assertThat(detalle.empresas).isEmpty()
        assertThat(detalle.oportunidades).isEmpty()
        assertThat(detalle.actividades).isEmpty()
    }

    @Test
    fun `en modo vincular el detalle no consulta la colaboracion ni los vinculos`() {
        contactoExiste(1)

        service.detalle(1, analista, ContextoBusquedaContacto.vincular)

        verify(exactly = 0) { tareaService.idsEmpresasDondeColabora(any()) }
        verify(exactly = 0) { empresaContactoRepository.findByIdIdContacto(any()) }
    }

    /** El 404 por inexistente se mantiene en los dos modos. */
    @Test
    fun `en modo vincular un contacto inexistente sigue siendo 404`() {
        every { contactoRepository.findById(99) } returns java.util.Optional.empty()

        org.assertj.core.api.Assertions
            .assertThatThrownBy { service.detalle(99, analista, ContextoBusquedaContacto.vincular) }
            .isInstanceOf(pe.quantum.crm.shared.exception.NoEncontradoException::class.java)
    }

    /** R4: un vendedor no pierde nada. */
    @Test
    fun `el detalle de un vendedor no consulta la colaboracion`() {
        contactoExiste(1)
        every { empresaContactoRepository.findByIdIdContacto(1) } returns emptyList()
        every { empresaService.resumenPorIds(emptyList()) } returns emptyMap()
        every { empresaService.segmentosPorIds(emptyList()) } returns emptyMap()

        assertThat(service.detalle(1, vendedor, ContextoBusquedaContacto.listado).tlf_1).isEqualTo("964415122")

        verify(exactly = 0) { tareaService.idsEmpresasDondeColabora(any()) }
    }

    // ── R7 / R10: escritura ────────────────────────────────────

    private fun requestDeActualizacion() = pe.quantum.crm.domain.contactos.dto.ActualizarContactoRequest(tlf_1 = "999888777")

    /**
     * 403 y no 404 a proposito (decision de producto, R10): en modo vincular este
     * mismo usuario puede ver el contacto por nombre, asi que su existencia no es
     * secreta para el — esconderlo al editar mentiria. Mismo criterio que
     * `EmpresaServiceImpl.rechazarSiEsApoyo`.
     */
    @Test
    fun `un rol de apoyo no puede editar un contacto fuera de su alcance`() {
        contactoExiste(1)
        colaboraEn(idEmpleado = 7, empresas = setOf(99L), contactos = listOf(50L))

        org.assertj.core.api.Assertions
            .assertThatThrownBy { service.actualizar(1, requestDeActualizacion(), analista) }
            .isInstanceOf(pe.quantum.crm.shared.exception.PermisoInsuficienteException::class.java)
            .hasMessageContaining("apoyo")

        verify(exactly = 0) { contactoRepository.save(any()) }
    }

    @Test
    fun `el rol otro tampoco puede editar un contacto fuera de su alcance`() {
        contactoExiste(1)
        every { tareaService.idsEmpresasDondeColabora(8) } returns emptySet()

        org.assertj.core.api.Assertions
            .assertThatThrownBy { service.actualizar(1, requestDeActualizacion(), otro) }
            .isInstanceOf(pe.quantum.crm.shared.exception.PermisoInsuficienteException::class.java)

        verify(exactly = 0) { contactoRepository.save(any()) }
    }

    /** Dentro de su alcance sigue pudiendo editar: R7 restringe, no prohibe. */
    @Test
    fun `un rol de apoyo puede editar un contacto de una empresa donde colabora`() {
        contactoExiste(1)
        colaboraEn(idEmpleado = 7, empresas = setOf(3L), contactos = listOf(1L))
        every { contactoRepository.save(any()) } answers { firstArg() }
        every { empresaContactoRepository.findByIdIdContacto(1) } returns emptyList()
        every { empresaService.resumenPorIds(emptyList()) } returns emptyMap()

        val dto = service.actualizar(1, requestDeActualizacion(), analista)

        assertThat(dto.tlf_1).isEqualTo("999888777")
        verify(exactly = 1) { contactoRepository.save(any()) }
    }

    /** R4: vendedor y supervisores no pierden nada. */
    @Test
    fun `un vendedor edita sin que se consulte la colaboracion`() {
        contactoExiste(1)
        every { contactoRepository.save(any()) } answers { firstArg() }
        every { empresaContactoRepository.findByIdIdContacto(1) } returns emptyList()
        every { empresaService.resumenPorIds(emptyList()) } returns emptyMap()

        service.actualizar(1, requestDeActualizacion(), vendedor)

        verify(exactly = 0) { tareaService.idsEmpresasDondeColabora(any()) }
    }

    /**
     * El 404 del inexistente gana al 403 del permiso: si el contacto no existe, la
     * respuesta es la misma para todos los roles y no revela nada.
     */
    @Test
    fun `editar un contacto inexistente sigue siendo 404 tambien para un rol de apoyo`() {
        every { contactoRepository.findById(99) } returns java.util.Optional.empty()

        org.assertj.core.api.Assertions
            .assertThatThrownBy { service.actualizar(99, requestDeActualizacion(), analista) }
            .isInstanceOf(pe.quantum.crm.shared.exception.NoEncontradoException::class.java)

        verify(exactly = 0) { tareaService.idsEmpresasDondeColabora(any()) }
    }

    // ── bloqueo de vinculacion para roles de apoyo (hallazgo #9 de la revision final) ──

    /**
     * Cierra el camino de escalada: sin este guard, un rol de apoyo podia buscar
     * cualquier contacto por nombre en modo vincular, vincularlo a una empresa
     * donde colabora (sin guard aqui) y despues verlo completo por GET /contactos/:id.
     * Bloquear la vinculacion en si misma cierra el camino en el segundo paso.
     */
    @Test
    fun `un rol de apoyo no puede vincular un contacto a una empresa`() {
        org.assertj.core.api.Assertions
            .assertThatThrownBy {
                service.vincular(
                    3,
                    pe.quantum.crm.domain.contactos.dto.VincularContactoRequest(idContacto = 1),
                    analista,
                )
            }.isInstanceOf(pe.quantum.crm.shared.exception.PermisoInsuficienteException::class.java)
            .hasMessageContaining("apoyo")

        verify(exactly = 0) { empresaContactoRepository.save(any()) }
    }

    @Test
    fun `el rol otro tampoco puede vincular un contacto a una empresa`() {
        org.assertj.core.api.Assertions
            .assertThatThrownBy {
                service.vincular(
                    3,
                    pe.quantum.crm.domain.contactos.dto.VincularContactoRequest(idContacto = 1),
                    otro,
                )
            }.isInstanceOf(pe.quantum.crm.shared.exception.PermisoInsuficienteException::class.java)
    }

    @Test
    fun `un rol de apoyo no puede editar el vinculo de un contacto con una empresa`() {
        org.assertj.core.api.Assertions
            .assertThatThrownBy {
                service.actualizarVinculo(
                    3,
                    1,
                    pe.quantum.crm.domain.contactos.dto.ActualizarVinculoRequest(cargo = "Gerente"),
                    analista,
                )
            }.isInstanceOf(pe.quantum.crm.shared.exception.PermisoInsuficienteException::class.java)

        verify(exactly = 0) { empresaContactoRepository.save(any()) }
    }

    @Test
    fun `un rol de apoyo no puede desvincular un contacto de una empresa`() {
        org.assertj.core.api.Assertions
            .assertThatThrownBy { service.desvincular(3, 1, analista) }
            .isInstanceOf(pe.quantum.crm.shared.exception.PermisoInsuficienteException::class.java)

        verify(exactly = 0) { empresaContactoRepository.delete(any<EmpresaContacto>()) }
    }

    /** El guard bloquea antes de tocar la empresa: nunca llega a `empresaService.vinculoVisible`. */
    @Test
    fun `el bloqueo de vinculacion corre antes de resolver la visibilidad de la empresa`() {
        org.assertj.core.api.Assertions
            .assertThatThrownBy {
                service.vincular(
                    3,
                    pe.quantum.crm.domain.contactos.dto.VincularContactoRequest(idContacto = 1),
                    analista,
                )
            }.isInstanceOf(pe.quantum.crm.shared.exception.PermisoInsuficienteException::class.java)

        verify(exactly = 0) { empresaService.vinculoVisible(any(), any()) }
    }

    /** R4: el resto de roles no pierde nada. */
    @Test
    fun `un vendedor sigue pudiendo vincular un contacto a su empresa`() {
        every { empresaService.vinculoVisible(3, vendedor) } returns
            pe.quantum.crm.domain.empresas.dto.EmpresaVinculo(
                id = 3,
                razonSocial = "Transp. Sta. Anita S.A.",
                idVendedor = 42,
                estadoCartera = "prospeccion",
            )
        every { contactoRepository.findById(1) } returns java.util.Optional.of(contacto(1))
        every { empresaContactoRepository.existsById(EmpresaContactoId(idEmpresa = 3, idContacto = 1)) } returns false
        every { empresaContactoRepository.save(any()) } answers { firstArg() }

        val dto =
            service.vincular(
                3,
                pe.quantum.crm.domain.contactos.dto.VincularContactoRequest(idContacto = 1),
                vendedor,
            )

        assertThat(dto.idEmpresa).isEqualTo(3)
    }
}
