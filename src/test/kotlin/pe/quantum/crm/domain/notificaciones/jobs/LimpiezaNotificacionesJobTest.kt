package pe.quantum.crm.domain.notificaciones.jobs

import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import pe.quantum.crm.domain.notificaciones.NotificacionRepository
import java.time.Clock
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset

class LimpiezaNotificacionesJobTest {
    private val notificacionRepository = mockk<NotificacionRepository>()

    /**
     * El corte ES el criterio de purga. Con reloj fijo se puede afirmar el valor
     * exacto en vez de una franja de un minuto alrededor de `now()`, que pasaba
     * verde aunque la aritmetica se desviara.
     */
    @Test
    fun `el corte de purga son exactamente 30 dias antes de la ejecucion`() {
        val reloj = Clock.fixed(Instant.parse("2026-08-11T12:00:00Z"), ZoneOffset.UTC)
        val corte = slot<LocalDateTime>()
        every { notificacionRepository.purgarLeidasAntesDe(capture(corte)) } returns 3

        LimpiezaNotificacionesJob(notificacionRepository, reloj).ejecutar()

        assertThat(corte.captured).isEqualTo(LocalDateTime.of(2026, 7, 12, 12, 0, 0))
    }
}
