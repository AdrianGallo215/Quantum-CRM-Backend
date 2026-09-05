package pe.quantum.crm.domain.oportunidades

import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import pe.quantum.crm.domain.contactos.ContactoService
import pe.quantum.crm.domain.contactos.dto.ContactoResumen
import pe.quantum.crm.domain.empleados.EmpleadoService
import pe.quantum.crm.domain.empleados.dto.EmpleadoResumen
import pe.quantum.crm.domain.empresas.EmpresaService
import pe.quantum.crm.domain.empresas.dto.EmpresaResumen
import pe.quantum.crm.domain.empresas.dto.EmpresaVinculo
import pe.quantum.crm.domain.financiadoras.FinanciadoraService
import pe.quantum.crm.domain.financiadoras.dto.FinanciadoraDto
import pe.quantum.crm.domain.modelos.ModeloService
import pe.quantum.crm.domain.modelos.dto.ModeloResumen
import pe.quantum.crm.domain.notificaciones.NotificacionService
import pe.quantum.crm.domain.oportunidades.dto.ContactoVinculoRequest
import pe.quantum.crm.domain.oportunidades.dto.CrearOportunidadRequest
import pe.quantum.crm.domain.oportunidades.dto.OportunidadItemDto
import pe.quantum.crm.domain.tareas.TareaService
import pe.quantum.crm.integracion.drive.DriveStorageService
import pe.quantum.crm.shared.enums.EstadoOportunidad
import pe.quantum.crm.shared.exception.ConflictoException
import pe.quantum.crm.shared.exception.NoEncontradoException
import pe.quantum.crm.shared.security.UsuarioActual
import java.math.BigDecimal
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.Optional

/**
 * Contactos de una oportunidad (contrato §10): vinculacion desde el POST de
 * creacion, alta/edicion/baja del vinculo y su aparicion en el detalle.
 * Independientes de los contactos de la empresa (reglas §11.3).
 */
class OportunidadContactosTest {
    private val oportunidadRepository = mockk<OportunidadRepository>()
    private val logRepository = mockk<OportunidadEstadoLogRepository>()
    private val contactoOportunidadRepository = mockk<OportunidadContactoRepository>()
    private val estadoCarteraService = mockk<EstadoCarteraService>()
    private val empresaService = mockk<EmpresaService>()
    private val empleadoService = mockk<EmpleadoService>()
    private val financiadoraService = mockk<FinanciadoraService>()
    private val modeloService = mockk<ModeloService>()
    private val contactoService = mockk<ContactoService>()
    private val consultas = mockk<OportunidadConsultas>()
    private val notificacionService = mockk<NotificacionService>(relaxed = true)
    private val driveStorageService = mockk<DriveStorageService>(relaxed = true)
    private val tareaService = mockk<TareaService>()
    private val oportunidadItemService = mockk<OportunidadItemService>()
    private val listadoDao = mockk<OportunidadListadoDao>(relaxed = true)
    private val service =
        OportunidadServiceImpl(
            oportunidadRepository,
            logRepository,
            contactoOportunidadRepository,
            estadoCarteraService,
            empresaService,
            empleadoService,
            financiadoraService,
            modeloService,
            contactoService,
            consultas,
            notificacionService,
            driveStorageService,
            OportunidadVisibilidad(tareaService),
            oportunidadItemService,
            listadoDao,
        )

    private val vendedor = UsuarioActual(id = 5, rol = "vendedor")
    private val busX = ModeloResumen(id = 1, codigo = "BUS-X", precioBase = BigDecimal("100.00"))
    private val calidda =
        FinanciadoraDto(
            id = 1,
            nombre = "Calidda",
            montoPorUnidad = null,
            plazoMeses = null,
            tea = null,
            cuotaPorUnidad = null,
            esDefault = true,
            notas = null,
        )

    init {
        every { empresaService.resumenPorIds(any()) } returns
            mapOf(10L to EmpresaResumen(id = 10, razonSocial = "Kincar S.A.C.", distrito = null))
        every { empleadoService.resumenPorIds(any()) } returns
            mapOf(5L to EmpleadoResumen(id = 5, nombres = "Ana", apellidos = "Diaz"))
        every { financiadoraService.porIds(any()) } returns mapOf(1L to calidda)
        every { modeloService.resumenPorIds(any()) } returns mapOf(1L to busX)
        every { consultas.tareasPendientesPorOportunidad(any()) } returns emptyMap()
        every { consultas.eventosPendientesPorOportunidad(any()) } returns emptyMap()
        every { logRepository.findFirstByIdOportunidadOrderByChangedAtDescIdDesc(any()) } returns null
        every { oportunidadItemService.crear(any(), any(), any()) } returns itemDtoNeutro()
        every { oportunidadItemService.porOportunidades(any()) } returns emptyMap()
        every { oportunidadItemService.montoTotalPorOportunidades(any()) } returns emptyMap()
    }

    /**
     * Item neutro: estos escenarios no verifican nada de los items, pero
     * `crear()` delega en OportunidadItemService (B6) y `toDtos()` le pide items
     * y monto (B8). Lo que si comprueban los items esta en
     * OportunidadItemServiceImplTest.
     */
    private fun itemDtoNeutro() =
        OportunidadItemDto(
            id = 500,
            idModelo = 1,
            modelo = null,
            cantidad = 1,
            precioVenta = "100.00",
            descuento = "0.00",
            cuotaFinanciadora = "0.00",
            montoItem = "100.00",
        )

    private fun oportunidad() =
        Oportunidad(
            id = 100,
            idEmpresa = 10,
            idVendedor = 5,
            idFinanciadora = 1,
            estado = EstadoOportunidad.evaluacion_calidda,
            createdAt = LocalDateTime.now(),
            createdBy = 5,
            updatedAt = LocalDateTime.now(),
            updatedBy = 5,
        )

    // ── POST /oportunidades/:id/contactos ─────────────────────

    @Test
    fun `vincularContacto guarda el vinculo con su rol y devuelve lo pedido`() {
        every { oportunidadRepository.findById(100) } returns Optional.of(oportunidad())
        every { contactoService.existe(7) } returns true
        every { contactoOportunidadRepository.existsById(OportunidadContactoId(idOportunidad = 100, idContacto = 7)) } returns false
        val guardado = slot<OportunidadContacto>()
        every { contactoOportunidadRepository.save(capture(guardado)) } answers { guardado.captured }

        val resultado = service.vincularContacto(100, ContactoVinculoRequest(idContacto = 7, rolEnOportunidad = "Aprobador"), vendedor)

        assertThat(guardado.captured.id).isEqualTo(OportunidadContactoId(idOportunidad = 100, idContacto = 7))
        assertThat(guardado.captured.rolEnOportunidad).isEqualTo("Aprobador")
        assertThat(resultado.idContacto).isEqualTo(7)
    }

    @Test
    fun `vincularContacto con un contacto inexistente responde 404 sin guardar`() {
        every { oportunidadRepository.findById(100) } returns Optional.of(oportunidad())
        every { contactoService.existe(7) } returns false

        assertThatThrownBy { service.vincularContacto(100, ContactoVinculoRequest(idContacto = 7), vendedor) }
            .isInstanceOf(NoEncontradoException::class.java)

        verify(exactly = 0) { contactoOportunidadRepository.save(any<OportunidadContacto>()) }
    }

    /**
     * `save` sobre una clave compuesta que ya existe es un UPDATE: vincular dos veces
     * sobreescribia el rol anterior sin avisar y respondia 201 como si fuera un
     * vinculo nuevo. Cambiar el rol tiene su propio endpoint (PUT).
     */
    @Test
    fun `vincular un contacto ya vinculado devuelve 409 sin sobreescribir el rol`() {
        every { oportunidadRepository.findById(100) } returns Optional.of(oportunidad())
        every { contactoService.existe(7) } returns true
        every {
            contactoOportunidadRepository.existsById(OportunidadContactoId(idOportunidad = 100, idContacto = 7))
        } returns true

        val ex =
            assertThrows<ConflictoException> {
                service.vincularContacto(100, ContactoVinculoRequest(idContacto = 7, rolEnOportunidad = "Aprobador"), vendedor)
            }

        assertThat(ex.code).isEqualTo("CONTACTO_YA_VINCULADO")
        verify(exactly = 0) { contactoOportunidadRepository.save(any<OportunidadContacto>()) }
    }

    /** IDOR (regla 14): la visibilidad se comprueba antes de mirar el contacto. */
    @Test
    fun `vincularContacto sobre una oportunidad ajena responde 404 sin consultar el contacto`() {
        every { oportunidadRepository.findById(100) } returns Optional.of(oportunidad())

        assertThatThrownBy {
            service.vincularContacto(100, ContactoVinculoRequest(idContacto = 7), UsuarioActual(id = 77, rol = "vendedor"))
        }.isInstanceOf(NoEncontradoException::class.java)

        verify(exactly = 0) { contactoService.existe(any()) }
    }

    // ── PUT /oportunidades/:id/contactos/:contacto_id ─────────

    @Test
    fun `actualizarContacto cambia el rol del vinculo existente`() {
        val vinculo = OportunidadContacto(OportunidadContactoId(idOportunidad = 100, idContacto = 7), rolEnOportunidad = "Tecnico")
        every { oportunidadRepository.findById(100) } returns Optional.of(oportunidad())
        every { contactoOportunidadRepository.findById(vinculo.id) } returns Optional.of(vinculo)
        every { contactoOportunidadRepository.save(vinculo) } returns vinculo

        val resultado = service.actualizarContacto(100, 7, "Aprobador", vendedor)

        assertThat(vinculo.rolEnOportunidad).isEqualTo("Aprobador")
        assertThat(resultado).isEqualTo(ContactoVinculoRequest(idContacto = 7, rolEnOportunidad = "Aprobador"))
    }

    /** El rol se puede limpiar mandando `rol_en_oportunidad` nulo. */
    @Test
    fun `actualizarContacto con rol nulo limpia el rol`() {
        val vinculo = OportunidadContacto(OportunidadContactoId(idOportunidad = 100, idContacto = 7), rolEnOportunidad = "Tecnico")
        every { oportunidadRepository.findById(100) } returns Optional.of(oportunidad())
        every { contactoOportunidadRepository.findById(vinculo.id) } returns Optional.of(vinculo)
        every { contactoOportunidadRepository.save(vinculo) } returns vinculo

        service.actualizarContacto(100, 7, null, vendedor)

        assertThat(vinculo.rolEnOportunidad).isNull()
    }

    @Test
    fun `actualizarContacto de un contacto no vinculado responde 404`() {
        every { oportunidadRepository.findById(100) } returns Optional.of(oportunidad())
        every { contactoOportunidadRepository.findById(any()) } returns Optional.empty()

        assertThatThrownBy { service.actualizarContacto(100, 7, "Aprobador", vendedor) }
            .isInstanceOf(NoEncontradoException::class.java)
    }

    // ── DELETE /oportunidades/:id/contactos/:contacto_id ──────

    @Test
    fun `desvincularContacto borra el vinculo`() {
        val vinculo = OportunidadContacto(OportunidadContactoId(idOportunidad = 100, idContacto = 7))
        every { oportunidadRepository.findById(100) } returns Optional.of(oportunidad())
        every { contactoOportunidadRepository.findById(vinculo.id) } returns Optional.of(vinculo)
        every { contactoOportunidadRepository.delete(vinculo) } just Runs

        service.desvincularContacto(100, 7, vendedor)

        verify { contactoOportunidadRepository.delete(vinculo) }
    }

    @Test
    fun `desvincularContacto de un contacto no vinculado responde 404`() {
        every { oportunidadRepository.findById(100) } returns Optional.of(oportunidad())
        every { contactoOportunidadRepository.findById(any()) } returns Optional.empty()

        assertThatThrownBy { service.desvincularContacto(100, 7, vendedor) }
            .isInstanceOf(NoEncontradoException::class.java)

        verify(exactly = 0) { contactoOportunidadRepository.delete(any<OportunidadContacto>()) }
    }

    @Test
    fun `desvincularContacto sobre una oportunidad ajena responde 404`() {
        every { oportunidadRepository.findById(100) } returns Optional.of(oportunidad())

        assertThatThrownBy { service.desvincularContacto(100, 7, UsuarioActual(id = 77, rol = "vendedor")) }
            .isInstanceOf(NoEncontradoException::class.java)

        verify(exactly = 0) { contactoOportunidadRepository.findById(any()) }
    }

    // ── El detalle los devuelve ───────────────────────────────

    @Test
    fun `el detalle devuelve los contactos con su rol y la entrada a la etapa actual`() {
        val entrada = LocalDateTime.of(2026, 2, 1, 12, 0)
        every { oportunidadRepository.findById(100) } returns Optional.of(oportunidad())
        every { contactoOportunidadRepository.findByIdIdOportunidad(100) } returns
            listOf(
                OportunidadContacto(OportunidadContactoId(idOportunidad = 100, idContacto = 7), rolEnOportunidad = "Aprobador"),
                OportunidadContacto(OportunidadContactoId(idOportunidad = 100, idContacto = 8), rolEnOportunidad = null),
            )
        every { contactoService.resumenPorIds(listOf(7L, 8L)) } returns
            mapOf(
                7L to ContactoResumen(id = 7, nombres = "Luis", apellidos = "Vera"),
                8L to ContactoResumen(id = 8, nombres = "Rosa", apellidos = "Paz"),
            )
        every { logRepository.findFirstByIdOportunidadOrderByChangedAtDescIdDesc(100) } returns
            OportunidadEstadoLog(
                idOportunidad = 100,
                estadoAnterior = null,
                estadoNuevo = EstadoOportunidad.evaluacion_calidda,
                changedAt = entrada,
                changedBy = 5,
            )

        val dto = service.detalle(100, vendedor)

        val contactos = requireNotNull(dto.contactos)
        assertThat(contactos.map { it.id }).containsExactly(7L, 8L)
        assertThat(contactos.map { it.nombres }).containsExactly("Luis", "Rosa")
        assertThat(contactos.map { it.rolEnOportunidad }).containsExactly("Aprobador", null)
        assertThat(dto.entradaEtapaActual).isEqualTo(entrada.toInstant(ZoneOffset.UTC))
        assertThat(dto.empresa?.razonSocial).isEqualTo("Kincar S.A.C.")
    }

    /** Un vinculo cuyo contacto ya no existe no revienta el detalle: simplemente no sale. */
    @Test
    fun `el detalle omite los vinculos cuyo contacto ya no existe`() {
        every { oportunidadRepository.findById(100) } returns Optional.of(oportunidad())
        every { contactoOportunidadRepository.findByIdIdOportunidad(100) } returns
            listOf(OportunidadContacto(OportunidadContactoId(idOportunidad = 100, idContacto = 7)))
        every { contactoService.resumenPorIds(listOf(7L)) } returns emptyMap()

        assertThat(service.detalle(100, vendedor).contactos).isEmpty()
    }

    // ── POST /oportunidades con contactos en el body ──────────

    @Test
    fun `crear vincula los contactos que vienen en el body`() {
        stubsDeCreacion()
        every { contactoService.existe(7) } returns true
        val guardados = mutableListOf<OportunidadContacto>()
        every { contactoOportunidadRepository.save(any<OportunidadContacto>()) } answers {
            firstArg<OportunidadContacto>().also { guardados += it }
        }
        every { contactoOportunidadRepository.findByIdIdOportunidad(100) } returns emptyList()
        every { contactoService.resumenPorIds(emptyList()) } returns emptyMap()

        service.crear(
            CrearOportunidadRequest(
                idEmpresa = 10,
                idModelo = 1,
                contactos = listOf(ContactoVinculoRequest(idContacto = 7, rolEnOportunidad = "Aprobador")),
            ),
            vendedor,
        )

        assertThat(guardados).hasSize(1)
        assertThat(guardados[0].id.idContacto).isEqualTo(7)
        assertThat(guardados[0].rolEnOportunidad).isEqualTo("Aprobador")
    }

    @Test
    fun `crear con un contacto inexistente responde 404 y no abre carpeta en Drive`() {
        stubsDeCreacion()
        every { contactoService.existe(7) } returns false

        assertThatThrownBy {
            service.crear(
                CrearOportunidadRequest(idEmpresa = 10, idModelo = 1, contactos = listOf(ContactoVinculoRequest(idContacto = 7))),
                vendedor,
            )
        }.isInstanceOf(NoEncontradoException::class.java)

        verify(exactly = 0) { driveStorageService.crearCarpeta(any(), any()) }
    }

    /** Stubs minimos para que `crear` llegue hasta el ensamblado del DTO. */
    private fun stubsDeCreacion() {
        every { empresaService.vinculoVisible(10, any()) } returns
            EmpresaVinculo(
                id = 10,
                razonSocial = "Kincar S.A.C.",
                idVendedor = 5,
                estadoCartera = "prospeccion",
                driveFolderId = "carpeta-empresa",
            )
        every { modeloService.resumen(1) } returns busX
        every { financiadoraService.default() } returns calidda
        every { oportunidadRepository.save(any<Oportunidad>()) } returns oportunidad()
        every { logRepository.save(any()) } returns mockk()
        every { estadoCarteraService.actualizar(10) } returns null
        every { driveStorageService.crearCarpeta(any(), any()) } returns "carpeta-op"
    }
}
