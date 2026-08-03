package pe.quantum.crm.domain.catalogoeventos

import pe.quantum.crm.domain.catalogoeventos.dto.ActualizarCatalogoEventoRequest
import pe.quantum.crm.domain.catalogoeventos.dto.CatalogoEventoDto
import pe.quantum.crm.domain.catalogoeventos.dto.CrearCatalogoEventoRequest
import pe.quantum.crm.shared.enums.EstadoOportunidad

/** Interfaz publica del modulo catalogo de eventos. */
interface CatalogoEventoService {
    fun listar(etapaAsociada: EstadoOportunidad?): List<CatalogoEventoDto>

    fun crear(request: CrearCatalogoEventoRequest): CatalogoEventoDto

    fun actualizar(
        id: Long,
        request: ActualizarCatalogoEventoRequest,
    ): CatalogoEventoDto

    fun porId(id: Long): CatalogoEventoDto

    /** Todos los eventos del catalogo indexados por id (para el modulo eventos). */
    fun todosPorId(): Map<Long, CatalogoEventoDto>

    /** Hitos de prospeccion en orden (reglas_negocio.md §10.3). */
    fun hitosProspeccion(): List<CatalogoEventoDto>
}
