-- =============================================================================
-- V29 — Cascada de eliminación de empresa (DELETE /empresas/:id, admin)
--
-- Antes: RESTRICT bloqueaba eliminar una empresa con oportunidades/tareas/
-- contactos vinculados (reglas_negocio.md §11.2 anterior).
--
-- Ahora: eliminar una empresa elimina en cascada sus oportunidades (y con
-- ellas, vía las constraints ya existentes, su log de estados, sus eventos y
-- sus tareas) y sus tareas propias. Los contactos vinculados NO se eliminan:
-- solo se borra la fila de `empresa_contactos` (la relación), nunca la fila
-- de `contactos` — por eso `empresa_contactos_id_contacto_fkey` se queda en
-- RESTRICT.
-- =============================================================================

ALTER TABLE oportunidades
    DROP CONSTRAINT oportunidades_id_empresa_fkey,
    ADD CONSTRAINT oportunidades_id_empresa_fkey
        FOREIGN KEY (id_empresa) REFERENCES empresas(id) ON DELETE CASCADE;

ALTER TABLE tareas
    DROP CONSTRAINT tareas_id_empresa_fkey,
    ADD CONSTRAINT tareas_id_empresa_fkey
        FOREIGN KEY (id_empresa) REFERENCES empresas(id) ON DELETE CASCADE;

ALTER TABLE empresa_contactos
    DROP CONSTRAINT empresa_contactos_id_empresa_fkey,
    ADD CONSTRAINT empresa_contactos_id_empresa_fkey
        FOREIGN KEY (id_empresa) REFERENCES empresas(id) ON DELETE CASCADE;

COMMENT ON COLUMN oportunidades.id_empresa IS 'CASCADE: al eliminar la empresa (admin, hard delete) se eliminan sus oportunidades.';
COMMENT ON COLUMN tareas.id_empresa IS 'CASCADE: al eliminar la empresa (admin, hard delete) se eliminan sus tareas.';
