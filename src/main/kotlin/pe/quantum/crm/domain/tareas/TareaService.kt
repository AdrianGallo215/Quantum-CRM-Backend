package pe.quantum.crm.domain.tareas

import pe.quantum.crm.domain.tareas.dto.ActividadContactoDto
import pe.quantum.crm.domain.tareas.dto.ActualizarTareaRequest
import pe.quantum.crm.domain.tareas.dto.CrearTareaRequest
import pe.quantum.crm.domain.tareas.dto.TareaDto
import pe.quantum.crm.domain.tareas.dto.TareaFiltros
import pe.quantum.crm.domain.tareas.dto.TareaRecordatorioProyeccion
import pe.quantum.crm.shared.Paginado
import pe.quantum.crm.shared.security.UsuarioActual

/**
 * Interfaz publica del modulo tareas. `listar` arrastra los 4 parametros de
 * paginacion del contrato (page, per_page, sort, dir).
 */
@Suppress("LongParameterList")
interface TareaService {
    fun listar(
        filtros: TareaFiltros,
        usuario: UsuarioActual,
        page: Int?,
        perPage: Int?,
        sort: String?,
        dir: String?,
    ): Paginado<TareaDto>

    /**
     * Crea una tarea. `id_oportunidad = null` → prospeccion; se rechaza si la
     * empresa tiene oportunidades activas (reglas §10.2). `id_asignado` default
     * al usuario autenticado.
     */
    fun crear(
        request: CrearTareaRequest,
        usuario: UsuarioActual,
    ): TareaDto

    fun completar(
        id: Long,
        descripcion: String?,
        usuario: UsuarioActual,
    ): TareaDto

    fun cancelar(
        id: Long,
        usuario: UsuarioActual,
    ): TareaDto

    /** Solo tareas pendientes (contrato §12). */
    fun actualizar(
        id: Long,
        request: ActualizarTareaRequest,
        usuario: UsuarioActual,
    ): TareaDto

    /** Para el job de recordatorios (notificaciones): tareas pendientes, asignadas, con fecha. */
    fun pendientesParaRecordatorio(): List<TareaRecordatorioProyeccion>

    /** Tareas de un contacto como linea de tiempo (detalle de contacto, §9). vendedor/analista solo ven las suyas. */
    fun actividadesPorContacto(
        idContacto: Long,
        usuario: UsuarioActual,
    ): List<ActividadContactoDto>
}
