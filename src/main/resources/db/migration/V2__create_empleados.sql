-- =============================================================================
-- V2 — Tabla empleados
-- Referenciada por casi todas las tablas (created_by, updated_by, id_vendedor).
-- Debe existir antes que cualquier otra tabla de dominio.
-- =============================================================================

CREATE TABLE empleados (
    id          BIGSERIAL       PRIMARY KEY,
    nombres     VARCHAR(100)    NOT NULL,
    apellidos   VARCHAR(100)    NOT NULL,
    email       VARCHAR(150)    UNIQUE NOT NULL,
    area        TEXT,
    puesto      TEXT,
    rol         rol_empleado    NOT NULL,
    activo      BOOLEAN         NOT NULL DEFAULT true
);

COMMENT ON TABLE  empleados             IS 'Usuarios del sistema con su rol de acceso.';
COMMENT ON COLUMN empleados.rol         IS 'Controla visibilidad y permisos. Ver matriz en contrato_api.md sección 5.';
COMMENT ON COLUMN empleados.activo      IS 'Los empleados inactivos no pueden iniciar sesión pero sus registros se conservan.';
