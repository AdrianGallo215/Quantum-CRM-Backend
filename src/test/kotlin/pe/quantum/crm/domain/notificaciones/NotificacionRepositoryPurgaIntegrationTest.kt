package pe.quantum.crm.domain.notificaciones

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.transaction.annotation.Transactional
import pe.quantum.crm.support.IntegrationTestBase
import java.time.LocalDateTime

/**
 * Test de integracion del criterio real de `purgarLeidasAntesDe` (hallazgo C.4).
 * `LimpiezaNotificacionesJobTest` (unitario, con mock) fija el corte que se pasa al
 * repositorio; este test fija lo que Postgres realmente borra con ese corte —
 * en particular, que una notificacion NO leida sobrevive aunque sea vieja, caso que
 * el mock nunca podia ver.
 */
@Tag("integration")
@SpringBootTest
@Transactional
class NotificacionRepositoryPurgaIntegrationTest : IntegrationTestBase() {
    @Autowired
    lateinit var repository: NotificacionRepository

    private fun notificacion(
        leida: Boolean,
        createdAt: LocalDateTime,
    ) = repository.save(
        Notificacion(
            idEmpleadoDestinatario = 1,
            idActor = null,
            tipo = TipoNotificacion.tarea_recordatorio,
            mensaje = "Notificacion de prueba",
            entidadTipo = EntidadNotificacion.empresa,
            entidadId = 1,
            leida = leida,
            createdAt = createdAt,
        ),
    )

    @Test
    fun `purga solo las leidas anteriores al corte`() {
        val leidaVieja = notificacion(leida = true, createdAt = LocalDateTime.now().minusDays(40))
        val leidaReciente = notificacion(leida = true, createdAt = LocalDateTime.now().minusDays(10))
        val noLeidaVieja = notificacion(leida = false, createdAt = LocalDateTime.now().minusDays(40))

        val borradas = repository.purgarLeidasAntesDe(LocalDateTime.now().minusDays(30))

        assertThat(borradas).isEqualTo(1)
        assertThat(repository.findById(requireNotNull(leidaVieja.id))).isEmpty
        assertThat(repository.findById(requireNotNull(leidaReciente.id))).isPresent
        assertThat(repository.findById(requireNotNull(noLeidaVieja.id))).isPresent
    }
}
