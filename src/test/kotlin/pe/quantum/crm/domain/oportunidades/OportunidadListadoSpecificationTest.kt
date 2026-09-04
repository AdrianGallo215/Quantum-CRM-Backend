package pe.quantum.crm.domain.oportunidades

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
import org.junit.jupiter.api.assertThrows
import org.springframework.data.domain.PageImpl
import org.springframework.data.domain.PageRequest
import org.springframework.data.jpa.domain.Specification
import pe.quantum.crm.domain.contactos.ContactoService
import pe.quantum.crm.domain.empleados.EmpleadoService
import pe.quantum.crm.domain.empresas.EmpresaService
import pe.quantum.crm.domain.financiadoras.FinanciadoraService
import pe.quantum.crm.domain.modelos.ModeloService
import pe.quantum.crm.domain.notificaciones.NotificacionService
import pe.quantum.crm.domain.oportunidades.dto.OportunidadFiltros
import pe.quantum.crm.domain.tareas.TareaService
import pe.quantum.crm.integracion.drive.DriveStorageService
import pe.quantum.crm.shared.exception.ValidacionException
import pe.quantum.crm.shared.security.UsuarioActual
import java.math.BigDecimal

/**
 * Tests de la Specification de `GET /oportunidades` contra el metamodelo REAL de JPA.
 *
 * Mismo enfoque que `ContactoBusquedaSpecificationTest`: el resto de tests del
 * servicio mockean `oportunidadRepository.findAll(spec, page)` y tiran la
 * Specification sin evaluarla, asi que un `root.get("idVendedor")` mal escrito
 * pasaria verde aqui y devolveria 500 en produccion (ya paso en contactos con
 * `tlf1` vs `tlf_1`).
 *
 * Aqui el mock del repositorio no descarta nada: coge la Specification y el
 * PageRequest que le llegan y los compila contra un metamodelo de Hibernate de
 * verdad, que es quien resuelve los nombres de atributo. No hace falta base de
 * datos (ni Docker, roto en local para Testcontainers): Hibernate arranca sin
 * conexion si se le da el dialecto.
 */
class OportunidadListadoSpecificationTest {
    private val oportunidadRepository = mockk<OportunidadRepository>()
    private val logRepository = mockk<OportunidadEstadoLogRepository>()
    private val contactoOportunidadRepository = mockk<OportunidadContactoRepository>()
    private val estadoCarteraService = mockk<EstadoCarteraService>()
    private val empresaService = mockk<EmpresaService>()
    private val empleadoService = mockk<EmpleadoService>()
    private val financiadoraService = mockk<FinanciadoraService>()
    private val modeloService = mockk<ModeloService>()
    private val contactoService = mockk<ContactoService>()
    private val consultas = mockk<OportunidadConsultas>()
    private val notificacionService = mockk<NotificacionService>(relaxed = true)
    private val driveStorageService = mockk<DriveStorageService>(relaxed = true)
    private val tareaService = mockk<TareaService>()
    private val oportunidadItemService = mockk<OportunidadItemService>()
    private val service =
        OportunidadServiceImpl(
            oportunidadRepository,
            logRepository,
            contactoOportunidadRepository,
            estadoCarteraService,
            empresaService,
            empleadoService,
            financiadoraService,
            modeloService,
            contactoService,
            consultas,
            notificacionService,
            driveStorageService,
            OportunidadVisibilidad(tareaService),
            oportunidadItemService,
        )

    private val admin = UsuarioActual(id = 1, rol = "admin")
    private val vendedor = UsuarioActual(id = 7, rol = "vendedor")
    private val analista = UsuarioActual(id = 7, rol = "analista")

    /** HQL efectivo de la query y numero de predicados que la Specification apilo. */
    private data class Compilada(
        val hql: String,
        val predicados: Int,
    )

    @Test
    fun `el listado por defecto compila contra el metamodelo real y excluye las cerradas`() {
        val compilada = listar(OportunidadFiltros(), admin)

        assertThat(compilada.predicados).isEqualTo(1)
        assertThat(compilada.hql).contains("estado")
    }

    @Test
    fun `un vendedor solo ve sus propias oportunidades`() {
        val compilada = listar(OportunidadFiltros(), vendedor)

        assertThat(compilada.predicados).isEqualTo(2)
        assertThat(compilada.hql).contains("idVendedor")
    }

    /** El filtro de visibilidad manda: un vendedor no puede espiar la cartera de otro. */
    @Test
    fun `un vendedor que pide id_vendedor ajeno sigue viendo solo el suyo`() {
        val compilada = listar(OportunidadFiltros(idVendedor = 99), vendedor)

        assertThat(compilada.predicados).isEqualTo(2)
        assertThat(compilada.hql).contains("idVendedor")
    }

    @Test
    fun `un supervisor si puede filtrar por id_vendedor`() {
        val compilada = listar(OportunidadFiltros(idVendedor = 99), admin)

        assertThat(compilada.predicados).isEqualTo(2)
        assertThat(compilada.hql).contains("idVendedor")
    }

    /**
     * El hueco de seguridad que este plan viene a cerrar: si `especificacion`
     * cambiara `cb.disjunction()` por `cb.conjunction()` (o el `if` desapareciera),
     * este test tiene que fallar porque el HQL compilado dejaria de forzar el
     * "siempre falso". Se compila contra el metamodelo real (no contra un mock de
     * `findAll`) precisamente para que la rama de la lambda se evalue de verdad.
     */
    @Test
    fun `un rol de apoyo sin colaboraciones compila una Specification siempre falsa`() {
        every { tareaService.idsOportunidadesDondeColabora(7) } returns emptySet()

        val compilada = listar(OportunidadFiltros(), analista)

        assertThat(compilada.hql).contains("1 <> 1")
    }

    /**
     * Contraparte: con colaboraciones, la Specification compilada debe traer un
     * `IN` sobre esos ids exactos, no un filtro de `idVendedor` (el rol de apoyo no
     * tiene cartera propia).
     */
    @Test
    fun `un rol de apoyo con colaboraciones compila un IN sobre esos ids`() {
        every { tareaService.idsOportunidadesDondeColabora(7) } returns setOf(1L, 2L)

        val compilada = listar(OportunidadFiltros(), analista)

        assertThat(compilada.hql).contains(".id in (1, 2)")
        assertThat(compilada.hql).doesNotContain("idVendedor")
    }

    @Test
    fun `filtrar por estado sustituye la exclusion automatica de cerradas`() {
        val compilada = listar(OportunidadFiltros(estado = "cerrado"), admin)

        assertThat(compilada.predicados).isEqualTo(1)
        assertThat(compilada.hql).contains("estado")
    }

    /**
     * Un `?estado=` fuera del enum es un typo del cliente. Antes se ignoraba en
     * silencio y la respuesta salia 200 con TODO el pipeline, cerradas incluidas.
     */
    @Test
    fun `un estado desconocido devuelve 400 en vez de ignorarse`() {
        val ex = assertThrows<ValidacionException> { listar(OportunidadFiltros(estado = "perdido"), admin) }

        assertThat(ex.field).isEqualTo("estado")
        assertThat(ex.message).contains("cerrado")
    }

    /** Un valor en blanco no es un typo: no filtra, pero conserva la exclusion de cerradas. */
    @Test
    fun `un estado en blanco no filtra y conserva la exclusion de cerradas`() {
        val compilada = listar(OportunidadFiltros(estado = "   "), admin)

        assertThat(compilada.predicados).isEqualTo(1)
    }

    @Test
    fun `incluir_cerradas quita la exclusion de cerradas`() {
        val compilada = listar(OportunidadFiltros(incluirCerradas = true), admin)

        assertThat(compilada.predicados).isZero()
    }

    @Test
    fun `los filtros de empresa y financiadora se apilan sobre la exclusion de cerradas`() {
        val compilada = listar(OportunidadFiltros(idEmpresa = 3, idFinanciadora = 2), admin)

        assertThat(compilada.predicados).isEqualTo(3)
        assertThat(compilada.hql).contains("idEmpresa", "idFinanciadora")
    }

    /**
     * El `sort` no pasa por la Specification: Spring Data lo resuelve como property
     * path de la entidad al ejecutar la query, asi que un campo de la allowlist que
     * no exista seria un 500 por otra puerta. Los campos permitidos se leen del
     * propio mensaje de error para no duplicar la lista aqui.
     */
    @Test
    fun `todos los campos ordenables de la allowlist existen en la entidad`() {
        val permitidos = camposOrdenablesPermitidos()

        assertThat(permitidos).isNotEmpty()
        permitidos.forEach { campo ->
            assertThatCode { listar(OportunidadFiltros(), admin, sort = campo) }
                .describedAs("el campo ordenable '%s' no existe en Oportunidad", campo)
                .doesNotThrowAnyException()
        }
    }

    /**
     * B10 / D9 de `plan-03-mapa-oportunidad-items.md`: con varios modelos por
     * oportunidad, `precio_unitario` a nivel de oportunidad ya no significa nada
     * (la sincronizacion de D21 lo deja en null en cuanto hay 2+ items), asi que
     * ordenar por el es un sinsentido. Sale de la allowlist: pedirlo es 400.
     */
    @Test
    fun `precio_unitario ya no es un campo ordenable`() {
        assertThat(camposOrdenablesPermitidos()).doesNotContain("precio_unitario")

        val ex = assertThrows<ValidacionException> { listar(OportunidadFiltros(), admin, sort = "precio_unitario") }

        assertThat(ex.field).isEqualTo("sort")
    }

    /**
     * B10, nucleo de la tarea: `cantidad` y `monto_total` se ordenan por las
     * columnas planas de `oportunidades`, sin subconsulta sobre `oportunidad_items`,
     * porque la sincronizacion de `OportunidadItemServiceImpl` (D21/B3) las mantiene
     * iguales a la suma real de los items tras cada escritura. Este test lo verifica
     * de punta a punta: construye dos oportunidades con DISTINTO numero de items,
     * les pone los valores que la sincronizacion dejaria (suma de cantidades y
     * `MontoTotal.sumarItems`), y ordena por la property que el `PageRequest`
     * realmente resuelve.
     */
    @Test
    fun `ordenar por cantidad y monto_total usa la columna sincronizada y respeta el agregado de los items`() {
        // Una oportunidad con 1 item: 2 x 100 000 = 200 000.
        val unItem = listOf(item(cantidad = 2, precio = "100000"))
        // Otra con 2 items: 3 x 90 000 + 1 x 50 000 = 320 000, y 4 buses en total.
        val dosItems = listOf(item(cantidad = 3, precio = "90000"), item(cantidad = 1, precio = "50000"))

        val pequena = sincronizada(id = 1, items = unItem)
        val grande = sincronizada(id = 2, items = dosItems)

        // La sincronizacion dejo las columnas planas como el agregado real de los items.
        assertThat(pequena.cantidad).isEqualTo(2)
        assertThat(grande.cantidad).isEqualTo(4)
        assertThat(pequena.montoTotal).isEqualByComparingTo(BigDecimal("200000"))
        assertThat(grande.montoTotal).isEqualByComparingTo(BigDecimal("320000"))
        // Con 2+ items `precio_unitario` es null: por eso salio de la allowlist.
        assertThat(grande.precioUnitario).isNull()

        assertThat(ordenadasPor("cantidad", "desc", listOf(pequena, grande))).containsExactly(grande, pequena)
        assertThat(ordenadasPor("cantidad", "asc", listOf(grande, pequena))).containsExactly(pequena, grande)
        assertThat(ordenadasPor("monto_total", "desc", listOf(pequena, grande))).containsExactly(grande, pequena)
        assertThat(ordenadasPor("monto_total", "asc", listOf(grande, pequena))).containsExactly(pequena, grande)
    }

    // ── privados ───────────────────────────────────────────────

    private fun listar(
        filtros: OportunidadFiltros,
        usuario: UsuarioActual,
        sort: String? = null,
    ): Compilada {
        var compilada = Compilada(hql = "", predicados = 0)
        every { oportunidadRepository.findAll(any<Specification<Oportunidad>>(), any<PageRequest>()) } answers {
            compilada = compilar(firstArg(), secondArg())
            PageImpl(emptyList(), PageRequest.of(0, 20), 0)
        }
        service.listar(filtros, usuario, page = null, perPage = null, sort = sort, dir = null)
        return compilada
    }

    private fun compilar(
        spec: Specification<Oportunidad>,
        pageRequest: PageRequest,
    ): Compilada {
        val cb = emf.criteriaBuilder
        val query = cb.createQuery(Oportunidad::class.java)
        val root = query.from(Oportunidad::class.java)
        // El select explicito es solo para poder renderizar el HQL de vuelta.
        query.select(root)
        pageRequest.sort.forEach { orden -> root.get<Any>(orden.property) }
        val predicado = spec.toPredicate(root, query, cb)
        predicado?.let { query.where(it) }
        return Compilada(
            hql = (query as SqmSelectStatement<*>).toHqlString(),
            predicados = predicado?.expressions?.size ?: 0,
        )
    }

    private fun camposOrdenablesPermitidos(): List<String> {
        val error =
            runCatching {
                service.listar(OportunidadFiltros(), admin, page = null, perPage = null, sort = "__nope__", dir = null)
            }.exceptionOrNull()
        return (error as ValidacionException).message.substringAfter("Campos permitidos: ").split(", ")
    }

    /**
     * Ordena `oportunidades` por el mismo `Sort` que el servicio le pasa al
     * repositorio para ese `sort`/`dir`: la property se lee del `PageRequest` real,
     * no se hardcodea aqui, para que un cambio en `CAMPOS_ORDENABLES` se note.
     */
    private fun ordenadasPor(
        sort: String,
        dir: String,
        oportunidades: List<Oportunidad>,
    ): List<Oportunidad> {
        val orden = pageRequestDe(sort, dir).sort.single()
        val valor = { o: Oportunidad ->
            Oportunidad::class.java
                .getMethod("get" + orden.property.replaceFirstChar { it.uppercase() })
                .invoke(o) as Comparable<Any>
        }
        val comparador = compareBy(valor)
        return oportunidades.sortedWith(if (orden.isAscending) comparador else comparador.reversed())
    }

    /** El `PageRequest` que `listar` construye de verdad para esos query params. */
    private fun pageRequestDe(
        sort: String,
        dir: String,
    ): PageRequest {
        lateinit var capturado: PageRequest
        every { oportunidadRepository.findAll(any<Specification<Oportunidad>>(), any<PageRequest>()) } answers {
            capturado = secondArg()
            PageImpl(emptyList(), PageRequest.of(0, 20), 0)
        }
        service.listar(OportunidadFiltros(), admin, page = null, perPage = null, sort = sort, dir = dir)
        return capturado
    }

    private fun item(
        cantidad: Int,
        precio: String,
    ) = OportunidadItem(
        idOportunidad = 1,
        idModelo = 1,
        cantidad = cantidad,
        precioVenta = BigDecimal(precio),
        createdBy = 1,
        updatedBy = 1,
    )

    /**
     * Oportunidad con las columnas planas puestas como las deja
     * `OportunidadItemServiceImpl.sincronizarColumnasViejas` (D21): `cantidad` es la
     * suma de las cantidades de los items, `monto_total` es `MontoTotal.sumarItems`,
     * y `precio_unitario` solo sobrevive cuando hay exactamente un item.
     */
    private fun sincronizada(
        id: Long,
        items: List<OportunidadItem>,
    ) = Oportunidad(
        id = id,
        idEmpresa = 1,
        idVendedor = 7,
        idFinanciadora = 1,
        idModelo = 1,
        cantidad = items.mapNotNull { it.cantidad }.takeIf { it.isNotEmpty() }?.sum(),
        precioUnitario = items.singleOrNull()?.precioVenta,
        dcto = items.singleOrNull()?.descuento,
        montoTotal = MontoTotal.sumarItems(items),
        createdBy = 1,
        updatedBy = 1,
    )

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
            ).addAnnotatedClass(Oportunidad::class.java)
                .buildMetadata()
                .buildSessionFactory()

        @JvmStatic
        @AfterAll
        fun cerrarEmf() {
            emf.close()
        }
    }
}
