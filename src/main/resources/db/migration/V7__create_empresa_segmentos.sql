-- =============================================================================
-- V7 — Segmentos de empresa (multi-select)
-- Una empresa puede operar en más de un segmento de transporte.
-- =============================================================================

CREATE TABLE empresa_segmentos (
    id_empresa  BIGINT          NOT NULL REFERENCES empresas(id) ON DELETE CASCADE,
    segmento    segmento_enum   NOT NULL,
    PRIMARY KEY (id_empresa, segmento)
);

COMMENT ON TABLE empresa_segmentos IS 'Multi-select de segmento por empresa. CASCADE: al eliminar empresa se eliminan sus segmentos.';
