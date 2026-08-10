package pe.quantum.crm.domain.prospeccion

import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import pe.quantum.crm.domain.catalogoeventos.CatalogoEventoService
import pe.quantum.crm.domain.catalogoeventos.dto.CatalogoEventoDto
import pe.quantum.crm.shared.security.UsuarioActual
import java.time.LocalDateTime

class ProspeccionServiceImplTest {
    private val dao = mockk<ProspeccionDao>()
    private val catalogoEventoService = mockk<CatalogoEventoService>()
    private val service = ProspeccionServiceImpl(dao, catalogoEventoService)

    private val gerencia = UsuarioActual(id = 1, rol = "gerencia")

    private fun hito(
        id: Long,
        nombre: String,
    ) = CatalogoEventoDto(
        id = id,
        nombre = nombre,
        etapaAsociada = null,
        disparaCambioEstado = false,
        estadoDestino = null,
        esRecomendado = false,
        esHitoProspeccion = true,
    )

    /** Una empresa en prospeccion con los hitos de catalogo y ocurridos indicados. */
    private fun escenario(
        hitosCatalogo: List<CatalogoEventoDto>,
        hitosOcurridos: Map<Pair<Long, Long>, LocalDateTime?>,
    ) {
        every { dao.empresasEnProspeccion(null) } returns
            listOf(
                EmpresaProspeccionRow(
                    id = 10L,
                    ruc = "20512345678",
                    razonSocial = "Transportes Andinos SAC",
                    distrito = "Surco",
                    createdAt = LocalDateTime.now().minusDays(3),
                ),
            )
        every { catalogoEventoService.hitosProspeccion() } returns hitosCatalogo
        every { dao.hitosOcurridos(listOf(10L)) } returns hitosOcurridos
        every { dao.ultimaActividad(listOf(10L)) } returns emptyMap()
        every { dao.siguienteTarea(listOf(10L)) } returns emptyMap()
        every { dao.segmentos(listOf(10L)) } returns emptyMap()
        every { dao.contactoPrincipal(listOf(10L)) } returns emptyMap()
    }

    @Test
    fun `un hito ocurrido sin fecha cuenta como completado, con la fecha en null`() {
        // El hito ocurrio (hay un evento en estado 'ocurrido'); solo se desconoce
        // cuando. Perder el avance por no tener la fecha falsearia el embudo.
        escenario(
            hitosCatalogo = listOf(hito(7L, "Primera visita")),
            hitosOcurridos = mapOf((10L to 7L) to null),
        )

        val item = service.listar(gerencia, page = null, perPage = null).items.single()

        assertThat(item.hitos.single().completado).isTrue()
        assertThat(item.hitos.single().fecha).isNull()
        assertThat(item.checkpointsCompletados).isEqualTo(1)
    }

    @Test
    fun `un hito ocurrido sin fecha no impide marcar la empresa lista para convertir`() {
        escenario(
            hitosCatalogo = listOf(hito(7L, "Primera visita"), hito(8L, "Cotizacion enviada")),
            hitosOcurridos =
                mapOf(
                    (10L to 7L) to null,
                    (10L to 8L) to LocalDateTime.of(2026, 3, 14, 9, 30),
                ),
        )

        val item = service.listar(gerencia, page = null, perPage = null).items.single()

        assertThat(item.checkpointsCompletados).isEqualTo(2)
        assertThat(item.listaParaConvertir).isTrue()
    }

    @Test
    fun `un hito sin evento asociado sigue sin completarse`() {
        escenario(
            hitosCatalogo = listOf(hito(7L, "Primera visita")),
            hitosOcurridos = emptyMap(),
        )

        val item = service.listar(gerencia, page = null, perPage = null).items.single()

        assertThat(item.hitos.single().completado).isFalse()
        assertThat(item.checkpointsCompletados).isZero()
        assertThat(item.listaParaConvertir).isFalse()
    }
}
