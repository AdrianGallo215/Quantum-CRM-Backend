package pe.quantum.crm.domain.eventos

import pe.quantum.crm.domain.eventos.dto.ActualizarEventoRequest
import pe.quantum.crm.domain.eventos.dto.CrearEventoRequest
import pe.quantum.crm.domain.eventos.dto.EventoDto
import pe.quantum.crm.domain.eventos.dto.EventoOcurridoDto
import pe.quantum.crm.domain.eventos.dto.EventoRecordatorioProyeccion
import pe.quantum.crm.domain.eventos.dto.EventosAgrupadosDto
import pe.quantum.crm.domain.eventos.dto.MarcarDescartadoRequest
import pe.quantum.crm.domain.eventos.dto.MarcarOcurridoRequest
import pe.quantum.crm.shared.security.UsuarioActual

/** Interfaz publica del modulo eventos. */
interface EventoService {
    fun listarPorOportunidad(
        idOportunidad: Long,
        usuario: UsuarioActual,
    ): EventosAgrupadosDto

    fun crearEnOportunidad(
        idOportunidad: Long,
        request: CrearEventoRequest,
        usuario: UsuarioActual,
    ): EventoDto

    /** Evento de prospeccion: vinculado a la empresa, sin oportunidad (V21). */
    fun crearEnEmpresa(
        idEmpresa: Long,
        request: CrearEventoRequest,
        usuario: UsuarioActual,
    ): EventoDto

    fun listarPorEmpresa(
        idEmpresa: Long,
        usuario: UsuarioActual,
    ): EventosAgrupadosDto

    /**
     * Marca el evento como ocurrido. NO cambia el estado de la oportunidad:
     * si `dispara_cambio_estado`, devuelve la sugerencia (reglas §5.3).
     */
    fun marcarOcurrido(
        id: Long,
        request: MarcarOcurridoRequest,
        usuario: UsuarioActual,
    ): EventoOcurridoDto

    fun marcarDescartado(
        id: Long,
        request: MarcarDescartadoRequest,
        usuario: UsuarioActual,
    ): EventoDto

    /** Solo eventos pendientes (contrato §11). */
    fun actualizar(
        id: Long,
        request: ActualizarEventoRequest,
        usuario: UsuarioActual,
    ): EventoDto

    /** Para el job de recordatorios (notificaciones): eventos pendientes con fecha_estimada. */
    fun pendientesParaRecordatorio(): List<EventoRecordatorioProyeccion>
}
