package pe.quantum.crm.domain.financiadoras

import pe.quantum.crm.domain.financiadoras.dto.ActualizarFinanciadoraRequest
import pe.quantum.crm.domain.financiadoras.dto.CrearFinanciadoraRequest
import pe.quantum.crm.domain.financiadoras.dto.FinanciadoraDto

/** Interfaz publica del modulo financiadoras. */
interface FinanciadoraService {
    fun listar(): List<FinanciadoraDto>

    fun crear(request: CrearFinanciadoraRequest): FinanciadoraDto

    fun actualizar(
        id: Long,
        request: ActualizarFinanciadoraRequest,
    ): FinanciadoraDto

    /** La financiadora con `es_default = true` (reglas_negocio.md §9.1). */
    fun default(): FinanciadoraDto

    fun porId(id: Long): FinanciadoraDto

    fun porIds(ids: Collection<Long>): Map<Long, FinanciadoraDto>
}
