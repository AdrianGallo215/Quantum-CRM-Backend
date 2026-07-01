-- =============================================================================
-- V10 — Oportunidades
-- Núcleo del pipeline comercial.
--
-- CAMPOS CRÍTICOS:
--   id_vendedor     → snapshot al crear. Solo muta en traspaso explícito.
--   precio_unitario → editable. Default = modelos.precio_base al crear.
--   monto_total     → CALCULADO: cantidad * precio_unitario * (1 - dcto/100).
--                     Nunca se acepta como input. El backend lo recalcula.
--   motivo_cierre   → obligatorio si estado = 'cerrado' (CHECK constraint).
--
-- Ver reglas_negocio.md §4, §7, §8.
-- =============================================================================

CREATE TABLE oportunidades (
    id                      BIGSERIAL       PRIMARY KEY,
    id_empresa              BIGINT          NOT NULL REFERENCES empresas(id)      ON DELETE RESTRICT,
    id_vendedor             BIGINT          NOT NULL REFERENCES empleados(id),
    id_financiadora         BIGINT          NOT NULL REFERENCES financiadoras(id),
    id_modelo               BIGINT          NOT NULL REFERENCES modelos(id),
    estado                  estado_op_enum  NOT NULL DEFAULT 'evaluacion_calidda',
    cantidad                INT,
    precio_unitario         NUMERIC(12,2),
    dcto                    NUMERIC(5,2),
    monto_total             NUMERIC(12,2),
    garantia                BOOLEAN,
    finc_paralelo           BOOLEAN,
    ficha_venta             TEXT,
    notas                   TEXT,
    motivo_cierre           TEXT,
    fecha_cierre_estimado   DATE,
    created_at              TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by              BIGINT          NOT NULL REFERENCES empleados(id),
    updated_at              TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by              BIGINT          NOT NULL REFERENCES empleados(id),

    CONSTRAINT chk_motivo_cierre
        CHECK (estado != 'cerrado' OR motivo_cierre IS NOT NULL)
);

CREATE INDEX idx_oportunidades_empresa    ON oportunidades(id_empresa);
CREATE INDEX idx_oportunidades_vendedor   ON oportunidades(id_vendedor);
CREATE INDEX idx_oportunidades_estado     ON oportunidades(estado);
CREATE INDEX idx_oportunidades_financiadora ON oportunidades(id_financiadora);

COMMENT ON TABLE  oportunidades                 IS 'Núcleo del pipeline. Una oportunidad por deal comercial.';
COMMENT ON COLUMN oportunidades.id_vendedor     IS 'Snapshot del vendedor asignado a la empresa al crear. No cambia con reasignaciones de empresa.';
COMMENT ON COLUMN oportunidades.precio_unitario IS 'Editable. Se inicializa con modelos.precio_base al crear la oportunidad.';
COMMENT ON COLUMN oportunidades.monto_total     IS 'SOLO LECTURA. Calculado por backend: cantidad * precio_unitario * (1 - dcto/100).';
COMMENT ON COLUMN oportunidades.motivo_cierre   IS 'Obligatorio cuando estado = cerrado. Verificado por CHECK constraint y por backend.';
COMMENT ON COLUMN oportunidades.finc_paralelo   IS 'true si hay una entidad financiadora adicional cofinanciando la operación.';
