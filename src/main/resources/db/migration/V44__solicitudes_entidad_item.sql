-- =============================================================================
-- V44 — `entidad_solicitud_enum` gana el valor `oportunidad_item`, para que
-- una solicitud de descuento (TipoSolicitud.descuento) referencie el ítem
-- concreto en vez de la oportunidad completa: con varios ítems por
-- oportunidad, "el descuento de la oportunidad" ya no es una sola cosa
-- (docs/planes/plan-05-mapa-migrar-items.md, decision D12).
--
-- Sin backfill: no hay solicitudes de descuento pendientes en produccion.
-- ALTER TYPE ... ADD VALUE no puede combinarse en la misma transaccion que un
-- INSERT/UPDATE que ya use el enum, por eso esta migracion no hace nada mas.
-- =============================================================================

ALTER TYPE entidad_solicitud_enum ADD VALUE 'oportunidad_item';
