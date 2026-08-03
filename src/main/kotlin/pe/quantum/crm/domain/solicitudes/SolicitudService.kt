package pe.quantum.crm.domain.solicitudes

import pe.quantum.crm.domain.solicitudes.dto.CrearSolicitudRequest
import pe.quantum.crm.domain.solicitudes.dto.SolicitudDto
import pe.quantum.crm.domain.solicitudes.dto.SolicitudFiltros
import pe.quantum.crm.shared.Paginado
import pe.quantum.crm.shared.security.UsuarioActual

/**
 * Interfaz publica del modulo solicitudes: capa intermedia de aprobacion
 * (docs/gerencia_solicitudes_modelo_datos.md, gerencia_contrato_frontend.md §4).
 */
interface SolicitudService {
    /** Valida tipo/payload/visibilidad, deriva el aprobador y notifica. 409 si ya hay una pendiente. */
    fun crear(
        request: CrearSolicitudRequest,
        usuario: UsuarioActual,
    ): SolicitudDto

    /** Visibilidad: admin todo; gerencia su bandeja; jdv su bandeja + propias; resto solo propias. */
    @Suppress("LongParameterList") // Paginacion + filtros del contrato (mismo patron que OportunidadService.listar).
    fun listar(
        filtros: SolicitudFiltros,
        usuario: UsuarioActual,
        page: Int?,
        perPage: Int?,
        sort: String?,
        dir: String?,
    ): Paginado<SolicitudDto>

    fun detalle(
        id: Long,
        usuario: UsuarioActual,
    ): SolicitudDto

    /** Aplica el cambio y marca aprobada, en la misma transaccion. Notifica al solicitante. */
    fun aprobar(
        id: Long,
        usuario: UsuarioActual,
    ): SolicitudDto

    /** Deniega con motivo obligatorio. Notifica al solicitante. */
    fun denegar(
        id: Long,
        motivo: String,
        usuario: UsuarioActual,
    ): SolicitudDto
}
