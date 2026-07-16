package pe.quantum.crm.domain.solicitudes

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pe.quantum.crm.domain.empleados.EmpleadoService
import pe.quantum.crm.domain.empleados.RolEmpleado
import pe.quantum.crm.domain.empleados.dto.nombreCompleto
import pe.quantum.crm.domain.empresas.EmpresaService
import pe.quantum.crm.domain.notificaciones.EntidadNotificacion
import pe.quantum.crm.domain.notificaciones.NotificacionService
import pe.quantum.crm.domain.notificaciones.TipoNotificacion
import pe.quantum.crm.domain.oportunidades.OportunidadService
import pe.quantum.crm.domain.solicitudes.dto.CrearSolicitudRequest
import pe.quantum.crm.domain.solicitudes.dto.SolicitudDto
import pe.quantum.crm.domain.solicitudes.dto.SolicitudFiltros
import pe.quantum.crm.shared.Paginado
import pe.quantum.crm.shared.PoliticaDescuento
import pe.quantum.crm.shared.enums.AprobadorSolicitud
import pe.quantum.crm.shared.enums.EntidadSolicitud
import pe.quantum.crm.shared.enums.EstadoSolicitud
import pe.quantum.crm.shared.enums.TipoSolicitud
import pe.quantum.crm.shared.exception.ConflictoException
import pe.quantum.crm.shared.exception.PermisoInsuficienteException
import pe.quantum.crm.shared.exception.ValidacionException
import pe.quantum.crm.shared.security.UsuarioActual

@Service
class SolicitudServiceImpl(
    private val solicitudRepository: SolicitudRepository,
    private val oportunidadService: OportunidadService,
    private val empresaService: EmpresaService,
    private val empleadoService: EmpleadoService,
    private val notificacionService: NotificacionService,
) : SolicitudService {
    @Transactional
    override fun crear(
        request: CrearSolicitudRequest,
        usuario: UsuarioActual,
    ): SolicitudDto {
        val tipo = requireNotNull(request.tipo)
        val entidadId = requireNotNull(request.entidadId)
        val (rolAprobador, entidadTipo, descripcion) =
            when (tipo) {
                TipoSolicitud.descuento -> validarDescuento(request, usuario)
                TipoSolicitud.reasignacion_cliente -> validarReasignacion(request, usuario)
            }
        if (solicitudRepository.existsByTipoAndEntidadTipoAndEntidadIdAndEstado(
                tipo,
                entidadTipo,
                entidadId,
                EstadoSolicitud.pendiente,
            )
        ) {
            throw ConflictoException("SOLICITUD_DUPLICADA", "Ya existe una solicitud pendiente de este tipo sobre esta entidad")
        }
        val solicitud =
            solicitudRepository.save(
                Solicitud(
                    tipo = tipo,
                    rolAprobador = rolAprobador,
                    idSolicitante = usuario.id,
                    entidadTipo = entidadTipo,
                    entidadId = entidadId,
                    entidadDescripcion = descripcion,
                    motivo = requireNotNull(request.motivo),
                    dctoSolicitado = request.dctoSolicitado,
                    idVendedorNuevo = request.idVendedorNuevo,
                ),
            )
        notificarCreada(solicitud, usuario)
        return toDto(listOf(solicitud)).first()
    }

    // ── privados de creacion ───────────────────────────────────

    private data class Contexto(
        val rolAprobador: AprobadorSolicitud,
        val entidadTipo: EntidadSolicitud,
        val descripcion: String,
    )

    /** Descuento: solo sobre oportunidades visibles, y solo si excede el limite propio. */
    private fun validarDescuento(
        request: CrearSolicitudRequest,
        usuario: UsuarioActual,
    ): Contexto {
        if (request.entidadTipo != EntidadSolicitud.oportunidad) {
            throw ValidacionException("Una solicitud de descuento aplica sobre una oportunidad", field = "entidad_tipo")
        }
        val dcto =
            request.dctoSolicitado
                ?: throw ValidacionException("dcto_solicitado es obligatorio", field = "dcto_solicitado")
        // IDOR: si no es visible para el solicitante, 404 desde vinculoVisible.
        val oportunidad = oportunidadService.vinculoVisible(requireNotNull(request.entidadId), usuario)
        val aprobador =
            PoliticaDescuento.aprobadorPara(usuario.rol, dcto)
                ?: throw ValidacionException(
                    "Un descuento de ${dcto.toPlainString()}% está dentro de tu límite: aplícalo directamente",
                    field = "dcto_solicitado",
                )
        val empresa = empresaService.resumenPorIds(listOf(oportunidad.idEmpresa))[oportunidad.idEmpresa]
        val descripcion = "${empresa?.razonSocial ?: "Empresa"} — Oportunidad #${oportunidad.id}"
        return Contexto(aprobador, EntidadSolicitud.oportunidad, descripcion)
    }

    /** Reasignacion: solo la solicita el jdv y siempre la aprueba gerencia. */
    private fun validarReasignacion(
        request: CrearSolicitudRequest,
        usuario: UsuarioActual,
    ): Contexto {
        if (usuario.rol != "jdv") {
            throw PermisoInsuficienteException("Solo el jefe de ventas puede solicitar reasignar un cliente")
        }
        if (request.entidadTipo != EntidadSolicitud.empresa) {
            throw ValidacionException("Una reasignación de cliente aplica sobre una empresa", field = "entidad_tipo")
        }
        val destino =
            request.idVendedorNuevo
                ?: throw ValidacionException("id_vendedor_nuevo es obligatorio", field = "id_vendedor_nuevo")
        if (!empleadoService.esAsignableComoVendedor(destino)) {
            throw ValidacionException("El destino debe ser un vendedor o jdv activo", field = "id_vendedor_nuevo")
        }
        val empresa = empresaService.vinculoVisible(requireNotNull(request.entidadId), usuario)
        return Contexto(AprobadorSolicitud.gerencia, EntidadSolicitud.empresa, empresa.razonSocial)
    }

    private fun notificarCreada(
        solicitud: Solicitud,
        usuario: UsuarioActual,
    ) {
        val rol = if (solicitud.rolAprobador == AprobadorSolicitud.jdv) RolEmpleado.jdv else RolEmpleado.gerencia
        val solicitante = empleadoService.resumenPorIds(listOf(usuario.id))[usuario.id]
        val nombre = solicitante?.nombreCompleto() ?: "Un usuario"
        notificacionService.notificar(
            destinatarios = empleadoService.idsActivosPorRol(rol).toSet(),
            idActor = usuario.id,
            tipo = TipoNotificacion.solicitud_creada,
            mensaje = "$nombre envió una solicitud de ${etiquetaTipo(solicitud.tipo)} sobre ${solicitud.entidadDescripcion}",
            entidadTipo = EntidadNotificacion.solicitud,
            entidadId = requireNotNull(solicitud.id),
        )
    }

    private fun etiquetaTipo(tipo: TipoSolicitud): String =
        when (tipo) {
            TipoSolicitud.descuento -> "descuento"
            TipoSolicitud.reasignacion_cliente -> "reasignación de cliente"
        }

    /** Ensambla DTOs por lotes (sin N+1), mismo patron que OportunidadServiceImpl.toDtos. */
    internal fun toDto(solicitudes: List<Solicitud>): List<SolicitudDto> {
        if (solicitudes.isEmpty()) return emptyList()
        val idsEmpleados =
            (
                solicitudes.map { it.idSolicitante } +
                    solicitudes.mapNotNull { it.idResolutor } +
                    solicitudes.mapNotNull { it.idVendedorNuevo }
            ).distinct()
        val empleados = empleadoService.resumenPorIds(idsEmpleados)
        return solicitudes.map { s ->
            SolicitudDto(
                id = requireNotNull(s.id),
                tipo = s.tipo.name,
                estado = s.estado.name,
                rolAprobador = s.rolAprobador.name,
                entidadTipo = s.entidadTipo.name,
                entidadId = s.entidadId,
                entidadDescripcion = s.entidadDescripcion,
                dctoSolicitado = s.dctoSolicitado?.toPlainString(),
                idVendedorNuevo = s.idVendedorNuevo,
                vendedorNuevo = s.idVendedorNuevo?.let { empleados[it] },
                motivo = s.motivo,
                solicitante = empleados[s.idSolicitante],
                resolutor = s.idResolutor?.let { empleados[it] },
                motivoResolucion = s.motivoResolucion,
                resolvedAt = s.resolvedAt,
                createdAt = s.createdAt,
            )
        }
    }

    // listar/detalle: Task 6 · aprobar: Task 7-8 · denegar: Task 8
    override fun listar(
        filtros: SolicitudFiltros,
        usuario: UsuarioActual,
        page: Int?,
        perPage: Int?,
        sort: String?,
        dir: String?,
    ): Paginado<SolicitudDto> = throw NotImplementedError("Task 6")

    override fun detalle(
        id: Long,
        usuario: UsuarioActual,
    ): SolicitudDto = throw NotImplementedError("Task 6")

    override fun aprobar(
        id: Long,
        usuario: UsuarioActual,
    ): SolicitudDto = throw NotImplementedError("Task 7")

    override fun denegar(
        id: Long,
        motivo: String,
        usuario: UsuarioActual,
    ): SolicitudDto = throw NotImplementedError("Task 8")
}
