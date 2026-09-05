package pe.quantum.crm.domain.oportunidades

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.annotation.Transactional
import pe.quantum.crm.domain.oportunidades.dto.OportunidadFiltros
import pe.quantum.crm.shared.security.UsuarioActual
import pe.quantum.crm.support.IntegrationTestBase

/**
 * `GET /oportunidades?sort=cantidad|monto_total` contra Postgres real (D29 de
 * `plan-07-mapa-retirar-columnas.md`).
 *
 * Por que aqui y no en `OportunidadListadoSpecificationTest`: aquel compila
 * `Specification`s contra el metamodelo de Hibernate, sin conexion. Estos dos
 * campos ya no son columnas de `oportunidades` mantenidas al dia por la
 * sincronizacion de D21 — son agregados de `oportunidad_items` que se ordenan en
 * una rama de SQL nativo. Un mock del `NamedParameterJdbcTemplate` solo probaria
 * que se llama al mock; que la subconsulta correlacionada ordene de verdad, y que
 * la formula de dinero (`MontoTotal.calcular` duplicada en SQL) aplique el
 * descuento, solo lo dice el motor.
 *
 * Se siembra con `JdbcTemplate` crudo (mismo patron que
 * `ReporteServiceIntegrationTest`) y se consulta como un `vendedor`, de modo que
 * el filtro de visibilidad de la propia rama nativa acota el resultado a lo
 * sembrado aqui y el contenedor compartido no puede contaminarlo. La clase es
 * transaccional: todo se revierte.
 *
 * Las tres oportunidades estan elegidas para que los dos ordenes NO coincidan
 * (`chica` < `grande` en ambos, pero `barata` es la mayor en cantidad y la menor
 * en monto): si las dos ramas compartieran subconsulta por error, uno de los dos
 * bloques de aserciones tendria que fallar.
 */
@Tag("integration")
@SpringBootTest
@Transactional
class OportunidadListadoSortNativoIntegrationTest : IntegrationTestBase() {
    @Autowired
    lateinit var jdbcTemplate: JdbcTemplate

    @Autowired
    lateinit var oportunidadService: OportunidadService

    // ── Semilla ────────────────────────────────────────────────

    private fun id(sql: String): Long = jdbcTemplate.queryForObject(sql, Long::class.java)!!

    private fun crearVendedor(): Long =
        id(
            "INSERT INTO empleados (nombres, apellidos, email, rol) " +
                "VALUES ('Sort', 'Nativo', 'sort.nativo@quantum.pe', 'vendedor') RETURNING id",
        )

    private fun crearEmpresa(idVendedor: Long): Long =
        id(
            """
            INSERT INTO empresas
                (ruc, razon_social, actividad_econ, id_vendedor, estado_sunat, condicion_sunat,
                 direccion_fiscal, origen_lead, estado_cartera, created_by, updated_by)
            VALUES
                ('20777000111', 'Sort Nativo S.A.C.', 'Transporte', $idVendedor, 'ACTIVO', 'HABIDO',
                 'Av. Sort 1', 'visita_fria', 'oportunidad_activa', $idVendedor, $idVendedor)
            RETURNING id
            """.trimIndent(),
        )

    private fun crearModelo(sufijo: String): Long = id("INSERT INTO modelos (codigo) VALUES ('SORT-$sufijo') RETURNING id")

    private fun crearFinanciadora(): Long = id("INSERT INTO financiadoras (nombre) VALUES ('Financiadora sort nativo') RETURNING id")

    /**
     * Oportunidad SIN valores en las columnas planas de item (retiradas por V46):
     * el modelo, cantidad, precio y descuento viven solo en `oportunidad_items`, y
     * por eso el orden solo puede salir de los items.
     */
    private fun crearOportunidad(
        idEmpresa: Long,
        idVendedor: Long,
        idFinanciadora: Long,
    ): Long =
        id(
            """
            INSERT INTO oportunidades
                (id_empresa, id_vendedor, id_financiadora, estado, created_by, updated_by)
            VALUES
                ($idEmpresa, $idVendedor, $idFinanciadora, 'evaluacion_calidda', $idVendedor, $idVendedor)
            RETURNING id
            """.trimIndent(),
        )

    @Suppress("LongParameterList") // Fixture: son las columnas del item, no un parametro de diseno.
    private fun crearItem(
        idOportunidad: Long,
        idVendedor: Long,
        idModelo: Long,
        cantidad: Int,
        precioVenta: String,
        descuento: String,
    ) {
        jdbcTemplate.update(
            """
            INSERT INTO oportunidad_items
                (id_oportunidad, id_modelo, cantidad, precio_venta, descuento, created_by, updated_by)
            VALUES
                ($idOportunidad, $idModelo, $cantidad, $precioVenta, $descuento, $idVendedor, $idVendedor)
            """.trimIndent(),
        )
    }

    /** Las tres oportunidades del escenario, en el orden en que se crean. */
    private data class Escenario(
        val vendedor: UsuarioActual,
        val chica: Long,
        val grande: Long,
        val barata: Long,
    )

    private fun sembrar(): Escenario {
        val idVendedor = crearVendedor()
        val idEmpresa = crearEmpresa(idVendedor)
        val idFinanciadora = crearFinanciadora()
        val modeloA = crearModelo("A")
        val modeloB = crearModelo("B")

        // chica: 1 item — 2 unidades, 2 x 100 000 = 200 000.
        val chica = crearOportunidad(idEmpresa, idVendedor, idFinanciadora)
        crearItem(chica, idVendedor, modeloA, cantidad = 2, precioVenta = "100000.00", descuento = "0.00")

        // grande: 2 items — 4 unidades, 3 x 90 000 + 1 x 50 000 = 320 000.
        val grande = crearOportunidad(idEmpresa, idVendedor, idFinanciadora)
        crearItem(grande, idVendedor, modeloA, cantidad = 3, precioVenta = "90000.00", descuento = "0.00")
        crearItem(grande, idVendedor, modeloB, cantidad = 1, precioVenta = "50000.00", descuento = "0.00")

        // barata: 1 item — 10 unidades (la mayor cantidad) pero con 50% de descuento,
        // 10 x 2 000 x 0,5 = 10 000 (el menor monto). El descuento no es decorativo:
        // sin el COALESCE/la resta de la formula, esta oportunidad ordenaria por
        // 20 000 y el bloque de monto cambiaria de orden.
        val barata = crearOportunidad(idEmpresa, idVendedor, idFinanciadora)
        crearItem(barata, idVendedor, modeloB, cantidad = 10, precioVenta = "2000.00", descuento = "50.00")

        return Escenario(UsuarioActual(id = idVendedor, rol = "vendedor"), chica, grande, barata)
    }

    private fun idsOrdenadosPor(
        usuario: UsuarioActual,
        sort: String,
        dir: String,
    ): List<Long> =
        oportunidadService
            .listar(OportunidadFiltros(), usuario, page = null, perPage = null, sort = sort, dir = dir)
            .items
            .map { it.id }

    // ── Orden por cantidad ─────────────────────────────────────

    @Test
    fun `sort por cantidad ordena por la suma de las cantidades de los items`() {
        val escenario = sembrar()

        assertThat(idsOrdenadosPor(escenario.vendedor, "cantidad", "asc"))
            .containsExactly(escenario.chica, escenario.grande, escenario.barata)
        assertThat(idsOrdenadosPor(escenario.vendedor, "cantidad", "desc"))
            .containsExactly(escenario.barata, escenario.grande, escenario.chica)
    }

    // ── Orden por monto ────────────────────────────────────────

    @Test
    fun `sort por monto_total ordena por la suma de los subtotales de los items, con descuento`() {
        val escenario = sembrar()

        assertThat(idsOrdenadosPor(escenario.vendedor, "monto_total", "asc"))
            .containsExactly(escenario.barata, escenario.chica, escenario.grande)
        assertThat(idsOrdenadosPor(escenario.vendedor, "monto_total", "desc"))
            .containsExactly(escenario.grande, escenario.chica, escenario.barata)
    }

    // ── Envelope y visibilidad ─────────────────────────────────

    /**
     * La rama nativa tiene su propio `COUNT(*)` y su propio `LIMIT/OFFSET`: si el
     * conteo se calculara sobre la pagina en vez de sobre el filtro completo, o si
     * el `WHERE` de las dos consultas se separara, `total` dejaria de cuadrar.
     */
    @Test
    fun `la rama nativa pagina y cuenta sobre el filtro completo`() {
        val escenario = sembrar()

        val primera =
            oportunidadService.listar(
                OportunidadFiltros(),
                escenario.vendedor,
                page = 1,
                perPage = 2,
                sort = "cantidad",
                dir = "asc",
            )
        val segunda =
            oportunidadService.listar(
                OportunidadFiltros(),
                escenario.vendedor,
                page = 2,
                perPage = 2,
                sort = "cantidad",
                dir = "asc",
            )

        assertThat(primera.meta.total).isEqualTo(3)
        assertThat(primera.meta.totalPages).isEqualTo(2)
        assertThat(primera.items.map { it.id }).containsExactly(escenario.chica, escenario.grande)
        assertThat(segunda.items.map { it.id }).containsExactly(escenario.barata)
    }

    /**
     * El filtro de visibilidad de la rama nativa es el mismo que el de la
     * `Specification` (`OportunidadVisibilidad.filtroVisibilidadSql`): otro vendedor
     * no ve nada de esta cartera aunque ordene por un agregado.
     */
    @Test
    fun `un vendedor ajeno no ve las oportunidades de otro al ordenar por agregado`() {
        val escenario = sembrar()
        val ajeno =
            UsuarioActual(
                id =
                    id(
                        "INSERT INTO empleados (nombres, apellidos, email, rol) " +
                            "VALUES ('Otro', 'Vendedor', 'otro.sort@quantum.pe', 'vendedor') RETURNING id",
                    ),
                rol = "vendedor",
            )

        val resultado =
            oportunidadService.listar(
                OportunidadFiltros(),
                ajeno,
                page = null,
                perPage = null,
                sort = "monto_total",
                dir = "desc",
            )

        assertThat(resultado.items).isEmpty()
        assertThat(resultado.meta.total).isZero()
        // Y la cartera propia sigue completa: no es que la consulta devuelva vacio siempre.
        assertThat(idsOrdenadosPor(escenario.vendedor, "monto_total", "desc")).hasSize(3)
    }

    /**
     * Tercera rama de la visibilidad: un rol de apoyo sin ninguna tarea donde
     * colabore no tiene cartera propia, asi que el filtro tiene que ser un "siempre
     * falso" explicito. Es el equivalente en SQL del `cb.disjunction()` que
     * `OportunidadListadoSpecificationTest` vigila del lado de Criteria; sin el, un
     * `IN ()` vacio no filtraria nada y el listado se convertiria en una fuga.
     */
    @Test
    fun `un rol de apoyo sin colaboraciones no ve nada al ordenar por agregado`() {
        sembrar()
        val apoyo =
            UsuarioActual(
                id =
                    id(
                        "INSERT INTO empleados (nombres, apellidos, email, rol) " +
                            "VALUES ('Ana', 'Lista', 'ana.lista.sort@quantum.pe', 'analista') RETURNING id",
                    ),
                rol = "analista",
            )

        val resultado =
            oportunidadService.listar(
                OportunidadFiltros(),
                apoyo,
                page = null,
                perPage = null,
                sort = "cantidad",
                dir = "desc",
            )

        assertThat(resultado.items).isEmpty()
        assertThat(resultado.meta.total).isZero()
    }
}
