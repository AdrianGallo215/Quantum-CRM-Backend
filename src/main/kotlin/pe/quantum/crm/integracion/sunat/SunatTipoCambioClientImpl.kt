package pe.quantum.crm.integracion.sunat

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.stereotype.Service
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientResponseException
import java.math.BigDecimal
import java.time.LocalDate
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Implementacion contra Decolecta (`GET /v1/tipo-cambio/sunat`).
 *
 * Tres invariantes que no se pueden relajar:
 *
 * 1. **No se envia el parametro `date`.** Sin el, el proveedor devuelve el valor
 *    vigente del dia y el backend nunca tiene que calcular "que dia es hoy": la
 *    app corre en UTC y Lima es UTC-5. La fecha se toma del campo `date` de la
 *    respuesta.
 * 2. **Nunca coma flotante.** Los precios llegan como String y se convierten con
 *    `BigDecimal(String)`, la unica ruta que no puede perder precision.
 * 3. **Nunca propaga una excepcion.** Cualquier fallo (timeout, 401, 404, 5xx,
 *    JSON inesperado) se registra en WARN y devuelve null, porque el llamador
 *    hace fallback al ultimo valor guardado sin error visible.
 *
 * La API key viaja solo en la cabecera `Authorization` y jamas se registra.
 */
@Service
class SunatTipoCambioClientImpl(
    private val propiedades: SunatProperties,
    @Qualifier("sunatRestClient") private val restClient: RestClient,
) : SunatTipoCambioClient {
    private val log = LoggerFactory.getLogger(SunatTipoCambioClientImpl::class.java)

    /** Evita repetir el aviso de "integracion apagada" en cada ejecucion del job. */
    private val avisoApagadaEmitido = AtomicBoolean(false)

    @Suppress("TooGenericExceptionCaught")
    override fun consultar(): TipoCambioExterno? {
        if (propiedades.url.isBlank() || propiedades.apiKey.isBlank()) {
            if (avisoApagadaEmitido.compareAndSet(false, true)) {
                log.info(
                    "Integracion de tipo de cambio desactivada: falta app.sunat.url o la variable " +
                        "de entorno DECOLECTA_API_KEY. No se consultara a SUNAT.",
                )
            }
            return null
        }
        return try {
            mapear(pedir())
        } catch (ex: RestClientResponseException) {
            // Solo el codigo de estado: el cuerpo del proveedor no aporta nada y la
            // cabecera con la API key no debe acabar en ningun log.
            log.warn("SUNAT respondio {}: {}", ex.statusCode.value(), diagnostico(ex))
            null
        } catch (ex: Exception) {
            // Red, timeout, JSON inesperado, fecha o precio no parseables. Se traga a
            // proposito: el llamador hace fallback al ultimo tipo de cambio guardado.
            log.warn("Fallo al consultar el tipo de cambio de SUNAT: {}", ex.toString())
            null
        }
    }

    private fun pedir(): RespuestaProveedor? =
        restClient
            .get()
            // Sin parametro `date`: el proveedor devuelve el valor vigente del dia.
            .uri(propiedades.url)
            .header(HttpHeaders.AUTHORIZATION, "Bearer ${propiedades.apiKey}")
            .accept(MediaType.APPLICATION_JSON)
            .retrieve()
            .body(RespuestaProveedor::class.java)

    private fun mapear(respuesta: RespuestaProveedor?): TipoCambioExterno? {
        val fecha = respuesta?.date
        val compra = respuesta?.buyPrice
        val venta = respuesta?.sellPrice
        if (fecha.isNullOrBlank() || compra.isNullOrBlank() || venta.isNullOrBlank()) {
            log.warn("Respuesta de SUNAT sin los campos date/buy_price/sell_price esperados; se descarta.")
            return null
        }
        return TipoCambioExterno(
            fecha = LocalDate.parse(fecha),
            compra = BigDecimal(compra),
            venta = BigDecimal(venta),
        )
    }

    private fun diagnostico(ex: RestClientResponseException): String =
        when {
            // El proveedor devuelve el mismo 401 para clave ausente, clave invalida y
            // cuota mensual agotada, asi que el mensaje tiene que nombrar los tres casos.
            HttpStatus.UNAUTHORIZED.isSameCodeAs(ex.statusCode) ->
                "API key ausente, invalida o cuota agotada. Revisar la variable de entorno DECOLECTA_API_KEY."
            HttpStatus.NOT_FOUND.isSameCodeAs(ex.statusCode) ->
                "no hay tipo de cambio publicado para la fecha consultada."
            else -> "respuesta no utilizable del proveedor."
        }
}

/**
 * Cuerpo del proveedor. `buy_price` y `sell_price` llegan como String y se
 * mapean como String a proposito: asi Jackson no puede pasarlos por un tipo de
 * coma flotante y perder precision.
 * Los nombres son explicitos porque este cliente no usa el ObjectMapper de la
 * app (que va en SNAKE_CASE) sino el del RestClient.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
private data class RespuestaProveedor(
    @param:JsonProperty("buy_price") val buyPrice: String? = null,
    @param:JsonProperty("sell_price") val sellPrice: String? = null,
    @param:JsonProperty("date") val date: String? = null,
)
