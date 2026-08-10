package pe.quantum.crm.domain.notificaciones

import pe.quantum.crm.domain.notificaciones.dto.NotificacionDto
import pe.quantum.crm.shared.security.UsuarioActual

/**
 * Interfaz publica del modulo notificaciones. `notificar` es el UNICO efecto
 * secundario que otros modulos invocan (dentro de su propia transaccion) para
 * generar notificaciones (docs/superpowers/specs/2026-07-09-notificaciones-in-app-design.md).
 */
interface NotificacionService {
    /**
     * Crea una notificacion por cada destinatario. Excluye `idActor` del set
     * (nadie se notifica de su propia accion); si `idActor` es null (job de
     * sistema) no excluye a nadie. No hace nada si el set resultante queda vacio.
     */
    @Suppress("LongParameterList") // Firma publica del modulo: cada parametro es necesario para armar la notificacion.
    fun notificar(
        destinatarios: Set<Long>,
        idActor: Long?,
        tipo: TipoNotificacion,
        mensaje: String,
        entidadTipo: EntidadNotificacion,
        entidadId: Long,
    )

    /**
     * Borra el registro de dedup de recordatorios de una tarea o evento.
     *
     * Lo llama el modulo dueño al reprogramarla. La clave de dedup es
     * `(origen, id_origen, umbral)` y NO incluye la fecha, asi que sin este
     * reinicio una tarea o evento ya recordado no volveria a recordarse nunca
     * tras moverle la fecha.
     */
    fun reiniciarRecordatorios(
        origen: OrigenRecordatorio,
        idOrigen: Long,
    )

    fun contarNoLeidas(usuario: UsuarioActual): Long

    /** Ultimas 20 notificaciones (leidas + no leidas) del usuario, mas recientes primero. */
    fun listar(usuario: UsuarioActual): List<NotificacionDto>

    /** 404 NO_ENCONTRADO si no existe o no pertenece al usuario. */
    fun marcarLeida(
        id: Long,
        usuario: UsuarioActual,
    )

    fun marcarTodasLeidas(usuario: UsuarioActual)
}
