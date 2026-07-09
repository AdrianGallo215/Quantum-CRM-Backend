package pe.quantum.crm.domain.notificaciones

/** Valores de `tipo_notificacion_enum` (migracion V22). */
enum class TipoNotificacion {
    oportunidad_cambio_estado,
    empresa_convertida,
    evento_creado,
    tarea_creada,
    empresa_asignada,
    oportunidad_traspasada,
    tarea_recordatorio,
    evento_recordatorio,
}

/** Valores de `entidad_notificacion_enum` (migracion V22). */
enum class EntidadNotificacion {
    oportunidad,
    empresa,
}
