package pe.quantum.crm.domain.contactos

import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import pe.quantum.crm.domain.contactos.dto.ActualizarContactoRequest
import pe.quantum.crm.domain.contactos.dto.ActualizarVinculoRequest
import pe.quantum.crm.domain.contactos.dto.CrearContactoRequest
import pe.quantum.crm.domain.contactos.dto.VincularContactoRequest
import pe.quantum.crm.domain.empresas.EmpresaService
import pe.quantum.crm.domain.empresas.dto.EmpresaResumen
import pe.quantum.crm.domain.empresas.dto.EmpresaVinculo
import pe.quantum.crm.shared.exception.ConflictoException
import pe.quantum.crm.shared.exception.ContactoVinculadoException
import pe.quantum.crm.shared.exception.NoEncontradoException
import pe.quantum.crm.shared.security.UsuarioActual
import java.time.LocalDateTime
import java.util.Optional

/**
 * Escritura del modulo contactos: `crear`, `actualizar`, `eliminar` y las tres
 * operaciones de vinculacion con empresa. Va en un archivo aparte de
 * `ContactoServiceImplTest` (lectura/busqueda) para que ninguna de las dos clases
 * acabe siendo un cajon de sastre.
 *
 * Todas las escrituras pasan primero por `empresaService.vinculoVisible`, que es
 * el filtro IDOR del modulo de empresas (empresa ajena o inexistente -> 404): los
 * tests lo verifican explicitamente porque saltarselo abriria el pipeline de otro
 * vendedor por la puerta de los contactos, que son globales por diseño.
 */
class ContactoServiceImplEscrituraTest {
    private val contactoRepository = mockk<ContactoRepository>()
    private val empresaContactoRepository = mockk<EmpresaContactoRepository>()
    private val empresaService = mockk<EmpresaService>()
    private val service = ContactoServiceImpl(contactoRepository, empresaContactoRepository, empresaService)

    private val usuario = UsuarioActual(id = 42, rol = "vendedor")

    private fun contacto(id: Long = 1) =
        Contacto(
            id = id,
            nombres = "Hugo",
            apellidos = "Rodríguez",
            email_1 = "hugo@transportes.pe",
            email_2 = "hugo.rodriguez@gmail.com",
            tlf_1 = "964415122",
            tlf_2 = "015551234",
            notas = "Prefiere WhatsApp",
            createdAt = LocalDateTime.of(2026, 1, 1, 9, 0),
            createdBy = 7,
            updatedAt = LocalDateTime.of(2026, 1, 1, 9, 0),
            updatedBy = 7,
        )

    private fun vinculoVisible(idEmpresa: Long = 3) {
        every { empresaService.vinculoVisible(idEmpresa, usuario) } returns
            EmpresaVinculo(id = idEmpresa, razonSocial = "Transp. Sta. Anita S.A.", idVendedor = 42, estadoCartera = "prospeccion")
    }

    /** El `toDto` privado relee los vinculos del contacto: se stubea para que devuelva una empresa. */
    private fun conUnaEmpresaVinculada(
        idContacto: Long = 9,
        idEmpresa: Long = 3,
    ) {
        every { empresaContactoRepository.findByIdIdContacto(idContacto) } returns
            listOf(EmpresaContacto(id = EmpresaContactoId(idEmpresa = idEmpresa, idContacto = idContacto), cargo = "Gerente"))
        every { empresaService.resumenPorIds(listOf(idEmpresa)) } returns
            mapOf(idEmpresa to EmpresaResumen(id = idEmpresa, razonSocial = "Transp. Sta. Anita S.A.", distrito = "Ate"))
    }

    /** `save` no puede devolver la entidad tal cual: sin id el `requireNotNull` del `toDto` revienta. */
    private fun guardaConId(
        id: Long,
        capturado: io.mockk.CapturingSlot<Contacto>,
    ) {
        every { contactoRepository.save(capture(capturado)) } answers {
            val nuevo = capturado.captured
            Contacto(
                id = id,
                nombres = nuevo.nombres,
                apellidos = nuevo.apellidos,
                email_1 = nuevo.email_1,
                email_2 = nuevo.email_2,
                tlf_1 = nuevo.tlf_1,
                tlf_2 = nuevo.tlf_2,
                notas = nuevo.notas,
                createdAt = nuevo.createdAt,
                createdBy = nuevo.createdBy,
                updatedAt = nuevo.updatedAt,
                updatedBy = nuevo.updatedBy,
            )
        }
    }

    // ── crear ──────────────────────────────────────────────────

    @Test
    fun `crear persiste todos los campos del request y devuelve el contacto con sus empresas`() {
        vinculoVisible()
        val capturado = slot<Contacto>()
        guardaConId(id = 9, capturado = capturado)
        every { empresaContactoRepository.save(any()) } answers { firstArg() }
        conUnaEmpresaVinculada()
        val request =
            CrearContactoRequest(
                nombres = "Hugo",
                apellidos = "Rodríguez",
                email_1 = "hugo@transportes.pe",
                email_2 = "hugo.rodriguez@gmail.com",
                tlf_1 = "964415122",
                tlf_2 = "015551234",
                notas = "Prefiere WhatsApp",
                idEmpresa = 3,
                cargo = "Gerente",
                tomaDecision = true,
                esPrincipal = true,
            )

        val dto = service.crear(request, usuario)

        assertThat(dto.id).isEqualTo(9)
        assertThat(dto.nombres).isEqualTo("Hugo")
        assertThat(dto.apellidos).isEqualTo("Rodríguez")
        assertThat(dto.email_1).isEqualTo("hugo@transportes.pe")
        assertThat(dto.email_2).isEqualTo("hugo.rodriguez@gmail.com")
        assertThat(dto.tlf_1).isEqualTo("964415122")
        assertThat(dto.tlf_2).isEqualTo("015551234")
        assertThat(dto.notas).isEqualTo("Prefiere WhatsApp")
        assertThat(dto.empresas).hasSize(1)
        assertThat(dto.empresas.first().id).isEqualTo(3)
        assertThat(dto.empresas.first().razonSocial).isEqualTo("Transp. Sta. Anita S.A.")
        assertThat(dto.empresas.first().cargo).isEqualTo("Gerente")
        // El request lleva los datos del contacto, no del vinculo: nada de cargo en la entidad.
        assertThat(capturado.captured.nombres).isEqualTo("Hugo")
        assertThat(capturado.captured.notas).isEqualTo("Prefiere WhatsApp")
    }

    @Test
    fun `crear sella la auditoria con el usuario actual y el mismo instante en created y updated`() {
        vinculoVisible()
        val capturado = slot<Contacto>()
        guardaConId(id = 9, capturado = capturado)
        every { empresaContactoRepository.save(any()) } answers { firstArg() }
        conUnaEmpresaVinculada()

        service.crear(CrearContactoRequest(nombres = "Hugo", apellidos = "Rodríguez", idEmpresa = 3), usuario)

        val creado = capturado.captured
        assertThat(creado.createdBy).isEqualTo(42)
        assertThat(creado.updatedBy).isEqualTo(42)
        assertThat(creado.createdAt).isEqualTo(creado.updatedAt)
    }

    /** El vinculo se crea en la misma transaccion que el contacto (contrato_api.md §9). */
    @Test
    fun `crear vincula el contacto nuevo a la empresa con cargo, toma_decision y es_principal`() {
        vinculoVisible()
        val capturado = slot<Contacto>()
        guardaConId(id = 9, capturado = capturado)
        val vinculo = slot<EmpresaContacto>()
        every { empresaContactoRepository.save(capture(vinculo)) } answers { firstArg() }
        conUnaEmpresaVinculada()
        val request =
            CrearContactoRequest(
                nombres = "Hugo",
                apellidos = "Rodríguez",
                idEmpresa = 3,
                cargo = "Jefe de flota",
                tomaDecision = true,
                esPrincipal = true,
            )

        service.crear(request, usuario)

        assertThat(vinculo.captured.id).isEqualTo(EmpresaContactoId(idEmpresa = 3, idContacto = 9))
        assertThat(vinculo.captured.cargo).isEqualTo("Jefe de flota")
        assertThat(vinculo.captured.tomaDecision).isTrue()
        assertThat(vinculo.captured.esPrincipal).isTrue()
    }

    @Test
    fun `crear con los campos opcionales ausentes deja nulos y es_principal en false`() {
        vinculoVisible()
        val capturado = slot<Contacto>()
        guardaConId(id = 9, capturado = capturado)
        val vinculo = slot<EmpresaContacto>()
        every { empresaContactoRepository.save(capture(vinculo)) } answers { firstArg() }
        every { empresaContactoRepository.findByIdIdContacto(9) } returns emptyList()
        every { empresaService.resumenPorIds(emptyList()) } returns emptyMap()

        val dto = service.crear(CrearContactoRequest(nombres = "Hugo", apellidos = "Rodríguez", idEmpresa = 3), usuario)

        assertThat(dto.email_1).isNull()
        assertThat(dto.email_2).isNull()
        assertThat(dto.tlf_1).isNull()
        assertThat(dto.tlf_2).isNull()
        assertThat(dto.notas).isNull()
        assertThat(dto.empresas).isEmpty()
        assertThat(vinculo.captured.cargo).isNull()
        assertThat(vinculo.captured.tomaDecision).isNull()
        assertThat(vinculo.captured.esPrincipal).isFalse()
    }

    /** IDOR: empresa ajena o inexistente -> 404 antes de tocar nada (CLAUDE.md regla 14). */
    @Test
    fun `crear sobre una empresa no visible propaga el 404 y no guarda nada`() {
        every { empresaService.vinculoVisible(77, usuario) } throws NoEncontradoException("La empresa no existe")

        assertThatThrownBy {
            service.crear(CrearContactoRequest(nombres = "Hugo", apellidos = "Rodríguez", idEmpresa = 77), usuario)
        }.isInstanceOf(NoEncontradoException::class.java)

        verify(exactly = 0) { contactoRepository.save(any()) }
        verify(exactly = 0) { empresaContactoRepository.save(any()) }
    }

    // ── actualizar ─────────────────────────────────────────────

    @Test
    fun `actualizar aplica todos los campos presentes y refresca la auditoria`() {
        val entidad = contacto()
        every { contactoRepository.findById(1) } returns Optional.of(entidad)
        every { contactoRepository.save(any()) } answers { firstArg() }
        every { empresaContactoRepository.findByIdIdContacto(1) } returns
            listOf(EmpresaContacto(id = EmpresaContactoId(idEmpresa = 3, idContacto = 1), cargo = "Gerente"))
        every { empresaService.resumenPorIds(listOf(3L)) } returns
            mapOf(3L to EmpresaResumen(id = 3, razonSocial = "Transp. Sta. Anita S.A.", distrito = "Ate"))
        val request =
            ActualizarContactoRequest(
                nombres = "Hugo Andrés",
                apellidos = "Rodríguez Paz",
                email_1 = "nuevo1@transportes.pe",
                email_2 = "nuevo2@transportes.pe",
                tlf_1 = "999888777",
                tlf_2 = "015559999",
                notas = "Actualizado tras la visita",
            )

        val dto = service.actualizar(1, request, usuario)

        assertThat(dto.nombres).isEqualTo("Hugo Andrés")
        assertThat(dto.apellidos).isEqualTo("Rodríguez Paz")
        assertThat(dto.email_1).isEqualTo("nuevo1@transportes.pe")
        assertThat(dto.email_2).isEqualTo("nuevo2@transportes.pe")
        assertThat(dto.tlf_1).isEqualTo("999888777")
        assertThat(dto.tlf_2).isEqualTo("015559999")
        assertThat(dto.notas).isEqualTo("Actualizado tras la visita")
        assertThat(dto.empresas).hasSize(1)
        assertThat(entidad.updatedBy).isEqualTo(42)
        assertThat(entidad.updatedAt).isAfter(LocalDateTime.of(2026, 1, 1, 9, 0))
        // La auditoria de creacion no se reescribe.
        assertThat(entidad.createdBy).isEqualTo(7)
    }

    /**
     * PUT parcial: un campo ausente (null en el body) NO borra el valor guardado.
     * El unico modo de vaciar un campo en este contrato seria otro endpoint.
     */
    @Test
    fun `actualizar con el body vacio conserva todos los valores previos`() {
        val entidad = contacto()
        every { contactoRepository.findById(1) } returns Optional.of(entidad)
        every { contactoRepository.save(any()) } answers { firstArg() }
        every { empresaContactoRepository.findByIdIdContacto(1) } returns emptyList()
        every { empresaService.resumenPorIds(emptyList()) } returns emptyMap()

        val dto = service.actualizar(1, ActualizarContactoRequest(), usuario)

        assertThat(dto.nombres).isEqualTo("Hugo")
        assertThat(dto.apellidos).isEqualTo("Rodríguez")
        assertThat(dto.email_1).isEqualTo("hugo@transportes.pe")
        assertThat(dto.email_2).isEqualTo("hugo.rodriguez@gmail.com")
        assertThat(dto.tlf_1).isEqualTo("964415122")
        assertThat(dto.tlf_2).isEqualTo("015551234")
        assertThat(dto.notas).isEqualTo("Prefiere WhatsApp")
    }

    @Test
    fun `actualizar un contacto inexistente lanza NoEncontradoException sin guardar`() {
        every { contactoRepository.findById(99) } returns Optional.empty()

        assertThatThrownBy { service.actualizar(99, ActualizarContactoRequest(nombres = "X"), usuario) }
            .isInstanceOf(NoEncontradoException::class.java)

        verify(exactly = 0) { contactoRepository.save(any()) }
    }

    // ── eliminar ───────────────────────────────────────────────

    @Test
    fun `eliminar borra el contacto cuando no esta vinculado a ninguna empresa`() {
        val entidad = contacto()
        every { contactoRepository.findById(1) } returns Optional.of(entidad)
        every { empresaContactoRepository.existsByIdIdContacto(1) } returns false
        every { contactoRepository.delete(entidad) } returns Unit

        service.eliminar(1)

        verify(exactly = 1) { contactoRepository.delete(entidad) }
    }

    /** reglas_negocio.md §11.2: vinculado a una empresa -> 409 CONTACTO_VINCULADO. */
    @Test
    fun `eliminar un contacto vinculado lanza ContactoVinculadoException y no borra`() {
        val entidad = contacto()
        every { contactoRepository.findById(1) } returns Optional.of(entidad)
        every { empresaContactoRepository.existsByIdIdContacto(1) } returns true

        assertThatThrownBy { service.eliminar(1) }
            .isInstanceOf(ContactoVinculadoException::class.java)
            .hasMessageContaining("vinculado")

        verify(exactly = 0) { contactoRepository.delete(any<Contacto>()) }
    }

    @Test
    fun `eliminar un contacto inexistente lanza NoEncontradoException`() {
        every { contactoRepository.findById(99) } returns Optional.empty()

        assertThatThrownBy { service.eliminar(99) }.isInstanceOf(NoEncontradoException::class.java)
    }

    // ── vincular ───────────────────────────────────────────────

    @Test
    fun `vincular crea la relacion y devuelve el vinculo completo`() {
        vinculoVisible()
        every { contactoRepository.findById(1) } returns Optional.of(contacto())
        every { empresaContactoRepository.existsById(EmpresaContactoId(idEmpresa = 3, idContacto = 1)) } returns false
        every { empresaContactoRepository.save(any()) } answers { firstArg() }
        val request = VincularContactoRequest(idContacto = 1, cargo = "Jefe de flota", tomaDecision = true, esPrincipal = true)

        val dto = service.vincular(3, request, usuario)

        assertThat(dto.idEmpresa).isEqualTo(3)
        assertThat(dto.idContacto).isEqualTo(1)
        assertThat(dto.cargo).isEqualTo("Jefe de flota")
        assertThat(dto.tomaDecision).isTrue()
        assertThat(dto.esPrincipal).isTrue()
    }

    @Test
    fun `vincular un contacto ya vinculado lanza VINCULO_DUPLICADO`() {
        vinculoVisible()
        every { contactoRepository.findById(1) } returns Optional.of(contacto())
        every { empresaContactoRepository.existsById(EmpresaContactoId(idEmpresa = 3, idContacto = 1)) } returns true

        assertThatThrownBy { service.vincular(3, VincularContactoRequest(idContacto = 1), usuario) }
            .isInstanceOf(ConflictoException::class.java)
            .hasMessageContaining("ya está vinculado")

        verify(exactly = 0) { empresaContactoRepository.save(any()) }
    }

    @Test
    fun `vincular un contacto inexistente lanza NoEncontradoException antes de mirar el duplicado`() {
        vinculoVisible()
        every { contactoRepository.findById(99) } returns Optional.empty()

        assertThatThrownBy { service.vincular(3, VincularContactoRequest(idContacto = 99), usuario) }
            .isInstanceOf(NoEncontradoException::class.java)

        verify(exactly = 0) { empresaContactoRepository.existsById(any()) }
    }

    @Test
    fun `vincular sobre una empresa no visible propaga el 404 sin tocar el contacto`() {
        every { empresaService.vinculoVisible(77, usuario) } throws NoEncontradoException("La empresa no existe")

        assertThatThrownBy { service.vincular(77, VincularContactoRequest(idContacto = 1), usuario) }
            .isInstanceOf(NoEncontradoException::class.java)

        verify(exactly = 0) { contactoRepository.findById(any()) }
    }

    // ── actualizarVinculo ──────────────────────────────────────

    @Test
    fun `actualizarVinculo cambia cargo, toma_decision y es_principal`() {
        vinculoVisible()
        val vinculo =
            EmpresaContacto(
                id = EmpresaContactoId(idEmpresa = 3, idContacto = 1),
                cargo = "Gerente",
                tomaDecision = false,
                esPrincipal = false,
            )
        every { empresaContactoRepository.findById(EmpresaContactoId(idEmpresa = 3, idContacto = 1)) } returns Optional.of(vinculo)
        every { empresaContactoRepository.save(any()) } answers { firstArg() }

        val dto =
            service.actualizarVinculo(
                3,
                1,
                ActualizarVinculoRequest(cargo = "Jefe de flota", tomaDecision = true, esPrincipal = true),
                usuario,
            )

        assertThat(dto.cargo).isEqualTo("Jefe de flota")
        assertThat(dto.tomaDecision).isTrue()
        assertThat(dto.esPrincipal).isTrue()
    }

    @Test
    fun `actualizarVinculo con el body vacio conserva el vinculo tal cual`() {
        vinculoVisible()
        val vinculo =
            EmpresaContacto(
                id = EmpresaContactoId(idEmpresa = 3, idContacto = 1),
                cargo = "Gerente",
                tomaDecision = true,
                esPrincipal = true,
            )
        every { empresaContactoRepository.findById(EmpresaContactoId(idEmpresa = 3, idContacto = 1)) } returns Optional.of(vinculo)
        every { empresaContactoRepository.save(any()) } answers { firstArg() }

        val dto = service.actualizarVinculo(3, 1, ActualizarVinculoRequest(), usuario)

        assertThat(dto.cargo).isEqualTo("Gerente")
        assertThat(dto.tomaDecision).isTrue()
        assertThat(dto.esPrincipal).isTrue()
    }

    @Test
    fun `actualizarVinculo de un contacto no vinculado a esa empresa lanza NoEncontradoException`() {
        vinculoVisible()
        every { empresaContactoRepository.findById(EmpresaContactoId(idEmpresa = 3, idContacto = 1)) } returns Optional.empty()

        assertThatThrownBy { service.actualizarVinculo(3, 1, ActualizarVinculoRequest(cargo = "X"), usuario) }
            .isInstanceOf(NoEncontradoException::class.java)
            .hasMessageContaining("no está vinculado")
    }

    // ── desvincular ────────────────────────────────────────────

    @Test
    fun `desvincular borra la relacion pero no el contacto`() {
        vinculoVisible()
        val vinculo = EmpresaContacto(id = EmpresaContactoId(idEmpresa = 3, idContacto = 1))
        every { empresaContactoRepository.findById(EmpresaContactoId(idEmpresa = 3, idContacto = 1)) } returns Optional.of(vinculo)
        every { empresaContactoRepository.delete(vinculo) } returns Unit

        service.desvincular(3, 1, usuario)

        verify(exactly = 1) { empresaContactoRepository.delete(vinculo) }
        verify(exactly = 0) { contactoRepository.delete(any<Contacto>()) }
    }

    @Test
    fun `desvincular una relacion inexistente lanza NoEncontradoException`() {
        vinculoVisible()
        every { empresaContactoRepository.findById(EmpresaContactoId(idEmpresa = 3, idContacto = 1)) } returns Optional.empty()

        assertThatThrownBy { service.desvincular(3, 1, usuario) }.isInstanceOf(NoEncontradoException::class.java)
    }

    @Test
    fun `desvincular sobre una empresa no visible propaga el 404 sin borrar`() {
        every { empresaService.vinculoVisible(77, usuario) } throws NoEncontradoException("La empresa no existe")

        assertThatThrownBy { service.desvincular(77, 1, usuario) }.isInstanceOf(NoEncontradoException::class.java)

        verify(exactly = 0) { empresaContactoRepository.delete(any<EmpresaContacto>()) }
    }
}
