-- =============================================================================
-- V13 — Catálogo de eventos estándar
-- Define los eventos predefinidos del proceso comercial y de prospección.
-- Los 7 eventos estándar se insertan en V18 (seed).
--
-- CAMPOS CLAVE:
--   dispara_cambio_estado → si true, al marcar ocurrido el backend
--                           sugiere (no ejecuta) el cambio de estado.
--   etapa_asociada        → orientativo. La UI filtra por esto pero no bloquea.
--   es_recomendado        → si true, la UI avisa al vendedor si avanza
--                           de etapa sin haberlo registrado.
--   es_hito_prospeccion   → si true, cuenta como checkpoint de avance
--                           en la vista de madurez de prospección.
-- =============================================================================

CREATE TABLE catalogo_eventos (
    id                      BIGSERIAL       PRIMARY KEY,
    nombre                  TEXT            UNIQUE NOT NULL,
    etapa_asociada          estado_op_enum,
    dispara_cambio_estado   BOOLEAN         NOT NULL DEFAULT false,
    estado_destino          estado_op_enum,
    es_recomendado          BOOLEAN         NOT NULL DEFAULT false,
    es_hito_prospeccion     BOOLEAN         NOT NULL DEFAULT false,

    CONSTRAINT chk_catalogo_estado_destino
        CHECK (dispara_cambio_estado = false OR estado_destino IS NOT NULL)
);

COMMENT ON TABLE  catalogo_eventos                       IS 'Plantillas de eventos reutilizables. Los eventos personalizados no referencian esta tabla.';
COMMENT ON COLUMN catalogo_eventos.dispara_cambio_estado IS 'Si true, marcar el evento como ocurrido genera una sugerencia de cambio de estado en la respuesta del API.';
COMMENT ON COLUMN catalogo_eventos.es_hito_prospeccion   IS 'Si true, cuenta como checkpoint en el cálculo de avance de prospección (GET /prospeccion).';
