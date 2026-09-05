-- =============================================================================
-- V46 — Retira las columnas planas de `oportunidades` que el rediseno
-- multi-modelo (V42-V45) dejo como codigo puente mientras reportes/inicio no
-- migraban a leer `oportunidad_items` directamente (plan-05-mapa-migrar-items.md,
-- decision D21). Con ese codigo ya retirado (plan-07-mapa-retirar-columnas.md,
-- decision D27), estas columnas no tienen ningun lector.
--
-- Sin backfill: todo lo que hay aqui ya esta duplicado en `oportunidad_items`
-- desde el backfill de V42, y se mantuvo sincronizado hasta ahora por D21.
-- Verificado contra produccion antes de escribir esta migracion: 5
-- oportunidades, 5 items, ninguna oportunidad con mas de un item todavia.
-- =============================================================================

ALTER TABLE oportunidades
    DROP COLUMN cantidad,
    DROP COLUMN precio_unitario,
    DROP COLUMN dcto,
    DROP COLUMN monto_total,
    DROP COLUMN id_modelo;
