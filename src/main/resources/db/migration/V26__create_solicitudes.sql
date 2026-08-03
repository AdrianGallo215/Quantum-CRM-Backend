-- =============================================================================
-- V26 — Solicitudes de aprobacion (descuento, reasignacion de cliente).
-- Ver docs/gerencia_solicitudes_modelo_datos.md §3.
-- =============================================================================

CREATE TYPE tipo_solicitud_enum AS ENUM ('descuento', 'reasignacion_cliente');

CREATE TYPE estado_solicitud_enum AS ENUM ('pendiente', 'aprobada', 'denegada');

CREATE TYPE aprobador_solicitud_enum AS ENUM ('jdv', 'gerencia');

CREATE TYPE entidad_solicitud_enum AS ENUM ('oportunidad', 'empresa');

CREATE TABLE solicitudes (
    id                  BIGSERIAL                   PRIMARY KEY,
    tipo                tipo_solicitud_enum         NOT NULL,
    estado              estado_solicitud_enum       NOT NULL DEFAULT 'pendiente',
    rol_aprobador       aprobador_solicitud_enum    NOT NULL,
    id_solicitante      BIGINT                      NOT NULL REFERENCES empleados(id),
    entidad_tipo        entidad_solicitud_enum      NOT NULL,
    entidad_id          BIGINT                      NOT NULL,
    entidad_descripcion TEXT                        NOT NULL,
    motivo              TEXT                        NOT NULL,

    dcto_solicitado     NUMERIC(5,2),
    id_vendedor_nuevo   BIGINT                      REFERENCES empleados(id),

    id_resolutor        BIGINT                      REFERENCES empleados(id),
    motivo_resolucion   TEXT,
    resolved_at         TIMESTAMP,

    created_at          TIMESTAMP                   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at          TIMESTAMP                   NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT chk_solicitud_payload CHECK (
        (tipo = 'descuento'
            AND dcto_solicitado IS NOT NULL
            AND dcto_solicitado > 0 AND dcto_solicitado <= 100
            AND entidad_tipo = 'oportunidad'
            AND id_vendedor_nuevo IS NULL)
        OR
        (tipo = 'reasignacion_cliente'
            AND id_vendedor_nuevo IS NOT NULL
            AND entidad_tipo = 'empresa'
            AND dcto_solicitado IS NULL)
    ),

    CONSTRAINT chk_solicitud_resolucion CHECK (
        (estado = 'pendiente' AND id_resolutor IS NULL AND resolved_at IS NULL AND motivo_resolucion IS NULL)
        OR
        (estado IN ('aprobada', 'denegada') AND id_resolutor IS NOT NULL AND resolved_at IS NOT NULL)
    ),

    CONSTRAINT chk_solicitud_denegada_motivo CHECK (
        estado <> 'denegada' OR motivo_resolucion IS NOT NULL
    )
);

CREATE INDEX idx_solicitudes_aprobador ON solicitudes(rol_aprobador, estado, created_at DESC);

CREATE INDEX idx_solicitudes_solicitante ON solicitudes(id_solicitante, created_at DESC);

CREATE INDEX idx_solicitudes_entidad ON solicitudes(entidad_tipo, entidad_id);

CREATE UNIQUE INDEX uq_solicitud_pendiente_por_entidad
    ON solicitudes(tipo, entidad_tipo, entidad_id)
    WHERE estado = 'pendiente';
