package pe.quantum.crm.domain.simulaciones

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.JpaSpecificationExecutor
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query

interface SimulacionRepository :
    JpaRepository<Simulacion, Long>,
    JpaSpecificationExecutor<Simulacion> {
    /**
     * Desmarca la principal vigente de un ítem. Debe ejecutarse ANTES de
     * insertar la nueva principal, o el índice único parcial
     * `uq_simulacion_principal` aborta la transacción (K14/D38 de
     * plan-09-mapa-simulaciones-modulo.md).
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE Simulacion s SET s.esPrincipal = false WHERE s.idOportunidadItem = :idItem AND s.esPrincipal = true")
    fun desmarcarPrincipalDe(idItem: Long): Int

    /**
     * El correlativo `#{n}` del nombre autogenerado (§8.1 de
     * reglas_simulaciones.md). El correlativo cuenta dentro del mismo ítem;
     * para las simulaciones no enlazadas (`id_oportunidad_item IS NULL`) el
     * scope pasa a ser `(id_modelo, modo)` — en Postgres `PARTITION BY` agrupa
     * todos los NULL de `id_oportunidad_item` juntos, y por eso las dos
     * columnas `CASE` los vuelven a separar por modelo y modo. §8.1 dice
     * explícitamente que para las no enlazadas este número no es un dato
     * crítico.
     *
     * Una sola consulta para toda la página de resultados: evita el N+1 de
     * calcular el correlativo simulación por simulación.
     */
    @Query(
        value = """
            SELECT t.id AS id, t.correlativo AS correlativo
            FROM (
                SELECT s.id,
                       ROW_NUMBER() OVER (
                           PARTITION BY s.id_oportunidad_item,
                                        (CASE WHEN s.id_oportunidad_item IS NULL THEN s.id_modelo END),
                                        (CASE WHEN s.id_oportunidad_item IS NULL THEN s.modo END)
                           ORDER BY s.created_at, s.id
                       ) AS correlativo
                FROM simulaciones s
            ) t
            WHERE t.id IN (:ids)
        """,
        nativeQuery = true,
    )
    fun correlativos(ids: Collection<Long>): List<CorrelativoProjection>
}

/** Fila de [SimulacionRepository.correlativos]: el `#{n}` del nombre autogenerado (§8.1). */
interface CorrelativoProjection {
    fun getId(): Long

    fun getCorrelativo(): Int
}
