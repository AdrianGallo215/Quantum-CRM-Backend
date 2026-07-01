-- =============================================================================
-- V8 — Contactos
-- Un contacto puede estar vinculado a más de una empresa.
-- NO tiene FK directa a empresas — la relación vive en empresa_contactos.
-- cargo y toma_decision varían por empresa, por eso están en la tabla intermedia.
-- =============================================================================

CREATE TABLE contactos (
    id          BIGSERIAL       PRIMARY KEY,
    nombres     VARCHAR(100)    NOT NULL,
    apellidos   VARCHAR(100)    NOT NULL,
    email_1     VARCHAR(150),
    email_2     VARCHAR(150),
    tlf_1       VARCHAR(20),
    tlf_2       VARCHAR(20),
    notas       TEXT,
    created_at  TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by  BIGINT          NOT NULL REFERENCES empleados(id),
    updated_at  TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by  BIGINT          NOT NULL REFERENCES empleados(id)
);

COMMENT ON TABLE contactos IS 'Un contacto puede pertenecer a múltiples empresas. Ver empresa_contactos para la relación.';
