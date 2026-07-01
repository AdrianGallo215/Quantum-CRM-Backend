-- =============================================================================
-- V9 — Empresa ↔ Contacto (junction table)
-- cargo y toma_decision son atributos de la RELACIÓN, no del contacto.
-- RESTRICT en ambos lados: no se puede eliminar empresa o contacto con vínculos.
-- =============================================================================

CREATE TABLE empresa_contactos (
    id_empresa      BIGINT      NOT NULL REFERENCES empresas(id)  ON DELETE RESTRICT,
    id_contacto     BIGINT      NOT NULL REFERENCES contactos(id) ON DELETE RESTRICT,
    cargo           VARCHAR(100),
    toma_decision   BOOLEAN,
    es_principal    BOOLEAN     NOT NULL DEFAULT false,
    PRIMARY KEY (id_empresa, id_contacto)
);

CREATE INDEX idx_empresa_contactos_contacto ON empresa_contactos(id_contacto);

COMMENT ON TABLE empresa_contactos IS 'Relación N:M empresa ↔ contacto. RESTRICT: desvincular antes de eliminar.';
COMMENT ON COLUMN empresa_contactos.cargo IS 'Rol del contacto en esta empresa específica. Puede diferir si el contacto está en varias empresas.';
