-- =============================================================================
-- V49 - Auditoria de ediciones sobre actividades (tareas y eventos).
--
-- Una fila por CAMPO modificado, no una por edicion: asi el historial responde
-- "quien cambio que", que es justo lo que la vista de supervision necesita.
-- Hasta ahora `tareas` y `eventos` solo guardaban `updated_by`/`updated_at` en
-- la propia fila: eso es "ultima modificacion", no historial - no dice que
-- cambio ni conserva el valor anterior.
--
-- Se registran TODAS las ediciones, tambien las del propio dueno. Registrar
-- solo las de terceros produciria un historial con huecos, imposible de leer.
--
-- Append-only: sin UPDATE ni DELETE propios. Las filas mueren con su actividad
-- (ON DELETE CASCADE).
--
-- Mismo patron de origen excluyente que V48.
-- =============================================================================

CREATE TABLE actividad_auditoria (
    id              BIGSERIAL   PRIMARY KEY,

    id_tarea        BIGINT      REFERENCES tareas(id)   ON DELETE CASCADE,
    id_evento       BIGINT      REFERENCES eventos(id)  ON DELETE CASCADE,

    -- Nombre del campo tal y como lo expone el contrato (snake_case):
    -- 'descripcion', 'fecha_ejecucion', 'tipo_accion', 'id_asignado', ...
    campo           TEXT        NOT NULL,
    valor_anterior  TEXT,
    valor_nuevo     TEXT,

    changed_at      TIMESTAMP   NOT NULL DEFAULT NOW(),
    changed_by      BIGINT      NOT NULL REFERENCES empleados(id),

    CONSTRAINT chk_auditoria_origen CHECK (
        (id_tarea IS NOT NULL AND id_evento IS NULL) OR
        (id_tarea IS NULL     AND id_evento IS NOT NULL)
    )
);

CREATE INDEX idx_actividad_auditoria_tarea
    ON actividad_auditoria (id_tarea, changed_at DESC)
    WHERE id_tarea IS NOT NULL;

CREATE INDEX idx_actividad_auditoria_evento
    ON actividad_auditoria (id_evento, changed_at DESC)
    WHERE id_evento IS NOT NULL;
