package pe.quantum.crm.domain.notificaciones.jobs

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import pe.quantum.crm.domain.empresas.EmpresaService
import pe.quantum.crm.domain.empresas.dto.EmpresaResumen
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
import pe.quantum.crm.domain.oportunidades.dto.OportunidadRecordatorioDatos
import pe.quantum.crm.domain.tareas.TareaService
import pe.quantum.crm.domain.tareas.dto.TareaRecordatorioProyeccion
import java.time.LocalDate
import java.time.LocalDateTime

class RecordatorioJobTest {
    private val tareaService = mockk<TareaService>()
    private val eventoService = mockk<EventoService>()
    private val oportunidadService = mockk<OportunidadService>()
    private val empresaService = mockk<EmpresaService>()
    private val notificacionService = mockk<NotificacionService>(relaxed = true)
    private val recordatorioEnviadoRepository = mockk<RecordatorioEnviadoRepository>(relaxed = true)
    private val job =
        RecordatorioJob(tareaService, eventoService, oportunidadService, empresaService, notificacionService, recordatorioEnviadoRepository)

    @Test
    fun `tarea vencida (fecha ya paso) notifica umbral vencido una sola vez`() {
        every { tareaService.pendientesParaRecordatorio() } returns
            listOf(
                TareaRecordatorioProyeccion(
                    id = 1,
                    idAsignado = 3,
                    idEmpresa = 10,
                    idOportunidad = null,
                    fechaEjecucion = LocalDateTime.now().minusHours(2),
                ),
            )
        every { eventoService.pendientesParaRecordatorio() } returns emptyList()
        every {
            recordatorioEnviadoRepository.existsByOrigenAndIdOrigenAndUmbral(OrigenRecordatorio.tarea, 1, UmbralRecordatorio.vencido)
        } returns false
        every { empresaService.resumenPorIds(listOf(10)) } returns
            mapOf(10L to EmpresaResumen(id = 10, razonSocial = "Kincar S.A.C.", distrito = null))
        every { recordatorioEnviadoRepository.save(any()) } returns
            RecordatorioEnviado(origen = OrigenRecordatorio.tarea, idOrigen = 1, umbral = UmbralRecordatorio.vencido)

        job.ejecutar()

        verify {
            notificacionService.notificar(
                destinatarios = setOf(3L),
                idActor = null,
                tipo = TipoNotificacion.tarea_recordatorio,
                mensaje = "Recordatorio: tienes una tarea vencida en Kincar S.A.C.",
                entidadTipo = EntidadNotificacion.empresa,
                entidadId = 10L,
            )
        }
    }

    @Test
    fun `tarea con recordatorio ya enviado para ese umbral no notifica de nuevo`() {
        every { tareaService.pendientesParaRecordatorio() } returns
            listOf(
                TareaRecordatorioProyeccion(
                    id = 1,
                    idAsignado = 3,
                    idEmpresa = 10,
                    idOportunidad = null,
                    fechaEjecucion = LocalDateTime.now().minusHours(2),
                ),
            )
        every { eventoService.pendientesParaRecordatorio() } returns emptyList()
        every {
            recordatorioEnviadoRepository.existsByOrigenAndIdOrigenAndUmbral(OrigenRecordatorio.tarea, 1, UmbralRecordatorio.vencido)
        } returns true

        job.ejecutar()

        verify(exactly = 0) { notificacionService.notificar(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `evento con fecha_estimada manana notifica umbral proximo via oportunidad`() {
        every { tareaService.pendientesParaRecordatorio() } returns emptyList()
        every { eventoService.pendientesParaRecordatorio() } returns
            listOf(EventoRecordatorioProyeccion(id = 4, idOportunidad = 50, idEmpresa = null, fechaEstimada = LocalDate.now().plusDays(1)))
        every {
            recordatorioEnviadoRepository.existsByOrigenAndIdOrigenAndUmbral(OrigenRecordatorio.evento, 4, UmbralRecordatorio.proximo)
        } returns false
        every { oportunidadService.datosRecordatorio(50) } returns OportunidadRecordatorioDatos(idEmpresa = 10, idVendedor = 3)
        every { empresaService.resumenPorIds(listOf(10)) } returns
            mapOf(10L to EmpresaResumen(id = 10, razonSocial = "Kincar S.A.C.", distrito = null))
        every { recordatorioEnviadoRepository.save(any()) } returns
            RecordatorioEnviado(origen = OrigenRecordatorio.evento, idOrigen = 4, umbral = UmbralRecordatorio.proximo)

        job.ejecutar()

        verify {
            notificacionService.notificar(
                destinatarios = setOf(3L),
                idActor = null,
                tipo = TipoNotificacion.evento_recordatorio,
                mensaje = "Recordatorio: hay un evento próximo a vencer en Kincar S.A.C.",
                entidadTipo = EntidadNotificacion.oportunidad,
                entidadId = 50L,
            )
        }
    }
}
