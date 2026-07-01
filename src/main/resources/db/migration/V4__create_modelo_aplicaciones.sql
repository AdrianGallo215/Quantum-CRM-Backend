-- =============================================================================
-- V4 — Aplicaciones de cada modelo (multi-select)
-- Un modelo puede aplicar a más de un tipo de servicio.
-- REGLA: todo modelo debe tener al menos una aplicación.
--        Esta restricción se impone en el backend (transacción atómica),
--        no con un constraint de DB.
-- =============================================================================

CREATE TABLE modelo_aplicaciones (
    id_modelo   BIGINT          NOT NULL REFERENCES modelos(id) ON DELETE CASCADE,
    aplicacion  aplicacion_enum NOT NULL,
    PRIMARY KEY (id_modelo, aplicacion)
);

COMMENT ON TABLE modelo_aplicaciones IS 'Relación modelo ↔ tipo de servicio. Un modelo puede tener varias aplicaciones.';
