-- =============================================================================
-- V31 — Colaboradores de tareas
-- Una tarea sigue teniendo un unico dueno (tareas.id_asignado, sin cambios),
-- pero ahora puede tener ademas varios colaboradores (trabajo en conjunto).
-- =============================================================================

CREATE TABLE tarea_responsables (
    id_tarea    BIGINT      NOT NULL REFERENCES tareas(id) ON DELETE CASCADE,
    id_empleado BIGINT      NOT NULL REFERENCES empleados(id),
    created_at  TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by  BIGINT      NOT NULL REFERENCES empleados(id),
    PRIMARY KEY (id_tarea, id_empleado)
);

CREATE INDEX idx_tarea_responsables_empleado ON tarea_responsables(id_empleado);

COMMENT ON TABLE tarea_responsables IS 'Colaboradores adicionales de una tarea, ademas de su dueno (tareas.id_asignado).';

ALTER TYPE tipo_notificacion_enum ADD VALUE 'tarea_colaborador_agregado';
