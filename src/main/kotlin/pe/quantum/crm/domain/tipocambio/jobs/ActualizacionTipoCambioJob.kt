package pe.quantum.crm.domain.tipocambio.jobs

import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import pe.quantum.crm.domain.tipocambio.TipoCambioService

/**
 * Actualizacion diaria del tipo de cambio PEN/USD desde SUNAT.
 *
 * 14:30 UTC = 09:30 Lima (UTC-5): SUNAT publica en horario de Lima, un horario
 * mas temprano en UTC dispararia antes de que el dato del dia exista.
 *
 * La transaccion vive en TipoCambioService.actualizarDesdeSunat(), no aqui. El
 * try/catch es obligatorio: una excepcion no capturada en un @Scheduled mata
 * las ejecuciones siguientes del mismo job en el scheduler de Spring.
 */
@Component
class ActualizacionTipoCambioJob(
    private val tipoCambioService: TipoCambioService,
) {
    @Scheduled(cron = "0 30 14 * * *")
    @Suppress("TooGenericExceptionCaught") // Una excepcion no capturada mata las ejecuciones siguientes del job.
    fun ejecutar() {
        try {
            val actualizo = tipoCambioService.actualizarDesdeSunat()
            log.info("Actualizacion de tipo de cambio SUNAT ejecutada. actualizo={}", actualizo)
        } catch (e: Exception) {
            log.error("Error al actualizar el tipo de cambio desde SUNAT", e)
        }
    }

    private companion object {
        val log = LoggerFactory.getLogger(ActualizacionTipoCambioJob::class.java)
    }
}
