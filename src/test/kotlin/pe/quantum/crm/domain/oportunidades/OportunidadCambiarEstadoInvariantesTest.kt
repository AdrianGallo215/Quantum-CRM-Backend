package pe.quantum.crm.domain.oportunidades

import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import jakarta.persistence.LockModeType
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import pe.quantum.crm.domain.contactos.ContactoService
import pe.quantum.crm.domain.empleados.EmpleadoService
import pe.quantum.crm.domain.empleados.dto.EmpleadoResumen
import pe.quantum.crm.domain.empresas.EmpresaService
import pe.quantum.crm.domain.empresas.dto.EmpresaResumen
import pe.quantum.crm.domain.financiadoras.FinanciadoraService
import pe.quantum.crm.domain.modelos.ModeloService
import pe.quantum.crm.domain.notificaciones.NotificacionService
import pe.quantum.crm.domain.oportunidades.dto.ActualizarOportunidadRequest
import pe.quantum.crm.domain.oportunidades.dto.CambiarEstadoRequest
import pe.quantum.crm.domain.tareas.TareaService
import pe.quantum.crm.integracion.drive.DriveStorageService
import pe.quantum.crm.shared.enums.EstadoOportunidad
import pe.quantum.crm.shared.exception.EstadoInvalidoException
import pe.quantum.crm.shared.exception.MontoNoEditableException
import pe.quantum.crm.shared.exception.MotivoCierreRequeridoException
import pe.quantum.crm.shared.exception.PermisoInsuficienteException
import pe.quantum.crm.shared.security.UsuarioActual
import java.math.BigDecimal
import java.time.LocalDateTime

/**
 * A1 (docs/plan-ejecucion-subagentes.md, tarea C.2): un test por invariante de
 * `OportunidadServiceImpl.cambiarEstado`. Vive en un archivo propio (en vez de
 * dentro de `OportunidadServiceImplTest`) porque juntar los dos disparaba
 * `LargeClass` en detekt; la division sigue el mismo criterio que ya usaba el
 * archivo original para sus propias secciones (comentarios `// ── ... ───`).
 *
 * Las reglas que cubre ya estaban implementadas y son correctas en
 * `OportunidadServiceImpl.cambiarEstado`; lo que faltaba era la aserción que
 * impida que alguien las borre sin darse cuenta.
 */
class OportunidadCambiarEstadoInvariantesTest {
    private val oportunidadRepository = mockk<OportunidadRepository>()
    private val logRepository = mockk<OportunidadEstadoLogRepository>()
    private val contactoOportunidadRepository = mockk<OportunidadContactoRepository>()
    private val estadoCarteraService = mockk<EstadoCarteraService>()
    private val empresaService = mockk<EmpresaService>()
    private val empleadoService = mockk<EmpleadoService>()
    private val financiadoraService = mockk<FinanciadoraService>()
    private val modeloService = mockk<ModeloService>()
    private val contactoService = mockk<ContactoService>()
    private val consultas = mockk<OportunidadConsultas>()
    private val notificacionService = mockk<NotificacionService>(relaxed = true)
    private val driveStorageService = mockk<DriveStorageService>(relaxed = true)
    private val tareaService = mockk<TareaService>()
    private val service =
        OportunidadServiceImpl(
            oportunidadRepository,
            logRepository,
            contactoOportunidadRepository,
            estadoCarteraService,
            empresaService,
            empleadoService,
            financiadoraService,
            modeloService,
            contactoService,
            consultas,
            notificacionService,
            driveStorageService,
            tareaService,
        )

    private fun oportunidad(
        id: Long = 100,
        idVendedor: Long = 1,
    ) = Oportunidad(
        id = id,
        idEmpresa = 10,
        idVendedor = idVendedor,
        idFinanciadora = 1,
        idModelo = 1,
        estado = EstadoOportunidad.evaluacion_calidda,
        cantidad = 1,
        precioUnitario = BigDecimal.TEN,
        dcto = BigDecimal.ZERO,
        montoTotal = BigDecimal.TEN,
        createdAt = LocalDateTime.now(),
        createdBy = 1,
        updatedAt = LocalDateTime.now(),
        updatedBy = 1,
    )

    /**
     * Stubs minimos para que `cambiarEstado` complete su transaccion, incluida
     * `notificarCambioEstado` (que SIEMPRE se invoca al final, exito o no-op de
     * cartera). `empresaService`/`empleadoService` no son mocks relajados: sin
     * esto, cualquier escenario que llegue a persistir lanzaria por falta de
     * stub, no por la aserción que se quiere probar.
     */
    private fun stubsDeCambioEstado(
        entidad: Oportunidad,
        estadoDesde: EstadoOportunidad = entidad.estado,
    ) {
        val id = requireNotNull(entidad.id)
        every { oportunidadRepository.findByIdBloqueando(id) } returns entidad
        every { oportunidadRepository.save(entidad) } returns entidad
        every { logRepository.save(any()) } returns mockk()
        every { consultas.eventosRecomendadosSinRegistrar(id, estadoDesde) } returns emptyList()
        every { estadoCarteraService.actualizar(entidad.idEmpresa) } returns null
        every { empresaService.resumenPorIds(listOf(entidad.idEmpresa)) } returns
            mapOf(entidad.idEmpresa to EmpresaResumen(id = entidad.idEmpresa, razonSocial = "Kincar S.A.C.", distrito = null))
        every { empleadoService.resumenPorIds(any()) } returns
            mapOf(entidad.idVendedor to EmpleadoResumen(id = entidad.idVendedor, nombres = "Ana", apellidos = "Diaz"))
        every { empleadoService.idsSupervisoresActivos() } returns listOf(1)
    }

    @Test
    fun `cerrar sin motivo_cierre lanza MOTIVO_CIERRE_REQUERIDO`() {
        val entidad = oportunidad(idVendedor = 1)
        every { oportunidadRepository.findByIdBloqueando(100) } returns entidad

        assertThatThrownBy {
            service.cambiarEstado(100, CambiarEstadoRequest(estado = "cerrado"), UsuarioActual(id = 1, rol = "admin"))
        }.isInstanceOf(MotivoCierreRequeridoException::class.java)

        verify(exactly = 0) { oportunidadRepository.save(any()) }
    }

    @Test
    fun `cerrar con motivo lo persiste en la oportunidad`() {
        val entidad = oportunidad(idVendedor = 1)
        stubsDeCambioEstado(entidad)

        service.cambiarEstado(
            100,
            CambiarEstadoRequest(estado = "cerrado", motivoCierre = "Cliente eligió otro proveedor"),
            UsuarioActual(id = 1, rol = "admin"),
        )

        assertThat(entidad.motivoCierre).isEqualTo("Cliente eligió otro proveedor")
    }

    @Test
    fun `retroceder desde cerrado limpia motivo_cierre`() {
        val entidad =
            oportunidad(idVendedor = 1).apply {
                estado = EstadoOportunidad.cerrado
                motivoCierre = "Motivo viejo"
            }
        stubsDeCambioEstado(entidad, estadoDesde = EstadoOportunidad.cerrado)

        service.cambiarEstado(
            100,
            CambiarEstadoRequest(estado = "documentos_legales"),
            UsuarioActual(id = 1, rol = "admin"),
        )

        assertThat(entidad.motivoCierre).isNull()
    }

    @Test
    fun `un vendedor no puede pasar a facturado`() {
        val entidad = oportunidad(idVendedor = 1)
        every { oportunidadRepository.findByIdBloqueando(100) } returns entidad

        assertThatThrownBy {
            service.cambiarEstado(100, CambiarEstadoRequest(estado = "facturado"), UsuarioActual(id = 1, rol = "vendedor"))
        }.isInstanceOf(PermisoInsuficienteException::class.java)

        verify(exactly = 0) { oportunidadRepository.save(any()) }
    }

    @Test
    fun `un jdv tampoco puede pasar a facturado`() {
        val entidad = oportunidad(idVendedor = 1)
        every { oportunidadRepository.findByIdBloqueando(100) } returns entidad

        assertThatThrownBy {
            service.cambiarEstado(100, CambiarEstadoRequest(estado = "facturado"), UsuarioActual(id = 1, rol = "jdv"))
        }.isInstanceOf(PermisoInsuficienteException::class.java)

        verify(exactly = 0) { oportunidadRepository.save(any()) }
    }

    @Test
    fun `admin y gerencia si pueden pasar a facturado`() {
        listOf("admin", "gerencia").forEach { rol ->
            val entidad = oportunidad(idVendedor = 1)
            stubsDeCambioEstado(entidad)

            service.cambiarEstado(100, CambiarEstadoRequest(estado = "facturado"), UsuarioActual(id = 1, rol = rol))

            assertThat(entidad.facturadoEn).describedAs("rol=$rol debe poder facturar").isNotNull()
        }
    }

    @Test
    fun `es_retroceso viene en true al volver a un estado anterior`() {
        val entidad = oportunidad(idVendedor = 1).apply { estado = EstadoOportunidad.documentos_legales }
        stubsDeCambioEstado(entidad, estadoDesde = EstadoOportunidad.documentos_legales)

        val resultado =
            service.cambiarEstado(
                100,
                CambiarEstadoRequest(estado = "evaluacion_calidda"),
                UsuarioActual(id = 1, rol = "admin"),
            )

        assertThat(resultado.esRetroceso).isTrue()
    }

    @Test
    fun `es_retroceso viene en false al avanzar`() {
        val entidad = oportunidad(idVendedor = 1)
        stubsDeCambioEstado(entidad)

        val resultado =
            service.cambiarEstado(
                100,
                CambiarEstadoRequest(estado = "documentos_legales"),
                UsuarioActual(id = 1, rol = "admin"),
            )

        assertThat(resultado.esRetroceso).isFalse()
    }

    @Test
    fun `cambiar al mismo estado lanza ESTADO_INVALIDO`() {
        val entidad = oportunidad(idVendedor = 1)
        every { oportunidadRepository.findByIdBloqueando(100) } returns entidad

        assertThatThrownBy {
            service.cambiarEstado(
                100,
                CambiarEstadoRequest(estado = "evaluacion_calidda"),
                UsuarioActual(id = 1, rol = "admin"),
            )
        }.isInstanceOf(EstadoInvalidoException::class.java)
    }

    @Test
    fun `un estado desconocido lanza ESTADO_INVALIDO`() {
        val entidad = oportunidad(idVendedor = 1)
        every { oportunidadRepository.findByIdBloqueando(100) } returns entidad

        assertThatThrownBy {
            service.cambiarEstado(100, CambiarEstadoRequest(estado = "perdido"), UsuarioActual(id = 1, rol = "admin"))
        }.isInstanceOf(EstadoInvalidoException::class.java)
    }

    @Test
    fun `cambiarEstado registra una fila en el log con el estado anterior y el nuevo`() {
        val entidad = oportunidad(idVendedor = 1)
        stubsDeCambioEstado(entidad)
        val logGuardado = slot<OportunidadEstadoLog>()
        every { logRepository.save(capture(logGuardado)) } returns mockk()

        service.cambiarEstado(
            100,
            CambiarEstadoRequest(estado = "documentos_legales"),
            UsuarioActual(id = 1, rol = "admin"),
        )

        assertThat(logGuardado.captured.estadoAnterior).isEqualTo(EstadoOportunidad.evaluacion_calidda)
        assertThat(logGuardado.captured.estadoNuevo).isEqualTo(EstadoOportunidad.documentos_legales)
    }

    /**
     * `CrearOportunidadRequest` no declara `montoTotal` (ver el comentario del
     * DTO): un valor en el body simplemente se ignora por `@JsonIgnoreProperties`,
     * no hay ninguna ruta de codigo que lanzar MONTO_NO_EDITABLE desde `crear`. El
     * caso de `crear` de la tarea C.2.12 no aplica; solo `actualizar` puede
     * recibirlo.
     */
    @Test
    fun `enviar monto_total en actualizar lanza MONTO_NO_EDITABLE`() {
        assertThatThrownBy {
            service.actualizar(100, ActualizarOportunidadRequest(montoTotal = BigDecimal("500.00")), UsuarioActual(id = 1, rol = "admin"))
        }.isInstanceOf(MontoNoEditableException::class.java)

        verify(exactly = 0) { oportunidadRepository.save(any()) }
    }

    /**
     * El bloqueo es lo que impide que dos PATCH simultaneos lean el mismo
     * `estado_anterior` y escriban dos filas de log con el mismo origen. No se puede
     * demostrar la concurrencia sin base de datos, pero si se puede fijar que el
     * camino de escritura pasa por el finder que bloquea y no por el que no.
     */
    @Test
    fun `cambiarEstado lee la oportunidad con el finder que bloquea la fila`() {
        val entidad = oportunidad(idVendedor = 1)
        every { oportunidadRepository.findByIdBloqueando(100) } returns entidad
        every { oportunidadRepository.save(entidad) } returns entidad
        every { logRepository.save(any()) } returns mockk()
        every { consultas.eventosRecomendadosSinRegistrar(100, entidad.estado) } returns emptyList()
        every { estadoCarteraService.actualizar(entidad.idEmpresa) } returns null
        every { empresaService.resumenPorIds(listOf(entidad.idEmpresa)) } returns
            mapOf(entidad.idEmpresa to EmpresaResumen(id = entidad.idEmpresa, razonSocial = "Kincar S.A.C.", distrito = null))
        every { empleadoService.resumenPorIds(any()) } returns
            mapOf(entidad.idVendedor to EmpleadoResumen(id = entidad.idVendedor, nombres = "Ana", apellidos = "Diaz"))
        every { empleadoService.idsSupervisoresActivos() } returns listOf(1)

        service.cambiarEstado(100, CambiarEstadoRequest(estado = "documentos_legales"), UsuarioActual(id = 1, rol = "admin"))

        verify(exactly = 1) { oportunidadRepository.findByIdBloqueando(100) }
        verify(exactly = 0) { oportunidadRepository.findById(any()) }
    }

    /** La anotacion ES el fix: sin ella la query no bloquea nada aunque el nombre lo sugiera. */
    @Test
    fun `el finder de cambiarEstado declara bloqueo pesimista de escritura`() {
        val metodo = OportunidadRepository::class.java.getMethod("findByIdBloqueando", Long::class.java)

        val lock = metodo.getAnnotation(org.springframework.data.jpa.repository.Lock::class.java)

        assertThat(lock).isNotNull
        assertThat(lock.value).isEqualTo(LockModeType.PESSIMISTIC_WRITE)
    }
}
