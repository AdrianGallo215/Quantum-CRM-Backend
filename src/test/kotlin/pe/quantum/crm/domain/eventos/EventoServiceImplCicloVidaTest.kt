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
import pe.quantum.crm.domain.empresas.EmpresaService
import pe.quantum.crm.domain.eventos.dto.ActualizarEventoRequest
import pe.quantum.crm.domain.eventos.dto.CrearEventoRequest
import pe.quantum.crm.domain.eventos.dto.MarcarDescartadoRequest
import pe.quantum.crm.domain.eventos.dto.MarcarOcurridoRequest
import pe.quantum.crm.domain.notificaciones.NotificacionService
import pe.quantum.crm.domain.oportunidades.OportunidadService
import pe.quantum.crm.domain.oportunidades.dto.OportunidadVinculo
import pe.quantum.crm.shared.enums.EstadoEvento
import pe.quantum.crm.shared.enums.EstadoOportunidad
import pe.quantum.crm.shared.exception.EstadoInvalidoException
import pe.quantum.crm.shared.exception.NoEncontradoException
import pe.quantum.crm.shared.exception.ValidacionException
import pe.quantum.crm.shared.security.UsuarioActual
import java.time.Instant
import java.time.LocalDate
import java.util.Optional

/**
 * Ciclo de vida de un evento: origen excluyente al crearlo (reglas §5.1),
 * ocurrido/descartado y edicion. Va en su propio archivo para no engordar
 * `EventoServiceImplTest`, que cubre notificaciones y recordatorios.
 */
class EventoServiceImplCicloVidaTest {
    private val eventoRepository = mockk<EventoRepository>()
    private val catalogoEventoService = mockk<CatalogoEventoService>()
    private val oportunidadService = mockk<OportunidadService>(relaxed = true)
    private val empresaService = mockk<EmpresaService>(relaxed = true)
    private val empleadoService = mockk<EmpleadoService>(relaxed = true)
    private val notificacionService = mockk<NotificacionService>(relaxed = true)
    private val service =
        EventoServiceImpl(eventoRepository, catalogoEventoService, oportunidadService, empresaService, empleadoService, notificacionService)

    private val usuario = UsuarioActual(id = 1, rol = "vendedor")

    private fun oportunidadVinculo() = OportunidadVinculo(id = 50, idEmpresa = 10, idVendedor = 1, estado = "evaluacion_calidda")

    private fun catalogo(
        id: Long = 5,
        disparaCambioEstado: Boolean = false,
        estadoDestino: EstadoOportunidad? = null,
    ) = CatalogoEventoDto(
        id = id,
        nombre = "Firma de contrato",
        etapaAsociada = null,
        disparaCambioEstado = disparaCambioEstado,
        estadoDestino = estadoDestino?.name,
        esRecomendado = false,
        esHitoProspeccion = false,
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

    // ── crear: origen mutuamente excluyente (§5.1) ─────────────

    @Test
    fun `un evento personalizado guarda su nombre y nunca dispara cambio de estado`() {
        val slot = slot<Evento>()
        every { oportunidadService.vinculoVisible(50, usuario) } returns oportunidadVinculo()
        every { eventoRepository.save(capture(slot)) } answers { slot.captured.conId(9) }

        val dto =
            service.crearEnOportunidad(
                50,
                CrearEventoRequest(
                    esPersonalizado = true,
                    nombrePersonalizado = "Visita a la planta de Ate",
                    descripcion = "Coordinar con el jefe de flota",
                    fechaEstimada = LocalDate.of(2026, 8, 1),
                    fechaSeguimiento = LocalDate.of(2026, 8, 8),
                ),
                usuario,
            )

        assertThat(dto.nombre).isEqualTo("Visita a la planta de Ate")
        assertThat(dto.esPersonalizado).isTrue()
        assertThat(dto.idCatalogoEvento).isNull()
        assertThat(dto.disparaCambioEstado).isFalse()
        assertThat(dto.estadoDestino).isNull()
        assertThat(dto.fechaEstimada).isEqualTo(LocalDate.of(2026, 8, 1))
        assertThat(dto.fechaSeguimiento).isEqualTo(LocalDate.of(2026, 8, 8))
        // Un personalizado no lleva entrada de catalogo: no debe consultarse.
        verify(exactly = 0) { catalogoEventoService.porId(any()) }
    }

    @Test
    fun `un evento personalizado sin nombre lanza VALIDACION`() {
        every { oportunidadService.vinculoVisible(50, usuario) } returns oportunidadVinculo()

        val ex =
            assertThrows<ValidacionException> {
                service.crearEnOportunidad(50, CrearEventoRequest(esPersonalizado = true), usuario)
            }

        assertThat(ex.field).isEqualTo("nombre_personalizado")
        verify(exactly = 0) { eventoRepository.save(any()) }
    }

    @Test
    fun `un evento personalizado con nombre en blanco lanza VALIDACION`() {
        every { oportunidadService.vinculoVisible(50, usuario) } returns oportunidadVinculo()

        val ex =
            assertThrows<ValidacionException> {
                service.crearEnOportunidad(50, CrearEventoRequest(esPersonalizado = true, nombrePersonalizado = "   "), usuario)
            }

        assertThat(ex.field).isEqualTo("nombre_personalizado")
    }

    @Test
    fun `un evento personalizado que ademas referencia el catalogo lanza VALIDACION`() {
        every { oportunidadService.vinculoVisible(50, usuario) } returns oportunidadVinculo()

        val ex =
            assertThrows<ValidacionException> {
                service.crearEnOportunidad(
                    50,
                    CrearEventoRequest(esPersonalizado = true, nombrePersonalizado = "Visita", idCatalogoEvento = 5),
                    usuario,
                )
            }

        assertThat(ex.field).isEqualTo("id_catalogo_evento")
    }

    @Test
    fun `un evento del catalogo sin id_catalogo_evento lanza VALIDACION`() {
        every { oportunidadService.vinculoVisible(50, usuario) } returns oportunidadVinculo()

        val ex = assertThrows<ValidacionException> { service.crearEnOportunidad(50, CrearEventoRequest(), usuario) }

        assertThat(ex.field).isEqualTo("id_catalogo_evento")
    }

    @Test
    fun `un evento del catalogo hereda dispara_cambio_estado y estado_destino de su entrada`() {
        val entrada = catalogo(disparaCambioEstado = true, estadoDestino = EstadoOportunidad.documentos_legales)
        every { oportunidadService.vinculoVisible(50, usuario) } returns oportunidadVinculo()
        every { catalogoEventoService.porId(5) } returns entrada
        every { catalogoEventoService.todosPorId() } returns mapOf(5L to entrada)
        every { eventoRepository.save(any()) } answers { firstArg<Evento>().conId(9) }

        val dto = service.crearEnOportunidad(50, CrearEventoRequest(idCatalogoEvento = 5), usuario)

        assertThat(dto.nombre).isEqualTo("Firma de contrato")
        assertThat(dto.disparaCambioEstado).isTrue()
        assertThat(dto.estadoDestino).isEqualTo("documentos_legales")
    }

    // ── marcar ocurrido ────────────────────────────────────────

    private fun eventoPendiente(
        estado: EstadoEvento = EstadoEvento.pendiente,
        dispara: Boolean = false,
        destino: EstadoOportunidad? = null,
    ) = Evento(
        id = 7,
        idOportunidad = 50,
        idEmpresa = null,
        idCatalogoEvento = 5,
        descripcion = "Esperando reporte",
        estado = estado,
        fechaEstimada = LocalDate.of(2026, 7, 10),
        disparaCambioEstado = dispara,
        estadoDestino = destino,
        createdBy = 1,
        updatedBy = 1,
    )

    @Test
    fun `marcar ocurrido devuelve la sugerencia pero NO cambia el estado de la oportunidad`() {
        // Invariante #4 de CLAUDE.md / reglas §5.3: el backend sugiere, el cambio
        // es una segunda llamada confirmada a PATCH /oportunidades/:id/estado.
        val evento = eventoPendiente(dispara = true, destino = EstadoOportunidad.documentos_legales)
        every { eventoRepository.findById(7) } returns Optional.of(evento)
        every { oportunidadService.vinculoVisible(50, usuario) } returns oportunidadVinculo()
        every { eventoRepository.save(evento) } returns evento

        val resultado = service.marcarOcurrido(7, MarcarOcurridoRequest(), usuario)

        assertThat(resultado.estado).isEqualTo("ocurrido")
        assertThat(resultado.sugerencia?.dispara).isTrue()
        assertThat(resultado.sugerencia?.estadoDestino).isEqualTo("documentos_legales")
        assertThat(resultado.sugerencia?.mensaje).isEqualTo("¿Deseas mover la oportunidad a Documentos Legales?")
        verify(exactly = 0) { oportunidadService.cambiarEstado(any(), any(), any()) }
    }

    @Test
    fun `la sugerencia usa la etiqueta legible de cada estado destino del pipeline`() {
        val etiquetas =
            mapOf(
                EstadoOportunidad.evaluacion_calidda to "Evaluación Calidda",
                EstadoOportunidad.documentos_legales to "Documentos Legales",
                EstadoOportunidad.facturado to "Facturado",
                EstadoOportunidad.cerrado to "Cerrado",
            )

        etiquetas.forEach { (destino, etiqueta) ->
            val evento = eventoPendiente(dispara = true, destino = destino)
            every { eventoRepository.findById(7) } returns Optional.of(evento)
            every { oportunidadService.vinculoVisible(50, usuario) } returns oportunidadVinculo()
            every { eventoRepository.save(evento) } returns evento

            val resultado = service.marcarOcurrido(7, MarcarOcurridoRequest(), usuario)

            assertThat(resultado.sugerencia?.mensaje)
                .describedAs("etiqueta de %s", destino)
                .isEqualTo("¿Deseas mover la oportunidad a $etiqueta?")
        }
    }

    @Test
    fun `marcar ocurrido con fecha explicita la respeta y guarda quien lo registro`() {
        val evento = eventoPendiente()
        every { eventoRepository.findById(7) } returns Optional.of(evento)
        every { oportunidadService.vinculoVisible(50, usuario) } returns oportunidadVinculo()
        every { eventoRepository.save(evento) } returns evento

        val resultado =
            service.marcarOcurrido(
                7,
                MarcarOcurridoRequest(fechaOcurrencia = Instant.parse("2026-07-09T16:30:00Z"), descripcion = "Llegó el reporte"),
                usuario,
            )

        assertThat(resultado.fechaOcurrencia).isEqualTo(Instant.parse("2026-07-09T16:30:00Z"))
        assertThat(evento.descripcion).isEqualTo("Llegó el reporte")
        assertThat(evento.registradoPor).isEqualTo(1)
    }

    @Test
    fun `solo un evento pendiente puede marcarse como ocurrido`() {
        every { eventoRepository.findById(7) } returns Optional.of(eventoPendiente(estado = EstadoEvento.descartado))
        every { oportunidadService.vinculoVisible(50, usuario) } returns oportunidadVinculo()

        assertThrows<EstadoInvalidoException> { service.marcarOcurrido(7, MarcarOcurridoRequest(), usuario) }
        verify(exactly = 0) { eventoRepository.save(any()) }
    }

    // ── marcar descartado ──────────────────────────────────────

    @Test
    fun `descartar un evento pendiente lo deja descartado con su motivo`() {
        val evento = eventoPendiente()
        every { eventoRepository.findById(7) } returns Optional.of(evento)
        every { oportunidadService.vinculoVisible(50, usuario) } returns oportunidadVinculo()
        every { eventoRepository.save(evento) } returns evento
        every { catalogoEventoService.todosPorId() } returns mapOf(5L to catalogo())

        val dto = service.marcarDescartado(7, MarcarDescartadoRequest(descripcion = "El cliente canceló"), usuario)

        assertThat(dto.estado).isEqualTo("descartado")
        assertThat(dto.descripcion).isEqualTo("El cliente canceló")
        assertThat(evento.updatedBy).isEqualTo(1)
    }

    @Test
    fun `descartar sin descripcion conserva la que ya tenia el evento`() {
        val evento = eventoPendiente()
        every { eventoRepository.findById(7) } returns Optional.of(evento)
        every { oportunidadService.vinculoVisible(50, usuario) } returns oportunidadVinculo()
        every { eventoRepository.save(evento) } returns evento
        every { catalogoEventoService.todosPorId() } returns mapOf(5L to catalogo())

        val dto = service.marcarDescartado(7, MarcarDescartadoRequest(), usuario)

        assertThat(dto.descripcion).isEqualTo("Esperando reporte")
    }

    @Test
    fun `solo un evento pendiente puede descartarse`() {
        every { eventoRepository.findById(7) } returns Optional.of(eventoPendiente(estado = EstadoEvento.ocurrido))
        every { oportunidadService.vinculoVisible(50, usuario) } returns oportunidadVinculo()

        assertThrows<EstadoInvalidoException> { service.marcarDescartado(7, MarcarDescartadoRequest(), usuario) }
    }

    // ── actualizar ─────────────────────────────────────────────

    @Test
    fun `solo se pueden editar eventos pendientes`() {
        every { eventoRepository.findById(7) } returns Optional.of(eventoPendiente(estado = EstadoEvento.ocurrido))
        every { oportunidadService.vinculoVisible(50, usuario) } returns oportunidadVinculo()

        assertThrows<EstadoInvalidoException> {
            service.actualizar(7, ActualizarEventoRequest(descripcion = "otra cosa"), usuario)
        }
    }

    @Test
    fun `actualizar mueve la fecha de seguimiento sin tocar el dedup de recordatorios`() {
        // El dedup solo se reinicia con `fecha_estimada`, que es la que dispara el recordatorio.
        val evento = eventoPendiente()
        every { eventoRepository.findById(7) } returns Optional.of(evento)
        every { oportunidadService.vinculoVisible(50, usuario) } returns oportunidadVinculo()
        every { eventoRepository.save(any()) } answers { firstArg() }
        every { catalogoEventoService.todosPorId() } returns mapOf(5L to catalogo())

        val dto = service.actualizar(7, ActualizarEventoRequest(fechaSeguimiento = LocalDate.of(2026, 7, 25)), usuario)

        assertThat(dto.fechaSeguimiento).isEqualTo(LocalDate.of(2026, 7, 25))
        verify(exactly = 0) { notificacionService.reiniciarRecordatorios(any(), any()) }
    }

    // ── visibilidad (IDOR → 404) ───────────────────────────────

    @Test
    fun `un evento inexistente devuelve 404`() {
        every { eventoRepository.findById(99) } returns Optional.empty()

        assertThrows<NoEncontradoException> { service.marcarOcurrido(99, MarcarOcurridoRequest(), usuario) }
    }

    @Test
    fun `un evento sin oportunidad ni empresa devuelve 404 en vez de saltarse el filtro`() {
        // Fila imposible segun el CHECK de V21; si apareciera, no debe quedar
        // accesible para cualquiera por no tener por donde comprobar visibilidad.
        val huerfano = Evento(id = 7, idOportunidad = null, idEmpresa = null, createdBy = 1, updatedBy = 1)
        every { eventoRepository.findById(7) } returns Optional.of(huerfano)

        assertThrows<NoEncontradoException> { service.marcarDescartado(7, MarcarDescartadoRequest(), usuario) }
    }

    // ── listado por oportunidad ────────────────────────────────

    @Test
    fun `listar por oportunidad separa los eventos en pendientes, ocurridos y descartados`() {
        every { oportunidadService.vinculoVisible(50, usuario) } returns oportunidadVinculo()
        every { catalogoEventoService.todosPorId() } returns mapOf(5L to catalogo())
        every { eventoRepository.findByIdOportunidadOrderByIdAsc(50) } returns
            listOf(
                Evento(id = 1, idOportunidad = 50, idCatalogoEvento = 5, estado = EstadoEvento.pendiente, createdBy = 1, updatedBy = 1),
                Evento(id = 2, idOportunidad = 50, idCatalogoEvento = 5, estado = EstadoEvento.ocurrido, createdBy = 1, updatedBy = 1),
                Evento(id = 3, idOportunidad = 50, idCatalogoEvento = 5, estado = EstadoEvento.descartado, createdBy = 1, updatedBy = 1),
            )

        val resultado = service.listarPorOportunidad(50, usuario)

        assertThat(resultado.pendientes.map { it.id }).containsExactly(1)
        assertThat(resultado.ocurridos.map { it.id }).containsExactly(2)
        assertThat(resultado.descartados.map { it.id }).containsExactly(3)
    }

    @Test
    fun `listar por oportunidad sin eventos ni siquiera consulta el catalogo`() {
        every { oportunidadService.vinculoVisible(50, usuario) } returns oportunidadVinculo()
        every { eventoRepository.findByIdOportunidadOrderByIdAsc(50) } returns emptyList()

        val resultado = service.listarPorOportunidad(50, usuario)

        assertThat(resultado.pendientes).isEmpty()
        assertThat(resultado.ocurridos).isEmpty()
        assertThat(resultado.descartados).isEmpty()
        verify(exactly = 0) { catalogoEventoService.todosPorId() }
    }

    @Test
    fun `listar eventos de una oportunidad ajena o inexistente devuelve 404`() {
        every { oportunidadService.vinculoVisible(99, usuario) } throws NoEncontradoException("La oportunidad no existe")

        assertThrows<NoEncontradoException> { service.listarPorOportunidad(99, usuario) }
    }
}
