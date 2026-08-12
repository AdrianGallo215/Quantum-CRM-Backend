package pe.quantum.crm.domain.eventos

import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import pe.quantum.crm.domain.catalogoeventos.CatalogoEventoService
import pe.quantum.crm.domain.catalogoeventos.dto.CatalogoEventoDto
import pe.quantum.crm.domain.empleados.EmpleadoService
import pe.quantum.crm.domain.empleados.dto.EmpleadoResumen
import pe.quantum.crm.domain.empresas.EmpresaService
import pe.quantum.crm.domain.empresas.dto.EmpresaResumen
import pe.quantum.crm.domain.empresas.dto.EmpresaVinculo
import pe.quantum.crm.domain.eventos.dto.ActualizarEventoRequest
import pe.quantum.crm.domain.eventos.dto.CrearEventoRequest
import pe.quantum.crm.domain.eventos.dto.MarcarOcurridoRequest
import pe.quantum.crm.domain.notificaciones.EntidadNotificacion
import pe.quantum.crm.domain.notificaciones.NotificacionService
import pe.quantum.crm.domain.notificaciones.OrigenRecordatorio
import pe.quantum.crm.domain.notificaciones.TipoNotificacion
import pe.quantum.crm.domain.oportunidades.OportunidadService
import pe.quantum.crm.domain.oportunidades.dto.OportunidadVinculo
import pe.quantum.crm.shared.enums.EstadoCartera
import pe.quantum.crm.shared.enums.EstadoEvento
import pe.quantum.crm.shared.enums.EstadoOportunidad
import pe.quantum.crm.shared.exception.NoEncontradoException
import pe.quantum.crm.shared.exception.ValidacionException
import pe.quantum.crm.shared.security.UsuarioActual
import java.time.LocalDate

/**
 * Unit tests de EventoServiceImpl sin Spring ni base de datos: las 4
 * dependencias se mockean directamente con MockK.
 */
class EventoServiceImplTest {
    private val eventoRepository = mockk<EventoRepository>()
    private val catalogoEventoService = mockk<CatalogoEventoService>()
    private val oportunidadService = mockk<OportunidadService>(relaxed = true)
    private val empresaService = mockk<EmpresaService>(relaxed = true)
    private val empleadoService = mockk<EmpleadoService>(relaxed = true)
    private val notificacionService = mockk<NotificacionService>(relaxed = true)
    private val service =
        EventoServiceImpl(eventoRepository, catalogoEventoService, oportunidadService, empresaService, empleadoService, notificacionService)

    private val usuario = UsuarioActual(id = 1, rol = "vendedor")

    private fun empresaVinculo(id: Long = 10) =
        EmpresaVinculo(id = id, razonSocial = "Kincar S.A.C.", idVendedor = 1, estadoCartera = EstadoCartera.prospeccion.name)

    private fun catalogo(
        id: Long = 5,
        etapaAsociada: EstadoOportunidad? = null,
        esHitoProspeccion: Boolean = true,
    ) = CatalogoEventoDto(
        id = id,
        nombre = "Reporte Tributario recibido",
        etapaAsociada = etapaAsociada?.name,
        disparaCambioEstado = false,
        estadoDestino = null,
        esRecomendado = false,
        esHitoProspeccion = esHitoProspeccion,
    )

    /** Devuelve una copia del evento con `id` asignado, simulando lo que hace JPA al guardar. */
    private fun Evento.conId(nuevoId: Long) =
        Evento(
            id = nuevoId,
            idOportunidad = idOportunidad,
            idEmpresa = idEmpresa,
            idCatalogoEvento = idCatalogoEvento,
            esPersonalizado = esPersonalizado,
            nombrePersonalizado = nombrePersonalizado,
            descripcion = descripcion,
            estado = estado,
            fechaEstimada = fechaEstimada,
            fechaSeguimiento = fechaSeguimiento,
            fechaOcurrencia = fechaOcurrencia,
            disparaCambioEstado = disparaCambioEstado,
            estadoDestino = estadoDestino,
            registradoPor = registradoPor,
            createdAt = createdAt,
            createdBy = createdBy,
            updatedAt = updatedAt,
            updatedBy = updatedBy,
        )

    @Test
    fun `crear hito de prospeccion sobre una empresa expone es_hito_prospeccion en true`() {
        val slot = slot<Evento>()
        every { empresaService.vinculoVisible(10, usuario) } returns empresaVinculo()
        every { catalogoEventoService.porId(5) } returns catalogo()
        every { catalogoEventoService.todosPorId() } returns mapOf(5L to catalogo())
        every { eventoRepository.save(capture(slot)) } answers { slot.captured.conId(1) }

        val dto = service.crearEnEmpresa(10, CrearEventoRequest(idCatalogoEvento = 5), usuario)

        assertThat(dto.idOportunidad).isNull()
        assertThat(dto.idEmpresa).isEqualTo(10)
        assertThat(dto.esHitoProspeccion).isTrue()
    }

    @Test
    fun `evento de catalogo del pipeline expone es_hito_prospeccion en false`() {
        every { empresaService.vinculoVisible(10, usuario) } returns empresaVinculo()
        every { catalogoEventoService.todosPorId() } returns
            mapOf(5L to catalogo(etapaAsociada = null, esHitoProspeccion = false))
        every { eventoRepository.findByIdEmpresaAndIdOportunidadIsNullOrderByIdAsc(10) } returns
            listOf(Evento(id = 1, idEmpresa = 10, idCatalogoEvento = 5, createdBy = 1, updatedBy = 1))

        val resultado = service.listarPorEmpresa(10, usuario)

        assertThat(resultado.pendientes).hasSize(1)
        assertThat(resultado.pendientes.first().esHitoProspeccion).isFalse()
    }

    @Test
    fun `crear evento del catalogo con etapa_asociada sobre una empresa lanza VALIDACION`() {
        every { empresaService.vinculoVisible(10, usuario) } returns empresaVinculo()
        every { catalogoEventoService.porId(5) } returns
            catalogo(etapaAsociada = EstadoOportunidad.evaluacion_calidda, esHitoProspeccion = false)

        val ex =
            assertThrows<ValidacionException> {
                service.crearEnEmpresa(10, CrearEventoRequest(idCatalogoEvento = 5), usuario)
            }

        assertThat(ex.field).isEqualTo("id_catalogo_evento")
    }

    @Test
    fun `listar eventos de una empresa ajena o inexistente devuelve 404`() {
        every { empresaService.vinculoVisible(99, usuario) } throws NoEncontradoException("La empresa no existe")

        assertThrows<NoEncontradoException> { service.listarPorEmpresa(99, usuario) }
    }

    /**
     * Invariante #4 de CLAUDE.md: marcar un evento como ocurrido devuelve la
     * SUGERENCIA y NO toca la oportunidad; el cambio es una segunda llamada
     * confirmada del usuario. Solo existia el test negativo (evento que no dispara),
     * asi que borrar el guard no ponia nada en rojo.
     */
    @Test
    fun `marcar ocurrido un evento que dispara cambio de estado sugiere sin cambiar la oportunidad`() {
        val evento =
            Evento(
                id = 7,
                idOportunidad = 50,
                idCatalogoEvento = 5,
                disparaCambioEstado = true,
                estadoDestino = EstadoOportunidad.documentos_legales,
                createdBy = 1,
                updatedBy = 1,
            )
        every { eventoRepository.findById(7) } returns java.util.Optional.of(evento)
        every { oportunidadService.vinculoVisible(50, usuario) } returns
            OportunidadVinculo(id = 50, idEmpresa = 10, idVendedor = 3, estado = "evaluacion_calidda")
        every { eventoRepository.save(evento) } returns evento

        val resultado = service.marcarOcurrido(7, MarcarOcurridoRequest(), usuario)

        assertThat(resultado.sugerencia).isNotNull
        assertThat(resultado.sugerencia?.dispara).isTrue()
        assertThat(resultado.sugerencia?.estadoDestino).isEqualTo("documentos_legales")
        verify(exactly = 0) { oportunidadService.cambiarEstado(any(), any(), any()) }
    }

    @Test
    fun `marcar ocurrido un hito de empresa no genera sugerencia de cambio de estado`() {
        val evento =
            Evento(
                id = 7,
                idEmpresa = 10,
                idCatalogoEvento = 5,
                disparaCambioEstado = false,
                estadoDestino = null,
                createdBy = 1,
                updatedBy = 1,
            )
        every { eventoRepository.findById(7) } returns java.util.Optional.of(evento)
        every { empresaService.vinculoVisible(10, usuario) } returns empresaVinculo()
        every { eventoRepository.save(evento) } returns evento

        val resultado = service.marcarOcurrido(7, MarcarOcurridoRequest(), usuario)

        assertThat(resultado.sugerencia).isNull()
    }

    @Test
    fun `crear evento en una oportunidad notifica al vendedor asignado cuando el actor no es el`() {
        every { oportunidadService.vinculoVisible(50, any()) } returns
            OportunidadVinculo(id = 50, idEmpresa = 10, idVendedor = 3, estado = "evaluacion_calidda")
        every { catalogoEventoService.porId(5) } returns catalogo()
        every { catalogoEventoService.todosPorId() } returns mapOf(5L to catalogo())
        every { eventoRepository.save(any()) } answers { (firstArg<Evento>()).conId(1) }
        every { empresaService.resumenPorIds(listOf(10)) } returns
            mapOf(10L to EmpresaResumen(id = 10, razonSocial = "Kincar S.A.C.", distrito = null))
        every { empleadoService.resumenPorIds(listOf(7)) } returns
            mapOf(7L to EmpleadoResumen(id = 7, nombres = "Rosa", apellidos = "Vega"))

        service.crearEnOportunidad(50, CrearEventoRequest(idCatalogoEvento = 5), UsuarioActual(id = 7, rol = "analista"))

        verify {
            notificacionService.notificar(
                destinatarios = setOf(3L),
                idActor = 7L,
                tipo = TipoNotificacion.evento_creado,
                mensaje = "Rosa Vega creó un evento en Kincar S.A.C.",
                entidadTipo = EntidadNotificacion.oportunidad,
                entidadId = 50L,
            )
        }
    }

    @Test
    fun `crear evento cuando el actor es el propio vendedor notifica a supervisores en vez de a si mismo`() {
        every { oportunidadService.vinculoVisible(50, any()) } returns
            OportunidadVinculo(id = 50, idEmpresa = 10, idVendedor = 7, estado = "evaluacion_calidda")
        every { catalogoEventoService.porId(5) } returns catalogo()
        every { catalogoEventoService.todosPorId() } returns mapOf(5L to catalogo())
        every { eventoRepository.save(any()) } answers { (firstArg<Evento>()).conId(1) }
        every { empresaService.resumenPorIds(listOf(10)) } returns
            mapOf(10L to EmpresaResumen(id = 10, razonSocial = "Kincar S.A.C.", distrito = null))
        every { empleadoService.resumenPorIds(listOf(7)) } returns
            mapOf(7L to EmpleadoResumen(id = 7, nombres = "Rosa", apellidos = "Vega"))
        every { empleadoService.idsSupervisoresActivos() } returns listOf(1, 2)

        service.crearEnOportunidad(50, CrearEventoRequest(idCatalogoEvento = 5), UsuarioActual(id = 7, rol = "vendedor"))

        verify {
            notificacionService.notificar(
                destinatarios = setOf(1L, 2L),
                idActor = 7L,
                tipo = TipoNotificacion.evento_creado,
                mensaje = "Rosa Vega creó un evento en Kincar S.A.C.",
                entidadTipo = EntidadNotificacion.oportunidad,
                entidadId = 50L,
            )
        }
    }

    @Test
    fun `crear evento en una empresa notifica con entidad_tipo empresa y el id de la propia empresa`() {
        every { empresaService.vinculoVisible(10, any()) } returns empresaVinculo(10)
        every { catalogoEventoService.porId(5) } returns catalogo()
        every { catalogoEventoService.todosPorId() } returns mapOf(5L to catalogo())
        every { eventoRepository.save(any()) } answers { (firstArg<Evento>()).conId(1) }
        every { empresaService.resumenPorIds(listOf(10)) } returns
            mapOf(10L to EmpresaResumen(id = 10, razonSocial = "Kincar S.A.C.", distrito = null))
        every { empleadoService.resumenPorIds(listOf(7)) } returns
            mapOf(7L to EmpleadoResumen(id = 7, nombres = "Rosa", apellidos = "Vega"))

        service.crearEnEmpresa(10, CrearEventoRequest(idCatalogoEvento = 5), UsuarioActual(id = 7, rol = "analista"))

        verify {
            notificacionService.notificar(
                destinatarios = setOf(1L),
                idActor = 7L,
                tipo = TipoNotificacion.evento_creado,
                mensaje = "Rosa Vega creó un evento en Kincar S.A.C.",
                entidadTipo = EntidadNotificacion.empresa,
                entidadId = 10L,
            )
        }
    }

    /** Evento pendiente ya programado, base de los tests de reprogramacion. */
    private fun eventoProgramado() =
        Evento(
            id = 1,
            idOportunidad = 50,
            idEmpresa = null,
            idCatalogoEvento = 5,
            descripcion = "Esperando reporte",
            fechaEstimada = LocalDate.of(2026, 7, 10),
            createdBy = 1,
            updatedBy = 1,
        )

    @Test
    fun `reprogramar un evento reinicia el dedup de sus recordatorios`() {
        // Mismo motivo que en tareas: la clave de dedup no lleva la fecha.
        every { eventoRepository.findById(1) } returns java.util.Optional.of(eventoProgramado())
        every { eventoRepository.save(any()) } answers { firstArg() }
        every { catalogoEventoService.todosPorId() } returns mapOf(5L to catalogo())

        service.actualizar(1, ActualizarEventoRequest(fechaEstimada = LocalDate.of(2026, 7, 20)), usuario)

        verify { notificacionService.reiniciarRecordatorios(OrigenRecordatorio.evento, 1L) }
    }

    @Test
    fun `editar un evento sin mover la fecha estimada no reinicia sus recordatorios`() {
        every { eventoRepository.findById(1) } returns java.util.Optional.of(eventoProgramado())
        every { eventoRepository.save(any()) } answers { firstArg() }
        every { catalogoEventoService.todosPorId() } returns mapOf(5L to catalogo())

        service.actualizar(1, ActualizarEventoRequest(descripcion = "Sigue pendiente"), usuario)

        verify(exactly = 0) { notificacionService.reiniciarRecordatorios(any(), any()) }
    }

    @Test
    fun `reenviar la misma fecha estimada no reinicia sus recordatorios`() {
        every { eventoRepository.findById(1) } returns java.util.Optional.of(eventoProgramado())
        every { eventoRepository.save(any()) } answers { firstArg() }
        every { catalogoEventoService.todosPorId() } returns mapOf(5L to catalogo())

        service.actualizar(1, ActualizarEventoRequest(fechaEstimada = LocalDate.of(2026, 7, 10)), usuario)

        verify(exactly = 0) { notificacionService.reiniciarRecordatorios(any(), any()) }
    }

    @Test
    fun `pendientesParaRecordatorio proyecta solo eventos pendientes con fecha_estimada`() {
        every { eventoRepository.findByEstadoAndFechaEstimadaIsNotNull(EstadoEvento.pendiente) } returns
            listOf(
                Evento(
                    id = 1,
                    idOportunidad = 50,
                    idEmpresa = null,
                    idCatalogoEvento = 5,
                    fechaEstimada = java.time.LocalDate.of(2026, 7, 10),
                    createdBy = 1,
                    updatedBy = 1,
                ),
            )

        val resultado = service.pendientesParaRecordatorio()

        assertThat(resultado).hasSize(1)
        assertThat(resultado.first().idOportunidad).isEqualTo(50)
    }
}
