package pe.quantum.crm.domain.simulaciones

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatCode
import org.junit.jupiter.api.Test
import pe.quantum.crm.domain.empresas.EmpresaService
import pe.quantum.crm.domain.modelos.ModeloService
import pe.quantum.crm.domain.notificaciones.NotificacionService
import pe.quantum.crm.domain.oportunidades.OportunidadItemService
import pe.quantum.crm.domain.simulaciones.dto.ItemParaCuota
import pe.quantum.crm.shared.enums.ModoSimulacion
import java.math.BigDecimal
import java.time.LocalDateTime

/**
 * `SimulacionServiceImpl.cuotaQuantumPorItems` (§6.2 de
 * `reglas_simulaciones.md`, decision D56 de
 * `plan-13-mapa-cierre-simulaciones.md`).
 *
 * Archivo propio y no parte de `SimulacionServiceImplTest.kt`: ese ya lleva
 * `@Suppress("LargeClass")`, mismo criterio que separo
 * `SimulacionCronogramaTest` en el Plan D.
 *
 * [SimulacionPermisos] se mockea AQUI —y no se usa la instancia real como en
 * el resto del modulo— precisamente para poder afirmar que este metodo no la
 * consulta en ninguna rama: es un dato de listado que el `vendedor` debe ver
 * en su propia oportunidad, y quien llama (`oportunidades`) ya filtro la
 * visibilidad (D32/D56).
 */
class SimulacionCuotaPorItemsTest {
    private val simulacionRepository = mockk<SimulacionRepository>()
    private val simulacionLogRepository = mockk<SimulacionLogRepository>()

    /** Mock estricto: cualquier llamada no stubeada falla el test por si sola. */
    private val permisos = mockk<SimulacionPermisos>()
    private val oportunidadItemService = mockk<OportunidadItemService>()
    private val modeloService = mockk<ModeloService>()
    private val empresaService = mockk<EmpresaService>()

    /** Solo lo usa `avisarHuerfanasPorExpirar` (F7), que este test no ejercita. */
    private val notificacionService = mockk<NotificacionService>()
    private val service =
        SimulacionServiceImpl(
            simulacionRepository,
            simulacionLogRepository,
            permisos,
            oportunidadItemService,
            modeloService,
            empresaService,
            notificacionService,
        )

    private companion object {
        const val ID_ITEM_CON_PRINCIPAL = 10L
        const val ID_ITEM_SIN_PRINCIPAL = 20L
        const val ID_ITEM_SIN_PRECIO = 30L
        const val ID_ITEM_PRECIO_INVALIDO = 40L

        /** Precio para el que los defaults de §6.1 SI producen cuota efimera. */
        val PRECIO_VALIDO: BigDecimal = BigDecimal("190000")

        /**
         * Caso K32: en leasing el Principal de 60 000 queda en 12 711.86 y el
         * valor residual por defecto de §6.1 (25 000) es mayor, asi que §13 no
         * se cumple y [CuotaEfimera] degrada a null (verificado en F3).
         */
        val PRECIO_INVALIDO_PARA_DEFAULTS: BigDecimal = BigDecimal("60000")

        /**
         * `cuota_final` sembrada en la principal. Deliberadamente distinta de
         * cualquier cosa que [CuotaEfimera] pueda devolver para
         * [PRECIO_VALIDO]: si el metodo tomara el camino efimero teniendo
         * principal, el assert lo detecta.
         */
        val CUOTA_DE_LA_PRINCIPAL: BigDecimal = BigDecimal("1234.56")
    }

    /** Simulacion principal (§6.3) de [idItem], con su `cuota_final` ya persistida. */
    private fun principal(
        idItem: Long,
        cuotaFinal: BigDecimal = CUOTA_DE_LA_PRINCIPAL,
    ) = Simulacion(
        id = idItem * 100,
        modo = ModoSimulacion.leasing,
        idOportunidadItem = idItem,
        precioVenta = PRECIO_VALIDO,
        descuento = BigDecimal.ZERO,
        cuotaInicial = BigDecimal("45000"),
        plazoMeses = 48,
        tea = BigDecimal("14"),
        valorResidual = BigDecimal("25000"),
        cuotaFinal = cuotaFinal,
        esPrincipal = true,
        createdAt = LocalDateTime.now(),
        createdBy = 1L,
        updatedAt = LocalDateTime.now(),
        updatedBy = 1L,
    )

    private fun item(
        idItem: Long,
        precioVenta: BigDecimal?,
        descuento: BigDecimal? = null,
    ) = ItemParaCuota(idItem = idItem, precioVenta = precioVenta, descuento = descuento)

    @Test
    fun `item con simulacion principal - devuelve su cuota_final, no el calculo efimero`() {
        every {
            simulacionRepository.findByIdOportunidadItemInAndEsPrincipalTrue(any())
        } returns listOf(principal(ID_ITEM_CON_PRINCIPAL))

        val cuotas = service.cuotaQuantumPorItems(listOf(item(ID_ITEM_CON_PRINCIPAL, PRECIO_VALIDO)))

        assertThat(cuotas).containsOnlyKeys(ID_ITEM_CON_PRINCIPAL)
        assertThat(cuotas.getValue(ID_ITEM_CON_PRINCIPAL)).isEqualByComparingTo(CUOTA_DE_LA_PRINCIPAL)
        // El otro camino habria dado un numero distinto: la principal manda (§6.2).
        val efimera = requireNotNull(CuotaEfimera.calcular(PRECIO_VALIDO, null))
        assertThat(cuotas.getValue(ID_ITEM_CON_PRINCIPAL)).isNotEqualByComparingTo(efimera)
    }

    @Test
    fun `item sin principal y con precio valido - devuelve la cuota efimera de §6_1`() {
        every { simulacionRepository.findByIdOportunidadItemInAndEsPrincipalTrue(any()) } returns emptyList()
        val esperada = requireNotNull(CuotaEfimera.calcular(PRECIO_VALIDO, null))

        val cuotas = service.cuotaQuantumPorItems(listOf(item(ID_ITEM_SIN_PRINCIPAL, PRECIO_VALIDO)))

        assertThat(cuotas).containsOnlyKeys(ID_ITEM_SIN_PRINCIPAL)
        assertThat(cuotas.getValue(ID_ITEM_SIN_PRINCIPAL)).isEqualByComparingTo(esperada)
    }

    @Test
    fun `item sin principal y sin precio de venta - no aparece en el mapa`() {
        every { simulacionRepository.findByIdOportunidadItemInAndEsPrincipalTrue(any()) } returns emptyList()

        val cuotas = service.cuotaQuantumPorItems(listOf(item(ID_ITEM_SIN_PRECIO, null)))

        assertThat(cuotas).isEmpty()
    }

    @Test
    fun `item sin principal cuyo precio invalida los defaults - no aparece en el mapa y no lanza`() {
        every { simulacionRepository.findByIdOportunidadItemInAndEsPrincipalTrue(any()) } returns emptyList()
        val items = listOf(item(ID_ITEM_PRECIO_INVALIDO, PRECIO_INVALIDO_PARA_DEFAULTS))

        assertThatCode { service.cuotaQuantumPorItems(items) }.doesNotThrowAnyException()
        assertThat(service.cuotaQuantumPorItems(items)).isEmpty()
    }

    @Test
    fun `mezcla de los cuatro casos - el mapa trae exactamente los que si tienen cuota`() {
        every {
            simulacionRepository.findByIdOportunidadItemInAndEsPrincipalTrue(any())
        } returns listOf(principal(ID_ITEM_CON_PRINCIPAL))
        val esperadaEfimera = requireNotNull(CuotaEfimera.calcular(PRECIO_VALIDO, null))

        val cuotas =
            service.cuotaQuantumPorItems(
                listOf(
                    item(ID_ITEM_CON_PRINCIPAL, PRECIO_VALIDO),
                    item(ID_ITEM_SIN_PRINCIPAL, PRECIO_VALIDO),
                    item(ID_ITEM_SIN_PRECIO, null),
                    item(ID_ITEM_PRECIO_INVALIDO, PRECIO_INVALIDO_PARA_DEFAULTS),
                ),
            )

        assertThat(cuotas).containsOnlyKeys(ID_ITEM_CON_PRINCIPAL, ID_ITEM_SIN_PRINCIPAL)
        assertThat(cuotas.getValue(ID_ITEM_CON_PRINCIPAL)).isEqualByComparingTo(CUOTA_DE_LA_PRINCIPAL)
        assertThat(cuotas.getValue(ID_ITEM_SIN_PRINCIPAL)).isEqualByComparingTo(esperadaEfimera)
    }

    @Test
    fun `coleccion vacia - devuelve mapa vacio sin tocar la base`() {
        val cuotas = service.cuotaQuantumPorItems(emptyList())

        assertThat(cuotas).isEmpty()
        verify(exactly = 0) { simulacionRepository.findByIdOportunidadItemInAndEsPrincipalTrue(any()) }
    }

    @Test
    fun `sin N+1 - una sola consulta de principales para toda la coleccion`() {
        every {
            simulacionRepository.findByIdOportunidadItemInAndEsPrincipalTrue(any())
        } returns listOf(principal(ID_ITEM_CON_PRINCIPAL))
        val cinco = (1L..5L).map { item(it * ID_ITEM_CON_PRINCIPAL, PRECIO_VALIDO) }

        service.cuotaQuantumPorItems(cinco)

        verify(exactly = 1) { simulacionRepository.findByIdOportunidadItemInAndEsPrincipalTrue(any()) }
    }

    @Test
    fun `no aplica permisos - el metodo no consulta SimulacionPermisos en ninguna rama`() {
        every {
            simulacionRepository.findByIdOportunidadItemInAndEsPrincipalTrue(any())
        } returns listOf(principal(ID_ITEM_CON_PRINCIPAL))

        // Las cuatro ramas del metodo en una sola llamada: con principal, con
        // efimera, sin precio y con precio que invalida los defaults.
        service.cuotaQuantumPorItems(
            listOf(
                item(ID_ITEM_CON_PRINCIPAL, PRECIO_VALIDO),
                item(ID_ITEM_SIN_PRINCIPAL, PRECIO_VALIDO),
                item(ID_ITEM_SIN_PRECIO, null),
                item(ID_ITEM_PRECIO_INVALIDO, PRECIO_INVALIDO_PARA_DEFAULTS),
            ),
        )

        verify(exactly = 0) { permisos.exigirAcceso(any()) }
        verify(exactly = 0) { permisos.exigirAccesoAlModulo(any()) }
        verify(exactly = 0) { permisos.alcanza(any(), any(), any()) }
        verify(exactly = 0) { permisos.exigirAlcance(any(), any(), any()) }
    }
}
