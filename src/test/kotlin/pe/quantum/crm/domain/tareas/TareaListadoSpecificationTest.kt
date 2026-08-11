package pe.quantum.crm.domain.tareas

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
import pe.quantum.crm.domain.contactos.ContactoService
import pe.quantum.crm.domain.empleados.EmpleadoService
import pe.quantum.crm.domain.empresas.EmpresaService
import pe.quantum.crm.domain.notificaciones.NotificacionService
import pe.quantum.crm.domain.oportunidades.OportunidadService
import pe.quantum.crm.domain.tareas.dto.TareaFiltros
import pe.quantum.crm.shared.exception.ValidacionException
import pe.quantum.crm.shared.security.UsuarioActual

/**
 * Tests de la Specification de `GET /tareas` contra el metamodelo REAL de JPA.
 *
 * El resto de tests del servicio mockean `tareaRepository.findAll(spec, page)` y
 * descartan la Specification sin evaluarla, asi que un `root.get("...")` con el
 * nombre de atributo mal escrito pasaria desapercibido hasta produccion, donde
 * seria un 500 de cada listado (exactamente lo que ya paso en contactos con
 * `tlf1` vs `tlf_1`; ver ContactoBusquedaSpecificationTest).
 *
 * Aqui el mock no descarta nada: coge la Specification y el PageRequest que le
 * llegan y los compila contra un metamodelo de Hibernate de verdad, que es quien
 * resuelve los nombres de atributo. No hace falta base de datos (ni Docker, roto
 * en local para Testcontainers): Hibernate arranca sin conexion si se le da el
 * dialecto, y la resolucion de atributos es puramente metamodelo.
 */
class TareaListadoSpecificationTest {
    private val tareaRepository = mockk<TareaRepository>()
    private val tareaResponsableRepository = mockk<TareaResponsableRepository>(relaxed = true)
    private val empresaService = mockk<EmpresaService>()
    private val oportunidadService = mockk<OportunidadService>()
    private val contactoService = mockk<ContactoService>()
    private val empleadoService = mockk<EmpleadoService>()
    private val notificacionService = mockk<NotificacionService>(relaxed = true)
    private val service =
        TareaServiceImpl(
            tareaRepository,
            tareaResponsableRepository,
            empresaService,
            oportunidadService,
            contactoService,
            empleadoService,
            notificacionService,
        )

    private val supervisor = UsuarioActual(id = 1, rol = "gerencia")
    private val vendedor = UsuarioActual(id = 9, rol = "vendedor")

    @Test
    fun `listar sin filtros arma una Specification que el metamodelo de JPA resuelve`() {
        assertThatCode { listar(TareaFiltros(), supervisor) }.doesNotThrowAnyException()
    }

    @Test
    fun `un rol supervisor sin filtro de asignado no restringe por dueno`() {
        val hql = listar(TareaFiltros(), supervisor)

        assertThat(hql).doesNotContain("idAsignado")
    }

    @Test
    fun `un rol supervisor puede filtrar por id_asignado`() {
        val hql = listar(TareaFiltros(idAsignado = 3), supervisor)

        assertThat(hql).contains("idAsignado")
    }

    @Test
    fun `la visibilidad restringida cruza dueno con la subconsulta de colaboradores`() {
        // vendedor/analista ven sus tareas Y aquellas donde son colaborador
        // (tabla tarea_responsables, migracion V31).
        val hql = listar(TareaFiltros(), vendedor)

        assertThat(hql).contains("idAsignado", "idTarea", "idEmpleado")
        assertThat(hql).containsIgnoringCase("TareaResponsable")
    }

    @Test
    fun `la visibilidad restringida ignora el filtro id_asignado de otro empleado`() {
        // Sin el `else if`, un vendedor podria listar la agenda de un companero.
        val hql = listar(TareaFiltros(idAsignado = 3), vendedor)

        assertThat(hql).containsIgnoringCase("TareaResponsable")
    }

    @Test
    fun `los filtros de empresa y oportunidad compilan contra el metamodelo`() {
        val hql = listar(TareaFiltros(idEmpresa = 10, idOportunidad = 50), supervisor)

        assertThat(hql).contains("idEmpresa", "idOportunidad")
    }

    @Test
    fun `estado_accion valido se traduce al enum de la entidad`() {
        val hql = listar(TareaFiltros(estadoAccion = "completada"), supervisor)

        assertThat(hql).contains("estadoAccion")
    }

    @Test
    fun `estado_accion desconocido se ignora en vez de reventar el listado`() {
        // `EstadoAccion.valueOf` lanzaria IllegalArgumentException: el runCatching
        // lo degrada a "sin filtro", no a un 500.
        val hql = listar(TareaFiltros(estadoAccion = "inventado"), supervisor)

        assertThat(hql).doesNotContain("estadoAccion")
    }

    @Test
    fun `solo_prospeccion filtra las tareas sin oportunidad`() {
        val hql = listar(TareaFiltros(soloProspeccion = true), supervisor)

        assertThat(hql).contains("idOportunidad")
        assertThat(hql).containsIgnoringCase("is null")
    }

    @Test
    fun `vencidas exige pendiente y fecha de ejecucion pasada`() {
        val hql = listar(TareaFiltros(vencidas = true), supervisor)

        assertThat(hql).contains("estadoAccion", "fechaEjecucion")
    }

    @Test
    fun `todos los filtros a la vez siguen compilando`() {
        assertThatCode {
            listar(
                TareaFiltros(
                    idEmpresa = 10,
                    idOportunidad = 50,
                    estadoAccion = "pendiente",
                    idAsignado = 3,
                    soloProspeccion = true,
                    vencidas = true,
                ),
                vendedor,
            )
        }.doesNotThrowAnyException()
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
            assertThatCode { listar(TareaFiltros(), supervisor, sort = campo) }
                .describedAs("el campo ordenable '%s' no existe en Tarea", campo)
                .doesNotThrowAnyException()
        }
    }

    @Test
    fun `un sort fuera de la allowlist es 400 y no un 500 del listado`() {
        val error =
            runCatching { listar(TareaFiltros(), supervisor, sort = "__nope__") }.exceptionOrNull()

        assertThat(error).isInstanceOf(ValidacionException::class.java)
        assertThat((error as ValidacionException).field).isEqualTo("sort")
    }

    @Test
    fun `un listado sin resultados devuelve la pagina vacia con su meta`() {
        every { tareaRepository.findAll(any<Specification<Tarea>>(), any<PageRequest>()) } returns
            PageImpl(emptyList(), PageRequest.of(0, 20), 0)

        val resultado = service.listar(TareaFiltros(), supervisor, null, null, null, null)

        assertThat(resultado.items).isEmpty()
        assertThat(resultado.meta.page).isEqualTo(1)
        assertThat(resultado.meta.perPage).isEqualTo(20)
        assertThat(resultado.meta.total).isZero()
        assertThat(resultado.meta.totalPages).isZero()
    }

    // ── privados ───────────────────────────────────────────────

    /**
     * Ejecuta `listar` y devuelve el HQL de la query que se habria lanzado. El
     * `answers` compila lo que el servicio construyo: si un atributo no existe,
     * revienta aqui igual que reventaria contra la base.
     */
    private fun listar(
        filtros: TareaFiltros,
        usuario: UsuarioActual,
        sort: String? = null,
    ): String {
        var hql = ""
        every { tareaRepository.findAll(any<Specification<Tarea>>(), any<PageRequest>()) } answers {
            hql = compilar(firstArg(), secondArg())
            PageImpl(emptyList(), PageRequest.of(0, 20), 0)
        }
        service.listar(filtros, usuario, null, null, sort, null)
        return hql
    }

    private fun compilar(
        spec: Specification<Tarea>,
        pageRequest: PageRequest,
    ): String {
        val cb = emf.criteriaBuilder
        val query = cb.createQuery(Tarea::class.java)
        val root = query.from(Tarea::class.java)
        // El select explicito es solo para poder renderizar el HQL de vuelta.
        query.select(root)
        pageRequest.sort.forEach { orden -> root.get<Any>(orden.property) }
        spec.toPredicate(root, query, cb)?.let { query.where(it) }
        return (query as SqmSelectStatement<*>).toHqlString()
    }

    private fun camposOrdenablesPermitidos(): List<String> {
        val error = runCatching { listar(TareaFiltros(), supervisor, sort = "__nope__") }.exceptionOrNull()
        return (error as ValidacionException).message.substringAfter("Campos permitidos: ").split(", ")
    }

    companion object {
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
            ).addAnnotatedClass(Tarea::class.java)
                .addAnnotatedClass(TareaResponsable::class.java)
                .addAnnotatedClass(TareaResponsableId::class.java)
                .buildMetadata()
                .buildSessionFactory()

        @JvmStatic
        @AfterAll
        fun cerrarEmf() {
            emf.close()
        }
    }
}
