package pe.quantum.crm.config

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.ZoneId

/**
 * La garantia de UTC en las columnas TIMESTAMP depende solo de `ENV TZ=UTC` en el
 * Dockerfile (hallazgo [Medio] G.3). Esta guarda convierte una JVM fuera de UTC en
 * un fallo de arranque inmediato en vez de un corruptor de datos silencioso.
 */
class ZonaHorariaGuardTest {
    @Test
    fun `una JVM en UTC pasa la comprobacion`() {
        assertThat(ZonaHorariaGuard.esUtc(ZoneId.of("UTC"))).isTrue()
    }

    @Test
    fun `una JVM en America Lima no pasa la comprobacion`() {
        assertThat(ZonaHorariaGuard.esUtc(ZoneId.of("America/Lima"))).isFalse()
    }

    /**
     * El mensaje de error es lo unico verificable sin depender de la zona real de
     * la maquina que corre el test: `verificar()` usa `ZoneId.systemDefault()`, que
     * no se puede fijar de forma fiable en un test unitario sin reiniciar la JVM.
     */
    @Test
    fun `el mensaje de error nombra la zona detectada`() {
        assertThat(ZonaHorariaGuard.mensajeError(ZoneId.of("America/Lima"))).contains("America/Lima")
    }

    @Test
    fun `verificar no lanza cuando exigirUtc esta desactivado`() {
        val guard = ZonaHorariaGuard(exigirUtc = false)

        guard.verificar()
    }
}
