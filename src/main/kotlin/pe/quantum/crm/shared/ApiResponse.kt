package pe.quantum.crm.shared

/**
 * Envelope unico de respuesta de la API (contrato_api.md §2). Todo endpoint
 * devuelve `{ data, meta, error }`: en exito `error` es null; en error `data` es
 * null y `error` describe el fallo.
 */
data class ApiResponse<T>(
    val data: T?,
    val meta: Any? = null,
    val error: ApiError? = null,
) {
    companion object {
        fun <T> ok(data: T): ApiResponse<T> = ApiResponse(data = data)

        fun <T> ok(
            data: T,
            meta: Any?,
        ): ApiResponse<T> = ApiResponse(data = data, meta = meta)

        fun fail(error: ApiError): ApiResponse<Nothing> = ApiResponse(data = null, error = error)
    }
}

/**
 * Detalle de error del envelope (contrato_api.md §2, §3). `field` es opcional: solo
 * se usa cuando el error apunta a un campo especifico del request.
 */
data class ApiError(
    val code: String,
    val message: String,
    val field: String? = null,
)
