package pe.quantum.crm.domain.solicitudes.dto

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import pe.quantum.crm.domain.empleados.dto.EmpleadoResumen
import pe.quantum.crm.shared.enums.EntidadSolicitud
import pe.quantum.crm.shared.enums.TipoSolicitud
import java.math.BigDecimal
import java.time.Instant

data class CrearSolicitudRequest(
    @field:NotNull(message = "tipo es obligatorio")
    val tipo: TipoSolicitud? = null,
    @field:NotNull(message = "entidad_tipo es obligatorio")
    val entidadTipo: EntidadSolicitud? = null,
    @field:NotNull(message = "entidad_id es obligatorio")
    val entidadId: Long? = null,
    @field:NotBlank(message = "motivo es obligatorio")
    val motivo: String? = null,
    val dctoSolicitado: BigDecimal? = null,
    val idVendedorNuevo: Long? = null,
)

data class DenegarSolicitudRequest(
    @field:NotBlank(message = "motivo es obligatorio")
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
    val resolvedAt: Instant?,
    val createdAt: Instant,
)

data class SolicitudFiltros(
    val estado: String? = null,
    val tipo: String? = null,
    val mias: Boolean = false,
)
