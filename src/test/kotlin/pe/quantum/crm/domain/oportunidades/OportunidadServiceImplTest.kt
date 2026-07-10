package pe.quantum.crm.domain.oportunidades

import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import pe.quantum.crm.domain.contactos.ContactoService
import pe.quantum.crm.domain.empleados.EmpleadoService
import pe.quantum.crm.domain.empleados.dto.EmpleadoResumen
import pe.quantum.crm.domain.empresas.EmpresaService
import pe.quantum.crm.domain.empresas.dto.CambioEstadoCartera
import pe.quantum.crm.domain.empresas.dto.EmpresaResumen
import pe.quantum.crm.domain.financiadoras.FinanciadoraService
import pe.quantum.crm.domain.modelos.ModeloService
import pe.quantum.crm.domain.notificaciones.EntidadNotificacion
import pe.quantum.crm.domain.notificaciones.NotificacionService
import pe.quantum.crm.domain.notificaciones.TipoNotificacion
import pe.quantum.crm.shared.enums.EstadoCartera
import pe.quantum.crm.shared.security.UsuarioActual
import java.time.LocalDateTime
import java.util.Optional

class OportunidadServiceImplTest {
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
        )

    private fun oportunidad(idVendedor: Long = 1) =
        Oportunidad(
            id = 100,
            idEmpresa = 10,
            idVendedor = idVendedor,
            idFinanciadora = 1,
            idModelo = 1,
            estado = pe.quantum.crm.shared.enums.EstadoOportunidad.evaluacion_calidda,
            cantidad = 1,
            precioUnitario = java.math.BigDecimal.TEN,
            dcto = java.math.BigDecimal.ZERO,
            montoTotal = java.math.BigDecimal.TEN,
            createdAt = LocalDateTime.now(),
            createdBy = 1,
            updatedAt = LocalDateTime.now(),
            updatedBy = 1,
        )

    @Test
    fun `traspasar notifica al vendedor destino`() {
        val entidad = oportunidad(idVendedor = 1)
        every { oportunidadRepository.findById(100) } returns Optional.of(entidad)
        every { empleadoService.existeActivo(2) } returns true
        every { oportunidadRepository.save(entidad) } returns entidad
        every { empleadoService.resumenPorIds(listOf(9)) } returns
            mapOf(9L to EmpleadoResumen(id = 9, nombres = "Luis", apellidos = "Soto"))
        every { empresaService.resumenPorIds(listOf(10)) } returns
            mapOf(10L to EmpresaResumen(id = 10, razonSocial = "Kincar S.A.C.", distrito = null))

        service.traspasar(100, 2, UsuarioActual(id = 9, rol = "jdv"))

        verify {
            notificacionService.notificar(
                destinatarios = setOf(2L),
                idActor = 9L,
                tipo = TipoNotificacion.oportunidad_traspasada,
                mensaje = "Luis Soto te traspasó la oportunidad de Kincar S.A.C.",
                entidadTipo = EntidadNotificacion.oportunidad,
                entidadId = 100L,
            )
        }
        assertThat(entidad.idVendedor).isEqualTo(2)
    }

    @Test
    fun `cambiarEstado notifica a los supervisores activos, excluyendo al actor si es supervisor`() {
        val entidad = oportunidad(idVendedor = 1)
        every { oportunidadRepository.findById(100) } returns Optional.of(entidad)
        every { oportunidadRepository.save(entidad) } returns entidad
        every { logRepository.save(any()) } returns mockk()
        every {
            consultas.eventosRecomendadosSinRegistrar(100, pe.quantum.crm.shared.enums.EstadoOportunidad.evaluacion_calidda)
        } returns emptyList()
        every { estadoCarteraService.actualizar(10) } returns null
        every { empresaService.resumenPorIds(listOf(10)) } returns
            mapOf(10L to EmpresaResumen(id = 10, razonSocial = "Kincar S.A.C.", distrito = null))
        every { empleadoService.resumenPorIds(listOf(1)) } returns mapOf(1L to EmpleadoResumen(id = 1, nombres = "Ana", apellidos = "Diaz"))
        every { empleadoService.idsSupervisoresActivos() } returns listOf(1, 5, 6)

        service.cambiarEstado(
            100,
            pe.quantum.crm.domain.oportunidades.dto.CambiarEstadoRequest(estado = "documentos_legales"),
            UsuarioActual(id = 1, rol = "vendedor"),
        )

        verify {
            notificacionService.notificar(
                destinatarios = setOf(1L, 5L, 6L),
                idActor = 1L,
                tipo = TipoNotificacion.oportunidad_cambio_estado,
                mensaje = "Ana Diaz cambió el estado de Kincar S.A.C. a Documentos legales",
                entidadTipo = EntidadNotificacion.oportunidad,
                entidadId = 100L,
            )
        }
    }

    @Test
    fun `crear notifica empresa_convertida cuando la empresa pasa de prospeccion a oportunidad_activa`() {
        every { empresaService.vinculoVisible(10, any()) } returns
            pe.quantum.crm.domain.empresas.dto.EmpresaVinculo(
                id = 10,
                razonSocial = "Kincar S.A.C.",
                idVendedor = 3,
                estadoCartera = "prospeccion",
            )
        every { modeloService.resumen(1) } returns
            pe.quantum.crm.domain.modelos.dto.ModeloResumen(id = 1, codigo = "BUS-X", precioBase = java.math.BigDecimal.TEN)
        every { financiadoraService.default() } returns
            pe.quantum.crm.domain.financiadoras.dto.FinanciadoraDto(
                id = 1,
                nombre = "Calidda",
                montoPorUnidad = null,
                plazoMeses = null,
                tea = null,
                cuotaPorUnidad = null,
                esDefault = true,
                notas = null,
            )
        val guardada = slot<Oportunidad>()
        every { oportunidadRepository.save(capture(guardada)) } answers {
            // El id es autogenerado (IDENTITY); en el mock lo poblamos por reflexion
            // igual que lo haria Hibernate al persistir, para simular el id devuelto.
            guardada.captured.also {
                val idField = Oportunidad::class.java.getDeclaredField("id")
                idField.isAccessible = true
                idField.set(it, 100L)
            }
        }
        every { logRepository.save(any()) } returns mockk()
        every {
            estadoCarteraService.actualizar(10)
        } returns CambioEstadoCartera(anterior = EstadoCartera.prospeccion, nuevo = EstadoCartera.oportunidad_activa)
        every {
            empresaService.resumenPorIds(listOf(10))
        } returns mapOf(10L to EmpresaResumen(id = 10, razonSocial = "Kincar S.A.C.", distrito = null))
        every { empleadoService.resumenPorIds(listOf(3)) } returns
            mapOf(3L to EmpleadoResumen(id = 3, nombres = "Jose", apellidos = "Lima"))
        every { empleadoService.idsSupervisoresActivos() } returns listOf(9)
        // Stubs adicionales requeridos por el ensamblado de OportunidadDto (toDto/toDtos).
        every { financiadoraService.porIds(listOf(1)) } returns
            mapOf(
                1L to
                    pe.quantum.crm.domain.financiadoras.dto.FinanciadoraDto(
                        id = 1,
                        nombre = "Calidda",
                        montoPorUnidad = null,
                        plazoMeses = null,
                        tea = null,
                        cuotaPorUnidad = null,
                        esDefault = true,
                        notas = null,
                    ),
            )
        every { modeloService.resumenPorIds(listOf(1)) } returns
            mapOf(
                1L to
                    pe.quantum.crm.domain.modelos.dto.ModeloResumen(
                        id = 1,
                        codigo = "BUS-X",
                        precioBase = java.math.BigDecimal.TEN,
                    ),
            )
        every { consultas.tareasPendientesPorOportunidad(listOf(100L)) } returns emptyMap()
        every { consultas.eventosPendientesPorOportunidad(listOf(100L)) } returns emptyMap()
        every { contactoOportunidadRepository.findByIdIdOportunidad(100L) } returns emptyList()
        every { contactoService.resumenPorIds(emptyList()) } returns emptyMap()
        every { logRepository.findFirstByIdOportunidadOrderByChangedAtDescIdDesc(100L) } returns null

        service.crear(
            pe.quantum.crm.domain.oportunidades.dto.CrearOportunidadRequest(
                idEmpresa = 10,
                idModelo = 1,
                cantidad = 1,
                dcto = java.math.BigDecimal.ZERO,
            ),
            UsuarioActual(id = 3, rol = "vendedor"),
        )

        verify {
            notificacionService.notificar(
                destinatarios = setOf(9L),
                idActor = 3L,
                tipo = TipoNotificacion.empresa_convertida,
                mensaje = "Jose Lima convirtió Kincar S.A.C. de prospección a oportunidad",
                entidadTipo = EntidadNotificacion.oportunidad,
                entidadId = any(),
            )
        }
    }
}
