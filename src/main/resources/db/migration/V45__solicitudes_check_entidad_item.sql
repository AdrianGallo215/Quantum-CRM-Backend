-- =============================================================================
-- V45 — `chk_solicitud_payload` seguia exigiendo `entidad_tipo = 'oportunidad'`
-- para `tipo = 'descuento'`, literal, desde que V26 la creo. V44 agrego el
-- valor `oportunidad_item` al enum y la capa de aplicacion (SolicitudServiceImpl)
-- exige ese valor desde entonces (docs/planes/plan-05-mapa-migrar-items.md,
-- decision D12) — pero el CHECK de base de datos nunca se actualizo, asi que
-- cualquier INSERT de una solicitud de descuento (la unica forma valida desde
-- D12) violaba esta constraint y nunca se detecto porque el flujo de
-- solicitudes solo tiene cobertura en tests @Tag("integration"), bloqueados
-- localmente por Testcontainers/Docker 29 durante toda la sesion en que se
-- escribio D12.
--
-- SIN backfill de datos, a proposito: existe una solicitud de descuento
-- historica (id=1, ya resuelta como 'aprobada') cuyo entidad_id apuntaba a una
-- oportunidad que ya no existe (fue eliminada antes del backfill de V42, asi
-- que no tiene item equivalente al que reapuntarla). No hay forma correcta de
-- migrar esa fila a 'oportunidad_item' sin inventar un item que nunca existio.
-- El CHECK por eso acepta AMBOS valores de entidad_tipo para 'descuento':
-- 'oportunidad' se conserva solo por compatibilidad con este registro
-- historico huerfano (el diseño de `solicitudes` es de auditoria permanente,
-- nunca se borra), mientras que la capa de aplicacion ya es la unica puerta de
-- escritura real y solo genera filas nuevas con 'oportunidad_item'
-- (SolicitudServiceImpl.validarDescuento, unico punto de INSERT del modulo).
-- =============================================================================

ALTER TABLE solicitudes DROP CONSTRAINT chk_solicitud_payload;

ALTER TABLE solicitudes
    ADD CONSTRAINT chk_solicitud_payload CHECK (
        (tipo = 'descuento'
            AND dcto_solicitado IS NOT NULL
            AND dcto_solicitado > 0 AND dcto_solicitado <= 100
            AND entidad_tipo IN ('oportunidad_item', 'oportunidad')
            AND id_vendedor_nuevo IS NULL)
        OR
        (tipo = 'reasignacion_cliente'
            AND id_vendedor_nuevo IS NOT NULL
            AND entidad_tipo = 'empresa'
            AND dcto_solicitado IS NULL)
    );
