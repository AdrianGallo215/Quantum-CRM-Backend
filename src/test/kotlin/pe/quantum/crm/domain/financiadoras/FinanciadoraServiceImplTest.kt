package pe.quantum.crm.domain.financiadoras

import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
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
     * Hallazgo [Medio] ABIERTO (docs/plan-ejecucion-subagentes.md, Agente D.2): actualizar la
     * financiadora que HOY es default poniendo `esDefault = false` no tiene ninguna validacion
     * que lo impida ni que reasigne otra default. El sistema queda sin ninguna financiadora
     * default (rompe el invariante que documenta `FinanciadoraDefaultInexistenteException`).
     * Este test documenta el comportamiento ACTUAL, no lo corrige.
     */
    @Test
    fun `actualizar la financiadora default desmarcandola deja el sistema sin default - hallazgo abierto`() {
        val financiadora = Financiadora(id = 5, nombre = "Calidda", esDefault = true)
        every { financiadoraRepository.findById(5) } returns Optional.of(financiadora)
        every { financiadoraRepository.save(financiadora) } returns financiadora

        val dto = service.actualizar(5, ActualizarFinanciadoraRequest(esDefault = false))

        assertThat(dto.esDefault).isFalse()
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
