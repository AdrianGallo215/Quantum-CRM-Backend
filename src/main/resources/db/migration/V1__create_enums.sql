-- =============================================================================
-- V1 — ENUMs globales
-- Deben crearse antes que cualquier tabla que los referencie.
-- =============================================================================

CREATE TYPE rol_empleado AS ENUM (
    'admin',
    'gerente',
    'jdv',
    'vendedor',
    'analista',
    'otro'
);

CREATE TYPE aplicacion_enum AS ENUM (
    'urbano',
    'interprovincial',
    'turismo',
    'personal'
);

CREATE TYPE segmento_enum AS ENUM (
    'urbano',
    'personal',
    'turismo',
    'interprovincial'
);

CREATE TYPE origen_lead_enum AS ENUM (
    'cartera',
    'visita_fria',
    'referido_calidda',
    'red_contactos'
);

CREATE TYPE estado_cartera_enum AS ENUM (
    'no_contactado',
    'no_aplica',
    'no_interesado',
    'prospeccion',
    'oportunidad_activa',
    'cliente'
);

-- Flujo positivo termina en 'facturado'.
-- 'cerrado' es la salida negativa recuperable desde cualquier etapa.
-- No existe 'perdido' — toda oportunidad cerrada puede recuperarse.
CREATE TYPE estado_op_enum AS ENUM (
    'evaluacion_calidda',
    'documentos_legales',
    'facturado',
    'cerrado'
);

CREATE TYPE estado_evento_enum AS ENUM (
    'pendiente',
    'ocurrido',
    'descartado'
);

CREATE TYPE tipo_accion_enum AS ENUM (
    'llamada',
    'correo',
    'reunion',
    'whatsapp',
    'otro'
);

CREATE TYPE estado_accion_enum AS ENUM (
    'pendiente',
    'completada',
    'cancelada'
);

CREATE TYPE estado_entrega_enum AS ENUM (
    'pendiente',
    'entregado'
);
