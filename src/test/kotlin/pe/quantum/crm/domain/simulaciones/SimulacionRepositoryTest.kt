package pe.quantum.crm.domain.simulaciones

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatCode
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.annotation.Transactional
import pe.quantum.crm.shared.enums.ModoSimulacion
import pe.quantum.crm.shared.enums.TipoEventoSimulacion
import pe.quantum.crm.support.IntegrationTestBase
import java.math.BigDecimal
import java.time.LocalDateTime

/**
 * Cobertura de `SimulacionRepository` y `SimulacionLogRepository` contra
 * Postgres real: el índice único parcial `uq_simulacion_principal`, los CHECK
 * `chk_simulacion_principal_requiere_item` y `chk_simulacion_log_snapshot`, y
 * la query nativa `correlativos` solo se pueden validar de verdad contra el
 * motor real (K14/K15/K17 de plan-09-mapa-simulaciones-modulo.md); un mock del
 * repositorio no probaría nada de esto.
 *
 * `@Transactional` a nivel de clase, igual que
 * `NotificacionRepositoryPurgaIntegrationTest`: `desmarcarPrincipalDe` es un
 * `@Modifying` de JPQL y exige una transacción activa para ejecutarse (Spring
 * Data lanza `TransactionRequiredException` si no la hay), y además así cada
 * test hace rollback solo sin necesidad de borrar a mano.
 *
 * NO se ejecutaron en la máquina que escribió este archivo: Testcontainers
 * está roto por el bloqueo de Docker 29 (memoria testcontainers-docker29-blocker).
 * Se validan en CI mediante la tarea `integrationTest`. Las aserciones se
 * derivaron leyendo `V43__create_simulaciones.sql` y `SimulacionRepository.kt`
 * línea por línea, no ejecutando el código.
 */
@Tag("integration")
@SpringBootTest
@Transactional
class SimulacionRepositoryTest
    @Autowired
    constructor(
        private val repository: SimulacionRepository,
        private val logRepository: SimulacionLogRepository,
        private val jdbcTemplate: JdbcTemplate,
    ) : IntegrationTestBase() {
        // created_by/updated_by=1 es el admin sembrado por V19, igual que SolicitudRepositoryTest.
        private fun id(sql: String): Long = jdbcTemplate.queryForObject(sql, Long::class.java)!!

        /** Ítem de oportunidad real, sembrado con JdbcTemplate crudo (patrón de ReporteServiceSqlIntegrationTest). */
        private fun crearOportunidadItem(sufijo: String): Long {
            val vendedor =
                id(
                    "INSERT INTO empleados (nombres, apellidos, email, rol) " +
                        "VALUES ('Repo', 'Test$sufijo', 'repo.test$sufijo@quantum.pe', 'vendedor') RETURNING id",
                )
            val empresa =
                id(
                    """
                    INSERT INTO empresas
                        (ruc, razon_social, actividad_econ, id_vendedor, estado_sunat, condicion_sunat,
                         direccion_fiscal, origen_lead, estado_cartera, created_by, updated_by)
                    VALUES
                        ('210$sufijo', 'Repo Test $sufijo S.A.C.', 'Transporte', $vendedor, 'ACTIVO', 'HABIDO',
                         'Av. Repo Test 1', 'visita_fria', 'no_contactado', $vendedor, $vendedor)
                    RETURNING id
                    """.trimIndent(),
                )
            val financiadora = id("INSERT INTO financiadoras (nombre) VALUES ('Financiadora repo $sufijo') RETURNING id")
            val modelo = id("INSERT INTO modelos (codigo) VALUES ('REPO-$sufijo') RETURNING id")
            val oportunidad =
                id(
                    """
                    INSERT INTO oportunidades
                        (id_empresa, id_vendedor, id_financiadora, estado, created_by, updated_by)
                    VALUES
                        ($empresa, $vendedor, $financiadora, 'evaluacion_calidda', $vendedor, $vendedor)
                    RETURNING id
                    """.trimIndent(),
                )
            return id(
                """
                INSERT INTO oportunidad_items
                    (id_oportunidad, id_modelo, cantidad, precio_venta, descuento, created_by, updated_by)
                VALUES
                    ($oportunidad, $modelo, 1, 100000.00, 0, $vendedor, $vendedor)
                RETURNING id
                """.trimIndent(),
            )
        }

        private fun simulacion(
            idItem: Long? = null,
            esPrincipal: Boolean = false,
            createdAt: LocalDateTime = LocalDateTime.now(),
        ) = Simulacion(
            modo = ModoSimulacion.leasing,
            idOportunidadItem = idItem,
            precioVenta = BigDecimal("100000.00"),
            cuotaInicial = BigDecimal("20000.00"),
            plazoMeses = 36,
            tea = BigDecimal("15.00"),
            cuotaFinal = BigDecimal("3500.00"),
            esPrincipal = esPrincipal,
            createdAt = createdAt,
            createdBy = 1,
            updatedBy = 1,
        )

        @Test
        fun `una simulacion se guarda y se relee con todos sus campos`() {
            val original =
                Simulacion(
                    modo = ModoSimulacion.credito_directo,
                    nombre = "Simulación manual",
                    idOportunidadItem = null,
                    idModelo = null,
                    idSimulacionOrigen = null,
                    precioVenta = BigDecimal("120000.00"),
                    descuento = BigDecimal("5.00"),
                    cuotaInicial = BigDecimal("15000.00"),
                    plazoMeses = 48,
                    tea = BigDecimal("18.50"),
                    valorResidual = BigDecimal("1000.00"),
                    diasTrabajados = 20,
                    comisionEstructuracion = BigDecimal("1500.00"),
                    cuotaFinal = BigDecimal("2800.00"),
                    esPrincipal = false,
                    createdBy = 1,
                    updatedBy = 1,
                )

            val guardada = repository.save(original)
            val leida = repository.findById(requireNotNull(guardada.id)).orElseThrow()

            assertThat(leida.modo).isEqualTo(ModoSimulacion.credito_directo)
            assertThat(leida.nombre).isEqualTo("Simulación manual")
            assertThat(leida.idOportunidadItem).isNull()
            assertThat(leida.idModelo).isNull()
            assertThat(leida.idSimulacionOrigen).isNull()
            assertThat(leida.precioVenta).isEqualByComparingTo(BigDecimal("120000.00"))
            assertThat(leida.descuento).isEqualByComparingTo(BigDecimal("5.00"))
            assertThat(leida.cuotaInicial).isEqualByComparingTo(BigDecimal("15000.00"))
            assertThat(leida.plazoMeses).isEqualTo(48)
            assertThat(leida.tea).isEqualByComparingTo(BigDecimal("18.50"))
            assertThat(leida.valorResidual).isEqualByComparingTo(BigDecimal("1000.00"))
            assertThat(leida.diasTrabajados).isEqualTo(20)
            assertThat(leida.comisionEstructuracion).isEqualByComparingTo(BigDecimal("1500.00"))
            assertThat(leida.cuotaFinal).isEqualByComparingTo(BigDecimal("2800.00"))
            assertThat(leida.esPrincipal).isFalse()
            assertThat(leida.createdBy).isEqualTo(1)
            assertThat(leida.updatedBy).isEqualTo(1)
        }

        @Test
        fun `el indice unico parcial rechaza dos principales para el mismo item`() {
            val idItem = crearOportunidadItem("P1")
            repository.saveAndFlush(simulacion(idItem = idItem, esPrincipal = true))

            assertThatThrownBy {
                repository.saveAndFlush(simulacion(idItem = idItem, esPrincipal = true))
            }.isInstanceOf(DataIntegrityViolationException::class.java)
        }

        @Test
        fun `desmarcarPrincipalDe permite el relevo de la principal sin violar el indice`() {
            val idItem = crearOportunidadItem("P2")
            repository.saveAndFlush(simulacion(idItem = idItem, esPrincipal = true))

            repository.desmarcarPrincipalDe(idItem)

            assertThatCode {
                repository.saveAndFlush(simulacion(idItem = idItem, esPrincipal = true))
            }.doesNotThrowAnyException()
        }

        @Test
        fun `el CHECK chk_simulacion_principal_requiere_item rechaza una principal sin item`() {
            assertThatThrownBy {
                repository.saveAndFlush(simulacion(idItem = null, esPrincipal = true))
            }.isInstanceOf(DataIntegrityViolationException::class.java)
        }

        @Test
        fun `el CHECK chk_simulacion_log_snapshot rechaza un evento creada sin snapshot completo`() {
            val logSinSnapshot =
                SimulacionLog(
                    idSimulacion = 999_999,
                    tipoEvento = TipoEventoSimulacion.creada,
                    modo = null,
                    createdBy = 1,
                )

            assertThatThrownBy {
                logRepository.saveAndFlush(logSinSnapshot)
            }.isInstanceOf(DataIntegrityViolationException::class.java)
        }

        @Test
        fun `correlativos numera 1 2 3 por orden de createdAt dentro del mismo item`() {
            val idItem = crearOportunidadItem("C1")
            val base = LocalDateTime.of(2026, 1, 1, 10, 0)

            val s1 = repository.saveAndFlush(simulacion(idItem = idItem, createdAt = base))
            val s2 = repository.saveAndFlush(simulacion(idItem = idItem, createdAt = base.plusMinutes(5)))
            val s3 = repository.saveAndFlush(simulacion(idItem = idItem, createdAt = base.plusMinutes(10)))

            val filas =
                repository
                    .correlativos(listOf(requireNotNull(s1.id), requireNotNull(s2.id), requireNotNull(s3.id)))
                    .associateBy { it.getId() }

            assertThat(filas.getValue(requireNotNull(s1.id)).getCorrelativo()).isEqualTo(1)
            assertThat(filas.getValue(requireNotNull(s2.id)).getCorrelativo()).isEqualTo(2)
            assertThat(filas.getValue(requireNotNull(s3.id)).getCorrelativo()).isEqualTo(3)
        }

        // ---------------------------------------------------------------------
        // Historial (D43): ventana de 7 días y desempate por `id` (K23/K29).
        // `simulacion_log.id_simulacion` NO tiene FK a propósito, así que un id
        // sintético basta para aislar estos casos del resto del archivo.
        // ---------------------------------------------------------------------

        private fun eventoLog(
            tipoEvento: TipoEventoSimulacion,
            createdAt: LocalDateTime,
            idSimulacion: Long = ID_SIMULACION_HISTORIAL,
        ) = SimulacionLog(
            idSimulacion = idSimulacion,
            tipoEvento = tipoEvento,
            // El CHECK chk_simulacion_log_snapshot exige el snapshot completo
            // para creada/editada/restaurada.
            modo = ModoSimulacion.leasing,
            precioVenta = BigDecimal("100000.00"),
            cuotaInicial = BigDecimal("20000.00"),
            plazoMeses = 36,
            tea = BigDecimal("15.00"),
            valorResidual = BigDecimal("0.00"),
            cuotaFinal = BigDecimal("3500.00"),
            createdAt = createdAt,
            createdBy = 1,
        )

        /**
         * Siembra, en orden de inserción (y por tanto de `id` ascendente):
         * `creada` de hace 20 días (fuera de la ventana), `editada` de hace 3
         * días, y **dos** `editada` que comparten exactamente el mismo
         * `createdAt` de hace 2 días — el caso de K29.
         */
        private fun sembrarHistorial(): Sembrado {
            val ahora = LocalDateTime.now()
            val empate = ahora.minusDays(2)
            return Sembrado(
                viejo = logRepository.saveAndFlush(eventoLog(TipoEventoSimulacion.creada, ahora.minusDays(20))),
                tresDias = logRepository.saveAndFlush(eventoLog(TipoEventoSimulacion.editada, ahora.minusDays(3))),
                empateA = logRepository.saveAndFlush(eventoLog(TipoEventoSimulacion.editada, empate)),
                empateB = logRepository.saveAndFlush(eventoLog(TipoEventoSimulacion.editada, empate)),
            )
        }

        private data class Sembrado(
            val viejo: SimulacionLog,
            val tresDias: SimulacionLog,
            val empateA: SimulacionLog,
            val empateB: SimulacionLog,
        )

        @Test
        fun `historial deja fuera el evento de 20 dias y desempata los empatados por id descendente`() {
            val sembrado = sembrarHistorial()

            val historial = logRepository.historial(ID_SIMULACION_HISTORIAL)

            assertThat(historial.map { it.id })
                .containsExactly(sembrado.empateB.id, sembrado.empateA.id, sembrado.tresDias.id)
                .doesNotContain(sembrado.viejo.id)
        }

        @Test
        fun `eventoAnteriorA cruza la ventana de 7 dias para el evento mas antiguo del historial`() {
            val sembrado = sembrarHistorial()

            val anterior =
                logRepository.eventoAnteriorA(
                    ID_SIMULACION_HISTORIAL,
                    sembrado.tresDias.createdAt,
                    requireNotNull(sembrado.tresDias.id),
                )

            assertThat(anterior?.id).isEqualTo(sembrado.viejo.id)
        }

        @Test
        fun `eventoAnteriorA no se salta el evento empatado en created_at con id menor`() {
            val sembrado = sembrarHistorial()

            val anterior =
                logRepository.eventoAnteriorA(
                    ID_SIMULACION_HISTORIAL,
                    sembrado.empateB.createdAt,
                    requireNotNull(sembrado.empateB.id),
                )

            assertThat(anterior?.id).isEqualTo(sembrado.empateA.id)
        }

        private companion object {
            const val ID_SIMULACION_HISTORIAL = 900_001L
        }
    }
