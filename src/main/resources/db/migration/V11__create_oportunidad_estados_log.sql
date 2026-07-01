-- =============================================================================
-- V11 — Log de estados de oportunidad
-- Fuente de verdad para historial del pipeline.
-- USOS CRÍTICOS:
--   1. Pronta facturación:
--      SELECT MIN(changed_at) FROM oportunidad_estados_log
--      WHERE id_oportunidad = ? AND estado_nuevo = 'documentos_legales'
--      → si (NOW() - resultado) <= 30 días → aplica pronta facturación
--   2. Velocidad por etapa: tiempo entre estados consecutivos.
--   3. Historial visible en UI (sección detalle de oportunidad).
-- =============================================================================

CREATE TABLE oportunidad_estados_log (
    id              BIGSERIAL       PRIMARY KEY,
    id_oportunidad  BIGINT          NOT NULL REFERENCES oportunidades(id) ON DELETE CASCADE,
    estado_anterior estado_op_enum,
    estado_nuevo    estado_op_enum  NOT NULL,
    changed_at      TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    changed_by      BIGINT          NOT NULL REFERENCES empleados(id)
);

-- Índice compuesto para la query de pronta facturación (frecuente).
CREATE INDEX idx_op_estados_log_oportunidad
    ON oportunidad_estados_log(id_oportunidad, estado_nuevo, changed_at);

COMMENT ON TABLE  oportunidad_estados_log                IS 'Inmutable. Solo INSERT. Nunca UPDATE ni DELETE.';
COMMENT ON COLUMN oportunidad_estados_log.estado_anterior IS 'NULL únicamente en el primer registro de cada oportunidad (creación).';
