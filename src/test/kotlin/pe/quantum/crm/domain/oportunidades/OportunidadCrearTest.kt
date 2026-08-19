package pe.quantum.crm.domain.oportunidades

import io.mockk.CapturingSlot
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import pe.quantum.crm.domain.contactos.ContactoService
import pe.quantum.crm.domain.empleados.EmpleadoService
import pe.quantum.crm.domain.empleados.dto.EmpleadoResumen
import pe.quantum.crm.domain.empresas.EmpresaService
import pe.quantum.crm.domain.empresas.dto.EmpresaResumen
import pe.quantum.crm.domain.empresas.dto.EmpresaVinculo
import pe.quantum.crm.domain.financiadoras.FinanciadoraService
import pe.quantum.crm.domain.financiadoras.dto.FinanciadoraDto
import pe.quantum.crm.domain.modelos.ModeloService
import pe.quantum.crm.domain.modelos.dto.ModeloResumen
import pe.quantum.crm.domain.notificaciones.NotificacionService
import pe.quantum.crm.domain.oportunidades.dto.CrearOportunidadRequest
import pe.quantum.crm.domain.tareas.TareaService
import pe.quantum.crm.integracion.drive.DriveStorageService
import pe.quantum.crm.shared.enums.EstadoOportunidad
import pe.quantum.crm.shared.exception.ValidacionException
import pe.quantum.crm.shared.security.UsuarioActual
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * Ramas de `POST /oportunidades` que las demas suites no recorren: eleccion de
 * financiadora, snapshot del vendedor cuando la empresa no tiene uno
 * (reglas §8.4) y el primer registro del log con `estado_anterior = NULL`
 * (reglas §4.2 paso 6).
 */
class OportunidadCrearTest {
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

    private val busX = ModeloResumen(id = 1, codigo = "BUS-X", precioBase = BigDecimal("92000.00"))

    private fun financiadora(
        id: Long,
        nombre: String,
        esDefault: Boolean,
    ) = FinanciadoraDto(
        id = id,
        nombre = nombre,
        montoPorUnidad = null,
        plazoMeses = null,
        tea = null,
        cuotaPorUnidad = null,
        esDefault = esDefault,
        notas = null,
    )

    init {
        every { modeloService.resumen(1) } returns busX
        every { financiadoraService.default() } returns financiadora(1, "Calidda", esDefault = true)
        every { logRepository.save(any()) } returns mockk()
        every { estadoCarteraService.actualizar(10) } returns null
        every { driveStorageService.crearCarpeta(any(), any()) } returns "carpeta-op"
        every { empresaService.resumenPorIds(any()) } returns
            mapOf(10L to EmpresaResumen(id = 10, razonSocial = "Kincar S.A.C.", distrito = null))
        every { empleadoService.resumenPorIds(any()) } returns
            mapOf(5L to EmpleadoResumen(id = 5, nombres = "Ana", apellidos = "Diaz"))
        every { financiadoraService.porIds(any()) } returns
            mapOf(
                1L to financiadora(1, "Calidda", esDefault = true),
                2L to financiadora(2, "Financiera Sur", esDefault = false),
            )
        every { modeloService.resumenPorIds(any()) } returns mapOf(1L to busX)
        every { consultas.tareasPendientesPorOportunidad(any()) } returns emptyMap()
        every { consultas.eventosPendientesPorOportunidad(any()) } returns emptyMap()
        every { contactoOportunidadRepository.findByIdIdOportunidad(any()) } returns emptyList()
        every { contactoService.resumenPorIds(any()) } returns emptyMap()
        every { logRepository.findFirstByIdOportunidadOrderByChangedAtDescIdDesc(any()) } returns null
    }

    /** `save` devuelve una copia con id, como haria JPA al persistir. */
    private fun stubSave(guardada: CapturingSlot<Oportunidad>) {
        every { oportunidadRepository.save(capture(guardada)) } answers {
            val original = guardada.captured
            Oportunidad(
                id = 100,
                idEmpresa = original.idEmpresa,
                idVendedor = original.idVendedor,
                idFinanciadora = original.idFinanciadora,
                idModelo = original.idModelo,
                estado = original.estado,
                cantidad = original.cantidad,
                precioUnitario = original.precioUnitario,
                dcto = original.dcto,
                montoTotal = original.montoTotal,
                fincParalelo = original.fincParalelo,
                garantia = original.garantia,
                fichaVenta = original.fichaVenta,
                notas = original.notas,
                fechaCierreEstimado = original.fechaCierreEstimado,
                createdAt = original.createdAt,
                createdBy = original.createdBy,
                updatedAt = original.updatedAt,
                updatedBy = original.updatedBy,
            )
        }
    }

    private fun empresaConVendedor(idVendedor: Long?) =
        EmpresaVinculo(
            id = 10,
            razonSocial = "Kincar S.A.C.",
            idVendedor = idVendedor,
            estadoCartera = "prospeccion",
            driveFolderId = "carpeta-empresa",
        )

    @Test
    fun `crear toma el precio del modelo, calcula monto_total y arranca en evaluacion_calidda`() {
        every { empresaService.vinculoVisible(10, any()) } returns empresaConVendedor(5)
        val guardada = slot<Oportunidad>()
        stubSave(guardada)

        val dto =
            service.crear(
                CrearOportunidadRequest(
                    idEmpresa = 10,
                    idModelo = 1,
                    cantidad = 8,
                    dcto = BigDecimal("3.00"),
                    garantia = true,
                    fincParalelo = false,
                    fichaVenta = "FV-1",
                    notas = "Primer contacto",
                    fechaCierreEstimado = LocalDate.of(2026, 6, 30),
                ),
                UsuarioActual(id = 5, rol = "vendedor"),
            )

        assertThat(guardada.captured.estado).isEqualTo(EstadoOportunidad.evaluacion_calidda)
        assertThat(guardada.captured.precioUnitario).isEqualByComparingTo("92000.00")
        // 8 x 92000.00 x (1 - 3/100) = 713920.00
        assertThat(guardada.captured.montoTotal).isEqualByComparingTo("713920.00")
        assertThat(guardada.captured.createdBy).isEqualTo(5)
        assertThat(dto.estado).isEqualTo("evaluacion_calidda")
        assertThat(dto.montoTotal).isEqualTo("713920.00")
    }

    /** Paso 6 de reglas §4.2: la primera fila del log no tiene estado anterior. */
    @Test
    fun `crear escribe la primera fila del log con estado_anterior nulo`() {
        every { empresaService.vinculoVisible(10, any()) } returns empresaConVendedor(5)
        val guardada = slot<Oportunidad>()
        stubSave(guardada)
        val log = slot<OportunidadEstadoLog>()
        every { logRepository.save(capture(log)) } returns mockk()

        service.crear(CrearOportunidadRequest(idEmpresa = 10, idModelo = 1), UsuarioActual(id = 5, rol = "vendedor"))

        assertThat(log.captured.idOportunidad).isEqualTo(100)
        assertThat(log.captured.estadoAnterior).isNull()
        assertThat(log.captured.estadoNuevo).isEqualTo(EstadoOportunidad.evaluacion_calidda)
        assertThat(log.captured.changedBy).isEqualTo(5)
    }

    @Test
    fun `crear con id_financiadora explicita no usa la financiadora por defecto`() {
        every { empresaService.vinculoVisible(10, any()) } returns empresaConVendedor(5)
        every { financiadoraService.porId(2) } returns financiadora(2, "Financiera Sur", esDefault = false)
        val guardada = slot<Oportunidad>()
        stubSave(guardada)

        val dto =
            service.crear(
                CrearOportunidadRequest(idEmpresa = 10, idModelo = 1, idFinanciadora = 2),
                UsuarioActual(id = 5, rol = "vendedor"),
            )

        assertThat(guardada.captured.idFinanciadora).isEqualTo(2)
        assertThat(dto.financiadora?.nombre).isEqualTo("Financiera Sur")
        verify(exactly = 0) { financiadoraService.default() }
    }

    /**
     * Guard de reglas §8.4: un rol de visibilidad restringida que llega a una
     * empresa sin vendedor solo puede ser su propio vendedor. En la practica es
     * inalcanzable (la empresa le seria invisible), pero la invariante se mantiene
     * aunque la visibilidad cambie.
     */
    @Test
    fun `un vendedor en una empresa sin vendedor se queda el mismo como vendedor`() {
        every { empresaService.vinculoVisible(10, any()) } returns empresaConVendedor(null)
        val guardada = slot<Oportunidad>()
        stubSave(guardada)

        val dto = service.crear(CrearOportunidadRequest(idEmpresa = 10, idModelo = 1), UsuarioActual(id = 5, rol = "vendedor"))

        assertThat(guardada.captured.idVendedor).isEqualTo(5)
        assertThat(dto.idVendedor).isEqualTo(5)
        verify(exactly = 0) { empresaService.reasignarVendedor(any(), any(), any()) }
    }

    @Test
    fun `gerencia no puede asignar como vendedor a un empleado que no lo es`() {
        val gerencia = UsuarioActual(id = 1, rol = "gerencia")
        every { empresaService.vinculoVisible(10, gerencia) } returns empresaConVendedor(null)
        every { empleadoService.esAsignableComoVendedor(4) } returns false

        assertThatThrownBy {
            service.crear(CrearOportunidadRequest(idEmpresa = 10, idModelo = 1, idVendedor = 4), gerencia)
        }.isInstanceOf(ValidacionException::class.java)
            .hasMessageContaining("vendedor o jdv activo")

        verify(exactly = 0) { empresaService.reasignarVendedor(any(), any(), any()) }
        verify(exactly = 0) { oportunidadRepository.save(any<Oportunidad>()) }
    }

    /** La empresa sin carpeta se resuelve antes de colgar la de la oportunidad. */
    @Test
    fun `crear en una empresa sin carpeta de Drive la crea primero y anida la de la oportunidad`() {
        every { empresaService.vinculoVisible(10, any()) } returns empresaConVendedor(5).copy(driveFolderId = null)
        every { empresaService.asegurarCarpetaDrive(10) } returns "carpeta-empresa-nueva"
        every { driveStorageService.crearCarpeta("OP-100 - BUS-X", "carpeta-empresa-nueva") } returns "carpeta-op"
        val guardada = slot<Oportunidad>()
        stubSave(guardada)

        val dto = service.crear(CrearOportunidadRequest(idEmpresa = 10, idModelo = 1), UsuarioActual(id = 5, rol = "vendedor"))

        assertThat(dto.driveFolderId).isEqualTo("carpeta-op")
        verify { driveStorageService.crearCarpeta("OP-100 - BUS-X", "carpeta-empresa-nueva") }
    }

    /** `created_at` y `updated_at` de una oportunidad recien creada son el mismo instante. */
    @Test
    fun `crear sella created_at y updated_at con el mismo instante`() {
        every { empresaService.vinculoVisible(10, any()) } returns empresaConVendedor(5)
        val guardada = slot<Oportunidad>()
        stubSave(guardada)
        val antes = LocalDateTime.now()

        service.crear(CrearOportunidadRequest(idEmpresa = 10, idModelo = 1), UsuarioActual(id = 5, rol = "vendedor"))

        assertThat(guardada.captured.createdAt).isEqualTo(guardada.captured.updatedAt)
        assertThat(guardada.captured.createdAt).isAfterOrEqualTo(antes)
    }
}
