package pe.quantum.crm.domain.tareas.dto

import pe.quantum.crm.domain.contactos.dto.ContactoResumen
import pe.quantum.crm.domain.empleados.dto.EmpleadoResumen
import pe.quantum.crm.domain.empresas.dto.EmpresaResumen
import pe.quantum.crm.shared.enums.TipoAccion
import java.time.Instant
import java.time.LocalDateTime

/** Tarea expuesta en respuestas (contrato_api.md §12). */
data class TareaDto(
    val id: Long,
    val idEmpresa: Long,
    val empresa: EmpresaResumen?,
    val idOportunidad: Long?,
    val idContacto: Long?,
    val contacto: ContactoResumen?,
    val idAsignado: Long?,
    val asignado: EmpleadoResumen?,
    val idsColaboradores: List<Long>,
    val colaboradores: List<EmpleadoResumen>,
    val tipoAccion: String,
    val estadoAccion: String,
    val descripcion: String?,
    val fechaEjecucion: LocalDateTime?,
    val createdAt: LocalDateTime,
)

/** Body de `POST /tareas`. */
data class CrearTareaRequest(
    val idEmpresa: Long,
    val idOportunidad: Long? = null,
    val idContacto: Long? = null,
    val idAsignado: Long? = null,
    val idsColaboradores: List<Long> = emptyList(),
    val tipoAccion: TipoAccion,
    val descripcion: String? = null,
    val fechaEjecucion: Instant? = null,
)

/**
 * Body de `PUT /tareas/:id` (solo tareas pendientes). `idsColaboradores`, si
 * viene (aunque sea `[]`), reemplaza el set completo de colaboradores; si se
 * omite (`null`), los colaboradores existentes no se tocan.
 */
data class ActualizarTareaRequest(
    val tipoAccion: TipoAccion? = null,
    val descripcion: String? = null,
    val fechaEjecucion: Instant? = null,
    val idContacto: Long? = null,
    val idAsignado: Long? = null,
    val idsColaboradores: List<Long>? = null,
)

/** Body opcional de `PATCH /tareas/:id/completada`. */
data class CompletarTareaRequest(
    val descripcion: String? = null,
)

/** Filtros del listado de tareas (contrato §12). */
data class TareaFiltros(
    val idEmpresa: Long? = null,
    val idOportunidad: Long? = null,
    val estadoAccion: String? = null,
    val idAsignado: Long? = null,
    val soloProspeccion: Boolean = false,
    val vencidas: Boolean = false,
)

/** Proyeccion de solo lectura para el job de recordatorios (notificaciones). */
data class TareaRecordatorioProyeccion(
    val id: Long,
    val idAsignado: Long,
    val idEmpresa: Long,
    val idOportunidad: Long?,
    val fechaEjecucion: LocalDateTime,
)
