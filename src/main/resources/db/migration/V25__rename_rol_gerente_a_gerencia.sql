-- =============================================================================
-- V25 — El rol 'gerente' pasa a llamarse 'gerencia'.
-- Los empleados existentes migran automáticamente (mismo valor, renombrado).
-- Los JWT vigentes emitidos con rol=gerente quedan sin privilegios: re-login.
-- =============================================================================
ALTER TYPE rol_empleado RENAME VALUE 'gerente' TO 'gerencia';
