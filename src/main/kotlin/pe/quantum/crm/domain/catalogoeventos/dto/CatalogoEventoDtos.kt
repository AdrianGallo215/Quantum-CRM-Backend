package pe.quantum.crm.domain.catalogoeventos.dto

import jakarta.validation.constraints.NotBlank
import pe.quantum.crm.domain.catalogoeventos.CatalogoEvento
import pe.quantum.crm.shared.enums.EstadoOportunidad

/** Evento del catalogo expuesto en respuestas (contrato_api.md §15). */
data class CatalogoEventoDto(
    val id: Long,
    val nombre: String,
    val etapaAsociada: String?,
    val disparaCambioEstado: Boolean,
    val estadoDestino: String?,
    val esRecomendado: Boolean,
    val esHitoProspeccion: Boolean,
)

data class CrearCatalogoEventoRequest(
    @field:NotBlank
    val nombre: String,
    val etapaAsociada: EstadoOportunidad? = null,
    val disparaCambioEstado: Boolean = false,
    val estadoDestino: EstadoOportunidad? = null,
    val esRecomendado: Boolean = false,
    val esHitoProspeccion: Boolean = false,
)

data class ActualizarCatalogoEventoRequest(
    val nombre: String? = null,
    val etapaAsociada: EstadoOportunidad? = null,
    val disparaCambioEstado: Boolean? = null,
    val estadoDestino: EstadoOportunidad? = null,
    val esRecomendado: Boolean? = null,
    val esHitoProspeccion: Boolean? = null,
)

fun CatalogoEvento.toDto(): CatalogoEventoDto =
    CatalogoEventoDto(
        id = requireNotNull(id),
        nombre = nombre,
        etapaAsociada = etapaAsociada?.name,
        disparaCambioEstado = disparaCambioEstado,
        estadoDestino = estadoDestino?.name,
        esRecomendado = esRecomendado,
        esHitoProspeccion = esHitoProspeccion,
    )
