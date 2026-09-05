package pe.quantum.crm.integracion.sunat

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Configuracion de la integracion con el proveedor del tipo de cambio SUNAT
 * (`app.sunat.*`, mismo prefijo `app.*` que el resto de integraciones del repo,
 * ver `DriveProperties`). El proveedor es Decolecta: `GET /v1/tipo-cambio/sunat`
 * con cabecera `Authorization: Bearer <api-key>`.
 *
 * La API key es un secreto (CLAUDE.md regla 13): llega SIEMPRE por variable de
 * entorno `DECOLECTA_API_KEY` y no se escribe en ningun archivo versionado ni en
 * ningun log.
 */
@ConfigurationProperties(prefix = "app.sunat")
data class SunatProperties(
    /** Endpoint del proveedor. En blanco desactiva la integracion. */
    val url: String,
    /**
     * API key del proveedor, desde `DECOLECTA_API_KEY`. En blanco desactiva la
     * integracion: el cliente devuelve null sin llegar a hacer la llamada.
     */
    val apiKey: String,
    /**
     * Timeout de conexion y de lectura. Corto a proposito: lo invoca un job
     * diario y ninguna ruta de usuario espera por esta llamada.
     */
    val timeoutSegundos: Long = DEFAULT_TIMEOUT_SEGUNDOS,
) {
    companion object {
        const val DEFAULT_TIMEOUT_SEGUNDOS = 10L
    }
}
