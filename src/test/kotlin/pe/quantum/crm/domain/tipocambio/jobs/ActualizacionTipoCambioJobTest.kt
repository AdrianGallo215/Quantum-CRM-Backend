package pe.quantum.crm.domain.tipocambio.jobs

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import pe.quantum.crm.domain.tipocambio.TipoCambioService

class ActualizacionTipoCambioJobTest {
    private val tipoCambioService = mockk<TipoCambioService>()
    private val job = ActualizacionTipoCambioJob(tipoCambioService)

    @Test
    fun `invoca al service una vez`() {
        every { tipoCambioService.actualizarDesdeSunat() } returns true

        job.ejecutar()

        verify(exactly = 1) { tipoCambioService.actualizarDesdeSunat() }
    }

    /**
     * Una excepcion no capturada en un @Scheduled mata las ejecuciones
     * siguientes del mismo job en el scheduler de Spring: el try/catch del
     * job es lo que evita eso.
     */
    @Test
    fun `si el service lanza una excepcion, el job la captura y no la propaga`() {
        every { tipoCambioService.actualizarDesdeSunat() } throws RuntimeException("SUNAT no responde")

        job.ejecutar()

        verify(exactly = 1) { tipoCambioService.actualizarDesdeSunat() }
    }

    /**
     * No hay precedente de verificacion de cron por reflexion en
     * domain/notificaciones/jobs (LimpiezaNotificacionesJobTest,
     * RecordatorioJobTest solo ejercitan el metodo). Se verifica leyendo el
     * codigo fuente del job directamente, comparando el string del cron.
     */
    @Test
    fun `el cron es 14 30 UTC diario, 09_30 Lima`() {
        val fuente =
            java.io.File(
                "src/main/kotlin/pe/quantum/crm/domain/tipocambio/jobs/ActualizacionTipoCambioJob.kt",
            ).readText()

        org.assertj.core.api.Assertions.assertThat(fuente).contains("""@Scheduled(cron = "0 30 14 * * *")""")
    }
}
