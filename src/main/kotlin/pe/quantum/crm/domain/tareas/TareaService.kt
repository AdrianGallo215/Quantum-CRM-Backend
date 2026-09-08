package pe.quantum.crm.domain.tareas

import pe.quantum.crm.domain.tareas.dto.ActividadContactoDto
import pe.quantum.crm.domain.tareas.dto.ActualizarTareaRequest
import pe.quantum.crm.domain.tareas.dto.CrearTareaRequest
import pe.quantum.crm.domain.tareas.dto.TareaDto
import pe.quantum.crm.domain.tareas.dto.TareaFiltros
import pe.quantum.crm.domain.tareas.dto.TareaRecordatorioProyeccion
import pe.quantum.crm.domain.tareas.dto.TareaVinculo
import pe.quantum.crm.shared.Paginado
import pe.quantum.crm.shared.security.UsuarioActual
import java.time.Instant

/**
 * Interfaz publica del modulo tareas. `listar` arrastra los 4 parametros de
 * paginacion del contrato (page, per_page, sort, dir).
 */
@Suppress("LongParameterList", "TooManyFunctions")
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

    /**
     * Ids de oportunidad donde `idEmpleado` colabora en alguna tarea. Es la API
     * publica que usan `oportunidades` y `empresas` para su filtro de visibilidad
     * de roles de apoyo: cruzan la frontera con ids, nunca con entidades.
     */
    fun idsOportunidadesDondeColabora(idEmpleado: Long): Set<Long>

    /** Ids de empresa donde `idEmpleado` colabora en alguna tarea. */
    fun idsEmpresasDondeColabora(idEmpleado: Long): Set<Long>

    /**
     * Tareas asignadas a un empleado, filtradas por rango de `created_at`
     * (ambos extremos opcionales). Aplica el MISMO filtro de visibilidad que
     * `listar`: un rol restringido solo recibe aquellas de las que es dueño o
     * colaborador, aunque pida el id de otro empleado.
     */
    fun listarPorEmpleado(
        idEmpleado: Long,
        desde: Instant?,
        hasta: Instant?,
        usuario: UsuarioActual,
    ): List<TareaDto>

    /**
     * Datos minimos de una tarea, comprobando visibilidad. `NoEncontradoException`
     * (404, nunca 403) si no existe o queda fuera del alcance del usuario.
     */
    fun vinculoVisible(
        id: Long,
        usuario: UsuarioActual,
    ): TareaVinculo
}
