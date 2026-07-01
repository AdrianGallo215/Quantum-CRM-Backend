-- =============================================================================
-- V15 — Tareas
-- Las tareas las ejecutan miembros del equipo de Quantum (actores internos).
-- A diferencia de los eventos, tienen un responsable interno asignable.
--
-- id_oportunidad es NULLABLE:
--   NULL     → tarea de prospección (empresa en estado 'prospeccion', sin oportunidad)
--   NOT NULL → tarea de oportunidad activa
--
-- REGLA DE BACKEND: si id_oportunidad es NULL y la empresa tiene
-- oportunidades activas → rechazar con 400 (las tareas deben vincularse
-- a la oportunidad, no colgar de la empresa directamente).
-- =============================================================================

CREATE TABLE tareas (
    id              BIGSERIAL           PRIMARY KEY,
    id_empresa      BIGINT              NOT NULL REFERENCES empresas(id)      ON DELETE RESTRICT,
    id_oportunidad  BIGINT              REFERENCES oportunidades(id)          ON DELETE CASCADE,
    id_contacto     BIGINT              REFERENCES contactos(id),
    id_asignado     BIGINT              REFERENCES empleados(id),
    tipo_accion     tipo_accion_enum    NOT NULL,
    estado_accion   estado_accion_enum  NOT NULL DEFAULT 'pendiente',
    descripcion     TEXT,
    fecha_ejecucion TIMESTAMP,
    created_at      TIMESTAMP           NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by      BIGINT              NOT NULL REFERENCES empleados(id),
    updated_at      TIMESTAMP           NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by      BIGINT              NOT NULL REFERENCES empleados(id)
);

CREATE INDEX idx_tareas_oportunidad ON tareas(id_oportunidad) WHERE id_oportunidad IS NOT NULL;
CREATE INDEX idx_tareas_empresa     ON tareas(id_empresa);
CREATE INDEX idx_tareas_asignado    ON tareas(id_asignado, estado_accion);
CREATE INDEX idx_tareas_vencidas    ON tareas(fecha_ejecucion)
    WHERE estado_accion = 'pendiente' AND fecha_ejecucion IS NOT NULL;

COMMENT ON TABLE  tareas                IS 'Actividades internas. id_oportunidad NULL = tarea de prospección.';
COMMENT ON COLUMN tareas.id_oportunidad IS 'NULL para tareas de prospección. CASCADE: al eliminar oportunidad se eliminan sus tareas.';
COMMENT ON COLUMN tareas.id_empresa     IS 'RESTRICT: no se puede eliminar una empresa con tareas asociadas.';
