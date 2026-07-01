-- =============================================================================
-- V17 — Seed: financiadora Calidda
-- Única financiadora del MVP. es_default = true.
-- Términos fijos del programa Fraccionamiento GNV:
--   Monto por unidad: USD 45,000
--   Plazo:            48 meses (4 años)
--   TEA:              0% (tasa cero)
--   Cuota por unidad: USD 937.50 (= 45000 / 48)
-- =============================================================================

INSERT INTO financiadoras (nombre, monto_por_unidad, plazo_meses, tea, cuota_por_unidad, es_default, notas)
VALUES (
    'Calidda – Fraccionamiento GNV',
    45000.00,
    48,
    0.0000,
    937.50,
    true,
    'Programa de financiamiento para renovación de flotas de transporte urbano a GNV. Aplica para Lima departamental.'
);
