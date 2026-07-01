-- =============================================================================
-- V3 — Catálogo de modelos de bus
-- =============================================================================

CREATE TABLE modelos (
    id                  BIGSERIAL       PRIMARY KEY,
    codigo              VARCHAR(50)     UNIQUE NOT NULL,
    longitud            NUMERIC(5,2),
    capacidad_tanques   TEXT,
    max_asientos        INT,
    precio_base         NUMERIC(12,2),
    ficha_tecnica       TEXT
);

COMMENT ON TABLE  modelos                   IS 'Catálogo de modelos de bus disponibles para venta.';
COMMENT ON COLUMN modelos.precio_base       IS 'Precio de referencia del catálogo. Precio final vive en oportunidades.precio_unitario.';
COMMENT ON COLUMN modelos.capacidad_tanques IS 'Texto libre. Ej: "2x100L", "2x200L + 1x65L".';
