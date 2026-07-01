-- =============================================================================
-- QUANTUM CRM — SCHEMA COMPLETO v2
-- PostgreSQL · versión consolidada
-- =============================================================================
-- ORDEN DE CREACIÓN (respeta dependencias FK):
--   1.  empleados
--   2.  modelos + modelo_aplicaciones
--   3.  financiadoras
--   4.  empresas + empresa_segmentos
--   5.  contactos + empresa_contactos
--   6.  oportunidades + oportunidad_estados_log + oportunidad_contactos
--   7.  catalogo_eventos
--   8.  eventos
--   9.  tareas
--   10. buses_entregados
-- =============================================================================


-- ---------------------------------------------------------------------------
-- ENUMS GLOBALES
-- ---------------------------------------------------------------------------

CREATE TYPE rol_empleado        AS ENUM ('admin', 'gerente', 'jdv', 'vendedor', 'analista', 'otro');
CREATE TYPE aplicacion_enum     AS ENUM ('urbano', 'interprovincial', 'turismo', 'personal');
CREATE TYPE segmento_enum       AS ENUM ('urbano', 'personal', 'turismo', 'interprovincial');
CREATE TYPE origen_lead_enum    AS ENUM ('cartera', 'visita_fria', 'referido_calidda', 'red_contactos');
CREATE TYPE estado_cartera_enum AS ENUM ('no_contactado', 'no_aplica', 'no_interesado', 'prospeccion', 'oportunidad_activa', 'cliente');
-- estado_op_enum: el flujo positivo termina en 'facturado'.
--   'cerrado' es la salida NEGATIVA (recuperable) desde cualquier etapa:
--   Calidda rechazó, el cliente se echó atrás, etc. No es un paso final
--   positivo. Una oportunidad 'cerrado' puede retroceder a un estado activo.
CREATE TYPE estado_op_enum      AS ENUM ('evaluacion_calidda', 'documentos_legales', 'facturado', 'cerrado');
CREATE TYPE estado_evento_enum  AS ENUM ('pendiente', 'ocurrido', 'descartado');
CREATE TYPE tipo_accion_enum    AS ENUM ('llamada', 'correo', 'reunion', 'whatsapp', 'otro');
CREATE TYPE estado_accion_enum  AS ENUM ('pendiente', 'completada', 'cancelada');
CREATE TYPE estado_entrega_enum AS ENUM ('pendiente', 'entregado');


-- ---------------------------------------------------------------------------
-- 1. EMPLEADOS
-- ---------------------------------------------------------------------------

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


-- ---------------------------------------------------------------------------
-- 2. MODELOS
-- Catálogo de buses que Quantum vende. La configuración global de una
-- oportunidad y cada bus entregado referencian un modelo de aquí.
-- Las aplicaciones son multi-select → tabla intermedia modelo_aplicaciones.
-- Regla de backend: crear modelo y sus aplicaciones en una sola transacción.
-- Si el array de aplicaciones viene vacío, no se hace commit.
-- ---------------------------------------------------------------------------

CREATE TABLE modelos (
    id                  BIGSERIAL   PRIMARY KEY,
    codigo              VARCHAR(50) UNIQUE NOT NULL,
    longitud            NUMERIC(5,2),
    capacidad_tanques   TEXT,               -- Ej: "2x100L", "2x200L + 1x65L"
    max_asientos        INT,
    precio_base         NUMERIC(12,2),
    ficha_tecnica       TEXT                -- URL
);

CREATE TABLE modelo_aplicaciones (
    id_modelo   BIGINT          NOT NULL REFERENCES modelos(id) ON DELETE CASCADE,
    aplicacion  aplicacion_enum NOT NULL,
    PRIMARY KEY (id_modelo, aplicacion)
);


-- ---------------------------------------------------------------------------
-- 3. FINANCIADORAS
-- Calidda se precarga como default. Campos de monto/plazo/tea/cuota son
-- NULL para financiadoras con términos negociables por operación.
-- cuota_por_unidad se autocalcula al insertar/actualizar y se almacena.
-- Solo una financiadora puede tener es_default = true (unique index parcial).
-- ---------------------------------------------------------------------------

CREATE TABLE financiadoras (
    id                  BIGSERIAL   PRIMARY KEY,
    nombre              TEXT        NOT NULL,
    monto_por_unidad    NUMERIC(12,2),          -- NULL = negociable por operación
    plazo_meses         INT,                    -- NULL = negociable
    tea                 NUMERIC(6,4),           -- NULL = negociable
    cuota_por_unidad    NUMERIC(12,2),          -- autocalculada y almacenada; NULL si negociable
    es_default          BOOLEAN     NOT NULL DEFAULT false,
    notas               TEXT
);

CREATE UNIQUE INDEX uq_financiadora_default
    ON financiadoras(es_default)
    WHERE es_default = true;

-- Seed:
-- INSERT INTO financiadoras (nombre, monto_por_unidad, plazo_meses, tea, cuota_por_unidad, es_default)
-- VALUES ('Calidda – Fraccionamiento GNV', 45000.00, 48, 0.0000, 937.50, true);


-- ---------------------------------------------------------------------------
-- 4. EMPRESAS
-- Solo personas jurídicas. estado_sunat y condicion_sunat vienen de SUNAT.
-- estado_cartera es SIEMPRE derivado por el backend en la misma transacción
-- que el evento que lo causa. Nunca se actualiza de forma independiente.
-- Los segmentos son multi-select → tabla intermedia empresa_segmentos.
-- ---------------------------------------------------------------------------

CREATE TABLE empresas (
    id                  BIGSERIAL               PRIMARY KEY,
    ruc                 VARCHAR(11)             UNIQUE NOT NULL,
    razon_social        TEXT                    UNIQUE NOT NULL,
    actividad_econ      TEXT                    NOT NULL,
    ciiu                VARCHAR(6),
    sector_industrial   TEXT,
    id_vendedor         BIGINT                  REFERENCES empleados(id) ON DELETE SET NULL,
    file_drive          TEXT,
    sitio_web           TEXT,
    notas               TEXT,
    estado_sunat        TEXT                    NOT NULL,
    condicion_sunat     TEXT                    NOT NULL,
    direccion_fiscal    TEXT                    NOT NULL,
    ubicacion_real      TEXT,
    distrito            TEXT,
    provincia           TEXT,
    departamento        TEXT,
    aval_fiador         TEXT,
    origen_lead         origen_lead_enum,
    estado_cartera      estado_cartera_enum     NOT NULL DEFAULT 'no_contactado',
    created_at          TIMESTAMP               NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by          BIGINT                  NOT NULL REFERENCES empleados(id),
    updated_at          TIMESTAMP               NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by          BIGINT                  NOT NULL REFERENCES empleados(id)
);

CREATE TABLE empresa_segmentos (
    id_empresa  BIGINT          NOT NULL REFERENCES empresas(id) ON DELETE CASCADE,
    segmento    segmento_enum   NOT NULL,
    PRIMARY KEY (id_empresa, segmento)
);


-- ---------------------------------------------------------------------------
-- 5. CONTACTOS
-- Un contacto puede pertenecer a más de una empresa.
-- cargo y toma_decision varían por empresa → viven en empresa_contactos.
-- ON DELETE RESTRICT en ambos lados: borrar empresa o contacto no elimina
-- silenciosamente al otro.
-- ---------------------------------------------------------------------------

CREATE TABLE contactos (
    id          BIGSERIAL       PRIMARY KEY,
    nombres     VARCHAR(100)    NOT NULL,
    apellidos   VARCHAR(100)    NOT NULL,
    email_1     VARCHAR(150),
    email_2     VARCHAR(150),
    tlf_1       VARCHAR(20),
    tlf_2       VARCHAR(20),
    notas       TEXT,
    created_at  TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by  BIGINT          NOT NULL REFERENCES empleados(id),
    updated_at  TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by  BIGINT          NOT NULL REFERENCES empleados(id)
);

CREATE TABLE empresa_contactos (
    id_empresa      BIGINT      NOT NULL REFERENCES empresas(id)  ON DELETE RESTRICT,
    id_contacto     BIGINT      NOT NULL REFERENCES contactos(id) ON DELETE RESTRICT,
    cargo           VARCHAR(100),
    toma_decision   BOOLEAN,
    es_principal    BOOLEAN     NOT NULL DEFAULT false,
    PRIMARY KEY (id_empresa, id_contacto)
);


-- ---------------------------------------------------------------------------
-- 6. OPORTUNIDADES
-- Núcleo del pipeline.
-- id_vendedor: se llena con empresas.id_vendedor al crear la oportunidad.
--   En un traspaso de oportunidad activa, este campo MUTA al nuevo vendedor
--   (una sola fila, sin duplicar). El kanban filtra por id_vendedor = actual,
--   así el vendedor anterior deja de verla y el nuevo la ve con todo el
--   historial intacto. Nota: con la mutación se pierde el "snapshot" de
--   comisiones — la comisión es del vendedor actual al facturar. Como el
--   módulo de comisiones es post-MVP, no es un problema hoy.
-- Los términos de la financiadora se traen via JOIN — no se duplican aquí.
--
-- MONTO: el vendedor NO negocia el monto_total directamente. Negocia el
--   descuento (dcto) y, en casos raros, el precio_unitario. Por eso:
--     · precio_unitario: editable, default = modelos.precio_base
--     · monto_total: CALCULADO y de solo lectura =
--         cantidad * precio_unitario * (1 - dcto/100)
--   monto_total se recalcula en el backend al guardar; nunca se escribe a mano.
--
-- CIERRE: el fin positivo es 'facturado'. 'cerrado' es la salida negativa
--   recuperable. motivo_cierre es obligatorio a nivel DB cuando estado='cerrado'.
-- ---------------------------------------------------------------------------

CREATE TABLE oportunidades (
    id                      BIGSERIAL       PRIMARY KEY,
    id_empresa              BIGINT          NOT NULL REFERENCES empresas(id)      ON DELETE RESTRICT,
    id_vendedor             BIGINT          NOT NULL REFERENCES empleados(id),
    id_financiadora         BIGINT          NOT NULL REFERENCES financiadoras(id),
    id_modelo               BIGINT          REFERENCES modelos(id),
    estado                  estado_op_enum  NOT NULL DEFAULT 'evaluacion_calidda',
    cantidad                INT,
    precio_unitario         NUMERIC(12,2),                  -- editable; default = modelos.precio_base
    dcto                    NUMERIC(5,2),                   -- porcentaje
    monto_total             NUMERIC(12,2),                  -- CALCULADO: cantidad*precio_unitario*(1-dcto/100). Solo lectura.
    finc_paralelo           BOOLEAN,
    garantia                BOOLEAN,
    ficha_venta             TEXT,
    notas                   TEXT,
    motivo_cierre           TEXT,                           -- obligatorio cuando estado = 'cerrado'
    fecha_cierre_estimado   DATE,
    created_at              TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by              BIGINT          NOT NULL REFERENCES empleados(id),
    updated_at              TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by              BIGINT          NOT NULL REFERENCES empleados(id),

    CONSTRAINT chk_motivo_cierre
        CHECK (estado != 'cerrado' OR motivo_cierre IS NOT NULL)
);

-- Historial completo de cambios de estado.
-- Fuente de verdad para pronta facturación:
--   SELECT MIN(changed_at) FROM oportunidad_estados_log
--   WHERE id_oportunidad = $1 AND estado_nuevo = 'documentos_legales'
-- Si (NOW() - resultado) <= 30 días → aplica pronta facturación.
CREATE TABLE oportunidad_estados_log (
    id              BIGSERIAL       PRIMARY KEY,
    id_oportunidad  BIGINT          NOT NULL REFERENCES oportunidades(id) ON DELETE CASCADE,
    estado_anterior estado_op_enum,                 -- NULL = creación de la oportunidad
    estado_nuevo    estado_op_enum  NOT NULL,
    changed_at      TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    changed_by      BIGINT          NOT NULL REFERENCES empleados(id)
);

CREATE INDEX idx_op_estados_log
    ON oportunidad_estados_log(id_oportunidad, estado_nuevo, changed_at);

CREATE TABLE oportunidad_contactos (
    id_oportunidad      BIGINT      NOT NULL REFERENCES oportunidades(id) ON DELETE CASCADE,
    id_contacto         BIGINT      NOT NULL REFERENCES contactos(id)     ON DELETE RESTRICT,
    rol_en_oportunidad  VARCHAR(50),
    PRIMARY KEY (id_oportunidad, id_contacto)
);


-- ---------------------------------------------------------------------------
-- 7. CATÁLOGO DE EVENTOS
-- Define los eventos estándar del proceso comercial.
-- etapa_asociada: etapa donde ese evento es típico. NULL = cualquier etapa.
--   La UI filtra por etapa para mostrar los eventos relevantes, pero no
--   bloquea registrar un evento fuera de su etapa (sin restricción dura).
-- dispara_cambio_estado: si true, al marcar el evento como 'ocurrido' el
--   backend cambia el estado de la oportunidad a estado_destino en la misma
--   transacción.
-- es_recomendado: si true, la UI avisa al vendedor si avanza de etapa sin
--   haberlo registrado. No bloquea — solo avisa.
-- ---------------------------------------------------------------------------

CREATE TABLE catalogo_eventos (
    id                      BIGSERIAL       PRIMARY KEY,
    nombre                  TEXT            UNIQUE NOT NULL,
    etapa_asociada          estado_op_enum,
    dispara_cambio_estado   BOOLEAN         NOT NULL DEFAULT false,
    estado_destino          estado_op_enum,         -- NULL si no dispara cambio
    es_recomendado          BOOLEAN         NOT NULL DEFAULT false,

    CONSTRAINT chk_catalogo_estado_destino
        CHECK (dispara_cambio_estado = false OR estado_destino IS NOT NULL)
);

-- Seed de eventos estándar:
-- INSERT INTO catalogo_eventos (nombre, etapa_asociada, dispara_cambio_estado, estado_destino, es_recomendado) VALUES
-- ('Fee depositado',                'evaluacion_calidda',  false, NULL,                  true),
-- ('Aprobación Calidda',            'evaluacion_calidda',  true,  'documentos_legales',  true),
-- ('Rechazo Calidda',               'evaluacion_calidda',  true,  'cerrado',             true),
-- ('Desembolso Calidda',            'documentos_legales',  true,  'facturado',           true),
-- ('Propuesta aceptada',            'evaluacion_calidda',  false, NULL,                  false),
-- ('Contrato tripartito firmado',   'documentos_legales',  false, NULL,                  true),
-- ('Documentación cliente recibida','evaluacion_calidda',  false, NULL,                  false);


-- ---------------------------------------------------------------------------
-- 8. EVENTOS
-- Hechos del proceso que el vendedor registra — mayormente externos
-- (cliente, Calidda, financiadora) pero cuyo seguimiento es responsabilidad
-- del vendedor.
--
-- Origen del evento (mutuamente excluyentes, forzado por CHECK):
--   · Del catálogo:    id_catalogo_evento NOT NULL, es_personalizado = false
--   · Personalizado:   id_catalogo_evento NULL,     es_personalizado = true,
--                      nombre_personalizado NOT NULL
--
-- Tres fechas con roles distintos:
--   · fecha_estimada:    cuándo se espera que ocurra el evento
--   · fecha_seguimiento: cuándo debe el vendedor volver a presionar
--   · fecha_ocurrencia:  cuándo ocurrió realmente (se llena al marcar 'ocurrido')
--
-- Cambio de estado:
--   Cuando estado pasa a 'ocurrido' y dispara_cambio_estado = true, el backend
--   ejecuta el cambio de estado de la oportunidad y escribe en
--   oportunidad_estados_log en la misma transacción. El vendedor no puede
--   cambiar el estado de la oportunidad por otra vía para los eventos que
--   lo disparan.
--
-- Eventos personalizados: dispara_cambio_estado = false siempre.
--   Los cambios de estado automáticos están reservados al catálogo.
-- ---------------------------------------------------------------------------

CREATE TABLE eventos (
    id                      BIGSERIAL           PRIMARY KEY,
    id_oportunidad          BIGINT              NOT NULL REFERENCES oportunidades(id) ON DELETE CASCADE,
    id_catalogo_evento      BIGINT              REFERENCES catalogo_eventos(id),
    es_personalizado        BOOLEAN             NOT NULL DEFAULT false,
    nombre_personalizado    TEXT,
    descripcion             TEXT,
    estado                  estado_evento_enum  NOT NULL DEFAULT 'pendiente',
    fecha_estimada          DATE,
    fecha_seguimiento       DATE,
    fecha_ocurrencia        TIMESTAMP,
    dispara_cambio_estado   BOOLEAN             NOT NULL DEFAULT false,
    estado_destino          estado_op_enum,
    registrado_por          BIGINT              REFERENCES empleados(id),
    created_at              TIMESTAMP           NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by              BIGINT              NOT NULL REFERENCES empleados(id),
    updated_at              TIMESTAMP           NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by              BIGINT              NOT NULL REFERENCES empleados(id),

    -- Origen mutuamente excluyente
    CONSTRAINT chk_evento_origen CHECK (
        (es_personalizado = false AND id_catalogo_evento IS NOT NULL AND nombre_personalizado IS NULL)
        OR
        (es_personalizado = true  AND id_catalogo_evento IS NULL     AND nombre_personalizado IS NOT NULL)
    ),
    -- Si dispara cambio de estado, debe indicar a qué estado
    CONSTRAINT chk_evento_estado_destino CHECK (
        dispara_cambio_estado = false OR estado_destino IS NOT NULL
    ),
    -- Los eventos personalizados no pueden disparar cambios de estado
    CONSTRAINT chk_evento_personalizado_no_dispara CHECK (
        es_personalizado = false OR dispara_cambio_estado = false
    ),
    -- fecha_ocurrencia solo cuando el evento ya ocurrió
    CONSTRAINT chk_evento_fecha_ocurrencia CHECK (
        estado = 'ocurrido' OR fecha_ocurrencia IS NULL
    )
);

CREATE INDEX idx_eventos_oportunidad
    ON eventos(id_oportunidad, estado);


-- ---------------------------------------------------------------------------
-- 9. TAREAS
-- Actividades que ejecuta alguien de Quantum. A diferencia de los eventos,
-- las tareas tienen un responsable interno asignable.
-- id_oportunidad NULLABLE: NULL = tarea de prospección (sin oportunidad aún).
-- id_contacto NULLABLE: con quién es la reunión o llamada.
-- ---------------------------------------------------------------------------

CREATE TABLE tareas (
    id              BIGSERIAL           PRIMARY KEY,
    id_empresa      BIGINT              NOT NULL REFERENCES empresas(id)      ON DELETE RESTRICT,
    id_oportunidad  BIGINT              REFERENCES oportunidades(id)          ON DELETE CASCADE,
    id_contacto     BIGINT              REFERENCES contactos(id),
    id_asignado     BIGINT              REFERENCES empleados(id),
    tipo_accion     tipo_accion_enum    NOT NULL,
    estado_accion   estado_accion_enum  NOT NULL DEFAULT 'pendiente',
    descripcion     TEXT,
    fecha_ejecucion TIMESTAMP,
    created_at      TIMESTAMP           NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by      BIGINT              NOT NULL REFERENCES empleados(id),
    updated_at      TIMESTAMP           NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by      BIGINT              NOT NULL REFERENCES empleados(id)
);


-- ---------------------------------------------------------------------------
-- 10. BUSES_ENTREGADOS
-- Una fila por bus físico entregado en el marco de una oportunidad.
-- numero_serie y vin llegan NULL al crear el registro y se completan
-- al nacionalizar las unidades.
-- url_contrato = contrato individual Quantum–cliente (distinto al tripartito).
-- ON DELETE RESTRICT: no se puede eliminar una oportunidad con buses
-- registrados.
-- ---------------------------------------------------------------------------

CREATE TABLE buses_entregados (
    id                      BIGSERIAL           PRIMARY KEY,
    id_oportunidad          BIGINT              NOT NULL REFERENCES oportunidades(id) ON DELETE RESTRICT,
    id_modelo               BIGINT              NOT NULL REFERENCES modelos(id),
    numero_serie            VARCHAR(100),
    vin                     VARCHAR(50),
    url_contrato            TEXT,
    fecha_entrega_estimada  DATE,
    fecha_entrega_real      DATE,
    estado_entrega          estado_entrega_enum NOT NULL DEFAULT 'pendiente'
);


-- =============================================================================
-- NOTAS DE IMPLEMENTACIÓN (backend)
-- =============================================================================
--
-- PRONTA FACTURACIÓN
--   SELECT MIN(changed_at) FROM oportunidad_estados_log
--   WHERE id_oportunidad = $1 AND estado_nuevo = 'documentos_legales';
--   Si (NOW() - resultado) <= 30 días → aplica pronta facturación.
--
-- ESTADO_CARTERA (atomicidad + guarda de entrada)
--   Solo existe una función actualizarEstadoCartera(id_empresa) que se llama
--   dentro de la misma transacción del evento que lo causa. Nunca se actualiza
--   de forma independiente.
--
--   La función SIEMPRE recalcula mirando TODAS las oportunidades de la empresa
--   (nunca asume el estado desde la transición individual):
--     actualizarEstadoCartera(id_empresa):
--       nuevo = calcular(id_empresa):
--         · ¿tiene alguna oportunidad en 'facturado'?      → 'cliente'
--         · si no, ¿tiene alguna activa
--           ('evaluacion_calidda' | 'documentos_legales')? → 'oportunidad_activa'
--         · si no, → NULL (no hay derivado que aplicar)
--       actual = estado_cartera actual de la empresa
--       GUARDA DE ENTRADA (evita writes en vano):
--         if nuevo == actual:                 return   -- nada cambió, no escribe
--         if nuevo IS NULL and actual in manuales: return -- respeta el manual
--       UPDATE empresas SET estado_cartera = nuevo ...
--
--   Estados manuales: no_contactado, no_aplica, no_interesado, prospeccion.
--   Estados derivados: oportunidad_activa, cliente. El derivado tiene prioridad
--   sobre el manual mientras exista la oportunidad que lo justifica.
--   'cliente' se dispara con 'facturado' (cierre positivo), NO con 'cerrado'
--   ('cerrado' es la salida negativa recuperable).
--
-- TRASPASO DE OPORTUNIDAD ACTIVA
--   No se duplica la oportunidad. Se hace UPDATE de oportunidades.id_vendedor
--   al nuevo vendedor (una sola fila). El kanban filtra por id_vendedor =
--   usuario actual, así el anterior deja de verla y el nuevo la hereda con
--   todo su historial (log, eventos, tareas) intacto.
--
-- EVENTOS QUE DISPARAN CAMBIO DE ESTADO
--   El cambio de estado NO es automático. El flujo es:
--     1. Vendedor marca evento como 'ocurrido'
--     2. Backend guarda: eventos.estado = 'ocurrido', fecha_ocurrencia = NOW()
--     3. Backend detecta dispara_cambio_estado = true y devuelve estado_destino
--        en la respuesta (no ejecuta nada más)
--     4. Frontend muestra sugerencia no invasiva:
--        "¿Desea modificar la oportunidad a {estado_destino}?"
--     5a. Vendedor confirma → segunda llamada al backend que ejecuta:
--          · UPDATE oportunidades.estado = estado_destino
--          · INSERT en oportunidad_estados_log
--          · actualizarEstadoCartera(id_empresa) si aplica
--          Todo en una sola transacción.
--     5b. Vendedor descarta → el evento queda como 'ocurrido' y la
--          oportunidad mantiene su estado actual. Sin efecto secundario.
--   El evento y el cambio de estado son dos operaciones independientes.
--   El evento se guarda siempre; el estado solo cambia si el vendedor confirma.
--
-- AVANCE DE ETAPA SIN EVENTOS RECOMENDADOS
--   El vendedor puede cambiar el estado libremente. Si hay eventos con
--   es_recomendado = true sin registrar para esa etapa, la UI muestra un
--   aviso, no un bloqueo.
--
-- FINANCIADORA EN OPORTUNIDADES
--   No hay campos financieros de la financiadora en oportunidades.
--   La vista hace JOIN: oportunidades JOIN financiadoras ON id_financiadora.
--   Para términos negociados por operación (futuro) → módulo financiero
--   separado con tabla oportunidad_financiamiento.
--
-- ID_VENDEDOR EN OPORTUNIDADES
--   Se llena con empresas.id_vendedor al crear la oportunidad. En un traspaso
--   de oportunidad activa MUTA al nuevo vendedor (ver TRASPASO arriba).
--
-- MONTO_TOTAL (calculado, solo lectura)
--   No se escribe a mano. El backend lo recalcula al guardar la oportunidad:
--     monto_total = cantidad * precio_unitario * (1 - dcto/100)
--   precio_unitario default = modelos.precio_base (editable en casos raros).
--   El vendedor negocia dcto y, excepcionalmente, precio_unitario — nunca el total.
--
-- RETROCESO DE ESTADO
--   Permitido pero tratado con cuidado: la UI muestra un aviso crítico antes
--   de confirmar. Al retroceder desde 'facturado' o 'cerrado', se ejecuta
--   actualizarEstadoCartera(id_empresa), que recalcula sobre el conjunto
--   completo de oportunidades (no asume nada desde la transición individual).
--
-- IMPORT DE CARTERA (Excel)
--   · RUC: VARCHAR, no BIGINT.
--   · Múltiples contactos por fila (\n): splitear → contactos + empresa_contactos.
--   · Múltiples teléfonos: tlf_1 y tlf_2 en orden.
--   · "NO PRESENTA": insertar como NULL.
--   · Vendedor por iniciales: resolver en UI antes del import.
--   · Segmento: convertir a segmento_enum → empresa_segmentos.
--
-- MODELOS (integridad de aplicaciones)
--   El endpoint de creación recibe modelo + aplicaciones en un solo payload.
--   Si aplicaciones viene vacío → rollback. No existe endpoint que cree
--   solo el modelo.
--
-- =============================================================================
