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
}
