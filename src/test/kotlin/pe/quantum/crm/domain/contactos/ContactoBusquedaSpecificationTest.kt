package pe.quantum.crm.domain.contactos

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
import pe.quantum.crm.domain.empresas.EmpresaService
import pe.quantum.crm.domain.empresas.dto.EmpresaVinculo
import pe.quantum.crm.shared.exception.ValidacionException
import pe.quantum.crm.shared.security.UsuarioActual

/**
 * Tests de la Specification de `GET /contactos?q=` contra el metamodelo REAL de JPA.
 *
 * El resto de tests del servicio mockean `contactoRepository.findAll(spec, page)` y
 * descartan la Specification sin evaluarla: por eso paso a produccion un
 * `root.get("tlf1")` cuando el atributo de la entidad se llama `tlf_1`, y toda
 * busqueda con `q` no vacio respondia 500 (los tres `like` van dentro de un mismo
 * `cb.or`, asi que tambien moria la busqueda por nombre).
 *
 * Aqui el mock del repositorio no descarta nada: coge la Specification y el
 * PageRequest que le llegan y los compila contra un metamodelo de Hibernate de
 * verdad, que es quien resuelve los nombres de atributo. No hace falta base de
 * datos (ni Docker, roto en local para Testcontainers): Hibernate arranca sin
 * conexion si se le da el dialecto, y la resolucion de atributos es puramente
 * metamodelo.
 */
class ContactoBusquedaSpecificationTest {
    private val contactoRepository = mockk<ContactoRepository>()
    private val empresaContactoRepository = mockk<EmpresaContactoRepository>()
    private val empresaService = mockk<EmpresaService>()
    private val service = ContactoServiceImpl(contactoRepository, empresaContactoRepository, empresaService)

    private val usuario = UsuarioActual(id = 1, rol = "admin")

    @Test
    fun `buscar con q arma una Specification que el metamodelo de JPA resuelve`() {
        assertThatCode { buscar(q = "964415122") }.doesNotThrowAnyException()
    }

    @Test
    fun `buscar con q filtra por nombre completo y por los dos telefonos`() {
        val hql = buscar(q = "964415122")

        assertThat(hql).contains("nombres", "apellidos", "tlf_1", "tlf_2")
    }

    @Test
    fun `buscar con id_empresa restringe por los ids de los contactos vinculados`() {
        val hql = buscar(q = null, idEmpresa = 10, contactosDeLaEmpresa = listOf(1L, 2L))

        assertThat(hql).containsIgnoringCase("where").containsIgnoringCase(" in ")
    }

    /**
     * Una empresa sin contactos debe devolver la lista vacia, NUNCA el listado
     * completo: si el filtro por ids desapareciera al quedarse vacio, un vendedor
     * veria todos los contactos del CRM pidiendo `?id_empresa=` de una empresa
     * recien creada. Se compara contra la busqueda sin filtro para no depender de
     * como Hibernate escriba la disyuncion vacia.
     */
    @Test
    fun `buscar con una empresa sin contactos no cae en el listado completo`() {
        val sinFiltro = buscar(q = null)
        val vacio = buscar(q = null, idEmpresa = 10, contactosDeLaEmpresa = emptyList())

        assertThat(vacio).isNotEqualTo(sinFiltro)
        assertThat(vacio).containsIgnoringCase("where")
    }

    @Test
    fun `buscar combina el filtro por empresa con el texto en un solo where`() {
        val hql = buscar(q = "Hugo", idEmpresa = 10, contactosDeLaEmpresa = listOf(1L))

        assertThat(hql).containsIgnoringCase(" in ").contains("nombres", "apellidos", "tlf_1", "tlf_2")
    }

    @Test
    fun `buscar con q en blanco no añade ningun filtro de texto`() {
        val enBlanco = buscar(q = "   ")

        assertThat(enBlanco).isEqualTo(buscar(q = null))
    }

    /**
     * El `sort` no pasa por la Specification: Spring Data lo resuelve como property
     * path de la entidad al ejecutar la query, asi que un campo de la allowlist que
     * no exista seria el mismo 500 por otra puerta. Se leen los campos permitidos
     * del propio mensaje de error para no duplicar la lista aqui.
     */
    @Test
    fun `todos los campos ordenables de la allowlist existen en la entidad`() {
        val permitidos = camposOrdenablesPermitidos()

        assertThat(permitidos).isNotEmpty()
        permitidos.forEach { campo ->
            assertThatCode { buscar(q = null, sort = campo) }
                .describedAs("el campo ordenable '%s' no existe en Contacto", campo)
                .doesNotThrowAnyException()
        }
    }

    // ── privados ───────────────────────────────────────────────

    /**
     * Ejecuta `buscar` y devuelve el HQL de la query que se habria lanzado. El
     * `answers` compila lo que el servicio construyo: si un atributo no existe,
     * revienta aqui igual que reventaria contra la base.
     */
    private fun buscar(
        q: String?,
        sort: String? = null,
        idEmpresa: Long? = null,
        contactosDeLaEmpresa: List<Long> = emptyList(),
    ): String {
        var hql = ""
        idEmpresa?.let { id ->
            every { empresaService.vinculoVisible(id, usuario) } returns
                EmpresaVinculo(id = id, razonSocial = "Transp. Sta. Anita S.A.", idVendedor = null, estadoCartera = "prospeccion")
            every { empresaContactoRepository.findByIdIdEmpresa(id) } returns
                contactosDeLaEmpresa.map { EmpresaContacto(id = EmpresaContactoId(idEmpresa = id, idContacto = it)) }
        }
        every { contactoRepository.findAll(any<Specification<Contacto>>(), any<PageRequest>()) } answers {
            hql = compilar(firstArg(), secondArg())
            PageImpl(emptyList(), PageRequest.of(0, 20), 0)
        }
        service.buscar(q = q, idEmpresa = idEmpresa, usuario = usuario, page = null, perPage = null, sort = sort, dir = null)
        return hql
    }

    private fun compilar(
        spec: Specification<Contacto>,
        pageRequest: PageRequest,
    ): String {
        val cb = emf.criteriaBuilder
        val query = cb.createQuery(Contacto::class.java)
        val root = query.from(Contacto::class.java)
        // El select explicito es solo para poder renderizar el HQL de vuelta.
        query.select(root)
        pageRequest.sort.forEach { orden -> root.get<Any>(orden.property) }
        spec.toPredicate(root, query, cb)?.let { query.where(it) }
        // El alias del root lo genera Hibernate con un numero distinto en cada
        // compilacion; se normaliza para poder comparar dos HQL entre si.
        return (query as SqmSelectStatement<*>).toHqlString().replace(ALIAS_GENERADO, "c")
    }

    private fun camposOrdenablesPermitidos(): List<String> {
        val error =
            runCatching {
                service.buscar(q = null, idEmpresa = null, usuario = usuario, page = null, perPage = null, sort = "__nope__", dir = null)
            }.exceptionOrNull()
        return (error as ValidacionException).message.substringAfter("Campos permitidos: ").split(", ")
    }

    companion object {
        private val ALIAS_GENERADO = Regex("alias_\\d+")

        /**
         * SessionFactory solo-metamodelo: con el dialecto fijado y la lectura de
         * metadata JDBC deshabilitada, Hibernate arranca sin DataSource. Basta para
         * resolver atributos en las Criteria, que es lo unico que se ejercita.
         */
        private val emf: EntityManagerFactory =
            MetadataSources(
                StandardServiceRegistryBuilder()
                    .applySetting("hibernate.dialect", "org.hibernate.dialect.PostgreSQLDialect")
                    .applySetting("hibernate.boot.allow_jdbc_metadata_access", "false")
                    .applySetting("hibernate.temp.use_jdbc_metadata_defaults", "false")
                    .build(),
            ).addAnnotatedClass(Contacto::class.java)
                .buildMetadata()
                .buildSessionFactory()

        @JvmStatic
        @AfterAll
        fun cerrarEmf() {
            emf.close()
        }
    }
}
