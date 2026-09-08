package pe.quantum.crm.domain.actividades

import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import pe.quantum.crm.domain.empleados.EmpleadoService
import pe.quantum.crm.domain.empleados.dto.EmpleadoResumen
import pe.quantum.crm.domain.eventos.EventoService
import pe.quantum.crm.domain.eventos.dto.EventoVinculo
import pe.quantum.crm.domain.tareas.TareaService
import pe.quantum.crm.domain.tareas.dto.TareaVinculo
import pe.quantum.crm.shared.exception.NoEncontradoException
import pe.quantum.crm.shared.exception.ValidacionException
import pe.quantum.crm.shared.security.UsuarioActual
import java.time.LocalDateTime

/**
 * Comentarios de seguimiento. Lo que estos tests protegen:
 *  1. Un comentario NUNCA pisa otro (append-only): es la razon de que exista la
 *     tabla en vez de reusar `descripcion`.
 *  2. No se puede comentar una actividad que no ves (IDOR -> 404).
 */
class ComentarioActividadServiceImplTest {
    private val repository = mockk<ActividadComentarioRepository>()
    private val tareaService = mockk<TareaService>()
    private val eventoService = mockk<EventoService>()
    private val empleadoService = mockk<EmpleadoService>(relaxed = true)
    private val service = ComentarioActividadServiceImpl(repository, tareaService, eventoService, empleadoService)

    private val supervisor = UsuarioActual(id = 1, rol = "gerencia")
    private val vendedor = UsuarioActual(id = 9, rol = "vendedor")

    private fun comentario(
        id: Long,
        texto: String,
        idTarea: Long? = 5,
    ) = ActividadComentario(
        id = id,
        idTarea = idTarea,
        idEvento = null,
        texto = texto,
        createdAt = LocalDateTime.of(2026, 9, 1, 10, 0),
        createdBy = 1,
    )

    @Test
    fun `crear un comentario sobre una tarea visible lo persiste`() {
        every { tareaService.vinculoVisible(5, supervisor) } returns
            TareaVinculo(id = 5, idEmpresa = 3, idOportunidad = null, idAsignado = 7)
        val guardado = slot<ActividadComentario>()
        every { repository.save(capture(guardado)) } answers { comentario(id = 1, texto = guardado.captured.texto) }
        every { empleadoService.resumenPorIds(listOf(1L)) } returns
            mapOf(1L to EmpleadoResumen(id = 1, nombres = "Ana", apellidos = "Ruiz"))

        val dto = service.crear(TipoActividad.tarea, 5, "Llame y no contesto", supervisor)

        assertThat(guardado.captured.idTarea).isEqualTo(5)
        assertThat(guardado.captured.idEvento).isNull()
        assertThat(guardado.captured.texto).isEqualTo("Llame y no contesto")
        assertThat(guardado.captured.createdBy).isEqualTo(1)
        assertThat(dto.autor?.nombres).isEqualTo("Ana")
    }

    @Test
    fun `crear un comentario sobre un evento visible usa la columna de evento`() {
        every { eventoService.vinculoVisible(4, supervisor) } returns
            EventoVinculo(id = 4, idOportunidad = 20, createdBy = 7)
        val guardado = slot<ActividadComentario>()
        every { repository.save(capture(guardado)) } answers {
            ActividadComentario(id = 1, idTarea = null, idEvento = 4, texto = "ok", createdBy = 1)
        }

        service.crear(TipoActividad.evento, 4, "ok", supervisor)

        assertThat(guardado.captured.idEvento).isEqualTo(4)
        assertThat(guardado.captured.idTarea).isNull()
    }

    @Test
    fun `no se puede comentar una tarea que el usuario no ve`() {
        every { tareaService.vinculoVisible(5, vendedor) } throws NoEncontradoException("La tarea no existe")

        assertThatThrownBy { service.crear(TipoActividad.tarea, 5, "hola", vendedor) }
            .isInstanceOf(NoEncontradoException::class.java)

        verify(exactly = 0) { repository.save(any()) }
    }

    @Test
    fun `un comentario en blanco se rechaza antes de tocar la base`() {
        every { tareaService.vinculoVisible(5, supervisor) } returns
            TareaVinculo(id = 5, idEmpresa = 3, idOportunidad = null, idAsignado = 7)

        assertThatThrownBy { service.crear(TipoActividad.tarea, 5, "   ", supervisor) }
            .isInstanceOf(ValidacionException::class.java)

        verify(exactly = 0) { repository.save(any()) }
    }

    @Test
    fun `listar devuelve los comentarios en orden cronologico sin perder ninguno`() {
        every { tareaService.vinculoVisible(5, supervisor) } returns
            TareaVinculo(id = 5, idEmpresa = 3, idOportunidad = null, idAsignado = 7)
        every { repository.findByIdTareaOrderByCreatedAtAsc(5) } returns
            listOf(comentario(1, "primero"), comentario(2, "segundo"))
        every { empleadoService.resumenPorIds(any()) } returns emptyMap()

        val comentarios = service.listar(TipoActividad.tarea, 5, supervisor)

        // El segundo comentario NO reemplaza al primero: los dos siguen ahi.
        assertThat(comentarios.map { it.texto }).containsExactly("primero", "segundo")
    }

    @Test
    fun `contar agrupa los comentarios por actividad`() {
        every { repository.findByIdTareaInOrderByCreatedAtAsc(listOf(5L, 6L)) } returns
            listOf(comentario(1, "a", idTarea = 5), comentario(2, "b", idTarea = 5), comentario(3, "c", idTarea = 6))

        val conteo = service.contar(TipoActividad.tarea, listOf(5L, 6L))

        assertThat(conteo[5L]).isEqualTo(2)
        assertThat(conteo[6L]).isEqualTo(1)
    }

    @Test
    fun `contar sobre una lista vacia no consulta la base`() {
        val conteo = service.contar(TipoActividad.tarea, emptyList())

        assertThat(conteo).isEmpty()
        verify(exactly = 0) { repository.findByIdTareaInOrderByCreatedAtAsc(any()) }
    }
}
