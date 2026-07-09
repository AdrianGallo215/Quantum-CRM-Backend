package pe.quantum.crm.domain.notificaciones

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pe.quantum.crm.domain.empleados.EmpleadoService
import pe.quantum.crm.domain.notificaciones.dto.NotificacionDto
import pe.quantum.crm.shared.exception.NoEncontradoException
import pe.quantum.crm.shared.security.UsuarioActual
import java.time.LocalDateTime

@Service
class NotificacionServiceImpl(
    private val notificacionRepository: NotificacionRepository,
    private val empleadoService: EmpleadoService,
) : NotificacionService {
    @Transactional
    override fun notificar(
        destinatarios: Set<Long>,
        idActor: Long?,
        tipo: TipoNotificacion,
        mensaje: String,
        entidadTipo: EntidadNotificacion,
        entidadId: Long,
    ) {
        val destinatariosFinal = if (idActor != null) destinatarios - idActor else destinatarios
        if (destinatariosFinal.isEmpty()) {
            return
        }
        val ahora = LocalDateTime.now()
        destinatariosFinal.forEach { idDestinatario ->
            notificacionRepository.save(
                Notificacion(
                    idEmpleadoDestinatario = idDestinatario,
                    idActor = idActor,
                    tipo = tipo,
                    mensaje = mensaje,
                    entidadTipo = entidadTipo,
                    entidadId = entidadId,
                    createdAt = ahora,
                ),
            )
        }
    }

    @Transactional(readOnly = true)
    override fun contarNoLeidas(usuario: UsuarioActual): Long = notificacionRepository.countByIdEmpleadoDestinatarioAndLeidaFalse(usuario.id)

    @Transactional(readOnly = true)
    override fun listar(usuario: UsuarioActual): List<NotificacionDto> {
        val notificaciones = notificacionRepository.findTop20ByIdEmpleadoDestinatarioOrderByCreatedAtDesc(usuario.id)
        val actores = empleadoService.resumenPorIds(notificaciones.mapNotNull { it.idActor })
        return notificaciones.map { notificacion ->
            NotificacionDto(
                id = requireNotNull(notificacion.id),
                tipo = notificacion.tipo.name,
                mensaje = notificacion.mensaje,
                entidadTipo = notificacion.entidadTipo.name,
                entidadId = notificacion.entidadId,
                leida = notificacion.leida,
                createdAt = notificacion.createdAt,
                actor = notificacion.idActor?.let { actores[it] },
            )
        }
    }

    @Transactional
    override fun marcarLeida(
        id: Long,
        usuario: UsuarioActual,
    ) {
        val notificacion =
            notificacionRepository.findByIdAndIdEmpleadoDestinatario(id, usuario.id)
                ?: throw NoEncontradoException("La notificación no existe")
        notificacion.leida = true
        notificacionRepository.save(notificacion)
    }

    @Transactional
    override fun marcarTodasLeidas(usuario: UsuarioActual) {
        val pendientes = notificacionRepository.findByIdEmpleadoDestinatarioAndLeidaFalse(usuario.id)
        pendientes.forEach { it.leida = true }
        notificacionRepository.saveAll(pendientes)
    }
}
