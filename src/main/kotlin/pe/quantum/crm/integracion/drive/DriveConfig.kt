package pe.quantum.crm.integracion.drive

import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport
import com.google.api.client.http.HttpRequestInitializer
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.drive.Drive
import com.google.api.services.drive.DriveScopes
import com.google.auth.http.HttpCredentialsAdapter
import com.google.auth.oauth2.GoogleCredentials
import com.google.auth.oauth2.ServiceAccountCredentials
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
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

    @Bean
    fun googleDrive(
        credentials: GoogleCredentials,
        propiedades: DriveProperties,
    ): Drive {
        val credencialesHttp = HttpCredentialsAdapter(credentials)
        // Timeouts explicitos: sin ellos una conexion colgada a Drive retiene la
        // transaccion (y su conexion a la BD) de forma indefinida.
        val inicializador =
            HttpRequestInitializer { request ->
                credencialesHttp.initialize(request)
                request.connectTimeout = propiedades.connectTimeoutMs
                request.readTimeout = propiedades.readTimeoutMs
            }
        return Drive
            .Builder(
                GoogleNetHttpTransport.newTrustedTransport(),
                GsonFactory.getDefaultInstance(),
                inicializador,
            ).setApplicationName(propiedades.applicationName)
            .build()
    }
}
