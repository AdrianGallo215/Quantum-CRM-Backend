package pe.quantum.crm.integracion.sunat

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.test.web.client.match.MockRestRequestMatchers.header
import org.springframework.test.web.client.match.MockRestRequestMatchers.method
import org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo
import org.springframework.test.web.client.response.MockRestResponseCreators.withStatus
import org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess
import org.springframework.web.client.RestClient
import java.time.LocalDate

/**
 * Ninguna llamada de red real: todo pasa por [MockRestServiceServer].
 * La API key de los tests es un literal ficticio; la real solo existe en `.env`.
 */
class SunatTipoCambioClientImplTest {
    private companion object {
        const val URL = "https://api.decolecta.com/v1/tipo-cambio/sunat"
        const val API_KEY_FICTICIA = "sk_test"
        const val JSON_OK =
            """{"buy_price":"3.357","sell_price":"3.367","base_currency":"USD","quote_currency":"PEN","date":"2026-09-01"}"""
    }

    private val builder: RestClient.Builder = RestClient.builder()
    private val server: MockRestServiceServer = MockRestServiceServer.bindTo(builder).build()

    private fun cliente(
        url: String = URL,
        apiKey: String = API_KEY_FICTICIA,
    ) = SunatTipoCambioClientImpl(
        SunatProperties(url = url, apiKey = apiKey),
        builder.build(),
    )

    private fun esperarPeticion() =
        server
            .expect(requestTo(URL))
            .andExpect(method(HttpMethod.GET))
            .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer $API_KEY_FICTICIA"))

    @Test
    fun `con la api key en blanco devuelve null sin llamar al proveedor`() {
        val resultado = cliente(apiKey = "").consultar()

        assertThat(resultado).isNull()
        // Sin expectativas registradas, verify() falla si hubo cualquier peticion.
        server.verify()
    }

    @Test
    fun `con la url en blanco devuelve null sin llamar al proveedor`() {
        val resultado = cliente(url = " ").consultar()

        assertThat(resultado).isNull()
        server.verify()
    }

    @Test
    fun `mapea la respuesta 200 a TipoCambioExterno`() {
        esperarPeticion().andRespond(withSuccess(JSON_OK, MediaType.APPLICATION_JSON))

        val resultado = cliente().consultar()

        assertThat(resultado).isNotNull
        assertThat(resultado!!.fecha).isEqualTo(LocalDate.of(2026, 9, 1))
        // compareTo, no equals: 3.357 y 3.3570 son el mismo numero con distinta escala.
        assertThat(resultado.compra).isEqualByComparingTo("3.357")
        assertThat(resultado.venta).isEqualByComparingTo("3.367")
        server.verify()
    }

    @Test
    fun `la peticion sale sin parametro date y con la cabecera Authorization`() {
        esperarPeticion().andRespond(withSuccess(JSON_OK, MediaType.APPLICATION_JSON))

        cliente().consultar()

        // requestTo(URL) es coincidencia exacta de URI: si se enviara ?date=... fallaria.
        server.verify()
    }

    @Test
    fun `un 401 devuelve null sin lanzar excepcion`() {
        esperarPeticion().andRespond(
            withStatus(HttpStatus.UNAUTHORIZED)
                .contentType(MediaType.APPLICATION_JSON)
                .body("""{"error":"Apikey Required / Limit Exceeded"}"""),
        )

        assertThat(cliente().consultar()).isNull()
        server.verify()
    }

    @Test
    fun `un 404 devuelve null sin lanzar excepcion`() {
        esperarPeticion().andRespond(
            withStatus(HttpStatus.NOT_FOUND)
                .contentType(MediaType.APPLICATION_JSON)
                .body("""{"message":"Not found"}"""),
        )

        assertThat(cliente().consultar()).isNull()
        server.verify()
    }

    @Test
    fun `un 500 devuelve null sin lanzar excepcion`() {
        esperarPeticion().andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR))

        assertThat(cliente().consultar()).isNull()
        server.verify()
    }

    @Test
    fun `una respuesta sin buy_price devuelve null sin lanzar excepcion`() {
        esperarPeticion().andRespond(
            withSuccess(
                """{"sell_price":"3.367","date":"2026-09-01"}""",
                MediaType.APPLICATION_JSON,
            ),
        )

        assertThat(cliente().consultar()).isNull()
        server.verify()
    }

    @Test
    fun `una fecha no parseable devuelve null sin lanzar excepcion`() {
        esperarPeticion().andRespond(
            withSuccess(
                """{"buy_price":"3.357","sell_price":"3.367","date":"01-09-2026"}""",
                MediaType.APPLICATION_JSON,
            ),
        )

        assertThat(cliente().consultar()).isNull()
        server.verify()
    }
}
