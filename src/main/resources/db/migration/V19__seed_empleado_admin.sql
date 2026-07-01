-- =============================================================================
-- V19 — Seed: empleado administrador inicial
--
-- IMPORTANTE: la contraseña en este seed es un placeholder.
-- Antes de ejecutar en cualquier entorno, reemplazar el hash con el
-- generado por BCrypt para la contraseña real del admin inicial.
--
-- Para generar el hash en Java:
--   BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();
--   String hash = encoder.encode("tu_contraseña_segura");
--
-- Este empleado es el único que puede crear otros empleados (rol = admin).
-- La contraseña debe cambiarse inmediatamente después del primer login.
-- =============================================================================

-- REEMPLAZAR el valor de password_hash antes de ejecutar.
INSERT INTO empleados (nombres, apellidos, email, area, puesto, rol, activo)
VALUES (
    'Admin',
    'Sistema',
    'admin@quantum.pe',
    'TI',
    'Administrador del sistema',
    'admin',
    true
);

-- NOTA: Spring Security maneja el campo de contraseña en su propia tabla
-- (tabla 'usuarios' con FK a empleados, o campo password en empleados según
-- la implementación elegida). Esta migración solo crea el registro base.
-- El campo de contraseña debe agregarse en la tabla correspondiente
-- según cómo se implemente Spring Security.
