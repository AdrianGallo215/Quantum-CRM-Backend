-- =============================================================================
-- V24 — Agrega 'otro' a segmento_enum
-- El frontend ofrece "otro" como segmento de empresa; el enum solo tenia
-- urbano/personal/turismo/interprovincial (reglas_negocio.md §2.3), causando un
-- 400 (HttpMessageNotReadableException) al crear empresas con ese segmento.
-- =============================================================================

ALTER TYPE segmento_enum ADD VALUE 'otro';
