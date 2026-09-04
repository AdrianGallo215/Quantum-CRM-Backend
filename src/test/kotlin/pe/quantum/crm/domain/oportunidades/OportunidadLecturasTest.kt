package pe.quantum.crm.domain.oportunidades

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.data.domain.PageImpl
import org.springframework.data.domain.PageRequest
import org.springframework.data.jpa.domain.Specification
import pe.quantum.crm.domain.contactos.ContactoService
import pe.quantum.crm.domain.empleados.EmpleadoService
import pe.quantum.crm.domain.empleados.dto.EmpleadoResumen
import pe.quantum.crm.domain.empresas.EmpresaService
import pe.quantum.crm.domain.empresas.dto.EmpresaResumen
import pe.quantum.crm.domain.financiadoras.FinanciadoraService
import pe.quantum.crm.domain.financiadoras.dto.FinanciadoraDto
import pe.quantum.crm.domain.modelos.ModeloService
import pe.quantum.crm.domain.modelos.dto.ModeloResumen
import pe.quantum.crm.domain.notificaciones.NotificacionService
import pe.quantum.crm.domain.oportunidades.dto.ModeloEnOportunidadDto
import pe.quantum.crm.domain.oportunidades.dto.OportunidadFiltros
import pe.quantum.crm.domain.oportunidades.dto.OportunidadItemDto
import pe.quantum.crm.domain.tareas.TareaService
import pe.quantum.crm.integracion.drive.DriveStorageService
import pe.quantum.crm.shared.enums.EstadoOportunidad
import pe.quantum.crm.shared.exception.NoEncontradoException
import pe.quantum.crm.shared.security.UsuarioActual
import java.math.BigDecimal
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.Optional

/**
 * Lecturas del modulo: listado paginado, historial de estados y los dos accesos
 * que otros modulos usan (`vinculoVisible` para eventos/tareas y
 * `datosRecordatorio` para el job de notificaciones, que corre sin usuario).
 */
class OportunidadLecturasTest {
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
        )

    private val admin = UsuarioActual(id = 1, rol = "admin")
    private val vendedor = UsuarioActual(id = 5, rol = "vendedor")

    private fun oportunidad(
        id: Long = 100,
        idVendedor: Long = 5,
    ) = Oportunidad(
        id = id,
        idEmpresa = 10,
        idVendedor = idVendedor,
        idFinanciadora = 1,
        idModelo = 1,
        estado = EstadoOportunidad.documentos_legales,
        cantidad = 2,
        precioUnitario = BigDecimal("100.00"),
        dcto = BigDecimal.ZERO,
        montoTotal = BigDecimal("200.00"),
        createdAt = LocalDateTime.of(2026, 1, 15, 9, 30),
        createdBy = 5,
        updatedAt = LocalDateTime.now(),
        updatedBy = 5,
    )

    private fun itemDto() =
        OportunidadItemDto(
            id = 500,
            idModelo = 1,
            modelo = ModeloEnOportunidadDto(id = 1, codigo = "BUS-X", precioBase = "100.00"),
            cantidad = 2,
            precioVenta = "100.00",
            descuento = "0.00",
            cuotaFinanciadora = "0.00",
            montoItem = "200.00",
        )

    // ── GET /oportunidades ────────────────────────────────────

    @Test
    fun `listar ensambla los DTOs del lote y la meta de paginacion`() {
        every { oportunidadRepository.findAll(any<Specification<Oportunidad>>(), any<PageRequest>()) } returns
            PageImpl(listOf(oportunidad(id = 100), oportunidad(id = 101)), PageRequest.of(0, 20), 42)
        every { empresaService.resumenPorIds(any()) } returns
            mapOf(10L to EmpresaResumen(id = 10, razonSocial = "Kincar S.A.C.", distrito = "Miraflores"))
        every { empleadoService.resumenPorIds(any()) } returns
            mapOf(5L to EmpleadoResumen(id = 5, nombres = "Ana", apellidos = "Diaz"))
        every { financiadoraService.porIds(any()) } returns mapOf(1L to calidda())
        every { modeloService.resumenPorIds(any()) } returns
            mapOf(1L to ModeloResumen(id = 1, codigo = "BUS-X", precioBase = BigDecimal("100.00")))
        // Modelo y monto ya no salen de la oportunidad: los aporta OportunidadItemService (B8).
        every { oportunidadItemService.porOportunidades(listOf(100L, 101L)) } returns
            mapOf(100L to listOf(itemDto()), 101L to listOf(itemDto()))
        every { oportunidadItemService.montoTotalPorOportunidades(listOf(100L, 101L)) } returns
            mapOf(100L to BigDecimal("200.00"), 101L to BigDecimal("200.00"))
        every { consultas.tareasPendientesPorOportunidad(listOf(100L, 101L)) } returns mapOf(100L to 3)
        every { consultas.eventosPendientesPorOportunidad(listOf(100L, 101L)) } returns mapOf(101L to 1)

        val paginado = service.listar(OportunidadFiltros(), admin, page = null, perPage = null, sort = null, dir = null)

        assertThat(paginado.items.map { it.id }).containsExactly(100L, 101L)
        assertThat(paginado.items[0].empresa?.razonSocial).isEqualTo("Kincar S.A.C.")
        assertThat(paginado.items[0].vendedor?.nombres).isEqualTo("Ana")
        assertThat(paginado.items[0].financiadora?.nombre).isEqualTo("Calidda")
        assertThat(paginado.items[0].items.single().modelo?.codigo).isEqualTo("BUS-X")
        assertThat(paginado.items[0].items.single().modelo?.precioBase).isEqualTo("100.00")
        assertThat(paginado.items[0].montoTotal).isEqualTo("200.00")
        assertThat(paginado.items[0].tareasPendientesCount).isEqualTo(3)
        assertThat(paginado.items[0].eventosPendientesCount).isZero()
        assertThat(paginado.items[1].eventosPendientesCount).isEqualTo(1)
        // El listado no trae el bloque de detalle.
        assertThat(paginado.items[0].contactos).isNull()
        assertThat(paginado.meta.page).isEqualTo(1)
        assertThat(paginado.meta.perPage).isEqualTo(20)
        assertThat(paginado.meta.total).isEqualTo(42)
        assertThat(paginado.meta.totalPages).isEqualTo(3)
    }

    @Test
    fun `listar sin resultados no consulta contadores ni catalogos`() {
        every { oportunidadRepository.findAll(any<Specification<Oportunidad>>(), any<PageRequest>()) } returns
            PageImpl(emptyList(), PageRequest.of(0, 20), 0)

        val paginado = service.listar(OportunidadFiltros(), admin, page = null, perPage = null, sort = null, dir = null)

        assertThat(paginado.items).isEmpty()
        assertThat(paginado.meta.total).isZero()
        verify(exactly = 0) { consultas.tareasPendientesPorOportunidad(any()) }
    }

    // ── GET /oportunidades/:id/log ────────────────────────────

    @Test
    fun `log devuelve el historial en orden con el empleado que hizo cada cambio`() {
        val alta = LocalDateTime.of(2026, 1, 15, 9, 30)
        val avance = LocalDateTime.of(2026, 2, 1, 16, 0)
        every { oportunidadRepository.findById(100) } returns Optional.of(oportunidad())
        every { logRepository.findByIdOportunidadOrderByChangedAtAscIdAsc(100) } returns
            listOf(
                OportunidadEstadoLog(
                    idOportunidad = 100,
                    estadoAnterior = null,
                    estadoNuevo = EstadoOportunidad.evaluacion_calidda,
                    changedAt = alta,
                    changedBy = 5,
                ),
                OportunidadEstadoLog(
                    idOportunidad = 100,
                    estadoAnterior = EstadoOportunidad.evaluacion_calidda,
                    estadoNuevo = EstadoOportunidad.documentos_legales,
                    changedAt = avance,
                    changedBy = 9,
                ),
            )
        every { empleadoService.resumenPorIds(listOf(5L, 9L)) } returns
            mapOf(5L to EmpleadoResumen(id = 5, nombres = "Ana", apellidos = "Diaz"))

        val entradas = service.log(100, vendedor)

        assertThat(entradas).hasSize(2)
        // El primer registro de toda oportunidad lleva estado_anterior = NULL (reglas §4.2).
        assertThat(entradas[0].estadoAnterior).isNull()
        assertThat(entradas[0].estadoNuevo).isEqualTo("evaluacion_calidda")
        assertThat(entradas[0].changedAt).isEqualTo(alta.toInstant(ZoneOffset.UTC))
        assertThat(entradas[0].changedBy?.nombres).isEqualTo("Ana")
        assertThat(entradas[1].estadoAnterior).isEqualTo("evaluacion_calidda")
        assertThat(entradas[1].estadoNuevo).isEqualTo("documentos_legales")
        // Un empleado que ya no se resuelve no rompe el historial: viaja como null.
        assertThat(entradas[1].changedBy).isNull()
    }

    @Test
    fun `log de una oportunidad ajena responde 404 sin leer el historial`() {
        every { oportunidadRepository.findById(100) } returns Optional.of(oportunidad(idVendedor = 5))

        assertThatThrownBy { service.log(100, UsuarioActual(id = 77, rol = "vendedor")) }
            .isInstanceOf(NoEncontradoException::class.java)

        verify(exactly = 0) { logRepository.findByIdOportunidadOrderByChangedAtAscIdAsc(any()) }
    }

    // ── API para otros modulos ────────────────────────────────

    @Test
    fun `vinculoVisible devuelve empresa, vendedor y estado de la oportunidad`() {
        every { oportunidadRepository.findById(100) } returns Optional.of(oportunidad())

        val vinculo = service.vinculoVisible(100, vendedor)

        assertThat(vinculo.id).isEqualTo(100)
        assertThat(vinculo.idEmpresa).isEqualTo(10)
        assertThat(vinculo.idVendedor).isEqualTo(5)
        assertThat(vinculo.estado).isEqualTo("documentos_legales")
    }

    @Test
    fun `vinculoVisible de una oportunidad ajena responde 404`() {
        every { oportunidadRepository.findById(100) } returns Optional.of(oportunidad(idVendedor = 5))

        assertThatThrownBy { service.vinculoVisible(100, UsuarioActual(id = 77, rol = "vendedor")) }
            .isInstanceOf(NoEncontradoException::class.java)
    }

    /** Un supervisor alcanza cualquier oportunidad, no solo las suyas. */
    @Test
    fun `vinculoVisible deja pasar a un supervisor sobre una oportunidad de otro`() {
        every { oportunidadRepository.findById(100) } returns Optional.of(oportunidad(idVendedor = 5))

        assertThat(service.vinculoVisible(100, admin).idVendedor).isEqualTo(5)
    }

    @Test
    fun `datosRecordatorio devuelve empresa y vendedor sin comprobar visibilidad`() {
        every { oportunidadRepository.findById(100) } returns Optional.of(oportunidad())

        val datos = service.datosRecordatorio(100)

        assertThat(datos?.idEmpresa).isEqualTo(10)
        assertThat(datos?.idVendedor).isEqualTo(5)
    }

    @Test
    fun `datosRecordatorio de una oportunidad inexistente devuelve null`() {
        every { oportunidadRepository.findById(999) } returns Optional.empty()

        assertThat(service.datosRecordatorio(999)).isNull()
    }

    @Test
    fun `tieneOportunidadesActivas delega en el repositorio con los estados activos`() {
        every { oportunidadRepository.existsByIdEmpresaAndEstadoIn(10, EstadoCarteraService.ESTADOS_ACTIVOS) } returns true

        assertThat(service.tieneOportunidadesActivas(10)).isTrue()

        verify { oportunidadRepository.existsByIdEmpresaAndEstadoIn(10, EstadoCarteraService.ESTADOS_ACTIVOS) }
    }

    private fun calidda() =
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
}
