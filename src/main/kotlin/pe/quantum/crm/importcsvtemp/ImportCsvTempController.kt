package pe.quantum.crm.importcsvtemp

import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.multipart.MultipartFile
import pe.quantum.crm.importcsvtemp.dto.ImportEmpresasResultDto
import pe.quantum.crm.shared.ApiResponse
import pe.quantum.crm.shared.security.UsuarioActualProvider

/**
 * Endpoint temporal de importación masiva de empresas por CSV (módulo desechable,
 * ver docs/superpowers/specs/2026-07-09-import-csv-temp-empresas-design.md).
 * No forma parte de contrato_api.md.
 */
@RestController
@RequestMapping("/api/v1/import-csv-temp/empresas")
class ImportCsvTempController(
    private val importCsvTempService: ImportCsvTempService,
    private val usuarioProvider: UsuarioActualProvider,
) {
    @PostMapping
    fun importarEmpresas(
        @RequestParam("file") file: MultipartFile,
    ): ApiResponse<ImportEmpresasResultDto> {
        val usuario = usuarioProvider.actual()
        return ApiResponse.ok(importCsvTempService.importarEmpresas(file, usuario))
    }
}
