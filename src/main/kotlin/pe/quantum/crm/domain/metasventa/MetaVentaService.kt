package pe.quantum.crm.domain.metasventa

import pe.quantum.crm.domain.metasventa.dto.CrearMetaVentaRequest
import pe.quantum.crm.domain.metasventa.dto.EditarMetaVentaRequest
import pe.quantum.crm.domain.metasventa.dto.MetaVentaDto
import pe.quantum.crm.domain.metasventa.dto.MetaVentaFiltros
import pe.quantum.crm.domain.metasventa.dto.MetaVentaResumen
import pe.quantum.crm.shared.Paginado
import pe.quantum.crm.shared.security.UsuarioActual

/**
 * Interfaz publica del modulo metas de venta. Otros modulos (p. ej. `inicio`)
 * usan esta interfaz, nunca `MetaVentaRepository`/`MetaVenta` directamente
 * (CLAUDE.md regla #12).
 */
interface MetaVentaService {
    /**
     * jdv: crea/re-propone (si no existe fila, o si la existente está
     * `rechazada`) en estado `propuesta`; 409 si ya hay `propuesta`/`aprobada`.
     * gerencia/admin: crea o sobreescribe directo en `aprobada` (upsert).
     */
    fun crear(
        request: CrearMetaVentaRequest,
        usuario: UsuarioActual,
    ): MetaVentaDto

    /** gerencia/admin: edita cualquier subconjunto de los 12 meses, recalcula el anual y auto-aprueba. */
    fun editar(
        id: Long,
        request: EditarMetaVentaRequest,
        usuario: UsuarioActual,
    ): MetaVentaDto

    /** gerencia/admin: aprueba una `propuesta` tal cual. */
    fun aprobar(
        id: Long,
        usuario: UsuarioActual,
    ): MetaVentaDto

    /** gerencia/admin: rechaza una `propuesta` con motivo obligatorio. */
    fun rechazar(
        id: Long,
        motivo: String,
        usuario: UsuarioActual,
    ): MetaVentaDto

    /** Visibilidad: admin/gerencia/jdv ven todas; vendedor/analista solo las propias. */
    @Suppress("LongParameterList") // Paginacion + filtros del contrato, mismo patron que SolicitudService.listar.
    fun listar(
        filtros: MetaVentaFiltros,
        usuario: UsuarioActual,
        page: Int?,
        perPage: Int?,
        sort: String?,
        dir: String?,
    ): Paginado<MetaVentaDto>

    fun detalle(
        id: Long,
        usuario: UsuarioActual,
    ): MetaVentaDto

    /** Metas `aprobada` de estos empleados para el año, indexadas por id_empleado. Usado por `inicio` para el cumplimiento. */
    fun aprobadasPorEmpleadosYAnio(
        idsEmpleado: Collection<Long>,
        anio: Int,
    ): Map<Long, MetaVentaResumen>
}
