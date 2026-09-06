package pe.quantum.crm.domain.simulaciones

import pe.quantum.crm.domain.simulaciones.dto.ActualizarSimulacionRequest
import pe.quantum.crm.domain.simulaciones.dto.CrearSimulacionRequest
import pe.quantum.crm.domain.simulaciones.dto.CronogramaDto
import pe.quantum.crm.domain.simulaciones.dto.SimulacionDto
import pe.quantum.crm.domain.simulaciones.dto.SimulacionFiltros
import pe.quantum.crm.shared.Paginado
import pe.quantum.crm.shared.security.UsuarioActual

/**
 * API publica del modulo de simulaciones del financiamiento propio de Quantum
 * (`reglas_simulaciones.md`). El cronograma **no se persiste** (§4): se
 * recalcula al vuelo en cada lectura. `cuota_final` es el unico derivado
 * persistido y **nunca** se acepta del cliente (restriccion 2 del encargo).
 */
interface SimulacionService {
    fun crear(
        request: CrearSimulacionRequest,
        usuario: UsuarioActual,
    ): SimulacionDto

    /** IDOR: simulacion ajena → 404, nunca 403 (CLAUDE.md regla 14). */
    fun detalle(
        id: Long,
        usuario: UsuarioActual,
    ): SimulacionDto

    /** Listado del modulo. 403 para `vendedor`, `jdv` y `otro` (§10, decision D39 de plan-09-mapa-simulaciones-modulo.md). */
    @Suppress("LongParameterList") // Query params del contrato.
    fun listar(
        filtros: SimulacionFiltros,
        usuario: UsuarioActual,
        page: Int?,
        perPage: Int?,
        sort: String?,
        dir: String?,
    ): Paginado<SimulacionDto>

    /** `modo` distinto del actual → 409 `MODO_INMUTABLE` (§2, decision D36). */
    fun actualizar(
        id: Long,
        request: ActualizarSimulacionRequest,
        usuario: UsuarioActual,
    ): SimulacionDto

    fun eliminar(
        id: Long,
        usuario: UsuarioActual,
    )

    /** Cronograma recalculado al vuelo; nunca persistido (§4, decision D40). */
    fun cronograma(
        id: Long,
        usuario: UsuarioActual,
    ): CronogramaDto
}
