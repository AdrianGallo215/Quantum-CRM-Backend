package pe.quantum.crm.domain.modelos

import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import pe.quantum.crm.domain.modelos.dto.ActualizarModeloRequest
import pe.quantum.crm.domain.modelos.dto.CrearModeloRequest
import pe.quantum.crm.shared.enums.Aplicacion
import pe.quantum.crm.shared.exception.ConflictoException
import pe.quantum.crm.shared.exception.ModeloSinAplicacionesException
import pe.quantum.crm.shared.exception.NoEncontradoException
import java.math.BigDecimal
import java.util.Optional

class ModeloServiceImplTest {
    private val modeloRepository = mockk<ModeloRepository>()
    private val service = ModeloServiceImpl(modeloRepository)

    private fun requestValido(
        codigo: String = "KW-100",
        aplicaciones: List<Aplicacion>? = listOf(Aplicacion.urbano),
    ) = CrearModeloRequest(
        codigo = codigo,
        longitud = BigDecimal("12.5"),
        capacidadTanques = "2x200L",
        maxAsientos = 40,
        precioBase = BigDecimal("350000"),
        fichaTecnica = "ficha.pdf",
        aplicaciones = aplicaciones,
    )

    @Test
    fun `crear con codigo duplicado lanza ConflictoException`() {
        every { modeloRepository.existsByCodigo("KW-100") } returns true

        assertThatThrownBy { service.crear(requestValido()) }
            .isInstanceOf(ConflictoException::class.java)

        verify(exactly = 0) { modeloRepository.save(any()) }
    }

    @Test
    fun `crear sin aplicaciones lanza ModeloSinAplicacionesException`() {
        assertThatThrownBy { service.crear(requestValido(aplicaciones = emptyList())) }
            .isInstanceOf(ModeloSinAplicacionesException::class.java)

        verify(exactly = 0) { modeloRepository.existsByCodigo(any()) }
        verify(exactly = 0) { modeloRepository.save(any()) }
    }

    @Test
    fun `crear con aplicaciones null lanza ModeloSinAplicacionesException`() {
        assertThatThrownBy { service.crear(requestValido(aplicaciones = null)) }
            .isInstanceOf(ModeloSinAplicacionesException::class.java)
    }

    @Test
    fun `crear happy path persiste y devuelve el dto`() {
        every { modeloRepository.existsByCodigo("KW-100") } returns false
        val guardado = slot<Modelo>()
        every { modeloRepository.save(capture(guardado)) } answers {
            Modelo(
                id = 7,
                codigo = guardado.captured.codigo,
                longitud = guardado.captured.longitud,
                capacidadTanques = guardado.captured.capacidadTanques,
                maxAsientos = guardado.captured.maxAsientos,
                precioBase = guardado.captured.precioBase,
                fichaTecnica = guardado.captured.fichaTecnica,
                aplicaciones = guardado.captured.aplicaciones,
            )
        }

        val dto = service.crear(requestValido())

        assertThat(dto.id).isEqualTo(7)
        assertThat(dto.codigo).isEqualTo("KW-100")
        assertThat(dto.precioBase).isEqualTo("350000")
        assertThat(dto.aplicaciones).containsExactly("urbano")
        verify { modeloRepository.save(any()) }
    }

    @Test
    fun `actualizar de un id inexistente lanza NoEncontradoException`() {
        every { modeloRepository.findById(99) } returns Optional.empty()

        assertThatThrownBy { service.actualizar(99, ActualizarModeloRequest(codigo = "X")) }
            .isInstanceOf(NoEncontradoException::class.java)

        verify(exactly = 0) { modeloRepository.save(any()) }
    }

    /**
     * La unicidad se validaba solo al crear. Al actualizar, el choque llegaba a la
     * constraint y salia como `CONFLICTO_DATOS` generico: otro codigo de error para
     * el mismo problema, y sin `field` con el que el frontend pueda marcar el input.
     */
    @Test
    fun `cambiar el codigo a uno que ya existe devuelve CODIGO_DUPLICADO`() {
        every { modeloRepository.findById(1) } returns
            Optional.of(Modelo(id = 1, codigo = "KW-8", aplicaciones = mutableSetOf(Aplicacion.urbano)))
        every { modeloRepository.existsByCodigoAndIdNot("KW-12", 1) } returns true

        val ex = assertThrows<ConflictoException> { service.actualizar(1, ActualizarModeloRequest(codigo = "KW-12")) }

        assertThat(ex.code).isEqualTo("CODIGO_DUPLICADO")
        assertThat(ex.field).isEqualTo("codigo")
    }

    /** Reenviar su propio codigo no es un duplicado: es una actualizacion parcial normal. */
    @Test
    fun `reenviar el mismo codigo no dispara el conflicto`() {
        val modelo = Modelo(id = 1, codigo = "KW-8", aplicaciones = mutableSetOf(Aplicacion.urbano))
        every { modeloRepository.findById(1) } returns Optional.of(modelo)
        every { modeloRepository.save(any()) } answers { firstArg() }

        service.actualizar(1, ActualizarModeloRequest(codigo = "KW-8"))

        verify(exactly = 0) { modeloRepository.existsByCodigoAndIdNot(any(), any()) }
    }

    @Test
    fun `listar mapea entidades a dto ordenadas por codigo`() {
        val modeloB =
            Modelo(id = 2, codigo = "KW-200", aplicaciones = mutableSetOf(Aplicacion.turismo))
        val modeloA =
            Modelo(id = 1, codigo = "KW-100", aplicaciones = mutableSetOf(Aplicacion.urbano))
        every { modeloRepository.findAll() } returns listOf(modeloB, modeloA)

        val resultado = service.listar()

        assertThat(resultado).extracting("codigo").containsExactly("KW-100", "KW-200")
    }
}
