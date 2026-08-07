package pe.quantum.crm.domain.oportunidades

import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import io.mockk.verifyOrder
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import pe.quantum.crm.domain.contactos.ContactoService
import pe.quantum.crm.domain.empleados.EmpleadoService
import pe.quantum.crm.domain.empleados.dto.EmpleadoResumen
import pe.quantum.crm.domain.empresas.EmpresaService
import pe.quantum.crm.domain.empresas.VendedorEmpresaReasignadoEvent
import pe.quantum.crm.domain.empresas.dto.CambioEstadoCartera
import pe.quantum.crm.domain.empresas.dto.EmpresaResumen
import pe.quantum.crm.domain.empresas.dto.EmpresaVinculo
import pe.quantum.crm.domain.financiadoras.FinanciadoraService
import pe.quantum.crm.domain.modelos.ModeloService
import pe.quantum.crm.domain.notificaciones.EntidadNotificacion
import pe.quantum.crm.domain.notificaciones.NotificacionService
import pe.quantum.crm.domain.notificaciones.TipoNotificacion
import pe.quantum.crm.domain.oportunidades.dto.ActualizarOportunidadRequest
import pe.quantum.crm.domain.oportunidades.dto.CrearOportunidadRequest
import pe.quantum.crm.integracion.drive.DriveStorageService
import pe.quantum.crm.shared.enums.EstadoCartera
import pe.quantum.crm.shared.exception.AprobacionRequeridaException
import pe.quantum.crm.shared.exception.ValidacionException
import pe.quantum.crm.shared.security.UsuarioActual
import java.math.BigDecimal
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
    private val driveStorageService = mockk<DriveStorageService>(relaxed = true)
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
        )

    init {
        // Toda creacion de oportunidad abre su carpeta en Drive. Los escenarios que
        // verifican ese comportamiento lo re-declaran con valores concretos.
        every { empresaService.asegurarCarpetaDrive(any()) } returns "carpeta-empresa"
    }

    private fun oportunidad(
        id: Long = 100,
        idVendedor: Long = 1,
    ) = Oportunidad(
        id = id,
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

    /** Financiadora Calidda usada como stub en varios escenarios de `crear`. */
    private fun calidda() =
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

    /** Modelo BUS-X usado como stub en varios escenarios de `crear`. */
    private fun busX() = pe.quantum.crm.domain.modelos.dto.ModeloResumen(id = 1, codigo = "BUS-X", precioBase = java.math.BigDecimal.TEN)

    /** Devuelve una copia de la oportunidad con `id` asignado, simulando lo que hace JPA al guardar. */
    private fun Oportunidad.conId(nuevoId: Long) =
        Oportunidad(
            id = nuevoId,
            idEmpresa = idEmpresa,
            idVendedor = idVendedor,
            idFinanciadora = idFinanciadora,
            idModelo = idModelo,
            estado = estado,
            cantidad = cantidad,
            precioUnitario = precioUnitario,
            dcto = dcto,
            montoTotal = montoTotal,
            fincParalelo = fincParalelo,
            garantia = garantia,
            fichaVenta = fichaVenta,
            notas = notas,
            motivoCierre = motivoCierre,
            fechaCierreEstimado = fechaCierreEstimado,
            facturadoEn = facturadoEn,
            createdAt = createdAt,
            createdBy = createdBy,
            updatedAt = updatedAt,
            updatedBy = updatedBy,
        )

    @Test
    fun `onVendedorEmpresaReasignado actualiza y notifica las oportunidades activas con vendedor distinto`() {
        val activa = oportunidad(id = 100, idVendedor = 1)
        val yaAsignada = oportunidad(id = 101, idVendedor = 2)
        every {
            oportunidadRepository.findByIdEmpresaAndEstadoIn(10, EstadoCarteraService.ESTADOS_ACTIVOS)
        } returns listOf(activa, yaAsignada)
        every { oportunidadRepository.saveAll(listOf(activa)) } returns listOf(activa)
        every { empleadoService.resumenPorIds(listOf(9)) } returns
            mapOf(9L to EmpleadoResumen(id = 9, nombres = "Aldo", apellidos = "Martinez"))
        every { empresaService.resumenPorIds(listOf(10)) } returns
            mapOf(10L to EmpresaResumen(id = 10, razonSocial = "Kincar S.A.C.", distrito = null))

        service.onVendedorEmpresaReasignado(VendedorEmpresaReasignadoEvent(idEmpresa = 10, idVendedorNuevo = 2, idActor = 9))

        assertThat(activa.idVendedor).isEqualTo(2)
        assertThat(activa.updatedBy).isEqualTo(9)
        assertThat(yaAsignada.idVendedor).isEqualTo(2)
        verify(exactly = 1) {
            notificacionService.notificar(
                destinatarios = setOf(2L),
                idActor = 9L,
                tipo = TipoNotificacion.oportunidad_traspasada,
                mensaje = "Aldo Martinez te traspasó la oportunidad de Kincar S.A.C.",
                entidadTipo = EntidadNotificacion.oportunidad,
                entidadId = 100L,
            )
        }
    }

    @Test
    fun `onVendedorEmpresaReasignado no hace nada si ninguna oportunidad activa cambia de vendedor`() {
        val yaAsignada = oportunidad(id = 101, idVendedor = 2)
        every {
            oportunidadRepository.findByIdEmpresaAndEstadoIn(10, EstadoCarteraService.ESTADOS_ACTIVOS)
        } returns listOf(yaAsignada)

        service.onVendedorEmpresaReasignado(VendedorEmpresaReasignadoEvent(idEmpresa = 10, idVendedorNuevo = 2, idActor = 9))

        verify(exactly = 0) { oportunidadRepository.saveAll(any<List<Oportunidad>>()) }
        verify(exactly = 0) { notificacionService.notificar(any(), any(), any(), any(), any(), any()) }
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
        every { modeloService.resumen(1) } returns busX()
        every { financiadoraService.default() } returns calidda()
        val guardada = slot<Oportunidad>()
        every { oportunidadRepository.save(capture(guardada)) } answers { guardada.captured.conId(100) }
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
        every { financiadoraService.porIds(listOf(1)) } returns mapOf(1L to calidda())
        every { modeloService.resumenPorIds(listOf(1)) } returns mapOf(1L to busX())
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
                entidadId = 100L,
            )
        }
    }

    @Test
    fun `actualizar con dcto sobre el limite del rol lanza APROBACION_REQUERIDA sin guardar`() {
        val vendedor = UsuarioActual(id = 5, rol = "vendedor")
        val entidad = oportunidad(idVendedor = 5)
        every { oportunidadRepository.findById(100) } returns Optional.of(entidad)

        assertThatThrownBy {
            service.actualizar(100, ActualizarOportunidadRequest(dcto = BigDecimal("5.00")), vendedor)
        }.isInstanceOf(AprobacionRequeridaException::class.java)

        verify(exactly = 0) { oportunidadRepository.save(any()) }
    }

    @Test
    fun `crear con dcto sobre el limite lanza APROBACION_REQUERIDA`() {
        val vendedor = UsuarioActual(id = 5, rol = "vendedor")
        every { empresaService.vinculoVisible(10, vendedor) } returns
            EmpresaVinculo(id = 10, razonSocial = "ABC", idVendedor = 5, estadoCartera = "prospeccion")

        assertThatThrownBy {
            service.crear(CrearOportunidadRequest(idEmpresa = 10, idModelo = 1, dcto = BigDecimal("4.00")), vendedor)
        }.isInstanceOf(AprobacionRequeridaException::class.java)
    }

    @Test
    fun `gerencia crea oportunidad en empresa sin vendedor - exige id_vendedor y lo asigna a la empresa`() {
        val gerencia = UsuarioActual(id = 1, rol = "gerencia")
        every { empresaService.vinculoVisible(10, gerencia) } returns
            EmpresaVinculo(id = 10, razonSocial = "ABC", idVendedor = null, estadoCartera = "prospeccion")
        every { empleadoService.esAsignableComoVendedor(8) } returns true
        every { empresaService.reasignarVendedor(10, 8, gerencia) } returns 8
        every { modeloService.resumen(1) } returns busX()
        every { financiadoraService.default() } returns calidda()
        val guardada = slot<Oportunidad>()
        every { oportunidadRepository.save(capture(guardada)) } answers { guardada.captured.conId(200) }
        every { logRepository.save(any()) } returns mockk()
        every { estadoCarteraService.actualizar(10) } returns null
        every { empresaService.resumenPorIds(listOf(10)) } returns
            mapOf(10L to EmpresaResumen(id = 10, razonSocial = "ABC", distrito = null))
        every { empleadoService.resumenPorIds(listOf(8)) } returns
            mapOf(8L to EmpleadoResumen(id = 8, nombres = "Luis", apellidos = "Vera"))
        every { financiadoraService.porIds(listOf(1)) } returns mapOf(1L to calidda())
        every { modeloService.resumenPorIds(listOf(1)) } returns mapOf(1L to busX())
        every { consultas.tareasPendientesPorOportunidad(listOf(200L)) } returns emptyMap()
        every { consultas.eventosPendientesPorOportunidad(listOf(200L)) } returns emptyMap()
        every { contactoOportunidadRepository.findByIdIdOportunidad(200L) } returns emptyList()
        every { contactoService.resumenPorIds(emptyList()) } returns emptyMap()
        every { logRepository.findFirstByIdOportunidadOrderByChangedAtDescIdDesc(200L) } returns null

        val dto = service.crear(CrearOportunidadRequest(idEmpresa = 10, idModelo = 1, idVendedor = 8), gerencia)

        assertThat(dto.idVendedor).isEqualTo(8)
        verify { empresaService.reasignarVendedor(10, 8, gerencia) }
    }

    @Test
    fun `gerencia crea oportunidad en empresa sin vendedor sin id_vendedor es VALIDACION`() {
        val gerencia = UsuarioActual(id = 1, rol = "gerencia")
        every { empresaService.vinculoVisible(10, gerencia) } returns
            EmpresaVinculo(id = 10, razonSocial = "ABC", idVendedor = null, estadoCartera = "prospeccion")
        assertThatThrownBy { service.crear(CrearOportunidadRequest(idEmpresa = 10, idModelo = 1), gerencia) }
            .isInstanceOf(ValidacionException::class.java)
    }

    @Test
    fun `la oportunidad creada por gerencia en empresa con vendedor queda para ese vendedor, no para gerencia`() {
        val gerencia = UsuarioActual(id = 1, rol = "gerencia")
        every { empresaService.vinculoVisible(10, gerencia) } returns
            EmpresaVinculo(id = 10, razonSocial = "ABC", idVendedor = 5, estadoCartera = "cliente")
        every { modeloService.resumen(1) } returns busX()
        every { financiadoraService.default() } returns calidda()
        val guardada = slot<Oportunidad>()
        every { oportunidadRepository.save(capture(guardada)) } answers { guardada.captured.conId(201) }
        every { logRepository.save(any()) } returns mockk()
        every { estadoCarteraService.actualizar(10) } returns null
        every { empresaService.resumenPorIds(listOf(10)) } returns
            mapOf(10L to EmpresaResumen(id = 10, razonSocial = "ABC", distrito = null))
        every { empleadoService.resumenPorIds(listOf(5)) } returns
            mapOf(5L to EmpleadoResumen(id = 5, nombres = "Ana", apellidos = "Diaz"))
        every { financiadoraService.porIds(listOf(1)) } returns mapOf(1L to calidda())
        every { modeloService.resumenPorIds(listOf(1)) } returns mapOf(1L to busX())
        every { consultas.tareasPendientesPorOportunidad(listOf(201L)) } returns emptyMap()
        every { consultas.eventosPendientesPorOportunidad(listOf(201L)) } returns emptyMap()
        every { contactoOportunidadRepository.findByIdIdOportunidad(201L) } returns emptyList()
        every { contactoService.resumenPorIds(emptyList()) } returns emptyMap()
        every { logRepository.findFirstByIdOportunidadOrderByChangedAtDescIdDesc(201L) } returns null

        val dto = service.crear(CrearOportunidadRequest(idEmpresa = 10, idModelo = 1), gerencia)

        assertThat(dto.idVendedor).isEqualTo(5)
    }

    @Test
    fun `aplicarDescuentoAprobado setea dcto y recalcula monto_total`() {
        val entidad =
            oportunidad(idVendedor = 5).apply {
                cantidad = 2
                precioUnitario = BigDecimal("100.00")
            }
        every { oportunidadRepository.findById(100) } returns Optional.of(entidad)
        every { oportunidadRepository.save(any()) } answers { firstArg() }

        service.aplicarDescuentoAprobado(100, BigDecimal("5.00"), idAprobador = 2)

        assertThat(entidad.dcto).isEqualByComparingTo(BigDecimal("5.00"))
        assertThat(entidad.montoTotal).isEqualByComparingTo(BigDecimal("190.00"))
        assertThat(entidad.updatedBy).isEqualTo(2)
    }

    @Test
    fun `aplicarDescuentoAprobado sobre oportunidad cerrada es SOLICITUD_NO_APLICABLE`() {
        val cerrada = oportunidad(idVendedor = 5).apply { estado = pe.quantum.crm.shared.enums.EstadoOportunidad.cerrado }
        every { oportunidadRepository.findById(100) } returns Optional.of(cerrada)
        assertThatThrownBy { service.aplicarDescuentoAprobado(100, BigDecimal("5.00"), 2) }
            .isInstanceOf(pe.quantum.crm.shared.exception.ConflictoException::class.java)
    }

    @Test
    fun `eliminar borra la oportunidad y recalcula el estado de cartera de su empresa`() {
        val entidad = oportunidad(id = 100)
        every { oportunidadRepository.findById(100) } returns Optional.of(entidad)
        every { oportunidadRepository.delete(entidad) } just io.mockk.Runs
        every { estadoCarteraService.actualizar(10) } returns null

        service.eliminar(100)

        verify { oportunidadRepository.delete(entidad) }
        verify { estadoCarteraService.actualizar(10) }
    }

    @Test
    fun `eliminar lanza NoEncontradoException si la oportunidad no existe`() {
        every { oportunidadRepository.findById(999) } returns Optional.empty()

        assertThatThrownBy { service.eliminar(999) }
            .isInstanceOf(pe.quantum.crm.shared.exception.NoEncontradoException::class.java)
        verify(exactly = 0) { oportunidadRepository.delete(any<Oportunidad>()) }
        verify(exactly = 0) { estadoCarteraService.actualizar(any()) }
    }

    @Test
    fun `eliminar permite borrar una oportunidad en estado facturado sin bloqueo de negocio`() {
        val entidad = oportunidad(id = 200)
        entidad.estado = pe.quantum.crm.shared.enums.EstadoOportunidad.facturado
        every { oportunidadRepository.findById(200) } returns Optional.of(entidad)
        every { oportunidadRepository.delete(entidad) } just io.mockk.Runs
        every { estadoCarteraService.actualizar(10) } returns null

        service.eliminar(200)

        verify { oportunidadRepository.delete(entidad) }
    }

    @Test
    fun `cambiarEstado a facturado fija facturado_en`() {
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
        every { empleadoService.idsSupervisoresActivos() } returns listOf(1)

        service.cambiarEstado(
            100,
            pe.quantum.crm.domain.oportunidades.dto.CambiarEstadoRequest(estado = "facturado"),
            UsuarioActual(id = 1, rol = "admin"),
        )

        assertThat(entidad.facturadoEn).isNotNull()
    }

    @Test
    fun `cambiarEstado que retrocede desde facturado limpia facturado_en`() {
        val entidad =
            oportunidad(idVendedor = 1).apply {
                estado = pe.quantum.crm.shared.enums.EstadoOportunidad.facturado
                facturadoEn = LocalDateTime.now().minusDays(5)
            }
        every { oportunidadRepository.findById(100) } returns Optional.of(entidad)
        every { oportunidadRepository.save(entidad) } returns entidad
        every { logRepository.save(any()) } returns mockk()
        every {
            consultas.eventosRecomendadosSinRegistrar(100, pe.quantum.crm.shared.enums.EstadoOportunidad.facturado)
        } returns emptyList()
        every { estadoCarteraService.actualizar(10) } returns null
        every { empresaService.resumenPorIds(listOf(10)) } returns
            mapOf(10L to EmpresaResumen(id = 10, razonSocial = "Kincar S.A.C.", distrito = null))
        every { empleadoService.resumenPorIds(listOf(1)) } returns mapOf(1L to EmpleadoResumen(id = 1, nombres = "Ana", apellidos = "Diaz"))
        every { empleadoService.idsSupervisoresActivos() } returns listOf(1)

        service.cambiarEstado(
            100,
            pe.quantum.crm.domain.oportunidades.dto.CambiarEstadoRequest(estado = "documentos_legales"),
            UsuarioActual(id = 1, rol = "admin"),
        )

        assertThat(entidad.facturadoEn).isNull()
    }

    // ── Carpeta de Google Drive ───────────────────────────────

    @Test
    fun `crear abre la subcarpeta de Drive dentro de la carpeta de la empresa`() {
        stubsDeCreacion(driveFolderIdEmpresa = "carpeta-empresa")
        every { driveStorageService.crearCarpeta("OP-100 - BUS-X", "carpeta-empresa") } returns "carpeta-op"

        val dto =
            service.crear(
                CrearOportunidadRequest(idEmpresa = 10, idModelo = 1, cantidad = 1, dcto = BigDecimal.ZERO),
                UsuarioActual(id = 3, rol = "vendedor"),
            )

        verify { driveStorageService.crearCarpeta("OP-100 - BUS-X", "carpeta-empresa") }
        assertThat(dto.driveFolderId).isEqualTo("carpeta-op")
    }

    @Test
    fun `crear abre primero la carpeta de la empresa si esta aun no tiene`() {
        stubsDeCreacion(driveFolderIdEmpresa = null)
        every { empresaService.asegurarCarpetaDrive(10) } returns "carpeta-empresa-nueva"
        every { driveStorageService.crearCarpeta("OP-100 - BUS-X", "carpeta-empresa-nueva") } returns "carpeta-op"

        service.crear(
            CrearOportunidadRequest(idEmpresa = 10, idModelo = 1, cantidad = 1, dcto = BigDecimal.ZERO),
            UsuarioActual(id = 3, rol = "vendedor"),
        )

        verify { empresaService.asegurarCarpetaDrive(10) }
        verify { driveStorageService.crearCarpeta("OP-100 - BUS-X", "carpeta-empresa-nueva") }
    }

    @Test
    fun `asegurarCarpetaDrive no llama a Drive si la oportunidad ya tiene carpeta`() {
        val entidad = oportunidadConVendedor(3).apply { driveFolderId = "ya-existe" }
        every { oportunidadRepository.findById(100) } returns Optional.of(entidad)

        val carpeta = service.asegurarCarpetaDrive(100, UsuarioActual(id = 3, rol = "vendedor"))

        assertThat(carpeta).isEqualTo("ya-existe")
        verify(exactly = 0) { driveStorageService.crearCarpeta(any(), any()) }
        verify(exactly = 0) { oportunidadRepository.asignarCarpetaDriveSiFalta(any(), any()) }
    }

    @Test
    fun `asegurarCarpetaDrive fija la carpeta con un UPDATE condicional, no con un save completo`() {
        val entidad = oportunidadConVendedor(3)
        every { oportunidadRepository.findById(100) } returns Optional.of(entidad)
        every { empresaService.asegurarCarpetaDrive(10) } returns "carpeta-empresa"
        every { modeloService.resumen(1) } returns busX()
        every { driveStorageService.crearCarpeta(any(), any()) } returns "carpeta-op"
        every { oportunidadRepository.asignarCarpetaDriveSiFalta(100, "carpeta-op") } returns 1

        service.asegurarCarpetaDrive(100)

        // Sin SELECT ... FOR UPDATE: esta ruta encadenaba DOS bloqueos de fila
        // (oportunidad y empresa) y hasta DOS llamadas a Drive dentro de la misma
        // transaccion. Ahora la red ocurre sin nada bloqueado.
        verifyOrder {
            driveStorageService.crearCarpeta(any(), any())
            oportunidadRepository.asignarCarpetaDriveSiFalta(100, "carpeta-op")
        }
        verify(exactly = 0) { oportunidadRepository.save(any<Oportunidad>()) }
    }

    @Test
    fun `asegurarCarpetaDrive devuelve la carpeta ganadora si otra peticion se adelanto`() {
        val entidad = oportunidadConVendedor(3)
        every { oportunidadRepository.findById(100) } returns Optional.of(entidad)
        every { empresaService.asegurarCarpetaDrive(10) } returns "carpeta-empresa"
        every { modeloService.resumen(1) } returns busX()
        every { driveStorageService.crearCarpeta(any(), any()) } returns "carpeta-perdedora"
        every { oportunidadRepository.asignarCarpetaDriveSiFalta(100, "carpeta-perdedora") } returns 0
        every { oportunidadRepository.findDriveFolderId(100) } returns "carpeta-ganadora"

        assertThat(service.asegurarCarpetaDrive(100)).isEqualTo("carpeta-ganadora")
        assertThat(entidad.driveFolderId).isEqualTo("carpeta-ganadora")
    }

    @Test
    fun `asegurarCarpetaDrive responde 404 en una oportunidad ajena antes de tocar Drive`() {
        every { oportunidadRepository.findById(100) } returns Optional.of(oportunidadConVendedor(3))

        assertThatThrownBy {
            service.asegurarCarpetaDrive(100, UsuarioActual(id = 77, rol = "vendedor"))
        }.isInstanceOf(pe.quantum.crm.shared.exception.NoEncontradoException::class.java)

        verify(exactly = 0) { driveStorageService.crearCarpeta(any(), any()) }
    }

    private fun oportunidadConVendedor(idVendedor: Long) =
        Oportunidad(
            id = 100,
            idEmpresa = 10,
            idVendedor = idVendedor,
            idFinanciadora = 1,
            idModelo = 1,
            createdBy = idVendedor,
            updatedBy = idVendedor,
        )

    /** Stubs minimos para que `crear` llegue hasta el ensamblado del DTO. */
    private fun stubsDeCreacion(driveFolderIdEmpresa: String?) {
        every { empresaService.vinculoVisible(10, any()) } returns
            EmpresaVinculo(
                id = 10,
                razonSocial = "Kincar S.A.C.",
                idVendedor = 3,
                estadoCartera = "prospeccion",
                driveFolderId = driveFolderIdEmpresa,
            )
        every { modeloService.resumen(1) } returns busX()
        every { financiadoraService.default() } returns calidda()
        val guardada = slot<Oportunidad>()
        every { oportunidadRepository.save(capture(guardada)) } answers { guardada.captured.conId(100) }
        every { logRepository.save(any()) } returns mockk()
        every { estadoCarteraService.actualizar(10) } returns null
        every { empresaService.resumenPorIds(listOf(10)) } returns
            mapOf(10L to EmpresaResumen(id = 10, razonSocial = "Kincar S.A.C.", distrito = null))
        every { empleadoService.resumenPorIds(listOf(3)) } returns
            mapOf(3L to EmpleadoResumen(id = 3, nombres = "Jose", apellidos = "Lima"))
        every { financiadoraService.porIds(listOf(1)) } returns mapOf(1L to calidda())
        every { modeloService.resumenPorIds(listOf(1)) } returns mapOf(1L to busX())
        every { consultas.tareasPendientesPorOportunidad(listOf(100L)) } returns emptyMap()
        every { consultas.eventosPendientesPorOportunidad(listOf(100L)) } returns emptyMap()
        every { contactoOportunidadRepository.findByIdIdOportunidad(100L) } returns emptyList()
        every { contactoService.resumenPorIds(emptyList()) } returns emptyMap()
        every { logRepository.findFirstByIdOportunidadOrderByChangedAtDescIdDesc(100L) } returns null
    }

    // ── archivosDrive ─────────────────────────────────────────

    @Test
    fun `archivosDrive devuelve los documentos de la carpeta de la oportunidad`() {
        val entidad = oportunidadConVendedor(3).apply { driveFolderId = "carpeta-op" }
        every { oportunidadRepository.findById(100) } returns Optional.of(entidad)
        every { driveStorageService.listarArchivos("carpeta-op") } returns
            listOf(
                pe.quantum.crm.integracion.drive.DriveArchivoSubido(
                    id = "archivo-1",
                    nombre = "contrato.pdf",
                    url = "https://drive.google.com/file/d/archivo-1/view",
                    tamanoBytes = 1024,
                    mimeType = "application/pdf",
                ),
            )

        val resultado = service.archivosDrive(100, UsuarioActual(id = 3, rol = "vendedor"))

        assertThat(resultado).hasSize(1)
        assertThat(resultado[0].nombre).isEqualTo("contrato.pdf")
    }

    @Test
    fun `archivosDrive de una oportunidad sin carpeta devuelve lista vacia sin llamar a Drive`() {
        every { oportunidadRepository.findById(100) } returns Optional.of(oportunidadConVendedor(3))

        assertThat(service.archivosDrive(100, UsuarioActual(id = 3, rol = "vendedor"))).isEmpty()

        verify(exactly = 0) { driveStorageService.listarArchivos(any()) }
    }

    @Test
    fun `archivosDrive de una oportunidad ajena responde 404`() {
        every { oportunidadRepository.findById(100) } returns Optional.of(oportunidadConVendedor(3))

        assertThatThrownBy {
            service.archivosDrive(100, UsuarioActual(id = 77, rol = "vendedor"))
        }.isInstanceOf(pe.quantum.crm.shared.exception.NoEncontradoException::class.java)
    }

    @Test
    fun `idsSinCarpetaDrive delega en el repositorio y devuelve los ids pendientes`() {
        every { oportunidadRepository.findIdsSinCarpetaDrive() } returns listOf(11L, 12L)

        assertThat(service.idsSinCarpetaDrive()).containsExactly(11L, 12L)
    }

    @Test
    fun `asegurarCarpetaDrive de sistema crea la carpeta sin exigir visibilidad`() {
        val entidad = oportunidadConVendedor(3)
        every { oportunidadRepository.findById(100) } returns Optional.of(entidad)
        every { empresaService.asegurarCarpetaDrive(10) } returns "carpeta-empresa"
        every { modeloService.resumen(1) } returns busX()
        every { driveStorageService.crearCarpeta("OP-100 - BUS-X", "carpeta-empresa") } returns "carpeta-op"
        every { oportunidadRepository.asignarCarpetaDriveSiFalta(100, "carpeta-op") } returns 1

        assertThat(service.asegurarCarpetaDrive(100)).isEqualTo("carpeta-op")
        assertThat(entidad.driveFolderId).isEqualTo("carpeta-op")
    }

    @Test
    fun `asegurarCarpetaDrive de sistema es idempotente`() {
        val entidad = oportunidadConVendedor(3).apply { driveFolderId = "ya-existe" }
        every { oportunidadRepository.findById(100) } returns Optional.of(entidad)

        assertThat(service.asegurarCarpetaDrive(100)).isEqualTo("ya-existe")

        verify(exactly = 0) { driveStorageService.crearCarpeta(any(), any()) }
    }
}
