package pe.quantum.crm.domain.empresas

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
import org.springframework.context.ApplicationEventPublisher
import org.springframework.data.domain.PageImpl
import org.springframework.data.domain.PageRequest
import org.springframework.data.jpa.domain.Specification
import org.springframework.transaction.support.TransactionTemplate
import pe.quantum.crm.domain.contactos.ContactoService
import pe.quantum.crm.domain.empleados.EmpleadoService
import pe.quantum.crm.domain.empresas.dto.EmpresaFiltros
import pe.quantum.crm.domain.notificaciones.NotificacionService
import pe.quantum.crm.integracion.drive.DriveStorageService
import pe.quantum.crm.shared.enums.EstadoCartera
import pe.quantum.crm.shared.enums.Segmento
import pe.quantum.crm.shared.exception.ValidacionException
import pe.quantum.crm.shared.security.UsuarioActual

/**
 * Tests de la Specification de `GET /empresas` contra el metamodelo REAL de JPA.
 *
 * Mismo enfoque que ContactoBusquedaSpecificationTest: el resto de tests del
 * servicio mockean `empresaRepository.findAll(spec, page)` y descartan la
 * Specification sin evaluarla, asi que un `root.get("razonSocial")` mal escrito
 * pasaria verde en unitarios y seria un 500 en cada listado. Aqui el mock del
 * repositorio coge la Specification y el PageRequest y los compila contra un
 * metamodelo de Hibernate de verdad, sin base de datos ni Docker.
 */
class EmpresaBusquedaSpecificationTest {
    private val empresaRepository = mockk<EmpresaRepository>()
    private val empleadoService = mockk<EmpleadoService>()
    private val notificacionService = mockk<NotificacionService>(relaxed = true)
    private val eventPublisher = mockk<ApplicationEventPublisher>(relaxed = true)
    private val driveStorageService = mockk<DriveStorageService>()
    private val contactoService = mockk<ContactoService>()
    private val transactionTemplate = mockk<TransactionTemplate>()
    private val service =
        EmpresaServiceImpl(
            empresaRepository,
            empleadoService,
            notificacionService,
            eventPublisher,
            driveStorageService,
            contactoService,
            transactionTemplate,
        )

    private val gerencia = UsuarioActual(id = 1, rol = "gerencia")
    private val jdv = UsuarioActual(id = 3, rol = "jdv")
    private val vendedor = UsuarioActual(id = 7, rol = "vendedor")

    init {
        every { empleadoService.resumenPorIds(any()) } returns emptyMap()
    }

    @Test
    fun `todos los filtros a la vez arman una Specification que el metamodelo resuelve`() {
        assertThatCode {
            listar(
                EmpresaFiltros(
                    q = "transportes",
                    estadoCartera = EstadoCartera.prospeccion.name,
                    idVendedor = 8,
                    segmento = Segmento.interprovincial,
                    distrito = "Ate",
                    carteraMaestra = true,
                ),
                gerencia,
            )
        }.doesNotThrowAnyException()
    }

    @Test
    fun `un vendedor solo ve sus empresas y nunca las de la cartera maestra`() {
        val hql = listar(EmpresaFiltros(), vendedor)

        assertThat(hql).contains("enCarteraMaestra", "idVendedor")
    }

    @Test
    fun `el jdv tampoco alcanza la cartera maestra pese a ver todo el equipo`() {
        val hql = listar(EmpresaFiltros(), jdv)

        // El jdv no tiene visibilidad restringida, asi que no filtra por vendedor,
        // pero la cartera maestra es exclusiva de gerencia y admin.
        assertThat(hql).contains("enCarteraMaestra")
        assertThat(hql).doesNotContain("idVendedor")
    }

    @Test
    fun `gerencia sin filtro de cartera maestra ve reservadas y no reservadas`() {
        val hql = listar(EmpresaFiltros(), gerencia)

        assertThat(hql).doesNotContain("enCarteraMaestra")
    }

    @Test
    fun `gerencia con cartera_maestra filtra por ese flag`() {
        val hql = listar(EmpresaFiltros(carteraMaestra = true), gerencia)

        assertThat(hql).contains("enCarteraMaestra")
    }

    @Test
    fun `el filtro id_vendedor solo lo aplican los roles que ven todo`() {
        assertThat(listar(EmpresaFiltros(idVendedor = 8), gerencia)).contains("idVendedor")
        // Para un vendedor su propia visibilidad manda: el id ajeno del filtro se ignora.
        assertThat(listar(EmpresaFiltros(idVendedor = 8), vendedor)).contains("idVendedor")
    }

    @Test
    fun `q busca por razon social en minusculas y por prefijo de ruc`() {
        val hql = listar(EmpresaFiltros(q = "transportes"), gerencia)

        assertThat(hql).contains("razonSocial", "ruc")
    }

    @Test
    fun `q en blanco no agrega predicado de busqueda`() {
        val hql = listar(EmpresaFiltros(q = "   "), gerencia)

        assertThat(hql).doesNotContain("razonSocial")
    }

    @Test
    fun `estado_cartera valido filtra y uno desconocido se ignora sin reventar`() {
        assertThat(listar(EmpresaFiltros(estadoCartera = "cliente"), gerencia)).contains("estadoCartera")
        assertThat(listar(EmpresaFiltros(estadoCartera = "__nope__"), gerencia)).doesNotContain("estadoCartera")
    }

    @Test
    fun `distrito compara en minusculas y en blanco no filtra`() {
        assertThat(listar(EmpresaFiltros(distrito = "Ate"), gerencia)).contains("distrito")
        assertThat(listar(EmpresaFiltros(distrito = " "), gerencia)).doesNotContain("distrito")
    }

    @Test
    fun `segmento hace join a la coleccion de segmentos`() {
        val hql = listar(EmpresaFiltros(segmento = Segmento.urbano), gerencia)

        assertThat(hql).contains("segmentos")
    }

    /**
     * El `sort` no pasa por la Specification: Spring Data lo resuelve como property
     * path de la entidad al ejecutar la query, asi que un campo de la allowlist que
     * no exista seria un 500 por otra puerta.
     */
    @Test
    fun `todos los campos ordenables de la allowlist existen en la entidad`() {
        val permitidos = camposOrdenablesPermitidos()

        assertThat(permitidos).isNotEmpty()
        permitidos.forEach { campo ->
            assertThatCode { listar(EmpresaFiltros(), gerencia, sort = campo) }
                .describedAs("el campo ordenable '%s' no existe en Empresa", campo)
                .doesNotThrowAnyException()
        }
    }

    // ── privados ───────────────────────────────────────────────

    /** Ejecuta `listar` y devuelve el HQL de la query que se habria lanzado. */
    private fun listar(
        filtros: EmpresaFiltros,
        usuario: UsuarioActual,
        sort: String? = null,
    ): String {
        var hql = ""
        every { empresaRepository.findAll(any<Specification<Empresa>>(), any<PageRequest>()) } answers {
            hql = compilar(firstArg(), secondArg())
            PageImpl(emptyList(), PageRequest.of(0, 20), 0)
        }
        service.listar(filtros, usuario, page = null, perPage = null, sort = sort, dir = null)
        return hql
    }

    private fun compilar(
        spec: Specification<Empresa>,
        pageRequest: PageRequest,
    ): String {
        val cb = emf.criteriaBuilder
        val query = cb.createQuery(Empresa::class.java)
        val root = query.from(Empresa::class.java)
        query.select(root)
        pageRequest.sort.forEach { orden -> root.get<Any>(orden.property) }
        spec.toPredicate(root, query, cb)?.let { query.where(it) }
        return (query as SqmSelectStatement<*>).toHqlString()
    }

    private fun camposOrdenablesPermitidos(): List<String> {
        val error =
            runCatching {
                service.listar(EmpresaFiltros(), gerencia, page = null, perPage = null, sort = "__nope__", dir = null)
            }.exceptionOrNull()
        return (error as ValidacionException).message.substringAfter("Campos permitidos: ").split(", ")
    }

    companion object {
        /** SessionFactory solo-metamodelo: arranca sin DataSource con el dialecto fijado. */
        private val emf: EntityManagerFactory =
            MetadataSources(
                StandardServiceRegistryBuilder()
                    .applySetting("hibernate.dialect", "org.hibernate.dialect.PostgreSQLDialect")
                    .applySetting("hibernate.boot.allow_jdbc_metadata_access", "false")
                    .applySetting("hibernate.temp.use_jdbc_metadata_defaults", "false")
                    .build(),
            ).addAnnotatedClass(Empresa::class.java)
                .buildMetadata()
                .buildSessionFactory()

        @JvmStatic
        @AfterAll
        fun cerrarEmf() {
            emf.close()
        }
    }
}
