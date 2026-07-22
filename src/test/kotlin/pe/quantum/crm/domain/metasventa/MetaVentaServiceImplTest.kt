package pe.quantum.crm.domain.metasventa

import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import pe.quantum.crm.domain.empleados.EmpleadoService
import pe.quantum.crm.domain.empleados.RolEmpleado
import pe.quantum.crm.domain.metasventa.dto.CrearMetaVentaRequest
import pe.quantum.crm.domain.metasventa.dto.EditarMetaVentaRequest
import pe.quantum.crm.domain.notificaciones.EntidadNotificacion
import pe.quantum.crm.domain.notificaciones.NotificacionService
import pe.quantum.crm.domain.notificaciones.TipoNotificacion
import pe.quantum.crm.shared.enums.EstadoMeta
import pe.quantum.crm.shared.exception.ConflictoException
import pe.quantum.crm.shared.exception.PermisoInsuficienteException
import pe.quantum.crm.shared.exception.ValidacionException
import pe.quantum.crm.shared.security.UsuarioActual

class MetaVentaServiceImplTest {
    private val metaVentaRepository = mockk<MetaVentaRepository>()
    private val empleadoService = mockk<EmpleadoService>()
    private val notificacionService = mockk<NotificacionService>(relaxed = true)
    private val service = MetaVentaServiceImpl(metaVentaRepository, empleadoService, notificacionService)

    private val jdv = UsuarioActual(id = 2, rol = "jdv")
    private val gerencia = UsuarioActual(id = 1, rol = "gerencia")
    private val vendedor = UsuarioActual(id = 5, rol = "vendedor")

    private fun requestAnioCompleto(
        idEmpleado: Long = 5,
        anio: Int = 2027,
        valorMes: Int = 10,
    ) = CrearMetaVentaRequest(
        idEmpleado = idEmpleado, anio = anio,
        metaEnero = valorMes, metaFebrero = valorMes, metaMarzo = valorMes, metaAbril = valorMes,
        metaMayo = valorMes, metaJunio = valorMes, metaJulio = valorMes, metaAgosto = valorMes,
        metaSeptiembre = valorMes, metaOctubre = valorMes, metaNoviembre = valorMes, metaDiciembre = valorMes,
    )

    /** JPA asigna el id al guardar (IDENTITY); el mock lo simula con una copia, igual que en SolicitudServiceImplTest. */
    private fun MetaVenta.conId(nuevoId: Long) =
        MetaVenta(
            id = nuevoId,
            idEmpleado = idEmpleado,
            anio = anio,
            estado = estado,
            idPropuestoPor = idPropuestoPor,
            idResolutor = idResolutor,
            motivoRechazo = motivoRechazo,
            resolvedAt = resolvedAt,
            createdAt = createdAt,
            updatedAt = updatedAt,
        ).also { it.establecerMeses(meses()) }

    @Test
    fun `jdv propone meta nueva de un vendedor - queda propuesta y notifica a gerencia`() {
        every { empleadoService.esAsignableComoVendedor(5) } returns true
        every { metaVentaRepository.findByIdEmpleadoAndAnio(5, 2027) } returns null
        val guardada = slot<MetaVenta>()
        every { metaVentaRepository.save(capture(guardada)) } answers { guardada.captured.conId(50) }
        every { empleadoService.idsActivosPorRol(RolEmpleado.gerencia) } returns listOf(1)
        every { empleadoService.resumenPorIds(any()) } returns emptyMap()

        val dto = service.crear(requestAnioCompleto(), jdv)

        assertThat(dto.estado).isEqualTo("propuesta")
        assertThat(dto.metaAnual).isEqualTo(120)
        verify {
            notificacionService.notificar(
                destinatarios = setOf(1L),
                idActor = 2,
                tipo = TipoNotificacion.meta_propuesta,
                mensaje = any(),
                entidadTipo = EntidadNotificacion.meta_venta,
                entidadId = any(),
            )
        }
    }

    @Test
    fun `jdv proponer sobre una meta ya propuesta es 409 META_YA_EXISTE`() {
        every { empleadoService.esAsignableComoVendedor(5) } returns true
        val existente = MetaVenta(idEmpleado = 5, anio = 2027, idPropuestoPor = 2).apply { establecerMeses(List(12) { 10 }) }
        every { metaVentaRepository.findByIdEmpleadoAndAnio(5, 2027) } returns existente
        assertThatThrownBy { service.crear(requestAnioCompleto(), jdv) }
            .isInstanceOf(ConflictoException::class.java)
    }

    @Test
    fun `gerencia crea meta directo y queda aprobada`() {
        every { empleadoService.esAsignableComoVendedor(5) } returns true
        every { metaVentaRepository.findByIdEmpleadoAndAnio(5, 2027) } returns null
        val guardada = slot<MetaVenta>()
        every { metaVentaRepository.save(capture(guardada)) } answers { guardada.captured.conId(51) }
        every { empleadoService.resumenPorIds(any()) } returns emptyMap()

        val dto = service.crear(requestAnioCompleto(), gerencia)

        assertThat(dto.estado).isEqualTo("aprobada")
        assertThat(dto.resolvedAt).isNotNull()
        verify {
            notificacionService.notificar(
                destinatarios = setOf(1L, 5L),
                idActor = 1,
                tipo = TipoNotificacion.meta_modificada,
                mensaje = any(),
                entidadTipo = EntidadNotificacion.meta_venta,
                entidadId = any(),
            )
        }
    }

    @Test
    fun `gerencia sobreescribe una propuesta pendiente sin 409`() {
        every { empleadoService.esAsignableComoVendedor(5) } returns true
        val existente = MetaVenta(id = 9, idEmpleado = 5, anio = 2027, idPropuestoPor = 2).apply { establecerMeses(List(12) { 10 }) }
        every { metaVentaRepository.findByIdEmpleadoAndAnio(5, 2027) } returns existente
        every { metaVentaRepository.save(existente) } returns existente
        every { empleadoService.resumenPorIds(any()) } returns emptyMap()

        val dto = service.crear(requestAnioCompleto(valorMes = 20), gerencia)

        assertThat(dto.estado).isEqualTo("aprobada")
        assertThat(dto.metaAnual).isEqualTo(240)
    }

    @Test
    fun `vendedor no puede proponer metas`() {
        assertThatThrownBy { service.crear(requestAnioCompleto(), vendedor) }
            .isInstanceOf(PermisoInsuficienteException::class.java)
    }

    @Test
    fun `editar un mes especifico recalcula el anual y auto-aprueba`() {
        val meta = MetaVenta(id = 9, idEmpleado = 5, anio = 2027, idPropuestoPor = 2).apply { establecerMeses(List(12) { 10 }) }
        every { metaVentaRepository.findById(9) } returns java.util.Optional.of(meta)
        every { metaVentaRepository.save(meta) } returns meta
        every { empleadoService.resumenPorIds(any()) } returns emptyMap()

        val dto = service.editar(9, EditarMetaVentaRequest(metaMarzo = 50), gerencia)

        assertThat(dto.metaMarzo).isEqualTo(50)
        assertThat(dto.metaAnual).isEqualTo(160) // 11*10 + 50
        assertThat(dto.estado).isEqualTo("aprobada")
    }

    @Test
    fun `aprobar una ya resuelta es 409 META_YA_RESUELTA`() {
        val meta =
            MetaVenta(id = 9, idEmpleado = 5, anio = 2027, idPropuestoPor = 2, estado = EstadoMeta.aprobada)
                .apply { establecerMeses(List(12) { 10 }) }
        every { metaVentaRepository.findByIdForUpdate(9) } returns meta
        assertThatThrownBy { service.aprobar(9, gerencia) }
            .isInstanceOf(ConflictoException::class.java)
            .hasMessageContaining("resuelta")
    }

    @Test
    fun `rechazar exige motivo y notifica al proponente y al empleado`() {
        val meta = MetaVenta(id = 9, idEmpleado = 5, anio = 2027, idPropuestoPor = 2).apply { establecerMeses(List(12) { 10 }) }
        every { metaVentaRepository.findByIdForUpdate(9) } returns meta
        every { metaVentaRepository.save(meta) } returns meta
        every { empleadoService.resumenPorIds(any()) } returns emptyMap()

        val dto = service.rechazar(9, "Marzo está muy alto respecto al histórico", gerencia)

        assertThat(dto.estado).isEqualTo("rechazada")
        assertThat(dto.motivoRechazo).isEqualTo("Marzo está muy alto respecto al histórico")
        verify {
            notificacionService.notificar(
                destinatarios = setOf(2L, 5L),
                idActor = 1,
                tipo = TipoNotificacion.meta_rechazada,
                mensaje = match { it.contains("Marzo está muy alto") },
                entidadTipo = EntidadNotificacion.meta_venta,
                entidadId = 9L,
            )
        }
    }

    @Test
    fun `rechazar con motivo en blanco es VALIDACION`() {
        assertThatThrownBy { service.rechazar(9, "  ", gerencia) }
            .isInstanceOf(ValidacionException::class.java)
    }

    @Test
    fun `detalle de meta ajena para vendedor es 404`() {
        val meta = MetaVenta(id = 9, idEmpleado = 99, anio = 2027, idPropuestoPor = 2).apply { establecerMeses(List(12) { 10 }) }
        every { metaVentaRepository.findById(9) } returns java.util.Optional.of(meta)
        assertThatThrownBy { service.detalle(9, vendedor) }
            .isInstanceOf(pe.quantum.crm.shared.exception.NoEncontradoException::class.java)
    }

    @Test
    fun `aprobadasPorEmpleadosYAnio agrega solo las aprobadas del anio`() {
        val aprobada =
            MetaVenta(idEmpleado = 5, anio = 2027, idPropuestoPor = 2, estado = EstadoMeta.aprobada).apply {
                establecerMeses(List(12) { 10 })
            }
        every { metaVentaRepository.findByIdEmpleadoInAndAnioAndEstado(listOf(5L, 6L), 2027, EstadoMeta.aprobada) } returns listOf(aprobada)

        val resultado = service.aprobadasPorEmpleadosYAnio(listOf(5L, 6L), 2027)

        assertThat(resultado).containsOnlyKeys(5L)
        assertThat(resultado.getValue(5L).metaAnual).isEqualTo(120)
        assertThat(resultado.getValue(5L).metaPorMes).hasSize(12)
    }
}
