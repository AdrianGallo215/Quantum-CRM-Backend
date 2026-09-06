package pe.quantum.crm.shared.enums

/**
 * Valores de `tipo_evento_simulacion_enum` (migracion V43). En minuscula a
 * proposito: deben coincidir con las etiquetas del enum nativo de PostgreSQL,
 * que Hibernate mapea por nombre via `@JdbcTypeCode(NAMED_ENUM)`.
 *
 * `simulacion_log` es solo INSERT y sin purga (reglas_simulaciones.md §7).
 */
@Suppress("ktlint:standard:enum-entry-name-case", "EnumNaming", "EnumEntryName")
enum class TipoEventoSimulacion {
    creada,
    editada,
    restaurada,
    marcada_principal,
    enlazada_a_item,
    eliminada,
}
