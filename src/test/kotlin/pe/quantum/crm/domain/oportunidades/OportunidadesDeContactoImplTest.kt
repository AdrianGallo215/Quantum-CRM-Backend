package pe.quantum.crm.domain.oportunidades

import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import pe.quantum.crm.domain.empresas.EmpresaService
import pe.quantum.crm.domain.empresas.dto.EmpresaResumen
import pe.quantum.crm.domain.oportunidades.dto.ModeloEnOportunidadDto
import pe.quantum.crm.domain.oportunidades.dto.OportunidadItemDto
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
    private val oportunidadItemService = mockk<OportunidadItemService>()
    private val service =
        OportunidadesDeContactoImpl(
            oportunidadRepository,
            contactoOportunidadRepository,
            empresaService,
            oportunidadItemService,
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
        estado = pe.quantum.crm.shared.enums.EstadoOportunidad.evaluacion_calidda,
        createdAt = LocalDateTime.now(),
        createdBy = 1,
        updatedAt = LocalDateTime.now(),
        updatedBy = 1,
    )

    private fun itemDto(idOportunidad: Long) =
        OportunidadItemDto(
            id = idOportunidad * 10,
            idModelo = 1,
            modelo = ModeloEnOportunidadDto(id = 1, codigo = "BUS-X", precioBase = "10"),
            cantidad = 1,
            precioVenta = "10",
            descuento = null,
            cuotaFinanciadora = "0",
            montoItem = "10",
        )

    private fun vinculo(
        idOportunidad: Long,
        rol: String = "Titular",
    ) = OportunidadContacto(OportunidadContactoId(idOportunidad = idOportunidad, idContacto = 5), rol)

    private fun stubResumenes() {
        every { empresaService.resumenPorIds(any()) } returns
            mapOf(10L to EmpresaResumen(id = 10, razonSocial = "Transp. Sta. Anita S.A.", distrito = null))
        every { oportunidadItemService.porOportunidades(any()) } returns
            mapOf(100L to listOf(itemDto(100)), 200L to listOf(itemDto(200)))
        every { oportunidadItemService.montoTotalPorOportunidades(any()) } returns
            mapOf(100L to BigDecimal.TEN, 200L to BigDecimal.TEN)
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

    /**
     * D19: el monto sale de los items, nunca de la columna plana. Si todos los items
     * estan incompletos no hay suma y el DTO devuelve `null`, sin caer a `op.montoTotal`.
     */
    @Test
    fun `listar devuelve monto nulo si ningun item aporta suma`() {
        every { contactoOportunidadRepository.findByIdIdContacto(5) } returns listOf(vinculo(100))
        every { oportunidadRepository.findAllById(listOf(100L)) } returns listOf(oportunidad(id = 100))
        every { empresaService.resumenPorIds(any()) } returns
            mapOf(10L to EmpresaResumen(id = 10, razonSocial = "Transp. Sta. Anita S.A.", distrito = null))
        every { oportunidadItemService.porOportunidades(any()) } returns mapOf(100L to listOf(itemDto(100)))
        every { oportunidadItemService.montoTotalPorOportunidades(any()) } returns emptyMap()

        val resultado = service.listar(5, admin)

        assertThat(resultado.first().montoTotal).isNull()
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

    /**
     * El listado de contactos pedia el conteo fila a fila (~101 consultas con
     * per_page=100). El conteo por lote conserva exactamente la misma regla de
     * visibilidad: `filtroVendedor` null = supervisor, cuenta todas.
     */
    @Test
    fun `contarPorContactos resuelve toda la pagina en una sola consulta`() {
        every { contactoOportunidadRepository.contarVisiblesPorContactos(setOf(7L, 8L, 9L), null) } returns
            listOf(conteo(7, 2), conteo(9, 5))

        val conteos = service.contarPorContactos(listOf(7, 8, 9), admin)

        assertThat(conteos).containsExactlyInAnyOrderEntriesOf(mapOf(7L to 2, 9L to 5))
        io.mockk.verify(exactly = 1) { contactoOportunidadRepository.contarVisiblesPorContactos(any(), any()) }
    }

    @Test
    fun `contarPorContactos con la lista vacia no consulta nada`() {
        val conteos = service.contarPorContactos(emptyList(), admin)

        assertThat(conteos).isEmpty()
        io.mockk.verify(exactly = 0) { contactoOportunidadRepository.contarVisiblesPorContactos(any(), any()) }
    }

    @Test
    fun `un vendedor solo cuenta las oportunidades que alcanza`() {
        every { contactoOportunidadRepository.contarVisiblesPorContactos(setOf(7L), 7L) } returns listOf(conteo(7, 1))

        val conteos = service.contarPorContactos(listOf(7), vendedor)

        assertThat(conteos).isEqualTo(mapOf(7L to 1))
    }

    private fun conteo(
        idContactoValor: Long,
        totalValor: Long,
    ) = object : ConteoPorContacto {
        override val idContacto = idContactoValor
        override val total = totalValor
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
