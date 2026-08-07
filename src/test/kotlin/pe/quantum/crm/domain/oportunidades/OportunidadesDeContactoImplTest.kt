package pe.quantum.crm.domain.oportunidades

import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import pe.quantum.crm.domain.empresas.EmpresaService
import pe.quantum.crm.domain.empresas.dto.EmpresaResumen
import pe.quantum.crm.domain.modelos.ModeloService
import pe.quantum.crm.shared.security.UsuarioActual
import java.math.BigDecimal
import java.time.LocalDateTime

/**
 * Proyeccion de oportunidades para la vista de contacto.
 *
 * El grueso de estos tests es el filtro de visibilidad: los contactos son globales
 * (cualquier rol los busca para vincular), asi que si las oportunidades colgadas de
 * un contacto no se filtran por vendedor, enumerar contactos vuelca el pipeline
 * entero de la empresa con sus montos (matriz_permisos.md §1: la visibilidad aplica
 * a listado Y detalle, sin excepcion).
 */
class OportunidadesDeContactoImplTest {
    private val oportunidadRepository = mockk<OportunidadRepository>()
    private val contactoOportunidadRepository = mockk<OportunidadContactoRepository>()
    private val empresaService = mockk<EmpresaService>()
    private val modeloService = mockk<ModeloService>()
    private val service =
        OportunidadesDeContactoImpl(
            oportunidadRepository,
            contactoOportunidadRepository,
            empresaService,
            modeloService,
        )

    private val admin = UsuarioActual(id = 1, rol = "admin")
    private val vendedor = UsuarioActual(id = 7, rol = "vendedor")

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
        precioUnitario = BigDecimal.TEN,
        dcto = BigDecimal.ZERO,
        montoTotal = BigDecimal.TEN,
        createdAt = LocalDateTime.now(),
        createdBy = 1,
        updatedAt = LocalDateTime.now(),
        updatedBy = 1,
    )

    private fun busX() = pe.quantum.crm.domain.modelos.dto.ModeloResumen(id = 1, codigo = "BUS-X", precioBase = BigDecimal.TEN)

    private fun vinculo(
        idOportunidad: Long,
        rol: String = "Titular",
    ) = OportunidadContacto(OportunidadContactoId(idOportunidad = idOportunidad, idContacto = 5), rol)

    private fun stubResumenes() {
        every { empresaService.resumenPorIds(any()) } returns
            mapOf(10L to EmpresaResumen(id = 10, razonSocial = "Transp. Sta. Anita S.A.", distrito = null))
        every { modeloService.resumenPorIds(any()) } returns mapOf(1L to busX())
    }

    @Test
    fun `contar sin filtro de vendedor para un supervisor`() {
        every { contactoOportunidadRepository.countVisiblesPorContacto(5, null) } returns 3L

        assertThat(service.contar(5, admin)).isEqualTo(3)
    }

    @Test
    fun `contar solo las oportunidades propias de un vendedor`() {
        every { contactoOportunidadRepository.countVisiblesPorContacto(5, 7) } returns 1L

        assertThat(service.contar(5, vendedor)).isEqualTo(1)
    }

    @Test
    fun `listar mapea empresa, modelo, monto y rol`() {
        every { contactoOportunidadRepository.findByIdIdContacto(5) } returns listOf(vinculo(100, "Contacto Principal"))
        every { oportunidadRepository.findAllById(listOf(100L)) } returns listOf(oportunidad(id = 100))
        stubResumenes()

        val resultado = service.listar(5, admin)

        assertThat(resultado).hasSize(1)
        val dto = resultado.first()
        assertThat(dto.id).isEqualTo(100)
        assertThat(dto.empresa?.razonSocial).isEqualTo("Transp. Sta. Anita S.A.")
        assertThat(dto.modelo?.codigo).isEqualTo("BUS-X")
        assertThat(dto.montoTotal).isEqualTo("10")
        assertThat(dto.rolEnOportunidad).isEqualTo("Contacto Principal")
    }

    @Test
    fun `listar devuelve vacio si no hay vinculos`() {
        every { contactoOportunidadRepository.findByIdIdContacto(5) } returns emptyList()

        assertThat(service.listar(5, admin)).isEmpty()
    }

    @Test
    fun `listar oculta a un vendedor las oportunidades de otro vendedor`() {
        every { contactoOportunidadRepository.findByIdIdContacto(5) } returns listOf(vinculo(100), vinculo(200))
        every { oportunidadRepository.findAllById(listOf(100L, 200L)) } returns
            listOf(oportunidad(id = 100, idVendedor = 7), oportunidad(id = 200, idVendedor = 99))
        stubResumenes()

        val resultado = service.listar(5, vendedor)

        assertThat(resultado.map { it.id }).containsExactly(100)
    }

    @Test
    fun `listar muestra al supervisor las oportunidades de todos los vendedores`() {
        every { contactoOportunidadRepository.findByIdIdContacto(5) } returns listOf(vinculo(100), vinculo(200))
        every { oportunidadRepository.findAllById(listOf(100L, 200L)) } returns
            listOf(oportunidad(id = 100, idVendedor = 7), oportunidad(id = 200, idVendedor = 99))
        stubResumenes()

        val resultado = service.listar(5, UsuarioActual(id = 3, rol = "gerencia"))

        assertThat(resultado.map { it.id }).containsExactly(100, 200)
    }
}
