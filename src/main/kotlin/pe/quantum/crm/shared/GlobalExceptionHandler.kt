package pe.quantum.crm.shared

import org.slf4j.LoggerFactory
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import pe.quantum.crm.shared.exception.ApiException
import pe.quantum.crm.shared.exception.DemasiadosIntentosException
import java.util.UUID

/**
 * Traduce las excepciones al envelope de error unico (contrato_api.md §2, §3).
 * Un error interno responde con mensaje generico + un id de correlacion que se
 * loguea con el detalle completo (SECURITY §11): nunca se filtra el stacktrace.
 */
@RestControllerAdvice
class GlobalExceptionHandler {
    private val log = LoggerFactory.getLogger(GlobalExceptionHandler::class.java)

    @ExceptionHandler(ApiException::class)
    fun handleApiException(ex: ApiException): ResponseEntity<ApiResponse<Nothing>> {
        val body = ApiResponse.fail(ApiError(code = ex.code, message = ex.message, field = ex.field))
        val response = ResponseEntity.status(ex.status)
        if (ex is DemasiadosIntentosException) {
            response.header(HttpHeaders.RETRY_AFTER, ex.retryAfterSeconds.toString())
        }
        return response.body(body)
    }

    @ExceptionHandler(MethodArgumentNotValidException::class)
    fun handleValidation(ex: MethodArgumentNotValidException): ResponseEntity<ApiResponse<Nothing>> {
        val fieldError = ex.bindingResult.fieldErrors.firstOrNull()
        val error =
            ApiError(
                code = "VALIDACION",
                message = fieldError?.defaultMessage ?: "Datos invalidos",
                field = fieldError?.field,
            )
        return ResponseEntity.badRequest().body(ApiResponse.fail(error))
    }

    @ExceptionHandler(Exception::class)
    fun handleUnexpected(ex: Exception): ResponseEntity<ApiResponse<Nothing>> {
        val correlationId = UUID.randomUUID().toString()
        log.error("Error interno no controlado [correlationId={}]", correlationId, ex)
        val error =
            ApiError(
                code = "ERROR_INTERNO",
                message = "Ocurrio un error inesperado. Referencia: $correlationId",
            )
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(ApiResponse.fail(error))
    }
}
