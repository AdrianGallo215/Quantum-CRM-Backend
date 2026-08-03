-- =============================================================================
-- V22 — Notificaciones in-app
-- Notifica a un usuario cuando ocurre una accion relevante para el, generada
-- por otra persona (o por un job programado, sin actor humano — id_actor NULL).
-- entidad_tipo solo cubre oportunidad|empresa: el frontend navega a esas dos,
-- nunca a una tarea/evento suelto (para tareas/eventos se referencia su
-- oportunidad si tiene una, si no su empresa).
-- =============================================================================

CREATE TYPE tipo_notificacion_enum AS ENUM (
    'oportunidad_cambio_estado',
    'empresa_convertida',
    'evento_creado',
    'tarea_creada',
    'empresa_asignada',
    'oportunidad_traspasada',
    'tarea_recordatorio',
    'evento_recordatorio'
);

CREATE TYPE entidad_notificacion_enum AS ENUM ('oportunidad', 'empresa');

CREATE TABLE notificaciones (
    id                          BIGSERIAL                   PRIMARY KEY,
    id_empleado_destinatario   BIGINT                      NOT NULL REFERENCES empleados(id),
    id_actor                    BIGINT                      REFERENCES empleados(id),
    tipo                        tipo_notificacion_enum      NOT NULL,
    mensaje                     TEXT                        NOT NULL,
    entidad_tipo                entidad_notificacion_enum   NOT NULL,
    entidad_id                  BIGINT                      NOT NULL,
    leida                       BOOLEAN                     NOT NULL DEFAULT false,
    created_at                  TIMESTAMP                   NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_notificaciones_destinatario ON notificaciones(id_empleado_destinatario, created_at DESC);

COMMENT ON COLUMN notificaciones.id_actor IS 'NULL para recordatorios generados por un job programado (sin actor humano).';

-- Dedup del job de recordatorios. Tabla separada de `notificaciones` para que
-- el job de limpieza (purga leida=true y >30 dias) nunca pueda reabrir una
-- ventana de duplicado.
CREATE TYPE origen_recordatorio_enum AS ENUM ('tarea', 'evento');
CREATE TYPE umbral_recordatorio_enum AS ENUM ('proximo', 'vencido');

CREATE TABLE recordatorios_enviados (
    id          BIGSERIAL                   PRIMARY KEY,
    origen      origen_recordatorio_enum    NOT NULL,
    id_origen   BIGINT                      NOT NULL,
    umbral      umbral_recordatorio_enum    NOT NULL,
    created_at  TIMESTAMP                   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_recordatorio UNIQUE (origen, id_origen, umbral)
);
