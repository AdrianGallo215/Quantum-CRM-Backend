package pe.quantum.crm.domain.notificaciones

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import pe.quantum.crm.support.IntegrationTestBase
import java.time.LocalDateTime

@Tag("integration")
@SpringBootTest
class NotificacionRepositoryIntegrationTest : IntegrationTestBase() {
    @Autowired
    lateinit var repository: NotificacionRepository

    @Test
    fun `guarda y recupera una notificacion con id_actor nulo`() {
        val guardada =
            repository.save(
                Notificacion(
                    idEmpleadoDestinatario = 1,
                    idActor = null,
                    tipo = TipoNotificacion.tarea_recordatorio,
                    mensaje = "Recordatorio de prueba",
                    entidadTipo = EntidadNotificacion.empresa,
                    entidadId = 1,
                    createdAt = LocalDateTime.now(),
                ),
            )

        val recuperada = repository.findById(requireNotNull(guardada.id)).orElseThrow()
        assertThat(recuperada.idActor).isNull()
        assertThat(recuperada.tipo).isEqualTo(TipoNotificacion.tarea_recordatorio)
        assertThat(recuperada.leida).isFalse()
    }
}
