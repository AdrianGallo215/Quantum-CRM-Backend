-- =============================================================================
-- V6 — Empresas
-- Solo personas jurídicas. RUC como VARCHAR(11).
-- estado_cartera NUNCA se actualiza directamente. Solo vía actualizarEstadoCartera().
-- Ver reglas_negocio.md sección 3.
-- =============================================================================

CREATE TABLE empresas (
    id                  BIGSERIAL               PRIMARY KEY,
    ruc                 VARCHAR(11)             UNIQUE NOT NULL,
    razon_social        TEXT                    UNIQUE NOT NULL,
    actividad_econ      TEXT                    NOT NULL,
    ciiu                VARCHAR(6),
    sector_industrial   TEXT,
    id_vendedor         BIGINT                  REFERENCES empleados(id) ON DELETE SET NULL,
    file_drive          TEXT,
    sitio_web           TEXT,
    notas               TEXT,
    estado_sunat        TEXT                    NOT NULL,
    condicion_sunat     TEXT                    NOT NULL,
    direccion_fiscal    TEXT                    NOT NULL,
    ubicacion_real      TEXT,
    distrito            TEXT,
    provincia           TEXT,
    departamento        TEXT,
    aval_fiador         TEXT,
    origen_lead         origen_lead_enum,
    estado_cartera      estado_cartera_enum     NOT NULL DEFAULT 'no_contactado',
    created_at          TIMESTAMP               NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by          BIGINT                  NOT NULL REFERENCES empleados(id),
    updated_at          TIMESTAMP               NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by          BIGINT                  NOT NULL REFERENCES empleados(id)
);

CREATE INDEX idx_empresas_vendedor      ON empresas(id_vendedor);
CREATE INDEX idx_empresas_estado_cartera ON empresas(estado_cartera);
CREATE INDEX idx_empresas_ruc           ON empresas(ruc);

COMMENT ON TABLE  empresas                  IS 'Solo personas jurídicas. Toda empresa tiene RUC de 11 dígitos.';
COMMENT ON COLUMN empresas.ruc              IS 'VARCHAR, nunca INT. Único en el sistema.';
COMMENT ON COLUMN empresas.estado_cartera   IS 'NUNCA actualizar directamente. Usar función actualizarEstadoCartera(). Ver reglas_negocio.md §3.';
COMMENT ON COLUMN empresas.id_vendedor      IS 'NULL = empresa sin asignar. ON DELETE SET NULL preserva la empresa si se elimina el empleado.';
