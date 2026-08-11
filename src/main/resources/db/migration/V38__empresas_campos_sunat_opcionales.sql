-- =============================================================================
-- V38 — `empresas`: los cuatro campos de SUNAT dejan de ser NOT NULL
--
-- `V6__create_empresas.sql` declaro `actividad_econ`, `estado_sunat`,
-- `condicion_sunat` y `direccion_fiscal` como NOT NULL, pero tanto el contrato
-- (`contrato_api.md §8`) como el DTO (`CrearEmpresaRequest`, donde solo `ruc` y
-- `razon_social` llevan @field:NotBlank) como la entidad (`Empresa`, con `String?`)
-- los tratan como opcionales. La base de datos era la unica de las cuatro capas
-- que los exigia.
--
-- Por que `ddl-auto=validate` no lo detecto: Hibernate valida que la columna
-- exista y que su tipo case, pero NO compara la nulabilidad de la columna con la
-- del campo Kotlin. La app arrancaba en verde y reventaba con
-- DataIntegrityViolationException -> 500 en el primer POST /empresas con body
-- minimo (el caso real: un vendedor registrando un lead del que aun no tiene la
-- ficha RUC). Mismo patron de fallo silencioso que V37 con las etiquetas de enum.
--
-- Se relaja el esquema en vez de exigir los campos en el DTO porque el dato de
-- SUNAT no siempre esta disponible en el momento del alta, y forzarlo obligaria
-- al vendedor a inventarselo o a no registrar la empresa. El contrato con el
-- frontend ya asume que son opcionales.
--
-- IDEMPOTENTE a proposito, igual que V37: esto se aplica primero a mano en el
-- panel de Supabase para desbloquear produccion, asi que Flyway tiene que poder
-- correrlo despues sin fallar. `DROP NOT NULL` sobre una columna que ya es
-- nullable es un no-op en Postgres, no un error, asi que no hace falta guarda
-- adicional. Se versiona igualmente para que el esquema quede reproducible desde
-- cero (CI levanta la BD con Testcontainers desde V1).
-- =============================================================================

ALTER TABLE empresas ALTER COLUMN actividad_econ   DROP NOT NULL;
ALTER TABLE empresas ALTER COLUMN estado_sunat     DROP NOT NULL;
ALTER TABLE empresas ALTER COLUMN condicion_sunat  DROP NOT NULL;
ALTER TABLE empresas ALTER COLUMN direccion_fiscal DROP NOT NULL;

COMMENT ON COLUMN empresas.actividad_econ   IS 'Opcional: puede no conocerse al dar de alta el lead (V38).';
COMMENT ON COLUMN empresas.estado_sunat     IS 'Opcional: se completa al consultar la ficha RUC (V38).';
COMMENT ON COLUMN empresas.condicion_sunat  IS 'Opcional: se completa al consultar la ficha RUC (V38).';
COMMENT ON COLUMN empresas.direccion_fiscal IS 'Opcional: se completa al consultar la ficha RUC (V38).';
