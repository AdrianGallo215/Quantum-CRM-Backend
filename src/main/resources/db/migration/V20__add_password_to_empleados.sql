-- =============================================================================
-- V20 — Autenticacion: almacenamiento de contraseña
--
-- Decision de B0.7 (contrato_api.md / SECURITY-backend.md): el hash de la
-- contraseña vive en la tabla `empleados`, no en una tabla separada. Es simple,
-- 1:1 con el empleado, y no hay requisito de historicos de credenciales en el MVP.
--
-- - password_hash: hash BCrypt (cost >= 12). NULLABLE: el admin sembrado en V19
--   nace sin contraseña; se establece por un bootstrap seguro (nunca hardcodeada
--   en una migracion). Un empleado con password_hash NULL no puede autenticarse.
-- - requiere_cambio_contrasena: fuerza el cambio en el primer login tras un alta
--   o un reset por admin (SECURITY-backend.md §2.3).
-- =============================================================================

ALTER TABLE empleados
    ADD COLUMN password_hash              VARCHAR(100),
    ADD COLUMN requiere_cambio_contrasena BOOLEAN NOT NULL DEFAULT true;

COMMENT ON COLUMN empleados.password_hash              IS 'Hash BCrypt de la contraseña. NULL = el empleado aun no puede iniciar sesion.';
COMMENT ON COLUMN empleados.requiere_cambio_contrasena IS 'Si true, se fuerza el cambio de contraseña en el primer login.';
