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
import pe.quantum.crm.domain.notificaciones.OrigenRecordatorio
import pe.quantum.crm.domain.notificaciones.TipoNotificacion
import pe.quantum.crm.domain.oportunidades.OportunidadService
import pe.quantum.crm.domain.tareas.dto.ActividadContactoDto
import pe.quantum.crm.domain.tareas.dto.ActualizarTareaRequest
import pe.quantum.crm.domain.tareas.dto.CrearTareaRequest
import pe.quantum.crm.domain.tareas.dto.TareaDto
import pe.quantum.crm.domain.tareas.dto.TareaFiltros
import pe.quantum.crm.domain.tareas.dto.TareaRecordatorioProyeccion
import pe.quantum.crm.shared.CamposOrdenables
import pe.quantum.crm.shared.Paginacion
import pe.quantum.crm.shared.Paginado
import pe.quantum.crm.shared.comoInstanteUtc
import pe.quantum.crm.shared.enums.EstadoAccion
import pe.quantum.crm.shared.exception.EstadoInvalidoException
import pe.quantum.crm.shared.exception.NoEncontradoException
import pe.quantum.crm.shared.exception.PermisoInsuficienteException
import pe.quantum.crm.shared.exception.ValidacionException
import pe.quantum.crm.shared.security.UsuarioActual
import java.time.LocalDateTime
import java.time.ZoneOffset

@Service
@Suppress("LongParameterList", "TooManyFunctions") // Cada dependencia es una interfaz de servicio publica de otro modulo.
class TareaServiceImpl(
    private val tareaRepository: TareaRepository,
    private val tareaResponsableRepository: TareaResponsableRepository,
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
        val pageRequest = Paginacion.pageRequest(page, perPage, sort, dir, CAMPOS_ORDENABLES)
        val resultado = tareaRepository.findAll(especificacion(filtros, usuario), pageRequest)
        val meta = Paginacion.meta(pageRequest.pageNumber + 1, pageRequest.pageSize, resultado.totalElements)
        return Paginado(toDtos(resultado.content), meta)
    }

    @Transactional
    @Suppress("LongMethod", "CyclomaticComplexMethod") // Crea la tarea, resuelve colaboradores y notifica en una sola transaccion.
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
        val idsColaboradores = request.idsColaboradores.toSet() - idAsignado
        validarPermisoAsignacion(setOf(idAsignado) + idsColaboradores, usuario)
        if (!empleadoService.existeActivo(idAsignado)) {
            throw NoEncontradoException("El empleado asignado no existe o está inactivo")
        }
        idsColaboradores.forEach {
            if (!empleadoService.existeActivo(it)) {
                throw NoEncontradoException("Un colaborador indicado no existe o está inactivo")
            }
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
        val idTarea = requireNotNull(tarea.id)
        if (idsColaboradores.isNotEmpty()) {
            tareaResponsableRepository.saveAll(
                idsColaboradores.map {
                    TareaResponsable(id = TareaResponsableId(idTarea = idTarea, idEmpleado = it), createdAt = ahora, createdBy = usuario.id)
                },
            )
        }
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
        if (idsColaboradores.isNotEmpty()) {
            notificacionService.notificar(
                destinatarios = idsColaboradores,
                idActor = usuario.id,
                tipo = TipoNotificacion.tarea_colaborador_agregado,
                mensaje = "${actor?.nombreCompleto()} te agregó como colaborador en una tarea en ${empresa.razonSocial}",
                entidadTipo = entidadTipo,
                entidadId = entidadId,
            )
        }
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

    /**
     * Sustituye el set de colaboradores de la tarea y devuelve solo los que se
     * agregaron (los unicos a los que hay que notificar). El dueño nunca es
     * colaborador de su propia tarea, asi que se excluye del set.
     */
    private fun reemplazarColaboradores(
        id: Long,
        idsColaboradores: List<Long>,
        idAsignado: Long?,
        usuario: UsuarioActual,
    ): Set<Long> {
        val nuevoSet = idsColaboradores.toSet() - setOfNotNull(idAsignado)
        validarPermisoAsignacion(nuevoSet, usuario)
        nuevoSet.forEach {
            if (!empleadoService.existeActivo(it)) {
                throw NoEncontradoException("Un colaborador indicado no existe o está inactivo")
            }
        }
        val setActual = tareaResponsableRepository.findByIdIdTarea(id).map { it.id.idEmpleado }.toSet()
        tareaResponsableRepository.deleteByIdIdTarea(id)
        if (nuevoSet.isNotEmpty()) {
            val ahora = LocalDateTime.now()
            tareaResponsableRepository.saveAll(
                nuevoSet.map {
                    TareaResponsable(id = TareaResponsableId(idTarea = id, idEmpleado = it), createdAt = ahora, createdBy = usuario.id)
                },
            )
        }
        return nuevoSet - setActual
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
        // Solo cuenta como reprogramacion si la fecha se mueve de verdad: reiniciar
        // el dedup en cada edicion reenviaria recordatorios ya entregados.
        var reprogramada = false
        request.fechaEjecucion?.let {
            val nueva = LocalDateTime.ofInstant(it, ZoneOffset.UTC)
            if (nueva != tarea.fechaEjecucion) {
                tarea.fechaEjecucion = nueva
                reprogramada = true
            }
        }
        request.idContacto?.let {
            if (!contactoService.existe(it)) {
                throw NoEncontradoException("El contacto no existe")
            }
            tarea.idContacto = it
        }
        var nuevoDueno = false
        if (request.idAsignado != null && request.idAsignado != tarea.idAsignado) {
            validarPermisoAsignacion(setOf(request.idAsignado), usuario)
            if (!empleadoService.existeActivo(request.idAsignado)) {
                throw NoEncontradoException("El empleado asignado no existe o está inactivo")
            }
            tarea.idAsignado = request.idAsignado
            nuevoDueno = true
        }
        val colaboradoresAgregados =
            request.idsColaboradores?.let { reemplazarColaboradores(id, it, tarea.idAsignado, usuario) }.orEmpty()
        tarea.updatedAt = LocalDateTime.now()
        tarea.updatedBy = usuario.id
        // Dentro de la misma transaccion que la reprogramacion: si esta se revierte,
        // el dedup queda intacto.
        if (reprogramada) {
            notificacionService.reiniciarRecordatorios(OrigenRecordatorio.tarea, id)
        }
        val actualizada = tareaRepository.save(tarea)
        notificarCambiosAsignacion(actualizada, nuevoDueno, colaboradoresAgregados, usuario)
        return toDtos(listOf(actualizada)).first()
    }

    private fun notificarCambiosAsignacion(
        tarea: Tarea,
        nuevoDueno: Boolean,
        colaboradoresAgregados: Set<Long>,
        usuario: UsuarioActual,
    ) {
        if (!nuevoDueno && colaboradoresAgregados.isEmpty()) {
            return
        }
        val entidadTipo = if (tarea.idOportunidad != null) EntidadNotificacion.oportunidad else EntidadNotificacion.empresa
        val entidadId = tarea.idOportunidad ?: tarea.idEmpresa
        val actor = empleadoService.resumenPorIds(listOf(usuario.id))[usuario.id]
        val empresa = empresaService.resumenPorIds(listOf(tarea.idEmpresa))[tarea.idEmpresa]
        if (nuevoDueno) {
            notificacionService.notificar(
                destinatarios = setOf(requireNotNull(tarea.idAsignado)),
                idActor = usuario.id,
                tipo = TipoNotificacion.tarea_creada,
                mensaje = "${actor?.nombreCompleto()} te asignó una tarea en ${empresa?.razonSocial}",
                entidadTipo = entidadTipo,
                entidadId = entidadId,
            )
        }
        if (colaboradoresAgregados.isNotEmpty()) {
            notificacionService.notificar(
                destinatarios = colaboradoresAgregados,
                idActor = usuario.id,
                tipo = TipoNotificacion.tarea_colaborador_agregado,
                mensaje = "${actor?.nombreCompleto()} te agregó como colaborador en una tarea en ${empresa?.razonSocial}",
                entidadTipo = entidadTipo,
                entidadId = entidadId,
            )
        }
    }

    /** matriz_permisos.md §2.6: solo admin/gerencia/jdv pueden asignar la tarea a otro empleado. */
    private fun validarPermisoAsignacion(
        idsEmpleados: Set<Long>,
        usuario: UsuarioActual,
    ) {
        if (!usuario.esSupervisor && idsEmpleados.any { it != usuario.id }) {
            throw PermisoInsuficienteException("Solo admin, gerencia o jdv pueden asignar la tarea a otro empleado")
        }
    }

    @Transactional(readOnly = true)
    override fun actividadesPorContacto(
        idContacto: Long,
        usuario: UsuarioActual,
    ): List<ActividadContactoDto> {
        val tareas = tareaRepository.findByIdContactoOrdenado(idContacto)
        val visibles =
            if (usuario.visibilidadRestringida) {
                val idsColaborador =
                    tareaResponsableRepository
                        .findByIdIdTareaIn(tareas.mapNotNull { it.id })
                        .filter { it.id.idEmpleado == usuario.id }
                        .map { it.id.idTarea }
                        .toSet()
                tareas.filter { it.idAsignado == usuario.id || it.id in idsColaborador }
            } else {
                tareas
            }
        return visibles.map {
            ActividadContactoDto(
                id = requireNotNull(it.id),
                titulo = it.tipoAccion.name,
                descripcion = it.descripcion,
                fecha = (it.fechaEjecucion ?: it.createdAt).comoInstanteUtc(),
                estado = it.estadoAccion.name,
            )
        }
    }

    @Transactional(readOnly = true)
    override fun pendientesParaRecordatorio(): List<TareaRecordatorioProyeccion> {
        val ahora = LocalDateTime.now()
        return tareaRepository
            .findByEstadoAccionAndIdAsignadoIsNotNullAndFechaEjecucionBetween(
                EstadoAccion.pendiente,
                ahora.minusDays(DIAS_VENCIDO_NOTIFICABLE),
                ahora.plusHours(HORAS_VENTANA_PROXIMO),
            ).map {
                TareaRecordatorioProyeccion(
                    id = requireNotNull(it.id),
                    idAsignado = requireNotNull(it.idAsignado),
                    idEmpresa = it.idEmpresa,
                    idOportunidad = it.idOportunidad,
                    fechaEjecucion = requireNotNull(it.fechaEjecucion),
                )
            }
    }

    // ── privados ───────────────────────────────────────────────

    /** vendedor/analista solo operan tareas donde son dueño o colaborador (404 si no). */
    private fun visible(
        id: Long,
        usuario: UsuarioActual,
    ): Tarea {
        val tarea = tareaRepository.findById(id).orElseThrow { NoEncontradoException("La tarea no existe") }
        if (usuario.visibilidadRestringida &&
            tarea.idAsignado != usuario.id &&
            !tareaResponsableRepository.existsByIdIdTareaAndIdIdEmpleado(id, usuario.id)
        ) {
            throw NoEncontradoException("La tarea no existe")
        }
        return tarea
    }

    private fun especificacion(
        filtros: TareaFiltros,
        usuario: UsuarioActual,
    ): Specification<Tarea> =
        Specification { root, query, cb ->
            val predicados = mutableListOf<Predicate>()
            if (usuario.visibilidadRestringida) {
                val colaboradorSubquery = requireNotNull(query).subquery(Long::class.java)
                val responsableRoot = colaboradorSubquery.from(TareaResponsable::class.java)
                colaboradorSubquery
                    .select(responsableRoot.get<TareaResponsableId>("id").get<Long>("idTarea"))
                    .where(cb.equal(responsableRoot.get<TareaResponsableId>("id").get<Long>("idEmpleado"), usuario.id))
                predicados +=
                    cb.or(
                        cb.equal(root.get<Long>("idAsignado"), usuario.id),
                        root.get<Long>("id").`in`(colaboradorSubquery),
                    )
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
        val colaboradoresPorTarea =
            tareaResponsableRepository
                .findByIdIdTareaIn(tareas.mapNotNull { it.id })
                .groupBy({ it.id.idTarea }, { it.id.idEmpleado })
        val idsEmpleados = (tareas.mapNotNull { it.idAsignado } + colaboradoresPorTarea.values.flatten()).distinct()
        val empleados = empleadoService.resumenPorIds(idsEmpleados)
        return tareas.map { tarea ->
            val idsColaboradores = colaboradoresPorTarea[tarea.id].orEmpty()
            TareaDto(
                id = requireNotNull(tarea.id),
                idEmpresa = tarea.idEmpresa,
                empresa = empresas[tarea.idEmpresa],
                idOportunidad = tarea.idOportunidad,
                idContacto = tarea.idContacto,
                contacto = tarea.idContacto?.let { contactos[it] },
                idAsignado = tarea.idAsignado,
                asignado = tarea.idAsignado?.let { empleados[it] },
                idsColaboradores = idsColaboradores,
                colaboradores = idsColaboradores.mapNotNull { empleados[it] },
                tipoAccion = tarea.tipoAccion.name,
                estadoAccion = tarea.estadoAccion.name,
                descripcion = tarea.descripcion,
                fechaEjecucion = tarea.fechaEjecucion?.comoInstanteUtc(),
                createdAt = tarea.createdAt.comoInstanteUtc(),
            )
        }
    }

    private companion object {
        /** Allowlist de `sort` de GET /tareas; el primero es el orden por defecto. */
        val CAMPOS_ORDENABLES =
            CamposOrdenables("fechaEjecucion", "id", "tipoAccion", "estadoAccion", "createdAt", "updatedAt")

        /**
         * Mas alla de 30 dias vencida, el recordatorio ya se envio hace mucho y el
         * dedup permanente impide reenviarlo: seguir escaneandola es puro coste.
         */
        const val DIAS_VENCIDO_NOTIFICABLE = 30L

        /** Superconjunto del umbral `proximo` de `RecordatorioJob` (24 h). */
        const val HORAS_VENTANA_PROXIMO = 24L
    }
}
