package pe.quantum.crm.integracion.drive

import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport
import com.google.api.client.http.HttpRequestInitializer
import com.google.api.client.http.javanet.NetHttpTransport
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.drive.Drive
import com.google.api.services.drive.DriveScopes
import com.google.auth.http.HttpCredentialsAdapter
import com.google.auth.oauth2.GoogleCredentials
import com.google.auth.oauth2.ServiceAccountCredentials
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Primary
import java.io.ByteArrayInputStream
import java.util.Base64

/**
 * Construye el cliente de Google Drive autenticado como Service Account.
 *
 * El JSON de credenciales llega en base64 por variable de entorno y se decodifica
 * en memoria: nunca se escribe ni se lee de disco.
 */
@Configuration
class DriveConfig {
    @Bean
    fun googleCredentials(propiedades: DriveProperties): GoogleCredentials {
        val json =
            try {
                Base64.getDecoder().decode(propiedades.credentialsBase64.trim())
            } catch (ex: IllegalArgumentException) {
                throw IllegalStateException(
                    "GOOGLE_DRIVE_CREDENTIALS_BASE64 no es base64 valido. " +
                        "Regenerar con: base64 -w0 <service-account>.json",
                    ex,
                )
            }
        // Scope `drive` completo: el Service Account solo es miembro de la unidad
        // compartida del CRM, asi que su alcance real ya esta acotado por permisos.
        // `drive.file` no basta: no permite operar dentro de una carpeta raiz que
        // el Service Account no creo.
        return ServiceAccountCredentials
            .fromStream(ByteArrayInputStream(json))
            .createScoped(listOf(DriveScopes.DRIVE))
    }

    /** Un unico transporte (y por tanto un unico pool HTTP) para los dos clientes. */
    @Bean
    fun driveHttpTransport(): NetHttpTransport = GoogleNetHttpTransport.newTrustedTransport()

    /**
     * Cliente por defecto: subidas y listados. Timeout de lectura largo, porque
     * subir un archivo grande tarda de verdad. Ninguna de esas rutas corre dentro
     * de una transaccion de base de datos.
     */
    @Bean
    @Primary
    fun googleDrive(
        credentials: GoogleCredentials,
        transporte: NetHttpTransport,
        propiedades: DriveProperties,
    ): Drive = cliente(credentials, transporte, propiedades, propiedades.readTimeoutMs)

    /**
     * Cliente exclusivo de la creacion de carpetas, con un timeout de lectura muy
     * inferior. Crear una carpeta es una llamada de metadatos, y es la unica
     * operacion de Drive que todavia puede quedar dentro de una transaccion
     * (POST /oportunidades: contrato_api.md §8 exige que sin carpeta no haya
     * oportunidad, y el nombre necesita el id que solo existe tras el insert).
     * Con el timeout de las subidas, una sola caida de Drive retenia conexiones de
     * Hikari hasta 2 minutos y agotaba el pool.
     */
    @Bean
    fun googleDriveCarpetas(
        credentials: GoogleCredentials,
        transporte: NetHttpTransport,
        propiedades: DriveProperties,
    ): Drive = cliente(credentials, transporte, propiedades, propiedades.folderReadTimeoutMs)

    private fun cliente(
        credentials: GoogleCredentials,
        transporte: NetHttpTransport,
        propiedades: DriveProperties,
        readTimeoutMs: Int,
    ): Drive {
        val credencialesHttp = HttpCredentialsAdapter(credentials)
        // Timeouts explicitos: sin ellos una conexion colgada a Drive retiene el
        // hilo (y, donde aun haya transaccion, su conexion a la BD) indefinidamente.
        val inicializador =
            HttpRequestInitializer { request ->
                credencialesHttp.initialize(request)
                request.connectTimeout = propiedades.connectTimeoutMs
                request.readTimeout = readTimeoutMs
            }
        return Drive
            .Builder(transporte, GsonFactory.getDefaultInstance(), inicializador)
            .setApplicationName(propiedades.applicationName)
            .build()
    }
}
