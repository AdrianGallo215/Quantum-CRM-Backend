package pe.quantum.crm.domain.empresas

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.context.ApplicationEventPublisher
import org.springframework.transaction.support.TransactionTemplate
import pe.quantum.crm.domain.contactos.ContactoService
import pe.quantum.crm.domain.empleados.EmpleadoService
import pe.quantum.crm.domain.empresas.dto.ActualizarEmpresaRequest
import pe.quantum.crm.domain.notificaciones.NotificacionService
import pe.quantum.crm.integracion.drive.DriveStorageService
import pe.quantum.crm.shared.enums.EstadoCartera
import pe.quantum.crm.shared.enums.OrigenLead
import pe.quantum.crm.shared.enums.Segmento
import pe.quantum.crm.shared.exception.EstadoInvalidoException
import pe.quantum.crm.shared.exception.NoEncontradoException
import pe.quantum.crm.shared.exception.PermisoInsuficienteException
import pe.quantum.crm.shared.exception.RucDuplicadoException
import pe.quantum.crm.shared.exception.ValidacionException
import pe.quantum.crm.shared.security.UsuarioActual
import java.time.LocalDateTime
import java.util.Optional

/**
 * Cambio manual de `estado_cartera`, PATCH de empresa y cartera maestra.
 *
 * `estado_cartera` es el campo con la regla mas dura del modulo (CLAUDE.md regla
 * 3 / reglas_negocio.md §3.1): los estados derivados los pone el sistema desde
 * `aplicarEstadoDerivado` y la unica via manual es esta, con dos guardas — el
 * estado pedido debe ser manual, y el estado ACTUAL tambien, porque mientras
 * exista la oportunidad que justifica el derivado este tiene prioridad.
 *
 * En archivo aparte de EmpresaServiceImplTest solo por tamaño; misma unidad.
 */
class EmpresaEstadoCarteraServiceTest {
    private val empresaRepository = mockk<EmpresaRepository>()
    private val empleadoService = mockk<EmpleadoService>()
    private val notificacionService = mockk<NotificacionService>(relaxed = true)
    private val eventPublisher = mockk<ApplicationEventPublisher>(relaxed = true)
    private val driveStorageService = mockk<DriveStorageService>()
    private val contactoService = mockk<ContactoService>()
    private val transactionTemplate = mockk<TransactionTemplate>()
    private val service =
        EmpresaServiceImpl(
            empresaRepository,
            empleadoService,
            notificacionService,
            eventPublisher,
            driveStorageService,
            contactoService,
            transactionTemplate,
        )

    private val gerencia = UsuarioActual(id = 1, rol = "gerencia")
    private val jdv = UsuarioActual(id = 3, rol = "jdv")
    private val vendedor = UsuarioActual(id = 7, rol = "vendedor")

    private fun empresa(
        estadoCartera: EstadoCartera = EstadoCartera.prospeccion,
        id: Long = 1,
        idVendedor: Long? = null,
    ): Empresa =
        Empresa(
            id = id,
            ruc = "20123456789",
            razonSocial = "Transportes ABC",
            estadoCartera = estadoCartera,
            idVendedor = idVendedor,
            createdAt = LocalDateTime.now(),
            createdBy = 1,
            updatedAt = LocalDateTime.now(),
            updatedBy = 1,
        )

    // ── cambiarEstadoCarteraManual (reglas_negocio.md §3.1) ────

    @Test
    fun `cambiar a un estado manual desde otro manual guarda y devuelve el nuevo estado`() {
        val entidad = empresa(estadoCartera = EstadoCartera.no_contactado)
        every { empresaRepository.findById(1) } returns Optional.of(entidad)
        every { empresaRepository.save(entidad) } returns entidad

        val resultado = service.cambiarEstadoCarteraManual(1, "prospeccion", gerencia)

        assertThat(resultado).isEqualTo("prospeccion")
        assertThat(entidad.estadoCartera).isEqualTo(EstadoCartera.prospeccion)
        assertThat(entidad.updatedBy).isEqualTo(1L)
        verify { empresaRepository.save(entidad) }
    }

    @Test
    fun `cambiar a un estado que no existe en el enum es ESTADO_INVALIDO`() {
        every { empresaRepository.findById(1) } returns Optional.of(empresa())

        assertThatThrownBy { service.cambiarEstadoCarteraManual(1, "__nope__", gerencia) }
            .isInstanceOf(EstadoInvalidoException::class.java)
            .hasMessageContaining("__nope__")
        verify(exactly = 0) { empresaRepository.save(any()) }
    }

    @Test
    fun `no se puede pedir a mano un estado derivado - lo pone el sistema`() {
        every { empresaRepository.findById(1) } returns Optional.of(empresa())

        listOf(EstadoCartera.oportunidad_activa, EstadoCartera.cliente).forEach { derivado ->
            assertThatThrownBy { service.cambiarEstadoCarteraManual(1, derivado.name, gerencia) }
                .describedAs("el estado derivado '%s' no puede fijarse a mano", derivado.name)
                .isInstanceOf(EstadoInvalidoException::class.java)
        }
        verify(exactly = 0) { empresaRepository.save(any()) }
    }

    @Test
    fun `una empresa en estado derivado no acepta cambio manual mientras lo conserve`() {
        val activa = empresa(estadoCartera = EstadoCartera.oportunidad_activa)
        every { empresaRepository.findById(1) } returns Optional.of(activa)

        assertThatThrownBy { service.cambiarEstadoCarteraManual(1, "no_interesado", gerencia) }
            .isInstanceOf(EstadoInvalidoException::class.java)
            .hasMessageContaining("oportunidad_activa")
        assertThat(activa.estadoCartera).isEqualTo(EstadoCartera.oportunidad_activa)
        verify(exactly = 0) { empresaRepository.save(any()) }
    }

    @Test
    fun `cambiar el estado de una empresa ajena es 404 - IDOR`() {
        every { empresaRepository.findById(1) } returns Optional.of(empresa(idVendedor = 99))

        assertThatThrownBy { service.cambiarEstadoCarteraManual(1, "no_interesado", vendedor) }
            .isInstanceOf(NoEncontradoException::class.java)
        verify(exactly = 0) { empresaRepository.save(any()) }
    }

    @Test
    fun `el vendedor dueño si puede cambiar el estado de su empresa`() {
        val propia = empresa(idVendedor = 7)
        every { empresaRepository.findById(1) } returns Optional.of(propia)
        every { empresaRepository.save(propia) } returns propia

        assertThat(service.cambiarEstadoCarteraManual(1, "no_aplica", vendedor)).isEqualTo("no_aplica")
    }

    // ── checkRuc (contrato_api.md §8, B2.2) ────────────────────

    @Test
    fun `checkRuc de un RUC ya registrado avisa sin decir de quien es`() {
        every { empresaRepository.existsByRuc("20123456789") } returns true

        val dto = service.checkRuc("20123456789")

        assertThat(dto.existe).isTrue()
        assertThat(dto.mensaje).isEqualTo("Esta empresa ya está registrada en el sistema")
    }

    @Test
    fun `checkRuc de un RUC libre responde existe false y sin mensaje`() {
        every { empresaRepository.existsByRuc("20999999999") } returns false

        val dto = service.checkRuc("20999999999")

        assertThat(dto.existe).isFalse()
        assertThat(dto.mensaje).isNull()
    }

    // ── actualizar (PATCH parcial, contrato_api.md §8) ─────────

    @Test
    fun `actualizar aplica todos los campos enviados y reemplaza los segmentos`() {
        val entidad = empresa().apply { segmentos = mutableSetOf(Segmento.urbano) }
        every { empresaRepository.findById(1) } returns Optional.of(entidad)
        every { empresaRepository.existsByRuc("20777777777") } returns false
        every { empresaRepository.save(entidad) } returns entidad
        every { empleadoService.resumenPorIds(any()) } returns emptyMap()
        every { contactoService.contactosDeEmpresa(1) } returns emptyList()

        val detalle =
            service.actualizar(
                1,
                ActualizarEmpresaRequest(
                    ruc = "20777777777",
                    razonSocial = "Transportes XYZ",
                    actividadEcon = "Transporte de pasajeros",
                    ciiu = "4921",
                    sectorIndustrial = "Transporte",
                    estadoSunat = "ACTIVO",
                    condicionSunat = "HABIDO",
                    direccionFiscal = "Av. Siempre Viva 742",
                    ubicacionReal = "Planta Ate",
                    distrito = "Ate",
                    provincia = "Lima",
                    departamento = "Lima",
                    avalFiador = "Juan Perez",
                    origenLead = OrigenLead.referido_calidda,
                    fileDrive = "https://drive.google.com/x",
                    sitioWeb = "https://xyz.pe",
                    notas = "Cliente recurrente",
                    segmentos = listOf(Segmento.interprovincial, Segmento.turismo),
                ),
                gerencia,
            )

        assertThat(detalle.ruc).isEqualTo("20777777777")
        assertThat(detalle.razonSocial).isEqualTo("Transportes XYZ")
        assertThat(detalle.distrito).isEqualTo("Ate")
        assertThat(detalle.origenLead).isEqualTo("referido_calidda")
        // `segmentos` no acumula: el envio reemplaza el conjunto completo.
        assertThat(detalle.segmentos).containsExactly("interprovincial", "turismo")
        assertThat(entidad.updatedBy).isEqualTo(1L)
    }

    @Test
    fun `actualizar a un RUC que ya tiene otra empresa es RUC_DUPLICADO`() {
        val entidad = empresa()
        every { empresaRepository.findById(1) } returns Optional.of(entidad)
        every { empresaRepository.existsByRuc("20777777777") } returns true

        assertThatThrownBy { service.actualizar(1, ActualizarEmpresaRequest(ruc = "20777777777"), gerencia) }
            .isInstanceOf(RucDuplicadoException::class.java)
        assertThat(entidad.ruc).isEqualTo("20123456789")
        verify(exactly = 0) { empresaRepository.save(any()) }
    }

    @Test
    fun `reenviar el mismo RUC en el PATCH no lo trata como duplicado`() {
        val entidad = empresa()
        every { empresaRepository.findById(1) } returns Optional.of(entidad)
        every { empresaRepository.save(entidad) } returns entidad
        every { empleadoService.resumenPorIds(any()) } returns emptyMap()
        every { contactoService.contactosDeEmpresa(1) } returns emptyList()

        service.actualizar(1, ActualizarEmpresaRequest(ruc = "20123456789"), gerencia)

        verify(exactly = 0) { empresaRepository.existsByRuc(any()) }
    }

    // ── cartera maestra (exclusiva de gerencia y admin) ────────

    @Test
    fun `la cartera maestra es exclusiva de gerencia y admin - el jdv no la toca`() {
        assertThatThrownBy { service.cambiarCarteraMaestra(1, enCarteraMaestra = true, idVendedor = null, usuario = jdv) }
            .isInstanceOf(PermisoInsuficienteException::class.java)
        assertThatThrownBy { service.cambiarCarteraMaestra(1, enCarteraMaestra = true, idVendedor = null, usuario = vendedor) }
            .isInstanceOf(PermisoInsuficienteException::class.java)
        verify(exactly = 0) { empresaRepository.findById(any()) }
    }

    @Test
    fun `liberar hacia un destino que no es vendedor ni jdv activo es VALIDACION`() {
        val reservada = empresa().apply { enCarteraMaestra = true }
        every { empresaRepository.findById(1) } returns Optional.of(reservada)
        every { empleadoService.esAsignableComoVendedor(99) } returns false

        assertThatThrownBy { service.cambiarCarteraMaestra(1, enCarteraMaestra = false, idVendedor = 99, usuario = gerencia) }
            .isInstanceOf(ValidacionException::class.java)
        assertThat(reservada.enCarteraMaestra).isTrue()
        verify(exactly = 0) { empresaRepository.save(any()) }
    }

    // ── lecturas para otros modulos ────────────────────────────

    @Test
    fun `vinculoVisible expone lo minimo de la empresa y respeta la visibilidad`() {
        val propia = empresa(idVendedor = 7).apply { driveFolderId = "carpeta-abc" }
        every { empresaRepository.findById(1) } returns Optional.of(propia)

        val vinculo = service.vinculoVisible(1, vendedor)

        assertThat(vinculo.id).isEqualTo(1L)
        assertThat(vinculo.razonSocial).isEqualTo("Transportes ABC")
        assertThat(vinculo.estadoCartera).isEqualTo("prospeccion")
        assertThat(vinculo.driveFolderId).isEqualTo("carpeta-abc")
    }

    @Test
    fun `vendedorAsignado devuelve el dueño y null si la empresa no existe`() {
        every { empresaRepository.findById(1) } returns Optional.of(empresa(idVendedor = 7))
        every { empresaRepository.findById(99) } returns Optional.empty()

        assertThat(service.vendedorAsignado(1)).isEqualTo(7L)
        assertThat(service.vendedorAsignado(99)).isNull()
    }

    @Test
    fun `resumenPorIds devuelve razon social y distrito por id`() {
        val entidad = empresa().apply { distrito = "Ate" }
        every { empresaRepository.findAllById(setOf(1L)) } returns listOf(entidad)

        val resumen = service.resumenPorIds(listOf(1L, 1L))

        assertThat(resumen).containsOnlyKeys(1L)
        assertThat(resumen.getValue(1L).razonSocial).isEqualTo("Transportes ABC")
        assertThat(resumen.getValue(1L).distrito).isEqualTo("Ate")
    }

    // ── aplicarEstadoDerivado: vuelta al estado manual base ────

    @Test
    fun `sin derivado y con estado manual no se escribe nada`() {
        val entidad = empresa(estadoCartera = EstadoCartera.no_interesado)
        every { empresaRepository.findById(1) } returns Optional.of(entidad)

        assertThat(service.aplicarEstadoDerivado(1, null)).isNull()
        verify(exactly = 0) { empresaRepository.save(any()) }
    }

    @Test
    fun `sin derivado y con estado derivado la empresa baja a prospeccion`() {
        val entidad = empresa(estadoCartera = EstadoCartera.cliente)
        every { empresaRepository.findById(1) } returns Optional.of(entidad)
        every { empresaRepository.save(entidad) } returns entidad

        val cambio = service.aplicarEstadoDerivado(1, null)

        assertThat(cambio?.anterior).isEqualTo(EstadoCartera.cliente)
        assertThat(cambio?.nuevo).isEqualTo(EstadoCartera.prospeccion)
    }
}
