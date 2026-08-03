-- =============================================================================
-- V21 — Eventos de prospección
-- Los hitos de prospección (reglas_negocio.md §10.3) son eventos del catálogo
-- registrados para una EMPRESA sin oportunidad (id_oportunidad = NULL).
-- V14 creó eventos con id_oportunidad NOT NULL; aquí se relaja y se agrega el
-- vínculo directo a empresa. Todo evento debe estar vinculado a una oportunidad
-- o a una empresa (CHECK).
-- =============================================================================

ALTER TABLE eventos ALTER COLUMN id_oportunidad DROP NOT NULL;

ALTER TABLE eventos
    ADD COLUMN id_empresa BIGINT REFERENCES empresas(id) ON DELETE CASCADE;

ALTER TABLE eventos
    ADD CONSTRAINT chk_evento_vinculo
        CHECK (id_oportunidad IS NOT NULL OR id_empresa IS NOT NULL);

CREATE INDEX idx_eventos_empresa
    ON eventos(id_empresa, estado)
    WHERE id_empresa IS NOT NULL;

COMMENT ON COLUMN eventos.id_empresa IS 'Vínculo directo a empresa para eventos de prospección (sin oportunidad).';
