package pe.quantum.crm.domain.empleados.dto

import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size
import pe.quantum.crm.domain.empleados.Empleado
import pe.quantum.crm.domain.empleados.RolEmpleado

/** Body de `POST /empleados` (contrato_api.md §7). Solo admin. */
data class CrearEmpleadoRequest(
    @field:NotBlank
    val nombres: String,
    @field:NotBlank
    val apellidos: String,
    @field:NotBlank
    @field:Email
    val email: String,
    @field:NotBlank
    @field:Size(min = 8, message = "La contraseña debe tener al menos 8 caracteres")
    val password: String,
    val rol: RolEmpleado,
    val area: String? = null,
    val puesto: String? = null,
)

/**
 * Regex de "presente y no en blanco": `@NotBlank` no sirve aqui porque rechaza el
 * `null`, que en esta request significa "no tocar el campo". `@Pattern` en cambio
 * da por valido el `null` y solo mira el valor cuando viene.
 */
private const val NO_EN_BLANCO = "(?s).*\\S.*"

/**
 * Body de `PUT /empleados/:id`. No actualiza contraseña.
 *
 * Actualizacion parcial: todos los campos son opcionales y un `null` significa
 * "no modificar". Lo que se rechaza es un valor PRESENTE pero invalido, con las
 * mismas reglas que el create. Sin esto, un `{"email":"pepe"}` respondia 200 y
 * dejaba la cuenta sin acceso para siempre: `LoginRequest.email` si valida el
 * formato, asi que el login de ese empleado moria en 400 antes de llegar al
 * servicio y solo se podia arreglar tocando la base a mano.
 */
data class ActualizarEmpleadoRequest(
    @field:Pattern(regexp = NO_EN_BLANCO, message = "Los nombres no pueden estar en blanco")
    val nombres: String? = null,
    @field:Pattern(regexp = NO_EN_BLANCO, message = "Los apellidos no pueden estar en blanco")
    val apellidos: String? = null,
    @field:Pattern(regexp = NO_EN_BLANCO, message = "El email no puede estar en blanco")
    @field:Email(message = "El email no tiene un formato válido")
    val email: String? = null,
    val rol: RolEmpleado? = null,
    val area: String? = null,
    val puesto: String? = null,
)

/** Body de `PATCH /empleados/:id/activo`. */
data class CambiarActivoRequest(
    val activo: Boolean,
)

/** Resumen para otros modulos (vendedor/asignado en DTOs compuestos). */
data class EmpleadoResumen(
    val id: Long,
    val nombres: String,
    val apellidos: String,
)

fun Empleado.toResumen(): EmpleadoResumen = EmpleadoResumen(id = requireNotNull(id), nombres = nombres, apellidos = apellidos)

fun EmpleadoResumen.nombreCompleto(): String = "$nombres $apellidos"
