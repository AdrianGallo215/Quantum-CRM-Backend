package pe.quantum.crm.domain.solicitudes

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
import pe.quantum.crm.domain.empresas.EmpresaService
import pe.quantum.crm.domain.notificaciones.NotificacionService
import pe.quantum.crm.domain.oportunidades.OportunidadService
import pe.quantum.crm.domain.solicitudes.dto.SolicitudFiltros
import pe.quantum.crm.shared.exception.ValidacionException
import pe.quantum.crm.shared.security.UsuarioActual

/**
 * Tests de la Specification de `GET /solicitudes` contra el metamodelo REAL de
 * JPA (mismo enfoque que ContactoBusquedaSpecificationTest). Aqui la Specification
 * NO se descarta: se compila contra un metamodelo de Hibernate de verdad, que es
 * quien resuelve los nombres de atributo, sin base de datos ni Docker.
 *
 * Ademas de detectar atributos mal escritos, fija la bandeja de cada rol: el
 * alcance de la solicitud es el mismo control de acceso que `visible`, pero
 * expresado como filtro del listado.
 */
class SolicitudBusquedaSpecificationTest {
    private val solicitudRepository = mockk<SolicitudRepository>()
    private val oportunidadService = mockk<OportunidadService>()
    private val empresaService = mockk<EmpresaService>()
    private val empleadoService = mockk<EmpleadoService>()
    private val notificacionService = mockk<NotificacionService>(relaxed = true)
    private val service =
        SolicitudServiceImpl(solicitudRepository, oportunidadService, empresaService, empleadoService, notificacionService)

    private val admin = UsuarioActual(id = 1, rol = "admin")
    private val gerencia = UsuarioActual(id = 2, rol = "gerencia")
    private val jdv = UsuarioActual(id = 3, rol = "jdv")
    private val vendedor = UsuarioActual(id = 5, rol = "vendedor")
    private val analista = UsuarioActual(id = 6, rol = "analista")

    @Test
    fun `todos los filtros a la vez arman una Specification que el metamodelo resuelve`() {
        assertThatCode {
            listar(SolicitudFiltros(estado = "pendiente", tipo = "descuento", mias = true), jdv)
        }.doesNotThrowAnyException()
    }

    @Test
    fun `vendedor y analista solo ven las solicitudes que ellos enviaron`() {
        assertThat(listar(SolicitudFiltros(), vendedor)).contains("idSolicitante")
        assertThat(listar(SolicitudFiltros(), analista)).contains("idSolicitante")
    }

    @Test
    fun `mias fuerza el filtro por solicitante incluso para gerencia`() {
        val hql = listar(SolicitudFiltros(mias = true), gerencia)

        assertThat(hql).contains("idSolicitante")
        assertThat(hql).doesNotContain("rolAprobador")
    }

    @Test
    fun `gerencia ve su bandeja por rol aprobador`() {
        val hql = listar(SolicitudFiltros(), gerencia)

        assertThat(hql).contains("rolAprobador")
        assertThat(hql).doesNotContain("idSolicitante")
    }

    @Test
    fun `el jdv ve su bandeja mas las solicitudes que el mismo envio`() {
        val hql = listar(SolicitudFiltros(), jdv)

        assertThat(hql).contains("rolAprobador", "idSolicitante")
    }

    @Test
    fun `admin no lleva predicado de alcance`() {
        val hql = listar(SolicitudFiltros(), admin)

        assertThat(hql).doesNotContain("rolAprobador")
        assertThat(hql).doesNotContain("idSolicitante")
    }

    @Test
    fun `estado valido filtra y uno desconocido se ignora sin reventar`() {
        assertThat(listar(SolicitudFiltros(estado = "aprobada"), admin)).contains("estado")
        assertThat(listar(SolicitudFiltros(estado = "__nope__"), admin)).doesNotContain("estado")
    }

    @Test
    fun `tipo valido filtra y uno desconocido se ignora sin reventar`() {
        assertThat(listar(SolicitudFiltros(tipo = "reasignacion_cliente"), admin)).contains("tipo")
        assertThat(listar(SolicitudFiltros(tipo = "__nope__"), admin)).doesNotContain("tipo")
    }

    @Test
    fun `todos los campos ordenables de la allowlist existen en la entidad`() {
        val permitidos = camposOrdenablesPermitidos()

        assertThat(permitidos).isNotEmpty()
        permitidos.forEach { campo ->
            assertThatCode { listar(SolicitudFiltros(), admin, sort = campo) }
                .describedAs("el campo ordenable '%s' no existe en Solicitud", campo)
                .doesNotThrowAnyException()
        }
    }

    // ── privados ───────────────────────────────────────────────

    private fun listar(
        filtros: SolicitudFiltros,
        usuario: UsuarioActual,
        sort: String? = null,
    ): String {
        var hql = ""
        every { solicitudRepository.findAll(any<Specification<Solicitud>>(), any<PageRequest>()) } answers {
            hql = compilar(firstArg(), secondArg())
            PageImpl(emptyList(), PageRequest.of(0, 20), 0)
        }
        service.listar(filtros, usuario, page = null, perPage = null, sort = sort, dir = null)
        return hql
    }

    private fun compilar(
        spec: Specification<Solicitud>,
        pageRequest: PageRequest,
    ): String {
        val cb = emf.criteriaBuilder
        val query = cb.createQuery(Solicitud::class.java)
        val root = query.from(Solicitud::class.java)
        query.select(root)
        pageRequest.sort.forEach { orden -> root.get<Any>(orden.property) }
        spec.toPredicate(root, query, cb)?.let { query.where(it) }
        return (query as SqmSelectStatement<*>).toHqlString()
    }

    private fun camposOrdenablesPermitidos(): List<String> {
        val error =
            runCatching {
                service.listar(SolicitudFiltros(), admin, page = null, perPage = null, sort = "__nope__", dir = null)
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
            ).addAnnotatedClass(Solicitud::class.java)
                .buildMetadata()
                .buildSessionFactory()

        @JvmStatic
        @AfterAll
        fun cerrarEmf() {
            emf.close()
        }
    }
}
