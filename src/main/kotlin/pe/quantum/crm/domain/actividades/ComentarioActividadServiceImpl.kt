package pe.quantum.crm.domain.actividades

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pe.quantum.crm.domain.actividades.dto.ComentarioDto
import pe.quantum.crm.domain.empleados.EmpleadoService
import pe.quantum.crm.domain.eventos.EventoService
import pe.quantum.crm.domain.tareas.TareaService
import pe.quantum.crm.shared.comoInstanteUtc
import pe.quantum.crm.shared.exception.ValidacionException
import pe.quantum.crm.shared.security.UsuarioActual
import java.time.LocalDateTime

@Service
class ComentarioActividadServiceImpl(
    private val comentarioRepository: ActividadComentarioRepository,
    private val tareaService: TareaService,
    private val eventoService: EventoService,
    private val empleadoService: EmpleadoService,
) : ComentarioActividadService {
    @Transactional
    override fun crear(
        tipo: TipoActividad,
        idActividad: Long,
        texto: String,
        usuario: UsuarioActual,
    ): ComentarioDto {
        // Primero visibilidad (404 si no alcanza), despues validacion del texto:
        // asi un usuario sin acceso no distingue "no existe" de "texto invalido".
        exigirVisible(tipo, idActividad, usuario)
        val limpio = texto.trim()
        if (limpio.isEmpty()) {
            throw ValidacionException("El comentario no puede estar vacío", field = "texto")
        }
        val guardado =
            comentarioRepository.save(
                ActividadComentario(
                    idTarea = idActividad.takeIf { tipo == TipoActividad.tarea },
                    idEvento = idActividad.takeIf { tipo == TipoActividad.evento },
                    texto = limpio,
                    createdAt = LocalDateTime.now(),
                    createdBy = usuario.id,
                ),
            )
        return toDtos(listOf(guardado)).first()
    }

    @Transactional(readOnly = true)
    override fun listar(
        tipo: TipoActividad,
        idActividad: Long,
        usuario: UsuarioActual,
    ): List<ComentarioDto> {
        exigirVisible(tipo, idActividad, usuario)
        val comentarios =
            when (tipo) {
                TipoActividad.tarea -> comentarioRepository.findByIdTareaOrderByCreatedAtAsc(idActividad)
                TipoActividad.evento -> comentarioRepository.findByIdEventoOrderByCreatedAtAsc(idActividad)
            }
        return toDtos(comentarios)
    }

    @Transactional(readOnly = true)
    override fun contar(
        tipo: TipoActividad,
        idsActividad: Collection<Long>,
    ): Map<Long, Int> {
        if (idsActividad.isEmpty()) {
            return emptyMap()
        }
        return when (tipo) {
            TipoActividad.tarea ->
                comentarioRepository
                    .findByIdTareaInOrderByCreatedAtAsc(idsActividad)
                    .groupingBy { requireNotNull(it.idTarea) }
                    .eachCount()

            TipoActividad.evento ->
                comentarioRepository
                    .findByIdEventoInOrderByCreatedAtAsc(idsActividad)
                    .groupingBy { requireNotNull(it.idEvento) }
                    .eachCount()
        }
    }

    // ── privados ───────────────────────────────────────────────

    /** Delega en el modulo dueño de la actividad; lanza 404 si no la alcanza. */
    private fun exigirVisible(
        tipo: TipoActividad,
        idActividad: Long,
        usuario: UsuarioActual,
    ) {
        when (tipo) {
            TipoActividad.tarea -> tareaService.vinculoVisible(idActividad, usuario)
            TipoActividad.evento -> eventoService.vinculoVisible(idActividad, usuario)
        }
    }

    private fun toDtos(comentarios: List<ActividadComentario>): List<ComentarioDto> {
        if (comentarios.isEmpty()) {
            return emptyList()
        }
        val autores = empleadoService.resumenPorIds(comentarios.map { it.createdBy }.distinct())
        return comentarios.map {
            val esTarea = it.idTarea != null
            ComentarioDto(
                id = requireNotNull(it.id),
                tipo = if (esTarea) TipoActividad.tarea.name else TipoActividad.evento.name,
                idActividad = requireNotNull(it.idTarea ?: it.idEvento),
                texto = it.texto,
                createdAt = it.createdAt.comoInstanteUtc(),
                createdBy = it.createdBy,
                autor = autores[it.createdBy],
            )
        }
    }
}
