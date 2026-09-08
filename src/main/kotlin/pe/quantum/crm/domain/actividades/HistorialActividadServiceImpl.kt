package pe.quantum.crm.domain.actividades

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pe.quantum.crm.domain.actividades.dto.ActividadDto
import pe.quantum.crm.domain.actividades.dto.CambioAuditoriaDto
import pe.quantum.crm.domain.actividades.dto.HistorialFiltros
import pe.quantum.crm.domain.empleados.EmpleadoService
import pe.quantum.crm.domain.empresas.EmpresaService
import pe.quantum.crm.domain.eventos.EventoService
import pe.quantum.crm.domain.eventos.dto.EventoDto
import pe.quantum.crm.domain.tareas.TareaService
import pe.quantum.crm.domain.tareas.dto.TareaDto
import pe.quantum.crm.shared.PageMeta
import pe.quantum.crm.shared.Paginacion
import pe.quantum.crm.shared.Paginado
import pe.quantum.crm.shared.exception.PermisoInsuficienteException
import pe.quantum.crm.shared.security.UsuarioActual

/**
 * Une dos modulos por sus interfaces publicas (CLAUDE.md regla 12): nunca toca
 * las entidades `Tarea` ni `Evento`, solo sus DTOs.
 *
 * La union y la paginacion son EN MEMORIA, a proposito: son dos tablas
 * distintas y no hay forma de paginarlas juntas en SQL sin una vista. Es
 * aceptable porque `idEmpleado` es obligatorio, lo que acota el resultado al
 * historial de UNA persona. Si algun dia una sola persona acumula decenas de
 * miles de actividades, esto hay que revisarlo.
 */
@Service
@Suppress("LongParameterList") // Seis interfaces de servicio publicas: es un modulo de composicion.
class HistorialActividadServiceImpl(
    private val tareaService: TareaService,
    private val eventoService: EventoService,
    private val comentarioService: ComentarioActividadService,
    private val auditoriaService: AuditoriaActividadService,
    private val empresaService: EmpresaService,
    private val empleadoService: EmpleadoService,
) : HistorialActividadService {
    @Transactional(readOnly = true)
    override fun historial(
        filtros: HistorialFiltros,
        usuario: UsuarioActual,
        page: Int?,
        perPage: Int?,
    ): Paginado<ActividadDto> {
        exigirPermiso(filtros.idEmpleado, usuario)

        val tareas =
            if (filtros.tipo == TipoActividad.evento.name) {
                emptyList()
            } else {
                tareaService.listarPorEmpleado(filtros.idEmpleado, filtros.desde, filtros.hasta, usuario)
            }
        val eventos =
            if (filtros.tipo == TipoActividad.tarea.name) {
                emptyList()
            } else {
                eventoService.listarPorEmpleado(filtros.idEmpleado, filtros.desde, filtros.hasta, usuario)
            }

        val comentariosTarea = comentarioService.contar(TipoActividad.tarea, tareas.map { it.id })
        val comentariosEvento = comentarioService.contar(TipoActividad.evento, eventos.map { it.id })
        // Los eventos solo traen ids; sus resumenes se resuelven aqui.
        val empresas = empresaService.resumenPorIds(eventos.mapNotNull { it.idEmpresa })
        val empleados = empleadoService.resumenPorIds(eventos.map { it.createdBy }.distinct())

        val todas =
            (
                tareas.map { it.aActividad(comentariosTarea[it.id] ?: 0) } +
                    eventos.map { it.aActividad(comentariosEvento[it.id] ?: 0, empresas, empleados) }
            ).filter { coincide(it, filtros) }
                .sortedByDescending { it.createdAt }

        return paginar(todas, page, perPage)
    }

    @Transactional(readOnly = true)
    override fun auditoria(
        tipo: TipoActividad,
        idActividad: Long,
        usuario: UsuarioActual,
    ): List<CambioAuditoriaDto> {
        // El guard va aqui y no dentro de AuditoriaActividadService: ese servicio
        // no puede depender de tareas/eventos sin crear un ciclo de beans.
        when (tipo) {
            TipoActividad.tarea -> tareaService.vinculoVisible(idActividad, usuario)
            TipoActividad.evento -> eventoService.vinculoVisible(idActividad, usuario)
        }
        return auditoriaService.historial(tipo, idActividad)
    }

    // ── privados ───────────────────────────────────────────────

    /**
     * Ver el historial ajeno exige ser supervisor. Es 403 y no 404 porque el
     * recurso protegido es el EMPLEADO, cuya existencia ya es publica via
     * `GET /empleados` — no hay nada que ocultar (ver el plan, seccion Permisos).
     */
    private fun exigirPermiso(
        idEmpleado: Long,
        usuario: UsuarioActual,
    ) {
        if (idEmpleado != usuario.id && !usuario.esSupervisor) {
            throw PermisoInsuficienteException(
                "Solo admin, gerencia o jdv pueden ver el historial de actividades de otro empleado",
            )
        }
    }

    @Suppress("ReturnCount")
    private fun coincide(
        actividad: ActividadDto,
        filtros: HistorialFiltros,
    ): Boolean {
        if (filtros.idEmpresa != null && actividad.idEmpresa != filtros.idEmpresa) {
            return false
        }
        if (filtros.idOportunidad != null && actividad.idOportunidad != filtros.idOportunidad) {
            return false
        }
        return true
    }

    private fun paginar(
        todas: List<ActividadDto>,
        page: Int?,
        perPage: Int?,
    ): Paginado<ActividadDto> {
        val pagina = (page ?: 1).coerceAtLeast(1)
        val tamano = (perPage ?: Paginacion.PER_PAGE_DEFAULT).coerceIn(1, Paginacion.PER_PAGE_MAX)
        val items = todas.drop((pagina - 1) * tamano).take(tamano)
        val meta: PageMeta = Paginacion.meta(pagina, tamano, todas.size.toLong())
        return Paginado(items, meta)
    }

    /** Una tarea NUNCA tiene `fechaDia`: `fecha_ejecucion` es TIMESTAMP (Trampa 1). */
    private fun TareaDto.aActividad(comentarios: Int) =
        ActividadDto(
            tipo = TipoActividad.tarea.name,
            id = id,
            titulo = tipoAccion,
            descripcion = descripcion,
            estado = estadoAccion,
            fechaHora = fechaEjecucion,
            fechaDia = null,
            idEmpresa = idEmpresa,
            empresa = empresa,
            idOportunidad = idOportunidad,
            idEmpleado = idAsignado,
            empleado = asignado,
            comentarios = comentarios,
            createdAt = createdAt,
        )

    /**
     * Un evento se atribuye a `createdBy`: no tiene asignado (Trampa 3).
     * `fechaEstimada` es DATE y va en `fechaDia`; `fechaOcurrencia` es TIMESTAMP
     * y va en `fechaHora`. No los cruces.
     */
    private fun EventoDto.aActividad(
        comentarios: Int,
        empresas: Map<Long, pe.quantum.crm.domain.empresas.dto.EmpresaResumen>,
        empleados: Map<Long, pe.quantum.crm.domain.empleados.dto.EmpleadoResumen>,
    ) = ActividadDto(
        tipo = TipoActividad.evento.name,
        id = id,
        titulo = nombre,
        descripcion = descripcion,
        estado = estado,
        fechaHora = fechaOcurrencia,
        fechaDia = fechaEstimada,
        idEmpresa = idEmpresa,
        empresa = idEmpresa?.let { empresas[it] },
        idOportunidad = idOportunidad,
        idEmpleado = createdBy,
        empleado = empleados[createdBy],
        comentarios = comentarios,
        createdAt = createdAt,
    )
}
