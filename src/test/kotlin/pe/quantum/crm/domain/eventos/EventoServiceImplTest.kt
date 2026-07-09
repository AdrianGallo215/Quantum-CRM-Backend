package pe.quantum.crm.domain.eventos

import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import pe.quantum.crm.domain.catalogoeventos.CatalogoEventoService
import pe.quantum.crm.domain.catalogoeventos.dto.CatalogoEventoDto
import pe.quantum.crm.domain.empresas.EmpresaService
import pe.quantum.crm.domain.empresas.dto.EmpresaVinculo
import pe.quantum.crm.domain.eventos.dto.CrearEventoRequest
import pe.quantum.crm.domain.oportunidades.OportunidadService
import pe.quantum.crm.shared.enums.EstadoCartera
import pe.quantum.crm.shared.enums.EstadoOportunidad
import pe.quantum.crm.shared.exception.ValidacionException
import pe.quantum.crm.shared.security.UsuarioActual

/**
 * Unit tests de EventoServiceImpl sin Spring ni base de datos: las 4
 * dependencias se mockean directamente con MockK.
 */
class EventoServiceImplTest {
    private val eventoRepository = mockk<EventoRepository>()
    private val catalogoEventoService = mockk<CatalogoEventoService>()
    private val oportunidadService = mockk<OportunidadService>()
    private val empresaService = mockk<EmpresaService>()
    private val service = EventoServiceImpl(eventoRepository, catalogoEventoService, oportunidadService, empresaService)

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
}
