-- =============================================================================
-- V32 — Metas de venta (unidades), una fila por (empleado, año) con los 12
-- meses + el total anual. El ciclo de aprobación (propuesta/aprobada/rechazada)
-- aplica al año completo: el JDV propone el año entero de una sola vez, no mes
-- a mes. Ver docs/superpowers/specs/2026-07-22-metas-venta-design.md.
-- =============================================================================

CREATE TYPE estado_meta_enum AS ENUM ('propuesta', 'aprobada', 'rechazada');

CREATE TABLE metas_venta (
    id                  BIGSERIAL           PRIMARY KEY,
    id_empleado         BIGINT              NOT NULL REFERENCES empleados(id),
    anio                INT                 NOT NULL,
    meta_enero          INT                 NOT NULL CHECK (meta_enero > 0),
    meta_febrero        INT                 NOT NULL CHECK (meta_febrero > 0),
    meta_marzo          INT                 NOT NULL CHECK (meta_marzo > 0),
    meta_abril          INT                 NOT NULL CHECK (meta_abril > 0),
    meta_mayo           INT                 NOT NULL CHECK (meta_mayo > 0),
    meta_junio          INT                 NOT NULL CHECK (meta_junio > 0),
    meta_julio          INT                 NOT NULL CHECK (meta_julio > 0),
    meta_agosto         INT                 NOT NULL CHECK (meta_agosto > 0),
    meta_septiembre     INT                 NOT NULL CHECK (meta_septiembre > 0),
    meta_octubre        INT                 NOT NULL CHECK (meta_octubre > 0),
    meta_noviembre      INT                 NOT NULL CHECK (meta_noviembre > 0),
    meta_diciembre      INT                 NOT NULL CHECK (meta_diciembre > 0),
    meta_anual          INT                 NOT NULL CHECK (meta_anual > 0),
    estado              estado_meta_enum    NOT NULL DEFAULT 'propuesta',
    id_propuesto_por    BIGINT              NOT NULL REFERENCES empleados(id),
    id_resolutor        BIGINT              REFERENCES empleados(id),
    motivo_rechazo      TEXT,
    resolved_at         TIMESTAMP,
    created_at          TIMESTAMP           NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at          TIMESTAMP           NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT uq_meta_venta_empleado_anio UNIQUE (id_empleado, anio),

    CONSTRAINT chk_meta_venta_resolucion CHECK (
        (estado = 'propuesta' AND id_resolutor IS NULL AND resolved_at IS NULL AND motivo_rechazo IS NULL)
        OR
        (estado = 'aprobada' AND id_resolutor IS NOT NULL AND resolved_at IS NOT NULL AND motivo_rechazo IS NULL)
        OR
        (estado = 'rechazada' AND id_resolutor IS NOT NULL AND resolved_at IS NOT NULL AND motivo_rechazo IS NOT NULL)
    )
);

CREATE INDEX idx_metas_venta_empleado ON metas_venta(id_empleado, anio);
CREATE INDEX idx_metas_venta_estado ON metas_venta(estado);

COMMENT ON TABLE  metas_venta            IS 'Meta de unidades vendidas por empleado (vendedor/jdv) y año; 12 meses + total anual calculado.';
COMMENT ON COLUMN metas_venta.meta_anual IS 'SOLO LECTURA. Calculado por backend: suma de meta_enero..meta_diciembre.';
