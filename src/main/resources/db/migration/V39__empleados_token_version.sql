-- =============================================================================
-- V39 — `empleados.token_version`: contador de revocacion de sesiones
--
-- El sistema es JWT stateless: hasta ahora el unico mecanismo de revocacion era
-- desactivar la cuenta completa (`activo = false`, ver EmpleadoServiceImpl), y
-- POST /auth/logout no existia en absoluto — AuthCookieFactory ya tenia listos
-- expiredAccessTokenCookie()/expiredRefreshTokenCookie() sin usar. Una cookie de
-- refresh copiada seguia sirviendo hasta sus 7 dias de vida aunque el usuario
-- cerrara sesion en el navegador.
--
-- token_version se incrusta como claim `tv` en cada refresh token (JwtService).
-- POST /auth/refresh compara el valor del token contra el de la base: si no
-- coincide, se trata como credencial invalida (401), igual que un empleado
-- inactivo. Se incrementa en logout (AuthController) y en cambio de contraseña
-- (EmpleadoServiceImpl.cambiarContrasena).
--
-- No se revisa en cada request autenticado, solo en refresh: mismo compromiso
-- que `activo` (documentado en EmpleadoServiceImpl) — la revocacion tarda como
-- maximo lo que dura el access token vigente (1h por defecto) en vez de forzar
-- una lectura a base de datos en cada endpoint.
--
-- IDEMPOTENTE (mismo patron que V37/V38): permite aplicarla primero a mano en
-- el panel de Supabase si hace falta desbloquear produccion antes que Flyway.
-- =============================================================================

ALTER TABLE empleados ADD COLUMN IF NOT EXISTS token_version INT NOT NULL DEFAULT 0;

COMMENT ON COLUMN empleados.token_version IS
    'Incrementado en logout y cambio de contraseña; invalida refresh tokens con una version anterior (V39).';
