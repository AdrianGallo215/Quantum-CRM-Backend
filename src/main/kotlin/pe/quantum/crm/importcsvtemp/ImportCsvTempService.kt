package pe.quantum.crm.importcsvtemp

import org.springframework.web.multipart.MultipartFile
import pe.quantum.crm.importcsvtemp.dto.ImportEmpresasResultDto
import pe.quantum.crm.shared.security.UsuarioActual

/** Interfaz del módulo temporal de importación de empresas por CSV. */
interface ImportCsvTempService {
    fun importarEmpresas(
        archivo: MultipartFile,
        usuario: UsuarioActual,
    ): ImportEmpresasResultDto
}
