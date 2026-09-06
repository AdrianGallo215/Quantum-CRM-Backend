package pe.quantum.crm.domain.simulaciones

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import pe.quantum.crm.domain.empresas.EmpresaService
import pe.quantum.crm.domain.modelos.ModeloService
import pe.quantum.crm.domain.oportunidades.OportunidadItemService
import pe.quantum.crm.domain.oportunidades.dto.OportunidadItemParaSimulacion
import pe.quantum.crm.shared.enums.ModoSimulacion
import pe.quantum.crm.shared.exception.NoEncontradoException
import pe.quantum.crm.shared.security.UsuarioActual
import java.math.BigDecimal
import java.time.LocalDateTime
import java.util.Optional

/**
 * `SimulacionServiceImpl.cronograma` (tarea D13 de plan-10-dominio-crud-tareas.md).
 *
 * Archivo propio y no parte de `SimulacionServiceImplTest.kt`: ese archivo ya
 * acumula los tests de D9-D12 y agregar los de D13 ahi lo llevaria a
 * `LargeClass` en detekt (mismo criterio que separo
 * `OportunidadCambiarEstadoInvariantesTest` de `OportunidadActualizarTest`).
 *
 * Los dos casos dorados de `reglas_simulaciones.md` §3.6 se verifican AQUI
 * sobre la salida del endpoint (filas 0, 1, 2 y 48 al centavo): lo que se
 * comprueba no es la formula del motor (eso ya lo cubre
 * `MotorSimulacionTest`) sino que el Service la exponga sin persistir nada
 * (D40, §4) y sin redondear la Tasa Nominal Mensual (§3.1).
 */
class SimulacionCronogramaTest {
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
    private val vendedor = UsuarioActual(id = ID_VENDEDOR, rol = "vendedor")

    private companion object {
        const val ID_SIMULACION = 900L
        const val ID_ITEM = 77L
        const val ID_VENDEDOR = 1L
    }

    /** Caso dorado leasing §3.6: PV 110 000 · CI 56 000 · n 48 · TEA 18 · balloon 0. */
    private fun simulacionLeasingDorada(createdBy: Long = admin.id) =
        Simulacion(
            id = ID_SIMULACION,
            modo = ModoSimulacion.leasing,
            nombre = null,
            idOportunidadItem = null,
            idModelo = null,
            precioVenta = BigDecimal("110000"),
            descuento = BigDecimal.ZERO,
            cuotaInicial = BigDecimal("56000"),
            plazoMeses = 48,
            tea = BigDecimal("18"),
            valorResidual = BigDecimal.ZERO,
            diasTrabajados = 22,
            comisionEstructuracion = BigDecimal("1180"),
            cuotaFinal = BigDecimal("1548.86"),
            esPrincipal = false,
            createdAt = LocalDateTime.now(),
            createdBy = createdBy,
            updatedAt = LocalDateTime.now(),
            updatedBy = createdBy,
        )

    /** Caso dorado credito directo §3.6: PV 90 000 · CI 45 000 · n 48 · TEA 13 · balloon 35 000. */
    private fun simulacionCreditoDirectoDorada(createdBy: Long = admin.id) =
        Simulacion(
            id = ID_SIMULACION,
            modo = ModoSimulacion.credito_directo,
            nombre = null,
            idOportunidadItem = null,
            idModelo = null,
            precioVenta = BigDecimal("90000"),
            descuento = BigDecimal.ZERO,
            cuotaInicial = BigDecimal("45000"),
            plazoMeses = 48,
            tea = BigDecimal("13"),
            valorResidual = BigDecimal("35000"),
            diasTrabajados = 22,
            comisionEstructuracion = BigDecimal("1180"),
            cuotaFinal = BigDecimal("697.67"),
            esPrincipal = false,
            createdAt = LocalDateTime.now(),
            createdBy = createdBy,
            updatedAt = LocalDateTime.now(),
            updatedBy = createdBy,
        )

    private fun stubBuscar(simulacion: Simulacion) {
        every { simulacionRepository.findById(ID_SIMULACION) } returns Optional.of(simulacion)
    }

    // ---------- Casos dorados §3.6 ----------

    @Test
    fun `caso dorado leasing trae 49 filas y las filas 0, 1, 2 y 48 coinciden al centavo`() {
        stubBuscar(simulacionLeasingDorada())

        val cronograma = service.cronograma(ID_SIMULACION, admin)

        assertThat(cronograma.filas).hasSize(49)

        val mes0 = cronograma.filas[0]
        assertThat(mes0.mes).isEqualTo(0)
        assertThat(mes0.saldoInicial).isEqualTo("93220.34")
        assertThat(mes0.amortizacion).isEqualTo("47457.63")
        assertThat(mes0.saldoFinal).isEqualTo("45762.71")

        val mes1 = cronograma.filas[1]
        assertThat(mes1.mes).isEqualTo(1)
        assertThat(mes1.saldoInicial).isEqualTo("45762.71")
        assertThat(mes1.interes).isEqualTo("635.57")
        assertThat(mes1.amortizacion).isEqualTo("677.02")
        assertThat(mes1.saldoFinal).isEqualTo("45085.69")
        assertThat(mes1.cuota).isEqualTo("1312.59")
        assertThat(mes1.cuotaConIgv).isEqualTo("1548.86")

        val mes2 = cronograma.filas[2]
        assertThat(mes2.mes).isEqualTo(2)
        assertThat(mes2.saldoInicial).isEqualTo("45085.69")
        assertThat(mes2.interes).isEqualTo("626.17")
        assertThat(mes2.amortizacion).isEqualTo("686.42")
        assertThat(mes2.saldoFinal).isEqualTo("44399.27")
        assertThat(mes2.cuota).isEqualTo("1312.59")
        assertThat(mes2.cuotaConIgv).isEqualTo("1548.86")

        val mes48 = cronograma.filas[48]
        assertThat(mes48.mes).isEqualTo(48)
        assertThat(mes48.saldoInicial).isEqualTo("1294.61")
        assertThat(mes48.interes).isEqualTo("17.98")
        assertThat(mes48.amortizacion).isEqualTo("1294.61")
        assertThat(mes48.saldoFinal).isEqualTo("0.00")
        assertThat(mes48.cuota).isEqualTo("1312.59")
        assertThat(mes48.cuotaConIgv).isEqualTo("1548.86")
    }

    @Test
    fun `caso dorado credito directo trae 49 filas y las filas 0, 1, 2 y 48 coinciden al centavo, con igv no nulo desde el mes 1`() {
        stubBuscar(simulacionCreditoDirectoDorada())

        val cronograma = service.cronograma(ID_SIMULACION, admin)

        assertThat(cronograma.filas).hasSize(49)

        val mes0 = cronograma.filas[0]
        assertThat(mes0.mes).isEqualTo(0)
        assertThat(mes0.saldoInicial).isEqualTo("90000.00")
        assertThat(mes0.amortizacion).isEqualTo("45000.00")
        assertThat(mes0.saldoFinal).isEqualTo("45000.00")

        val mes1 = cronograma.filas[1]
        assertThat(mes1.mes).isEqualTo(1)
        assertThat(mes1.saldoInicial).isEqualTo("45000.00")
        assertThat(mes1.interes).isEqualTo("460.66")
        assertThat(mes1.igv).isEqualTo("82.92")
        assertThat(mes1.amortizacion).isEqualTo("162.37")
        assertThat(mes1.saldoFinal).isEqualTo("44837.63")
        assertThat(mes1.cuota).isEqualTo("623.03")
        assertThat(mes1.cuotaConIgv).isEqualTo("705.94")

        val mes2 = cronograma.filas[2]
        assertThat(mes2.mes).isEqualTo(2)
        assertThat(mes2.saldoInicial).isEqualTo("44837.63")
        assertThat(mes2.interes).isEqualTo("459.00")
        assertThat(mes2.igv).isEqualTo("82.62")
        assertThat(mes2.amortizacion).isEqualTo("164.03")
        assertThat(mes2.saldoFinal).isEqualTo("44673.60")
        assertThat(mes2.cuota).isEqualTo("623.03")
        assertThat(mes2.cuotaConIgv).isEqualTo("705.64")

        val mes48 = cronograma.filas[48]
        assertThat(mes48.mes).isEqualTo(48)
        assertThat(mes48.saldoInicial).isEqualTo("35262.05")
        assertThat(mes48.interes).isEqualTo("360.97")
        assertThat(mes48.igv).isEqualTo("64.97")
        assertThat(mes48.amortizacion).isEqualTo("262.05")
        assertThat(mes48.saldoFinal).isEqualTo("35000.00")
        assertThat(mes48.cuota).isEqualTo("623.03")
        assertThat(mes48.cuotaConIgv).isEqualTo("688.00")

        // §3.3/§3.4: credito directo SI desglosa IGV desde el mes 1 (a diferencia de leasing).
        assertThat(cronograma.filas.drop(1)).allSatisfy { assertThat(it.igv).isNotNull() }
    }

    // ---------- Leasing no desglosa IGV (§3.3) ----------

    @Test
    fun `leasing no desglosa igv en ninguna fila`() {
        stubBuscar(simulacionLeasingDorada())

        val cronograma = service.cronograma(ID_SIMULACION, admin)

        assertThat(cronograma.filas).allSatisfy { assertThat(it.igv).isNull() }
    }

    // ---------- Mes 0 ----------

    @Test
    fun `en el mes 0 interes, igv, cuota y cuotaConIgv son null y la amortizacion es la cuota inicial sin IGV`() {
        stubBuscar(simulacionLeasingDorada())

        val mes0 = service.cronograma(ID_SIMULACION, admin).filas[0]

        assertThat(mes0.interes).isNull()
        assertThat(mes0.igv).isNull()
        assertThat(mes0.cuota).isNull()
        assertThat(mes0.cuotaConIgv).isNull()
        // Leasing trabaja sin IGV: cuota_inicial (56000) / 1.18 = 47457.63.
        assertThat(mes0.amortizacion).isEqualTo("47457.63")
    }

    // ---------- Sin fila extra por el balloon (restriccion 3 del encargo) ----------

    @Test
    fun `no hay fila extra por el balloon, la ultima fila es el mes 48 y su saldo final es el valor residual`() {
        stubBuscar(simulacionCreditoDirectoDorada())

        val cronograma = service.cronograma(ID_SIMULACION, admin)

        assertThat(cronograma.filas.last().mes).isEqualTo(48)
        assertThat(cronograma.filas.last().saldoFinal).isEqualTo("35000.00")
        assertThat(cronograma.filas.map { it.mes }).doesNotContain(49)
    }

    // ---------- No se persiste nada (§4, restriccion 1 del encargo) ----------

    @Test
    fun `no escribe nada en simulaciones ni en simulacion_log`() {
        stubBuscar(simulacionLeasingDorada())

        service.cronograma(ID_SIMULACION, admin)

        verify(exactly = 0) { simulacionRepository.save(any()) }
        verify(exactly = 0) { simulacionLogRepository.save(any()) }
        verify(exactly = 0) { simulacionRepository.delete(any<Simulacion>()) }
    }

    // ---------- Permisos (regla 14, D31) ----------

    @Test
    fun `un vendedor sobre una simulacion ajena recibe 404`() {
        val ajena = simulacionLeasingDorada(createdBy = 999).also { it.idOportunidadItem = ID_ITEM }
        stubBuscar(ajena)
        every { oportunidadItemService.datosParaSimulacion(listOf(ID_ITEM)) } returns
            mapOf(
                ID_ITEM to
                    OportunidadItemParaSimulacion(
                        id = ID_ITEM,
                        idOportunidad = 100L,
                        idEmpresa = 10L,
                        idVendedor = 998L,
                        idModelo = 7L,
                        cantidad = 2,
                        precioVenta = BigDecimal("110000.00"),
                        descuento = BigDecimal.ZERO,
                        cuotaFinanciadora = BigDecimal("937.50"),
                    ),
            )

        assertThatThrownBy {
            service.cronograma(ID_SIMULACION, vendedor)
        }.isInstanceOf(NoEncontradoException::class.java)
    }

    @Test
    fun `una simulacion inexistente es 404`() {
        every { simulacionRepository.findById(ID_SIMULACION) } returns Optional.empty()

        assertThatThrownBy {
            service.cronograma(ID_SIMULACION, admin)
        }.isInstanceOf(NoEncontradoException::class.java)
    }
}
