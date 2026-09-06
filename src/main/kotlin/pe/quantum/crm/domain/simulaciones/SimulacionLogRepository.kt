package pe.quantum.crm.domain.simulaciones

import org.springframework.data.jpa.repository.JpaRepository

/**
 * Bitácora de `simulacion_log`: solo INSERT, nunca UPDATE ni DELETE
 * (reglas_simulaciones.md §7). Sin métodos propios en Plan D — el historial con
 * diff y la ventana de restauración son de Plan E — pero se declara ya para que
 * `SimulacionServiceImpl` pueda registrar eventos desde D9 sin depender de un
 * `EntityManager` crudo.
 */
interface SimulacionLogRepository : JpaRepository<SimulacionLog, Long>
