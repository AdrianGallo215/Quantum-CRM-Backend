package pe.quantum.crm.domain.tareas

import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import pe.quantum.crm.domain.contactos.ContactoService
import pe.quantum.crm.domain.empleados.EmpleadoService
import pe.quantum.crm.domain.empresas.EmpresaService
import pe.quantum.crm.domain.notificaciones.NotificacionService
import pe.quantum.crm.domain.oportunidades.OportunidadService
import pe.quantum.crm.shared.enums.EstadoAccion
import pe.quantum.crm.shared.enums.TipoAccion
import pe.quantum.crm.shared.exception.NoEncontradoException
import pe.quantum.crm.shared.security.UsuarioActual
import java.time.Instant
import java.time.LocalDateTime
import java.util.Optional

/**
 * `listarPorEmpleado` y `vinculoVisible`: las dos puertas que el modulo
 * actividades usa para componer el historial sin tocar la entidad Tarea
 * (CLAUDE.md regla 12).
 */
class TareaHistorialTest {
    private val tareaRepository = mockk<TareaRepository>()
    private val tareaResponsableRepository = mockk<TareaResponsableRepository>(relaxed = true)
    private val empresaService = mockk<EmpresaService>(relaxed = true)
    private val oportunidadService = mockk<OportunidadService>(relaxed = true)
    private val contactoService = mockk<ContactoService>(relaxed = true)
    private val empleadoService = mockk<EmpleadoService>(relaxed = true)
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

    private val supervisor = UsuarioActual(id = 1, rol = "gerencia")
    private val vendedor = UsuarioActual(id = 9, rol = "vendedor")

    private fun tarea(
        id: Long = 5,
        idAsignado: Long? = 7,
    ) = Tarea(
        id = id,
        idEmpresa = 3,
        idOportunidad = null,
        idAsignado = idAsignado,
        tipoAccion = TipoAccion.llamada,
        estadoAccion = EstadoAccion.pendiente,
        createdAt = LocalDateTime.of(2026, 9, 1, 10, 0),
        createdBy = 7,
        updatedAt = LocalDateTime.of(2026, 9, 1, 10, 0),
        updatedBy = 7,
    )

    @Test
    fun `listarPorEmpleado sin rango consulta desde el inicio hasta el fin de los tiempos`() {
        every {
            tareaRepository.findByIdAsignadoAndCreatedAtBetweenOrderByCreatedAtDesc(7, any(), any())
        } returns listOf(tarea())

        val resultado = service.listarPorEmpleado(7, null, null, supervisor)

        assertThat(resultado).hasSize(1)
        assertThat(resultado.first().id).isEqualTo(5)
    }

    @Test
    fun `listarPorEmpleado respeta el rango de fechas recibido`() {
        val desde = Instant.parse("2026-09-01T00:00:00Z")
        val hasta = Instant.parse("2026-09-30T23:59:59Z")
        every {
            tareaRepository.findByIdAsignadoAndCreatedAtBetweenOrderByCreatedAtDesc(
                7,
                LocalDateTime.of(2026, 9, 1, 0, 0),
                LocalDateTime.of(2026, 9, 30, 23, 59, 59),
            )
        } returns listOf(tarea())

        val resultado = service.listarPorEmpleado(7, desde, hasta, supervisor)

        assertThat(resultado).hasSize(1)
    }

    @Test
    fun `un vendedor no obtiene las tareas de otro empleado`() {
        // El vendedor pide el historial del empleado 7, que no es el suyo (9).
        every {
            tareaRepository.findByIdAsignadoAndCreatedAtBetweenOrderByCreatedAtDesc(7, any(), any())
        } returns listOf(tarea(idAsignado = 7))
        every { tareaResponsableRepository.findByIdIdTareaIn(any()) } returns emptyList()

        val resultado = service.listarPorEmpleado(7, null, null, vendedor)

        assertThat(resultado).isEmpty()
    }

    @Test
    fun `vinculoVisible devuelve los datos minimos de la tarea`() {
        every { tareaRepository.findById(5) } returns Optional.of(tarea())

        val vinculo = service.vinculoVisible(5, supervisor)

        assertThat(vinculo.id).isEqualTo(5)
        assertThat(vinculo.idEmpresa).isEqualTo(3)
        assertThat(vinculo.idAsignado).isEqualTo(7)
    }

    @Test
    fun `vinculoVisible responde 404 sobre una tarea ajena de un rol restringido`() {
        every { tareaRepository.findById(5) } returns Optional.of(tarea(idAsignado = 7))
        every { tareaResponsableRepository.existsByIdIdTareaAndIdIdEmpleado(5, 9) } returns false

        assertThatThrownBy { service.vinculoVisible(5, vendedor) }
            .isInstanceOf(NoEncontradoException::class.java)
    }
}
