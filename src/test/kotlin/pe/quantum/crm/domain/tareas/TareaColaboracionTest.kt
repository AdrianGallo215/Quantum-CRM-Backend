package pe.quantum.crm.domain.tareas

import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import pe.quantum.crm.domain.contactos.ContactoService
import pe.quantum.crm.domain.empleados.EmpleadoService
import pe.quantum.crm.domain.empresas.EmpresaService
import pe.quantum.crm.domain.notificaciones.NotificacionService
import pe.quantum.crm.domain.oportunidades.OportunidadService

/**
 * La frontera entre `tareas` y los modulos que consultan colaboracion:
 * devuelve ids planos, nunca entidades ni Specifications (ArquitecturaModulosTest).
 */
class TareaColaboracionTest {
    private val tareaRepository = mockk<TareaRepository>()
    private val tareaResponsableRepository = mockk<TareaResponsableRepository>(relaxed = true)
    private val empresaService = mockk<EmpresaService>()
    private val oportunidadService = mockk<OportunidadService>()
    private val contactoService = mockk<ContactoService>()
    private val empleadoService = mockk<EmpleadoService>()
    private val notificacionService = mockk<NotificacionService>(relaxed = true)
    private val service =
        TareaServiceImpl(
            tareaRepository,
            tareaResponsableRepository,
            empresaService,
            oportunidadService,
            contactoService,
            empleadoService,
            notificacionService,
        )

    @Test
    fun `el servicio deduplica los ids de oportunidad que devuelve el repositorio`() {
        every { tareaRepository.idsOportunidadConColaborador(7L) } returns listOf(10L, 20L, 10L)

        assertThat(service.idsOportunidadesDondeColabora(7L)).containsExactlyInAnyOrder(10L, 20L)
    }

    @Test
    fun `el servicio devuelve vacio cuando el empleado no colabora en nada`() {
        every { tareaRepository.idsEmpresaConColaborador(9L) } returns emptyList()

        assertThat(service.idsEmpresasDondeColabora(9L)).isEmpty()
    }

    @Test
    fun `el servicio delega en el repositorio y no filtra en memoria`() {
        every { tareaRepository.idsEmpresaConColaborador(7L) } returns listOf(1L, 2L)

        assertThat(service.idsEmpresasDondeColabora(7L)).containsExactlyInAnyOrder(1L, 2L)
        io.mockk.verify(exactly = 1) { tareaRepository.idsEmpresaConColaborador(7L) }
    }
}
