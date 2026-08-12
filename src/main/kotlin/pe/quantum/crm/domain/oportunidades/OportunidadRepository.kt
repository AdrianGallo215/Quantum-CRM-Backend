package pe.quantum.crm.domain.oportunidades

import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.JpaSpecificationExecutor
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.transaction.annotation.Transactional
import pe.quantum.crm.shared.enums.EstadoOportunidad

interface OportunidadRepository :
    JpaRepository<Oportunidad, Long>,
    JpaSpecificationExecutor<Oportunidad> {
    fun existsByIdEmpresaAndEstado(
        idEmpresa: Long,
        estado: EstadoOportunidad,
    ): Boolean

    fun existsByIdEmpresaAndEstadoIn(
        idEmpresa: Long,
        estados: Collection<EstadoOportunidad>,
    ): Boolean

    fun findByIdEmpresaAndEstadoIn(
        idEmpresa: Long,
        estados: Collection<EstadoOportunidad>,
    ): List<Oportunidad>

    /**
     * Oportunidad bloqueada para escritura (`SELECT ... FOR UPDATE`). La usa
     * `cambiarEstado`: dos PATCH concurrentes sobre la misma fila leian el mismo
     * `estado_anterior` y escribian dos filas de log con el mismo origen,
     * corrompiendo el historial del que sale la pronta facturacion.
     *
     * El bloqueo es corto y no envuelve ninguna llamada de red: la transaccion de
     * `cambiarEstado` no habla con Drive (a diferencia de `asegurarCarpetaDrive`,
     * que por eso resuelve la exclusion con un UPDATE condicional en vez de un lock).
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from Oportunidad o where o.id = :id")
    fun findByIdBloqueando(
        @Param("id") id: Long,
    ): Oportunidad?

    /** Ids de oportunidades sin carpeta de Drive (backfill, ver modulo `mantenimiento`). */
    @Query("select o.id from Oportunidad o where o.driveFolderId is null order by o.id")
    fun findIdsSinCarpetaDrive(): List<Long>

    /** Carpeta de Drive de la oportunidad, leida de la BD sin pasar por el contexto de persistencia. */
    @Query("select o.driveFolderId from Oportunidad o where o.id = :id")
    fun findDriveFolderId(
        @Param("id") id: Long,
    ): String?

    /**
     * Fija la carpeta de Drive SOLO si la oportunidad aun no tiene ninguna, y
     * devuelve las filas afectadas (1 = la fijo esta peticion, 0 = otra se
     * adelanto). Misma garantia que el `SELECT ... FOR UPDATE` que sustituye — el
     * `WHERE ... IS NULL` solo puede casar una vez — sin retener la fila durante la
     * llamada a Drive. Gemelo de `EmpresaRepository.asignarCarpetaDriveSiFalta`.
     */
    @Modifying
    @Transactional
    @Query("update Oportunidad o set o.driveFolderId = :carpeta where o.id = :id and o.driveFolderId is null")
    fun asignarCarpetaDriveSiFalta(
        @Param("id") id: Long,
        @Param("carpeta") carpeta: String,
    ): Int
}

interface OportunidadEstadoLogRepository : JpaRepository<OportunidadEstadoLog, Long> {
    fun findByIdOportunidadOrderByChangedAtAscIdAsc(idOportunidad: Long): List<OportunidadEstadoLog>

    fun findFirstByIdOportunidadOrderByChangedAtDescIdDesc(idOportunidad: Long): OportunidadEstadoLog?
}

/** Proyeccion del conteo por lote del listado de contactos. */
interface ConteoPorContacto {
    val idContacto: Long
    val total: Long
}

interface OportunidadContactoRepository : JpaRepository<OportunidadContacto, OportunidadContactoId> {
    fun findByIdIdOportunidad(idOportunidad: Long): List<OportunidadContacto>

    fun findByIdIdContacto(idContacto: Long): List<OportunidadContacto>

    /**
     * Vinculos del contacto que un rol puede ver: el conteo del listado de contactos
     * debe cuadrar con las oportunidades que ese rol realmente alcanza
     * (matriz_permisos.md §1). `idVendedor` null = supervisor, cuenta todos. El
     * filtro se resuelve en SQL, no en memoria.
     */
    @Query(
        "select count(oc) from OportunidadContacto oc, Oportunidad o " +
            "where o.id = oc.id.idOportunidad and oc.id.idContacto = :idContacto " +
            "and (:idVendedor is null or o.idVendedor = :idVendedor)",
    )
    fun countVisiblesPorContacto(
        @Param("idContacto") idContacto: Long,
        @Param("idVendedor") idVendedor: Long?,
    ): Long

    /**
     * Conteo por lote para el listado de contactos: misma regla de visibilidad que
     * [countVisiblesPorContacto], pero UNA consulta para toda la pagina en vez de una
     * por fila. Los contactos sin ninguna oportunidad visible no salen en el
     * resultado; el llamador los resuelve a 0.
     */
    @Query(
        "select oc.id.idContacto as idContacto, count(oc) as total " +
            "from OportunidadContacto oc, Oportunidad o " +
            "where o.id = oc.id.idOportunidad and oc.id.idContacto in :idsContacto " +
            "and (:idVendedor is null or o.idVendedor = :idVendedor) " +
            "group by oc.id.idContacto",
    )
    fun contarVisiblesPorContactos(
        @Param("idsContacto") idsContacto: Collection<Long>,
        @Param("idVendedor") idVendedor: Long?,
    ): List<ConteoPorContacto>
}
