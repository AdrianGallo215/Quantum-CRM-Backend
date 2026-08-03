package pe.quantum.crm.domain.financiadoras.dto

import jakarta.validation.constraints.NotBlank
import pe.quantum.crm.domain.financiadoras.Financiadora
import java.math.BigDecimal

/** Financiadora expuesta en respuestas (contrato_api.md §13). Montos como string. */
data class FinanciadoraDto(
    val id: Long,
    val nombre: String,
    val montoPorUnidad: String?,
    val plazoMeses: Int?,
    val tea: String?,
    val cuotaPorUnidad: String?,
    val esDefault: Boolean,
    val notas: String?,
)

data class CrearFinanciadoraRequest(
    @field:NotBlank
    val nombre: String,
    val montoPorUnidad: BigDecimal? = null,
    val plazoMeses: Int? = null,
    val tea: BigDecimal? = null,
    val cuotaPorUnidad: BigDecimal? = null,
    val esDefault: Boolean = false,
    val notas: String? = null,
)

data class ActualizarFinanciadoraRequest(
    val nombre: String? = null,
    val montoPorUnidad: BigDecimal? = null,
    val plazoMeses: Int? = null,
    val tea: BigDecimal? = null,
    val cuotaPorUnidad: BigDecimal? = null,
    val esDefault: Boolean? = null,
    val notas: String? = null,
)

fun Financiadora.toDto(): FinanciadoraDto =
    FinanciadoraDto(
        id = requireNotNull(id),
        nombre = nombre,
        montoPorUnidad = montoPorUnidad?.toPlainString(),
        plazoMeses = plazoMeses,
        tea = tea?.toPlainString(),
        cuotaPorUnidad = cuotaPorUnidad?.toPlainString(),
        esDefault = esDefault,
        notas = notas,
    )
