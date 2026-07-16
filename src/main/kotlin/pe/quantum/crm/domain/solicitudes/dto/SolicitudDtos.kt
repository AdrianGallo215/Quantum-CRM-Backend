package pe.quantum.crm.domain.solicitudes.dto

import pe.quantum.crm.domain.empleados.dto.EmpleadoResumen
import pe.quantum.crm.shared.enums.EntidadSolicitud
import pe.quantum.crm.shared.enums.TipoSolicitud
import java.math.BigDecimal
import java.time.LocalDateTime

data class CrearSolicitudRequest(
    val tipo: TipoSolicitud? = null,
    val entidadTipo: EntidadSolicitud? = null,
    val entidadId: Long? = null,
    val motivo: String? = null,
    val dctoSolicitado: BigDecimal? = null,
    val idVendedorNuevo: Long? = null,
)

data class DenegarSolicitudRequest(
    val motivo: String? = null,
)

data class SolicitudDto(
    val id: Long,
    val tipo: String,
    val estado: String,
    val rolAprobador: String,
    val entidadTipo: String,
    val entidadId: Long,
    val entidadDescripcion: String,
    val dctoSolicitado: String?,
    val idVendedorNuevo: Long?,
    val vendedorNuevo: EmpleadoResumen?,
    val motivo: String,
    val solicitante: EmpleadoResumen?,
    val resolutor: EmpleadoResumen?,
    val motivoResolucion: String?,
    val resolvedAt: LocalDateTime?,
    val createdAt: LocalDateTime,
)

data class SolicitudFiltros(
    val estado: String? = null,
    val tipo: String? = null,
    val mias: Boolean = false,
)
