package pe.quantum.crm.domain.actividades

import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import pe.quantum.crm.domain.actividades.dto.HistorialFiltros
import pe.quantum.crm.domain.empleados.EmpleadoService
import pe.quantum.crm.domain.empresas.EmpresaService
import pe.quantum.crm.domain.eventos.EventoService
import pe.quantum.crm.domain.eventos.dto.EventoDto
import pe.quantum.crm.domain.tareas.TareaService
import pe.quantum.crm.domain.tareas.dto.TareaDto
import pe.quantum.crm.shared.exception.PermisoInsuficienteException
import pe.quantum.crm.shared.security.UsuarioActual
import java.time.Instant
import java.time.LocalDate

/**
 * Historial unificado. Lo que estos tests protegen:
 *  1. Un rol NO supervisor no puede leer la agenda de otro (403).
 *  2. Tareas y eventos se mezclan y se ordenan por `created_at`, no por lista.
 *  3. Las fechas DATE y TIMESTAMP no se cruzan (Trampa 1).
 */
class HistorialActividadServiceImplTest {
    private val tareaService = mockk<TareaService>(relaxed = true)
    private val eventoService = mockk<EventoService>(relaxed = true)
    private val comentarioService = mockk<ComentarioActividadService>(relaxed = true)
    private val auditoriaService = mockk<AuditoriaActividadService>(relaxed = true)
    private val empresaService = mockk<EmpresaService>(relaxed = true)
    private val empleadoService = mockk<EmpleadoService>(relaxed = true)
    private val service =
        HistorialActividadServiceImpl(
            tareaService,
            eventoService,
            comentarioService,
            auditoriaService,
            empresaService,
            empleadoService,
        )

    private val gerencia = UsuarioActual(id = 1, rol = "gerencia")
    private val jdv = UsuarioActual(id = 2, rol = "jdv")
    private val vendedor = UsuarioActual(id = 9, rol = "vendedor")

    private fun tareaDto(
        id: Long,
        createdAt: Instant,
    ) = TareaDto(
        id = id, idEmpresa = 3, empresa = null, idOportunidad = null, idContacto = null,
        contacto = null, idAsignado = 7, asignado = null, idsColaboradores = emptyList(),
        colaboradores = emptyList(), tipoAccion = "llamada", estadoAccion = "pendiente",
        descripcion = null, fechaEjecucion = Instant.parse("2026-09-15T15:00:00Z"), createdAt = createdAt,
    )

    private fun eventoDto(
        id: Long,
        createdAt: Instant,
    ) = EventoDto(
        id = id, idOportunidad = 20, idEmpresa = null, idCatalogoEvento = null,
        nombre = "Visita a planta", esPersonalizado = true, descripcion = null,
        estado = "pendiente", fechaEstimada = LocalDate.of(2026, 9, 20), fechaSeguimiento = null,
        fechaOcurrencia = null, disparaCambioEstado = false, estadoDestino = null,
        esRecomendado = false, etapaAsociada = null, esHitoProspeccion = false,
        createdBy = 7, createdAt = createdAt,
    )

    @Test
    fun `un vendedor no puede ver el historial de otro empleado`() {
        assertThatThrownBy {
            service.historial(HistorialFiltros(idEmpleado = 7), vendedor, null, null)
        }.isInstanceOf(PermisoInsuficienteException::class.java)
    }

    @Test
    fun `un vendedor si puede ver su propio historial`() {
        every { tareaService.listarPorEmpleado(9, any(), any(), vendedor) } returns emptyList()
        every { eventoService.listarPorEmpleado(9, any(), any(), vendedor) } returns emptyList()

        val resultado = service.historial(HistorialFiltros(idEmpleado = 9), vendedor, null, null)

        assertThat(resultado.items).isEmpty()
    }

    @Test
    fun `gerencia y jdv pueden ver el historial de cualquier empleado`() {
        every { tareaService.listarPorEmpleado(7, any(), any(), any()) } returns emptyList()
        every { eventoService.listarPorEmpleado(7, any(), any(), any()) } returns emptyList()

        assertThat(service.historial(HistorialFiltros(idEmpleado = 7), gerencia, null, null).items).isEmpty()
        assertThat(service.historial(HistorialFiltros(idEmpleado = 7), jdv, null, null).items).isEmpty()
    }

    @Test
    fun `tareas y eventos se mezclan ordenados por created_at descendente`() {
        every { tareaService.listarPorEmpleado(7, any(), any(), gerencia) } returns
            listOf(tareaDto(1, Instant.parse("2026-09-01T10:00:00Z")))
        every { eventoService.listarPorEmpleado(7, any(), any(), gerencia) } returns
            listOf(eventoDto(2, Instant.parse("2026-09-05T10:00:00Z")))

        val resultado = service.historial(HistorialFiltros(idEmpleado = 7), gerencia, null, null)

        // El evento es mas reciente, va primero, aunque las tareas se pidieran antes.
        assertThat(resultado.items.map { it.tipo }).containsExactly("evento", "tarea")
        assertThat(resultado.meta.total).isEqualTo(2)
    }

    @Test
    fun `una tarea nunca lleva fecha de dia y un evento conserva la suya`() {
        every { tareaService.listarPorEmpleado(7, any(), any(), gerencia) } returns
            listOf(tareaDto(1, Instant.parse("2026-09-01T10:00:00Z")))
        every { eventoService.listarPorEmpleado(7, any(), any(), gerencia) } returns
            listOf(eventoDto(2, Instant.parse("2026-09-05T10:00:00Z")))

        val resultado = service.historial(HistorialFiltros(idEmpleado = 7), gerencia, null, null)

        val tarea = resultado.items.single { it.tipo == "tarea" }
        val evento = resultado.items.single { it.tipo == "evento" }
        assertThat(tarea.fechaDia).isNull()
        assertThat(tarea.fechaHora).isEqualTo(Instant.parse("2026-09-15T15:00:00Z"))
        assertThat(evento.fechaDia).isEqualTo(LocalDate.of(2026, 9, 20))
        assertThat(evento.fechaHora).isNull()
    }

    @Test
    fun `el filtro de tipo devuelve solo esa clase de actividad`() {
        every { tareaService.listarPorEmpleado(7, any(), any(), gerencia) } returns
            listOf(tareaDto(1, Instant.parse("2026-09-01T10:00:00Z")))
        every { eventoService.listarPorEmpleado(7, any(), any(), gerencia) } returns
            listOf(eventoDto(2, Instant.parse("2026-09-05T10:00:00Z")))

        val soloTareas = service.historial(HistorialFiltros(idEmpleado = 7, tipo = "tarea"), gerencia, null, null)

        assertThat(soloTareas.items.map { it.tipo }).containsExactly("tarea")
    }

    @Test
    fun `el filtro de oportunidad descarta lo que no cuelga de ella`() {
        every { tareaService.listarPorEmpleado(7, any(), any(), gerencia) } returns
            listOf(tareaDto(1, Instant.parse("2026-09-01T10:00:00Z")))
        every { eventoService.listarPorEmpleado(7, any(), any(), gerencia) } returns
            listOf(eventoDto(2, Instant.parse("2026-09-05T10:00:00Z")))

        // La tarea tiene idOportunidad null; el evento cuelga de la 20.
        val resultado = service.historial(HistorialFiltros(idEmpleado = 7, idOportunidad = 20), gerencia, null, null)

        assertThat(resultado.items.map { it.id }).containsExactly(2L)
    }

    @Test
    fun `la paginacion en memoria corta la pagina y calcula el total`() {
        every { tareaService.listarPorEmpleado(7, any(), any(), gerencia) } returns
            (1L..5L).map { tareaDto(it, Instant.parse("2026-09-0${it}T10:00:00Z")) }
        every { eventoService.listarPorEmpleado(7, any(), any(), gerencia) } returns emptyList()

        val pagina2 = service.historial(HistorialFiltros(idEmpleado = 7), gerencia, page = 2, perPage = 2)

        assertThat(pagina2.items).hasSize(2)
        assertThat(pagina2.meta.page).isEqualTo(2)
        assertThat(pagina2.meta.perPage).isEqualTo(2)
        assertThat(pagina2.meta.total).isEqualTo(5)
        assertThat(pagina2.meta.totalPages).isEqualTo(3)
    }

    @Test
    fun `el conteo de comentarios llega a cada actividad`() {
        every { tareaService.listarPorEmpleado(7, any(), any(), gerencia) } returns
            listOf(tareaDto(1, Instant.parse("2026-09-01T10:00:00Z")))
        every { eventoService.listarPorEmpleado(7, any(), any(), gerencia) } returns emptyList()
        every { comentarioService.contar(TipoActividad.tarea, listOf(1L)) } returns mapOf(1L to 3)

        val resultado = service.historial(HistorialFiltros(idEmpleado = 7), gerencia, null, null)

        assertThat(resultado.items.first().comentarios).isEqualTo(3)
    }

    @Test
    fun `la auditoria exige que la actividad sea visible antes de devolverla`() {
        every { tareaService.vinculoVisible(5, gerencia) } returns
            pe.quantum.crm.domain.tareas.dto.TareaVinculo(id = 5, idEmpresa = 3, idOportunidad = null, idAsignado = 7)
        every { auditoriaService.historial(TipoActividad.tarea, 5) } returns emptyList()

        val resultado = service.auditoria(TipoActividad.tarea, 5, gerencia)

        assertThat(resultado).isEmpty()
    }
}
