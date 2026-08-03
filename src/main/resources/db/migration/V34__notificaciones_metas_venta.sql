-- =============================================================================
-- V34 — Notificaciones del sistema de metas de venta.
-- =============================================================================

ALTER TYPE tipo_notificacion_enum ADD VALUE 'meta_propuesta';
ALTER TYPE tipo_notificacion_enum ADD VALUE 'meta_aprobada';
ALTER TYPE tipo_notificacion_enum ADD VALUE 'meta_rechazada';
ALTER TYPE tipo_notificacion_enum ADD VALUE 'meta_modificada';

ALTER TYPE entidad_notificacion_enum ADD VALUE 'meta_venta';
