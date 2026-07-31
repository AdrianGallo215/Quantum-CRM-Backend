package pe.quantum.crm.domain.oportunidades

import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import pe.quantum.crm.integracion.drive.CarpetaDriveDto
import pe.quantum.crm.integracion.drive.DriveArchivoSubido
import pe.quantum.crm.integracion.drive.DriveMultipartUploader
import pe.quantum.crm.shared.ApiResponse
import pe.quantum.crm.shared.security.UsuarioActualProvider

/**
 * Carpeta y documentos de una oportunidad en Google Drive (contrato_api.md §10).
 *
 * La subida NUNCA materializa el archivo en el servidor: ver
 * `DriveMultipartUploader` y `DriveUploadMultipartResolver`, sin el cual Tomcat
 * volcaria el archivo a un temporal en disco antes de llegar aqui.
 */
@RestController
@RequestMapping("/api/v1/oportunidades/{id}")
class OportunidadDriveController(
    private val oportunidadService: OportunidadService,
    private val driveMultipartUploader: DriveMultipartUploader,
    private val usuarioProvider: UsuarioActualProvider,
) {
    @GetMapping("/archivos")
    fun listar(
        @PathVariable id: Long,
    ): ApiResponse<List<DriveArchivoSubido>> = ApiResponse.ok(oportunidadService.archivosDrive(id, usuarioProvider.actual()))

    @PostMapping("/archivos")
    @ResponseStatus(HttpStatus.CREATED)
    fun subir(
        @PathVariable id: Long,
        request: HttpServletRequest,
    ): ApiResponse<DriveArchivoSubido> {
        // Visibilidad y carpeta ANTES de leer un solo byte: una oportunidad ajena
        // responde 404 sin haber transferido nada (IDOR, SECURITY §3.2).
        val carpeta = oportunidadService.asegurarCarpetaDrive(id, usuarioProvider.actual())
        return ApiResponse.ok(driveMultipartUploader.subirPrimerArchivo(request, carpeta))
    }

    /** Idempotente: si la oportunidad ya tiene carpeta, la devuelve sin tocar Drive. */
    @PostMapping("/carpeta-drive")
    fun crearCarpeta(
        @PathVariable id: Long,
    ): ApiResponse<CarpetaDriveDto> =
        ApiResponse.ok(CarpetaDriveDto(oportunidadService.asegurarCarpetaDrive(id, usuarioProvider.actual())))
}
