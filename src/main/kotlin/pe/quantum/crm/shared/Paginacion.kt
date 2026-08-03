package pe.quantum.crm.shared

import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Sort
import pe.quantum.crm.shared.exception.ValidacionException

/** Metadatos de paginacion del envelope (contrato_api.md §4). */
data class PageMeta(
    val page: Int,
    val perPage: Int,
    val total: Long,
    val totalPages: Int,
)

/** Resultado paginado que los servicios devuelven a los controllers. */
data class Paginado<T>(
    val items: List<T>,
    val meta: PageMeta,
)

/**
 * Allowlist de campos por los que un endpoint acepta ordenar. Sin ella el query
 * param `sort` llega crudo a `Sort.by(...)`, Spring Data lo resuelve como property
 * path de la entidad y cualquier valor inexistente revienta con
 * `PropertyReferenceException`: un 500 provocado por el cliente.
 *
 * Cada servicio declara su lista una sola vez; el primer campo es el orden por
 * defecto, asi siempre esta permitido por construccion. Se acepta el nombre tal
 * cual (`createdAt`) o en snake_case (`created_at`), que es como el contrato
 * expone los campos en el JSON.
 */
class CamposOrdenables(
    val porDefecto: String,
    vararg otros: String,
) {
    private val propiedades: List<String> = listOf(porDefecto) + otros
    private val porClave: Map<String, String> = propiedades.associateBy(::normalizar)

    /** Nombres tal y como los conoce el frontend; van en el mensaje de error. */
    val nombresPublicos: List<String> = propiedades.map(::aSnakeCase)

    /**
     * Property path JPA por la que ordenar. Un campo fuera de la allowlist es un
     * error del cliente (400), no un fallo del servidor.
     */
    fun resolver(sort: String?): String {
        val pedido = sort?.trim()?.takeIf { it.isNotEmpty() } ?: return porDefecto
        return porClave[normalizar(pedido)] ?: throw campoInvalido(pedido)
    }

    private fun campoInvalido(pedido: String) =
        ValidacionException(
            message = "El campo de ordenamiento '$pedido' no es válido. Campos permitidos: ${nombresPublicos.joinToString(", ")}",
            field = "sort",
        )

    private companion object {
        /** `created_at`, `createdAt` y `CreatedAt` son el mismo campo. */
        fun normalizar(valor: String): String = valor.replace("_", "").lowercase()

        fun aSnakeCase(valor: String): String = valor.replace(Regex("([a-z0-9])([A-Z])"), "$1_$2").lowercase()
    }
}

/**
 * Traduce los query params de paginacion del contrato (`page` 1-based, `per_page`
 * max 100, `sort`, `dir`) a un `PageRequest` de Spring Data (0-based).
 */
object Paginacion {
    const val PER_PAGE_DEFAULT = 20
    const val PER_PAGE_MAX = 100

    fun pageRequest(
        page: Int?,
        perPage: Int?,
        sort: String?,
        dir: String?,
        campos: CamposOrdenables,
    ): PageRequest {
        val pagina = (page ?: 1).coerceAtLeast(1)
        val tamano = (perPage ?: PER_PAGE_DEFAULT).coerceIn(1, PER_PAGE_MAX)
        val direccion = if (dir.equals("asc", ignoreCase = true)) Sort.Direction.ASC else Sort.Direction.DESC
        return PageRequest.of(pagina - 1, tamano, Sort.by(direccion, campos.resolver(sort)))
    }

    fun meta(
        page: Int,
        perPage: Int,
        total: Long,
    ): PageMeta {
        val totalPages = if (total == 0L) 0 else ((total + perPage - 1) / perPage).toInt()
        return PageMeta(page = page, perPage = perPage, total = total, totalPages = totalPages)
    }
}
