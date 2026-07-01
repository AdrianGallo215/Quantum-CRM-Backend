-- =============================================================================
-- V5 — Financiadoras
-- Calidda se inserta en V17 (seed).
-- Los campos de monto/plazo/tea/cuota son NULL para financiadoras negociables.
-- Solo puede haber una financiadora con es_default = true (unique index parcial).
-- =============================================================================

CREATE TABLE financiadoras (
    id                  BIGSERIAL       PRIMARY KEY,
    nombre              TEXT            NOT NULL,
    monto_por_unidad    NUMERIC(12,2),
    plazo_meses         INT,
    tea                 NUMERIC(6,4),
    cuota_por_unidad    NUMERIC(12,2),
    es_default          BOOLEAN         NOT NULL DEFAULT false,
    notas               TEXT
);

-- Garantiza que solo una financiadora sea el default.
CREATE UNIQUE INDEX uq_financiadora_default
    ON financiadoras(es_default)
    WHERE es_default = true;

COMMENT ON TABLE  financiadoras                  IS 'Entidades que financian parte de la operación. Calidda es la default.';
COMMENT ON COLUMN financiadoras.monto_por_unidad IS 'NULL si los términos son negociables por operación.';
COMMENT ON COLUMN financiadoras.cuota_por_unidad IS 'Calculada y almacenada. Para Calidda: 45000 / 48 = 937.50.';
COMMENT ON COLUMN financiadoras.es_default       IS 'Solo una puede ser true. Aplicada automáticamente al crear una oportunidad sin financiadora explícita.';
