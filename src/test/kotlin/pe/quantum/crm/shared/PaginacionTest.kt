package pe.quantum.crm.shared

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.data.domain.Sort
import pe.quantum.crm.shared.exception.ValidacionException

/**
 * El query param `sort` llegaba crudo a `Sort.by(...)`: cualquier valor que no
 * fuera una property de la entidad reventaba con `PropertyReferenceException` y el
 * request moria con 500. Ahora cada endpoint declara su allowlist.
 */
class PaginacionTest {
    private val campos = CamposOrdenables("id", "razonSocial", "createdAt")

    @Test
    fun `sin sort ordena por el campo por defecto de la allowlist`() {
        val request = Paginacion.pageRequest(page = null, perPage = null, sort = null, dir = null, campos = campos)

        assertThat(request.sort.getOrderFor("id")).isNotNull()
    }

    @Test
    fun `sort en blanco tambien cae al campo por defecto`() {
        val request = Paginacion.pageRequest(page = 1, perPage = 10, sort = "   ", dir = null, campos = campos)

        assertThat(request.sort.getOrderFor("id")).isNotNull()
    }

    @Test
    fun `acepta un campo de la allowlist escrito como la property JPA`() {
        val request = Paginacion.pageRequest(page = null, perPage = null, sort = "razonSocial", dir = null, campos = campos)

        assertThat(request.sort.getOrderFor("razonSocial")).isNotNull()
    }

    @Test
    fun `acepta el mismo campo en snake_case y lo traduce a la property JPA`() {
        val request = Paginacion.pageRequest(page = null, perPage = null, sort = "created_at", dir = null, campos = campos)

        assertThat(request.sort.getOrderFor("createdAt")).isNotNull()
        // No debe colarse el nombre snake_case: Spring Data no lo resolveria.
        assertThat(request.sort.getOrderFor("created_at")).isNull()
    }

    @Test
    fun `un campo fuera de la allowlist es un error de validacion, no un 500`() {
        assertThatThrownBy {
            Paginacion.pageRequest(page = null, perPage = null, sort = "cualquiercosa", dir = null, campos = campos)
        }.isInstanceOf(ValidacionException::class.java)
            .hasMessageContaining("cualquiercosa")
            .hasMessageContaining("id")
            .hasMessageContaining("razon_social")
            .hasMessageContaining("created_at")
    }

    @Test
    fun `el error de sort apunta al campo sort y usa el codigo VALIDACION`() {
        val error =
            runCatching {
                Paginacion.pageRequest(page = null, perPage = null, sort = "; drop table", dir = null, campos = campos)
            }.exceptionOrNull() as ValidacionException

        assertThat(error.code).isEqualTo("VALIDACION")
        assertThat(error.field).isEqualTo("sort")
        assertThat(error.status.value()).isEqualTo(400)
    }

    @Test
    fun `dir asc ordena ascendente y cualquier otro valor ordena descendente`() {
        val ascendente = Paginacion.pageRequest(page = null, perPage = null, sort = "id", dir = "ASC", campos = campos)
        val descendente = Paginacion.pageRequest(page = null, perPage = null, sort = "id", dir = null, campos = campos)

        assertThat(ascendente.sort.getOrderFor("id")?.direction).isEqualTo(Sort.Direction.ASC)
        assertThat(descendente.sort.getOrderFor("id")?.direction).isEqualTo(Sort.Direction.DESC)
    }

    @Test
    fun `page y per_page se acotan al rango del contrato`() {
        val request = Paginacion.pageRequest(page = 0, perPage = 5_000, sort = null, dir = null, campos = campos)

        assertThat(request.pageNumber).isEqualTo(0)
        assertThat(request.pageSize).isEqualTo(Paginacion.PER_PAGE_MAX)
    }

    @Test
    fun `los nombres publicos de la allowlist salen en snake_case`() {
        assertThat(campos.nombresPublicos).containsExactly("id", "razon_social", "created_at")
    }

    /**
     * `Paginacion.meta` fabrica el `total_pages` de toda la API paginada. Un
     * off-by-one aqui rompe la paginacion de seis modulos a la vez y en silencio: el
     * cliente deja de pedir la ultima pagina, o pide una que no existe.
     */
    @Test
    fun `sin resultados no hay ninguna pagina`() {
        assertThat(Paginacion.meta(page = 1, perPage = 20, total = 0).totalPages).isZero()
    }

    @Test
    fun `un solo resultado ocupa una pagina`() {
        assertThat(Paginacion.meta(page = 1, perPage = 20, total = 1).totalPages).isEqualTo(1)
    }

    /** Borde exacto: 20 de 20 caben en una pagina, no en dos. */
    @Test
    fun `un total que llena la pagina justo no abre una pagina de mas`() {
        assertThat(Paginacion.meta(page = 1, perPage = 20, total = 20).totalPages).isEqualTo(1)
    }

    /** Borde exacto por el otro lado: uno mas obliga a una segunda pagina. */
    @Test
    fun `un resultado por encima del tamano de pagina abre la segunda`() {
        assertThat(Paginacion.meta(page = 1, perPage = 20, total = 21).totalPages).isEqualTo(2)
    }

    @Test
    fun `el maximo de per_page tambien reparte bien`() {
        assertThat(Paginacion.meta(page = 1, perPage = 100, total = 100).totalPages).isEqualTo(1)
        assertThat(Paginacion.meta(page = 1, perPage = 100, total = 101).totalPages).isEqualTo(2)
    }

    @Test
    fun `page per_page y total se devuelven tal cual se recibieron`() {
        val meta = Paginacion.meta(page = 3, perPage = 25, total = 57)

        assertThat(meta.page).isEqualTo(3)
        assertThat(meta.perPage).isEqualTo(25)
        assertThat(meta.total).isEqualTo(57)
        assertThat(meta.totalPages).isEqualTo(3)
    }
}
