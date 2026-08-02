package pe.quantum.crm.domain.modelos

import pe.quantum.crm.domain.modelos.dto.ActualizarModeloRequest
import pe.quantum.crm.domain.modelos.dto.CrearModeloRequest
import pe.quantum.crm.domain.modelos.dto.ModeloDto
import pe.quantum.crm.domain.modelos.dto.ModeloResumen

/** Interfaz publica del modulo modelos. */
interface ModeloService {
    fun listar(): List<ModeloDto>

    fun crear(request: CrearModeloRequest): ModeloDto

    fun actualizar(
        id: Long,
        request: ActualizarModeloRequest,
    ): ModeloDto

    /** Resumen (codigo + precio base) para el modulo de oportunidades. */
    fun resumen(id: Long): ModeloResumen

    fun resumenPorIds(ids: Collection<Long>): Map<Long, ModeloResumen>
}
