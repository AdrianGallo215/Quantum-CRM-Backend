package pe.quantum.crm.domain.empleados

/**
 * Rol de un empleado; controla visibilidad y permisos (matriz_permisos.md).
 *
 * Los nombres estan en minuscula a proposito: deben coincidir exactamente con las
 * etiquetas del enum nativo `rol_empleado` de PostgreSQL (migracion V1), que
 * Hibernate mapea por nombre via `@JdbcTypeCode(NAMED_ENUM)`. Por eso se suprimen
 * las reglas de naming de enums (que exigen MAYUSCULAS).
 */
@Suppress("ktlint:standard:enum-entry-name-case", "EnumNaming")
enum class RolEmpleado {
    admin,
    gerente,
    jdv,
    vendedor,
    analista,
    otro,
}
