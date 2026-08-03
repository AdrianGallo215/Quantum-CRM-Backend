package pe.quantum.crm.domain.notificaciones.jobs

import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import pe.quantum.crm.domain.empresas.EmpresaService
import pe.quantum.crm.domain.eventos.EventoService
import pe.quantum.crm.domain.eventos.dto.EventoRecordatorioProyeccion
import pe.quantum.crm.domain.notificaciones.EntidadNotificacion
import pe.quantum.crm.domain.notificaciones.OrigenRecordatorio
import pe.quantum.crm.domain.notificaciones.UmbralRecordatorio
import pe.quantum.crm.domain.oportunidades.OportunidadService
import pe.quantum.crm.domain.tareas.TareaService
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * Recordatorios de tareas/eventos por vencer o vencidos (docs/superpowers/specs/2026-07-09-notificaciones-in-app-design.md).
 * Corre cada hora; cada (origen, id, umbral) notifica como maximo una vez,
 * registrado en `recordatorios_enviados`.
 *
 * `ejecutar()` NO lleva `@Transactional` a proposito. La transaccion es de grano
 * fino: la abre [EnvioRecordatorio.enviar] por cada recordatorio. Envolver el
 * bucle entero en una sola transaccion hacia que un unico dato malo (una carrera
 * contra `uq_recordatorio`, una empresa inconsistente) abortara la transaccion en
 * Postgres y ningun destinatario recibiera nada esa hora; peor aun, lo ya insertado
 * se revertia, asi que el mismo fallo se repetia indefinidamente. Mismo criterio
 * que `CarpetasDriveBackfillService`.
 */
@Component
class RecordatorioJob(
    private val tareaService: TareaService,
    private val eventoService: EventoService,
    private val oportunidadService: OportunidadService,
    private val empresaService: EmpresaService,
    private val envioRecordatorio: EnvioRecordatorio,
) {
    private val log = LoggerFactory.getLogger(RecordatorioJob::class.java)

    @Scheduled(cron = "0 0 * * * *")
    fun ejecutar() {
        procesarTareas()
        procesarEventos()
    }

    private fun procesarTareas() {
        val ahora = LocalDateTime.now()
        tareaService.pendientesParaRecordatorio().forEach { tarea ->
            val umbral = umbralTarea(tarea.fechaEjecucion, ahora) ?: return@forEach
            enviarAislado(OrigenRecordatorio.tarea, tarea.id) {
                SolicitudRecordatorio(
                    origen = OrigenRecordatorio.tarea,
                    idOrigen = tarea.id,
                    umbral = umbral,
                    idEmpresa = tarea.idEmpresa,
                    destinatario = tarea.idAsignado,
                    entidadTipo = if (tarea.idOportunidad != null) EntidadNotificacion.oportunidad else EntidadNotificacion.empresa,
                    entidadId = tarea.idOportunidad ?: tarea.idEmpresa,
                )
            }
        }
    }

    private fun procesarEventos() {
        val hoy = LocalDate.now()
        eventoService.pendientesParaRecordatorio().forEach { evento ->
            val umbral = umbralEvento(evento.fechaEstimada, hoy) ?: return@forEach
            enviarAislado(OrigenRecordatorio.evento, evento.id) {
                destinoDe(evento)?.let { destino ->
                    SolicitudRecordatorio(
                        origen = OrigenRecordatorio.evento,
                        idOrigen = evento.id,
                        umbral = umbral,
                        idEmpresa = destino.idEmpresa,
                        destinatario = destino.idVendedor,
                        entidadTipo = destino.entidadTipo,
                        entidadId = destino.entidadId,
                    )
                }
            }
        }
    }

    /**
     * Aisla el fallo de un recordatorio: se loguea con su origen e id y el bucle
     * sigue con el siguiente. [construir] entra en el try porque tambien consulta
     * la BD (el destinatario del evento) y puede fallar igual que el envio.
     */
    @Suppress("TooGenericExceptionCaught") // Aislar el fallo de un recordatorio es justo el objetivo.
    private fun enviarAislado(
        origen: OrigenRecordatorio,
        idOrigen: Long,
        construir: () -> SolicitudRecordatorio?,
    ) {
        try {
            val solicitud = construir() ?: return
            envioRecordatorio.enviar(solicitud)
        } catch (ex: RuntimeException) {
            log.warn(
                "Recordatorio de {} {} no se pudo enviar; el job continua con los demas",
                origen,
                idOrigen,
                ex,
            )
        }
    }

    private data class Destino(
        val entidadTipo: EntidadNotificacion,
        val entidadId: Long,
        val idEmpresa: Long,
        val idVendedor: Long,
    )

    @Suppress("ReturnCount") // Guard clauses de salida temprana; dividir la funcion no mejora la legibilidad.
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

    private companion object {
        const val HORAS_PROXIMO = 24L
    }
}
