-- =============================================================================
-- V41 — Tipo de cambio PEN/USD publicado por SUNAT. Es una variable global del
-- CRM (no pertenece a ninguna simulación puntual, ver reglas_simulaciones.md
-- §12): un job la actualiza periódicamente y cualquier cálculo que necesite
-- convertir soles/dólares la lee de aquí.
--
-- La tabla es histórica (una fila por fecha) en vez de una única fila mutable
-- a propósito: el valor vigente sale de "ORDER BY fecha DESC LIMIT 1", y si el
-- job no llegó a correr hoy esa misma consulta ya devuelve el último valor
-- guardado — el fallback exigido por §12 cuando SUNAT no responde, sin ningún
-- código adicional de "no pisar con nulo" y sin perder trazabilidad histórica.
-- `fecha` como PRIMARY KEY hace el upsert del job idempotente.
-- =============================================================================

CREATE TABLE tipo_cambio (
    fecha       DATE            PRIMARY KEY,
    compra      NUMERIC(8,3)    NOT NULL,
    venta       NUMERIC(8,3)    NOT NULL,
    fuente      TEXT            NOT NULL DEFAULT 'sunat',
    created_at  TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT chk_tipo_cambio_compra_positiva CHECK (compra > 0),
    CONSTRAINT chk_tipo_cambio_venta_positiva  CHECK (venta > 0)
);

ALTER TABLE tipo_cambio ENABLE ROW LEVEL SECURITY;

COMMENT ON TABLE tipo_cambio IS
    'Tipo de cambio PEN/USD publicado por SUNAT. Variable global del CRM, no de la simulacion (reglas_simulaciones.md §12). Historico: el valor vigente es la fila de fecha mayor, lo que da el fallback "ultimo valor guardado" sin logica extra.';
