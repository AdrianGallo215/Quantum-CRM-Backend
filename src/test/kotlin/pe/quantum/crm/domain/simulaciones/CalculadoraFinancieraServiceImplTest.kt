package pe.quantum.crm.domain.simulaciones

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import pe.quantum.crm.domain.empresas.EmpresaService
import pe.quantum.crm.domain.empresas.dto.EmpresaResumen
import pe.quantum.crm.domain.modelos.ModeloService
import pe.quantum.crm.domain.modelos.dto.ModeloResumen
import pe.quantum.crm.domain.simulaciones.dto.CalculadoraRequest
import pe.quantum.crm.shared.exception.NoEncontradoException
import pe.quantum.crm.shared.exception.PermisoInsuficienteException
import pe.quantum.crm.shared.exception.ValidacionException
import pe.quantum.crm.shared.security.UsuarioActual
import java.math.BigDecimal

/**
 * `CalculadoraFinancieraServiceImpl` (tarea E10 de
 * plan-12-historial-calculadora-tareas.md).
 *
 * [SimulacionPermisos] se usa REAL, no mockeado: no tiene dependencias, y un
 * mock haria que el test finja cumplir la regla de acceso de §10 en vez de
 * cubrirla. `ModeloService` y `EmpresaService` si son mocks: son la frontera
 * con otros modulos.
 *
 * No hay test de "no escribe" porque no puede haberlo: el constructor no recibe
 * repositorios, asi que escribir es imposible por tipo (decision D50).
 */
class CalculadoraFinancieraServiceImplTest {
    private val modeloService = mockk<ModeloService>()
    private val empresaService = mockk<EmpresaService>()
    private val service =
        CalculadoraFinancieraServiceImpl(
            SimulacionPermisos(),
            modeloService,
            empresaService,
        )

    private val admin = UsuarioActual(id = 5, rol = "admin")

    private companion object {
        const val ID_EMPRESA = 10L
        const val ID_MODELO = 7L
    }

    /** Caso dorado leasing §3.6: PV 110000 · CI 56000 · n 48 · TEA 18 · balloon 0. */
    private fun leasing(
        idEmpresa: Long? = null,
        idModelo: Long? = null,
    ) = CalculadoraRequest(
        modo = "leasing",
        idEmpresa = idEmpresa,
        idModelo = idModelo,
        precioVenta = BigDecimal("110000.00"),
        cuotaInicial = BigDecimal("56000.00"),
        plazoMeses = 48,
        tea = BigDecimal("18.00"),
        valorResidual = BigDecimal("0.00"),
    )

    @Test
    fun `caso dorado leasing devuelve la cuota final de 3-6 sin persistir nada`() {
        val dto = service.calcular(leasing(), admin)

        assertThat(dto.cronograma.cuotaFinal).isEqualTo("1548.86")
    }

    @Test
    fun `caso dorado credito directo devuelve la cuota final de 3-6`() {
        val request =
            CalculadoraRequest(
                modo = "credito_directo",
                precioVenta = BigDecimal("90000.00"),
                cuotaInicial = BigDecimal("45000.00"),
                plazoMeses = 48,
                tea = BigDecimal("13.00"),
                valorResidual = BigDecimal("35000.00"),
            )

        val dto = service.calcular(request, admin)

        assertThat(dto.cronograma.cuotaFinal).isEqualTo("697.67")
    }

    @Test
    fun `sin idEmpresa ni idModelo no consulta esos modulos y devuelve ambos null`() {
        val dto = service.calcular(leasing(), admin)

        assertThat(dto.empresa).isNull()
        assertThat(dto.modelo).isNull()
        verify(exactly = 0) { modeloService.resumen(any()) }
        verify(exactly = 0) { empresaService.resumenPorIds(any()) }
    }

    @Test
    fun `con idEmpresa e idModelo validos los resuelve y los expone en el dto`() {
        every { modeloService.resumen(ID_MODELO) } returns
            ModeloResumen(id = ID_MODELO, codigo = "KW-12", precioBase = BigDecimal("110000.00"))
        every { empresaService.resumenPorIds(listOf(ID_EMPRESA)) } returns
            mapOf(ID_EMPRESA to EmpresaResumen(id = ID_EMPRESA, razonSocial = "Transportes SAC", distrito = "Ate"))

        val dto = service.calcular(leasing(idEmpresa = ID_EMPRESA, idModelo = ID_MODELO), admin)

        assertThat(dto.modelo?.id).isEqualTo(ID_MODELO)
        assertThat(dto.modelo?.codigo).isEqualTo("KW-12")
        assertThat(dto.empresa?.razonSocial).isEqualTo("Transportes SAC")
        assertThat(dto.cronograma.cuotaFinal).isEqualTo("1548.86")
    }

    @Test
    fun `idModelo inexistente propaga el 404 de ModeloService`() {
        every { modeloService.resumen(ID_MODELO) } throws NoEncontradoException("El modelo no existe")

        assertThatThrownBy { service.calcular(leasing(idModelo = ID_MODELO), admin) }
            .isInstanceOf(NoEncontradoException::class.java)
    }

    @Test
    fun `idEmpresa inexistente es 404 y no un empresa null silencioso`() {
        every { empresaService.resumenPorIds(listOf(ID_EMPRESA)) } returns emptyMap()

        assertThatThrownBy { service.calcular(leasing(idEmpresa = ID_EMPRESA), admin) }
            .isInstanceOf(NoEncontradoException::class.java)
    }

    @Test
    fun `jdv no tiene acceso a la calculadora`() {
        assertThatThrownBy { service.calcular(leasing(), UsuarioActual(id = 9, rol = "jdv")) }
            .isInstanceOf(PermisoInsuficienteException::class.java)
    }

    @Test
    fun `vendedor si tiene acceso a la calculadora`() {
        val dto = service.calcular(leasing(), UsuarioActual(id = 3, rol = "vendedor"))

        assertThat(dto.cronograma.cuotaFinal).isEqualTo("1548.86")
    }

    @Test
    fun `cuota inicial mayor o igual al precio efectivo es 400 en cuota_inicial`() {
        val request =
            CalculadoraRequest(
                modo = "leasing",
                precioVenta = BigDecimal("110000.00"),
                cuotaInicial = BigDecimal("110000.00"),
                plazoMeses = 48,
                tea = BigDecimal("18.00"),
            )

        assertThatThrownBy { service.calcular(request, admin) }
            .isInstanceOf(ValidacionException::class.java)
            .extracting { (it as ValidacionException).field }
            .isEqualTo("cuota_inicial")
    }

    @Test
    fun `modo fuera del enum es 400 en modo y nunca un 500`() {
        val request =
            CalculadoraRequest(
                modo = "leasing_invalido",
                precioVenta = BigDecimal("110000.00"),
                cuotaInicial = BigDecimal("56000.00"),
                plazoMeses = 48,
                tea = BigDecimal("18.00"),
            )

        assertThatThrownBy { service.calcular(request, admin) }
            .isInstanceOf(ValidacionException::class.java)
            .extracting { (it as ValidacionException).field }
            .isEqualTo("modo")
    }
}
