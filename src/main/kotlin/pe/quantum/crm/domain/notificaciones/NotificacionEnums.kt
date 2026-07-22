package pe.quantum.crm.domain.notificaciones

/** Valores de `tipo_notificacion_enum` (migracion V22). */
@Suppress("ktlint:standard:enum-entry-name-case", "EnumNaming", "EnumEntryName")
enum class TipoNotificacion {
    oportunidad_cambio_estado,
    empresa_convertida,
    evento_creado,
    tarea_creada,
    empresa_asignada,
    oportunidad_traspasada,
    tarea_recordatorio,
    evento_recordatorio,
    solicitud_creada,
    solicitud_aprobada,
    solicitud_denegada,
    meta_propuesta,
    meta_aprobada,
    meta_rechazada,
    meta_modificada,
}

/** Valores de `entidad_notificacion_enum` (migracion V22, V28). */
@Suppress("ktlint:standard:enum-entry-name-case", "EnumNaming", "EnumEntryName")
enum class EntidadNotificacion {
    oportunidad,
    empresa,
    solicitud,
    meta_venta,
}
