package pe.quantum.crm.domain.empleados.dto

import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotBlank
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

/** Body de `PUT /empleados/:id`. No actualiza contraseña. */
data class ActualizarEmpleadoRequest(
    val nombres: String? = null,
    val apellidos: String? = null,
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
