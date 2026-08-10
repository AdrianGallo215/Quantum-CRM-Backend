package pe.quantum.crm.domain.catalogoeventos

import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import pe.quantum.crm.domain.catalogoeventos.dto.ActualizarCatalogoEventoRequest
import pe.quantum.crm.domain.catalogoeventos.dto.CrearCatalogoEventoRequest
import pe.quantum.crm.shared.enums.EstadoOportunidad
import pe.quantum.crm.shared.exception.ConflictoException
import pe.quantum.crm.shared.exception.NoEncontradoException
import pe.quantum.crm.shared.exception.ValidacionException
import java.util.Optional

class CatalogoEventoServiceImplTest {
    private val repository = mockk<CatalogoEventoRepository>()
    private val service = CatalogoEventoServiceImpl(repository)

    private fun requestValido(
        nombre: String = "Visita inicial",
        dispara: Boolean = false,
        destino: EstadoOportunidad? = null,
    ) = CrearCatalogoEventoRequest(
        nombre = nombre,
        etapaAsociada = EstadoOportunidad.evaluacion_calidda,
        disparaCambioEstado = dispara,
        estadoDestino = destino,
        esRecomendado = true,
        esHitoProspeccion = false,
    )

    @Test
    fun `crear con nombre duplicado lanza ConflictoException`() {
        every { repository.existsByNombre("Visita inicial") } returns true

        assertThatThrownBy { service.crear(requestValido()) }
            .isInstanceOf(ConflictoException::class.java)

        verify(exactly = 0) { repository.save(any()) }
    }

    @Test
    fun `crear que dispara cambio de estado sin estado destino lanza ValidacionException`() {
        assertThatThrownBy {
            service.crear(requestValido(dispara = true, destino = null))
        }.isInstanceOf(ValidacionException::class.java)

        verify(exactly = 0) { repository.existsByNombre(any()) }
        verify(exactly = 0) { repository.save(any()) }
    }

    @Test
    fun `crear happy path persiste y devuelve el dto`() {
        every { repository.existsByNombre("Visita inicial") } returns false
        val guardado = slot<CatalogoEvento>()
        every { repository.save(capture(guardado)) } answers {
            CatalogoEvento(
                id = 1,
                nombre = guardado.captured.nombre,
                etapaAsociada = guardado.captured.etapaAsociada,
                disparaCambioEstado = guardado.captured.disparaCambioEstado,
                estadoDestino = guardado.captured.estadoDestino,
                esRecomendado = guardado.captured.esRecomendado,
                esHitoProspeccion = guardado.captured.esHitoProspeccion,
            )
        }

        val dto = service.crear(requestValido())

        assertThat(dto.id).isEqualTo(1)
        assertThat(dto.nombre).isEqualTo("Visita inicial")
    }

    @Test
    fun `hitosProspeccion devuelve solo los marcados como hito en orden`() {
        val hito1 = CatalogoEvento(id = 1, nombre = "Primer contacto", esHitoProspeccion = true)
        val hito2 = CatalogoEvento(id = 3, nombre = "Cita agendada", esHitoProspeccion = true)
        every { repository.findByEsHitoProspeccionTrueOrderById() } returns listOf(hito1, hito2)

        val resultado = service.hitosProspeccion()

        assertThat(resultado).extracting("id").containsExactly(1L, 3L)
        assertThat(resultado).allMatch { it.esHitoProspeccion }
    }

    @Test
    fun `todosPorId indexa los eventos por id`() {
        val evento1 = CatalogoEvento(id = 1, nombre = "Uno")
        val evento2 = CatalogoEvento(id = 2, nombre = "Dos")
        every { repository.findAll() } returns listOf(evento1, evento2)

        val resultado = service.todosPorId()

        assertThat(resultado).containsOnlyKeys(1L, 2L)
        assertThat(resultado.getValue(1L).nombre).isEqualTo("Uno")
        assertThat(resultado.getValue(2L).nombre).isEqualTo("Dos")
    }

    @Test
    fun `listar con etapaAsociada filtra por esa etapa`() {
        val evento = CatalogoEvento(id = 1, nombre = "Uno", etapaAsociada = EstadoOportunidad.facturado)
        every { repository.findByEtapaAsociada(EstadoOportunidad.facturado) } returns listOf(evento)

        val resultado = service.listar(EstadoOportunidad.facturado)

        assertThat(resultado).hasSize(1)
        assertThat(resultado.first().etapaAsociada).isEqualTo("facturado")
        verify(exactly = 0) { repository.findAll() }
    }

    @Test
    fun `listar sin etapaAsociada devuelve todos`() {
        val evento1 = CatalogoEvento(id = 1, nombre = "Uno")
        every { repository.findAll() } returns listOf(evento1)

        val resultado = service.listar(null)

        assertThat(resultado).hasSize(1)
        verify(exactly = 0) { repository.findByEtapaAsociada(any()) }
    }

    @Test
    fun `actualizar de un id inexistente lanza NoEncontradoException`() {
        every { repository.findById(99) } returns Optional.empty()

        assertThatThrownBy {
            service.actualizar(99, ActualizarCatalogoEventoRequest(nombre = "X"))
        }.isInstanceOf(NoEncontradoException::class.java)

        verify(exactly = 0) { repository.save(any()) }
    }
}
