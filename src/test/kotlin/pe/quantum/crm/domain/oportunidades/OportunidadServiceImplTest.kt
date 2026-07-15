package pe.quantum.crm.domain.oportunidades

import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import pe.quantum.crm.domain.contactos.ContactoService
import pe.quantum.crm.domain.empleados.EmpleadoService
import pe.quantum.crm.domain.empleados.dto.EmpleadoResumen
import pe.quantum.crm.domain.empresas.EmpresaService
import pe.quantum.crm.domain.empresas.VendedorEmpresaReasignadoEvent
import pe.quantum.crm.domain.empresas.dto.CambioEstadoCartera
import pe.quantum.crm.domain.empresas.dto.EmpresaResumen
import pe.quantum.crm.domain.financiadoras.FinanciadoraService
import pe.quantum.crm.domain.modelos.ModeloService
import pe.quantum.crm.domain.notificaciones.EntidadNotificacion
import pe.quantum.crm.domain.notificaciones.NotificacionService
import pe.quantum.crm.domain.notificaciones.TipoNotificacion
import pe.quantum.crm.shared.enums.EstadoCartera
import pe.quantum.crm.shared.security.UsuarioActual
import java.time.LocalDateTime
import java.util.Optional

class OportunidadServiceImplTest {
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
        )

    private fun oportunidad(
        id: Long = 100,
        idVendedor: Long = 1,
    ) = Oportunidad(
        id = id,
        idEmpresa = 10,
        idVendedor = idVendedor,
        idFinanciadora = 1,
        idModelo = 1,
        estado = pe.quantum.crm.shared.enums.EstadoOportunidad.evaluacion_calidda,
        cantidad = 1,
        precioUnitario = java.math.BigDecimal.TEN,
        dcto = java.math.BigDecimal.ZERO,
        montoTotal = java.math.BigDecimal.TEN,
        createdAt = LocalDateTime.now(),
        createdBy = 1,
        updatedAt = LocalDateTime.now(),
        updatedBy = 1,
    )

    /** Financiadora Calidda usada como stub en varios escenarios de `crear`. */
    private fun calidda() =
        pe.quantum.crm.domain.financiadoras.dto.FinanciadoraDto(
            id = 1,
            nombre = "Calidda",
            montoPorUnidad = null,
            plazoMeses = null,
            tea = null,
            cuotaPorUnidad = null,
            esDefault = true,
            notas = null,
        )

    /** Modelo BUS-X usado como stub en varios escenarios de `crear`. */
    private fun busX() = pe.quantum.crm.domain.modelos.dto.ModeloResumen(id = 1, codigo = "BUS-X", precioBase = java.math.BigDecimal.TEN)

    /** Devuelve una copia de la oportunidad con `id` asignado, simulando lo que hace JPA al guardar. */
    private fun Oportunidad.conId(nuevoId: Long) =
        Oportunidad(
            id = nuevoId,
            idEmpresa = idEmpresa,
            idVendedor = idVendedor,
            idFinanciadora = idFinanciadora,
            idModelo = idModelo,
            estado = estado,
            cantidad = cantidad,
            precioUnitario = precioUnitario,
            dcto = dcto,
            montoTotal = montoTotal,
            fincParalelo = fincParalelo,
            garantia = garantia,
            fichaVenta = fichaVenta,
            notas = notas,
            motivoCierre = motivoCierre,
            fechaCierreEstimado = fechaCierreEstimado,
            createdAt = createdAt,
            createdBy = createdBy,
            updatedAt = updatedAt,
            updatedBy = updatedBy,
        )

    @Test
    fun `onVendedorEmpresaReasignado actualiza y notifica las oportunidades activas con vendedor distinto`() {
        val activa = oportunidad(id = 100, idVendedor = 1)
        val yaAsignada = oportunidad(id = 101, idVendedor = 2)
        every {
            oportunidadRepository.findByIdEmpresaAndEstadoIn(10, EstadoCarteraService.ESTADOS_ACTIVOS)
        } returns listOf(activa, yaAsignada)
        every { oportunidadRepository.saveAll(listOf(activa)) } returns listOf(activa)
        every { empleadoService.resumenPorIds(listOf(9)) } returns
            mapOf(9L to EmpleadoResumen(id = 9, nombres = "Aldo", apellidos = "Martinez"))
        every { empresaService.resumenPorIds(listOf(10)) } returns
            mapOf(10L to EmpresaResumen(id = 10, razonSocial = "Kincar S.A.C.", distrito = null))

        service.onVendedorEmpresaReasignado(VendedorEmpresaReasignadoEvent(idEmpresa = 10, idVendedorNuevo = 2, idActor = 9))

        assertThat(activa.idVendedor).isEqualTo(2)
        assertThat(activa.updatedBy).isEqualTo(9)
        assertThat(yaAsignada.idVendedor).isEqualTo(2)
        verify(exactly = 1) {
            notificacionService.notificar(
                destinatarios = setOf(2L),
                idActor = 9L,
                tipo = TipoNotificacion.oportunidad_traspasada,
                mensaje = "Aldo Martinez te traspasó la oportunidad de Kincar S.A.C.",
                entidadTipo = EntidadNotificacion.oportunidad,
                entidadId = 100L,
            )
        }
    }

    @Test
    fun `onVendedorEmpresaReasignado no hace nada si ninguna oportunidad activa cambia de vendedor`() {
        val yaAsignada = oportunidad(id = 101, idVendedor = 2)
        every {
            oportunidadRepository.findByIdEmpresaAndEstadoIn(10, EstadoCarteraService.ESTADOS_ACTIVOS)
        } returns listOf(yaAsignada)

        service.onVendedorEmpresaReasignado(VendedorEmpresaReasignadoEvent(idEmpresa = 10, idVendedorNuevo = 2, idActor = 9))

        verify(exactly = 0) { oportunidadRepository.saveAll(any<List<Oportunidad>>()) }
        verify(exactly = 0) { notificacionService.notificar(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `cambiarEstado notifica a los supervisores activos, excluyendo al actor si es supervisor`() {
        val entidad = oportunidad(idVendedor = 1)
        every { oportunidadRepository.findById(100) } returns Optional.of(entidad)
        every { oportunidadRepository.save(entidad) } returns entidad
        every { logRepository.save(any()) } returns mockk()
        every {
            consultas.eventosRecomendadosSinRegistrar(100, pe.quantum.crm.shared.enums.EstadoOportunidad.evaluacion_calidda)
        } returns emptyList()
        every { estadoCarteraService.actualizar(10) } returns null
        every { empresaService.resumenPorIds(listOf(10)) } returns
            mapOf(10L to EmpresaResumen(id = 10, razonSocial = "Kincar S.A.C.", distrito = null))
        every { empleadoService.resumenPorIds(listOf(1)) } returns mapOf(1L to EmpleadoResumen(id = 1, nombres = "Ana", apellidos = "Diaz"))
        every { empleadoService.idsSupervisoresActivos() } returns listOf(1, 5, 6)

        service.cambiarEstado(
            100,
            pe.quantum.crm.domain.oportunidades.dto.CambiarEstadoRequest(estado = "documentos_legales"),
            UsuarioActual(id = 1, rol = "vendedor"),
        )

        verify {
            notificacionService.notificar(
                destinatarios = setOf(1L, 5L, 6L),
                idActor = 1L,
                tipo = TipoNotificacion.oportunidad_cambio_estado,
                mensaje = "Ana Diaz cambió el estado de Kincar S.A.C. a Documentos legales",
                entidadTipo = EntidadNotificacion.oportunidad,
                entidadId = 100L,
            )
        }
    }

    @Test
    fun `crear notifica empresa_convertida cuando la empresa pasa de prospeccion a oportunidad_activa`() {
        every { empresaService.vinculoVisible(10, any()) } returns
            pe.quantum.crm.domain.empresas.dto.EmpresaVinculo(
                id = 10,
                razonSocial = "Kincar S.A.C.",
                idVendedor = 3,
                estadoCartera = "prospeccion",
            )
        every { modeloService.resumen(1) } returns busX()
        every { financiadoraService.default() } returns calidda()
        val guardada = slot<Oportunidad>()
        every { oportunidadRepository.save(capture(guardada)) } answers { guardada.captured.conId(100) }
        every { logRepository.save(any()) } returns mockk()
        every {
            estadoCarteraService.actualizar(10)
        } returns CambioEstadoCartera(anterior = EstadoCartera.prospeccion, nuevo = EstadoCartera.oportunidad_activa)
        every {
            empresaService.resumenPorIds(listOf(10))
        } returns mapOf(10L to EmpresaResumen(id = 10, razonSocial = "Kincar S.A.C.", distrito = null))
        every { empleadoService.resumenPorIds(listOf(3)) } returns
            mapOf(3L to EmpleadoResumen(id = 3, nombres = "Jose", apellidos = "Lima"))
        every { empleadoService.idsSupervisoresActivos() } returns listOf(9)
        // Stubs adicionales requeridos por el ensamblado de OportunidadDto (toDto/toDtos).
        every { financiadoraService.porIds(listOf(1)) } returns mapOf(1L to calidda())
        every { modeloService.resumenPorIds(listOf(1)) } returns mapOf(1L to busX())
        every { consultas.tareasPendientesPorOportunidad(listOf(100L)) } returns emptyMap()
        every { consultas.eventosPendientesPorOportunidad(listOf(100L)) } returns emptyMap()
        every { contactoOportunidadRepository.findByIdIdOportunidad(100L) } returns emptyList()
        every { contactoService.resumenPorIds(emptyList()) } returns emptyMap()
        every { logRepository.findFirstByIdOportunidadOrderByChangedAtDescIdDesc(100L) } returns null

        service.crear(
            pe.quantum.crm.domain.oportunidades.dto.CrearOportunidadRequest(
                idEmpresa = 10,
                idModelo = 1,
                cantidad = 1,
                dcto = java.math.BigDecimal.ZERO,
            ),
            UsuarioActual(id = 3, rol = "vendedor"),
        )

        verify {
            notificacionService.notificar(
                destinatarios = setOf(9L),
                idActor = 3L,
                tipo = TipoNotificacion.empresa_convertida,
                mensaje = "Jose Lima convirtió Kincar S.A.C. de prospección a oportunidad",
                entidadTipo = EntidadNotificacion.oportunidad,
                entidadId = 100L,
            )
        }
    }

    @Test
    fun `countPorContacto devuelve la cantidad de vinculos del contacto`() {
        every { contactoOportunidadRepository.countByIdIdContacto(5) } returns 3L

        val resultado = service.countPorContacto(5)

        assertThat(resultado).isEqualTo(3)
    }

    @Test
    fun `oportunidadesPorContacto mapea empresa, modelo, monto y rol`() {
        val vinculo =
            OportunidadContacto(
                id = OportunidadContactoId(idOportunidad = 100, idContacto = 5),
                rolEnOportunidad = "Contacto Principal",
            )
        every { contactoOportunidadRepository.findByIdIdContacto(5) } returns listOf(vinculo)
        every { oportunidadRepository.findAllById(listOf(100L)) } returns listOf(oportunidad(id = 100))
        every { empresaService.resumenPorIds(listOf(10L)) } returns
            mapOf(10L to EmpresaResumen(id = 10, razonSocial = "Transp. Sta. Anita S.A.", distrito = null))
        every { modeloService.resumenPorIds(listOf(1L)) } returns mapOf(1L to busX())

        val resultado = service.oportunidadesPorContacto(5)

        assertThat(resultado).hasSize(1)
        val dto = resultado.first()
        assertThat(dto.id).isEqualTo(100)
        assertThat(dto.empresa?.razonSocial).isEqualTo("Transp. Sta. Anita S.A.")
        assertThat(dto.modelo?.codigo).isEqualTo("BUS-X")
        assertThat(dto.montoTotal).isEqualTo("10")
        assertThat(dto.rolEnOportunidad).isEqualTo("Contacto Principal")
    }

    @Test
    fun `oportunidadesPorContacto devuelve vacio si no hay vinculos`() {
        every { contactoOportunidadRepository.findByIdIdContacto(5) } returns emptyList()

        val resultado = service.oportunidadesPorContacto(5)

        assertThat(resultado).isEmpty()
    }
}
