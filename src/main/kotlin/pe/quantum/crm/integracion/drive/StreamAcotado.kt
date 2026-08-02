package pe.quantum.crm.integracion.drive

import java.io.FilterInputStream
import java.io.InputStream

/**
 * Corta la lectura apenas se superan [maxBytes], sin acumular nada: cuenta lo que
 * pasa y aborta.
 *
 * El tope no se delega al parser multipart a proposito. Las excepciones de
 * Commons FileUpload extienden `IOException`, asi que el SDK de Drive las
 * envolveria y el cliente veria un 502 (Drive caido) en vez de un 413 (archivo
 * demasiado grande). [ArchivoDemasiadoGrandeException] es de runtime y atraviesa
 * el SDK intacta.
 */
class StreamAcotado(
    delegado: InputStream,
    private val maxBytes: Long,
) : FilterInputStream(delegado) {
    private var leidos = 0L

    override fun read(): Int {
        val byte = super.read()
        if (byte != -1) {
            contar(1)
        }
        return byte
    }

    override fun read(
        destino: ByteArray,
        desplazamiento: Int,
        longitud: Int,
    ): Int {
        val cantidad = super.read(destino, desplazamiento, longitud)
        if (cantidad > 0) {
            contar(cantidad.toLong())
        }
        return cantidad
    }

    private fun contar(cantidad: Long) {
        leidos += cantidad
        if (leidos > maxBytes) {
            throw ArchivoDemasiadoGrandeException(maxBytes)
        }
    }
}
