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
import pe.quantum.crm.domain.oportunidades.dto.OportunidadItemDatos
import pe.quantum.crm.domain.oportunidades.dto.OportunidadItemDto
import pe.quantum.crm.domain.simulaciones.SimulacionService
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
    private val listadoDao = mockk<OportunidadListadoDao>(relaxed = true)
    private val simulacionService =
        mockk<SimulacionService> { every { cuotaQuantumPorItems(any()) } returns emptyMap() }
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
            simulacionService,
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
        estado = EstadoOportunidad.documentos_legales,
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
            cuotaQuantum = null,
            cuotaTotal = null,
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
        every { oportunidadItemService.datosCrudosPorOportunidades(any()) } returns emptyMap()
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

    // ── §6.2: cuota Quantum, cuota total y cuota diaria (D62) ──

    private fun itemDtoCuota(datos: OportunidadItemDatos) =
        itemDto().copy(
            id = datos.id,
            cantidad = datos.cantidad,
            cuotaFinanciadora = datos.cuotaFinanciadora.toPlainString(),
        )

    private fun itemDatos(
        id: Long,
        idOportunidad: Long,
        cantidad: Int?,
        cuotaFinanciadora: String,
    ) = OportunidadItemDatos(
        id = id,
        idOportunidad = idOportunidad,
        cantidad = cantidad,
        precioVenta = BigDecimal("100.00"),
        descuento = BigDecimal.ZERO,
        cuotaFinanciadora = BigDecimal(cuotaFinanciadora),
    )

    /**
     * Una pagina de listado con los items dados y todos los catalogos ya
     * resueltos: los tests de §6.2 solo declaran que items hay y que cuota
     * Quantum devuelve `simulaciones` para cada uno.
     */
    private fun stubPagina(
        ids: List<Long>,
        items: List<OportunidadItemDatos>,
        cuotas: Map<Long, BigDecimal>,
    ) {
        every { oportunidadRepository.findAll(any<Specification<Oportunidad>>(), any<PageRequest>()) } returns
            PageImpl(ids.map { oportunidad(id = it) }, PageRequest.of(0, 20), ids.size.toLong())
        every { empresaService.resumenPorIds(any()) } returns emptyMap()
        every { empleadoService.resumenPorIds(any()) } returns emptyMap()
        every { financiadoraService.porIds(any()) } returns emptyMap()
        every { oportunidadItemService.porOportunidades(ids) } returns
            items.groupBy { it.idOportunidad }.mapValues { (_, deLaOportunidad) -> deLaOportunidad.map(::itemDtoCuota) }
        every { oportunidadItemService.datosCrudosPorOportunidades(ids) } returns items.associateBy { it.id }
        every { oportunidadItemService.montoTotalPorOportunidades(ids) } returns emptyMap()
        every { simulacionService.cuotaQuantumPorItems(any()) } returns cuotas
        every { consultas.tareasPendientesPorOportunidad(ids) } returns emptyMap()
        every { consultas.eventosPendientesPorOportunidad(ids) } returns emptyMap()
    }

    private fun listarPagina() = service.listar(OportunidadFiltros(), admin, page = null, perPage = null, sort = null, dir = null)

    @Test
    fun `el item con cuota resuelta expone cuotaTotal igual a cuotaQuantum mas cuotaFinanciadora`() {
        // 1000 (cuota Quantum) + 500 (cuota financiadora del item) = 1500.
        stubPagina(
            ids = listOf(100L),
            items = listOf(itemDatos(id = 500, idOportunidad = 100, cantidad = 2, cuotaFinanciadora = "500.00")),
            cuotas = mapOf(500L to BigDecimal("1000.00")),
        )

        val item = listarPagina().items.single().items.single()

        assertThat(item.cuotaQuantum).isEqualTo("1000.00")
        assertThat(item.cuotaTotal).isEqualTo("1500.00")
    }

    /**
     * D62: basta con que UN item no aporte su cuota para que la oportunidad no
     * publique NINGUNO de los tres totales, aunque el resto de items si la tengan.
     * Una cuota parcial se lee como "esto es lo que paga al mes" y subestimaria
     * el pago en silencio.
     */
    @Test
    fun `un item sin cuota calculable deja sus dos campos y los TRES totales en null`() {
        stubPagina(
            ids = listOf(100L),
            items =
                listOf(
                    itemDatos(id = 500, idOportunidad = 100, cantidad = 2, cuotaFinanciadora = "500.00"),
                    itemDatos(id = 501, idOportunidad = 100, cantidad = 1, cuotaFinanciadora = "937.50"),
                ),
            // El 501 no esta en el mapa: `simulaciones` no pudo calcular su cuota (D55).
            cuotas = mapOf(500L to BigDecimal("1000.00")),
        )

        val dto = listarPagina().items.single()

        assertThat(dto.items.single { it.id == 501L }.cuotaQuantum).isNull()
        assertThat(dto.items.single { it.id == 501L }.cuotaTotal).isNull()
        // El otro item SI tiene la suya y aun asi los totales no se publican.
        assertThat(dto.items.single { it.id == 500L }.cuotaQuantum).isEqualTo("1000.00")
        assertThat(dto.cuotaQuantumTotal).isNull()
        assertThat(dto.cuotaTotal).isNull()
        assertThat(dto.cuotaDiariaTotal).isNull()
    }

    @Test
    fun `un item sin cantidad deja los TRES totales en null aunque tenga cuota`() {
        stubPagina(
            ids = listOf(100L),
            items =
                listOf(
                    itemDatos(id = 500, idOportunidad = 100, cantidad = 2, cuotaFinanciadora = "500.00"),
                    itemDatos(id = 501, idOportunidad = 100, cantidad = null, cuotaFinanciadora = "937.50"),
                ),
            cuotas = mapOf(500L to BigDecimal("1000.00"), 501L to BigDecimal("2000.00")),
        )

        val dto = listarPagina().items.single()

        // El item incompleto conserva su cuota: lo que no se puede es agregarla.
        assertThat(dto.items.single { it.id == 501L }.cuotaQuantum).isEqualTo("2000.00")
        assertThat(dto.cuotaQuantumTotal).isNull()
        assertThat(dto.cuotaTotal).isNull()
        assertThat(dto.cuotaDiariaTotal).isNull()
    }

    /**
     * Aritmetica de §6.2 verificada a mano:
     *
     *   item A: cuotaQuantum 1000, cuotaFinanciadora  500.00, cantidad 2
     *   item B: cuotaQuantum 2000, cuotaFinanciadora  937.50, cantidad 1
     *
     *   cuotaQuantumTotal = 1000 x 2 + 2000 x 1                  = 4000.00
     *   cuotaTotal        = (1000+500) x 2 + (2000+937.50) x 1
     *                     = 3000.00 + 2937.50                    = 5937.50
     *   cuotaDiariaTotal  = 5937.50 / 22 = 269.886363...         = 269.89 (HALF_UP)
     */
    @Test
    fun `dos items con cuota y cantidad multiplican por cantidad y suman`() {
        stubPagina(
            ids = listOf(100L),
            items =
                listOf(
                    itemDatos(id = 500, idOportunidad = 100, cantidad = 2, cuotaFinanciadora = "500.00"),
                    itemDatos(id = 501, idOportunidad = 100, cantidad = 1, cuotaFinanciadora = "937.50"),
                ),
            cuotas = mapOf(500L to BigDecimal("1000.00"), 501L to BigDecimal("2000.00")),
        )

        val dto = listarPagina().items.single()

        assertThat(dto.items.single { it.id == 500L }.cuotaTotal).isEqualTo("1500.00")
        assertThat(dto.items.single { it.id == 501L }.cuotaTotal).isEqualTo("2937.50")
        assertThat(dto.cuotaQuantumTotal).isEqualTo("4000.00")
        assertThat(dto.cuotaTotal).isEqualTo("5937.50")
    }

    /** El divisor es la constante 22 de `SimulacionService`, no el de ninguna simulacion (D62). */
    @Test
    fun `cuotaDiariaTotal es cuotaTotal dividido entre los 22 dias trabajados`() {
        stubPagina(
            ids = listOf(100L),
            items =
                listOf(
                    itemDatos(id = 500, idOportunidad = 100, cantidad = 2, cuotaFinanciadora = "500.00"),
                    itemDatos(id = 501, idOportunidad = 100, cantidad = 1, cuotaFinanciadora = "937.50"),
                ),
            cuotas = mapOf(500L to BigDecimal("1000.00"), 501L to BigDecimal("2000.00")),
        )

        val dto = listarPagina().items.single()

        assertThat(SimulacionService.DIAS_TRABAJADOS_POR_DEFECTO).isEqualTo(22)
        // cuotaTotal 5937.50 / 22 = 269.886363... -> 269.89
        assertThat(dto.cuotaDiariaTotal).isEqualTo("269.89")
    }

    /** Nunca una llamada por oportunidad ni por item: una sola para toda la pagina (D56). */
    @Test
    fun `cuotaQuantumPorItems se llama una sola vez para toda la pagina`() {
        stubPagina(
            ids = listOf(100L, 101L),
            items =
                listOf(
                    itemDatos(id = 500, idOportunidad = 100, cantidad = 2, cuotaFinanciadora = "500.00"),
                    itemDatos(id = 501, idOportunidad = 100, cantidad = 1, cuotaFinanciadora = "937.50"),
                    itemDatos(id = 600, idOportunidad = 101, cantidad = 3, cuotaFinanciadora = "500.00"),
                    itemDatos(id = 601, idOportunidad = 101, cantidad = 1, cuotaFinanciadora = "937.50"),
                ),
            cuotas =
                mapOf(
                    500L to BigDecimal("1000.00"),
                    501L to BigDecimal("2000.00"),
                    600L to BigDecimal("1000.00"),
                    601L to BigDecimal("2000.00"),
                ),
        )

        assertThat(listarPagina().items).hasSize(2)

        verify(exactly = 1) { simulacionService.cuotaQuantumPorItems(any()) }
        // Y esa unica llamada llevo los cuatro items de las dos oportunidades.
        verify(exactly = 1) {
            simulacionService.cuotaQuantumPorItems(
                match { enviados -> enviados.map { it.idItem }.toSet() == setOf(500L, 501L, 600L, 601L) },
            )
        }
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
