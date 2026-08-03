-- =============================================================================
-- V27 — Cartera Maestra: empresas reservadas de gerencia, invisibles para
-- jdv/vendedor/analista hasta que gerencia las libere. Ver
-- docs/gerencia_solicitudes_modelo_datos.md §4.
-- =============================================================================

ALTER TABLE empresas
    ADD COLUMN en_cartera_maestra BOOLEAN NOT NULL DEFAULT false;

ALTER TABLE empresas
    ADD CONSTRAINT chk_cartera_maestra_sin_vendedor
    CHECK (NOT en_cartera_maestra OR id_vendedor IS NULL);

CREATE INDEX idx_empresas_cartera_maestra ON empresas(en_cartera_maestra)
    WHERE en_cartera_maestra;
