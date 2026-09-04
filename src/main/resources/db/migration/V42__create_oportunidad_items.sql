-- =============================================================================
-- V42 — Oportunidad_items: primer paso del rediseño que permite a una
-- oportunidad llevar varios modelos de bus distintos, en vez de uno solo por
-- fila (ver docs/planes/plan-03-mapa-oportunidad-items.md, decision D6).
--
-- Estrategia expand -> migrate -> contract: esta migracion solo EXPANDE el
-- esquema y hace el backfill de los datos existentes. Las columnas viejas de
-- `oportunidades` (id_modelo, cantidad, precio_unitario, dcto, monto_total)
-- NO se tocan aqui y siguen siendo la fuente de verdad hasta que un plan
-- posterior migre el codigo de lectura/escritura y las retire.
--
-- `cantidad` y `precio_venta` son nullable a proposito, igual que en
-- `oportunidades` (V10) y como documenta V36: una oportunidad recien creada
-- puede no tener todavia cantidad ni precio definidos. Un CHECK sobre una
-- columna NULL evalua a UNKNOWN y no se viola, asi que
-- `CHECK (cantidad > 0)` deja pasar el NULL sin necesidad de escribir
-- `OR cantidad IS NULL` (seria redundante y sugeriria un caso especial que
-- no existe).
--
-- Los nombres de columna son `precio_venta` y `descuento`, no
-- `precio_unitario`/`dcto` como en `oportunidades`: los fijo
-- `docs/reglas_simulaciones.md` (documento cerrado), que ya los usa asi, y de
-- ellos depende la migracion de simulaciones (renumerada de V40 a V43 en este
-- mismo plan, porque V41 ya esta aplicada en produccion y Flyway no admite
-- migraciones fuera de orden).
--
-- `cuota_financiadora` es lo que el cliente paga a terceros (Calidda, cajas)
-- para cubrir su inicial; vive en el item porque cada modelo de la operacion
-- puede tener una cuota distinta. Su default (937.50) es el mismo que usa hoy
-- el calculo por defecto de una simulacion (reglas_simulaciones.md §1.2). No
-- participa del monto_total de la oportunidad.
--
-- `ON DELETE CASCADE` hacia `oportunidades`: un item no tiene sentido sin su
-- oportunidad, mismo criterio que `oportunidad_contactos` (V12).
-- =============================================================================

CREATE TABLE oportunidad_items (
    id                  BIGSERIAL       PRIMARY KEY,
    id_oportunidad      BIGINT          NOT NULL REFERENCES oportunidades(id) ON DELETE CASCADE,
    id_modelo           BIGINT          NOT NULL REFERENCES modelos(id),
    cantidad            INT,
    precio_venta        NUMERIC(12,2),
    descuento           NUMERIC(5,2),
    cuota_financiadora  NUMERIC(12,2)   NOT NULL DEFAULT 937.50,
    created_at          TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by          BIGINT          NOT NULL REFERENCES empleados(id),
    updated_at          TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by          BIGINT          NOT NULL REFERENCES empleados(id),

    CONSTRAINT chk_oportunidad_item_cantidad_positiva      CHECK (cantidad > 0),
    CONSTRAINT chk_oportunidad_item_precio_no_negativo     CHECK (precio_venta >= 0),
    CONSTRAINT chk_oportunidad_item_descuento_rango        CHECK (descuento >= 0 AND descuento <= 100),
    CONSTRAINT chk_oportunidad_item_cuota_finc_no_negativa CHECK (cuota_financiadora >= 0)
);

CREATE INDEX idx_oportunidad_items_oportunidad ON oportunidad_items(id_oportunidad);
CREATE INDEX idx_oportunidad_items_modelo      ON oportunidad_items(id_modelo);

ALTER TABLE oportunidad_items ENABLE ROW LEVEL SECURITY;

COMMENT ON TABLE  oportunidad_items                    IS 'Un modelo de bus dentro de una oportunidad. Una oportunidad con varios modelos distintos tiene varios items. Ver plan-03-mapa-oportunidad-items.md D6.';
COMMENT ON COLUMN oportunidad_items.cantidad            IS 'Nullable a proposito: una oportunidad recien creada puede no tener cantidad definida todavia (espejo de oportunidades.cantidad, V10/V36).';
COMMENT ON COLUMN oportunidad_items.precio_venta        IS 'Nullable a proposito, mismo criterio que cantidad. Editable; se inicializa con modelos.precio_base al crear el item.';
COMMENT ON COLUMN oportunidad_items.descuento           IS 'Porcentual 0-100. NULL = sin descuento (tratado como 0 en el calculo, igual que oportunidades.dcto).';
COMMENT ON COLUMN oportunidad_items.cuota_financiadora  IS 'Lo que el cliente paga a terceros (Calidda, cajas) por su inicial. Editable por el vendedor. NO participa del monto_total de la oportunidad (reglas_simulaciones.md §1.2).';

-- -----------------------------------------------------------------------------
-- Backfill: un item por cada oportunidad existente, copiando sus valores
-- actuales. Verificado contra produccion antes de escribir esta migracion:
-- 5 oportunidades, todas con id_modelo/cantidad/precio_unitario/monto_total
-- poblados y coherentes entre si (monto_total coincide exactamente con
-- cantidad * precio_unitario * (1 - dcto/100) en los 5 casos), ninguna
-- facturada. El backfill es determinista, sin casos sucios que arbitrar.
-- -----------------------------------------------------------------------------
INSERT INTO oportunidad_items
    (id_oportunidad, id_modelo, cantidad, precio_venta, descuento,
     created_at, created_by, updated_at, updated_by)
SELECT id, id_modelo, cantidad, precio_unitario, dcto,
       created_at, created_by, updated_at, updated_by
FROM oportunidades;

-- Si el backfill dejo alguna oportunidad sin su item, abortar la migracion
-- entera en vez de dejar datos a medias en produccion.
DO $$
DECLARE
    faltantes INT;
BEGIN
    SELECT count(*) INTO faltantes
    FROM oportunidades o
    WHERE NOT EXISTS (SELECT 1 FROM oportunidad_items i WHERE i.id_oportunidad = o.id);

    IF faltantes > 0 THEN
        RAISE EXCEPTION 'Backfill incompleto: % oportunidades sin item', faltantes;
    END IF;
END $$;
