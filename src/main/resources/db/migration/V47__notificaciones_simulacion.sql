-- =============================================================================
-- V47 — Valores de enum para el aviso de expiracion de simulaciones huerfanas
-- (reglas_simulaciones.md §5: aviso al creador 3 dias antes del borrado a los
-- 30 dias). El encargo autoriza expresamente esta migracion de enums.
--
-- Los dos valores se AGREGAN aqui y se USAN despues, desde el job: nunca en
-- esta misma transaccion. `origen_recordatorio_enum` NO se toca — el aviso
-- deduplica por ventana de 24 h, sin `recordatorios_enviados`
-- (plan-13-mapa-cierre-simulaciones.md, decision D58).
-- =============================================================================

ALTER TYPE tipo_notificacion_enum   ADD VALUE 'simulacion_por_expirar';
ALTER TYPE entidad_notificacion_enum ADD VALUE 'simulacion';
