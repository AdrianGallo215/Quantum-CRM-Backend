package pe.quantum.crm.domain.empleados

import org.springframework.data.jpa.repository.JpaRepository

/**
 * Acceso a `empleados`. La busqueda por email es la puerta de entrada de la
 * autenticacion (B0.8). Spring Data genera la query parametrizada.
 */
interface EmpleadoRepository : JpaRepository<Empleado, Long> {
    fun findByEmail(email: String): Empleado?
}
