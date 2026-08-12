package pe.quantum.crm.domain.financiadoras

import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import pe.quantum.crm.domain.financiadoras.dto.ActualizarFinanciadoraRequest
import pe.quantum.crm.domain.financiadoras.dto.CrearFinanciadoraRequest
import pe.quantum.crm.shared.exception.ConflictoException
import pe.quantum.crm.shared.exception.NoEncontradoException
import java.math.BigDecimal
import java.util.Optional

class FinanciadoraServiceImplTest {
    private val financiadoraRepository = mockk<FinanciadoraRepository>()
    private val service = FinanciadoraServiceImpl(financiadoraRepository)

    private fun requestValido(
        nombre: String = "Calidda",
        esDefault: Boolean = false,
    ) = CrearFinanciadoraRequest(
        nombre = nombre,
        montoPorUnidad = BigDecimal("10000"),
        plazoMeses = 36,
        tea = BigDecimal("15.5"),
        cuotaPorUnidad = BigDecimal("400"),
        esDefault = esDefault,
        notas = "notas",
    )

    @Test
    fun `crear marcando es_default cuando ya existe una default lanza ConflictoException`() {
        every { financiadoraRepository.existsByEsDefaultTrue() } returns true

        assertThatThrownBy { service.crear(requestValido(esDefault = true)) }
            .isInstanceOf(ConflictoException::class.java)

        verify(exactly = 0) { financiadoraRepository.save(any()) }
    }

    @Test
    fun `crear sin marcar default no valida unicidad y persiste`() {
        val guardado = slot<Financiadora>()
        every { financiadoraRepository.save(capture(guardado)) } answers {
            Financiadora(
                id = 3,
                nombre = guardado.captured.nombre,
                montoPorUnidad = guardado.captured.montoPorUnidad,
                plazoMeses = guardado.captured.plazoMeses,
                tea = guardado.captured.tea,
                cuotaPorUnidad = guardado.captured.cuotaPorUnidad,
                esDefault = guardado.captured.esDefault,
                notas = guardado.captured.notas,
            )
        }

        val dto = service.crear(requestValido(esDefault = false))

        assertThat(dto.id).isEqualTo(3)
        assertThat(dto.esDefault).isFalse()
        verify(exactly = 0) { financiadoraRepository.existsByEsDefaultTrue() }
    }

    @Test
    fun `actualizar marcando es_default cuando otra ya es default lanza ConflictoException`() {
        val financiadora = Financiadora(id = 5, nombre = "Otra", esDefault = false)
        every { financiadoraRepository.findById(5) } returns Optional.of(financiadora)
        every { financiadoraRepository.existsByEsDefaultTrueAndIdNot(5) } returns true

        assertThatThrownBy { service.actualizar(5, ActualizarFinanciadoraRequest(esDefault = true)) }
            .isInstanceOf(ConflictoException::class.java)

        verify(exactly = 0) { financiadoraRepository.save(any()) }
    }

    /**
     * Quedarse sin default no da error en el momento: rompe DESPUES, en la creacion
     * de cualquier oportunidad sin `id_financiadora` explicito, y como un 500. Se
     * corta aqui, donde todavia se puede explicar.
     */
    @Test
    fun `desmarcar la unica default se rechaza`() {
        val financiadora = Financiadora(id = 1, nombre = "Calidda", esDefault = true)
        every { financiadoraRepository.findById(1) } returns Optional.of(financiadora)
        every { financiadoraRepository.existsByEsDefaultTrueAndIdNot(1) } returns false

        val ex = assertThrows<ConflictoException> { service.actualizar(1, ActualizarFinanciadoraRequest(esDefault = false)) }

        assertThat(ex.code).isEqualTo("FINANCIADORA_DEFAULT_REQUERIDA")
        assertThat(ex.field).isEqualTo("es_default")
        verify(exactly = 0) { financiadoraRepository.save(any()) }
    }

    /** Con otra ya marcada, desmarcar esta es legitimo: el sistema no se queda huerfano. */
    @Test
    fun `desmarcar la default cuando ya hay otra si se permite`() {
        val financiadora = Financiadora(id = 1, nombre = "Calidda", esDefault = true)
        every { financiadoraRepository.findById(1) } returns Optional.of(financiadora)
        every { financiadoraRepository.existsByEsDefaultTrueAndIdNot(1) } returns true
        every { financiadoraRepository.save(any()) } answers { firstArg() }

        val dto = service.actualizar(1, ActualizarFinanciadoraRequest(esDefault = false))

        assertThat(dto.esDefault).isFalse()
    }

    /** Desmarcar una que ya NO era default no puede dejar al sistema sin ninguna. */
    @Test
    fun `desmarcar una financiadora que no era default no comprueba nada`() {
        val financiadora = Financiadora(id = 2, nombre = "Otra", esDefault = false)
        every { financiadoraRepository.findById(2) } returns Optional.of(financiadora)
        every { financiadoraRepository.save(any()) } answers { firstArg() }

        service.actualizar(2, ActualizarFinanciadoraRequest(esDefault = false))

        verify(exactly = 0) { financiadoraRepository.existsByEsDefaultTrueAndIdNot(any()) }
    }

    @Test
    fun `listar mapea entidades a dto ordenadas por id`() {
        val f2 = Financiadora(id = 2, nombre = "B")
        val f1 = Financiadora(id = 1, nombre = "A")
        every { financiadoraRepository.findAll() } returns listOf(f2, f1)

        val resultado = service.listar()

        assertThat(resultado).extracting("id").containsExactly(1L, 2L)
    }

    @Test
    fun `porId inexistente lanza NoEncontradoException`() {
        every { financiadoraRepository.findById(404) } returns Optional.empty()

        assertThatThrownBy { service.porId(404) }
            .isInstanceOf(NoEncontradoException::class.java)
    }
}
