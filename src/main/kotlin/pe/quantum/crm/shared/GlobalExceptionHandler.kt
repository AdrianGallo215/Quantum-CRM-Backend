package pe.quantum.crm.shared

import jakarta.validation.ConstraintViolationException
import org.slf4j.LoggerFactory
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.HttpRequestMethodNotSupportedException
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.MissingServletRequestParameterException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException
import org.springframework.web.multipart.MaxUploadSizeExceededException
import org.springframework.web.servlet.resource.NoResourceFoundException
import pe.quantum.crm.shared.exception.ApiException
import pe.quantum.crm.shared.exception.DemasiadosIntentosException
import java.util.UUID

/**
 * Traduce las excepciones al envelope de error unico (contrato_api.md §2, §3).
 * Un error interno responde con mensaje generico + un id de correlacion que se
 * loguea con el detalle completo (SECURITY §11): nunca se filtra el stacktrace.
 *
 * Los errores corrientes de Spring MVC (id no numerico, metodo no soportado,
 * query param ausente, subida grande, violacion de integridad) tienen handler
 * propio a proposito: sin el caerian en el generico, responderian 500 y llenarian
 * los logs de stacktraces que enmascaran los 500 de verdad.
 */
@RestControllerAdvice
@Suppress("TooManyFunctions") // Un handler por excepcion: partirlo solo dispersaria el contrato.
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

    @ExceptionHandler(org.springframework.security.access.AccessDeniedException::class)
    fun handleAccessDenied(): ResponseEntity<ApiResponse<Nothing>> {
        val error = ApiError(code = "PERMISO_INSUFICIENTE", message = "El rol no tiene acceso a esta operación")
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(ApiResponse.fail(error))
    }

    @ExceptionHandler(MethodArgumentNotValidException::class)
    fun handleValidation(ex: MethodArgumentNotValidException): ResponseEntity<ApiResponse<Nothing>> {
        val fieldError = ex.bindingResult.fieldErrors.firstOrNull()
        val error =
            ApiError(
                code = "VALIDACION",
                message = fieldError?.defaultMessage ?: "Datos invalidos",
                field = fieldError?.field?.aCampoSnakeCase(),
            )
        return ResponseEntity.badRequest().body(ApiResponse.fail(error))
    }

    @ExceptionHandler(HttpMessageNotReadableException::class)
    fun handleMalformedBody(): ResponseEntity<ApiResponse<Nothing>> {
        val error = ApiError(code = "VALIDACION", message = "El cuerpo de la petición es inválido o le faltan campos obligatorios")
        return ResponseEntity.badRequest().body(ApiResponse.fail(error))
    }

    /**
     * El valor de un path variable o query param no encaja con su tipo, p. ej.
     * `GET /oportunidades/abc` con `id: Long`. Es un request mal formado (400).
     * No se devuelve el valor recibido para no reflejarlo en la respuesta.
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException::class)
    fun handleTypeMismatch(ex: MethodArgumentTypeMismatchException): ResponseEntity<ApiResponse<Nothing>> {
        val esperado = ex.requiredType?.simpleName ?: "el tipo esperado"
        val error =
            ApiError(
                code = "VALIDACION",
                message = "El parámetro '${ex.name}' no tiene un valor válido: se esperaba $esperado",
                field = ex.name,
            )
        return ResponseEntity.badRequest().body(ApiResponse.fail(error))
    }

    /** Falta un query param obligatorio del contrato. */
    @ExceptionHandler(MissingServletRequestParameterException::class)
    fun handleMissingParam(ex: MissingServletRequestParameterException): ResponseEntity<ApiResponse<Nothing>> {
        val error =
            ApiError(
                code = "VALIDACION",
                message = "Falta el parámetro obligatorio '${ex.parameterName}'",
                field = ex.parameterName,
            )
        return ResponseEntity.badRequest().body(ApiResponse.fail(error))
    }

    /**
     * Validacion de Jakarta sobre parametros sueltos (`@Validated` en el controller),
     * que no pasa por `MethodArgumentNotValidException` porque no hay body.
     */
    @ExceptionHandler(ConstraintViolationException::class)
    fun handleConstraintViolation(ex: ConstraintViolationException): ResponseEntity<ApiResponse<Nothing>> {
        val violacion = ex.constraintViolations?.firstOrNull()
        val error =
            ApiError(
                code = "VALIDACION",
                message = violacion?.message ?: "Datos invalidos",
                // El propertyPath incluye el metodo ("listar.page"); solo interesa el campo.
                field = violacion?.propertyPath?.toString()?.substringAfterLast('.')?.aCampoSnakeCase(),
            )
        return ResponseEntity.badRequest().body(ApiResponse.fail(error))
    }

    /** Verbo HTTP que la ruta no soporta. */
    @ExceptionHandler(HttpRequestMethodNotSupportedException::class)
    fun handleMethodNotSupported(ex: HttpRequestMethodNotSupportedException): ResponseEntity<ApiResponse<Nothing>> {
        val error =
            ApiError(
                code = "METODO_NO_PERMITIDO",
                message = "El método ${ex.method} no está permitido en esta ruta",
            )
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED).body(ApiResponse.fail(error))
    }

    /**
     * La subida supera el limite de multipart. Se responde con el mismo codigo que
     * el limite propio de Drive: para el cliente es el mismo problema (413).
     */
    @ExceptionHandler(MaxUploadSizeExceededException::class)
    fun handleUploadTooLarge(ex: MaxUploadSizeExceededException): ResponseEntity<ApiResponse<Nothing>> {
        log.warn("Subida rechazada por tamano: maxUploadSize={}", ex.maxUploadSize)
        val error =
            ApiError(
                code = "ARCHIVO_DEMASIADO_GRANDE",
                message = "El archivo supera el tamaño máximo permitido",
                field = "file",
            )
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).body(ApiResponse.fail(error))
    }

    /**
     * Violacion de FK o de unique que ningun validador de negocio atrapo. Es 409,
     * no 500: el conflicto lo provoca el estado de los datos del request.
     *
     * El mensaje del driver nombra tablas, columnas y constraints — esquema puro —
     * asi que NUNCA se devuelve: va al log y el cliente recibe un texto generico
     * (SECURITY §11).
     */
    @ExceptionHandler(DataIntegrityViolationException::class)
    fun handleDataIntegrity(ex: DataIntegrityViolationException): ResponseEntity<ApiResponse<Nothing>> {
        val correlationId = UUID.randomUUID().toString()
        log.warn("Violacion de integridad [correlationId={}]", correlationId, ex)
        val error =
            ApiError(
                code = "CONFLICTO_DATOS",
                message = "La operación entra en conflicto con datos existentes. Referencia: $correlationId",
            )
        return ResponseEntity.status(HttpStatus.CONFLICT).body(ApiResponse.fail(error))
    }

    /**
     * Recurso estatico inexistente (p. ej. `/favicon.ico`, que todo navegador pide
     * solo). No es un error de la aplicacion: 404 silencioso, sin loguear.
     */
    @ExceptionHandler(NoResourceFoundException::class)
    fun handleNoResourceFound(): ResponseEntity<ApiResponse<Nothing>> {
        val error = ApiError(code = "NO_ENCONTRADO", message = "El recurso no existe")
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiResponse.fail(error))
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
