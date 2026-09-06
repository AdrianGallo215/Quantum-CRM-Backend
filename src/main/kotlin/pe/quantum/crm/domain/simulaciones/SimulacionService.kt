package pe.quantum.crm.domain.simulaciones

import pe.quantum.crm.domain.simulaciones.dto.ActualizarSimulacionRequest
import pe.quantum.crm.domain.simulaciones.dto.BifurcarSimulacionRequest
import pe.quantum.crm.domain.simulaciones.dto.CrearSimulacionRequest
import pe.quantum.crm.domain.simulaciones.dto.CronogramaDto
import pe.quantum.crm.domain.simulaciones.dto.EventoHistorialDto
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

    /** Historial con diff, ventana de 7 dias / 15 versiones (§7.2). */
    fun historial(
        id: Long,
        usuario: UsuarioActual,
    ): List<EventoHistorialDto>

    /** Restaura una version de la ventana de 7 dias (§7.2). Recalcula `cuota_final`. */
    fun restaurar(
        id: Long,
        idEventoLog: Long,
        usuario: UsuarioActual,
    ): SimulacionDto

    /** §6.3: cambia manualmente cual es la simulacion principal del item. */
    fun marcarPrincipal(
        id: Long,
        usuario: UsuarioActual,
    ): SimulacionDto

    /**
     * §7.3 "Guardar como Nueva Simulacion": fila NUEVA con
     * `id_simulacion_origen` apuntando a [id]. Unica via autorizada para
     * cambiar de `modo` (§2, hallazgo K27 de
     * plan-11-mapa-historial-calculadora.md).
     */
    fun bifurcar(
        id: Long,
        request: BifurcarSimulacionRequest,
        usuario: UsuarioActual,
    ): SimulacionDto
}
