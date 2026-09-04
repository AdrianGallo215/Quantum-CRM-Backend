package pe.quantum.crm.shared.enums

/**
 * Valores de `modo_simulacion_enum` (migracion V43, renumerada desde V40). En minuscula a proposito:
 * deben coincidir con las etiquetas del enum nativo de PostgreSQL, que Hibernate
 * mapea por nombre via `@JdbcTypeCode(NAMED_ENUM)`.
 *
 * `modo` es INMUTABLE tras la creacion (reglas_simulaciones.md §2): leasing y
 * credito directo usan formulas y columnas de cronograma distintas.
 */
@Suppress("ktlint:standard:enum-entry-name-case", "EnumNaming", "EnumEntryName")
enum class ModoSimulacion {
    leasing,
    credito_directo,
}
