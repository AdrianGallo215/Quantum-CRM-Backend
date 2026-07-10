package pe.quantum.crm.domain.tareas

import jakarta.persistence.criteria.Predicate
import org.springframework.data.jpa.domain.Specification
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pe.quantum.crm.domain.contactos.ContactoService
import pe.quantum.crm.domain.empleados.EmpleadoService
import pe.quantum.crm.domain.empleados.dto.nombreCompleto
import pe.quantum.crm.domain.empresas.EmpresaService
import pe.quantum.crm.domain.notificaciones.EntidadNotificacion
import pe.quantum.crm.domain.notificaciones.NotificacionService
import pe.quantum.crm.domain.notificaciones.TipoNotificacion
import pe.quantum.crm.domain.oportunidades.OportunidadService
import pe.quantum.crm.domain.tareas.dto.ActualizarTareaRequest
import pe.quantum.crm.domain.tareas.dto.CrearTareaRequest
import pe.quantum.crm.domain.tareas.dto.TareaDto
import pe.quantum.crm.domain.tareas.dto.TareaFiltros
import pe.quantum.crm.domain.tareas.dto.TareaRecordatorioProyeccion
import pe.quantum.crm.shared.Paginacion
import pe.quantum.crm.shared.Paginado
import pe.quantum.crm.shared.enums.EstadoAccion
import pe.quantum.crm.shared.exception.EstadoInvalidoException
import pe.quantum.crm.shared.exception.NoEncontradoException
import pe.quantum.crm.shared.exception.ValidacionException
import pe.quantum.crm.shared.security.UsuarioActual
import java.time.LocalDateTime
import java.time.ZoneOffset

@Service
class TareaServiceImpl(
    private val tareaRepository: TareaRepository,
    private val empresaService: EmpresaService,
    private val oportunidadService: OportunidadService,
    private val contactoService: ContactoService,
    private val empleadoService: EmpleadoService,
    private val notificacionService: NotificacionService,
) : TareaService {
    @Transactional(readOnly = true)
    override fun listar(
        filtros: TareaFiltros,
        usuario: UsuarioActual,
        page: Int?,
        perPage: Int?,
        sort: String?,
        dir: String?,
    ): Paginado<TareaDto> {
        val pageRequest = Paginacion.pageRequest(page, perPage, sort, dir, defaultSort = "fechaEjecucion")
        val resultado = tareaRepository.findAll(especificacion(filtros, usuario), pageRequest)
        val meta = Paginacion.meta(pageRequest.pageNumber + 1, pageRequest.pageSize, resultado.totalElements)
        return Paginado(toDtos(resultado.content), meta)
    }

    @Transactional
    override fun crear(
        request: CrearTareaRequest,
        usuario: UsuarioActual,
    ): TareaDto {
        val empresa = empresaService.vinculoVisible(request.idEmpresa, usuario)
        val idOportunidad = request.idOportunidad
        if (idOportunidad != null) {
            val oportunidad = oportunidadService.vinculoVisible(idOportunidad, usuario)
            if (oportunidad.idEmpresa != empresa.id) {
                throw ValidacionException("La oportunidad no pertenece a la empresa indicada", field = "id_oportunidad")
            }
        } else if (oportunidadService.tieneOportunidadesActivas(empresa.id)) {
            // Regla §10.2: la tarea de una empresa con oportunidad activa debe
            // vincularse a la oportunidad, no a la empresa directamente.
            throw ValidacionException(
                "Las tareas de empresas con oportunidades activas deben vincularse a una oportunidad",
                field = "id_oportunidad",
            )
        }
        request.idContacto?.let {
            if (!contactoService.existe(it)) {
                throw NoEncontradoException("El contacto no existe")
            }
        }
        val idAsignado = request.idAsignado ?: usuario.id
        if (!empleadoService.existeActivo(idAsignado)) {
            throw NoEncontradoException("El empleado asignado no existe o está inactivo")
        }
        val ahora = LocalDateTime.now()
        val tarea =
            tareaRepository.save(
                Tarea(
                    idEmpresa = empresa.id,
                    idOportunidad = idOportunidad,
                    idContacto = request.idContacto,
                    idAsignado = idAsignado,
                    tipoAccion = request.tipoAccion,
                    estadoAccion = EstadoAccion.pendiente,
                    descripcion = request.descripcion,
                    fechaEjecucion = request.fechaEjecucion?.let { LocalDateTime.ofInstant(it, ZoneOffset.UTC) },
                    createdAt = ahora,
                    createdBy = usuario.id,
                    updatedAt = ahora,
                    updatedBy = usuario.id,
                ),
            )
        val entidadTipo = if (idOportunidad != null) EntidadNotificacion.oportunidad else EntidadNotificacion.empresa
        val entidadId = idOportunidad ?: empresa.id
        val actor = empleadoService.resumenPorIds(listOf(usuario.id))[usuario.id]
        notificacionService.notificar(
            destinatarios = setOf(idAsignado),
            idActor = usuario.id,
            tipo = TipoNotificacion.tarea_creada,
            mensaje = "${actor?.nombreCompleto()} te asignó una tarea en ${empresa.razonSocial}",
            entidadTipo = entidadTipo,
            entidadId = entidadId,
        )
        return toDtos(listOf(tarea)).first()
    }

    @Transactional
    override fun completar(
        id: Long,
        descripcion: String?,
        usuario: UsuarioActual,
    ): TareaDto {
        val tarea = visible(id, usuario)
        if (tarea.estadoAccion != EstadoAccion.pendiente) {
            throw EstadoInvalidoException("Solo una tarea pendiente puede completarse")
        }
        tarea.estadoAccion = EstadoAccion.completada
        descripcion?.let { tarea.descripcion = it }
        tarea.updatedAt = LocalDateTime.now()
        tarea.updatedBy = usuario.id
        return toDtos(listOf(tareaRepository.save(tarea))).first()
    }

    @Transactional
    override fun cancelar(
        id: Long,
        usuario: UsuarioActual,
    ): TareaDto {
        val tarea = visible(id, usuario)
        if (tarea.estadoAccion != EstadoAccion.pendiente) {
            throw EstadoInvalidoException("Solo una tarea pendiente puede cancelarse")
        }
        tarea.estadoAccion = EstadoAccion.cancelada
        tarea.updatedAt = LocalDateTime.now()
        tarea.updatedBy = usuario.id
        return toDtos(listOf(tareaRepository.save(tarea))).first()
    }

    @Transactional
    override fun actualizar(
        id: Long,
        request: ActualizarTareaRequest,
        usuario: UsuarioActual,
    ): TareaDto {
        val tarea = visible(id, usuario)
        if (tarea.estadoAccion != EstadoAccion.pendiente) {
            throw EstadoInvalidoException("Solo se pueden editar tareas pendientes")
        }
        request.tipoAccion?.let { tarea.tipoAccion = it }
        request.descripcion?.let { tarea.descripcion = it }
        request.fechaEjecucion?.let { tarea.fechaEjecucion = LocalDateTime.ofInstant(it, ZoneOffset.UTC) }
        request.idContacto?.let {
            if (!contactoService.existe(it)) {
                throw NoEncontradoException("El contacto no existe")
            }
            tarea.idContacto = it
        }
        request.idAsignado?.let {
            if (!empleadoService.existeActivo(it)) {
                throw NoEncontradoException("El empleado asignado no existe o está inactivo")
            }
            tarea.idAsignado = it
        }
        tarea.updatedAt = LocalDateTime.now()
        tarea.updatedBy = usuario.id
        return toDtos(listOf(tareaRepository.save(tarea))).first()
    }

    @Transactional(readOnly = true)
    override fun pendientesParaRecordatorio(): List<TareaRecordatorioProyeccion> =
        tareaRepository.findByEstadoAccionAndIdAsignadoIsNotNullAndFechaEjecucionIsNotNull(EstadoAccion.pendiente).map {
            TareaRecordatorioProyeccion(
                id = requireNotNull(it.id),
                idAsignado = requireNotNull(it.idAsignado),
                idEmpresa = it.idEmpresa,
                idOportunidad = it.idOportunidad,
                fechaEjecucion = requireNotNull(it.fechaEjecucion),
            )
        }

    // ── privados ───────────────────────────────────────────────

    /** vendedor/analista solo operan tareas asignadas a si mismos (404 si no). */
    private fun visible(
        id: Long,
        usuario: UsuarioActual,
    ): Tarea {
        val tarea = tareaRepository.findById(id).orElseThrow { NoEncontradoException("La tarea no existe") }
        if (usuario.visibilidadRestringida && tarea.idAsignado != usuario.id) {
            throw NoEncontradoException("La tarea no existe")
        }
        return tarea
    }

    private fun especificacion(
        filtros: TareaFiltros,
        usuario: UsuarioActual,
    ): Specification<Tarea> =
        Specification { root, _, cb ->
            val predicados = mutableListOf<Predicate>()
            if (usuario.visibilidadRestringida) {
                predicados += cb.equal(root.get<Long>("idAsignado"), usuario.id)
            } else if (filtros.idAsignado != null) {
                predicados += cb.equal(root.get<Long>("idAsignado"), filtros.idAsignado)
            }
            filtros.idEmpresa?.let { predicados += cb.equal(root.get<Long>("idEmpresa"), it) }
            filtros.idOportunidad?.let { predicados += cb.equal(root.get<Long>("idOportunidad"), it) }
            filtros.estadoAccion?.let { estado ->
                runCatching { EstadoAccion.valueOf(estado) }.getOrNull()?.let {
                    predicados += cb.equal(root.get<EstadoAccion>("estadoAccion"), it)
                }
            }
            if (filtros.soloProspeccion) {
                predicados += cb.isNull(root.get<Long>("idOportunidad"))
            }
            if (filtros.vencidas) {
                predicados += cb.equal(root.get<EstadoAccion>("estadoAccion"), EstadoAccion.pendiente)
                predicados += cb.lessThan(root.get("fechaEjecucion"), LocalDateTime.now())
            }
            cb.and(*predicados.toTypedArray())
        }

    private fun toDtos(tareas: List<Tarea>): List<TareaDto> {
        if (tareas.isEmpty()) {
            return emptyList()
        }
        val empresas = empresaService.resumenPorIds(tareas.map { it.idEmpresa })
        val contactos = contactoService.resumenPorIds(tareas.mapNotNull { it.idContacto })
        val asignados = empleadoService.resumenPorIds(tareas.mapNotNull { it.idAsignado })
        return tareas.map { tarea ->
            TareaDto(
                id = requireNotNull(tarea.id),
                idEmpresa = tarea.idEmpresa,
                empresa = empresas[tarea.idEmpresa],
                idOportunidad = tarea.idOportunidad,
                idContacto = tarea.idContacto,
                contacto = tarea.idContacto?.let { contactos[it] },
                idAsignado = tarea.idAsignado,
                asignado = tarea.idAsignado?.let { asignados[it] },
                tipoAccion = tarea.tipoAccion.name,
                estadoAccion = tarea.estadoAccion.name,
                descripcion = tarea.descripcion,
                fechaEjecucion = tarea.fechaEjecucion,
                createdAt = tarea.createdAt,
            )
        }
    }
}
