-- =============================================================================
-- V16 — Buses entregados (POST-MVP)
-- La tabla existe desde el inicio para no requerir una migración posterior,
-- pero no tiene endpoints de escritura en el MVP.
-- numero_serie y vin llegan NULL al crear el registro y se completan
-- al nacionalizar las unidades (cuando llegan de China a Perú).
-- url_contrato = contrato individual Quantum–cliente (distinto al tripartito).
-- =============================================================================

CREATE TABLE buses_entregados (
    id                      BIGSERIAL           PRIMARY KEY,
    id_oportunidad          BIGINT              NOT NULL REFERENCES oportunidades(id) ON DELETE RESTRICT,
    id_modelo               BIGINT              NOT NULL REFERENCES modelos(id),
    numero_serie            VARCHAR(100),
    vin                     VARCHAR(50),
    url_contrato            TEXT,
    fecha_entrega_estimada  DATE,
    fecha_entrega_real      DATE,
    estado_entrega          estado_entrega_enum NOT NULL DEFAULT 'pendiente'
);

CREATE INDEX idx_buses_oportunidad ON buses_entregados(id_oportunidad);

COMMENT ON TABLE  buses_entregados                IS 'POST-MVP. Sin endpoints de escritura en MVP. Llenado manual hasta que se automatice.';
COMMENT ON COLUMN buses_entregados.numero_serie   IS 'NULL al crear. Se llena al nacionalizar las unidades.';
COMMENT ON COLUMN buses_entregados.vin            IS 'NULL al crear. Se llena al nacionalizar las unidades.';
COMMENT ON COLUMN buses_entregados.url_contrato   IS 'Contrato individual Quantum–cliente. Distinto al contrato tripartito de la oportunidad.';
