-- =============================================================================
-- V30 — Cascada de eliminación hacia buses_entregados
--
-- Gap encontrado en la revisión final de la feature de DELETE admin (V29):
-- buses_entregados.id_oportunidad era la única FK hacia oportunidades(id) que
-- seguía en RESTRICT. Como el admin puede eliminar oportunidades/empresas
-- incluso en estado facturado (sin restricción, decisión de diseño explícita —
-- ver docs/superpowers/specs/2026-07-17-delete-admin-empresas-oportunidades-design.md),
-- y facturado es justo el estado donde existirían filas de buses_entregados
-- una vez implementado ese módulo (hoy fuera del MVP, tabla vacía), dejarla en
-- RESTRICT habría producido un 500 no controlado en ese caso. Se agrega a la
-- cascada, consistente con el resto de FKs hacia oportunidades(id).
-- =============================================================================

ALTER TABLE buses_entregados
    DROP CONSTRAINT buses_entregados_id_oportunidad_fkey,
    ADD CONSTRAINT buses_entregados_id_oportunidad_fkey
        FOREIGN KEY (id_oportunidad) REFERENCES oportunidades(id) ON DELETE CASCADE;

COMMENT ON COLUMN buses_entregados.id_oportunidad IS 'CASCADE: al eliminar la oportunidad (admin, hard delete, incluye facturado) se eliminan sus registros de buses entregados.';
