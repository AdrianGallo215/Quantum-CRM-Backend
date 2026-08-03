package pe.quantum.crm.domain.empleados

import org.springframework.data.jpa.repository.JpaRepository

/**
 * Acceso a `empleados`. La busqueda por email es la puerta de entrada de la
 * autenticacion (B0.8). Spring Data genera la query parametrizada.
 */
interface EmpleadoRepository : JpaRepository<Empleado, Long> {
    fun findByEmail(email: String): Empleado?

    fun existsByEmail(email: String): Boolean

    fun findByActivo(activo: Boolean): List<Empleado>

    fun findByActivoAndRol(
        activo: Boolean,
        rol: RolEmpleado,
    ): List<Empleado>

    /** Admins activos distintos al indicado: la guarda de no-lockout (B1.4). */
    fun countByRolAndActivoTrueAndIdNot(
        rol: RolEmpleado,
        id: Long,
    ): Long

    /**
     * Broadcast de supervisores (sin jerarquia jdv->vendedor en el esquema; ver
     * docs/superpowers/specs/2026-07-09-notificaciones-in-app-design.md).
     */
    fun findByActivoTrueAndRolIn(roles: Collection<RolEmpleado>): List<Empleado>
}
