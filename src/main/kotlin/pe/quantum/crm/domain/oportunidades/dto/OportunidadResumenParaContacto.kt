package pe.quantum.crm.domain.oportunidades.dto

import pe.quantum.crm.domain.empresas.dto.EmpresaResumen
import java.time.LocalDate

/** Oportunidad vinculada a un contacto, para su vista de detalle (contrato_api.md §9). */
data class OportunidadResumenParaContacto(
    val id: Long,
    val empresa: EmpresaResumen?,
    val modelo: ModeloEnOportunidadDto?,
    val estado: String,
    val montoTotal: String?,
    val fechaCierreEstimado: LocalDate?,
    val rolEnOportunidad: String?,
)
