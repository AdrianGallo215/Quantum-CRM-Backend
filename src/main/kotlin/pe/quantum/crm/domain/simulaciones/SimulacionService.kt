package pe.quantum.crm.domain.simulaciones

import pe.quantum.crm.domain.simulaciones.dto.ActualizarSimulacionRequest
import pe.quantum.crm.domain.simulaciones.dto.BifurcarSimulacionRequest
import pe.quantum.crm.domain.simulaciones.dto.CrearSimulacionRequest
import pe.quantum.crm.domain.simulaciones.dto.CronogramaDto
import pe.quantum.crm.domain.simulaciones.dto.EventoHistorialDto
import pe.quantum.crm.domain.simulaciones.dto.ItemParaCuota
import pe.quantum.crm.domain.simulaciones.dto.SimulacionDto
import pe.quantum.crm.domain.simulaciones.dto.SimulacionFiltros
import pe.quantum.crm.shared.Paginado
import pe.quantum.crm.shared.security.UsuarioActual
import java.math.BigDecimal
import java.time.LocalDateTime

/**
 * API publica del modulo de simulaciones del financiamiento propio de Quantum
 * (`reglas_simulaciones.md`). El cronograma **no se persiste** (§4): se
 * recalcula al vuelo en cada lectura. `cuota_final` es el unico derivado
 * persistido y **nunca** se acepta del cliente (restriccion 2 del encargo).
 *
 * `TooManyFunctions`: las 10 operaciones del CRUD e historial del modulo mas
 * [cuotaQuantumPorItems], que no es una operacion de usuario sino la API
 * publica que consume `oportunidades` para §6.2 (D56). Mismo precedente que
 * `EmpleadoService`: partir la interfaz solo dispersaria el contrato del modulo.
 */
@Suppress("TooManyFunctions")
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

    /**
     * Cuota Quantum por item (§6.2): la `cuota_final` de su simulacion
     * principal si la tiene, o el calculo efimero de §6.1 si no (sin
     * persistir nada).
     *
     * SIN chequeo de visibilidad, igual que
     * `OportunidadItemService.datosParaSimulacion` en el sentido contrario
     * (D32): quien llama —`oportunidades`— ya filtro que el usuario alcanza
     * esas oportunidades. Aplicar aqui la regla de §10 le quitaria la cuota al
     * `vendedor`, que SI debe verla en su propia oportunidad.
     *
     * Un item ausente del mapa es un item cuya cuota no se pudo calcular
     * (incompleto, o parametros por defecto invalidos para su precio, D55).
     * Eso NO es un error: es "no hay cuota que mostrar".
     */
    fun cuotaQuantumPorItems(items: Collection<ItemParaCuota>): Map<Long, BigDecimal>

    /**
     * Purga de §5: hard delete de las simulaciones sin item creadas antes de
     * [limite]. Registra el evento `eliminada` con snapshot completo ANTES de
     * borrar cada una — el log sobrevive (`id_simulacion` no tiene FK, por eso
     * mismo). Devuelve cuantas elimino.
     *
     * Sin `UsuarioActual`: la ejecuta un job programado, y `created_by` del
     * evento queda en null (decision D60 de
     * plan-13-mapa-cierre-simulaciones.md).
     */
    fun purgarHuerfanas(limite: LocalDateTime): Int

    /**
     * Aviso de §5: notifica al creador de cada simulacion huerfana cuya
     * antiguedad cruza en esta corrida la frontera de los 3 dias previos al
     * borrado — `created_at` en `(desde, hasta]`. Devuelve cuantas notifico.
     *
     * Sin `UsuarioActual`: la ejecuta un job programado, y `notificar` recibe
     * `idActor = null` para que no se excluya a nadie del set de destinatarios
     * (decision D58 de plan-13-mapa-cierre-simulaciones.md).
     */
    fun avisarHuerfanasPorExpirar(
        desde: LocalDateTime,
        hasta: LocalDateTime,
    ): Int

    companion object {
        /**
         * Divisor de `cuota_diaria` (§6.1/§6.2), espejo publico de
         * `DefaultsSimulacion.DIAS_TRABAJADOS` para que `oportunidades` pueda
         * usarlo sin cruzar hacia un `object` interno del modulo (K34).
         */
        const val DIAS_TRABAJADOS_POR_DEFECTO = DefaultsSimulacion.DIAS_TRABAJADOS
    }
}
