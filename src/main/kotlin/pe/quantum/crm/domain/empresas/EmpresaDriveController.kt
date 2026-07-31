package pe.quantum.crm.domain.empresas

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
 * Carpeta y documentos de una empresa en Google Drive (contrato_api.md §8).
 *
 * La subida NUNCA materializa el archivo en el servidor: ver
 * `DriveMultipartUploader` y `DriveUploadMultipartResolver`, sin el cual Tomcat
 * volcaria el archivo a un temporal en disco antes de llegar aqui.
 */
@RestController
@RequestMapping("/api/v1/empresas/{id}")
class EmpresaDriveController(
    private val empresaService: EmpresaService,
    private val driveMultipartUploader: DriveMultipartUploader,
    private val usuarioProvider: UsuarioActualProvider,
) {
    @GetMapping("/archivos")
    fun listar(
        @PathVariable id: Long,
    ): ApiResponse<List<DriveArchivoSubido>> = ApiResponse.ok(empresaService.archivosDrive(id, usuarioProvider.actual()))

    @PostMapping("/archivos")
    @ResponseStatus(HttpStatus.CREATED)
    fun subir(
        @PathVariable id: Long,
        request: HttpServletRequest,
    ): ApiResponse<DriveArchivoSubido> {
        // Visibilidad y carpeta ANTES de leer un solo byte: una empresa ajena
        // responde 404 sin haber transferido nada (IDOR, SECURITY §3.2).
        val carpeta = empresaService.asegurarCarpetaDrive(id, usuarioProvider.actual())
        return ApiResponse.ok(driveMultipartUploader.subirPrimerArchivo(request, carpeta))
    }

    /** Idempotente: si la empresa ya tiene carpeta, la devuelve sin tocar Drive. */
    @PostMapping("/carpeta-drive")
    fun crearCarpeta(
        @PathVariable id: Long,
    ): ApiResponse<CarpetaDriveDto> = ApiResponse.ok(CarpetaDriveDto(empresaService.asegurarCarpetaDrive(id, usuarioProvider.actual())))
}
