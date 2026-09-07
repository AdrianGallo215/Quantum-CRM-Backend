package pe.quantum.crm.domain.simulaciones.jobs

import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import pe.quantum.crm.domain.simulaciones.DefaultsSimulacion
import pe.quantum.crm.domain.simulaciones.SimulacionService
import java.time.Clock
import java.time.LocalDateTime

/**
 * Las dos mitades de la regla de purga de `reglas_simulaciones.md` §5: hard
 * delete de las simulaciones huerfanas a los 30 dias, y aviso a su creador 3
 * dias antes. Un solo job con dos `@Scheduled` porque comparten sus dos
 * constantes y son la misma regla (decision D61 de
 * plan-13-mapa-cierre-simulaciones.md).
 *
 * El aviso corre ANTES que la purga del mismo dia a proposito: si alguna vez
 * las ventanas se rozaran por un desfase de reloj, es preferible avisar de mas
 * que purgar sin avisar.
 *
 * ## La ventana de aviso
 *
 * Una simulacion creada en el instante `T` se purga en la primera corrida de
 * [purgar] en la que `T < ahora - 30 dias`, es decir cuando pasa de los 30 dias
 * de vida. El aviso tiene que caer 3 dias antes, o sea a los 27:
 *
 * ```
 * desde = ahora - 28 dias   (30 - 3 + 1)
 * hasta = ahora - 27 dias   (30 - 3)
 * ```
 *
 * `desde` es mas antiguo que `hasta`, y la ventana `(desde, hasta]` recoge
 * exactamente las simulaciones con una edad en `[27 dias, 28 dias)`. Como dura
 * 24 h y el job corre cada 24 h, las ventanas de dos corridas consecutivas
 * teselan sin solaparse ni dejar hueco: cada simulacion cae en una, y solo en
 * una, en toda su vida. Por eso no hace falta tabla de dedup (D58, que resuelve
 * asi el hallazgo K35).
 *
 * ## Limitacion conocida, y aceptada (D58)
 *
 * Si el job no corre un dia, las simulaciones de esa ventana se quedan sin
 * aviso y aun asi se purgan a los 30 dias. Es un aviso, no una garantia
 * transaccional; el precio a cambio es no ampliar la API publica de
 * `notificaciones` con un "¿ya se aviso?" ni añadir un tercer valor a
 * `origen_recordatorio_enum`. No es un bug: esta escrito aqui para que nadie lo
 * lea como tal.
 */
@Component
class PurgaSimulacionesJob(
    private val simulacionService: SimulacionService,
    private val clock: Clock = Clock.systemUTC(),
) {
    /**
     * Aviso 3 dias antes del borrado (§5). Corre ANTES que la purga del mismo
     * dia. 05:00 UTC = 00:00 en Lima: fuera de las horas de uso, y sin chocar
     * con `LimpiezaNotificacionesJob` (03:00) ni con el de tipo de cambio
     * (14:30). SI coincide con el minuto 05:00:00 de `RecordatorioJob`, que
     * corre cada hora en punto (`0 0 * * * *`): es un solape inevitable de un
     * job horario contra uno diario, no un choque real — el `TaskScheduler`
     * por defecto de Spring tiene pool de 1, asi que las dos tareas se
     * serializan en vez de competir por el mismo recurso.
     */
    @Scheduled(cron = "0 0 5 * * *")
    fun avisar() {
        val ahora = LocalDateTime.now(clock)
        simulacionService.avisarHuerfanasPorExpirar(
            // Edad en [27 d, 28 d): la frontera del preaviso, cruzada una sola vez.
            desde = ahora.minusDays(DIAS_RETENCION - DIAS_AVISO + 1),
            hasta = ahora.minusDays(DIAS_RETENCION - DIAS_AVISO),
        )
    }

    /** Hard delete de las huerfanas de mas de 30 dias (§5, decision D60). */
    @Scheduled(cron = "0 30 5 * * *")
    fun purgar() {
        simulacionService.purgarHuerfanas(LocalDateTime.now(clock).minusDays(DIAS_RETENCION))
    }

    private companion object {
        /**
         * §5: 30 dias sin enlazar a un item. La MISMA constante que usa
         * `eliminacionPrevistaEl` del DTO (F2) — un solo numero que cambiar,
         * no un espejo que se pueda desincronizar.
         */
        val DIAS_RETENCION = DefaultsSimulacion.DIAS_RETENCION_HUERFANA.toLong()

        /** §5: el aviso al creador va 3 dias antes del borrado. */
        const val DIAS_AVISO = 3L
    }
}
