package pe.quantum.crm.integracion.drive

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream

class StreamAcotadoTest {
    @Test
    fun `deja pasar un contenido dentro del tope`() {
        val stream = StreamAcotado(ByteArrayInputStream("hola".toByteArray()), maxBytes = 10)

        assertThat(stream.readBytes()).asString().isEqualTo("hola")
    }

    @Test
    fun `corta apenas se supera el tope leyendo por bloques`() {
        val stream = StreamAcotado(ByteArrayInputStream(ByteArray(1_000)), maxBytes = 100)

        assertThatThrownBy { stream.readBytes() }
            .isInstanceOf(ArchivoDemasiadoGrandeException::class.java)
    }

    @Test
    fun `corta tambien leyendo byte a byte`() {
        val stream = StreamAcotado(ByteArrayInputStream(ByteArray(10)), maxBytes = 4)

        assertThatThrownBy { repeat(10) { stream.read() } }
            .isInstanceOf(ArchivoDemasiadoGrandeException::class.java)
    }

    @Test
    fun `el tope es exclusivo - exactamente maxBytes pasa`() {
        val stream = StreamAcotado(ByteArrayInputStream(ByteArray(100)), maxBytes = 100)

        assertThat(stream.readBytes()).hasSize(100)
    }
}
