package pe.quantum.crm.domain.notificaciones.jobs

import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import pe.quantum.crm.domain.empresas.EmpresaService
import pe.quantum.crm.domain.notificaciones.EntidadNotificacion
import pe.quantum.crm.domain.notificaciones.NotificacionService
import pe.quantum.crm.domain.notificaciones.OrigenRecordatorio
import pe.quantum.crm.domain.notificaciones.RecordatorioEnviado
import pe.quantum.crm.domain.notificaciones.RecordatorioEnviadoRepository
import pe.quantum.crm.domain.notificaciones.TipoNotificacion
import pe.quantum.crm.domain.notificaciones.UmbralRecordatorio

/**
 * Un recordatorio concreto, ya resuelto por [RecordatorioJob] y listo para enviarse.
 * La razon social no viaja aqui: la lee [EnvioRecordatorio] dentro de su propia
 * transaccion, junto al resto de la operacion.
 */
data class SolicitudRecordatorio(
    val origen: OrigenRecordatorio,
    val idOrigen: Long,
    val umbral: UmbralRecordatorio,
    /** Empresa de la que se toma la razon social del mensaje. */
    val idEmpresa: Long,
    val destinatario: Long,
    val entidadTipo: EntidadNotificacion,
    val entidadId: Long,
)

/**
 * Envia UN recordatorio en su propia transaccion.
 *
 * Es un bean aparte de [RecordatorioJob] a proposito, y no un metodo privado del
 * job: Spring implementa `@Transactional` con proxies, asi que una llamada interna
 * del bean a si mismo NO pasa por el proxy y no abriria transaccion nueva. Al vivir
 * en otro bean, cada `enviar` es una unidad atomica independiente y el fallo de un
 * recordatorio (p. ej. `uq_recordatorio` saltando por una carrera) solo revierte
 * ese, no la hora entera de recordatorios.
 *
 * `REQUIRES_NEW` deja el aislamiento escrito en el codigo y no en la ausencia de
 * `@Transactional` en el llamador: aunque alguien anotara `ejecutar()` mas adelante,
 * cada recordatorio seguiria commiteando o revirtiendo por su cuenta.
 */
@Component
class EnvioRecordatorio(
    private val empresaService: EmpresaService,
    private val notificacionService: NotificacionService,
    private val recordatorioEnviadoRepository: RecordatorioEnviadoRepository,
) {
    /**
     * Notifica y deja constancia en `recordatorios_enviados`, ambas cosas en la
     * misma transaccion: nunca queda una notificacion sin su marca de dedup (se
     * repetiria cada hora) ni una marca sin su notificacion (se perderia).
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun enviar(solicitud: SolicitudRecordatorio) {
        val yaEnviado =
            recordatorioEnviadoRepository.existsByOrigenAndIdOrigenAndUmbral(
                solicitud.origen,
                solicitud.idOrigen,
                solicitud.umbral,
            )
        if (yaEnviado) {
            return
        }
        val empresa = empresaService.resumenPorIds(listOf(solicitud.idEmpresa))[solicitud.idEmpresa] ?: return
        notificacionService.notificar(
            destinatarios = setOf(solicitud.destinatario),
            idActor = null,
            tipo = tipoDe(solicitud.origen),
            mensaje = mensaje(solicitud.origen, solicitud.umbral, empresa.razonSocial),
            entidadTipo = solicitud.entidadTipo,
            entidadId = solicitud.entidadId,
        )
        recordatorioEnviadoRepository.save(
            RecordatorioEnviado(origen = solicitud.origen, idOrigen = solicitud.idOrigen, umbral = solicitud.umbral),
        )
    }

    private fun tipoDe(origen: OrigenRecordatorio): TipoNotificacion =
        when (origen) {
            OrigenRecordatorio.tarea -> TipoNotificacion.tarea_recordatorio
            OrigenRecordatorio.evento -> TipoNotificacion.evento_recordatorio
        }

    private fun mensaje(
        origen: OrigenRecordatorio,
        umbral: UmbralRecordatorio,
        razonSocial: String,
    ): String =
        when (origen) {
            OrigenRecordatorio.tarea ->
                when (umbral) {
                    UmbralRecordatorio.proximo -> "Recordatorio: tienes una tarea próxima a vencer en $razonSocial"
                    UmbralRecordatorio.vencido -> "Recordatorio: tienes una tarea vencida en $razonSocial"
                }
            OrigenRecordatorio.evento ->
                when (umbral) {
                    UmbralRecordatorio.proximo -> "Recordatorio: hay un evento próximo a vencer en $razonSocial"
                    UmbralRecordatorio.vencido -> "Recordatorio: hay un evento vencido sin registrar en $razonSocial"
                }
        }
}
