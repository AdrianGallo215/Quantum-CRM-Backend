package pe.quantum.crm.domain.solicitudes

import jakarta.persistence.criteria.Predicate
import org.springframework.data.jpa.domain.Specification
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
import pe.quantum.crm.shared.CamposOrdenables
import pe.quantum.crm.shared.Paginacion
import pe.quantum.crm.shared.Paginado
import pe.quantum.crm.shared.PoliticaDescuento
import pe.quantum.crm.shared.comoInstanteUtc
import pe.quantum.crm.shared.enums.AprobadorSolicitud
import pe.quantum.crm.shared.enums.EntidadSolicitud
import pe.quantum.crm.shared.enums.EstadoSolicitud
import pe.quantum.crm.shared.enums.TipoSolicitud
import pe.quantum.crm.shared.exception.ConflictoException
import pe.quantum.crm.shared.exception.NoEncontradoException
import pe.quantum.crm.shared.exception.PermisoInsuficienteException
import pe.quantum.crm.shared.exception.ValidacionException
import pe.quantum.crm.shared.security.UsuarioActual

@Service
@Suppress("TooManyFunctions") // Modulo de solicitudes: crear/listar/detalle/aprobar/denegar + privados de cada rama.
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
        if (usuario.esRolApoyo) {
            throw PermisoInsuficienteException(
                "Tu rol es de apoyo: no puedes crear solicitudes de aprobación",
            )
        }
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
    @Suppress("ThrowsCount") // Guard clauses de validacion; dividir la funcion no mejora la legibilidad.
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
    @Suppress("ThrowsCount") // Guard clauses de validacion; dividir la funcion no mejora la legibilidad.
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
                resolvedAt = s.resolvedAt?.comoInstanteUtc(),
                createdAt = s.createdAt.comoInstanteUtc(),
            )
        }
    }

    @Transactional(readOnly = true)
    override fun listar(
        filtros: SolicitudFiltros,
        usuario: UsuarioActual,
        page: Int?,
        perPage: Int?,
        sort: String?,
        dir: String?,
    ): Paginado<SolicitudDto> {
        val pageRequest = Paginacion.pageRequest(page, perPage, sort, dir, CAMPOS_ORDENABLES)
        val resultado = solicitudRepository.findAll(especificacion(filtros, usuario), pageRequest)
        val meta = Paginacion.meta(pageRequest.pageNumber + 1, pageRequest.pageSize, resultado.totalElements)
        return Paginado(toDto(resultado.content), meta)
    }

    @Transactional(readOnly = true)
    override fun detalle(
        id: Long,
        usuario: UsuarioActual,
    ): SolicitudDto = toDto(listOf(visible(id, usuario))).first()

    // ── privados de visibilidad ────────────────────────────────

    /** IDOR: solicitud fuera del alcance del rol → 404, no 403. */
    private fun visible(
        id: Long,
        usuario: UsuarioActual,
    ): Solicitud {
        val solicitud =
            solicitudRepository.findById(id).orElseThrow { NoEncontradoException("La solicitud no existe") }
        val alcanzable =
            when (usuario.rol) {
                "admin" -> true
                "gerencia" -> solicitud.rolAprobador == AprobadorSolicitud.gerencia
                "jdv" -> solicitud.rolAprobador == AprobadorSolicitud.jdv || solicitud.idSolicitante == usuario.id
                else -> solicitud.idSolicitante == usuario.id
            }
        if (!alcanzable) {
            throw NoEncontradoException("La solicitud no existe")
        }
        return solicitud
    }

    private fun especificacion(
        filtros: SolicitudFiltros,
        usuario: UsuarioActual,
    ): Specification<Solicitud> =
        Specification { root, _, cb ->
            val predicados = mutableListOf<Predicate>()
            when {
                filtros.mias || usuario.rol == "vendedor" || usuario.rol == "analista" ->
                    predicados += cb.equal(root.get<Long>("idSolicitante"), usuario.id)
                usuario.rol == "gerencia" ->
                    predicados += cb.equal(root.get<AprobadorSolicitud>("rolAprobador"), AprobadorSolicitud.gerencia)
                usuario.rol == "jdv" ->
                    predicados +=
                        cb.or(
                            cb.equal(root.get<AprobadorSolicitud>("rolAprobador"), AprobadorSolicitud.jdv),
                            cb.equal(root.get<Long>("idSolicitante"), usuario.id),
                        )
                // admin: sin predicado de alcance.
            }
            filtros.estado?.let { estado ->
                runCatching { EstadoSolicitud.valueOf(estado) }.getOrNull()?.let {
                    predicados += cb.equal(root.get<EstadoSolicitud>("estado"), it)
                }
            }
            filtros.tipo?.let { tipo ->
                runCatching { TipoSolicitud.valueOf(tipo) }.getOrNull()?.let {
                    predicados += cb.equal(root.get<TipoSolicitud>("tipo"), it)
                }
            }
            cb.and(*predicados.toTypedArray())
        }

    @Transactional
    override fun aprobar(
        id: Long,
        usuario: UsuarioActual,
    ): SolicitudDto {
        val solicitud = pendienteParaResolver(id, usuario)
        // El efecto corre en ESTA transaccion: si falla, la solicitud sigue pendiente.
        when (solicitud.tipo) {
            TipoSolicitud.descuento ->
                oportunidadService.aplicarDescuentoAprobado(
                    solicitud.entidadId,
                    requireNotNull(solicitud.dctoSolicitado),
                    usuario.id,
                )
            TipoSolicitud.reasignacion_cliente -> aplicarReasignacion(solicitud, usuario) // Task 8
        }
        resolver(solicitud, EstadoSolicitud.aprobada, usuario, motivoResolucion = null)
        notificarResolucion(solicitud, usuario, TipoNotificacion.solicitud_aprobada, "aprobó")
        return toDto(listOf(solicitud)).first()
    }

    // ── privados de resolucion ─────────────────────────────────

    /** Lock pesimista + guardas: bandeja correcta y estado pendiente. */
    @Suppress("ThrowsCount") // Guard clauses de validacion; dividir la funcion no mejora la legibilidad.
    private fun pendienteParaResolver(
        id: Long,
        usuario: UsuarioActual,
    ): Solicitud {
        val solicitud =
            solicitudRepository.findParaResolver(id)
                ?: throw NoEncontradoException("La solicitud no existe")
        if (!usuario.puedeAprobar(solicitud.rolAprobador.name)) {
            throw PermisoInsuficienteException("Esta solicitud la resuelve ${solicitud.rolAprobador.name}")
        }
        if (solicitud.estado != EstadoSolicitud.pendiente) {
            throw ConflictoException("SOLICITUD_YA_RESUELTA", "La solicitud ya fue resuelta")
        }
        return solicitud
    }

    private fun resolver(
        solicitud: Solicitud,
        estado: EstadoSolicitud,
        usuario: UsuarioActual,
        motivoResolucion: String?,
    ) {
        solicitud.estado = estado
        solicitud.idResolutor = usuario.id
        solicitud.motivoResolucion = motivoResolucion
        solicitud.resolvedAt = java.time.LocalDateTime.now()
        solicitud.updatedAt = java.time.LocalDateTime.now()
        solicitudRepository.save(solicitud)
    }

    private fun notificarResolucion(
        solicitud: Solicitud,
        usuario: UsuarioActual,
        tipo: TipoNotificacion,
        verbo: String,
    ) {
        val resolutor = empleadoService.resumenPorIds(listOf(usuario.id))[usuario.id]
        val nombre = resolutor?.nombreCompleto() ?: "Gerencia"
        val sufijo = solicitud.motivoResolucion?.let { ": $it" } ?: ""
        notificacionService.notificar(
            destinatarios = setOf(solicitud.idSolicitante),
            idActor = usuario.id,
            tipo = tipo,
            mensaje = "$nombre $verbo tu solicitud de ${etiquetaTipo(solicitud.tipo)} sobre ${solicitud.entidadDescripcion}$sufijo",
            entidadTipo = EntidadNotificacion.solicitud,
            entidadId = requireNotNull(solicitud.id),
        )
    }

    /**
     * Reutiliza reasignarVendedor: cascada a oportunidades activas y notificacion
     * `empresa_asignada` incluidas. El actor es el aprobador (gerencia/admin), que
     * pasa el guard de `puedeReasignarDirecto`.
     */
    @Suppress("SwallowedException") // Se traduce a SOLICITUD_NO_APLICABLE; el detalle original no aporta al usuario.
    private fun aplicarReasignacion(
        solicitud: Solicitud,
        usuario: UsuarioActual,
    ) {
        try {
            empresaService.reasignarVendedor(solicitud.entidadId, requireNotNull(solicitud.idVendedorNuevo), usuario)
        } catch (ex: NoEncontradoException) {
            throw ConflictoException("SOLICITUD_NO_APLICABLE", "La empresa de la solicitud ya no existe")
        } catch (ex: ValidacionException) {
            throw ConflictoException("SOLICITUD_NO_APLICABLE", "El vendedor destino ya no es asignable")
        }
    }

    @Transactional
    override fun denegar(
        id: Long,
        motivo: String,
        usuario: UsuarioActual,
    ): SolicitudDto {
        if (motivo.isBlank()) {
            throw ValidacionException("El motivo de la denegación es obligatorio", field = "motivo")
        }
        val solicitud = pendienteParaResolver(id, usuario)
        resolver(solicitud, EstadoSolicitud.denegada, usuario, motivoResolucion = motivo)
        notificarResolucion(solicitud, usuario, TipoNotificacion.solicitud_denegada, "denegó")
        return toDto(listOf(solicitud)).first()
    }

    private companion object {
        /** Allowlist de `sort` de GET /solicitudes; el primero es el orden por defecto. */
        val CAMPOS_ORDENABLES =
            CamposOrdenables("createdAt", "id", "tipo", "estado", "resolvedAt", "updatedAt")
    }
}
