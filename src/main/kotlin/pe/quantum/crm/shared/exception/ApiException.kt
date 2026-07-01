package pe.quantum.crm.shared.exception

import org.springframework.http.HttpStatus

/**
 * Base de las excepciones de negocio que el GlobalExceptionHandler traduce al
 * envelope de error (contrato_api.md §2, §3). Cada una lleva su `code`, mensaje y
 * status HTTP.
 */
abstract class ApiException(
    val code: String,
    override val message: String,
    val status: HttpStatus,
    val field: String? = null,
) : RuntimeException(message)

/**
 * Credenciales de login invalidas. Mensaje generico a proposito: no revela si el
 * error fue el email o la contraseña, para no permitir enumeracion (SECURITY §2.4).
 */
class CredencialesInvalidasException :
    ApiException(
        code = "CREDENCIALES_INVALIDAS",
        message = "Email o contraseña incorrectos",
        status = HttpStatus.UNAUTHORIZED,
    )

/** Recurso inexistente (o ajeno — IDOR se trata como 404, SECURITY §3.2). */
class NoEncontradoException(
    message: String = "El recurso no existe",
) : ApiException(code = "NO_ENCONTRADO", message = message, status = HttpStatus.NOT_FOUND)

/** Rate limit de login superado (SECURITY §8). Lleva los segundos de Retry-After. */
class DemasiadosIntentosException(
    val retryAfterSeconds: Long,
) : ApiException(
        code = "DEMASIADOS_INTENTOS",
        message = "Demasiados intentos fallidos. Intenta de nuevo mas tarde.",
        status = HttpStatus.TOO_MANY_REQUESTS,
    )
