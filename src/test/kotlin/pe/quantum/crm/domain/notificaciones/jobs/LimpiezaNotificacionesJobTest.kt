package pe.quantum.crm.domain.notificaciones.jobs

import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import pe.quantum.crm.domain.notificaciones.NotificacionRepository
import java.time.Duration
import java.time.LocalDateTime

class LimpiezaNotificacionesJobTest {
    private val notificacionRepository = mockk<NotificacionRepository>()
    private val job = LimpiezaNotificacionesJob(notificacionRepository)

    @Test
    fun `purga notificaciones leidas con mas de 30 dias`() {
        val slot = slot<LocalDateTime>()
        every { notificacionRepository.purgarLeidasAntesDe(capture(slot)) } returns 3

        job.ejecutar()

        verify { notificacionRepository.purgarLeidasAntesDe(any()) }
        val diferencia = Duration.between(slot.captured, LocalDateTime.now().minusDays(30))
        assertThat(diferencia.abs()).isLessThan(Duration.ofMinutes(1))
    }
}
