package pe.quantum.crm.domain.simulaciones

import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import io.mockk.verifyOrder
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.data.domain.PageImpl
import org.springframework.data.domain.PageRequest
import org.springframework.data.jpa.domain.Specification
import pe.quantum.crm.domain.empresas.EmpresaService
import pe.quantum.crm.domain.empresas.dto.EmpresaResumen
import pe.quantum.crm.domain.modelos.ModeloService
import pe.quantum.crm.domain.modelos.dto.ModeloResumen
import pe.quantum.crm.domain.oportunidades.OportunidadItemService
import pe.quantum.crm.domain.oportunidades.dto.OportunidadItemParaSimulacion
import pe.quantum.crm.domain.simulaciones.dto.ActualizarSimulacionRequest
import pe.quantum.crm.domain.simulaciones.dto.BifurcarSimulacionRequest
import pe.quantum.crm.domain.simulaciones.dto.CrearSimulacionRequest
import pe.quantum.crm.domain.simulaciones.dto.SimulacionFiltros
import pe.quantum.crm.shared.enums.ModoSimulacion
import pe.quantum.crm.shared.enums.TipoEventoSimulacion
import pe.quantum.crm.shared.exception.ConflictoException
import pe.quantum.crm.shared.exception.NoEncontradoException
import pe.quantum.crm.shared.exception.PermisoInsuficienteException
import pe.quantum.crm.shared.exception.ValidacionException
import pe.quantum.crm.shared.security.UsuarioActual
import java.math.BigDecimal
import java.time.LocalDateTime
import java.util.Optional

/**
 * `SimulacionServiceImpl.crear` (tarea D9 de plan-10-dominio-crud-tareas.md).
 *
 * Los dos casos dorados de `reglas_simulaciones.md` §3.6 se verifican AQUI
 * ademas de en el test del motor: lo que se comprueba no es la formula (eso ya
 * lo cubre el test del motor) sino que el Service persista exactamente la
 * `cuota_final` que el motor devuelve, sin redondeos ni conversiones propias por
 * el camino.
 *
 * Acumula ademas los tests de D10 y D11: cada tarea del plan-10 exige
 * "añadelos al final del archivo", asi que partirlo en varias clases queda
 * fuera del alcance de esas tareas.
 */
@Suppress("LargeClass")
class SimulacionServiceImplTest {
    private val simulacionRepository = mockk<SimulacionRepository>()
    private val simulacionLogRepository = mockk<SimulacionLogRepository>()
    private val oportunidadItemService = mockk<OportunidadItemService>()
    private val modeloService = mockk<ModeloService>()
    private val empresaService = mockk<EmpresaService>()
    private val service =
        SimulacionServiceImpl(
            simulacionRepository,
            simulacionLogRepository,
            SimulacionPermisos(),
            oportunidadItemService,
            modeloService,
            empresaService,
        )

    private val admin = UsuarioActual(id = 5, rol = "admin")

    private val guardada = slot<Simulacion>()
    private val logGuardado = slot<SimulacionLog>()

    private companion object {
        const val ID_SIMULACION = 900L
        const val ID_ITEM = 77L
        const val ID_OPORTUNIDAD = 100L
        const val ID_EMPRESA = 10L
        const val ID_MODELO = 7L
        const val ID_VENDEDOR = 1L
        const val ID_EVENTO_LOG = 4200L
    }

    @Suppress("LongParameterList") // Espejo de CrearSimulacionRequest: cada test cambia un campo distinto.
    private fun request(
        modo: String = "leasing",
        nombre: String? = null,
        idOportunidadItem: Long? = null,
        idModelo: Long? = null,
        precioVenta: String = "110000",
        descuento: BigDecimal? = BigDecimal("0"),
        cuotaInicial: String = "56000",
        plazoMeses: Int = 48,
        tea: String = "18",
        valorResidual: BigDecimal? = BigDecimal("0"),
        diasTrabajados: Int? = 22,
        comisionEstructuracion: BigDecimal? = BigDecimal("1180"),
    ) = CrearSimulacionRequest(
        modo = modo,
        nombre = nombre,
        idOportunidadItem = idOportunidadItem,
        idModelo = idModelo,
        precioVenta = BigDecimal(precioVenta),
        descuento = descuento,
        cuotaInicial = BigDecimal(cuotaInicial),
        plazoMeses = plazoMeses,
        tea = BigDecimal(tea),
        valorResidual = valorResidual,
        diasTrabajados = diasTrabajados,
        comisionEstructuracion = comisionEstructuracion,
    )

    private fun item(idVendedor: Long = ID_VENDEDOR) =
        OportunidadItemParaSimulacion(
            id = ID_ITEM,
            idOportunidad = ID_OPORTUNIDAD,
            idEmpresa = ID_EMPRESA,
            idVendedor = idVendedor,
            idModelo = ID_MODELO,
            cantidad = 2,
            precioVenta = BigDecimal("110000.00"),
            descuento = BigDecimal("0.00"),
            cuotaFinanciadora = BigDecimal("937.50"),
        )

    /** JPA asigna el id en el `save`; la entidad lo declara `val`, asi que se devuelve una copia. */
    private fun conId(
        original: Simulacion,
        id: Long,
    ) = Simulacion(
        id = id,
        modo = original.modo,
        nombre = original.nombre,
        idOportunidadItem = original.idOportunidadItem,
        idModelo = original.idModelo,
        idSimulacionOrigen = original.idSimulacionOrigen,
        precioVenta = original.precioVenta,
        descuento = original.descuento,
        cuotaInicial = original.cuotaInicial,
        plazoMeses = original.plazoMeses,
        tea = original.tea,
        valorResidual = original.valorResidual,
        diasTrabajados = original.diasTrabajados,
        comisionEstructuracion = original.comisionEstructuracion,
        cuotaFinal = original.cuotaFinal,
        esPrincipal = original.esPrincipal,
        createdAt = original.createdAt,
        createdBy = original.createdBy,
        updatedAt = original.updatedAt,
        updatedBy = original.updatedBy,
    )

    /** Las escrituras mas las lecturas que arma `toDto`. Sin ellas mockk falla por stub faltante. */
    private fun stubEscrituraYLectura() {
        every { simulacionRepository.save(capture(guardada)) } answers { conId(guardada.captured, ID_SIMULACION) }
        every { simulacionLogRepository.save(capture(logGuardado)) } answers { logGuardado.captured }
        every { simulacionRepository.correlativos(any()) } returns emptyList()
        every { empresaService.resumenPorIds(any()) } returns
            mapOf(ID_EMPRESA to EmpresaResumen(id = ID_EMPRESA, razonSocial = "Transportes Lima SAC", distrito = null))
        every { modeloService.resumenPorIds(any()) } returns
            mapOf(ID_MODELO to ModeloResumen(id = ID_MODELO, codigo = "MB-O500", precioBase = null))
        every { modeloService.resumen(any()) } returns ModeloResumen(id = ID_MODELO, codigo = "MB-O500", precioBase = null)
    }

    private fun stubItem(idVendedor: Long = ID_VENDEDOR) {
        every { oportunidadItemService.datosParaSimulacion(listOf(ID_ITEM)) } returns mapOf(ID_ITEM to item(idVendedor))
    }

    private fun stubDesmarcar() {
        every { simulacionRepository.desmarcarPrincipalDe(ID_ITEM) } returns 1
    }

    // ---------- Casos dorados §3.6 ----------

    @Test
    fun `caso dorado leasing de 3_6 persiste cuota_final 1548_86`() {
        stubEscrituraYLectura()

        val dto = service.crear(request(), admin)

        assertThat(guardada.captured.cuotaFinal).isEqualByComparingTo(BigDecimal("1548.86"))
        assertThat(dto.cuotaFinal).isEqualTo("1548.86")
    }

    @Test
    fun `caso dorado credito directo de 3_6 persiste cuota_final 697_67`() {
        stubEscrituraYLectura()

        val dto =
            service.crear(
                request(
                    modo = "credito_directo",
                    precioVenta = "90000",
                    cuotaInicial = "45000",
                    tea = "13",
                    valorResidual = BigDecimal("35000"),
                ),
                admin,
            )

        assertThat(guardada.captured.cuotaFinal).isEqualByComparingTo(BigDecimal("697.67"))
        assertThat(dto.cuotaFinal).isEqualTo("697.67")
    }

    // ---------- Validaciones §13 ----------

    @Test
    fun `cuota inicial mayor o igual al precio efectivo es 400 y no guarda nada`() {
        assertThatThrownBy {
            // PV_efectivo = 100000 x (1 - 50/100) = 50000; la inicial lo iguala.
            service.crear(
                request(precioVenta = "100000", descuento = BigDecimal("50"), cuotaInicial = "50000"),
                admin,
            )
        }.isInstanceOf(ValidacionException::class.java)
            .extracting { (it as ValidacionException).field }
            .isEqualTo("cuota_inicial")

        verify(exactly = 0) { simulacionRepository.save(any()) }
        verify(exactly = 0) { simulacionLogRepository.save(any()) }
    }

    @Test
    fun `valor residual mayor o igual al principal es 400 y no guarda nada`() {
        assertThatThrownBy {
            // Credito directo: Principal = 90000 - 45000 = 45000; el residual lo iguala.
            service.crear(
                request(
                    modo = "credito_directo",
                    precioVenta = "90000",
                    cuotaInicial = "45000",
                    tea = "13",
                    valorResidual = BigDecimal("45000"),
                ),
                admin,
            )
        }.isInstanceOf(ValidacionException::class.java)
            .extracting { (it as ValidacionException).field }
            .isEqualTo("valor_residual")

        verify(exactly = 0) { simulacionRepository.save(any()) }
        verify(exactly = 0) { simulacionLogRepository.save(any()) }
    }

    // ---------- es_principal (K14 / D38) ----------

    @Test
    fun `con item desmarca la principal anterior ANTES de insertar y la nueva nace principal`() {
        stubEscrituraYLectura()
        stubItem()
        stubDesmarcar()

        service.crear(request(idOportunidadItem = ID_ITEM), admin)

        verifyOrder {
            simulacionRepository.desmarcarPrincipalDe(ID_ITEM)
            simulacionRepository.save(any())
        }
        assertThat(guardada.captured.esPrincipal).isTrue()
        assertThat(guardada.captured.idOportunidadItem).isEqualTo(ID_ITEM)
    }

    @Test
    fun `sin item la simulacion nunca es principal y no se desmarca nada`() {
        stubEscrituraYLectura()

        service.crear(request(), admin)

        assertThat(guardada.captured.esPrincipal).isFalse()
        verify(exactly = 0) { simulacionRepository.desmarcarPrincipalDe(any()) }
    }

    // ---------- Evento `creada` (K15) ----------

    @Test
    fun `el evento creada lleva el snapshot completo que exige el CHECK`() {
        stubEscrituraYLectura()

        service.crear(request(), admin)

        val log = logGuardado.captured
        assertThat(log.tipoEvento).isEqualTo(TipoEventoSimulacion.creada)
        assertThat(log.idSimulacion).isEqualTo(ID_SIMULACION)
        assertThat(log.modo).isNotNull()
        assertThat(log.precioVenta).isNotNull()
        assertThat(log.cuotaInicial).isNotNull()
        assertThat(log.plazoMeses).isNotNull()
        assertThat(log.tea).isNotNull()
        assertThat(log.valorResidual).isNotNull()
        assertThat(log.cuotaFinal).isNotNull()
        // El resto del snapshot que pide el paso 11 de D9, aunque el CHECK no lo exija.
        assertThat(log.descuento).isNotNull()
        assertThat(log.diasTrabajados).isNotNull()
        assertThat(log.comisionEstructuracion).isNotNull()
        assertThat(log.createdBy).isEqualTo(admin.id)
    }

    @Test
    fun `el evento creada deriva id_oportunidad del item`() {
        stubEscrituraYLectura()
        stubItem()
        stubDesmarcar()

        service.crear(request(idOportunidadItem = ID_ITEM), admin)

        assertThat(logGuardado.captured.idOportunidadItem).isEqualTo(ID_ITEM)
        assertThat(logGuardado.captured.idOportunidad).isEqualTo(ID_OPORTUNIDAD)
    }

    @Test
    fun `sin item el evento creada lleva id_oportunidad en null`() {
        stubEscrituraYLectura()

        service.crear(request(), admin)

        assertThat(logGuardado.captured.idOportunidadItem).isNull()
        assertThat(logGuardado.captured.idOportunidad).isNull()
    }

    // ---------- Permisos (§10, D30/D31, regla 14) ----------

    @Test
    fun `un vendedor que apunta al item de otro recibe 404 y no guarda nada`() {
        stubItem(idVendedor = 999)

        assertThatThrownBy {
            service.crear(request(idOportunidadItem = ID_ITEM), UsuarioActual(id = ID_VENDEDOR, rol = "vendedor"))
        }.isInstanceOf(NoEncontradoException::class.java)

        verify(exactly = 0) { simulacionRepository.save(any()) }
        verify(exactly = 0) { simulacionRepository.desmarcarPrincipalDe(any()) }
    }

    @Test
    fun `el item inexistente es 404`() {
        every { oportunidadItemService.datosParaSimulacion(listOf(ID_ITEM)) } returns emptyMap()

        assertThatThrownBy {
            service.crear(request(idOportunidadItem = ID_ITEM), admin)
        }.isInstanceOf(NoEncontradoException::class.java)

        verify(exactly = 0) { simulacionRepository.save(any()) }
    }

    @Test
    fun `el jdv no tiene acceso al modulo y recibe 403 sin guardar nada`() {
        assertThatThrownBy {
            service.crear(request(), UsuarioActual(id = 3, rol = "jdv"))
        }.isInstanceOf(PermisoInsuficienteException::class.java)

        verify(exactly = 0) { simulacionRepository.save(any()) }
    }

    // ---------- Modo ----------

    @Test
    fun `un modo fuera del enum es 400 sobre el campo modo, no un 500`() {
        assertThatThrownBy {
            service.crear(request(modo = "leasing_raro"), admin)
        }.isInstanceOf(ValidacionException::class.java)
            .extracting { (it as ValidacionException).field }
            .isEqualTo("modo")

        verify(exactly = 0) { simulacionRepository.save(any()) }
    }

    // ---------- Defaults §6.1 ----------

    @Test
    fun `los campos opcionales en null caen a los defaults de columna de V43`() {
        stubEscrituraYLectura()

        service.crear(
            request(
                descuento = null,
                valorResidual = null,
                diasTrabajados = null,
                comisionEstructuracion = null,
            ),
            admin,
        )

        assertThat(guardada.captured.descuento).isEqualByComparingTo(BigDecimal.ZERO)
        assertThat(guardada.captured.valorResidual).isEqualByComparingTo(BigDecimal.ZERO)
        assertThat(guardada.captured.diasTrabajados).isEqualTo(22)
        assertThat(guardada.captured.comisionEstructuracion).isEqualByComparingTo(BigDecimal("1180"))
    }

    // ---------- Nombre (§8.1, CHECK chk_simulacion_nombre_no_vacio) ----------

    @Test
    fun `un nombre en blanco se persiste como null, nunca como cadena vacia`() {
        stubEscrituraYLectura()

        val dto = service.crear(request(nombre = "   "), admin)

        assertThat(guardada.captured.nombre).isNull()
        assertThat(dto.nombreEsManual).isFalse()
    }

    @Test
    fun `el nombre manual manda sobre el autogenerado`() {
        stubEscrituraYLectura()

        val dto = service.crear(request(nombre = "  Propuesta A  "), admin)

        assertThat(guardada.captured.nombre).isEqualTo("Propuesta A")
        assertThat(dto.nombre).isEqualTo("Propuesta A")
        assertThat(dto.nombreEsManual).isTrue()
    }

    @Test
    fun `sin nombre manual el DTO trae el autogenerado de 8_1 y no se persiste`() {
        stubEscrituraYLectura()
        stubItem()
        stubDesmarcar()

        val dto = service.crear(request(idOportunidadItem = ID_ITEM), admin)

        assertThat(guardada.captured.nombre).isNull()
        assertThat(dto.nombre).isEqualTo("Transportes Lima SAC · MB-O500 · Leasing · #1")
        assertThat(dto.nombreEsManual).isFalse()
    }

    @Test
    fun `sin item ni modelo el nombre autogenerado dice Sin enlazar`() {
        stubEscrituraYLectura()

        val dto = service.crear(request(), admin)

        assertThat(dto.nombre).isEqualTo("Sin enlazar · Leasing · #1")
    }

    // ---------- Herencia del modelo del item (paso 4 de D9) ----------

    @Test
    fun `sin idModelo explicito se hereda el del item`() {
        stubEscrituraYLectura()
        stubItem()
        stubDesmarcar()

        service.crear(request(idOportunidadItem = ID_ITEM), admin)

        assertThat(guardada.captured.idModelo).isEqualTo(ID_MODELO)
        verify { modeloService.resumen(ID_MODELO) }
    }

    @Test
    fun `un modelo inexistente es 404 y no guarda nada`() {
        every { modeloService.resumen(ID_MODELO) } throws NoEncontradoException("El modelo no existe")

        assertThatThrownBy {
            service.crear(request(idModelo = ID_MODELO), admin)
        }.isInstanceOf(NoEncontradoException::class.java)

        verify(exactly = 0) { simulacionRepository.save(any()) }
    }

    @Test
    fun `la simulacion se crea con el usuario actual como autor`() {
        stubEscrituraYLectura()

        service.crear(request(), admin)

        assertThat(guardada.captured.createdBy).isEqualTo(admin.id)
        assertThat(guardada.captured.updatedBy).isEqualTo(admin.id)
        assertThat(guardada.captured.createdAt).isEqualTo(guardada.captured.updatedAt)
    }

    @Test
    fun `el analista tiene acceso total al modulo pese a ser rol de apoyo en oportunidades`() {
        stubEscrituraYLectura()

        val dto = service.crear(request(), UsuarioActual(id = 8, rol = "analista"))

        assertThat(dto.id).isEqualTo(ID_SIMULACION)
        assertThat(guardada.captured.createdBy).isEqualTo(8)
    }

    // ========================================================================
    // D10 — detalle y listar
    // ========================================================================

    private val analista = UsuarioActual(id = 8, rol = "analista")
    private val gerencia = UsuarioActual(id = 2, rol = "gerencia")
    private val vendedor = UsuarioActual(id = ID_VENDEDOR, rol = "vendedor")

    /** Entidad ya persistida, para tests de `detalle`/`listar` (no pasan por `crear`). */
    @Suppress("LongParameterList")
    private fun simulacionPersistida(
        id: Long,
        createdBy: Long = admin.id,
        idOportunidadItem: Long? = null,
        idModelo: Long? = null,
        nombre: String? = null,
        modo: ModoSimulacion = ModoSimulacion.leasing,
    ) = Simulacion(
        id = id,
        modo = modo,
        nombre = nombre,
        idOportunidadItem = idOportunidadItem,
        idModelo = idModelo,
        precioVenta = BigDecimal("110000.00"),
        descuento = BigDecimal.ZERO,
        cuotaInicial = BigDecimal("56000.00"),
        plazoMeses = 48,
        tea = BigDecimal("18.00"),
        valorResidual = BigDecimal.ZERO,
        diasTrabajados = 22,
        comisionEstructuracion = BigDecimal("1180"),
        cuotaFinal = BigDecimal("1548.86"),
        esPrincipal = idOportunidadItem != null,
        createdAt = LocalDateTime.now(),
        createdBy = createdBy,
        updatedAt = LocalDateTime.now(),
        updatedBy = createdBy,
    )

    // ---------- detalle ----------

    @Test
    fun `detalle de una simulacion propia sin item devuelve el DTO`() {
        val propia = simulacionPersistida(id = ID_SIMULACION, createdBy = ID_VENDEDOR)
        every { simulacionRepository.findById(ID_SIMULACION) } returns Optional.of(propia)
        every { simulacionRepository.correlativos(listOf(ID_SIMULACION)) } returns emptyList()

        val dto = service.detalle(ID_SIMULACION, vendedor)

        assertThat(dto.id).isEqualTo(ID_SIMULACION)
        assertThat(dto.nombre).isEqualTo("Sin enlazar · Leasing · #1")
        verify(exactly = 0) { oportunidadItemService.datosParaSimulacion(any()) }
    }

    @Test
    fun `detalle de una ajena con vendedor que no es creador ni vendedor del item es 404`() {
        val ajena = simulacionPersistida(id = ID_SIMULACION, createdBy = 999, idOportunidadItem = ID_ITEM)
        every { simulacionRepository.findById(ID_SIMULACION) } returns Optional.of(ajena)
        stubItem(idVendedor = 998)

        assertThatThrownBy {
            service.detalle(ID_SIMULACION, vendedor)
        }.isInstanceOf(NoEncontradoException::class.java)
    }

    @Test
    fun `detalle con analista sobre una ajena funciona por acceso total al modulo K12`() {
        val ajena = simulacionPersistida(id = ID_SIMULACION, createdBy = 999)
        every { simulacionRepository.findById(ID_SIMULACION) } returns Optional.of(ajena)
        every { simulacionRepository.correlativos(listOf(ID_SIMULACION)) } returns emptyList()

        val dto = service.detalle(ID_SIMULACION, analista)

        assertThat(dto.id).isEqualTo(ID_SIMULACION)
    }

    @Test
    fun `detalle de una simulacion inexistente es 404`() {
        every { simulacionRepository.findById(ID_SIMULACION) } returns Optional.empty()

        assertThatThrownBy {
            service.detalle(ID_SIMULACION, admin)
        }.isInstanceOf(NoEncontradoException::class.java)
    }

    // ---------- listar: permisos ----------

    @Test
    fun `listar con vendedor es 403`() {
        assertThatThrownBy {
            service.listar(SimulacionFiltros(), vendedor, null, null, null, null)
        }.isInstanceOf(PermisoInsuficienteException::class.java)

        verify(exactly = 0) { simulacionRepository.findAll(any<Specification<Simulacion>>(), any<PageRequest>()) }
    }

    @Test
    fun `listar con analista devuelve resultados`() {
        val fila = simulacionPersistida(id = ID_SIMULACION)
        every { simulacionRepository.findAll(any<Specification<Simulacion>>(), any<PageRequest>()) } returns
            PageImpl(listOf(fila), PageRequest.of(0, 20), 1)
        every { simulacionRepository.correlativos(listOf(ID_SIMULACION)) } returns emptyList()

        val resultado = service.listar(SimulacionFiltros(), analista, null, null, null, null)

        assertThat(resultado.items).hasSize(1)
        assertThat(resultado.items.first().id).isEqualTo(ID_SIMULACION)
        assertThat(resultado.meta.total).isEqualTo(1L)
    }

    @Test
    fun `listar no aplica filtro de visibilidad por vendedor y devuelve simulaciones de dos duenos distintos`() {
        val simA = simulacionPersistida(id = 901L, createdBy = 5)
        val simB = simulacionPersistida(id = 902L, createdBy = 6)
        every { simulacionRepository.findAll(any<Specification<Simulacion>>(), any<PageRequest>()) } returns
            PageImpl(listOf(simA, simB), PageRequest.of(0, 20), 2)
        every { simulacionRepository.correlativos(listOf(901L, 902L)) } returns emptyList()

        val resultado = service.listar(SimulacionFiltros(), gerencia, null, null, null, null)

        assertThat(resultado.items.map { it.id }).containsExactlyInAnyOrder(901L, 902L)
    }

    @Test
    fun `listar con modo invalido en el filtro es 400`() {
        assertThatThrownBy {
            service.listar(SimulacionFiltros(modo = "leasing_raro"), analista, null, null, null, null)
        }.isInstanceOf(ValidacionException::class.java)
            .extracting { (it as ValidacionException).field }
            .isEqualTo("modo")

        verify(exactly = 0) { simulacionRepository.findAll(any<Specification<Simulacion>>(), any<PageRequest>()) }
    }

    // ---------- listar: ensamblado en lotes, sin N+1 ----------

    @Test
    fun `listar ensambla en lotes y llama correlativos exactamente una vez para toda la pagina`() {
        val simA = simulacionPersistida(id = 901L)
        val simB = simulacionPersistida(id = 902L)
        val simC = simulacionPersistida(id = 903L)
        every { simulacionRepository.findAll(any<Specification<Simulacion>>(), any<PageRequest>()) } returns
            PageImpl(listOf(simA, simB, simC), PageRequest.of(0, 20), 3)
        every { simulacionRepository.correlativos(listOf(901L, 902L, 903L)) } returns emptyList()

        service.listar(SimulacionFiltros(), gerencia, null, null, null, null)

        verify(exactly = 1) { simulacionRepository.correlativos(any()) }
    }

    @Test
    fun `listar con nombre manual gana sobre el autogenerado`() {
        val fila = simulacionPersistida(id = ID_SIMULACION, nombre = "Propuesta A")
        every { simulacionRepository.findAll(any<Specification<Simulacion>>(), any<PageRequest>()) } returns
            PageImpl(listOf(fila), PageRequest.of(0, 20), 1)

        val resultado = service.listar(SimulacionFiltros(), gerencia, null, null, null, null)

        assertThat(resultado.items.first().nombre).isEqualTo("Propuesta A")
        assertThat(resultado.items.first().nombreEsManual).isTrue()
        // Nombre manual pegajoso: no hace falta correlativo ni empresa (§8.1).
        verify(exactly = 0) { simulacionRepository.correlativos(any()) }
    }

    @Test
    fun `listar con nombre null se autogenera y nombreEsManual es false`() {
        val fila = simulacionPersistida(id = ID_SIMULACION, nombre = null)
        every { simulacionRepository.findAll(any<Specification<Simulacion>>(), any<PageRequest>()) } returns
            PageImpl(listOf(fila), PageRequest.of(0, 20), 1)
        every { simulacionRepository.correlativos(listOf(ID_SIMULACION)) } returns emptyList()

        val resultado = service.listar(SimulacionFiltros(), gerencia, null, null, null, null)

        assertThat(resultado.items.first().nombre).isEqualTo("Sin enlazar · Leasing · #1")
        assertThat(resultado.items.first().nombreEsManual).isFalse()
    }

    // ========================================================================
    // D11 — actualizar (PATCH parcial + evento `editada`)
    // ========================================================================

    /**
     * `save` mutan la MISMA instancia (JPA actualiza la fila cargada, no crea
     * una nueva), asi que `slot.captured` es literalmente `original`: cualquier
     * valor que haya que comparar como "sin cambios" debe copiarse ANTES de
     * llamar a `actualizar`, no leerse de la entidad despues.
     */
    private fun stubActualizar() {
        every { simulacionRepository.save(capture(guardada)) } answers { guardada.captured }
        every { simulacionLogRepository.save(capture(logGuardado)) } answers { logGuardado.captured }
    }

    @Test
    fun `actualizar cambiando solo tea recalcula cuota_final y no toca el resto de los campos`() {
        val original = simulacionPersistida(id = ID_SIMULACION, createdBy = admin.id, nombre = "Nombre Original")
        val cuotaFinalOriginal = original.cuotaFinal
        val precioVentaOriginal = original.precioVenta
        val descuentoOriginal = original.descuento
        val cuotaInicialOriginal = original.cuotaInicial
        val plazoMesesOriginal = original.plazoMeses
        val valorResidualOriginal = original.valorResidual
        val diasTrabajadosOriginal = original.diasTrabajados
        val comisionEstructuracionOriginal = original.comisionEstructuracion
        every { simulacionRepository.findById(ID_SIMULACION) } returns Optional.of(original)
        stubActualizar()

        val dto = service.actualizar(ID_SIMULACION, ActualizarSimulacionRequest(tea = BigDecimal("22")), admin)

        assertThat(guardada.captured.tea).isEqualByComparingTo(BigDecimal("22"))
        assertThat(guardada.captured.cuotaFinal).isNotEqualByComparingTo(cuotaFinalOriginal)
        assertThat(dto.cuotaFinal).isNotEqualTo(cuotaFinalOriginal.toPlainString())
        // El resto de los campos, intacto.
        assertThat(guardada.captured.precioVenta).isEqualByComparingTo(precioVentaOriginal)
        assertThat(guardada.captured.descuento).isEqualByComparingTo(descuentoOriginal)
        assertThat(guardada.captured.cuotaInicial).isEqualByComparingTo(cuotaInicialOriginal)
        assertThat(guardada.captured.plazoMeses).isEqualTo(plazoMesesOriginal)
        assertThat(guardada.captured.valorResidual).isEqualByComparingTo(valorResidualOriginal)
        assertThat(guardada.captured.diasTrabajados).isEqualTo(diasTrabajadosOriginal)
        assertThat(guardada.captured.comisionEstructuracion).isEqualByComparingTo(comisionEstructuracionOriginal)
        assertThat(guardada.captured.nombre).isEqualTo("Nombre Original")
        assertThat(guardada.captured.idOportunidadItem).isNull()
        assertThat(guardada.captured.idModelo).isNull()
    }

    @Test
    fun `actualizar con patch vacio no cambia ningun campo de negocio pero recalcula cuota_final y registra editada`() {
        val original = simulacionPersistida(id = ID_SIMULACION, createdBy = admin.id, nombre = "Nombre Original")
        val cuotaFinalOriginal = original.cuotaFinal
        val precioVentaOriginal = original.precioVenta
        val cuotaInicialOriginal = original.cuotaInicial
        val teaOriginal = original.tea
        every { simulacionRepository.findById(ID_SIMULACION) } returns Optional.of(original)
        stubActualizar()

        service.actualizar(ID_SIMULACION, ActualizarSimulacionRequest(), admin)

        // Los inputs no cambiaron, asi que el motor devuelve la misma cuota_final.
        assertThat(guardada.captured.cuotaFinal).isEqualByComparingTo(cuotaFinalOriginal)
        assertThat(guardada.captured.precioVenta).isEqualByComparingTo(precioVentaOriginal)
        assertThat(guardada.captured.cuotaInicial).isEqualByComparingTo(cuotaInicialOriginal)
        assertThat(guardada.captured.tea).isEqualByComparingTo(teaOriginal)
        assertThat(guardada.captured.nombre).isEqualTo("Nombre Original")
        assertThat(logGuardado.captured.tipoEvento).isEqualTo(TipoEventoSimulacion.editada)
    }

    @Test
    fun `actualizar con modo distinto lanza ConflictoException MODO_INMUTABLE y no guarda nada`() {
        val original =
            simulacionPersistida(
                id = ID_SIMULACION,
                createdBy = admin.id,
                nombre = "Nombre Original",
                modo = ModoSimulacion.leasing,
            )
        every { simulacionRepository.findById(ID_SIMULACION) } returns Optional.of(original)

        assertThatThrownBy {
            service.actualizar(ID_SIMULACION, ActualizarSimulacionRequest(modo = "credito_directo"), admin)
        }.isInstanceOf(ConflictoException::class.java)
            .extracting { (it as ConflictoException).code }
            .isEqualTo("MODO_INMUTABLE")

        verify(exactly = 0) { simulacionRepository.save(any()) }
        verify(exactly = 0) { simulacionLogRepository.save(any()) }
    }

    @Test
    fun `actualizar con modo igual al actual no lanza y la edicion procede`() {
        val original =
            simulacionPersistida(
                id = ID_SIMULACION,
                createdBy = admin.id,
                nombre = "Nombre Original",
                modo = ModoSimulacion.leasing,
            )
        every { simulacionRepository.findById(ID_SIMULACION) } returns Optional.of(original)
        stubActualizar()

        val dto = service.actualizar(ID_SIMULACION, ActualizarSimulacionRequest(modo = "leasing"), admin)

        assertThat(dto.id).isEqualTo(ID_SIMULACION)
        verify(exactly = 1) { simulacionRepository.save(any()) }
        verify(exactly = 1) { simulacionLogRepository.save(any()) }
    }

    @Test
    fun `actualizar que rompe cuota_inicial menor que PV_efectivo tras la fusion es 400 y no guarda nada`() {
        // Base: precio_venta 110000, descuento 0 -> PV_efectivo 110000. La
        // cuota_inicial fusionada lo iguala.
        val original = simulacionPersistida(id = ID_SIMULACION, createdBy = admin.id, nombre = "Nombre Original")
        every { simulacionRepository.findById(ID_SIMULACION) } returns Optional.of(original)

        assertThatThrownBy {
            service.actualizar(ID_SIMULACION, ActualizarSimulacionRequest(cuotaInicial = BigDecimal("110000")), admin)
        }.isInstanceOf(ValidacionException::class.java)
            .extracting { (it as ValidacionException).field }
            .isEqualTo("cuota_inicial")

        verify(exactly = 0) { simulacionRepository.save(any()) }
        verify(exactly = 0) { simulacionLogRepository.save(any()) }
    }

    @Test
    fun `actualizar que rompe valor_residual menor que principal tras la fusion es 400 y no guarda nada`() {
        // Credito directo: Principal = PV_efectivo - cuota_inicial = 110000 - 56000 = 54000.
        // El valor_residual fusionado lo iguala.
        val original =
            simulacionPersistida(
                id = ID_SIMULACION,
                createdBy = admin.id,
                nombre = "Nombre Original",
                modo = ModoSimulacion.credito_directo,
            )
        every { simulacionRepository.findById(ID_SIMULACION) } returns Optional.of(original)

        assertThatThrownBy {
            service.actualizar(ID_SIMULACION, ActualizarSimulacionRequest(valorResidual = BigDecimal("54000")), admin)
        }.isInstanceOf(ValidacionException::class.java)
            .extracting { (it as ValidacionException).field }
            .isEqualTo("valor_residual")

        verify(exactly = 0) { simulacionRepository.save(any()) }
        verify(exactly = 0) { simulacionLogRepository.save(any()) }
    }

    @Test
    fun `el evento editada lleva el snapshot completo que exige el CHECK`() {
        val original = simulacionPersistida(id = ID_SIMULACION, createdBy = admin.id, nombre = "Nombre Original")
        every { simulacionRepository.findById(ID_SIMULACION) } returns Optional.of(original)
        stubActualizar()

        service.actualizar(ID_SIMULACION, ActualizarSimulacionRequest(tea = BigDecimal("22")), admin)

        val log = logGuardado.captured
        assertThat(log.tipoEvento).isEqualTo(TipoEventoSimulacion.editada)
        assertThat(log.idSimulacion).isEqualTo(ID_SIMULACION)
        assertThat(log.modo).isNotNull()
        assertThat(log.precioVenta).isNotNull()
        assertThat(log.cuotaInicial).isNotNull()
        assertThat(log.plazoMeses).isNotNull()
        assertThat(log.tea).isNotNull()
        assertThat(log.valorResidual).isNotNull()
        assertThat(log.cuotaFinal).isNotNull()
        assertThat(log.descuento).isNotNull()
        assertThat(log.diasTrabajados).isNotNull()
        assertThat(log.comisionEstructuracion).isNotNull()
        assertThat(log.createdBy).isEqualTo(admin.id)
    }

    @Test
    fun `un vendedor sobre una simulacion ajena recibe 404 y no guarda nada`() {
        val ajena = simulacionPersistida(id = ID_SIMULACION, createdBy = 999, nombre = "Nombre Original")
        every { simulacionRepository.findById(ID_SIMULACION) } returns Optional.of(ajena)

        assertThatThrownBy {
            service.actualizar(ID_SIMULACION, ActualizarSimulacionRequest(tea = BigDecimal("20")), vendedor)
        }.isInstanceOf(NoEncontradoException::class.java)

        verify(exactly = 0) { simulacionRepository.save(any()) }
        verify(exactly = 0) { simulacionLogRepository.save(any()) }
    }

    @Test
    fun `reenlazar a un item de otro vendedor siendo vendedor es 404 y no guarda nada`() {
        val propia = simulacionPersistida(id = ID_SIMULACION, createdBy = ID_VENDEDOR, nombre = "Nombre Original")
        every { simulacionRepository.findById(ID_SIMULACION) } returns Optional.of(propia)
        stubItem(idVendedor = 999)

        assertThatThrownBy {
            service.actualizar(ID_SIMULACION, ActualizarSimulacionRequest(idOportunidadItem = ID_ITEM), vendedor)
        }.isInstanceOf(NoEncontradoException::class.java)

        verify(exactly = 0) { simulacionRepository.save(any()) }
        verify(exactly = 0) { simulacionLogRepository.save(any()) }
    }

    @Test
    fun `reenlazar a otro item baja esPrincipal a false y no revienta el indice unico`() {
        // La simulacion nace principal de ID_ITEM (simulacionPersistida lo hace
        // por default cuando hay item). El item destino del reenlace es OTRO
        // item que bien podria ya tener su propia principal: si `actualizar`
        // dejara `esPrincipal = true` aqui, el UPDATE violaria
        // `uq_simulacion_principal` sin haber pasado por `desmarcarPrincipalDe`.
        val idItemNuevo = ID_ITEM + 1
        val original =
            simulacionPersistida(id = ID_SIMULACION, createdBy = admin.id, idOportunidadItem = ID_ITEM)
        every { simulacionRepository.findById(ID_SIMULACION) } returns Optional.of(original)
        stubItem() // itemDe(simulacion.idOportunidadItem) resuelve el item ACTUAL antes de reenlazar.
        every { oportunidadItemService.datosParaSimulacion(listOf(idItemNuevo)) } returns
            mapOf(idItemNuevo to item().copy(id = idItemNuevo))
        stubActualizar()
        // toDto autogenera el nombre (la simulacion no trae uno manual): necesita
        // el correlativo y la razon social de la empresa del item nuevo.
        every { simulacionRepository.correlativos(any()) } returns emptyList()
        every { empresaService.resumenPorIds(any()) } returns
            mapOf(ID_EMPRESA to EmpresaResumen(id = ID_EMPRESA, razonSocial = "Transportes Lima SAC", distrito = null))

        service.actualizar(ID_SIMULACION, ActualizarSimulacionRequest(idOportunidadItem = idItemNuevo), admin)

        assertThat(guardada.captured.idOportunidadItem).isEqualTo(idItemNuevo)
        assertThat(guardada.captured.esPrincipal).isFalse()
        // Nada que relevar: esta simulacion no se vuelve principal de nada por
        // el solo hecho de reenlazarse, asi que no hace falta desmarcar a nadie.
        verify(exactly = 0) { simulacionRepository.desmarcarPrincipalDe(any()) }
    }

    @Test
    fun `un nombre en blanco en el PATCH se persiste como null`() {
        val original = simulacionPersistida(id = ID_SIMULACION, createdBy = admin.id, nombre = "Nombre Original")
        every { simulacionRepository.findById(ID_SIMULACION) } returns Optional.of(original)
        stubActualizar()
        // El nombre queda null tras el PATCH -> toDto cae al autogenerado, que
        // consulta el correlativo (sin item, no consulta empresa ni modelo).
        every { simulacionRepository.correlativos(any()) } returns emptyList()

        service.actualizar(ID_SIMULACION, ActualizarSimulacionRequest(nombre = "   "), admin)

        assertThat(guardada.captured.nombre).isNull()
    }

    // ========================================================================
    // D12 — eliminar (hard delete + evento `eliminada`)
    // ========================================================================

    @Test
    fun `eliminar registra el evento eliminada ANTES del hard delete`() {
        val original = simulacionPersistida(id = ID_SIMULACION, createdBy = admin.id)
        every { simulacionRepository.findById(ID_SIMULACION) } returns Optional.of(original)
        every { simulacionLogRepository.save(capture(logGuardado)) } answers { logGuardado.captured }
        every { simulacionRepository.delete(original) } just Runs

        service.eliminar(ID_SIMULACION, admin)

        verifyOrder {
            simulacionLogRepository.save(any())
            simulacionRepository.delete(original)
        }
    }

    @Test
    fun `el evento eliminada lleva el snapshot completo que exige el CHECK`() {
        val original = simulacionPersistida(id = ID_SIMULACION, createdBy = admin.id)
        every { simulacionRepository.findById(ID_SIMULACION) } returns Optional.of(original)
        every { simulacionLogRepository.save(capture(logGuardado)) } answers { logGuardado.captured }
        every { simulacionRepository.delete(original) } just Runs

        service.eliminar(ID_SIMULACION, admin)

        val log = logGuardado.captured
        assertThat(log.tipoEvento).isEqualTo(TipoEventoSimulacion.eliminada)
        assertThat(log.idSimulacion).isEqualTo(ID_SIMULACION)
        assertThat(log.modo).isNotNull()
        assertThat(log.precioVenta).isNotNull()
        assertThat(log.cuotaInicial).isNotNull()
        assertThat(log.plazoMeses).isNotNull()
        assertThat(log.tea).isNotNull()
        assertThat(log.valorResidual).isNotNull()
        assertThat(log.cuotaFinal).isNotNull()
    }

    @Test
    fun `un vendedor sobre una simulacion ajena recibe 404 al eliminar y no borra nada`() {
        val ajena = simulacionPersistida(id = ID_SIMULACION, createdBy = 999)
        every { simulacionRepository.findById(ID_SIMULACION) } returns Optional.of(ajena)

        assertThatThrownBy {
            service.eliminar(ID_SIMULACION, vendedor)
        }.isInstanceOf(NoEncontradoException::class.java)

        verify(exactly = 0) { simulacionRepository.delete(any<Simulacion>()) }
        verify(exactly = 0) { simulacionLogRepository.save(any()) }
    }

    @Test
    fun `el analista puede eliminar una simulacion ajena por acceso total al modulo K12`() {
        val ajena = simulacionPersistida(id = ID_SIMULACION, createdBy = 999)
        every { simulacionRepository.findById(ID_SIMULACION) } returns Optional.of(ajena)
        every { simulacionLogRepository.save(capture(logGuardado)) } answers { logGuardado.captured }
        every { simulacionRepository.delete(ajena) } just Runs

        service.eliminar(ID_SIMULACION, analista)

        verify(exactly = 1) { simulacionRepository.delete(ajena) }
    }

    @Test
    fun `eliminar una simulacion inexistente lanza NoEncontradoException y no borra nada`() {
        every { simulacionRepository.findById(ID_SIMULACION) } returns Optional.empty()

        assertThatThrownBy {
            service.eliminar(ID_SIMULACION, admin)
        }.isInstanceOf(NoEncontradoException::class.java)

        verify(exactly = 0) { simulacionRepository.delete(any<Simulacion>()) }
        verify(exactly = 0) { simulacionLogRepository.save(any()) }
    }

    // ========================================================================
    // E4 — historial (ventana §7.2 + diff §7.1)
    // ========================================================================

    /**
     * Un evento de bitacora con snapshot completo. `tea` es el campo que los
     * tests de historial mueven para que el diff tenga algo que reportar.
     */
    private fun logDe(
        id: Long,
        momento: LocalDateTime,
        tea: String,
        tipoEvento: TipoEventoSimulacion = TipoEventoSimulacion.editada,
        createdBy: Long? = admin.id,
    ) = SimulacionLog(
        id = id,
        idSimulacion = ID_SIMULACION,
        tipoEvento = tipoEvento,
        modo = ModoSimulacion.leasing,
        precioVenta = BigDecimal("110000.00"),
        descuento = BigDecimal.ZERO,
        cuotaInicial = BigDecimal("56000.00"),
        plazoMeses = 48,
        tea = BigDecimal(tea),
        valorResidual = BigDecimal.ZERO,
        diasTrabajados = 22,
        comisionEstructuracion = BigDecimal("1180"),
        cuotaFinal = BigDecimal("1548.86"),
        createdAt = momento,
        createdBy = createdBy,
    )

    /** La simulacion existe y es propia del usuario que consulta. */
    private fun stubSimulacionParaHistorial(createdBy: Long = admin.id) {
        every { simulacionRepository.findById(ID_SIMULACION) } returns
            Optional.of(simulacionPersistida(id = ID_SIMULACION, createdBy = createdBy))
    }

    @Test
    fun `el diff del evento mas antiguo de la ventana se calcula contra su predecesor fuera de ella K23`() {
        stubSimulacionParaHistorial()
        val ahora = LocalDateTime.now()
        // La query devuelve descendente: el mas reciente primero.
        every { simulacionLogRepository.historial(ID_SIMULACION) } returns
            listOf(
                logDe(id = 3, momento = ahora.minusDays(1), tea = "24.00"),
                logDe(id = 2, momento = ahora.minusDays(3), tea = "22.00"),
                logDe(id = 1, momento = ahora.minusDays(6), tea = "20.00"),
            )
        // Fuera de los 7 dias, pero es el predecesor real del mas antiguo.
        every { simulacionLogRepository.eventoAnteriorA(ID_SIMULACION, any(), any()) } returns
            logDe(id = 0, momento = ahora.minusDays(30), tea = "18.00", tipoEvento = TipoEventoSimulacion.creada)

        val historial = service.historial(ID_SIMULACION, admin)

        val masAntiguo = historial.last()
        assertThat(masAntiguo.idEventoLog).isEqualTo(1)
        assertThat(masAntiguo.diff).isNotEmpty()
        assertThat(masAntiguo.diff.single().campo).isEqualTo("tea")
        assertThat(masAntiguo.diff.single().valorAnterior).isEqualTo("18.00")
        assertThat(masAntiguo.diff.single().valorNuevo).isEqualTo("20.00")
        // El par pasado tiene que ser el del evento MAS ANTIGUO (id=1, hace 6
        // dias), no el del mas reciente: una regresion que invirtiera cual fila
        // de la lista se usa seguiria dando un diff no vacio y este assert es
        // el unico que la distingue.
        verify(exactly = 1) {
            simulacionLogRepository.eventoAnteriorA(ID_SIMULACION, ahora.minusDays(6), 1L)
        }
    }

    @Test
    fun `un unico evento sin predecesor tiene diff vacio`() {
        stubSimulacionParaHistorial()
        every { simulacionLogRepository.historial(ID_SIMULACION) } returns
            listOf(logDe(id = 1, momento = LocalDateTime.now(), tea = "18.00", tipoEvento = TipoEventoSimulacion.creada))
        every { simulacionLogRepository.eventoAnteriorA(ID_SIMULACION, any(), any()) } returns null

        val historial = service.historial(ID_SIMULACION, admin)

        assertThat(historial).hasSize(1)
        assertThat(historial.single().idEventoLog).isEqualTo(1)
        assertThat(historial.single().tipoEvento).isEqualTo("creada")
        assertThat(historial.single().createdBy).isEqualTo(admin.id)
        assertThat(historial.single().diff).isEmpty()
    }

    @Test
    fun `eventoAnteriorA se consulta una sola vez para toda la peticion, no una por evento`() {
        stubSimulacionParaHistorial()
        val ahora = LocalDateTime.now()
        every { simulacionLogRepository.historial(ID_SIMULACION) } returns
            listOf(
                logDe(id = 3, momento = ahora.minusDays(1), tea = "24.00"),
                logDe(id = 2, momento = ahora.minusDays(3), tea = "22.00"),
                logDe(id = 1, momento = ahora.minusDays(6), tea = "20.00"),
            )
        every { simulacionLogRepository.eventoAnteriorA(ID_SIMULACION, any(), any()) } returns null

        service.historial(ID_SIMULACION, admin)

        verify(exactly = 1) { simulacionLogRepository.eventoAnteriorA(any(), any(), any()) }
        verify(exactly = 1) { simulacionLogRepository.historial(ID_SIMULACION) }
    }

    @Test
    fun `el historial sale en el mismo orden descendente que devolvio la query`() {
        stubSimulacionParaHistorial()
        val ahora = LocalDateTime.now()
        every { simulacionLogRepository.historial(ID_SIMULACION) } returns
            listOf(
                logDe(id = 3, momento = ahora.minusDays(1), tea = "24.00"),
                logDe(id = 2, momento = ahora.minusDays(3), tea = "22.00"),
                logDe(id = 1, momento = ahora.minusDays(6), tea = "20.00"),
            )
        every { simulacionLogRepository.eventoAnteriorA(ID_SIMULACION, any(), any()) } returns null

        val historial = service.historial(ID_SIMULACION, admin)

        assertThat(historial.map { it.idEventoLog }).containsExactly(3L, 2L, 1L)
        assertThat(historial[0].createdAt).isAfter(historial[1].createdAt)
        assertThat(historial[1].createdAt).isAfter(historial[2].createdAt)
        // Cada uno se diffea contra el que le sigue en la lista descendente.
        assertThat(historial[0].diff.single().valorAnterior).isEqualTo("22.00")
        assertThat(historial[1].diff.single().valorAnterior).isEqualTo("20.00")
        assertThat(historial[2].diff).isEmpty()
    }

    @Test
    fun `un vendedor sobre una simulacion ajena recibe 404 al pedir el historial`() {
        every { simulacionRepository.findById(ID_SIMULACION) } returns
            Optional.of(simulacionPersistida(id = ID_SIMULACION, createdBy = 999))

        assertThatThrownBy {
            service.historial(ID_SIMULACION, vendedor)
        }.isInstanceOf(NoEncontradoException::class.java)

        verify(exactly = 0) { simulacionLogRepository.historial(any()) }
        verify(exactly = 0) { simulacionLogRepository.eventoAnteriorA(any(), any(), any()) }
    }

    @Test
    fun `el analista ve el historial de una simulacion ajena por acceso total al modulo K12`() {
        every { simulacionRepository.findById(ID_SIMULACION) } returns
            Optional.of(simulacionPersistida(id = ID_SIMULACION, createdBy = 999))
        every { simulacionLogRepository.historial(ID_SIMULACION) } returns
            listOf(logDe(id = 1, momento = LocalDateTime.now(), tea = "18.00", tipoEvento = TipoEventoSimulacion.creada))
        every { simulacionLogRepository.eventoAnteriorA(ID_SIMULACION, any(), any()) } returns null

        val historial = service.historial(ID_SIMULACION, analista)

        assertThat(historial).hasSize(1)
        assertThat(historial.single().idEventoLog).isEqualTo(1)
    }

    @Test
    fun `un historial vacio devuelve lista vacia sin consultar el evento anterior`() {
        stubSimulacionParaHistorial()
        every { simulacionLogRepository.historial(ID_SIMULACION) } returns emptyList()

        val historial = service.historial(ID_SIMULACION, admin)

        assertThat(historial).isEmpty()
        verify(exactly = 0) { simulacionLogRepository.eventoAnteriorA(any(), any(), any()) }
    }

    // ========================================================================
    // E5 — evento `enlazada_a_item` en el PATCH que reenlaza (D45, D47)
    // ========================================================================

    /**
     * El PATCH que reenlaza guarda DOS filas de log en la misma transaccion
     * (D45), asi que un `slot` unico no sirve: solo conservaria la ultima
     * captura. Se acumulan en una lista y cada test busca por `tipoEvento`.
     */
    private val logsE5 = mutableListOf<SimulacionLog>()

    private fun stubActualizarConVariosLogs() {
        every { simulacionRepository.save(capture(guardada)) } answers { guardada.captured }
        every { simulacionLogRepository.save(capture(logsE5)) } answers { logsE5.last() }
    }

    /** El item destino del reenlace, distinto del item actual [ID_ITEM]. */
    private fun stubItemNuevo(idItemNuevo: Long) {
        every { oportunidadItemService.datosParaSimulacion(listOf(idItemNuevo)) } returns
            mapOf(idItemNuevo to item().copy(id = idItemNuevo))
    }

    @Test
    fun `el PATCH que reenlaza de un item a otro registra enlazada_a_item ADEMAS del editada`() {
        val idItemNuevo = ID_ITEM + 1
        val original =
            simulacionPersistida(
                id = ID_SIMULACION,
                createdBy = admin.id,
                idOportunidadItem = ID_ITEM,
                nombre = "Nombre Original",
            )
        every { simulacionRepository.findById(ID_SIMULACION) } returns Optional.of(original)
        stubItem() // el item ACTUAL, que `itemDe` resuelve antes de reenlazar.
        stubItemNuevo(idItemNuevo)
        stubActualizarConVariosLogs()

        service.actualizar(ID_SIMULACION, ActualizarSimulacionRequest(idOportunidadItem = idItemNuevo), admin)

        verify(exactly = 2) { simulacionLogRepository.save(any()) }
        assertThat(logsE5.map { it.tipoEvento })
            .containsExactlyInAnyOrder(TipoEventoSimulacion.enlazada_a_item, TipoEventoSimulacion.editada)
    }

    @Test
    fun `el evento enlazada_a_item va sin snapshot y apunta al item NUEVO K24`() {
        val idItemNuevo = ID_ITEM + 1
        val original =
            simulacionPersistida(
                id = ID_SIMULACION,
                createdBy = admin.id,
                idOportunidadItem = ID_ITEM,
                nombre = "Nombre Original",
            )
        every { simulacionRepository.findById(ID_SIMULACION) } returns Optional.of(original)
        stubItem()
        stubItemNuevo(idItemNuevo)
        stubActualizarConVariosLogs()

        service.actualizar(ID_SIMULACION, ActualizarSimulacionRequest(idOportunidadItem = idItemNuevo), admin)

        val enlace = logsE5.single { it.tipoEvento == TipoEventoSimulacion.enlazada_a_item }
        assertThat(enlace.idSimulacion).isEqualTo(ID_SIMULACION)
        assertThat(enlace.idOportunidadItem).isEqualTo(idItemNuevo)
        assertThat(enlace.createdBy).isEqualTo(admin.id)
        // Snapshot minimo: el CHECK solo exige `id_oportunidad_item` para este tipo.
        assertThat(enlace.modo).isNull()
        assertThat(enlace.precioVenta).isNull()
        assertThat(enlace.cuotaInicial).isNull()
        assertThat(enlace.plazoMeses).isNull()
        assertThat(enlace.tea).isNull()
        assertThat(enlace.valorResidual).isNull()
        assertThat(enlace.cuotaFinal).isNull()
    }

    @Test
    fun `enlazar por primera vez una simulacion sin item tambien registra enlazada_a_item`() {
        val sinItem =
            simulacionPersistida(id = ID_SIMULACION, createdBy = admin.id, nombre = "Nombre Original")
        every { simulacionRepository.findById(ID_SIMULACION) } returns Optional.of(sinItem)
        stubItem() // aqui es el item DESTINO: `itemDe(null)` no consulta nada.
        stubActualizarConVariosLogs()

        service.actualizar(ID_SIMULACION, ActualizarSimulacionRequest(idOportunidadItem = ID_ITEM), admin)

        verify(exactly = 2) { simulacionLogRepository.save(any()) }
        val enlace = logsE5.single { it.tipoEvento == TipoEventoSimulacion.enlazada_a_item }
        assertThat(enlace.idOportunidadItem).isEqualTo(ID_ITEM)
    }

    @Test
    fun `el PATCH que no toca el item registra un unico evento editada`() {
        val original =
            simulacionPersistida(id = ID_SIMULACION, createdBy = admin.id, nombre = "Nombre Original")
        every { simulacionRepository.findById(ID_SIMULACION) } returns Optional.of(original)
        stubActualizarConVariosLogs()

        service.actualizar(ID_SIMULACION, ActualizarSimulacionRequest(tea = BigDecimal("22")), admin)

        verify(exactly = 1) { simulacionLogRepository.save(any()) }
        assertThat(logsE5.single().tipoEvento).isEqualTo(TipoEventoSimulacion.editada)
    }

    // ========================================================================
    // E6 - restaurar (7.2, D48)
    // ========================================================================

    /**
     * `restaurar` guarda DOS filas de log en la misma transaccion (el `editada`
     * del estado previo y el `restaurada`), asi que —igual que en E5— un `slot`
     * unico solo conservaria la ultima captura.
     */
    private val logsE6 = mutableListOf<SimulacionLog>()

    private fun stubRestaurar() {
        every { simulacionRepository.save(capture(guardada)) } answers { guardada.captured }
        every { simulacionLogRepository.save(capture(logsE6)) } answers { logsE6.last() }
        every { simulacionRepository.correlativos(any()) } returns emptyList()
        every { empresaService.resumenPorIds(any()) } returns
            mapOf(ID_EMPRESA to EmpresaResumen(id = ID_EMPRESA, razonSocial = "Transportes Lima SAC", distrito = null))
        every { modeloService.resumen(any()) } returns ModeloResumen(id = ID_MODELO, codigo = "MB-O500", precioBase = null)
    }

    /**
     * La version a restaurar. Por defecto son los parametros del caso dorado
     * leasing de §3.6, salvo `tea`, que es lo que cada test mueve para que la
     * restauracion tenga algo que cambiar.
     */
    @Suppress("LongParameterList") // Espejo del snapshot: cada test siembra un campo distinto.
    private fun versionDe(
        id: Long = ID_EVENTO_LOG,
        idSimulacion: Long = ID_SIMULACION,
        tipoEvento: TipoEventoSimulacion = TipoEventoSimulacion.editada,
        createdAt: LocalDateTime = LocalDateTime.now().minusDays(2),
        tea: String = "18",
        valorResidual: String = "0",
        cuotaFinal: String = "1548.86",
    ) = SimulacionLog(
        id = id,
        idSimulacion = idSimulacion,
        tipoEvento = tipoEvento,
        modo = ModoSimulacion.leasing,
        precioVenta = BigDecimal("110000"),
        descuento = BigDecimal.ZERO,
        cuotaInicial = BigDecimal("56000"),
        plazoMeses = 48,
        tea = BigDecimal(tea),
        valorResidual = BigDecimal(valorResidual),
        diasTrabajados = 22,
        comisionEstructuracion = BigDecimal("1180"),
        cuotaFinal = BigDecimal(cuotaFinal),
        createdAt = createdAt,
        createdBy = admin.id,
    )

    /** El evento `marcada_principal` NO lleva snapshot (K24): no hay nada que restaurar. */
    private fun versionSinSnapshot() =
        SimulacionLog(
            id = ID_EVENTO_LOG,
            idSimulacion = ID_SIMULACION,
            tipoEvento = TipoEventoSimulacion.marcada_principal,
            idOportunidadItem = ID_ITEM,
            createdAt = LocalDateTime.now().minusDays(1),
            createdBy = admin.id,
        )

    @Test
    fun `restaurar registra el editada del estado previo ANTES del save y el restaurada despues`() {
        val original = simulacionPersistida(id = ID_SIMULACION, createdBy = admin.id, nombre = "Nombre Original")
        original.tea = BigDecimal("30.00")
        every { simulacionRepository.findById(ID_SIMULACION) } returns Optional.of(original)
        every { simulacionLogRepository.findById(ID_EVENTO_LOG) } returns Optional.of(versionDe(tea = "18"))
        stubRestaurar()

        val dto = service.restaurar(ID_SIMULACION, ID_EVENTO_LOG, admin)

        verifyOrder {
            simulacionLogRepository.save(match { it.tipoEvento == TipoEventoSimulacion.editada })
            simulacionRepository.save(any())
            simulacionLogRepository.save(match { it.tipoEvento == TipoEventoSimulacion.restaurada })
        }
        // El `editada` congela el estado PREVIO; el `restaurada`, el posterior.
        val previo = logsE6.single { it.tipoEvento == TipoEventoSimulacion.editada }
        val posterior = logsE6.single { it.tipoEvento == TipoEventoSimulacion.restaurada }
        assertThat(previo.tea).isEqualByComparingTo(BigDecimal("30.00"))
        assertThat(posterior.tea).isEqualByComparingTo(BigDecimal("18"))
        assertThat(dto.tea).isEqualTo("18")
        // Ambos con snapshot completo: los campos que exige `chk_simulacion_log_snapshot` (K15).
        listOf(previo, posterior).forEach { evento ->
            assertThat(evento.modo).isNotNull()
            assertThat(evento.precioVenta).isNotNull()
            assertThat(evento.cuotaInicial).isNotNull()
            assertThat(evento.plazoMeses).isNotNull()
            assertThat(evento.tea).isNotNull()
            assertThat(evento.valorResidual).isNotNull()
            assertThat(evento.cuotaFinal).isNotNull()
            assertThat(evento.createdBy).isEqualTo(admin.id)
        }
    }

    @Test
    fun `restaurar recalcula cuota_final con el motor y NUNCA copia la del snapshot`() {
        val original = simulacionPersistida(id = ID_SIMULACION, createdBy = admin.id, nombre = "Nombre Original")
        original.tea = BigDecimal("30.00")
        original.cuotaFinal = BigDecimal("2000.00")
        every { simulacionRepository.findById(ID_SIMULACION) } returns Optional.of(original)
        // Parametros del caso dorado leasing de §3.6, pero con una `cuota_final`
        // imposible para ellos: la que dejaria una formula ya corregida (§7.2 paso 3).
        every { simulacionLogRepository.findById(ID_EVENTO_LOG) } returns
            Optional.of(versionDe(tea = "18", cuotaFinal = "1.00"))
        stubRestaurar()

        val dto = service.restaurar(ID_SIMULACION, ID_EVENTO_LOG, admin)

        assertThat(guardada.captured.cuotaFinal).isEqualByComparingTo(BigDecimal("1548.86"))
        assertThat(guardada.captured.cuotaFinal).isNotEqualByComparingTo(BigDecimal("1.00"))
        assertThat(dto.cuotaFinal).isEqualTo("1548.86")
        // Y el evento `restaurada` guarda la recalculada, no la del snapshot viejo.
        val posterior = logsE6.single { it.tipoEvento == TipoEventoSimulacion.restaurada }
        assertThat(posterior.cuotaFinal).isEqualByComparingTo(BigDecimal("1548.86"))
    }

    @Test
    fun `restaurar con el log de OTRA simulacion es 404 y no escribe nada`() {
        every { simulacionRepository.findById(ID_SIMULACION) } returns
            Optional.of(simulacionPersistida(id = ID_SIMULACION, createdBy = admin.id))
        every { simulacionLogRepository.findById(ID_EVENTO_LOG) } returns
            Optional.of(versionDe(idSimulacion = ID_SIMULACION + 1))

        assertThatThrownBy {
            service.restaurar(ID_SIMULACION, ID_EVENTO_LOG, admin)
        }.isInstanceOf(NoEncontradoException::class.java)

        verify(exactly = 0) { simulacionRepository.save(any()) }
        verify(exactly = 0) { simulacionLogRepository.save(any()) }
    }

    @Test
    fun `restaurar un evento marcada_principal es 404 porque no lleva snapshot`() {
        every { simulacionRepository.findById(ID_SIMULACION) } returns
            Optional.of(simulacionPersistida(id = ID_SIMULACION, createdBy = admin.id))
        every { simulacionLogRepository.findById(ID_EVENTO_LOG) } returns Optional.of(versionSinSnapshot())

        assertThatThrownBy {
            service.restaurar(ID_SIMULACION, ID_EVENTO_LOG, admin)
        }.isInstanceOf(NoEncontradoException::class.java)

        verify(exactly = 0) { simulacionRepository.save(any()) }
        verify(exactly = 0) { simulacionLogRepository.save(any()) }
    }

    @Test
    fun `restaurar una version de hace mas de 7 dias es 404`() {
        every { simulacionRepository.findById(ID_SIMULACION) } returns
            Optional.of(simulacionPersistida(id = ID_SIMULACION, createdBy = admin.id))
        every { simulacionLogRepository.findById(ID_EVENTO_LOG) } returns
            Optional.of(versionDe(createdAt = LocalDateTime.now().minusDays(8)))

        assertThatThrownBy {
            service.restaurar(ID_SIMULACION, ID_EVENTO_LOG, admin)
        }.isInstanceOf(NoEncontradoException::class.java)

        verify(exactly = 0) { simulacionRepository.save(any()) }
        verify(exactly = 0) { simulacionLogRepository.save(any()) }
    }

    @Test
    fun `restaurar no cambia el modo de la entidad aunque el log traiga otro`() {
        val original =
            simulacionPersistida(
                id = ID_SIMULACION,
                createdBy = admin.id,
                nombre = "Nombre Original",
                modo = ModoSimulacion.credito_directo,
            )
        original.tea = BigDecimal("30.00")
        every { simulacionRepository.findById(ID_SIMULACION) } returns Optional.of(original)
        // El snapshot dice `leasing`; la entidad es `credito_directo` y manda ella (§2).
        every { simulacionLogRepository.findById(ID_EVENTO_LOG) } returns Optional.of(versionDe(tea = "18"))
        stubRestaurar()

        val dto = service.restaurar(ID_SIMULACION, ID_EVENTO_LOG, admin)

        assertThat(guardada.captured.modo).isEqualTo(ModoSimulacion.credito_directo)
        assertThat(dto.modo).isEqualTo("credito_directo")
        // Credito directo: Principal = 110000 - 56000, distinto del leasing del snapshot.
        assertThat(guardada.captured.cuotaFinal).isNotEqualByComparingTo(BigDecimal("1548.86"))
    }

    @Test
    fun `restaurar no toca es_principal ni el item enlazado`() {
        val original =
            simulacionPersistida(
                id = ID_SIMULACION,
                createdBy = admin.id,
                idOportunidadItem = ID_ITEM,
                nombre = "Nombre Original",
            )
        original.tea = BigDecimal("30.00")
        assertThat(original.esPrincipal).isTrue()
        every { simulacionRepository.findById(ID_SIMULACION) } returns Optional.of(original)
        every { simulacionLogRepository.findById(ID_EVENTO_LOG) } returns Optional.of(versionDe(tea = "18"))
        stubItem()
        stubRestaurar()

        service.restaurar(ID_SIMULACION, ID_EVENTO_LOG, admin)

        assertThat(guardada.captured.esPrincipal).isTrue()
        assertThat(guardada.captured.idOportunidadItem).isEqualTo(ID_ITEM)
        verify(exactly = 0) { simulacionRepository.desmarcarPrincipalDe(any()) }
    }

    @Test
    fun `un snapshot que ya no pasa 13 es 400 y no guarda la entidad ni registra restaurada`() {
        val original = simulacionPersistida(id = ID_SIMULACION, createdBy = admin.id, nombre = "Nombre Original")
        every { simulacionRepository.findById(ID_SIMULACION) } returns Optional.of(original)
        // Leasing: Principal = (110000 - 56000) / 1.18 = 45762.71. El residual del
        // snapshot lo supera con la formula de HOY; en su momento era valido.
        every { simulacionLogRepository.findById(ID_EVENTO_LOG) } returns
            Optional.of(versionDe(valorResidual = "46000"))
        stubRestaurar()

        assertThatThrownBy {
            service.restaurar(ID_SIMULACION, ID_EVENTO_LOG, admin)
        }.isInstanceOf(ValidacionException::class.java)
            .extracting { (it as ValidacionException).field }
            .isEqualTo("valor_residual")

        verify(exactly = 0) { simulacionRepository.save(any()) }
        // El `editada` del estado previo va ANTES de validar (D48 paso 3, orden
        // literal), asi que ya se registro; lo que no ocurre es el `restaurada`.
        // En produccion el rollback de la transaccion se lleva ambos.
        assertThat(logsE6.map { it.tipoEvento }).containsExactly(TipoEventoSimulacion.editada)
    }

    @Test
    fun `un vendedor no puede restaurar una simulacion ajena y no escribe nada`() {
        every { simulacionRepository.findById(ID_SIMULACION) } returns
            Optional.of(simulacionPersistida(id = ID_SIMULACION, createdBy = 999))

        assertThatThrownBy {
            service.restaurar(ID_SIMULACION, ID_EVENTO_LOG, vendedor)
        }.isInstanceOf(NoEncontradoException::class.java)

        verify(exactly = 0) { simulacionLogRepository.findById(any()) }
        verify(exactly = 0) { simulacionRepository.save(any()) }
        verify(exactly = 0) { simulacionLogRepository.save(any()) }
    }

    // ========================================================================
    // E7 - marcarPrincipal (D46, K28)
    // ========================================================================

    @Test
    fun `marcarPrincipal sin item enlazado es 409 SIMULACION_SIN_ITEM y no escribe nada`() {
        val sinItem = simulacionPersistida(id = ID_SIMULACION, createdBy = admin.id)
        every { simulacionRepository.findById(ID_SIMULACION) } returns Optional.of(sinItem)

        assertThatThrownBy {
            service.marcarPrincipal(ID_SIMULACION, admin)
        }.isInstanceOf(ConflictoException::class.java)
            .extracting { (it as ConflictoException).code }
            .isEqualTo("SIMULACION_SIN_ITEM")

        verify(exactly = 0) { simulacionRepository.save(any()) }
        verify(exactly = 0) { simulacionRepository.desmarcarPrincipalDe(any()) }
        verify(exactly = 0) { simulacionLogRepository.save(any()) }
    }

    @Test
    fun `marcarPrincipal sobre una simulacion ya principal es un no-op exitoso`() {
        val yaPrincipal =
            simulacionPersistida(
                id = ID_SIMULACION,
                createdBy = admin.id,
                idOportunidadItem = ID_ITEM,
                nombre = "Nombre Original",
            )
        every { simulacionRepository.findById(ID_SIMULACION) } returns Optional.of(yaPrincipal)
        stubItem()

        val dto = service.marcarPrincipal(ID_SIMULACION, admin)

        assertThat(dto.esPrincipal).isTrue()
        verify(exactly = 0) { simulacionRepository.save(any()) }
        verify(exactly = 0) { simulacionRepository.desmarcarPrincipalDe(any()) }
        verify(exactly = 0) { simulacionLogRepository.save(any()) }
    }

    @Test
    fun `marcarPrincipal con item y no principal desmarca la vigente ANTES de guardar y registra marcada_principal`() {
        val noPrincipal =
            simulacionPersistida(
                id = ID_SIMULACION,
                createdBy = admin.id,
                idOportunidadItem = ID_ITEM,
                nombre = "Nombre Original",
            ).apply { esPrincipal = false }
        every { simulacionRepository.findById(ID_SIMULACION) } returns Optional.of(noPrincipal)
        stubItem()
        stubDesmarcar()
        every { simulacionRepository.save(capture(guardada)) } answers { guardada.captured }
        val log = slot<SimulacionLog>()
        every { simulacionLogRepository.save(capture(log)) } answers { log.captured }

        val dto = service.marcarPrincipal(ID_SIMULACION, admin)

        verifyOrder {
            simulacionRepository.desmarcarPrincipalDe(ID_ITEM)
            simulacionRepository.save(any())
        }
        assertThat(dto.esPrincipal).isTrue()
        assertThat(guardada.captured.esPrincipal).isTrue()
        assertThat(log.captured.tipoEvento).isEqualTo(TipoEventoSimulacion.marcada_principal)
        assertThat(log.captured.idOportunidadItem).isEqualTo(ID_ITEM)
        // Snapshot minimo (K24): el CHECK solo exige `id_oportunidad_item` para este tipo.
        assertThat(log.captured.modo).isNull()
        assertThat(log.captured.precioVenta).isNull()
        assertThat(log.captured.cuotaInicial).isNull()
        assertThat(log.captured.plazoMeses).isNull()
        assertThat(log.captured.tea).isNull()
        assertThat(log.captured.valorResidual).isNull()
        assertThat(log.captured.cuotaFinal).isNull()
    }

    @Test
    fun `un vendedor no puede marcar principal una simulacion ajena y no escribe nada`() {
        every { simulacionRepository.findById(ID_SIMULACION) } returns
            Optional.of(simulacionPersistida(id = ID_SIMULACION, createdBy = 999))

        assertThatThrownBy {
            service.marcarPrincipal(ID_SIMULACION, vendedor)
        }.isInstanceOf(NoEncontradoException::class.java)

        verify(exactly = 0) { simulacionRepository.save(any()) }
        verify(exactly = 0) { simulacionRepository.desmarcarPrincipalDe(any()) }
        verify(exactly = 0) { simulacionLogRepository.save(any()) }
    }

    // ---------- bifurcar: "Guardar como Nueva Simulacion" (§7.3, D49/K27) ----------

    /** Id que JPA asigna a la fila NUEVA; distinto del origen [ID_SIMULACION] a proposito. */
    private val idBifurcada = 901L

    /**
     * Todo lo que captura una bifurcacion. `save` va a una LISTA, no a un slot:
     * lo que se comprueba en el test 1 es que se guarde UNA sola entidad —la
     * nueva— y nunca la del origen.
     */
    private val bifurcadas = mutableListOf<Simulacion>()

    private fun stubBifurcar() {
        every { simulacionRepository.save(capture(bifurcadas)) } answers { conId(bifurcadas.last(), idBifurcada) }
        every { simulacionLogRepository.save(capture(logGuardado)) } answers { logGuardado.captured }
        every { simulacionRepository.correlativos(any()) } returns emptyList()
        every { empresaService.resumenPorIds(any()) } returns
            mapOf(ID_EMPRESA to EmpresaResumen(id = ID_EMPRESA, razonSocial = "Transportes Lima SAC", distrito = null))
        every { modeloService.resumen(any()) } returns ModeloResumen(id = ID_MODELO, codigo = "MB-O500", precioBase = null)
    }

    @Test
    fun `bifurcar sin cambios crea una fila NUEVA con los valores del origen y no toca la del origen`() {
        val origen = simulacionPersistida(id = ID_SIMULACION, createdBy = admin.id)
        every { simulacionRepository.findById(ID_SIMULACION) } returns Optional.of(origen)
        stubBifurcar()

        val dto = service.bifurcar(ID_SIMULACION, BifurcarSimulacionRequest(), admin)

        // Una sola entidad guardada, y NO es la del origen.
        assertThat(bifurcadas).hasSize(1)
        val nueva = bifurcadas.single()
        assertThat(nueva).isNotSameAs(origen)
        assertThat(nueva.id).isNull() // INSERT, no UPDATE: la entidad nueva llega sin id.
        assertThat(nueva.idSimulacionOrigen).isEqualTo(ID_SIMULACION)
        assertThat(dto.id).isEqualTo(idBifurcada)
        assertThat(dto.idSimulacionOrigen).isEqualTo(ID_SIMULACION)

        // Todo lo heredado, identico al origen.
        assertThat(nueva.modo).isEqualTo(origen.modo)
        assertThat(nueva.precioVenta).isEqualByComparingTo(origen.precioVenta)
        assertThat(nueva.descuento).isEqualByComparingTo(origen.descuento)
        assertThat(nueva.cuotaInicial).isEqualByComparingTo(origen.cuotaInicial)
        assertThat(nueva.plazoMeses).isEqualTo(origen.plazoMeses)
        assertThat(nueva.tea).isEqualByComparingTo(origen.tea)
        assertThat(nueva.valorResidual).isEqualByComparingTo(origen.valorResidual)
        assertThat(nueva.diasTrabajados).isEqualTo(origen.diasTrabajados)
        assertThat(nueva.comisionEstructuracion).isEqualByComparingTo(origen.comisionEstructuracion)
        assertThat(nueva.cuotaFinal).isEqualByComparingTo(origen.cuotaFinal)
        assertThat(nueva.createdBy).isEqualTo(admin.id)
        assertThat(nueva.updatedBy).isEqualTo(admin.id)
        assertThat(nueva.createdAt).isEqualTo(nueva.updatedAt)

        // El origen no se guarda ni se borra desde el Service (D49).
        verify(exactly = 0) { simulacionRepository.save(origen) }
        verify(exactly = 0) { simulacionRepository.delete(origen) }
    }

    @Test
    fun `bifurcar cambiando el modo a credito directo aplica el modo nuevo y su cuota_final 697_67`() {
        // El origen es el caso dorado leasing de §3.6 (cuota_final 1548.86).
        val origen = simulacionPersistida(id = ID_SIMULACION, createdBy = admin.id)
        every { simulacionRepository.findById(ID_SIMULACION) } returns Optional.of(origen)
        stubBifurcar()

        val dto =
            service.bifurcar(
                ID_SIMULACION,
                // Los parametros del caso dorado de credito directo de §3.6.
                BifurcarSimulacionRequest(
                    modo = "credito_directo",
                    precioVenta = BigDecimal("90000"),
                    cuotaInicial = BigDecimal("45000"),
                    tea = BigDecimal("13"),
                    valorResidual = BigDecimal("35000"),
                ),
                admin,
            )

        // Cambiar de modo aqui NO es conflicto: es el proposito de bifurcar (K27, §2).
        val nueva = bifurcadas.single()
        assertThat(nueva.modo).isEqualTo(ModoSimulacion.credito_directo)
        assertThat(nueva.cuotaFinal).isEqualByComparingTo(BigDecimal("697.67"))
        assertThat(dto.modo).isEqualTo("credito_directo")
        assertThat(dto.cuotaFinal).isEqualTo("697.67")
        // El origen conserva su modo y su cuota: su fila no se toca.
        assertThat(origen.modo).isEqualTo(ModoSimulacion.leasing)
        assertThat(origen.cuotaFinal).isEqualByComparingTo(BigDecimal("1548.86"))
    }

    @Test
    fun `la bifurcada que hereda el item nace principal y desmarca la vigente ANTES de insertar`() {
        val origen =
            simulacionPersistida(id = ID_SIMULACION, createdBy = admin.id, idOportunidadItem = ID_ITEM)
        assertThat(origen.esPrincipal).isTrue() // el origen era la principal de ese item.
        every { simulacionRepository.findById(ID_SIMULACION) } returns Optional.of(origen)
        stubItem()
        stubDesmarcar()
        stubBifurcar()

        val dto = service.bifurcar(ID_SIMULACION, BifurcarSimulacionRequest(), admin)

        verifyOrder {
            simulacionRepository.desmarcarPrincipalDe(ID_ITEM)
            simulacionRepository.save(any())
        }
        val nueva = bifurcadas.single()
        assertThat(nueva.idOportunidadItem).isEqualTo(ID_ITEM)
        assertThat(nueva.esPrincipal).isTrue()
        assertThat(dto.esPrincipal).isTrue()
    }

    @Test
    fun `bifurcar reenlazando al item de otro vendedor es 404 y no crea nada`() {
        // El origen es propio del vendedor y va suelto; el item destino es de otro.
        every { simulacionRepository.findById(ID_SIMULACION) } returns
            Optional.of(simulacionPersistida(id = ID_SIMULACION, createdBy = ID_VENDEDOR))
        stubItem(idVendedor = 999)

        assertThatThrownBy {
            service.bifurcar(ID_SIMULACION, BifurcarSimulacionRequest(idOportunidadItem = ID_ITEM), vendedor)
        }.isInstanceOf(NoEncontradoException::class.java)

        verify(exactly = 0) { simulacionRepository.save(any()) }
        verify(exactly = 0) { simulacionRepository.desmarcarPrincipalDe(any()) }
        verify(exactly = 0) { simulacionLogRepository.save(any()) }
    }

    @Test
    fun `bifurcar una simulacion sin item deja la nueva sin item y sin relevo de principal`() {
        every { simulacionRepository.findById(ID_SIMULACION) } returns
            Optional.of(simulacionPersistida(id = ID_SIMULACION, createdBy = admin.id))
        stubBifurcar()

        val dto = service.bifurcar(ID_SIMULACION, BifurcarSimulacionRequest(), admin)

        val nueva = bifurcadas.single()
        assertThat(nueva.idOportunidadItem).isNull()
        // Sin item no hay principal posible: lo prohibe `chk_simulacion_principal_requiere_item`.
        assertThat(nueva.esPrincipal).isFalse()
        assertThat(dto.esPrincipal).isFalse()
        verify(exactly = 0) { simulacionRepository.desmarcarPrincipalDe(any()) }
    }

    @Test
    fun `el evento de la bifurcada es creada y lleva id_simulacion_origen poblado en el log`() {
        every { simulacionRepository.findById(ID_SIMULACION) } returns
            Optional.of(simulacionPersistida(id = ID_SIMULACION, createdBy = admin.id))
        stubBifurcar()

        service.bifurcar(ID_SIMULACION, BifurcarSimulacionRequest(), admin)

        val log = logGuardado.captured
        // El enum no tiene tipo "bifurcada" (K27): la fila nueva nace con su propio `creada`.
        assertThat(log.tipoEvento).isEqualTo(TipoEventoSimulacion.creada)
        assertThat(log.idSimulacion).isEqualTo(idBifurcada)
        // El vinculo con el origen tambien viaja en el log, para sobrevivir a su purga (§7.3).
        assertThat(log.idSimulacionOrigen).isEqualTo(ID_SIMULACION)
        assertThat(log.createdBy).isEqualTo(admin.id)
        // Snapshot completo, igual que cualquier `creada` (K15).
        assertThat(log.modo).isNotNull()
        assertThat(log.precioVenta).isNotNull()
        assertThat(log.cuotaFinal).isNotNull()
    }

    @Test
    fun `bifurcar con una fusion que rompe 13 es 400 y no crea nada`() {
        every { simulacionRepository.findById(ID_SIMULACION) } returns
            Optional.of(simulacionPersistida(id = ID_SIMULACION, createdBy = admin.id))

        assertThatThrownBy {
            // Fusionado sobre el origen: PV_efectivo = 100000 x (1 - 50/100) = 50000;
            // la inicial lo iguala.
            service.bifurcar(
                ID_SIMULACION,
                BifurcarSimulacionRequest(
                    precioVenta = BigDecimal("100000"),
                    descuento = BigDecimal("50"),
                    cuotaInicial = BigDecimal("50000"),
                ),
                admin,
            )
        }.isInstanceOf(ValidacionException::class.java)
            .extracting { (it as ValidacionException).field }
            .isEqualTo("cuota_inicial")

        verify(exactly = 0) { simulacionRepository.save(any()) }
        verify(exactly = 0) { simulacionLogRepository.save(any()) }
    }

    @Test
    fun `el nombre manual del origen NO se hereda en la bifurcada`() {
        every { simulacionRepository.findById(ID_SIMULACION) } returns
            Optional.of(simulacionPersistida(id = ID_SIMULACION, createdBy = admin.id, nombre = "Mi simulación"))
        stubBifurcar()

        service.bifurcar(ID_SIMULACION, BifurcarSimulacionRequest(), admin)

        // Sin nombre en la entidad, el DTO autogenera el suyo (§8.1); lo que
        // importa aqui es que la fila persistida no copio el string del origen (D49).
        assertThat(bifurcadas.single().nombre).isNull()
    }

    @Test
    fun `un vendedor no puede bifurcar una simulacion ajena y no crea nada`() {
        every { simulacionRepository.findById(ID_SIMULACION) } returns
            Optional.of(simulacionPersistida(id = ID_SIMULACION, createdBy = 999))

        assertThatThrownBy {
            service.bifurcar(ID_SIMULACION, BifurcarSimulacionRequest(), vendedor)
        }.isInstanceOf(NoEncontradoException::class.java)

        verify(exactly = 0) { simulacionRepository.save(any()) }
        verify(exactly = 0) { simulacionRepository.desmarcarPrincipalDe(any()) }
        verify(exactly = 0) { simulacionLogRepository.save(any()) }
    }
}
