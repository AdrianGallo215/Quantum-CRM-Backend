-- =============================================================================
-- V12 — Oportunidad ↔ Contacto (junction table)
-- Los contactos de una oportunidad pueden diferir de los contactos generales
-- de la empresa. El rol en la oportunidad también puede variar.
-- =============================================================================

CREATE TABLE oportunidad_contactos (
    id_oportunidad      BIGINT      NOT NULL REFERENCES oportunidades(id) ON DELETE CASCADE,
    id_contacto         BIGINT      NOT NULL REFERENCES contactos(id)     ON DELETE RESTRICT,
    rol_en_oportunidad  VARCHAR(50),
    PRIMARY KEY (id_oportunidad, id_contacto)
);

CREATE INDEX idx_oportunidad_contactos_contacto ON oportunidad_contactos(id_contacto);

COMMENT ON TABLE oportunidad_contactos IS 'Contactos involucrados en una oportunidad específica con su rol. CASCADE en oportunidad, RESTRICT en contacto.';
