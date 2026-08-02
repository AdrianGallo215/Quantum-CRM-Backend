package pe.quantum.crm.shared

import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Sort

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
        defaultSort: String,
    ): PageRequest {
        val pagina = (page ?: 1).coerceAtLeast(1)
        val tamano = (perPage ?: PER_PAGE_DEFAULT).coerceIn(1, PER_PAGE_MAX)
        val direccion = if (dir.equals("asc", ignoreCase = true)) Sort.Direction.ASC else Sort.Direction.DESC
        val campo = sort?.takeIf { it.isNotBlank() } ?: defaultSort
        return PageRequest.of(pagina - 1, tamano, Sort.by(direccion, campo))
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
