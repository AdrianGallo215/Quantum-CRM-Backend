package pe.quantum.crm.domain.inicio

import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import pe.quantum.crm.domain.empleados.EmpleadoService
import pe.quantum.crm.domain.empleados.RolEmpleado
import pe.quantum.crm.domain.metasventa.MetaVentaService
import pe.quantum.crm.domain.metasventa.dto.MetaVentaResumen
import pe.quantum.crm.domain.prospeccion.ProspeccionService
import pe.quantum.crm.domain.prospeccion.dto.ResumenProspeccionDto
import pe.quantum.crm.domain.tareas.TareaService
import pe.quantum.crm.domain.tareas.dto.TareaFiltros
import pe.quantum.crm.shared.Paginacion
import pe.quantum.crm.shared.Paginado
import pe.quantum.crm.shared.security.UsuarioActual
import java.time.LocalDate

class InicioServiceImplTest {
    private val tareaService = mockk<TareaService>()
    private val prospeccionService = mockk<ProspeccionService>()
    private val inicioDao = mockk<InicioDao>()
    private val empleadoService = mockk<EmpleadoService>()
    private val metaVentaService = mockk<MetaVentaService>()
    private val service = InicioService(tareaService, prospeccionService, inicioDao, empleadoService, metaVentaService)

    private val anio = LocalDate.now().year
    private val mes = LocalDate.now().monthValue

    private fun stubsComunes(usuario: UsuarioActual) {
        every { tareaService.listar(any<TareaFiltros>(), usuario, any(), any(), any(), any()) } returns
            Paginado(emptyList(), Paginacion.meta(1, 50, 0))
        every { inicioDao.eventosPorSeguir(any()) } returns emptyList()
        every { inicioDao.resumenPipeline(any()) } returns emptyList()
        every { prospeccionService.resumen(usuario) } returns
            ResumenProspeccionDto(total = 0, listasParaConvertir = 0, requierenAtencion = 0)
    }

    @Test
    fun `vendedor sin meta aprobada ve el medidor en estado sin meta`() {
        val vendedor = UsuarioActual(id = 5, rol = "vendedor")
        stubsComunes(vendedor)
        every { metaVentaService.aprobadasPorEmpleadosYAnio(listOf(5L), anio) } returns emptyMap()
        every { inicioDao.unidadesFacturadasPorVendedor(listOf(5L), anio, null) } returns mapOf(5L to 3)
        every { inicioDao.unidadesFacturadasPorVendedor(listOf(5L), anio, mes) } returns mapOf(5L to 1)

        val panel = service.panel(vendedor)

        assertThat(panel.metaVentas?.mensual?.tieneMeta).isFalse()
        assertThat(panel.metaVentas?.mensual?.unidadesLogradas).isEqualTo(1)
        assertThat(panel.metaVentas?.anual?.unidadesLogradas).isEqualTo(3)
        assertThat(panel.metaVentas?.equipo).isNull()
    }

    @Test
    fun `vendedor con meta aprobada calcula el porcentaje del mes`() {
        val vendedor = UsuarioActual(id = 5, rol = "vendedor")
        stubsComunes(vendedor)
        every { metaVentaService.aprobadasPorEmpleadosYAnio(listOf(5L), anio) } returns
            mapOf(5L to MetaVentaResumen(idEmpleado = 5, anio = anio, metaAnual = 120, metaPorMes = List(12) { 10 }))
        every { inicioDao.unidadesFacturadasPorVendedor(listOf(5L), anio, null) } returns mapOf(5L to 60)
        every { inicioDao.unidadesFacturadasPorVendedor(listOf(5L), anio, mes) } returns mapOf(5L to 5)

        val panel = service.panel(vendedor)

        assertThat(panel.metaVentas?.mensual?.tieneMeta).isTrue()
        assertThat(panel.metaVentas?.mensual?.porcentaje).isEqualTo(50)
        assertThat(panel.metaVentas?.anual?.porcentaje).isEqualTo(50)
    }

    @Test
    fun `jdv ve su meta personal y el agregado del equipo`() {
        val jdv = UsuarioActual(id = 2, rol = "jdv")
        stubsComunes(jdv)
        every { metaVentaService.aprobadasPorEmpleadosYAnio(listOf(2L), anio) } returns emptyMap()
        every { inicioDao.unidadesFacturadasPorVendedor(listOf(2L), anio, null) } returns emptyMap()
        every { inicioDao.unidadesFacturadasPorVendedor(listOf(2L), anio, mes) } returns emptyMap()
        every { empleadoService.idsActivosPorRol(RolEmpleado.vendedor) } returns listOf(5L, 6L)
        every { metaVentaService.aprobadasPorEmpleadosYAnio(listOf(5L, 6L), anio) } returns
            mapOf(
                5L to MetaVentaResumen(idEmpleado = 5, anio = anio, metaAnual = 120, metaPorMes = List(12) { 10 }),
                6L to MetaVentaResumen(idEmpleado = 6, anio = anio, metaAnual = 60, metaPorMes = List(12) { 5 }),
            )
        every { inicioDao.unidadesFacturadasPorVendedor(listOf(5L, 6L), anio, null) } returns mapOf(5L to 60, 6L to 30)
        every { inicioDao.unidadesFacturadasPorVendedor(listOf(5L, 6L), anio, mes) } returns mapOf(5L to 5, 6L to 5)

        val panel = service.panel(jdv)

        assertThat(panel.metaVentas?.equipo?.anual?.unidadesMeta).isEqualTo(180)
        assertThat(panel.metaVentas?.equipo?.anual?.unidadesLogradas).isEqualTo(90)
        assertThat(panel.metaVentas?.equipo?.mensual?.unidadesMeta).isEqualTo(15)
        assertThat(panel.metaVentas?.equipo?.mensual?.unidadesLogradas).isEqualTo(10)
    }

    @Test
    fun `gerencia no ve bloque de metas de venta en inicio`() {
        val gerencia = UsuarioActual(id = 1, rol = "gerencia")
        stubsComunes(gerencia)

        val panel = service.panel(gerencia)

        assertThat(panel.metaVentas).isNull()
    }
}
