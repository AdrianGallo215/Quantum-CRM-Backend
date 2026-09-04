package pe.quantum.crm.domain.oportunidades

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import pe.quantum.crm.domain.contactos.ContactoService
import pe.quantum.crm.domain.empleados.EmpleadoService
import pe.quantum.crm.domain.empleados.dto.EmpleadoResumen
import pe.quantum.crm.domain.empresas.EmpresaService
import pe.quantum.crm.domain.empresas.dto.EmpresaResumen
import pe.quantum.crm.domain.financiadoras.FinanciadoraService
import pe.quantum.crm.domain.financiadoras.dto.FinanciadoraDto
import pe.quantum.crm.domain.modelos.ModeloService
import pe.quantum.crm.domain.modelos.dto.ModeloResumen
import pe.quantum.crm.domain.notificaciones.NotificacionService
import pe.quantum.crm.domain.oportunidades.dto.ActualizarOportunidadRequest
import pe.quantum.crm.domain.oportunidades.dto.ModeloEnOportunidadDto
import pe.quantum.crm.domain.oportunidades.dto.OportunidadItemDto
import pe.quantum.crm.domain.tareas.TareaService
import pe.quantum.crm.integracion.drive.DriveStorageService
import pe.quantum.crm.shared.enums.EstadoOportunidad
import pe.quantum.crm.shared.exception.NoEncontradoException
import pe.quantum.crm.shared.security.UsuarioActual
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.Optional

/**
 * `PUT /oportunidades/:id` (B3.4, reescrito en B9 tras D19): desde el rediseno
 * multi-item este endpoint solo toca los campos negociables de la oportunidad.
 * `id_modelo`, `cantidad`, `precio_venta` y `descuento` se editan por
 * `PUT /oportunidades/:id/items/:itemId` (ver `OportunidadItemServiceImplTest`),
 * y `monto_total` ya no se recalcula aqui: se deriva de los items (D15/D21).
 *
 * Archivo propio y no dentro de `OportunidadServiceImplTest` por el mismo motivo
 * que `OportunidadCambiarEstadoInvariantesTest`: juntarlos dispara `LargeClass`.
 */
class OportunidadActualizarTest {
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
    private val oportunidadItemService = mockk<OportunidadItemService>()
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
            OportunidadVisibilidad(tareaService),
            oportunidadItemService,
        )

    private val admin = UsuarioActual(id = 1, rol = "admin")

    private val busX = ModeloResumen(id = 1, codigo = "BUS-X", precioBase = BigDecimal("100.00"))
    private val busY = ModeloResumen(id = 2, codigo = "BUS-Y", precioBase = BigDecimal("200.00"))

    init {
        // Stubs del ensamblado del DTO de detalle (toDto/toDtos): comunes a todos
        // los escenarios y sin relacion con lo que cada test afirma.
        every { empresaService.resumenPorIds(any()) } returns
            mapOf(10L to EmpresaResumen(id = 10, razonSocial = "Kincar S.A.C.", distrito = null))
        every { empleadoService.resumenPorIds(any()) } returns
            mapOf(5L to EmpleadoResumen(id = 5, nombres = "Ana", apellidos = "Diaz"))
        every { financiadoraService.porIds(any()) } returns
            mapOf(
                1L to
                    FinanciadoraDto(
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
        every { modeloService.resumenPorIds(any()) } returns mapOf(1L to busX, 2L to busY)
        every { consultas.tareasPendientesPorOportunidad(any()) } returns mapOf(100L to 2)
        every { consultas.eventosPendientesPorOportunidad(any()) } returns mapOf(100L to 1)
        every { contactoOportunidadRepository.findByIdIdOportunidad(any()) } returns emptyList()
        every { contactoService.resumenPorIds(any()) } returns emptyMap()
        every { logRepository.findFirstByIdOportunidadOrderByChangedAtDescIdDesc(any()) } returns null
        every { oportunidadRepository.save(any<Oportunidad>()) } answers { firstArg() }
        // Items y monto_total del DTO salen de OportunidadItemService (B8), no de
        // las columnas planas de la oportunidad.
        every { oportunidadItemService.porOportunidades(listOf(100L)) } returns mapOf(100L to listOf(itemDto()))
        every { oportunidadItemService.montoTotalPorOportunidades(listOf(100L)) } returns
            mapOf(100L to BigDecimal("270.00"))
    }

    private fun itemDto() =
        OportunidadItemDto(
            id = 500,
            idModelo = 1,
            modelo = ModeloEnOportunidadDto(id = 1, codigo = "BUS-X", precioBase = "100.00"),
            cantidad = 2,
            precioVenta = "150.00",
            descuento = "10.00",
            cuotaFinanciadora = "0.00",
            montoItem = "270.00",
        )

    private fun oportunidad(
        idModelo: Long = 1,
        precioUnitario: BigDecimal? = BigDecimal("100.00"),
    ) = Oportunidad(
        id = 100,
        idEmpresa = 10,
        idVendedor = 5,
        idFinanciadora = 1,
        idModelo = idModelo,
        estado = EstadoOportunidad.evaluacion_calidda,
        cantidad = 1,
        precioUnitario = precioUnitario,
        dcto = BigDecimal.ZERO,
        montoTotal = precioUnitario,
        createdAt = LocalDateTime.now(),
        createdBy = 5,
        updatedAt = LocalDateTime.now().minusDays(1),
        updatedBy = 5,
    )

    @Test
    fun `actualizar aplica los campos negociables y devuelve el monto derivado de los items`() {
        val entidad = oportunidad()
        every { oportunidadRepository.findById(100) } returns Optional.of(entidad)

        val dto =
            service.actualizar(
                100,
                ActualizarOportunidadRequest(
                    garantia = true,
                    fincParalelo = true,
                    fichaVenta = "FV-9",
                    notas = "Negociando plazo",
                    fechaCierreEstimado = LocalDate.of(2026, 3, 1),
                ),
                admin,
            )

        assertThat(entidad.garantia).isTrue()
        assertThat(entidad.fincParalelo).isTrue()
        assertThat(entidad.fichaVenta).isEqualTo("FV-9")
        assertThat(entidad.notas).isEqualTo("Negociando plazo")
        assertThat(entidad.fechaCierreEstimado).isEqualTo(LocalDate.of(2026, 3, 1))
        // 2 x 150.00 x (1 - 10/100) = 270.00. La formula la aplica el item
        // (OportunidadItemServiceImplTest); aqui se fija que el PUT devuelve el
        // total derivado de los items y no la columna plana de la oportunidad.
        assertThat(dto.items.single().cantidad).isEqualTo(2)
        assertThat(dto.items.single().precioVenta).isEqualTo("150.00")
        assertThat(dto.items.single().descuento).isEqualTo("10.00")
        assertThat(dto.montoTotal).isEqualTo("270.00")
        assertThat(dto.advertencias).isEmpty()
        assertThat(entidad.updatedBy).isEqualTo(1)
        verify { oportunidadRepository.save(entidad) }
    }

    /** Un PUT vacio no borra nada: cada campo se toca solo si viene en el body. */
    @Test
    fun `actualizar sin campos deja la oportunidad como estaba`() {
        val entidad = oportunidad().apply { notas = "Original" }
        every { oportunidadRepository.findById(100) } returns Optional.of(entidad)

        service.actualizar(100, ActualizarOportunidadRequest(), admin)

        assertThat(entidad.notas).isEqualTo("Original")
        assertThat(entidad.garantia).isNull()
        assertThat(entidad.fichaVenta).isNull()
    }

    /**
     * D19: los campos de item ya no viajan en este body. `ActualizarOportunidadRequest`
     * no los declara y `@JsonIgnoreProperties(ignoreUnknown = true)` los descarta, asi
     * que un PUT de oportunidad no puede tocar el item por accidente.
     */
    @Test
    fun `actualizar no toca los campos de item de la oportunidad`() {
        val entidad = oportunidad()
        every { oportunidadRepository.findById(100) } returns Optional.of(entidad)

        service.actualizar(100, ActualizarOportunidadRequest(notas = "solo notas"), admin)

        assertThat(entidad.idModelo).isEqualTo(1)
        assertThat(entidad.cantidad).isEqualTo(1)
        assertThat(entidad.precioUnitario).isEqualByComparingTo("100.00")
        verify(exactly = 0) { modeloService.resumen(any()) }
    }

    /** IDOR (regla 14): oportunidad ajena para un vendedor → 404, no 403, y sin escribir. */
    @Test
    fun `actualizar una oportunidad ajena responde 404 y no guarda`() {
        every { oportunidadRepository.findById(100) } returns Optional.of(oportunidad())

        assertThatThrownBy {
            service.actualizar(100, ActualizarOportunidadRequest(notas = "x"), UsuarioActual(id = 77, rol = "vendedor"))
        }.isInstanceOf(NoEncontradoException::class.java)

        verify(exactly = 0) { oportunidadRepository.save(any<Oportunidad>()) }
    }

    @Test
    fun `actualizar una oportunidad inexistente responde 404`() {
        every { oportunidadRepository.findById(999) } returns Optional.empty()

        assertThatThrownBy { service.actualizar(999, ActualizarOportunidadRequest(notas = "x"), admin) }
            .isInstanceOf(NoEncontradoException::class.java)
    }

    /** Una oportunidad cuyos items no dan total (sin cantidad) devuelve `monto_total` null (reglas §7.2). */
    @Test
    fun `actualizar una oportunidad sin monto derivable deja monto_total en null`() {
        val entidad = oportunidad()
        every { oportunidadRepository.findById(100) } returns Optional.of(entidad)
        every { oportunidadItemService.montoTotalPorOportunidades(listOf(100L)) } returns emptyMap()

        val dto = service.actualizar(100, ActualizarOportunidadRequest(notas = "sin cantidad"), admin)

        assertThat(dto.montoTotal).isNull()
    }
}
