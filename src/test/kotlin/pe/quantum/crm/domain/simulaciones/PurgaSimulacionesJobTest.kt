package pe.quantum.crm.domain.simulaciones

import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.scheduling.annotation.Scheduled
import pe.quantum.crm.domain.notificaciones.jobs.LimpiezaNotificacionesJob
import pe.quantum.crm.domain.simulaciones.jobs.PurgaSimulacionesJob
import pe.quantum.crm.domain.tipocambio.jobs.ActualizacionTipoCambioJob
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset

/**
 * `PurgaSimulacionesJob` (tarea F7 de plan-14-cierre-simulaciones-tareas.md).
 *
 * Reloj fijo: lo que se verifica es la aritmetica de las dos fechas, y con
 * `now()` real solo se podria afirmar una franja alrededor del instante en vez
 * del valor exacto — mismo criterio que [LimpiezaNotificacionesJob]'s test.
 */
class PurgaSimulacionesJobTest {
    private val simulacionService = mockk<SimulacionService>()

    /** Un miercoles cualquiera, a la hora en la que corre el aviso (05:00 UTC). */
    private val reloj = Clock.fixed(Instant.parse("2026-09-01T05:00:00Z"), ZoneOffset.UTC)

    private val job = PurgaSimulacionesJob(simulacionService, reloj)

    @Test
    fun `la ventana de aviso dura exactamente 24 h y cubre la edad de 27 a 28 dias`() {
        val desde = slot<LocalDateTime>()
        val hasta = slot<LocalDateTime>()
        every { simulacionService.avisarHuerfanasPorExpirar(capture(desde), capture(hasta)) } returns 2

        job.avisar()

        // 2026-09-01 menos 28 dias = 2026-08-04; menos 27 = 2026-08-05. La
        // simulacion creada el 2026-08-04T05:00:01Z (edad 27 d 23 h 59 m 59 s)
        // entra hoy; la creada el 2026-08-05T05:00:00Z (edad exacta de 27 d)
        // tambien, y ninguna de las dos vuelve a entrar mañana.
        assertThat(desde.captured).isEqualTo(LocalDateTime.of(2026, 8, 4, 5, 0, 0))
        assertThat(hasta.captured).isEqualTo(LocalDateTime.of(2026, 8, 5, 5, 0, 0))
        assertThat(Duration.between(desde.captured, hasta.captured)).isEqualTo(Duration.ofDays(1))
        // El aviso cae 3 dias antes del corte de purga de esa misma simulacion.
        assertThat(Duration.between(hasta.captured, LocalDateTime.now(reloj))).isEqualTo(Duration.ofDays(27))
    }

    @Test
    fun `el corte de purga son exactamente 30 dias antes de la ejecucion`() {
        val limite = slot<LocalDateTime>()
        every { simulacionService.purgarHuerfanas(capture(limite)) } returns 1

        job.purgar()

        assertThat(limite.captured).isEqualTo(LocalDateTime.of(2026, 8, 2, 5, 0, 0))
    }

    /**
     * La ventana de aviso de dos corridas consecutivas tesela: el `hasta` de hoy
     * es el `desde` de mañana, y el limite inferior de la query es `>` estricto
     * (`findHuerfanasCreadasEntre`), asi que ninguna simulacion cae en las dos.
     */
    @Test
    fun `la ventana de manana empieza justo donde termina la de hoy`() {
        val fechas = mutableListOf<LocalDateTime>()
        every { simulacionService.avisarHuerfanasPorExpirar(any(), any()) } answers
            {
                fechas += firstArg<LocalDateTime>()
                fechas += secondArg<LocalDateTime>()
                0
            }

        job.avisar()
        val manana = Clock.fixed(Instant.parse("2026-09-02T05:00:00Z"), ZoneOffset.UTC)
        PurgaSimulacionesJob(simulacionService, manana).avisar()

        val (desdeHoy, hastaHoy) = fechas.take(2)
        val (desdeManana, hastaManana) = fechas.drop(2)
        assertThat(hastaHoy).isEqualTo(desdeManana)
        assertThat(Duration.between(desdeHoy, hastaHoy)).isEqualTo(Duration.ofDays(1))
        assertThat(Duration.between(desdeManana, hastaManana)).isEqualTo(Duration.ofDays(1))
    }

    /**
     * Los dos horarios de D61, verificados contra los dos jobs diarios que ya
     * existian: `LimpiezaNotificacionesJob` a las 03:00 UTC y
     * `ActualizacionTipoCambioJob` a las 14:30 UTC. Ninguno coincide con 05:00
     * ni con 05:30.
     *
     * `RecordatorioJob` queda fuera de esta comparacion porque no es diario:
     * corre `0 0 * * * *`, cada hora en punto, asi que se solapa por diseño con
     * cualquier job que arranque en un minuto 0 — el de las 05:00 incluido. Es
     * un solapamiento de dos tareas cortas en el scheduler, no una colision de
     * horario que se pueda evitar eligiendo otra hora del dia.
     */
    @Test
    fun `los crones del aviso y de la purga no colisionan con los otros jobs diarios`() {
        assertThat(cron(PurgaSimulacionesJob::class.java, "avisar")).isEqualTo("0 0 5 * * *")
        assertThat(cron(PurgaSimulacionesJob::class.java, "purgar")).isEqualTo("0 30 5 * * *")

        val ajenos =
            setOf(
                cron(LimpiezaNotificacionesJob::class.java, "ejecutar"),
                cron(ActualizacionTipoCambioJob::class.java, "ejecutar"),
            )
        assertThat(ajenos).containsExactlyInAnyOrder("0 0 3 * * *", "0 30 14 * * *")
        assertThat(ajenos).doesNotContain(
            cron(PurgaSimulacionesJob::class.java, "avisar"),
            cron(PurgaSimulacionesJob::class.java, "purgar"),
        )
    }

    private fun cron(
        clase: Class<*>,
        metodo: String,
    ): String = clase.getMethod(metodo).getAnnotation(Scheduled::class.java).cron
}
