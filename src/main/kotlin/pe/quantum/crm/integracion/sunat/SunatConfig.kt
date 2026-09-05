package pe.quantum.crm.integracion.sunat

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.client.SimpleClientHttpRequestFactory
import org.springframework.web.client.RestClient
import java.time.Duration

/**
 * Cliente HTTP de la integracion con SUNAT.
 *
 * Se construye aqui, y no dentro del `Impl`, por el mismo motivo que en
 * [pe.quantum.crm.integracion.drive.DriveConfig]: el servicio recibe un cliente
 * ya armado y asi el test puede pasarle uno enchufado a `MockRestServiceServer`
 * sin que ninguna llamada salga a la red.
 *
 * Los timeouts son explicitos: sin ellos una conexion colgada con el proveedor
 * retiene el hilo del job indefinidamente.
 */
@Configuration
class SunatConfig {
    @Bean("sunatRestClient")
    fun sunatRestClient(propiedades: SunatProperties): RestClient {
        val timeoutMs = Duration.ofSeconds(propiedades.timeoutSegundos).toMillis().toInt()
        val factory =
            SimpleClientHttpRequestFactory().apply {
                setConnectTimeout(timeoutMs)
                setReadTimeout(timeoutMs)
            }
        return RestClient.builder().requestFactory(factory).build()
    }
}
