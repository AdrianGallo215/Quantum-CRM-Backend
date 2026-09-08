-- =============================================================================
-- V48 - Comentarios de seguimiento sobre actividades (tareas y eventos).
--
-- Tabla append-only: un comentario NUNCA sobrescribe otro ni pisa la
-- `descripcion` de la tarea/evento. Antes de esta tabla, la unica forma de
-- dejar una nota era editar `descripcion`, que borraba la anterior y hacia
-- imposible el historial que esta vista existe para mostrar.
--
-- Origen mutuamente excluyente (mismo patron que `eventos`, V14/V21): un
-- comentario cuelga de UNA tarea o de UN evento, nunca de ambos ni de ninguno.
-- Dos FK nullable + CHECK en vez de una columna polimorfica (tipo, id) porque
-- asi la base garantiza la integridad referencial en los dos casos.
-- =============================================================================

CREATE TABLE actividad_comentarios (
    id              BIGSERIAL   PRIMARY KEY,

    id_tarea        BIGINT      REFERENCES tareas(id)   ON DELETE CASCADE,
    id_evento       BIGINT      REFERENCES eventos(id)  ON DELETE CASCADE,

    texto           TEXT        NOT NULL,

    created_at      TIMESTAMP   NOT NULL DEFAULT NOW(),
    created_by      BIGINT      NOT NULL REFERENCES empleados(id),

    CONSTRAINT chk_comentario_origen CHECK (
        (id_tarea IS NOT NULL AND id_evento IS NULL) OR
        (id_tarea IS NULL     AND id_evento IS NOT NULL)
    ),
    CONSTRAINT chk_comentario_texto_no_vacio CHECK (length(btrim(texto)) > 0)
);

-- Indices parciales: cada fila solo puebla una de las dos columnas.
CREATE INDEX idx_actividad_comentarios_tarea
    ON actividad_comentarios (id_tarea, created_at)
    WHERE id_tarea IS NOT NULL;

CREATE INDEX idx_actividad_comentarios_evento
    ON actividad_comentarios (id_evento, created_at)
    WHERE id_evento IS NOT NULL;
