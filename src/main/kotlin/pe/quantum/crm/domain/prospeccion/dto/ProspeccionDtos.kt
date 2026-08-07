package pe.quantum.crm.domain.prospeccion.dto

import pe.quantum.crm.domain.contactos.dto.ContactoResumen
import java.time.Instant

/** Hito de prospeccion con su avance (contrato_api.md §16). */
data class HitoDto(
    val nombre: String,
    val completado: Boolean,
    val fecha: Instant?,
)

/** Empresa en prospeccion con su avance calculado (contrato_api.md §16). */
data class ProspeccionItemDto(
    val idEmpresa: Long,
    val ruc: String,
    val razonSocial: String,
    val corta: String,
    val distrito: String?,
    val segmentos: List<String>,
    val contactoPrincipal: ContactoResumen?,
    val checkpointsCompletados: Int,
    val checkpointsTotal: Int,
    val hitos: List<HitoDto>,
    val diasSinActividad: Int,
    val ultimaActividadAt: Instant?,
    val siguienteTarea: String?,
    val listaParaConvertir: Boolean,
)

/** Resumen de prospeccion para el panel de inicio (contrato_api.md §17). */
data class ResumenProspeccionDto(
    val total: Int,
    val listasParaConvertir: Int,
    val requierenAtencion: Int,
)
