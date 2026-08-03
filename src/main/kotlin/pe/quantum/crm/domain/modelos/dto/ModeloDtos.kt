package pe.quantum.crm.domain.modelos.dto

import jakarta.validation.constraints.NotBlank
import pe.quantum.crm.domain.modelos.Modelo
import pe.quantum.crm.shared.enums.Aplicacion
import java.math.BigDecimal

/** Modelo expuesto en respuestas (contrato_api.md §14). Montos como string. */
data class ModeloDto(
    val id: Long,
    val codigo: String,
    val longitud: String?,
    val capacidadTanques: String?,
    val maxAsientos: Int?,
    val precioBase: String?,
    val fichaTecnica: String?,
    val aplicaciones: List<String>,
)

data class CrearModeloRequest(
    @field:NotBlank
    val codigo: String,
    val longitud: BigDecimal? = null,
    val capacidadTanques: String? = null,
    val maxAsientos: Int? = null,
    val precioBase: BigDecimal? = null,
    val fichaTecnica: String? = null,
    val aplicaciones: List<Aplicacion>? = null,
)

data class ActualizarModeloRequest(
    val codigo: String? = null,
    val longitud: BigDecimal? = null,
    val capacidadTanques: String? = null,
    val maxAsientos: Int? = null,
    val precioBase: BigDecimal? = null,
    val fichaTecnica: String? = null,
    val aplicaciones: List<Aplicacion>? = null,
)

/** Resumen para otros modulos (oportunidades). */
data class ModeloResumen(
    val id: Long,
    val codigo: String,
    val precioBase: BigDecimal?,
)

fun Modelo.toDto(): ModeloDto =
    ModeloDto(
        id = requireNotNull(id),
        codigo = codigo,
        longitud = longitud?.toPlainString(),
        capacidadTanques = capacidadTanques,
        maxAsientos = maxAsientos,
        precioBase = precioBase?.toPlainString(),
        fichaTecnica = fichaTecnica,
        aplicaciones = aplicaciones.map { it.name }.sorted(),
    )

fun Modelo.toResumen(): ModeloResumen = ModeloResumen(id = requireNotNull(id), codigo = codigo, precioBase = precioBase)
