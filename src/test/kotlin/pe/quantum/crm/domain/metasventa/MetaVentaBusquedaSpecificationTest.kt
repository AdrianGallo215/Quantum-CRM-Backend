package pe.quantum.crm.domain.metasventa

import io.mockk.every
import io.mockk.mockk
import jakarta.persistence.EntityManagerFactory
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatCode
import org.hibernate.boot.MetadataSources
import org.hibernate.boot.registry.StandardServiceRegistryBuilder
import org.hibernate.query.sqm.tree.select.SqmSelectStatement
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.springframework.data.domain.PageImpl
import org.springframework.data.domain.PageRequest
import org.springframework.data.jpa.domain.Specification
import pe.quantum.crm.domain.empleados.EmpleadoService
import pe.quantum.crm.domain.metasventa.dto.MetaVentaFiltros
import pe.quantum.crm.domain.notificaciones.NotificacionService
import pe.quantum.crm.shared.exception.ValidacionException
import pe.quantum.crm.shared.security.UsuarioActual

/**
 * Tests de la Specification de `GET /metas-venta` contra el metamodelo REAL de
 * JPA (mismo enfoque que ContactoBusquedaSpecificationTest): el mock del
 * repositorio compila la Specification en vez de descartarla, asi que un nombre
 * de atributo mal escrito falla aqui y no en produccion con un 500.
 */
class MetaVentaBusquedaSpecificationTest {
    private val metaVentaRepository = mockk<MetaVentaRepository>()
    private val empleadoService = mockk<EmpleadoService>()
    private val notificacionService = mockk<NotificacionService>(relaxed = true)
    private val service = MetaVentaServiceImpl(metaVentaRepository, empleadoService, notificacionService)

    private val gerencia = UsuarioActual(id = 1, rol = "gerencia")
    private val jdv = UsuarioActual(id = 2, rol = "jdv")
    private val vendedor = UsuarioActual(id = 5, rol = "vendedor")
    private val analista = UsuarioActual(id = 6, rol = "analista")
    private val otro = UsuarioActual(id = 9, rol = "otro")

    @Test
    fun `todos los filtros a la vez arman una Specification que el metamodelo resuelve`() {
        assertThatCode {
            listar(MetaVentaFiltros(idEmpleado = 5, anio = 2027, estado = "aprobada"), gerencia)
        }.doesNotThrowAnyException()
    }

    @Test
    fun `vendedor y analista solo ven su propia meta`() {
        assertThat(listar(MetaVentaFiltros(), vendedor)).contains("idEmpleado")
        assertThat(listar(MetaVentaFiltros(), analista)).contains("idEmpleado")
    }

    @Test
    fun `el rol de apoyo otro solo ve su propia meta`() {
        assertThat(listar(MetaVentaFiltros(), otro)).contains("idEmpleado")
    }

    @Test
    fun `gerencia y jdv ven todas las metas sin predicado de alcance`() {
        assertThat(listar(MetaVentaFiltros(), gerencia)).doesNotContain("idEmpleado")
        assertThat(listar(MetaVentaFiltros(), jdv)).doesNotContain("idEmpleado")
    }

    @Test
    fun `id_empleado y anio filtran cuando vienen`() {
        assertThat(listar(MetaVentaFiltros(idEmpleado = 5), gerencia)).contains("idEmpleado")
        assertThat(listar(MetaVentaFiltros(anio = 2027), gerencia)).contains("anio")
    }

    @Test
    fun `estado valido filtra y uno desconocido se ignora sin reventar`() {
        assertThat(listar(MetaVentaFiltros(estado = "propuesta"), gerencia)).contains("estado")
        assertThat(listar(MetaVentaFiltros(estado = "__nope__"), gerencia)).doesNotContain("estado")
    }

    @Test
    fun `todos los campos ordenables de la allowlist existen en la entidad`() {
        val permitidos = camposOrdenablesPermitidos()

        assertThat(permitidos).isNotEmpty()
        permitidos.forEach { campo ->
            assertThatCode { listar(MetaVentaFiltros(), gerencia, sort = campo) }
                .describedAs("el campo ordenable '%s' no existe en MetaVenta", campo)
                .doesNotThrowAnyException()
        }
    }

    // ── privados ───────────────────────────────────────────────

    private fun listar(
        filtros: MetaVentaFiltros,
        usuario: UsuarioActual,
        sort: String? = null,
    ): String {
        var hql = ""
        every { metaVentaRepository.findAll(any<Specification<MetaVenta>>(), any<PageRequest>()) } answers {
            hql = compilar(firstArg(), secondArg())
            PageImpl(emptyList(), PageRequest.of(0, 20), 0)
        }
        service.listar(filtros, usuario, page = null, perPage = null, sort = sort, dir = null)
        return hql
    }

    private fun compilar(
        spec: Specification<MetaVenta>,
        pageRequest: PageRequest,
    ): String {
        val cb = emf.criteriaBuilder
        val query = cb.createQuery(MetaVenta::class.java)
        val root = query.from(MetaVenta::class.java)
        query.select(root)
        pageRequest.sort.forEach { orden -> root.get<Any>(orden.property) }
        spec.toPredicate(root, query, cb)?.let { query.where(it) }
        return (query as SqmSelectStatement<*>).toHqlString()
    }

    private fun camposOrdenablesPermitidos(): List<String> {
        val error =
            runCatching {
                service.listar(MetaVentaFiltros(), gerencia, page = null, perPage = null, sort = "__nope__", dir = null)
            }.exceptionOrNull()
        return (error as ValidacionException).message.substringAfter("Campos permitidos: ").split(", ")
    }

    companion object {
        private val emf: EntityManagerFactory =
            MetadataSources(
                StandardServiceRegistryBuilder()
                    .applySetting("hibernate.dialect", "org.hibernate.dialect.PostgreSQLDialect")
                    .applySetting("hibernate.boot.allow_jdbc_metadata_access", "false")
                    .applySetting("hibernate.temp.use_jdbc_metadata_defaults", "false")
                    .build(),
            ).addAnnotatedClass(MetaVenta::class.java)
                .buildMetadata()
                .buildSessionFactory()

        @JvmStatic
        @AfterAll
        fun cerrarEmf() {
            emf.close()
        }
    }
}
