package pe.quantum.crm.domain.metasventa.dto

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Positive
import pe.quantum.crm.domain.empleados.dto.EmpleadoResumen
import java.time.Instant

data class CrearMetaVentaRequest(
    @field:NotNull(message = "id_empleado es obligatorio")
    val idEmpleado: Long? = null,
    @field:NotNull(message = "anio es obligatorio")
    val anio: Int? = null,
    @field:NotNull(message = "meta_enero es obligatorio") @field:Positive(message = "meta_enero debe ser mayor a 0")
    val metaEnero: Int? = null,
    @field:NotNull(message = "meta_febrero es obligatorio") @field:Positive(message = "meta_febrero debe ser mayor a 0")
    val metaFebrero: Int? = null,
    @field:NotNull(message = "meta_marzo es obligatorio") @field:Positive(message = "meta_marzo debe ser mayor a 0")
    val metaMarzo: Int? = null,
    @field:NotNull(message = "meta_abril es obligatorio") @field:Positive(message = "meta_abril debe ser mayor a 0")
    val metaAbril: Int? = null,
    @field:NotNull(message = "meta_mayo es obligatorio") @field:Positive(message = "meta_mayo debe ser mayor a 0")
    val metaMayo: Int? = null,
    @field:NotNull(message = "meta_junio es obligatorio") @field:Positive(message = "meta_junio debe ser mayor a 0")
    val metaJunio: Int? = null,
    @field:NotNull(message = "meta_julio es obligatorio") @field:Positive(message = "meta_julio debe ser mayor a 0")
    val metaJulio: Int? = null,
    @field:NotNull(message = "meta_agosto es obligatorio") @field:Positive(message = "meta_agosto debe ser mayor a 0")
    val metaAgosto: Int? = null,
    @field:NotNull(message = "meta_septiembre es obligatorio") @field:Positive(message = "meta_septiembre debe ser mayor a 0")
    val metaSeptiembre: Int? = null,
    @field:NotNull(message = "meta_octubre es obligatorio") @field:Positive(message = "meta_octubre debe ser mayor a 0")
    val metaOctubre: Int? = null,
    @field:NotNull(message = "meta_noviembre es obligatorio") @field:Positive(message = "meta_noviembre debe ser mayor a 0")
    val metaNoviembre: Int? = null,
    @field:NotNull(message = "meta_diciembre es obligatorio") @field:Positive(message = "meta_diciembre debe ser mayor a 0")
    val metaDiciembre: Int? = null,
) {
    /** Los 12 valores en orden calendario, ya validados como no-nulos por `@NotNull`. */
    fun meses(): List<Int> =
        listOf(
            requireNotNull(metaEnero), requireNotNull(metaFebrero), requireNotNull(metaMarzo), requireNotNull(metaAbril),
            requireNotNull(metaMayo), requireNotNull(metaJunio), requireNotNull(metaJulio), requireNotNull(metaAgosto),
            requireNotNull(metaSeptiembre), requireNotNull(metaOctubre), requireNotNull(metaNoviembre), requireNotNull(metaDiciembre),
        )
}

/** Edición parcial: solo gerencia/admin, cualquier subconjunto de los 12 meses. */
data class EditarMetaVentaRequest(
    @field:Positive(message = "meta_enero debe ser mayor a 0") val metaEnero: Int? = null,
    @field:Positive(message = "meta_febrero debe ser mayor a 0") val metaFebrero: Int? = null,
    @field:Positive(message = "meta_marzo debe ser mayor a 0") val metaMarzo: Int? = null,
    @field:Positive(message = "meta_abril debe ser mayor a 0") val metaAbril: Int? = null,
    @field:Positive(message = "meta_mayo debe ser mayor a 0") val metaMayo: Int? = null,
    @field:Positive(message = "meta_junio debe ser mayor a 0") val metaJunio: Int? = null,
    @field:Positive(message = "meta_julio debe ser mayor a 0") val metaJulio: Int? = null,
    @field:Positive(message = "meta_agosto debe ser mayor a 0") val metaAgosto: Int? = null,
    @field:Positive(message = "meta_septiembre debe ser mayor a 0") val metaSeptiembre: Int? = null,
    @field:Positive(message = "meta_octubre debe ser mayor a 0") val metaOctubre: Int? = null,
    @field:Positive(message = "meta_noviembre debe ser mayor a 0") val metaNoviembre: Int? = null,
    @field:Positive(message = "meta_diciembre debe ser mayor a 0") val metaDiciembre: Int? = null,
)

data class RechazarMetaVentaRequest(
    @field:NotBlank(message = "motivo es obligatorio")
    val motivo: String? = null,
)

data class MetaVentaDto(
    val id: Long,
    val idEmpleado: Long,
    val empleado: EmpleadoResumen?,
    val anio: Int,
    val metaEnero: Int,
    val metaFebrero: Int,
    val metaMarzo: Int,
    val metaAbril: Int,
    val metaMayo: Int,
    val metaJunio: Int,
    val metaJulio: Int,
    val metaAgosto: Int,
    val metaSeptiembre: Int,
    val metaOctubre: Int,
    val metaNoviembre: Int,
    val metaDiciembre: Int,
    val metaAnual: Int,
    val estado: String,
    val propuestoPor: EmpleadoResumen?,
    val resolutor: EmpleadoResumen?,
    val motivoRechazo: String?,
    val resolvedAt: Instant?,
    val createdAt: Instant,
)

data class MetaVentaFiltros(
    val idEmpleado: Long? = null,
    val anio: Int? = null,
    val estado: String? = null,
)

/** Resumen liviano para consumo de otros módulos (usado por `domain/inicio`, regla CLAUDE.md §12). */
data class MetaVentaResumen(
    val idEmpleado: Long,
    val anio: Int,
    val metaAnual: Int,
    val metaPorMes: List<Int>,
)
