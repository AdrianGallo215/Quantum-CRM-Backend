package pe.quantum.crm.shared.enums

/**
 * Enum del sistema de metas de venta (migracion V32). En minuscula para
 * coincidir con las etiquetas del enum nativo `estado_meta_enum` de PostgreSQL.
 */
@Suppress("ktlint:standard:enum-entry-name-case", "EnumNaming", "EnumEntryName")
enum class EstadoMeta {
    propuesta,
    aprobada,
    rechazada,
}
