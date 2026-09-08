package pe.quantum.crm.domain.actividades

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pe.quantum.crm.domain.actividades.dto.CambioAuditoriaDto
import pe.quantum.crm.domain.actividades.dto.CambioCampo
import pe.quantum.crm.domain.empleados.EmpleadoService
import pe.quantum.crm.shared.comoInstanteUtc
import java.time.LocalDateTime

/**
 * Solo dos dependencias, y es a proposito: ver la nota de la interfaz sobre la
 * dependencia circular con `tareas` y `eventos`. NO agregues `TareaService`
 * ni `EventoService` aqui.
 */
@Service
class AuditoriaActividadServiceImpl(
    private val auditoriaRepository: ActividadAuditoriaRepository,
    private val empleadoService: EmpleadoService,
) : AuditoriaActividadService {
    @Transactional
    override fun registrar(
        tipo: TipoActividad,
        idActividad: Long,
        cambios: List<CambioCampo>,
        idActor: Long,
    ) {
        if (cambios.isEmpty()) {
            return
        }
        val ahora = LocalDateTime.now()
        auditoriaRepository.saveAll(
            cambios.map {
                ActividadAuditoria(
                    idTarea = idActividad.takeIf { _ -> tipo == TipoActividad.tarea },
                    idEvento = idActividad.takeIf { _ -> tipo == TipoActividad.evento },
                    campo = it.campo,
                    valorAnterior = it.valorAnterior,
                    valorNuevo = it.valorNuevo,
                    changedAt = ahora,
                    changedBy = idActor,
                )
            },
        )
    }

    @Transactional(readOnly = true)
    override fun historial(
        tipo: TipoActividad,
        idActividad: Long,
    ): List<CambioAuditoriaDto> {
        val filas =
            when (tipo) {
                TipoActividad.tarea -> auditoriaRepository.findByIdTareaOrderByChangedAtDesc(idActividad)
                TipoActividad.evento -> auditoriaRepository.findByIdEventoOrderByChangedAtDesc(idActividad)
            }
        if (filas.isEmpty()) {
            return emptyList()
        }
        val autores = empleadoService.resumenPorIds(filas.map { it.changedBy }.distinct())
        return filas.map {
            CambioAuditoriaDto(
                id = requireNotNull(it.id),
                campo = it.campo,
                valorAnterior = it.valorAnterior,
                valorNuevo = it.valorNuevo,
                changedAt = it.changedAt.comoInstanteUtc(),
                changedBy = it.changedBy,
                autor = autores[it.changedBy],
            )
        }
    }
}
