-- =============================================================================
-- V14 — Eventos por oportunidad
-- Los eventos son hechos externos que el vendedor espera y registra.
-- A diferencia de las tareas, los eventos los ejecutan terceros
-- (cliente, Calidda, otra financiadora).
--
-- ORIGEN MUTUAMENTE EXCLUYENTE (forzado por CHECK):
--   Del catálogo:    id_catalogo_evento NOT NULL, es_personalizado = false
--   Personalizado:   id_catalogo_evento NULL, es_personalizado = true,
--                    nombre_personalizado NOT NULL
--
-- Los eventos personalizados nunca disparan cambio de estado (CHECK).
--
-- TRES FECHAS con roles distintos:
--   fecha_estimada    → cuándo se espera que ocurra
--   fecha_seguimiento → cuándo el vendedor debe volver a presionar
--   fecha_ocurrencia  → cuándo ocurrió realmente (solo cuando estado = 'ocurrido')
-- =============================================================================

CREATE TABLE eventos (
    id                      BIGSERIAL           PRIMARY KEY,
    id_oportunidad          BIGINT              NOT NULL REFERENCES oportunidades(id) ON DELETE CASCADE,
    id_catalogo_evento      BIGINT              REFERENCES catalogo_eventos(id),
    es_personalizado        BOOLEAN             NOT NULL DEFAULT false,
    nombre_personalizado    TEXT,
    descripcion             TEXT,
    estado                  estado_evento_enum  NOT NULL DEFAULT 'pendiente',
    fecha_estimada          DATE,
    fecha_seguimiento       DATE,
    fecha_ocurrencia        TIMESTAMP,
    dispara_cambio_estado   BOOLEAN             NOT NULL DEFAULT false,
    estado_destino          estado_op_enum,
    registrado_por          BIGINT              REFERENCES empleados(id),
    created_at              TIMESTAMP           NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by              BIGINT              NOT NULL REFERENCES empleados(id),
    updated_at              TIMESTAMP           NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by              BIGINT              NOT NULL REFERENCES empleados(id),

    -- Origen mutuamente excluyente
    CONSTRAINT chk_evento_origen CHECK (
        (es_personalizado = false AND id_catalogo_evento IS NOT NULL AND nombre_personalizado IS NULL)
        OR
        (es_personalizado = true  AND id_catalogo_evento IS NULL     AND nombre_personalizado IS NOT NULL)
    ),
    -- Si dispara cambio de estado, debe tener estado_destino
    CONSTRAINT chk_evento_estado_destino CHECK (
        dispara_cambio_estado = false OR estado_destino IS NOT NULL
    ),
    -- Eventos personalizados nunca disparan cambio de estado
    CONSTRAINT chk_evento_personalizado_no_dispara CHECK (
        es_personalizado = false OR dispara_cambio_estado = false
    ),
    -- fecha_ocurrencia solo aplica cuando el evento ya ocurrió
    CONSTRAINT chk_evento_fecha_ocurrencia CHECK (
        estado = 'ocurrido' OR fecha_ocurrencia IS NULL
    )
);

CREATE INDEX idx_eventos_oportunidad ON eventos(id_oportunidad, estado);
CREATE INDEX idx_eventos_seguimiento ON eventos(fecha_seguimiento) WHERE estado = 'pendiente';

COMMENT ON TABLE  eventos                        IS 'Eventos del proceso comercial. El backend sugiere cambio de estado al ocurrir, no lo ejecuta automáticamente.';
COMMENT ON COLUMN eventos.dispara_cambio_estado  IS 'Copiado del catálogo al crear. Para eventos del catálogo, el backend puede leerlo del catálogo directamente.';
COMMENT ON COLUMN eventos.fecha_seguimiento      IS 'Cuándo el vendedor debe presionar al tercero responsable, no cuándo ocurrirá el evento.';
