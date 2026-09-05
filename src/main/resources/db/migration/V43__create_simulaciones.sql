-- =============================================================================
-- V40 — Simulaciones financieras (financiamiento propio de Quantum).
--
-- Una simulación describe el financiamiento de UNA UNIDAD (un tipo de bus), no
-- de la operación completa. Por eso cuelga de `oportunidad_items`, no de
-- `oportunidades`: una oportunidad con 3 modelos distintos necesita 3
-- simulaciones, cada una con su propia inicial, tasa y cuota.
--
-- Independiente de `financiadoras`: la simulación cubre SOLO la porción
-- financiada por Quantum. Lo que el cliente paga a terceros (Calidda, cajas)
-- se resume en `oportunidad_items.cuota_financiadora` y se suma únicamente en
-- el DTO de oportunidad. NO existe FK entre simulaciones y financiadoras.
--
-- El cronograma de pagos NO se persiste: es una función pura y determinística
-- de los campos esenciales, y se recalcula on demand.
-- Ver docs/reglas_simulaciones.md.
--
-- REQUISITO PREVIO: la tabla `oportunidad_items` debe existir (PK `id BIGINT`).
-- =============================================================================

CREATE TYPE modo_simulacion_enum AS ENUM ('leasing', 'credito_directo');

CREATE TYPE tipo_evento_simulacion_enum AS ENUM (
    'creada',
    'editada',
    'restaurada',
    'marcada_principal',
    'enlazada_a_item',
    'eliminada'
);

-- -----------------------------------------------------------------------------
-- Estado actual de cada simulación
-- -----------------------------------------------------------------------------
CREATE TABLE simulaciones (
    id                      BIGSERIAL               PRIMARY KEY,
    modo                    modo_simulacion_enum    NOT NULL,

    nombre                  TEXT,

    id_oportunidad_item     BIGINT                  REFERENCES oportunidad_items(id) ON DELETE SET NULL,
    id_modelo               BIGINT                  REFERENCES modelos(id) ON DELETE SET NULL,
    id_simulacion_origen    BIGINT                  REFERENCES simulaciones(id) ON DELETE SET NULL,

    -- Campos esenciales (entrada del motor de cálculo)
    precio_venta            NUMERIC(12,2)           NOT NULL,
    descuento               NUMERIC(5,2)            NOT NULL DEFAULT 0,
    cuota_inicial           NUMERIC(12,2)           NOT NULL,
    plazo_meses             INT                     NOT NULL,
    tea                     NUMERIC(6,2)            NOT NULL,
    valor_residual          NUMERIC(12,2)           NOT NULL DEFAULT 0,

    -- Campos avanzados (no participan del cronograma)
    dias_trabajados         INT                     NOT NULL DEFAULT 22,
    comision_estructuracion NUMERIC(12,2)           NOT NULL DEFAULT 1180,

    -- Resultado congelado al guardar. NUNCA se acepta del cliente.
    cuota_final             NUMERIC(12,2)           NOT NULL,

    es_principal            BOOLEAN                 NOT NULL DEFAULT false,

    created_at              TIMESTAMP               NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by              BIGINT                  NOT NULL REFERENCES empleados(id),
    updated_at              TIMESTAMP               NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by              BIGINT                  NOT NULL REFERENCES empleados(id),

    CONSTRAINT chk_simulacion_precio_venta_positivo      CHECK (precio_venta > 0),
    CONSTRAINT chk_simulacion_descuento_rango            CHECK (descuento >= 0 AND descuento <= 100),
    CONSTRAINT chk_simulacion_cuota_inicial_no_negativa  CHECK (cuota_inicial >= 0),
    CONSTRAINT chk_simulacion_plazo_positivo             CHECK (plazo_meses > 0),
    CONSTRAINT chk_simulacion_tea_rango                  CHECK (tea > 0 AND tea < 200),
    CONSTRAINT chk_simulacion_valor_residual_no_negativo CHECK (valor_residual >= 0),
    CONSTRAINT chk_simulacion_dias_trabajados_positivo   CHECK (dias_trabajados > 0),
    CONSTRAINT chk_simulacion_comision_no_negativa       CHECK (comision_estructuracion >= 0),
    CONSTRAINT chk_simulacion_cuota_final_positiva       CHECK (cuota_final > 0),

    -- Nombre manual: si viene, no puede ser cadena vacía ni solo espacios.
    CONSTRAINT chk_simulacion_nombre_no_vacio            CHECK (nombre IS NULL OR length(btrim(nombre)) > 0),

    -- Una simulación no puede ser su propio origen
    CONSTRAINT chk_simulacion_origen_distinto            CHECK (id_simulacion_origen IS DISTINCT FROM id),

    -- es_principal solo tiene sentido dentro de un ítem
    CONSTRAINT chk_simulacion_principal_requiere_item    CHECK (es_principal = false OR id_oportunidad_item IS NOT NULL)
);

-- Solo una simulación principal por ítem de oportunidad.
CREATE UNIQUE INDEX uq_simulacion_principal
    ON simulaciones(id_oportunidad_item)
    WHERE es_principal = true AND id_oportunidad_item IS NOT NULL;

CREATE INDEX idx_simulacion_item   ON simulaciones(id_oportunidad_item);
CREATE INDEX idx_simulacion_origen ON simulaciones(id_simulacion_origen);

-- Soporta el correlativo del nombre autogenerado y el job de purga.
CREATE INDEX idx_simulacion_correlativo
    ON simulaciones(id_oportunidad_item, id_modelo, modo, created_at);

CREATE INDEX idx_simulacion_huerfana
    ON simulaciones(created_at)
    WHERE id_oportunidad_item IS NULL;

COMMENT ON TABLE  simulaciones                         IS 'Simulaciones de financiamiento propio de Quantum, una por unidad (ítem de oportunidad). El cronograma NO se persiste: se recalcula on demand desde los campos esenciales.';
COMMENT ON COLUMN simulaciones.modo                    IS 'INMUTABLE tras la creación (trigger trg_simulacion_modo_inmutable). Leasing y crédito directo usan fórmulas distintas.';
COMMENT ON COLUMN simulaciones.nombre                  IS 'Título puesto por el usuario. NULL = se autogenera al leer como "{Empresa} · {Modelo} · {Modo} · #{n}". Si tiene valor, ese manda y NO se regenera al editar parámetros.';
COMMENT ON COLUMN simulaciones.id_oportunidad_item     IS 'Unidad que se está simulando. NULL = simulación libre; si sigue NULL a los 30 días, el job de purga la elimina (hard delete). Es la vía para llegar a la oportunidad y a la empresa en la propuesta PDF.';
COMMENT ON COLUMN simulaciones.id_modelo               IS 'Modelo de bus usado para prellenar precio_venta, para el nombre autogenerado y para la propuesta PDF. No participa del cálculo.';
COMMENT ON COLUMN simulaciones.id_simulacion_origen    IS 'Simulación de la que se bifurcó vía "Guardar como Nueva Simulación". SET NULL si el origen se purga; el dato permanece en simulacion_log.';
COMMENT ON COLUMN simulaciones.precio_venta            IS 'Precio unitario CON IGV. La cantidad de unidades del ítem NO participa del cálculo (solo se muestra en la propuesta y al agregar a nivel de oportunidad).';
COMMENT ON COLUMN simulaciones.descuento               IS 'Descuento porcentual 0-100, aplicado al precio_venta ANTES de todo el cálculo.';
COMMENT ON COLUMN simulaciones.cuota_inicial           IS 'Monto total de inicial CON IGV que cubre el cliente por cualquier vía (aporte propio + financiadoras externas), en un solo campo.';
COMMENT ON COLUMN simulaciones.tea                     IS 'Tasa efectiva anual en escala 1-100 (15.00 = 15%). OJO: financiadoras.tea usa escala fraccionaria (0.15). NO comparar ni copiar entre ambas sin convertir.';
COMMENT ON COLUMN simulaciones.valor_residual          IS 'Cuota balloon. Debe coincidir con el saldo final del último mes del cronograma (validación, nunca se fuerza).';
COMMENT ON COLUMN simulaciones.dias_trabajados         IS 'Campo avanzado. Solo se usa para derivar la cuota diaria; no afecta el cronograma.';
COMMENT ON COLUMN simulaciones.comision_estructuracion IS 'Campo avanzado, CON IGV. Informativo/propuesta; no afecta el cronograma.';
COMMENT ON COLUMN simulaciones.cuota_final             IS 'SOLO LECTURA. Calculado por backend. Leasing: cuota financiera x 1.18. Crédito directo: promedio de las cuotas con IGV de intereses. Es la cuota de UNA unidad.';
COMMENT ON COLUMN simulaciones.es_principal            IS 'Simulación cuya cuota se muestra para ese ítem. Si el ítem no tiene ninguna, su cuota se calcula en tiempo real con parámetros por defecto.';

-- -----------------------------------------------------------------------------
-- Bitácora única de simulaciones — permanente, sin purga
-- -----------------------------------------------------------------------------
CREATE TABLE simulacion_log (
    id                      BIGSERIAL                   PRIMARY KEY,

    -- SIN FK deliberadamente: el log debe sobrevivir al hard delete de la simulación.
    id_simulacion           BIGINT                      NOT NULL,
    id_simulacion_origen    BIGINT,

    tipo_evento             tipo_evento_simulacion_enum NOT NULL,

    -- Snapshot de campos esenciales. Poblado en creada/editada/restaurada/eliminada.
    modo                    modo_simulacion_enum,
    precio_venta            NUMERIC(12,2),
    descuento               NUMERIC(5,2),
    cuota_inicial           NUMERIC(12,2),
    plazo_meses             INT,
    tea                     NUMERIC(6,2),
    valor_residual          NUMERIC(12,2),
    dias_trabajados         INT,
    comision_estructuracion NUMERIC(12,2),
    cuota_final             NUMERIC(12,2),

    -- Ambos sin FK, por el mismo motivo que id_simulacion.
    -- id_oportunidad se guarda derivado para que el evento conserve contexto
    -- aunque el ítem se reestructure después.
    id_oportunidad_item     BIGINT,
    id_oportunidad          BIGINT,

    created_at              TIMESTAMP                   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by              BIGINT                      REFERENCES empleados(id),

    -- Los eventos con snapshot deben traerlo completo; los de enlace deben traer el ítem.
    CONSTRAINT chk_simulacion_log_snapshot CHECK (
        (tipo_evento IN ('creada', 'editada', 'restaurada', 'eliminada')
            AND modo IS NOT NULL
            AND precio_venta IS NOT NULL
            AND cuota_inicial IS NOT NULL
            AND plazo_meses IS NOT NULL
            AND tea IS NOT NULL
            AND valor_residual IS NOT NULL
            AND cuota_final IS NOT NULL)
        OR
        (tipo_evento IN ('marcada_principal', 'enlazada_a_item')
            AND id_oportunidad_item IS NOT NULL)
    )
);

CREATE INDEX idx_simulacion_log_simulacion ON simulacion_log(id_simulacion, created_at DESC);
CREATE INDEX idx_simulacion_log_tipo       ON simulacion_log(id_simulacion, tipo_evento, created_at DESC);

COMMENT ON TABLE  simulacion_log                      IS 'Bitácora completa e inmutable de simulaciones. Solo INSERT: nunca UPDATE ni DELETE, y sin job de purga. La ventana de restauración (7 días / 15 versiones) es un filtro de lectura, no una política de borrado.';
COMMENT ON COLUMN simulacion_log.id_simulacion        IS 'SIN foreign key a propósito: el log sobrevive al hard delete de la simulación (incluido el evento "eliminada", que se perdería con ON DELETE CASCADE).';
COMMENT ON COLUMN simulacion_log.id_simulacion_origen IS 'Duplicado del origen al momento de crear la bifurcación. Conserva el dato aunque simulaciones.id_simulacion_origen caiga a NULL por purga del origen.';
COMMENT ON COLUMN simulacion_log.id_oportunidad       IS 'Derivado del ítem al momento del evento. Redundante a propósito: conserva el contexto si el ítem se reestructura o elimina.';
COMMENT ON COLUMN simulacion_log.created_by           IS 'NULL cuando el evento lo genera un job programado sin actor humano (p. ej. la purga a 30 días).';

-- -----------------------------------------------------------------------------
-- Inmutabilidad de `modo` (tercera línea de defensa; frontend y Service también validan)
-- -----------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION fn_simulacion_modo_inmutable()
RETURNS TRIGGER AS $$
BEGIN
    IF NEW.modo IS DISTINCT FROM OLD.modo THEN
        RAISE EXCEPTION
            'El modo de una simulación es inmutable (id=%, actual=%, intento=%). Use "Guardar como Nueva Simulación".',
            OLD.id, OLD.modo, NEW.modo;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_simulacion_modo_inmutable
    BEFORE UPDATE ON simulaciones
    FOR EACH ROW
    EXECUTE FUNCTION fn_simulacion_modo_inmutable();

-- -----------------------------------------------------------------------------
-- RLS: el backend accede con service_role. Habilitado sin políticas => bloquea
-- todo acceso público directo, igual que el resto de tablas del esquema.
-- -----------------------------------------------------------------------------
ALTER TABLE simulaciones    ENABLE ROW LEVEL SECURITY;
ALTER TABLE simulacion_log  ENABLE ROW LEVEL SECURITY;
