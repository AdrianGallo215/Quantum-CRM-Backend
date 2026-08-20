package pe.quantum.crm.domain.tareas

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.JpaSpecificationExecutor
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import pe.quantum.crm.shared.enums.EstadoAccion
import java.time.LocalDateTime

interface TareaRepository :
    JpaRepository<Tarea, Long>,
    JpaSpecificationExecutor<Tarea> {
    /**
     * Tareas pendientes cuya fecha cae dentro de la ventana que el job puede
     * notificar. `Between` ya excluye los nulos, asi que sustituye tambien al
     * `FechaEjecucionIsNotNull` anterior.
     */
    fun findByEstadoAccionAndIdAsignadoIsNotNullAndFechaEjecucionBetween(
        estadoAccion: EstadoAccion,
        desde: LocalDateTime,
        hasta: LocalDateTime,
    ): List<Tarea>

    @Query(
        """
        SELECT t FROM Tarea t
        WHERE t.idContacto = :idContacto
        ORDER BY COALESCE(t.fechaEjecucion, t.createdAt) DESC
        """,
    )
    fun findByIdContactoOrdenado(idContacto: Long): List<Tarea>

    /**
     * Ids de oportunidad de las tareas donde `idEmpleado` figura como colaborador
     * (tabla `tarea_responsables`). Excluye las tareas de prospeccion, que no
     * tienen oportunidad. Se resuelve en SQL, no en memoria.
     */
    @Query(
        """
        SELECT DISTINCT t.idOportunidad FROM Tarea t
        WHERE t.idOportunidad IS NOT NULL
          AND t.id IN (SELECT r.id.idTarea FROM TareaResponsable r WHERE r.id.idEmpleado = :idEmpleado)
        """,
    )
    fun idsOportunidadConColaborador(
        @Param("idEmpleado") idEmpleado: Long,
    ): List<Long>

    /** Ids de empresa de las tareas donde `idEmpleado` figura como colaborador. */
    @Query(
        """
        SELECT DISTINCT t.idEmpresa FROM Tarea t
        WHERE t.id IN (SELECT r.id.idTarea FROM TareaResponsable r WHERE r.id.idEmpleado = :idEmpleado)
        """,
    )
    fun idsEmpresaConColaborador(
        @Param("idEmpleado") idEmpleado: Long,
    ): List<Long>
}

/** Colaboradores de tareas (tabla `tarea_responsables`, migracion V31). */
interface TareaResponsableRepository : JpaRepository<TareaResponsable, TareaResponsableId> {
    fun findByIdIdTarea(idTarea: Long): List<TareaResponsable>

    fun findByIdIdTareaIn(idsTarea: Collection<Long>): List<TareaResponsable>

    fun existsByIdIdTareaAndIdIdEmpleado(
        idTarea: Long,
        idEmpleado: Long,
    ): Boolean

    fun deleteByIdIdTarea(idTarea: Long)
}
