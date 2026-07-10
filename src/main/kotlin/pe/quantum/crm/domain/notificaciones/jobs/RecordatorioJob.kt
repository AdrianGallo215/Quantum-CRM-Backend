package pe.quantum.crm.domain.notificaciones.jobs

import org.springframework.dao.DataIntegrityViolationException
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import pe.quantum.crm.domain.empresas.EmpresaService
import pe.quantum.crm.domain.eventos.EventoService
import pe.quantum.crm.domain.eventos.dto.EventoRecordatorioProyeccion
import pe.quantum.crm.domain.notificaciones.EntidadNotificacion
import pe.quantum.crm.domain.notificaciones.NotificacionService
import pe.quantum.crm.domain.notificaciones.OrigenRecordatorio
import pe.quantum.crm.domain.notificaciones.RecordatorioEnviado
import pe.quantum.crm.domain.notificaciones.RecordatorioEnviadoRepository
import pe.quantum.crm.domain.notificaciones.TipoNotificacion
import pe.quantum.crm.domain.notificaciones.UmbralRecordatorio
import pe.quantum.crm.domain.oportunidades.OportunidadService
import pe.quantum.crm.domain.tareas.TareaService
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * Recordatorios de tareas/eventos por vencer o vencidos (docs/superpowers/specs/2026-07-09-notificaciones-in-app-design.md).
 * Corre cada hora; cada (origen, id, umbral) notifica como maximo una vez,
 * registrado en `recordatorios_enviados`.
 */
@Component
@Suppress("LongParameterList") // Cruza 4 modulos de dominio + notificaciones.
class RecordatorioJob(
    private val tareaService: TareaService,
    private val eventoService: EventoService,
    private val oportunidadService: OportunidadService,
    private val empresaService: EmpresaService,
    private val notificacionService: NotificacionService,
    private val recordatorioEnviadoRepository: RecordatorioEnviadoRepository,
) {
    @Scheduled(cron = "0 0 * * * *")
    @Transactional
    fun ejecutar() {
        procesarTareas()
        procesarEventos()
    }

    private fun procesarTareas() {
        val ahora = LocalDateTime.now()
        tareaService.pendientesParaRecordatorio().forEach { tarea ->
            val umbral = umbralTarea(tarea.fechaEjecucion, ahora) ?: return@forEach
            procesarRecordatorio(
                origen = OrigenRecordatorio.tarea,
                idOrigen = tarea.id,
                umbral = umbral,
                idEmpresaNombre = tarea.idEmpresa,
                destinatario = tarea.idAsignado,
                entidadTipo = if (tarea.idOportunidad != null) EntidadNotificacion.oportunidad else EntidadNotificacion.empresa,
                entidadId = tarea.idOportunidad ?: tarea.idEmpresa,
                tipo = TipoNotificacion.tarea_recordatorio,
                mensaje = { razonSocial -> mensajeTarea(umbral, razonSocial) },
            )
        }
    }

    private fun procesarEventos() {
        val hoy = LocalDate.now()
        eventoService.pendientesParaRecordatorio().forEach { evento ->
            val umbral = umbralEvento(evento.fechaEstimada, hoy) ?: return@forEach
            val destino = destinoDe(evento) ?: return@forEach
            procesarRecordatorio(
                origen = OrigenRecordatorio.evento,
                idOrigen = evento.id,
                umbral = umbral,
                idEmpresaNombre = destino.idEmpresa,
                destinatario = destino.idVendedor,
                entidadTipo = destino.entidadTipo,
                entidadId = destino.entidadId,
                tipo = TipoNotificacion.evento_recordatorio,
                mensaje = { razonSocial -> mensajeEvento(umbral, razonSocial) },
            )
        }
    }

    @Suppress("LongParameterList")
    private fun procesarRecordatorio(
        origen: OrigenRecordatorio,
        idOrigen: Long,
        umbral: UmbralRecordatorio,
        idEmpresaNombre: Long,
        destinatario: Long,
        entidadTipo: EntidadNotificacion,
        entidadId: Long,
        tipo: TipoNotificacion,
        mensaje: (String) -> String,
    ) {
        if (recordatorioEnviadoRepository.existsByOrigenAndIdOrigenAndUmbral(origen, idOrigen, umbral)) {
            return
        }
        val empresa = empresaService.resumenPorIds(listOf(idEmpresaNombre))[idEmpresaNombre] ?: return
        notificacionService.notificar(
            destinatarios = setOf(destinatario),
            idActor = null,
            tipo = tipo,
            mensaje = mensaje(empresa.razonSocial),
            entidadTipo = entidadTipo,
            entidadId = entidadId,
        )
        registrarEnviado(origen, idOrigen, umbral)
    }

    @Suppress("SwallowedException") // Carrera entre 2 corridas del job; uq_recordatorio ya lo cubrio.
    private fun registrarEnviado(
        origen: OrigenRecordatorio,
        idOrigen: Long,
        umbral: UmbralRecordatorio,
    ) {
        try {
            recordatorioEnviadoRepository.save(RecordatorioEnviado(origen = origen, idOrigen = idOrigen, umbral = umbral))
        } catch (ex: DataIntegrityViolationException) {
            // uq_recordatorio: otra corrida del job ya registro este mismo umbral.
        }
    }

    private data class Destino(
        val entidadTipo: EntidadNotificacion,
        val entidadId: Long,
        val idEmpresa: Long,
        val idVendedor: Long,
    )

    private fun destinoDe(evento: EventoRecordatorioProyeccion): Destino? {
        val idOportunidad = evento.idOportunidad
        if (idOportunidad != null) {
            val datos = oportunidadService.datosRecordatorio(idOportunidad) ?: return null
            return Destino(EntidadNotificacion.oportunidad, idOportunidad, datos.idEmpresa, datos.idVendedor)
        }
        val idEmpresa = evento.idEmpresa ?: return null
        val idVendedor = empresaService.vendedorAsignado(idEmpresa) ?: return null
        return Destino(EntidadNotificacion.empresa, idEmpresa, idEmpresa, idVendedor)
    }

    private fun umbralTarea(
        fechaEjecucion: LocalDateTime,
        ahora: LocalDateTime,
    ): UmbralRecordatorio? =
        when {
            fechaEjecucion.isBefore(ahora) -> UmbralRecordatorio.vencido
            !fechaEjecucion.isAfter(ahora.plusHours(HORAS_PROXIMO)) -> UmbralRecordatorio.proximo
            else -> null
        }

    private fun umbralEvento(
        fechaEstimada: LocalDate,
        hoy: LocalDate,
    ): UmbralRecordatorio? =
        when {
            fechaEstimada.isBefore(hoy) -> UmbralRecordatorio.vencido
            fechaEstimada == hoy.plusDays(1) -> UmbralRecordatorio.proximo
            else -> null
        }

    private fun mensajeTarea(
        umbral: UmbralRecordatorio,
        razonSocial: String,
    ): String =
        when (umbral) {
            UmbralRecordatorio.proximo -> "Recordatorio: tienes una tarea próxima a vencer en $razonSocial"
            UmbralRecordatorio.vencido -> "Recordatorio: tienes una tarea vencida en $razonSocial"
        }

    private fun mensajeEvento(
        umbral: UmbralRecordatorio,
        razonSocial: String,
    ): String =
        when (umbral) {
            UmbralRecordatorio.proximo -> "Recordatorio: hay un evento próximo a vencer en $razonSocial"
            UmbralRecordatorio.vencido -> "Recordatorio: hay un evento vencido sin registrar en $razonSocial"
        }

    private companion object {
        const val HORAS_PROXIMO = 24L
    }
}
