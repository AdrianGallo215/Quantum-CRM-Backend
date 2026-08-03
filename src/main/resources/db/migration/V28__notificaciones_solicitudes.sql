-- =============================================================================
-- V28 — Notificaciones del sistema de solicitudes. Ver
-- docs/gerencia_solicitudes_modelo_datos.md §5.
-- =============================================================================

ALTER TYPE tipo_notificacion_enum ADD VALUE 'solicitud_creada';
ALTER TYPE tipo_notificacion_enum ADD VALUE 'solicitud_aprobada';
ALTER TYPE tipo_notificacion_enum ADD VALUE 'solicitud_denegada';

ALTER TYPE entidad_notificacion_enum ADD VALUE 'solicitud';
