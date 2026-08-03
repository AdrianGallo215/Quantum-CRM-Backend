-- =============================================================================
-- V33 — oportunidades.facturado_en: marca cuándo una oportunidad entró en
-- estado 'facturado'. Se limpia a NULL cuando sale de 'facturado' (retrocede o
-- se cierra). Fuente del cómputo de cumplimiento de metas de venta: una suma en
-- vivo sobre esta columna, sin contador aparte que pueda desincronizarse.
-- =============================================================================

ALTER TABLE oportunidades ADD COLUMN facturado_en TIMESTAMP NULL;

CREATE INDEX idx_oportunidades_facturado_en ON oportunidades(id_vendedor, facturado_en) WHERE estado = 'facturado';

-- Backfill: oportunidades ya facturadas toman el changed_at de su transición
-- más reciente a 'facturado' en el log de estados, para no perder ventas
-- históricas del cómputo de cumplimiento.
UPDATE oportunidades o
SET facturado_en = (
    SELECT MAX(l.changed_at)
    FROM oportunidad_estados_log l
    WHERE l.id_oportunidad = o.id AND l.estado_nuevo = 'facturado'
)
WHERE o.estado = 'facturado';

COMMENT ON COLUMN oportunidades.facturado_en IS 'Momento en que la oportunidad entró en estado facturado. NULL si nunca facturó o si salió de facturado. Fuente del cómputo de metas de venta.';
