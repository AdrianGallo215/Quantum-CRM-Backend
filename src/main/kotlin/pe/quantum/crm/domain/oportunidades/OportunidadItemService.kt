package pe.quantum.crm.domain.oportunidades

import pe.quantum.crm.domain.oportunidades.dto.ActualizarOportunidadItemRequest
import pe.quantum.crm.domain.oportunidades.dto.CrearOportunidadItemRequest
import pe.quantum.crm.domain.oportunidades.dto.OportunidadItemDto
import pe.quantum.crm.domain.oportunidades.dto.OportunidadItemVinculo
import pe.quantum.crm.shared.security.UsuarioActual
import java.math.BigDecimal

/**
 * API publica de los items de una oportunidad (tabla `oportunidad_items`, V42).
 * Sub-recurso de oportunidad (plan-05-mapa-migrar-items.md, decision D17): una
 * oportunidad nunca puede quedarse con cero items.
 */
interface OportunidadItemService {
    /** IDOR: item de oportunidad ajena → 404, nunca 403 (CLAUDE.md regla 14). */
    fun vinculoVisible(
        idItem: Long,
        usuario: UsuarioActual,
    ): OportunidadItemVinculo

    fun crear(
        idOportunidad: Long,
        request: CrearOportunidadItemRequest,
        usuario: UsuarioActual,
    ): OportunidadItemDto

    fun actualizar(
        idItem: Long,
        request: ActualizarOportunidadItemRequest,
        usuario: UsuarioActual,
    ): OportunidadItemDto

    /** 409 con codigo `ULTIMO_ITEM_NO_ELIMINABLE` si es el unico item de su oportunidad (D17). */
    fun eliminar(
        idItem: Long,
        usuario: UsuarioActual,
    )

    /** Sin chequeo de visibilidad: lo usa `OportunidadServiceImpl`, que ya filtro las oportunidades visibles. */
    fun porOportunidades(idsOportunidad: Collection<Long>): Map<Long, List<OportunidadItemDto>>

    /**
     * `monto_total` derivado de los items, por oportunidad (B8). Se calcula sobre
     * las entidades (`BigDecimal`), no sobre `OportunidadItemDto`, para no volver
     * a parsear los montos que ese DTO ya expone como `String`.
     *
     * Solo aparecen las oportunidades cuyo total no es null: una sin items, o con
     * todos los items incompletos, se omite del mapa (ver `MontoTotal.sumarItems`).
     * Sin chequeo de visibilidad, igual que [porOportunidades].
     */
    fun montoTotalPorOportunidades(idsOportunidad: Collection<Long>): Map<Long, BigDecimal>

    /**
     * Aplica sobre un item un descuento ya aprobado por solicitud (modulo
     * solicitudes): setea `descuento` y audita con el aprobador. `monto_total` no
     * es una columna de la oportunidad: se deriva de sus items en el momento de
     * leerlo (ver [montoTotalPorOportunidades]).
     *
     * NO valida limites de rol ni visibilidad: la aprobacion ES la autorizacion, y
     * el limite ya se verifico al crear la solicitud.
     *
     * 409 `SOLICITUD_NO_APLICABLE` si el item no existe o si su oportunidad ya
     * salio del pipeline activo (`cerrado` / `facturado`).
     */
    fun aplicarDescuentoAprobado(
        idItem: Long,
        descuento: BigDecimal,
        idAprobador: Long,
    )
}
