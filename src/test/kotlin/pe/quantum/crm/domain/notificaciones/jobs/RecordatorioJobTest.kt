package pe.quantum.crm.domain.notificaciones.jobs

import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.dao.DataIntegrityViolationException
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
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset

class RecordatorioJobTest {
    private val tareaService = mockk<TareaService>()
    private val eventoService = mockk<EventoService>()
    private val oportunidadService = mockk<OportunidadService>()
    private val empresaService = mockk<EmpresaService>()
    private val notificacionService = mockk<NotificacionService>(relaxed = true)
    private val recordatorioEnviadoRepository = mockk<RecordatorioEnviadoRepository>(relaxed = true)

    // El colaborador va real, no mockeado: es donde vive el dedup y el armado del
    // mensaje, y los tests comprueban el comportamiento del job completo.
    private val envioRecordatorio = EnvioRecordatorio(empresaService, notificacionService, recordatorioEnviadoRepository)
    private val job = RecordatorioJob(tareaService, eventoService, oportunidadService, empresaService, envioRecordatorio)

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
    fun `si el recordatorio de la primera tarea falla, las siguientes se envian igual`() {
        every { tareaService.pendientesParaRecordatorio() } returns
            listOf(
                tareaVencida(id = 1, idAsignado = 3, idEmpresa = 10),
                tareaVencida(id = 2, idAsignado = 4, idEmpresa = 11),
            )
        every { eventoService.pendientesParaRecordatorio() } returns emptyList()
        every { recordatorioEnviadoRepository.existsByOrigenAndIdOrigenAndUmbral(any(), any(), any()) } returns false
        every { empresaService.resumenPorIds(listOf(10)) } returns
            mapOf(10L to EmpresaResumen(id = 10, razonSocial = "Kincar S.A.C.", distrito = null))
        every { empresaService.resumenPorIds(listOf(11)) } returns
            mapOf(11L to EmpresaResumen(id = 11, razonSocial = "Transportes Lima", distrito = null))
        // Reproduce uq_recordatorio saltando en la primera tarea: antes, esa unica
        // fila mataba la transaccion del job y nadie mas recibia su recordatorio.
        every { recordatorioEnviadoRepository.save(match { it.idOrigen == 1L }) } throws
            DataIntegrityViolationException("duplicate key value violates unique constraint \"uq_recordatorio\"")
        every { recordatorioEnviadoRepository.save(match { it.idOrigen == 2L }) } returns
            RecordatorioEnviado(origen = OrigenRecordatorio.tarea, idOrigen = 2, umbral = UmbralRecordatorio.vencido)

        job.ejecutar()

        verify {
            notificacionService.notificar(
                destinatarios = setOf(4L),
                idActor = null,
                tipo = TipoNotificacion.tarea_recordatorio,
                mensaje = "Recordatorio: tienes una tarea vencida en Transportes Lima",
                entidadTipo = EntidadNotificacion.empresa,
                entidadId = 11L,
            )
        }
        verify { recordatorioEnviadoRepository.save(match { it.idOrigen == 2L }) }
    }

    @Test
    fun `una tarea que falla no impide procesar los eventos`() {
        every { tareaService.pendientesParaRecordatorio() } returns listOf(tareaVencida(id = 1, idAsignado = 3, idEmpresa = 10))
        every { eventoService.pendientesParaRecordatorio() } returns
            listOf(EventoRecordatorioProyeccion(id = 4, idOportunidad = 50, idEmpresa = null, fechaEstimada = LocalDate.now().plusDays(1)))
        every { recordatorioEnviadoRepository.existsByOrigenAndIdOrigenAndUmbral(any(), any(), any()) } returns false
        every { empresaService.resumenPorIds(listOf(10)) } throws IllegalStateException("la empresa de la tarea reventó")
        every { oportunidadService.datosRecordatorio(50) } returns OportunidadRecordatorioDatos(idEmpresa = 11, idVendedor = 4)
        every { empresaService.resumenPorIds(listOf(11)) } returns
            mapOf(11L to EmpresaResumen(id = 11, razonSocial = "Transportes Lima", distrito = null))
        every { recordatorioEnviadoRepository.save(any()) } returns
            RecordatorioEnviado(origen = OrigenRecordatorio.evento, idOrigen = 4, umbral = UmbralRecordatorio.proximo)

        job.ejecutar()

        verify {
            notificacionService.notificar(
                destinatarios = setOf(4L),
                idActor = null,
                tipo = TipoNotificacion.evento_recordatorio,
                mensaje = "Recordatorio: hay un evento próximo a vencer en Transportes Lima",
                entidadTipo = EntidadNotificacion.oportunidad,
                entidadId = 50L,
            )
        }
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

    /**
     * Instante elegido a proposito: 2026-08-12T02:00Z son las 21:00 del 2026-08-11 en
     * Lima. Con el reloj en UTC el evento del dia 11 parecia vencido; en el
     * calendario real del vendedor aun le quedaban tres horas de ese dia.
     */
    @Test
    fun `un evento de hoy en Lima no se notifica como vencido aunque en UTC ya sea manana`() {
        val reloj = Clock.fixed(Instant.parse("2026-08-12T02:00:00Z"), ZoneOffset.UTC)
        val job = RecordatorioJob(tareaService, eventoService, oportunidadService, empresaService, envioRecordatorio, reloj)
        every { tareaService.pendientesParaRecordatorio() } returns emptyList()
        every { eventoService.pendientesParaRecordatorio() } returns
            listOf(
                EventoRecordatorioProyeccion(
                    id = 4,
                    idOportunidad = 50,
                    idEmpresa = null,
                    fechaEstimada = LocalDate.of(2026, 8, 11),
                ),
            )

        job.ejecutar()

        verify(exactly = 0) { notificacionService.notificar(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `un evento de manana en Lima si se notifica como proximo`() {
        val reloj = Clock.fixed(Instant.parse("2026-08-12T02:00:00Z"), ZoneOffset.UTC)
        val job = RecordatorioJob(tareaService, eventoService, oportunidadService, empresaService, envioRecordatorio, reloj)
        every { tareaService.pendientesParaRecordatorio() } returns emptyList()
        every { eventoService.pendientesParaRecordatorio() } returns
            listOf(
                EventoRecordatorioProyeccion(
                    id = 4,
                    idOportunidad = 50,
                    idEmpresa = null,
                    fechaEstimada = LocalDate.of(2026, 8, 12),
                ),
            )
        every {
            recordatorioEnviadoRepository.existsByOrigenAndIdOrigenAndUmbral(OrigenRecordatorio.evento, 4, UmbralRecordatorio.proximo)
        } returns false
        every { oportunidadService.datosRecordatorio(50) } returns OportunidadRecordatorioDatos(idEmpresa = 10, idVendedor = 3)
        every { empresaService.resumenPorIds(listOf(10)) } returns
            mapOf(10L to EmpresaResumen(id = 10, razonSocial = "Kincar S.A.C.", distrito = null))
        every { recordatorioEnviadoRepository.save(any()) } returns
            RecordatorioEnviado(origen = OrigenRecordatorio.evento, idOrigen = 4, umbral = UmbralRecordatorio.proximo)

        job.ejecutar()

        verify(exactly = 1) { notificacionService.notificar(any(), any(), any(), any(), any(), any()) }
    }

    private val instanteFijo = Instant.parse("2026-08-11T12:00:00Z")

    private fun jobCon(reloj: Clock) =
        RecordatorioJob(tareaService, eventoService, oportunidadService, empresaService, envioRecordatorio, reloj)

    /** Justo dentro de las 24 h: `!isAfter` hace que el borde exacto cuente como proximo. */
    @Test
    fun `una tarea exactamente a 24 horas cuenta como proxima`() {
        assertThat(umbralDeTareaA(Duration.ofHours(24))).isEqualTo(UmbralRecordatorio.proximo)
    }

    @Test
    fun `una tarea a 23h59m cuenta como proxima`() {
        assertThat(umbralDeTareaA(Duration.ofHours(23).plusMinutes(59))).isEqualTo(UmbralRecordatorio.proximo)
    }

    /** Un minuto mas alla del umbral no genera ningun recordatorio todavia. */
    @Test
    fun `una tarea a 24h01m no genera recordatorio`() {
        assertThat(umbralDeTareaA(Duration.ofHours(24).plusMinutes(1))).isNull()
    }

    @Test
    fun `una tarea vencida por un minuto cuenta como vencida`() {
        assertThat(umbralDeTareaA(Duration.ofMinutes(-1))).isEqualTo(UmbralRecordatorio.vencido)
    }

    /**
     * Ejercita el job completo y devuelve el umbral con el que se registro el
     * recordatorio, o null si no se envio ninguno. Afirmar sobre el umbral
     * persistido —y no sobre un calculo repetido en el test— es lo que hace que
     * estos bordes prueben el job y no a si mismos.
     */
    private fun umbralDeTareaA(desfase: Duration): UmbralRecordatorio? {
        val reloj = Clock.fixed(instanteFijo, ZoneOffset.UTC)
        val fecha = LocalDateTime.ofInstant(instanteFijo, ZoneOffset.UTC).plus(desfase)
        every { tareaService.pendientesParaRecordatorio() } returns
            listOf(TareaRecordatorioProyeccion(id = 1, idAsignado = 3, idEmpresa = 10, idOportunidad = null, fechaEjecucion = fecha))
        every { eventoService.pendientesParaRecordatorio() } returns emptyList()
        every { recordatorioEnviadoRepository.existsByOrigenAndIdOrigenAndUmbral(any(), any(), any()) } returns false
        every { empresaService.resumenPorIds(listOf(10)) } returns
            mapOf(10L to EmpresaResumen(id = 10, razonSocial = "Kincar S.A.C.", distrito = null))
        val guardado = slot<RecordatorioEnviado>()
        every { recordatorioEnviadoRepository.save(capture(guardado)) } answers { firstArg() }

        jobCon(reloj).ejecutar()

        return if (guardado.isCaptured) guardado.captured.umbral else null
    }

    /**
     * La oportunidad del evento ya no existe (borrada mientras el evento seguia
     * pendiente). `destinoDe` devuelve null: el job debe saltarselo en silencio, sin
     * notificar y sin reventar el resto del barrido.
     */
    @Test
    fun `un evento cuya oportunidad ya no existe se ignora sin notificar`() {
        every { tareaService.pendientesParaRecordatorio() } returns emptyList()
        every { eventoService.pendientesParaRecordatorio() } returns
            listOf(EventoRecordatorioProyeccion(id = 4, idOportunidad = 50, idEmpresa = null, fechaEstimada = LocalDate.now().plusDays(1)))
        every { recordatorioEnviadoRepository.existsByOrigenAndIdOrigenAndUmbral(any(), any(), any()) } returns false
        every { oportunidadService.datosRecordatorio(50) } returns null

        job.ejecutar()

        verify(exactly = 0) { notificacionService.notificar(any(), any(), any(), any(), any(), any()) }
        verify(exactly = 0) { recordatorioEnviadoRepository.save(any()) }
    }

    private fun tareaVencida(
        id: Long,
        idAsignado: Long,
        idEmpresa: Long,
    ) = TareaRecordatorioProyeccion(
        id = id,
        idAsignado = idAsignado,
        idEmpresa = idEmpresa,
        idOportunidad = null,
        fechaEjecucion = LocalDateTime.now().minusHours(2),
    )
}
