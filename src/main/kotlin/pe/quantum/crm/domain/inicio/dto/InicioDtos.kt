package pe.quantum.crm.domain.inicio.dto

import pe.quantum.crm.domain.contactos.dto.ContactoResumen
import pe.quantum.crm.domain.empresas.dto.EmpresaResumen
import pe.quantum.crm.domain.prospeccion.dto.ResumenProspeccionDto
import java.time.LocalDate
import java.time.LocalDateTime

/** Tarea pendiente en el panel de inicio (contrato_api.md §17). */
data class TareaInicioDto(
    val id: Long,
    val descripcion: String?,
    val tipoAccion: String,
    val fechaEjecucion: LocalDateTime?,
    val estaVencida: Boolean,
    val esHoy: Boolean,
    val empresa: EmpresaResumen?,
    val idOportunidad: Long?,
    val contacto: ContactoResumen?,
)

/** Evento pendiente con fecha de seguimiento (contrato §17). */
data class EventoSeguimientoDto(
    val id: Long,
    val nombre: String,
    val fechaSeguimiento: LocalDate,
    val seguimientoVencido: Boolean,
    val disparaCambioEstado: Boolean,
    val empresa: EmpresaResumen?,
    val idOportunidad: Long?,
)

/** Totales de una etapa del pipeline. */
data class EtapaResumenDto(
    val count: Int,
    val valor: String,
    val cantidadUnidades: Int,
)

/** Resumen del pipeline del usuario (contrato §17). */
data class ResumenPipelineDto(
    val valorTotal: String,
    val oportunidadesActivas: Int,
    val cantidadUnidades: Int,
    val porEtapa: Map<String, EtapaResumenDto>,
)

/** Un medidor de cumplimiento (mensual o anual) del panel de inicio (contrato §17). */
data class MedidorMetaDto(
    val tieneMeta: Boolean,
    val unidadesMeta: Int?,
    val unidadesLogradas: Int,
    val porcentaje: Int?,
)

/** Cumplimiento agregado del equipo (solo para jdv). */
data class MetaVentaAgregadoDto(
    val mensual: MedidorMetaDto,
    val anual: MedidorMetaDto,
)

/** Bloque de metas de venta del panel de inicio; null para roles sin meta (contrato §17). */
data class MetaVentaInicioDto(
    val mensual: MedidorMetaDto,
    val anual: MedidorMetaDto,
    val equipo: MetaVentaAgregadoDto?,
)

/** Respuesta completa de `GET /inicio`: una sola llamada (contrato §17). */
data class InicioDto(
    val tareasPendientes: List<TareaInicioDto>,
    val eventosPorSeguir: List<EventoSeguimientoDto>,
    val resumenPipeline: ResumenPipelineDto,
    val resumenProspeccion: ResumenProspeccionDto,
    val metaVentas: MetaVentaInicioDto?,
)
