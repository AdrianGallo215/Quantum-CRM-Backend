-- =============================================================================
-- V23 — Backfill: sincronizar id_vendedor de oportunidades activas con su empresa
-- Corrige el drift causado por reasignaciones de empresa anteriores a este cambio
-- de regla (reglas_negocio.md §8): desde ahora, el vendedor de una oportunidad
-- activa siempre debe igualar al vendedor de su empresa. Corrida unica de backfill;
-- de aqui en adelante la sincronizacion la hace el evento VendedorEmpresaReasignadoEvent.
-- =============================================================================

UPDATE oportunidades o
SET id_vendedor = e.id_vendedor,
    updated_at = CURRENT_TIMESTAMP
FROM empresas e
WHERE o.id_empresa = e.id
  AND o.estado NOT IN ('facturado', 'cerrado')
  AND e.id_vendedor IS NOT NULL
  AND o.id_vendedor <> e.id_vendedor;
