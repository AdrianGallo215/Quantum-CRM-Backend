-- =============================================================================
-- V36 — CHECKs numéricos de oportunidades
--
-- Los DTOs de request ya validan estos rangos (Bean Validation), pero la API no
-- es la única puerta a la tabla: la aprobación de una solicitud de descuento
-- (§19), los jobs y cualquier script de mantenimiento escriben directo. La
-- invariante vive aquí, en la base, que es donde no se puede esquivar.
--
-- POR QUÉ IMPORTA `dcto`:
--   monto_total = cantidad * precio_unitario * (1 - dcto/100)
--   Con dcto = -100 el factor pasa a valer 2 y el monto_total se DUPLICA, sin
--   que ningún control de permisos lo note: la política de descuentos solo
--   compara `dcto > límite del rol`, y un descuento negativo nunca supera un
--   límite. Eso contamina los reportes de ventas, de descuentos y el valor del
--   pipeline.
--
-- NULL TOLERADO A PROPÓSITO: las tres columnas son nullable (V10) y una
-- oportunidad recién creada puede no tener todavía cantidad ni precio. En SQL
-- un CHECK que evalúa a UNKNOWN no se viola, así que `CHECK (cantidad > 0)`
-- deja pasar `cantidad IS NULL`. Por eso NO se escribe `OR ... IS NULL`: sería
-- redundante y sugeriría que el NULL es un caso especial cuando no lo es.
--
-- Ver reglas_negocio.md §7, CLAUDE.md regla 2.
-- =============================================================================

-- ---------------------------------------------------------------------------
-- Normalización previa
--
-- `ALTER TABLE ... ADD CONSTRAINT` valida las filas existentes y aborta la
-- migración entera si alguna incumple. Como no se puede inspeccionar la base de
-- producción desde aquí, se asume que puede haber filas corruptas (el bug del
-- dcto negativo era explotable por API) y se limpian antes.
--
-- Criterio, campo por campo:
--   dcto            → a 0. Es el único reemplazo inequívoco: reglas §7.2 ya
--                     trata `dcto IS NULL` como 0, así que el monto recalculado
--                     queda en el precio de lista, que es el dato honesto.
--   cantidad        → a NULL. No hay valor correcto deducible: un -5 puede ser
--                     un signo invertido, un typo o una fila de prueba, e
--                     inventar un número falsearía una venta. NULL es un estado
--                     que el modelo ya admite y que deja monto_total en NULL
--                     (reglas §7.2), marcando la fila como incompleta para que
--                     un humano la revise. No se pierde ninguna fila.
--   precio_unitario → a NULL, por el mismo motivo que cantidad.
--
-- Se recalcula monto_total en las mismas filas para que no quede un importe
-- derivado de los valores corruptos. Solo se tocan las filas que incumplen: las
-- sanas no se reescriben.
-- ---------------------------------------------------------------------------
UPDATE oportunidades o
SET dcto            = n.dcto,
    cantidad        = n.cantidad,
    precio_unitario = n.precio_unitario,
    monto_total     = CASE
                          WHEN n.cantidad IS NULL OR n.precio_unitario IS NULL THEN NULL
                          ELSE ROUND(n.cantidad * n.precio_unitario * (1 - COALESCE(n.dcto, 0) / 100), 2)
                      END,
    updated_at      = CURRENT_TIMESTAMP
FROM (
    SELECT id,
           CASE WHEN dcto < 0 OR dcto > 100      THEN 0    ELSE dcto            END AS dcto,
           CASE WHEN cantidad <= 0               THEN NULL ELSE cantidad        END AS cantidad,
           CASE WHEN precio_unitario < 0         THEN NULL ELSE precio_unitario END AS precio_unitario
    FROM oportunidades
    WHERE dcto < 0 OR dcto > 100 OR cantidad <= 0 OR precio_unitario < 0
) n
WHERE o.id = n.id;

ALTER TABLE oportunidades
    ADD CONSTRAINT chk_oportunidad_dcto_rango
        CHECK (dcto >= 0 AND dcto <= 100);

ALTER TABLE oportunidades
    ADD CONSTRAINT chk_oportunidad_cantidad_positiva
        CHECK (cantidad > 0);

ALTER TABLE oportunidades
    ADD CONSTRAINT chk_oportunidad_precio_unitario_no_negativo
        CHECK (precio_unitario >= 0);

COMMENT ON COLUMN oportunidades.dcto
    IS 'Descuento porcentual entre 0 y 100 (CHECK chk_oportunidad_dcto_rango). NULL = sin descuento. Fuera de ese rango el factor (1 - dcto/100) distorsiona monto_total.';
COMMENT ON COLUMN oportunidades.cantidad
    IS 'Unidades de la operación, siempre > 0 (CHECK chk_oportunidad_cantidad_positiva). NULL mientras la oportunidad no tenga cantidad definida.';
COMMENT ON COLUMN oportunidades.precio_unitario
    IS 'Editable, nunca negativo (CHECK chk_oportunidad_precio_unitario_no_negativo). Se inicializa con modelos.precio_base al crear la oportunidad.';
