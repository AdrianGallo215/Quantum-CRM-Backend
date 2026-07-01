-- =============================================================================
-- V18 — Seed: catálogo de eventos estándar
--
-- Eventos del proceso comercial (oportunidades):
--   Fee depositado             → recomendado en eval. Calidda, no dispara estado
--   Aprobación Calidda         → dispara paso a documentos_legales
--   Rechazo Calidda            → dispara paso a cerrado
--   Desembolso Calidda         → dispara paso a facturado
--   Contrato tripartito firmado→ recomendado en doc. legales, no dispara estado
--   Propuesta aceptada         → informativo
--   Documentación cliente recibida → informativo
--
-- Hitos de prospección (es_hito_prospeccion = true):
--   Reporte Tributario recibido → checkpoint 1
--   Sentinel positivo           → checkpoint 2
--   Reunión inicial realizada   → checkpoint 3
-- =============================================================================

INSERT INTO catalogo_eventos
    (nombre, etapa_asociada, dispara_cambio_estado, estado_destino, es_recomendado, es_hito_prospeccion)
VALUES
    -- Eventos del proceso comercial
    ('Fee depositado',
     'evaluacion_calidda', false, NULL, true, false),

    ('Aprobación Calidda',
     'evaluacion_calidda', true, 'documentos_legales', true, false),

    ('Rechazo Calidda',
     'evaluacion_calidda', true, 'cerrado', true, false),

    ('Desembolso Calidda',
     'documentos_legales', true, 'facturado', true, false),

    ('Contrato tripartito firmado',
     'documentos_legales', false, NULL, true, false),

    ('Propuesta aceptada',
     'evaluacion_calidda', false, NULL, false, false),

    ('Documentación cliente recibida',
     'evaluacion_calidda', false, NULL, false, false),

    -- Hitos de prospección (sin etapa_asociada porque aplican antes de la oportunidad)
    ('Reporte Tributario recibido',
     NULL, false, NULL, false, true),

    ('Sentinel positivo',
     NULL, false, NULL, false, true),

    ('Reunión inicial realizada',
     NULL, false, NULL, false, true);
