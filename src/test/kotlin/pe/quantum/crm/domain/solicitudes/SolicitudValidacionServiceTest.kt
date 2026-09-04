package pe.quantum.crm.domain.solicitudes

import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import pe.quantum.crm.domain.empleados.EmpleadoService
import pe.quantum.crm.domain.empresas.EmpresaService
import pe.quantum.crm.domain.notificaciones.NotificacionService
import pe.quantum.crm.domain.oportunidades.OportunidadItemService
import pe.quantum.crm.domain.oportunidades.dto.OportunidadItemVinculo
import pe.quantum.crm.domain.solicitudes.dto.CrearSolicitudRequest
import pe.quantum.crm.shared.enums.AprobadorSolicitud
import pe.quantum.crm.shared.enums.EntidadSolicitud
import pe.quantum.crm.shared.enums.TipoSolicitud
import pe.quantum.crm.shared.exception.ConflictoException
import pe.quantum.crm.shared.exception.NoEncontradoException
import pe.quantum.crm.shared.exception.ValidacionException
import pe.quantum.crm.shared.security.UsuarioActual
import java.math.BigDecimal
import java.util.Optional

/**
 * Guardas de creacion, visibilidad por bandeja y traduccion de fallos al aplicar
 * una reasignacion aprobada.
 *
 * Complementa a SolicitudServiceImplTest (caminos felices y resolucion). Todo lo
 * que se prueba aqui es el borde: cuerpos incoherentes, solicitudes fuera del
 * alcance del rol (que deben responder 404 y no 403, CLAUDE.md regla 14) y el
 * caso en que el mundo cambio entre la solicitud y su aprobacion.
 */
class SolicitudValidacionServiceTest {
    private val solicitudRepository = mockk<SolicitudRepository>()
    private val oportunidadItemService = mockk<OportunidadItemService>()
    private val empresaService = mockk<EmpresaService>()
    private val empleadoService = mockk<EmpleadoService>()
    private val notificacionService = mockk<NotificacionService>(relaxed = true)
    private val service =
        SolicitudServiceImpl(solicitudRepository, oportunidadItemService, empresaService, empleadoService, notificacionService)

    private val admin = UsuarioActual(id = 1, rol = "admin")
    private val gerencia = UsuarioActual(id = 2, rol = "gerencia")
    private val jdv = UsuarioActual(id = 3, rol = "jdv")
    private val vendedor = UsuarioActual(id = 5, rol = "vendedor")

    private fun solicitud(
        rolAprobador: AprobadorSolicitud = AprobadorSolicitud.jdv,
        idSolicitante: Long = 5,
        tipo: TipoSolicitud = TipoSolicitud.descuento,
        entidadId: Long = 45,
        idVendedorNuevo: Long? = null,
    ) = Solicitud(
        id = 9,
        tipo = tipo,
        rolAprobador = rolAprobador,
        idSolicitante = idSolicitante,
        entidadTipo = if (tipo == TipoSolicitud.descuento) EntidadSolicitud.oportunidad_item else EntidadSolicitud.empresa,
        entidadId = entidadId,
        entidadDescripcion = "ABC S.A.",
        motivo = "Cliente frecuente",
        dctoSolicitado = BigDecimal("8.00").takeIf { tipo == TipoSolicitud.descuento },
        idVendedorNuevo = idVendedorNuevo,
    )

    // ── validarDescuento ──────────────────────────────────────

    @Test
    fun `un descuento sobre algo que no es un item de oportunidad es VALIDACION`() {
        val request =
            CrearSolicitudRequest(
                tipo = TipoSolicitud.descuento,
                entidadTipo = EntidadSolicitud.empresa,
                entidadId = 12,
                motivo = "x",
                dctoSolicitado = BigDecimal("8.00"),
            )

        assertThat(campoDe { service.crear(request, vendedor) }).isEqualTo("entidad_tipo")
        verify(exactly = 0) { solicitudRepository.save(any()) }
    }

    @Test
    fun `un descuento sin dcto_solicitado es VALIDACION antes de mirar la oportunidad`() {
        val request =
            CrearSolicitudRequest(
                tipo = TipoSolicitud.descuento,
                entidadTipo = EntidadSolicitud.oportunidad_item,
                entidadId = 91,
                motivo = "x",
                dctoSolicitado = null,
            )

        assertThat(campoDe { service.crear(request, vendedor) }).isEqualTo("dcto_solicitado")
        verify(exactly = 0) { oportunidadItemService.vinculoVisible(any(), any()) }
    }

    @Test
    fun `un descuento sobre un item ajeno responde 404 desde vinculoVisible`() {
        val request =
            CrearSolicitudRequest(
                tipo = TipoSolicitud.descuento,
                entidadTipo = EntidadSolicitud.oportunidad_item,
                entidadId = 91,
                motivo = "x",
                dctoSolicitado = BigDecimal("8.00"),
            )
        every { oportunidadItemService.vinculoVisible(91, vendedor) } throws NoEncontradoException("El ítem no existe")

        assertThatThrownBy { service.crear(request, vendedor) }
            .isInstanceOf(NoEncontradoException::class.java)
    }

    @Test
    fun `si la empresa del item no se resuelve la descripcion cae al literal Empresa`() {
        val request =
            CrearSolicitudRequest(
                tipo = TipoSolicitud.descuento,
                entidadTipo = EntidadSolicitud.oportunidad_item,
                entidadId = 91,
                motivo = "Cliente frecuente",
                dctoSolicitado = BigDecimal("8.00"),
            )
        every { oportunidadItemService.vinculoVisible(91, vendedor) } returns
            OportunidadItemVinculo(id = 91, idOportunidad = 45, idEmpresa = 10, descuento = null)
        // La empresa fue borrada entre el alta del item y esta solicitud.
        every { empresaService.resumenPorIds(listOf(10L)) } returns emptyMap()
        every { solicitudRepository.existsByTipoAndEntidadTipoAndEntidadIdAndEstado(any(), any(), any(), any()) } returns false
        val guardada = slot<Solicitud>()
        every { solicitudRepository.save(capture(guardada)) } answers { solicitud(idSolicitante = 5) }
        every { empleadoService.idsActivosPorRol(any()) } returns listOf(3L)
        every { empleadoService.resumenPorIds(any()) } returns emptyMap()

        service.crear(request, vendedor)

        // Un 8% desde vendedor supera incluso el limite del jdv: lo aprueba gerencia.
        assertThat(guardada.captured.rolAprobador).isEqualTo(AprobadorSolicitud.gerencia)
        assertThat(guardada.captured.entidadDescripcion).isEqualTo("Empresa — Oportunidad #45 (ítem #91)")
    }

    // ── validarReasignacion ───────────────────────────────────

    private fun requestReasignacion(
        entidadTipo: EntidadSolicitud? = EntidadSolicitud.empresa,
        idVendedorNuevo: Long? = 8,
    ) = CrearSolicitudRequest(
        tipo = TipoSolicitud.reasignacion_cliente,
        entidadTipo = entidadTipo,
        entidadId = 12,
        motivo = "Vacaciones largas del vendedor actual",
        idVendedorNuevo = idVendedorNuevo,
    )

    @Test
    fun `una reasignacion sobre algo que no es una empresa es VALIDACION`() {
        assertThat(campoDe { service.crear(requestReasignacion(entidadTipo = EntidadSolicitud.oportunidad), jdv) })
            .isEqualTo("entidad_tipo")
    }

    @Test
    fun `una reasignacion sin id_vendedor_nuevo es VALIDACION`() {
        assertThat(campoDe { service.crear(requestReasignacion(idVendedorNuevo = null), jdv) })
            .isEqualTo("id_vendedor_nuevo")
        verify(exactly = 0) { empleadoService.esAsignableComoVendedor(any()) }
    }

    @Test
    fun `una reasignacion hacia un destino no asignable es VALIDACION`() {
        every { empleadoService.esAsignableComoVendedor(8) } returns false

        assertThat(campoDe { service.crear(requestReasignacion(), jdv) }).isEqualTo("id_vendedor_nuevo")
        verify(exactly = 0) { empresaService.vinculoVisible(any(), any()) }
    }

    // ── visible: alcance por rol (IDOR = 404, no 403) ─────────

    @Test
    fun `admin alcanza cualquier solicitud`() {
        every { solicitudRepository.findById(9) } returns Optional.of(solicitud(rolAprobador = AprobadorSolicitud.jdv, idSolicitante = 5))
        every { empleadoService.resumenPorIds(any()) } returns emptyMap()

        assertThat(service.detalle(9, admin).id).isEqualTo(9L)
    }

    @Test
    fun `gerencia no alcanza una solicitud de la bandeja del jdv`() {
        every { solicitudRepository.findById(9) } returns Optional.of(solicitud(rolAprobador = AprobadorSolicitud.jdv))

        assertThatThrownBy { service.detalle(9, gerencia) }.isInstanceOf(NoEncontradoException::class.java)
    }

    @Test
    fun `gerencia alcanza las de su propia bandeja`() {
        every { solicitudRepository.findById(9) } returns Optional.of(solicitud(rolAprobador = AprobadorSolicitud.gerencia))
        every { empleadoService.resumenPorIds(any()) } returns emptyMap()

        assertThat(service.detalle(9, gerencia).rolAprobador).isEqualTo("gerencia")
    }

    @Test
    fun `el jdv alcanza su bandeja y tambien las que el mismo envio a gerencia`() {
        every { solicitudRepository.findById(9) } returns
            Optional.of(solicitud(rolAprobador = AprobadorSolicitud.gerencia, idSolicitante = 3))
        every { empleadoService.resumenPorIds(any()) } returns emptyMap()

        assertThat(service.detalle(9, jdv).id).isEqualTo(9L)
    }

    @Test
    fun `el jdv no alcanza una solicitud de gerencia que envio otro`() {
        every { solicitudRepository.findById(9) } returns
            Optional.of(solicitud(rolAprobador = AprobadorSolicitud.gerencia, idSolicitante = 5))

        assertThatThrownBy { service.detalle(9, jdv) }.isInstanceOf(NoEncontradoException::class.java)
    }

    @Test
    fun `una solicitud inexistente es 404`() {
        every { solicitudRepository.findById(404) } returns Optional.empty()

        assertThatThrownBy { service.detalle(404, admin) }.isInstanceOf(NoEncontradoException::class.java)
    }

    @Test
    fun `resolver una solicitud inexistente es 404`() {
        every { solicitudRepository.findParaResolver(404) } returns null

        assertThatThrownBy { service.aprobar(404, gerencia) }.isInstanceOf(NoEncontradoException::class.java)
    }

    // ── aplicarReasignacion: el mundo cambio entre pedir y aprobar ──

    @Test
    fun `aprobar una reasignacion cuya empresa ya no existe es 409 SOLICITUD_NO_APLICABLE`() {
        val pendiente =
            solicitud(rolAprobador = AprobadorSolicitud.gerencia, tipo = TipoSolicitud.reasignacion_cliente, idVendedorNuevo = 8)
        every { solicitudRepository.findParaResolver(9) } returns pendiente
        every { empresaService.reasignarVendedor(45, 8, gerencia) } throws NoEncontradoException("La empresa no existe")

        assertThatThrownBy { service.aprobar(9, gerencia) }
            .isInstanceOf(ConflictoException::class.java)
            .hasMessageContaining("ya no existe")
        // La solicitud sigue pendiente: el efecto y la resolucion van en la misma transaccion.
        verify(exactly = 0) { solicitudRepository.save(any()) }
    }

    @Test
    fun `aprobar una reasignacion cuyo destino dejo de ser asignable es 409 SOLICITUD_NO_APLICABLE`() {
        val pendiente =
            solicitud(rolAprobador = AprobadorSolicitud.gerencia, tipo = TipoSolicitud.reasignacion_cliente, idVendedorNuevo = 8)
        every { solicitudRepository.findParaResolver(9) } returns pendiente
        every { empresaService.reasignarVendedor(45, 8, gerencia) } throws
            ValidacionException("El destino debe ser un vendedor o jdv activo", field = "id_vendedor")

        assertThatThrownBy { service.aprobar(9, gerencia) }
            .isInstanceOf(ConflictoException::class.java)
            .hasMessageContaining("asignable")
        verify(exactly = 0) { solicitudRepository.save(any()) }
    }

    // ── privados ───────────────────────────────────────────────

    /** Campo al que apunta la ValidacionException que lanza el bloque. */
    private fun campoDe(bloque: () -> Unit): String? {
        val error = runCatching(bloque).exceptionOrNull()
        return (error as ValidacionException).field
    }
}
