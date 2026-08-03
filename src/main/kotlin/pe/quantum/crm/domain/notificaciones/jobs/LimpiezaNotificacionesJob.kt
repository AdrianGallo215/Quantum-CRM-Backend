package pe.quantum.crm.domain.notificaciones.jobs

import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import pe.quantum.crm.domain.notificaciones.NotificacionRepository
import java.time.LocalDateTime

/** Purga diaria de notificaciones leidas con mas de 30 dias (docs/superpowers/specs/2026-07-09-notificaciones-in-app-design.md). */
@Component
class LimpiezaNotificacionesJob(
    private val notificacionRepository: NotificacionRepository,
) {
    @Scheduled(cron = "0 0 3 * * *")
    @Transactional
    fun ejecutar() {
        notificacionRepository.purgarLeidasAntesDe(LocalDateTime.now().minusDays(DIAS_RETENCION))
    }

    private companion object {
        const val DIAS_RETENCION = 30L
    }
}
