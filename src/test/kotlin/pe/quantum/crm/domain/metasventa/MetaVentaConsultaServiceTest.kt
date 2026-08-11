package pe.quantum.crm.domain.metasventa

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.data.domain.PageImpl
import org.springframework.data.domain.PageRequest
import org.springframework.data.jpa.domain.Specification
import pe.quantum.crm.domain.empleados.EmpleadoService
import pe.quantum.crm.domain.empleados.dto.EmpleadoResumen
import pe.quantum.crm.domain.metasventa.dto.CrearMetaVentaRequest
import pe.quantum.crm.domain.metasventa.dto.EditarMetaVentaRequest
import pe.quantum.crm.domain.metasventa.dto.MetaVentaFiltros
import pe.quantum.crm.domain.notificaciones.NotificacionService
import pe.quantum.crm.shared.enums.EstadoMeta
import pe.quantum.crm.shared.exception.NoEncontradoException
import pe.quantum.crm.shared.exception.PermisoInsuficienteException
import pe.quantum.crm.shared.exception.ValidacionException
import pe.quantum.crm.shared.security.UsuarioActual
import java.util.Optional

/**
 * Listado, visibilidad y bordes de las metas de venta.
 *
 * Complementa a MetaVentaServiceImplTest (proponer/aprobar/rechazar/editar).
 * Aqui se fija quien ve que meta — vendedor y analista solo la suya, el resto
 * ve todo el equipo — y los caminos de error que faltaban.
 */
class MetaVentaConsultaServiceTest {
    private val metaVentaRepository = mockk<MetaVentaRepository>()
    private val empleadoService = mockk<EmpleadoService>()
    private val notificacionService = mockk<NotificacionService>(relaxed = true)
    private val service = MetaVentaServiceImpl(metaVentaRepository, empleadoService, notificacionService)

    private val admin = UsuarioActual(id = 8, rol = "admin")
    private val gerencia = UsuarioActual(id = 1, rol = "gerencia")
    private val jdv = UsuarioActual(id = 2, rol = "jdv")
    private val vendedor = UsuarioActual(id = 5, rol = "vendedor")
    private val analista = UsuarioActual(id = 6, rol = "analista")

    private fun meta(
        id: Long = 9,
        idEmpleado: Long = 5,
        estado: EstadoMeta = EstadoMeta.propuesta,
        valorMes: Int = 10,
    ) = MetaVenta(id = id, idEmpleado = idEmpleado, anio = 2027, idPropuestoPor = 2, estado = estado)
        .apply { establecerMeses(List(12) { valorMes }) }

    private fun requestAnioCompleto(
        idEmpleado: Long = 5,
        valorMes: Int = 10,
    ) = CrearMetaVentaRequest(
        idEmpleado = idEmpleado, anio = 2027,
        metaEnero = valorMes, metaFebrero = valorMes, metaMarzo = valorMes, metaAbril = valorMes,
        metaMayo = valorMes, metaJunio = valorMes, metaJulio = valorMes, metaAgosto = valorMes,
        metaSeptiembre = valorMes, metaOctubre = valorMes, metaNoviembre = valorMes, metaDiciembre = valorMes,
    )

    // ── listar ────────────────────────────────────────────────

    @Test
    fun `listar ensambla los DTOs con el empleado resuelto y los metadatos de paginacion`() {
        val fila = meta(estado = EstadoMeta.aprobada).apply { idResolutor = 1 }
        every { metaVentaRepository.findAll(any<Specification<MetaVenta>>(), any<PageRequest>()) } returns
            PageImpl(listOf(fila), PageRequest.of(0, 20), 1)
        every { empleadoService.resumenPorIds(any()) } returns
            mapOf(
                5L to EmpleadoResumen(id = 5, nombres = "Ana", apellidos = "Diaz"),
                2L to EmpleadoResumen(id = 2, nombres = "Beto", apellidos = "Ruiz"),
                1L to EmpleadoResumen(id = 1, nombres = "Carla", apellidos = "Sosa"),
            )

        val resultado = service.listar(MetaVentaFiltros(), gerencia, null, null, null, null)

        assertThat(resultado.items).hasSize(1)
        assertThat(resultado.items.first().empleado?.nombres).isEqualTo("Ana")
        assertThat(resultado.items.first().propuestoPor?.nombres).isEqualTo("Beto")
        assertThat(resultado.items.first().resolutor?.nombres).isEqualTo("Carla")
        assertThat(resultado.items.first().metaAnual).isEqualTo(120)
        assertThat(resultado.meta.page).isEqualTo(1)
        assertThat(resultado.meta.total).isEqualTo(1L)
        // Un solo viaje a empleados para toda la pagina (sin N+1).
        verify(exactly = 1) { empleadoService.resumenPorIds(any()) }
    }

    @Test
    fun `listar sin resultados no consulta empleados`() {
        every { metaVentaRepository.findAll(any<Specification<MetaVenta>>(), any<PageRequest>()) } returns
            PageImpl(emptyList(), PageRequest.of(0, 20), 0)

        val resultado = service.listar(MetaVentaFiltros(), gerencia, null, null, null, null)

        assertThat(resultado.items).isEmpty()
        assertThat(resultado.meta.totalPages).isZero()
        verify(exactly = 0) { empleadoService.resumenPorIds(any()) }
    }

    // ── visible (IDOR: meta ajena = 404, no 403) ──────────────

    @Test
    fun `admin, gerencia y jdv ven la meta de cualquier empleado`() {
        every { metaVentaRepository.findById(9) } returns Optional.of(meta(idEmpleado = 99))
        every { empleadoService.resumenPorIds(any()) } returns emptyMap()

        listOf(admin, gerencia, jdv).forEach { usuario ->
            assertThat(service.detalle(9, usuario).id)
                .describedAs("el rol '%s' ve toda la cartera de metas", usuario.rol)
                .isEqualTo(9L)
        }
    }

    @Test
    fun `un vendedor si ve su propia meta`() {
        every { metaVentaRepository.findById(9) } returns Optional.of(meta(idEmpleado = 5))
        every { empleadoService.resumenPorIds(any()) } returns emptyMap()

        assertThat(service.detalle(9, vendedor).idEmpleado).isEqualTo(5L)
    }

    @Test
    fun `un analista no ve la meta de otro`() {
        every { metaVentaRepository.findById(9) } returns Optional.of(meta(idEmpleado = 5))

        assertThatThrownBy { service.detalle(9, analista) }.isInstanceOf(NoEncontradoException::class.java)
    }

    @Test
    fun `el detalle de una meta inexistente es 404`() {
        every { metaVentaRepository.findById(404) } returns Optional.empty()

        assertThatThrownBy { service.detalle(404, gerencia) }.isInstanceOf(NoEncontradoException::class.java)
    }

    // ── bordes de crear / editar / resolver ───────────────────

    @Test
    fun `proponer una meta para alguien que no es vendedor ni jdv activo es VALIDACION`() {
        every { empleadoService.esAsignableComoVendedor(99) } returns false

        assertThatThrownBy { service.crear(requestAnioCompleto(idEmpleado = 99), jdv) }
            .isInstanceOf(ValidacionException::class.java)
        verify(exactly = 0) { metaVentaRepository.save(any()) }
    }

    @Test
    fun `el jdv si puede volver a proponer sobre una meta rechazada`() {
        every { empleadoService.esAsignableComoVendedor(5) } returns true
        val rechazada = meta(estado = EstadoMeta.rechazada).apply { motivoRechazo = "Muy alta" }
        every { metaVentaRepository.findByIdEmpleadoAndAnio(5, 2027) } returns rechazada
        every { metaVentaRepository.save(rechazada) } returns rechazada
        every { empleadoService.idsActivosPorRol(any()) } returns listOf(1L)
        every { empleadoService.resumenPorIds(any()) } returns emptyMap()

        val dto = service.crear(requestAnioCompleto(valorMes = 20), jdv)

        // Vuelve al ciclo limpio: propuesta, sin resolutor ni motivo de rechazo previos.
        assertThat(dto.estado).isEqualTo("propuesta")
        assertThat(dto.metaAnual).isEqualTo(240)
        assertThat(dto.motivoRechazo).isNull()
        assertThat(dto.resolutor).isNull()
        assertThat(dto.resolvedAt).isNull()
    }

    @Test
    fun `resolver metas es exclusivo de gerencia y admin`() {
        assertThatThrownBy { service.aprobar(9, jdv) }.isInstanceOf(PermisoInsuficienteException::class.java)
        assertThatThrownBy { service.rechazar(9, "no", jdv) }.isInstanceOf(PermisoInsuficienteException::class.java)
        assertThatThrownBy { service.editar(9, EditarMetaVentaRequest(metaMarzo = 50), vendedor) }
            .isInstanceOf(PermisoInsuficienteException::class.java)
        verify(exactly = 0) { metaVentaRepository.findByIdForUpdate(any()) }
        verify(exactly = 0) { metaVentaRepository.findById(any()) }
    }

    @Test
    fun `aprobar una meta inexistente es 404`() {
        every { metaVentaRepository.findByIdForUpdate(404) } returns null

        assertThatThrownBy { service.aprobar(404, gerencia) }.isInstanceOf(NoEncontradoException::class.java)
    }

    @Test
    fun `editar una meta inexistente es 404`() {
        every { metaVentaRepository.findById(404) } returns Optional.empty()

        assertThatThrownBy { service.editar(404, EditarMetaVentaRequest(metaMarzo = 50), gerencia) }
            .isInstanceOf(NoEncontradoException::class.java)
    }

    @Test
    fun `editar acepta los doce meses en una sola llamada y recalcula el anual`() {
        val entidad = meta(estado = EstadoMeta.aprobada)
        every { metaVentaRepository.findById(9) } returns Optional.of(entidad)
        every { metaVentaRepository.save(entidad) } returns entidad
        every { empleadoService.resumenPorIds(any()) } returns emptyMap()

        val dto =
            service.editar(
                9,
                EditarMetaVentaRequest(
                    metaEnero = 1, metaFebrero = 2, metaMarzo = 3, metaAbril = 4,
                    metaMayo = 5, metaJunio = 6, metaJulio = 7, metaAgosto = 8,
                    metaSeptiembre = 9, metaOctubre = 10, metaNoviembre = 11, metaDiciembre = 12,
                ),
                admin,
            )

        assertThat(dto.metaEnero).isEqualTo(1)
        assertThat(dto.metaDiciembre).isEqualTo(12)
        // `meta_anual` es calculado, nunca input: 1+2+...+12.
        assertThat(dto.metaAnual).isEqualTo(78)
    }

    @Test
    fun `aprobadasPorEmpleadosYAnio sin empleados no consulta la base`() {
        assertThat(service.aprobadasPorEmpleadosYAnio(emptyList(), 2027)).isEmpty()

        verify(exactly = 0) { metaVentaRepository.findByIdEmpleadoInAndAnioAndEstado(any(), any(), any()) }
    }
}
