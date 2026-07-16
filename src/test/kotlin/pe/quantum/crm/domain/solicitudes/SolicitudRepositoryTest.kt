package pe.quantum.crm.domain.solicitudes

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.dao.DataIntegrityViolationException
import pe.quantum.crm.shared.enums.AprobadorSolicitud
import pe.quantum.crm.shared.enums.EntidadSolicitud
import pe.quantum.crm.shared.enums.EstadoSolicitud
import pe.quantum.crm.shared.enums.TipoSolicitud
import pe.quantum.crm.support.IntegrationTestBase
import java.math.BigDecimal

@Tag("integration")
@SpringBootTest
class SolicitudRepositoryTest
    @Autowired
    constructor(
        private val repository: SolicitudRepository,
    ) : IntegrationTestBase() {
        // id_solicitante=1 es el admin seed de V19.
        private fun solicitudDescuento(entidadId: Long = 999) =
            Solicitud(
                tipo = TipoSolicitud.descuento,
                rolAprobador = AprobadorSolicitud.jdv,
                idSolicitante = 1,
                entidadTipo = EntidadSolicitud.oportunidad,
                entidadId = entidadId,
                entidadDescripcion = "Empresa X — Oportunidad #$entidadId",
                motivo = "Cliente recurrente",
                dctoSolicitado = BigDecimal("5.00"),
            )

        @Test
        fun `persiste y recupera una solicitud pendiente de descuento`() {
            val guardada = repository.save(solicitudDescuento())
            val leida = repository.findById(requireNotNull(guardada.id)).orElseThrow()
            assertThat(leida.estado).isEqualTo(EstadoSolicitud.pendiente)
            assertThat(leida.dctoSolicitado).isEqualByComparingTo(BigDecimal("5.00"))
            repository.delete(leida)
        }

        @Test
        fun `el indice parcial rechaza dos pendientes del mismo tipo sobre la misma entidad`() {
            val primera = repository.save(solicitudDescuento(entidadId = 777))
            assertThatThrownBy {
                repository.saveAndFlush(solicitudDescuento(entidadId = 777))
            }.isInstanceOf(DataIntegrityViolationException::class.java)
            repository.delete(primera)
        }

        @Test
        fun `existsBy detecta la pendiente duplicada`() {
            val guardada = repository.save(solicitudDescuento(entidadId = 555))
            assertThat(
                repository.existsByTipoAndEntidadTipoAndEntidadIdAndEstado(
                    TipoSolicitud.descuento,
                    EntidadSolicitud.oportunidad,
                    555,
                    EstadoSolicitud.pendiente,
                ),
            ).isTrue()
            repository.delete(guardada)
        }
    }
