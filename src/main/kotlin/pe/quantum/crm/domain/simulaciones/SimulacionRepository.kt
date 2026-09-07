package pe.quantum.crm.domain.simulaciones

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.JpaSpecificationExecutor
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import java.time.LocalDateTime

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
     * La simulacion principal de cada item, si la tiene (§6.3: una sola por
     * item, garantizada por `uq_simulacion_principal`). Por lotes: una
     * consulta para toda la pagina, nunca una por item.
     */
    fun findByIdOportunidadItemInAndEsPrincipalTrue(idsItem: Collection<Long>): List<Simulacion>

    /**
     * Simulaciones huerfanas mas antiguas que `limite` (§5 de
     * reglas_simulaciones.md: 30 dias sin enlazar a un item). Derivada de
     * Spring Data; parametrizada, sin concatenacion (CLAUDE.md regla 11).
     *
     * La alimenta [SimulacionService.purgarHuerfanas], que las borra por hard
     * delete tras registrar su evento `eliminada`.
     */
    fun findByIdOportunidadItemIsNullAndCreatedAtBefore(limite: LocalDateTime): List<Simulacion>

    /**
     * Huerfanas cuya antiguedad cruza la frontera del preaviso en esta corrida
     * (decision D58 de plan-13-mapa-cierre-simulaciones.md): `created_at` en
     * `(desde, hasta]`. La ventana es de 24 h y el job corre cada 24 h, asi que
     * cada simulacion cae en ella UNA sola vez en toda su vida; por eso no hace
     * falta recordar a quien ya se aviso.
     *
     * JPQL y no una derivada `...CreatedAtBetween`: `Between` de Spring Data es
     * inclusiva en los DOS extremos, y el `hasta` de una corrida es exactamente
     * el `desde` de la siguiente. Una simulacion creada justo en ese instante
     * caeria en las dos ventanas y recibiria el aviso dos veces. El `>` estricto
     * del limite inferior es lo que hace que las ventanas teselen sin solaparse.
     */
    @Query(
        """
        SELECT s FROM Simulacion s
        WHERE s.idOportunidadItem IS NULL
          AND s.createdAt > :desde
          AND s.createdAt <= :hasta
        """,
    )
    fun findHuerfanasCreadasEntre(
        desde: LocalDateTime,
        hasta: LocalDateTime,
    ): List<Simulacion>

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
