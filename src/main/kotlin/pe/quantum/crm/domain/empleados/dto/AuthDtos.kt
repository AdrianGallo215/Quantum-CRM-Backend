package pe.quantum.crm.domain.empleados.dto

import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import pe.quantum.crm.domain.empleados.Empleado

/** Body de `POST /auth/login`. Validado en backend (SECURITY §4.2). */
data class LoginRequest(
    @field:NotBlank
    @field:Email
    val email: String,
    @field:NotBlank
    val password: String,
)

/**
 * Empleado expuesto en respuestas (contrato_api.md §6, §7). NUNCA incluye el hash
 * de contraseña ni otros datos sensibles.
 */
data class EmpleadoDto(
    val id: Long,
    val nombres: String,
    val apellidos: String,
    val email: String,
    val rol: String,
    val area: String?,
    val puesto: String?,
    val activo: Boolean,
)

/**
 * Respuesta de login. Los tokens NO viajan en el body: van en cookies httpOnly
 * (SECURITY §2.1), inaccesibles a JavaScript. El body lleva el perfil, la duracion
 * de la sesion y si se debe forzar el cambio de contraseña.
 */
data class LoginResponse(
    val empleado: EmpleadoDto,
    val expiresIn: Long,
    val requiereCambioContrasena: Boolean,
)

/** Respuesta de refresh: solo la nueva duracion; el token va en cookie httpOnly. */
data class RefreshResponse(
    val expiresIn: Long,
)

/** Cambio de contraseña del propio usuario (contrato_api.md §6). */
data class CambiarContrasenaRequest(
    @field:NotBlank(message = "password_actual es obligatorio")
    val passwordActual: String,
    @field:NotBlank(message = "password_nueva es obligatorio")
    @field:Size(min = 8, max = 72, message = "password_nueva debe tener entre 8 y 72 caracteres")
    val passwordNueva: String,
)

fun Empleado.toDto(): EmpleadoDto =
    EmpleadoDto(
        id = requireNotNull(id) { "El empleado persistido debe tener id" },
        nombres = nombres,
        apellidos = apellidos,
        email = email,
        rol = rol.name,
        area = area,
        puesto = puesto,
        activo = activo,
    )
