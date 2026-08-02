package pe.quantum.crm.domain.prospeccion

import pe.quantum.crm.domain.prospeccion.dto.ProspeccionItemDto
import pe.quantum.crm.domain.prospeccion.dto.ResumenProspeccionDto
import pe.quantum.crm.shared.Paginado
import pe.quantum.crm.shared.security.UsuarioActual

/** Interfaz publica del modulo prospeccion. */
interface ProspeccionService {
    /**
     * Empresas en prospeccion con hitos y actividad, ordenadas por
     * `checkpoints DESC, dias_sin_actividad DESC` (contrato §16).
     */
    fun listar(
        usuario: UsuarioActual,
        page: Int?,
        perPage: Int?,
    ): Paginado<ProspeccionItemDto>

    /** Resumen para el panel de inicio (contrato §17). */
    fun resumen(usuario: UsuarioActual): ResumenProspeccionDto
}
