package pe.quantum.crm.domain.inicio

import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import pe.quantum.crm.domain.contactos.dto.ContactoResumen
import pe.quantum.crm.domain.empleados.EmpleadoService
import pe.quantum.crm.domain.empleados.RolEmpleado
import pe.quantum.crm.domain.empresas.dto.EmpresaResumen
import pe.quantum.crm.domain.metasventa.MetaVentaService
import pe.quantum.crm.domain.metasventa.dto.MetaVentaResumen
import pe.quantum.crm.domain.prospeccion.ProspeccionService
import pe.quantum.crm.domain.prospeccion.dto.ResumenProspeccionDto
import pe.quantum.crm.domain.tareas.TareaService
import pe.quantum.crm.domain.tareas.dto.TareaDto
import pe.quantum.crm.domain.tareas.dto.TareaFiltros
import pe.quantum.crm.shared.Paginacion
import pe.quantum.crm.shared.Paginado
import pe.quantum.crm.shared.enums.EstadoAccion
import pe.quantum.crm.shared.enums.EstadoOportunidad
import pe.quantum.crm.shared.enums.TipoAccion
import pe.quantum.crm.shared.security.UsuarioActual
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset

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
    fun `jdv equipo solo cuenta logradas de vendedores con meta aprobada`() {
        val jdv = UsuarioActual(id = 2, rol = "jdv")
        stubsComunes(jdv)
        every { metaVentaService.aprobadasPorEmpleadosYAnio(listOf(2L), anio) } returns emptyMap()
        every { inicioDao.unidadesFacturadasPorVendedor(listOf(2L), anio, null) } returns emptyMap()
        every { inicioDao.unidadesFacturadasPorVendedor(listOf(2L), anio, mes) } returns emptyMap()
        every { empleadoService.idsActivosPorRol(RolEmpleado.vendedor) } returns listOf(5L, 6L, 7L)
        // vendedor 7 no tiene meta aprobada este año (nuevo ingreso / propuesta pendiente o rechazada)
        every { metaVentaService.aprobadasPorEmpleadosYAnio(listOf(5L, 6L, 7L), anio) } returns
            mapOf(
                5L to MetaVentaResumen(idEmpleado = 5, anio = anio, metaAnual = 120, metaPorMes = List(12) { 10 }),
                6L to MetaVentaResumen(idEmpleado = 6, anio = anio, metaAnual = 60, metaPorMes = List(12) { 5 }),
            )
        // vendedor 7 vendió unidades igual, pese a no tener meta propia: no debe contar en el total del equipo
        every { inicioDao.unidadesFacturadasPorVendedor(listOf(5L, 6L, 7L), anio, null) } returns mapOf(5L to 60, 6L to 30, 7L to 100)
        every { inicioDao.unidadesFacturadasPorVendedor(listOf(5L, 6L, 7L), anio, mes) } returns mapOf(5L to 5, 6L to 5, 7L to 10)

        val panel = service.panel(jdv)

        assertThat(panel.metaVentas?.equipo?.anual?.unidadesMeta).isEqualTo(180)
        assertThat(panel.metaVentas?.equipo?.anual?.unidadesLogradas).isEqualTo(90)
        assertThat(panel.metaVentas?.equipo?.mensual?.unidadesLogradas).isEqualTo(10)
    }

    @Test
    fun `gerencia no ve bloque de metas de venta en inicio`() {
        val gerencia = UsuarioActual(id = 1, rol = "gerencia")
        stubsComunes(gerencia)

        val panel = service.panel(gerencia)

        assertThat(panel.metaVentas).isNull()
    }

    // ── tareas pendientes (contrato_api.md §17) ────────────────

    private fun tarea(
        id: Long,
        fechaEjecucion: Instant?,
    ) = TareaDto(
        id = id,
        idEmpresa = 10,
        empresa = EmpresaResumen(id = 10, razonSocial = "Transportes ABC", distrito = "Ate"),
        idOportunidad = 45,
        idContacto = 7,
        contacto = ContactoResumen(id = 7, nombres = "Hugo", apellidos = "Rodriguez", tlf_1 = "964415122"),
        idAsignado = 5,
        asignado = null,
        idsColaboradores = emptyList(),
        colaboradores = emptyList(),
        tipoAccion = TipoAccion.llamada.name,
        estadoAccion = EstadoAccion.pendiente.name,
        descripcion = "Llamar para confirmar la visita",
        fechaEjecucion = fechaEjecucion,
        createdAt = Instant.now(),
    )

    /**
     * El panel solo pide tareas PENDIENTES, ordenadas por fecha de ejecucion
     * ascendente y acotadas: es un resumen, no el listado de /tareas.
     */
    @Test
    fun `el panel pide las tareas pendientes ordenadas por fecha y acotadas`() {
        val gerencia = UsuarioActual(id = 1, rol = "gerencia")
        stubsComunes(gerencia)
        val filtros = slot<TareaFiltros>()
        every {
            tareaService.listar(capture(filtros), gerencia, any(), any(), any(), any())
        } returns Paginado(emptyList(), Paginacion.meta(1, 50, 0))

        service.panel(gerencia)

        assertThat(filtros.captured.estadoAccion).isEqualTo(EstadoAccion.pendiente.name)
        verify { tareaService.listar(any(), gerencia, 1, 50, "fechaEjecucion", "asc") }
    }

    @Test
    fun `el panel marca la tarea de hoy y arrastra empresa, contacto y oportunidad`() {
        val gerencia = UsuarioActual(id = 1, rol = "gerencia")
        stubsComunes(gerencia)
        // Mediodia UTC del dia que el servicio considera "hoy": la comparacion es
        // exactamente la que hace `esHoy`, asi que no depende de la zona de la JVM.
        val hoyUtc = LocalDate.now().atTime(LocalTime.NOON).toInstant(ZoneOffset.UTC)
        every { tareaService.listar(any<TareaFiltros>(), gerencia, any(), any(), any(), any()) } returns
            Paginado(listOf(tarea(id = 1, fechaEjecucion = hoyUtc)), Paginacion.meta(1, 50, 1))

        val pendientes = service.panel(gerencia).tareasPendientes

        assertThat(pendientes).hasSize(1)
        val dto = pendientes.first()
        assertThat(dto.id).isEqualTo(1L)
        assertThat(dto.esHoy).isTrue()
        assertThat(dto.descripcion).isEqualTo("Llamar para confirmar la visita")
        assertThat(dto.tipoAccion).isEqualTo(TipoAccion.llamada.name)
        assertThat(dto.empresa?.razonSocial).isEqualTo("Transportes ABC")
        assertThat(dto.contacto?.nombres).isEqualTo("Hugo")
        assertThat(dto.idOportunidad).isEqualTo(45L)
    }

    @Test
    fun `esta vencida solo la tarea con fecha pasada, y una sin fecha no vence`() {
        val gerencia = UsuarioActual(id = 1, rol = "gerencia")
        stubsComunes(gerencia)
        val tresDias = 3L * 24 * 60 * 60
        every { tareaService.listar(any<TareaFiltros>(), gerencia, any(), any(), any(), any()) } returns
            Paginado(
                listOf(
                    tarea(id = 1, fechaEjecucion = Instant.now().minusSeconds(tresDias)),
                    tarea(id = 2, fechaEjecucion = Instant.now().plusSeconds(tresDias)),
                    tarea(id = 3, fechaEjecucion = null),
                ),
                Paginacion.meta(1, 50, 3),
            )

        val pendientes = service.panel(gerencia).tareasPendientes

        assertThat(pendientes.map { it.estaVencida }).containsExactly(true, false, false)
        // Ni la de hace tres dias ni la de dentro de tres dias son de hoy.
        assertThat(pendientes.map { it.esHoy }).containsExactly(false, false, false)
        assertThat(pendientes.last().fechaEjecucion).isNull()
    }

    // ── resumen de pipeline (contrato_api.md §17) ──────────────

    @Test
    fun `el resumen de pipeline suma solo las etapas previas al cierre`() {
        val gerencia = UsuarioActual(id = 1, rol = "gerencia")
        stubsComunes(gerencia)
        every { inicioDao.resumenPipeline(null) } returns
            listOf(
                EtapaPipelineRow(EstadoOportunidad.evaluacion_calidda.name, count = 2, valor = BigDecimal("1000.00"), cantidadUnidades = 4),
                EtapaPipelineRow(EstadoOportunidad.documentos_legales.name, count = 1, valor = BigDecimal("500.50"), cantidadUnidades = 2),
                EtapaPipelineRow(EstadoOportunidad.facturado.name, count = 3, valor = BigDecimal("9000.00"), cantidadUnidades = 6),
            )

        val resumen = service.panel(gerencia).resumenPipeline

        // `facturado` ya es una venta cerrada: no cuenta como pipeline activo...
        assertThat(resumen.valorTotal).isEqualTo("1500.50")
        assertThat(resumen.oportunidadesActivas).isEqualTo(3)
        assertThat(resumen.cantidadUnidades).isEqualTo(6)
        // ...pero si aparece en el desglose por etapa.
        assertThat(resumen.porEtapa).containsOnlyKeys(
            EstadoOportunidad.evaluacion_calidda.name,
            EstadoOportunidad.documentos_legales.name,
            EstadoOportunidad.facturado.name,
        )
        assertThat(resumen.porEtapa.getValue(EstadoOportunidad.facturado.name).valor).isEqualTo("9000.00")
        assertThat(resumen.porEtapa.getValue(EstadoOportunidad.facturado.name).count).isEqualTo(3)
        assertThat(resumen.porEtapa.getValue(EstadoOportunidad.evaluacion_calidda.name).cantidadUnidades).isEqualTo(4)
    }

    @Test
    fun `sin oportunidades el pipeline queda en cero y sin etapas`() {
        val gerencia = UsuarioActual(id = 1, rol = "gerencia")
        stubsComunes(gerencia)

        val resumen = service.panel(gerencia).resumenPipeline

        assertThat(resumen.valorTotal).isEqualTo("0")
        assertThat(resumen.oportunidadesActivas).isZero()
        assertThat(resumen.cantidadUnidades).isZero()
        assertThat(resumen.porEtapa).isEmpty()
    }

    /** Vendedor y analista solo ven lo suyo; supervisores ven todo (contrato §5). */
    @Test
    fun `el panel filtra por vendedor solo para los roles de visibilidad restringida`() {
        val vendedor = UsuarioActual(id = 5, rol = "vendedor")
        stubsComunes(vendedor)
        every { metaVentaService.aprobadasPorEmpleadosYAnio(listOf(5L), anio) } returns emptyMap()
        every { inicioDao.unidadesFacturadasPorVendedor(listOf(5L), anio, null) } returns emptyMap()
        every { inicioDao.unidadesFacturadasPorVendedor(listOf(5L), anio, mes) } returns emptyMap()

        service.panel(vendedor)

        verify { inicioDao.resumenPipeline(5L) }
        verify { inicioDao.eventosPorSeguir(5L) }
    }
}
