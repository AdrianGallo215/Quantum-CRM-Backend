package pe.quantum.crm.shared.enums

/**
 * Enums del sistema de solicitudes de aprobacion (migracion V26). En minuscula
 * para coincidir con las etiquetas de los enums nativos de PostgreSQL.
 */
@Suppress("ktlint:standard:enum-entry-name-case", "EnumNaming", "EnumEntryName")
enum class TipoSolicitud {
    descuento,
    reasignacion_cliente,
}

@Suppress("ktlint:standard:enum-entry-name-case", "EnumNaming", "EnumEntryName")
enum class EstadoSolicitud {
    pendiente,
    aprobada,
    denegada,
}

@Suppress("ktlint:standard:enum-entry-name-case", "EnumNaming", "EnumEntryName")
enum class AprobadorSolicitud {
    jdv,
    gerencia,
}

@Suppress("ktlint:standard:enum-entry-name-case", "EnumNaming", "EnumEntryName")
enum class EntidadSolicitud {
    oportunidad,
    empresa,
}
