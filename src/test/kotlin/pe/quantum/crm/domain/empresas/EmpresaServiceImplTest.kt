package pe.quantum.crm.domain.empresas

import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.context.ApplicationEventPublisher
import org.springframework.data.domain.PageImpl
import org.springframework.data.domain.PageRequest
import org.springframework.data.jpa.domain.Specification
import pe.quantum.crm.domain.contactos.ContactoService
import pe.quantum.crm.domain.empleados.EmpleadoService
import pe.quantum.crm.domain.empleados.dto.EmpleadoResumen
import pe.quantum.crm.domain.empresas.dto.ActualizarEmpresaRequest
import pe.quantum.crm.domain.empresas.dto.ContactoDeEmpresaDto
import pe.quantum.crm.domain.empresas.dto.CrearEmpresaRequest
import pe.quantum.crm.domain.empresas.dto.EmpresaFiltros
import pe.quantum.crm.domain.notificaciones.EntidadNotificacion
import pe.quantum.crm.domain.notificaciones.NotificacionService
import pe.quantum.crm.domain.notificaciones.TipoNotificacion
import pe.quantum.crm.integracion.drive.DriveException
import pe.quantum.crm.integracion.drive.DriveStorageService
import pe.quantum.crm.shared.enums.EstadoCartera
import pe.quantum.crm.shared.exception.PermisoInsuficienteException
import pe.quantum.crm.shared.exception.ValidacionException
import pe.quantum.crm.shared.security.UsuarioActual
import java.time.LocalDateTime
import java.util.Optional

class EmpresaServiceImplTest {
    private val empresaRepository = mockk<EmpresaRepository>()
    private val empleadoService = mockk<EmpleadoService>()
    private val notificacionService = mockk<NotificacionService>(relaxed = true)
    private val eventPublisher = mockk<ApplicationEventPublisher>(relaxed = true)
    private val driveStorageService = mockk<DriveStorageService>()
    private val contactoService = mockk<ContactoService>()
    private val service =
        EmpresaServiceImpl(
            empresaRepository,
            empleadoService,
            notificacionService,
            eventPublisher,
            driveStorageService,
            contactoService,
        )

    private fun empresa(
        estadoCartera: EstadoCartera = EstadoCartera.prospeccion,
        id: Long = 1,
    ): Empresa =
        Empresa(
            id = id,
            ruc = "20123456789",
            razonSocial = "Transportes ABC",
            estadoCartera = estadoCartera,
            createdAt = LocalDateTime.now(),
            createdBy = 1,
            updatedAt = LocalDateTime.now(),
            updatedBy = 1,
        )

    @Test
    fun `aplicarEstadoDerivado devuelve el cambio cuando prospeccion pasa a oportunidad_activa`() {
        val entidad = empresa(estadoCartera = EstadoCartera.prospeccion)
        every { empresaRepository.findById(1) } returns Optional.of(entidad)
        every { empresaRepository.save(entidad) } returns entidad

        val resultado = service.aplicarEstadoDerivado(1, EstadoCartera.oportunidad_activa)

        assertThat(resultado?.anterior).isEqualTo(EstadoCartera.prospeccion)
        assertThat(resultado?.nuevo).isEqualTo(EstadoCartera.oportunidad_activa)
        assertThat(entidad.estadoCartera).isEqualTo(EstadoCartera.oportunidad_activa)
    }

    @Test
    fun `aplicarEstadoDerivado devuelve null cuando no hay cambio real`() {
        val entidad = empresa(estadoCartera = EstadoCartera.oportunidad_activa)
        every { empresaRepository.findById(1) } returns Optional.of(entidad)

        val resultado = service.aplicarEstadoDerivado(1, EstadoCartera.oportunidad_activa)

        assertThat(resultado).isNull()
        verify(exactly = 0) { empresaRepository.save(any()) }
    }

    @Test
    fun `reasignarVendedor notifica al vendedor destino con el nombre del actor y de la empresa`() {
        val entidad = empresa()
        every { empresaRepository.findById(1) } returns Optional.of(entidad)
        every { empleadoService.esAsignableComoVendedor(2) } returns true
        every { empresaRepository.save(entidad) } returns entidad
        every { empleadoService.resumenPorIds(listOf(9)) } returns mapOf(9L to EmpleadoResumen(id = 9, nombres = "Ana", apellidos = "Diaz"))

        service.reasignarVendedor(1, 2, UsuarioActual(id = 9, rol = "gerencia"))

        verify {
            notificacionService.notificar(
                destinatarios = setOf(2L),
                idActor = 9L,
                tipo = TipoNotificacion.empresa_asignada,
                mensaje = "Ana Diaz te asignó la empresa Transportes ABC",
                entidadTipo = EntidadNotificacion.empresa,
                entidadId = 1L,
            )
        }
    }

    @Test
    fun `reasignarVendedor publica VendedorEmpresaReasignadoEvent con los datos del cambio`() {
        val entidad = empresa()
        every { empresaRepository.findById(1) } returns Optional.of(entidad)
        every { empleadoService.esAsignableComoVendedor(2) } returns true
        every { empresaRepository.save(entidad) } returns entidad
        every { empleadoService.resumenPorIds(listOf(9)) } returns
            mapOf(9L to EmpleadoResumen(id = 9, nombres = "Ana", apellidos = "Diaz"))
        val evento = slot<VendedorEmpresaReasignadoEvent>()
        every { eventPublisher.publishEvent(capture(evento)) } just Runs

        service.reasignarVendedor(1, 2, UsuarioActual(id = 9, rol = "gerencia"))

        assertThat(evento.captured).isEqualTo(VendedorEmpresaReasignadoEvent(idEmpresa = 1, idVendedorNuevo = 2, idActor = 9))
    }

    @Test
    fun `reasignarVendedor por jdv lanza PERMISO_INSUFICIENTE - debe usar solicitud`() {
        val jdv = UsuarioActual(id = 2, rol = "jdv")
        assertThatThrownBy { service.reasignarVendedor(10, 8, jdv) }
            .isInstanceOf(PermisoInsuficienteException::class.java)
    }

    @Test
    fun `reasignarVendedor rechaza destino que no es vendedor ni jdv`() {
        val gerencia = UsuarioActual(id = 1, rol = "gerencia")
        every { empresaRepository.findById(10) } returns Optional.of(empresa())
        every { empleadoService.esAsignableComoVendedor(99) } returns false
        assertThatThrownBy { service.reasignarVendedor(10, 99, gerencia) }
            .isInstanceOf(ValidacionException::class.java)
    }

    @Test
    fun `detalle de empresa en cartera maestra para jdv es 404 - IDOR`() {
        val jdv = UsuarioActual(id = 2, rol = "jdv")
        every { empresaRepository.findById(10) } returns Optional.of(empresa().apply { enCarteraMaestra = true })
        assertThatThrownBy { service.detalle(10, jdv) }
            .isInstanceOf(pe.quantum.crm.shared.exception.NoEncontradoException::class.java)
    }

    @Test
    fun `detalle de empresa en cartera maestra para gerencia responde normal`() {
        val gerencia = UsuarioActual(id = 1, rol = "gerencia")
        every { empresaRepository.findById(10) } returns Optional.of(empresa().apply { enCarteraMaestra = true })
        every { empleadoService.resumenPorIds(any()) } returns emptyMap()
        every { contactoService.contactosDeEmpresa(any()) } returns emptyList()
        assertThat(service.detalle(10, gerencia).enCarteraMaestra).isTrue()
    }

    // ── contactos_count y contactos (contrato_api.md §8) ──────

    @Test
    fun `listar puebla contactos_count con un unico lote, sin una consulta por empresa`() {
        val gerencia = UsuarioActual(id = 1, rol = "gerencia")
        every { empresaRepository.findAll(any<Specification<Empresa>>(), any<PageRequest>()) } returns
            PageImpl(listOf(empresa(id = 1), empresa(id = 2)), PageRequest.of(0, 20), 2)
        every { empleadoService.resumenPorIds(any()) } returns emptyMap()
        every { contactoService.countPorEmpresas(listOf(1L, 2L)) } returns mapOf(1L to 3)

        val resultado = service.listar(EmpresaFiltros(), gerencia, null, null, null, null)

        assertThat(resultado.items.map { it.contactosCount }).containsExactly(3, 0)
        verify(exactly = 1) { contactoService.countPorEmpresas(any()) }
        verify(exactly = 0) { contactoService.countPorEmpresa(any()) }
    }

    @Test
    fun `listar sin resultados no consulta conteos de contactos`() {
        val gerencia = UsuarioActual(id = 1, rol = "gerencia")
        every { empresaRepository.findAll(any<Specification<Empresa>>(), any<PageRequest>()) } returns
            PageImpl(emptyList(), PageRequest.of(0, 20), 0)
        every { empleadoService.resumenPorIds(any()) } returns emptyMap()

        assertThat(service.listar(EmpresaFiltros(), gerencia, null, null, null, null).items).isEmpty()

        verify(exactly = 0) { contactoService.countPorEmpresas(any()) }
    }

    @Test
    fun `detalle incluye los contactos vinculados con cargo y toma_decision`() {
        val gerencia = UsuarioActual(id = 1, rol = "gerencia")
        every { empresaRepository.findById(1) } returns Optional.of(empresa())
        every { empleadoService.resumenPorIds(any()) } returns emptyMap()
        every { contactoService.contactosDeEmpresa(1) } returns
            listOf(
                ContactoDeEmpresaDto(
                    id = 5,
                    nombres = "Hugo",
                    apellidos = "Rodríguez",
                    cargo = "Gerente",
                    tomaDecision = true,
                    esPrincipal = true,
                    email_1 = null,
                    tlf_1 = "964415122",
                ),
            )

        val detalle = service.detalle(1, gerencia)

        assertThat(detalle.contactos).hasSize(1)
        assertThat(detalle.contactos?.first()?.cargo).isEqualTo("Gerente")
        assertThat(detalle.contactos?.first()?.tomaDecision).isTrue()
        assertThat(detalle.contactos?.first()?.esPrincipal).isTrue()
    }

    @Test
    fun `actualizar devuelve el detalle con los contactos vinculados`() {
        val gerencia = UsuarioActual(id = 1, rol = "gerencia")
        val entidad = empresa()
        every { empresaRepository.findById(1) } returns Optional.of(entidad)
        every { empresaRepository.save(entidad) } returns entidad
        every { empleadoService.resumenPorIds(any()) } returns emptyMap()
        every { contactoService.contactosDeEmpresa(1) } returns
            listOf(
                ContactoDeEmpresaDto(
                    id = 5,
                    nombres = "Hugo",
                    apellidos = "Rodríguez",
                    cargo = null,
                    tomaDecision = null,
                    esPrincipal = false,
                    email_1 = null,
                    tlf_1 = null,
                ),
            )

        val detalle = service.actualizar(1, ActualizarEmpresaRequest(ruc = "20123456789"), gerencia)

        assertThat(detalle.contactos).hasSize(1)
    }

    // ── id_vendedor al crear (matriz_permisos.md §2.2) ────────

    @Test
    fun `crear con id_vendedor de otro empleado por un vendedor es PERMISO_INSUFICIENTE`() {
        val vendedor = UsuarioActual(id = 4, rol = "vendedor")

        assertThatThrownBy {
            service.crear(
                CrearEmpresaRequest(ruc = "20123456789", razonSocial = "Transportes ABC", idVendedor = 8),
                vendedor,
            )
        }.isInstanceOf(PermisoInsuficienteException::class.java)

        verify(exactly = 0) { empresaRepository.save(any<Empresa>()) }
        verify(exactly = 0) { driveStorageService.crearCarpeta(any(), any()) }
    }

    @Test
    fun `crear con id_vendedor de otro empleado por el jdv es PERMISO_INSUFICIENTE`() {
        val jdv = UsuarioActual(id = 3, rol = "jdv")

        assertThatThrownBy {
            service.crear(
                CrearEmpresaRequest(ruc = "20123456789", razonSocial = "Transportes ABC", idVendedor = 8),
                jdv,
            )
        }.isInstanceOf(PermisoInsuficienteException::class.java)
    }

    @Test
    fun `crear con id_vendedor de un rol no comercial es VALIDACION`() {
        val gerencia = UsuarioActual(id = 1, rol = "gerencia")
        every { empleadoService.esAsignableComoVendedor(8) } returns false

        assertThatThrownBy {
            service.crear(
                CrearEmpresaRequest(ruc = "20123456789", razonSocial = "Transportes ABC", idVendedor = 8),
                gerencia,
            )
        }.isInstanceOf(ValidacionException::class.java)

        verify(exactly = 0) { empresaRepository.save(any<Empresa>()) }
    }

    @Test
    fun `crear con id_vendedor inexistente es VALIDACION, no un fallo de clave foranea`() {
        val gerencia = UsuarioActual(id = 1, rol = "gerencia")
        every { empleadoService.esAsignableComoVendedor(999) } returns false

        assertThatThrownBy {
            service.crear(
                CrearEmpresaRequest(ruc = "20123456789", razonSocial = "Transportes ABC", idVendedor = 999),
                gerencia,
            )
        }.isInstanceOf(ValidacionException::class.java)
    }

    @Test
    fun `crear por gerencia con un vendedor asignable guarda el id_vendedor`() {
        val guardada = slot<Empresa>()
        every { empleadoService.esAsignableComoVendedor(8) } returns true
        every { empresaRepository.existsByRuc(any()) } returns false
        every { driveStorageService.crearCarpeta(any(), any()) } returns "carpeta-abc"
        every { empresaRepository.save(capture(guardada)) } answers { empresa().apply { idVendedor = guardada.captured.idVendedor } }
        every { empleadoService.resumenPorIds(any()) } returns emptyMap()

        service.crear(
            CrearEmpresaRequest(ruc = "20123456789", razonSocial = "Transportes ABC", idVendedor = 8),
            UsuarioActual(id = 1, rol = "gerencia"),
        )

        assertThat(guardada.captured.idVendedor).isEqualTo(8)
    }

    @Test
    fun `crear sin id_vendedor por un vendedor se asigna a si mismo sin validar el destino`() {
        val guardada = slot<Empresa>()
        every { empresaRepository.existsByRuc(any()) } returns false
        every { driveStorageService.crearCarpeta(any(), any()) } returns "carpeta-abc"
        every { empresaRepository.save(capture(guardada)) } answers { empresa().apply { idVendedor = guardada.captured.idVendedor } }
        every { empleadoService.resumenPorIds(any()) } returns emptyMap()

        service.crear(
            CrearEmpresaRequest(ruc = "20123456789", razonSocial = "Transportes ABC"),
            UsuarioActual(id = 4, rol = "vendedor"),
        )

        assertThat(guardada.captured.idVendedor).isEqualTo(4)
        verify(exactly = 0) { empleadoService.esAsignableComoVendedor(any()) }
    }

    @Test
    fun `crear con el propio id_vendedor por un vendedor equivale a no enviarlo`() {
        val guardada = slot<Empresa>()
        every { empresaRepository.existsByRuc(any()) } returns false
        every { driveStorageService.crearCarpeta(any(), any()) } returns "carpeta-abc"
        every { empresaRepository.save(capture(guardada)) } answers { empresa().apply { idVendedor = guardada.captured.idVendedor } }
        every { empleadoService.resumenPorIds(any()) } returns emptyMap()

        service.crear(
            CrearEmpresaRequest(ruc = "20123456789", razonSocial = "Transportes ABC", idVendedor = 4),
            UsuarioActual(id = 4, rol = "vendedor"),
        )

        assertThat(guardada.captured.idVendedor).isEqualTo(4)
        verify(exactly = 0) { empleadoService.esAsignableComoVendedor(any()) }
    }

    @Test
    fun `crear sin id_vendedor por gerencia deja la empresa sin asignar`() {
        val guardada = slot<Empresa>()
        every { empresaRepository.existsByRuc(any()) } returns false
        every { driveStorageService.crearCarpeta(any(), any()) } returns "carpeta-abc"
        every { empresaRepository.save(capture(guardada)) } answers { empresa().apply { idVendedor = guardada.captured.idVendedor } }
        every { empleadoService.resumenPorIds(any()) } returns emptyMap()

        service.crear(
            CrearEmpresaRequest(ruc = "20123456789", razonSocial = "Transportes ABC"),
            UsuarioActual(id = 1, rol = "gerencia"),
        )

        assertThat(guardada.captured.idVendedor).isNull()
    }

    @Test
    fun `mover a cartera maestra desasigna vendedor y exige que no haya oportunidades activas`() {
        val gerencia = UsuarioActual(id = 1, rol = "gerencia")
        val entidad = empresa().apply { idVendedor = 5 }
        every { empresaRepository.findById(10) } returns Optional.of(entidad)
        every { empresaRepository.save(any()) } answers { firstArg() }

        val dto = service.cambiarCarteraMaestra(10, enCarteraMaestra = true, idVendedor = null, usuario = gerencia)

        assertThat(dto.enCarteraMaestra).isTrue()
        assertThat(entidad.idVendedor).isNull()
    }

    @Test
    fun `mover a cartera maestra con oportunidad activa es 409`() {
        val gerencia = UsuarioActual(id = 1, rol = "gerencia")
        val activa = empresa(estadoCartera = EstadoCartera.oportunidad_activa)
        every { empresaRepository.findById(10) } returns Optional.of(activa)
        assertThatThrownBy { service.cambiarCarteraMaestra(10, true, null, gerencia) }
            .isInstanceOf(pe.quantum.crm.shared.exception.ConflictoException::class.java)
    }

    @Test
    fun `liberar exige id_vendedor, asigna y notifica empresa_asignada`() {
        val gerencia = UsuarioActual(id = 1, rol = "gerencia")
        val reservada = empresa().apply { enCarteraMaestra = true }
        every { empresaRepository.findById(10) } returns Optional.of(reservada)
        every { empleadoService.esAsignableComoVendedor(8) } returns true
        every { empresaRepository.save(any()) } answers { firstArg() }
        every { empleadoService.resumenPorIds(any()) } returns emptyMap()

        val dto = service.cambiarCarteraMaestra(10, enCarteraMaestra = false, idVendedor = 8, usuario = gerencia)

        assertThat(dto.enCarteraMaestra).isFalse()
        assertThat(dto.idVendedor).isEqualTo(8)
        verify {
            notificacionService.notificar(
                destinatarios = setOf(8L),
                idActor = 1,
                tipo = TipoNotificacion.empresa_asignada,
                mensaje = any(),
                entidadTipo = EntidadNotificacion.empresa,
                entidadId = 10,
            )
        }
    }

    @Test
    fun `liberar sin id_vendedor es VALIDACION`() {
        val gerencia = UsuarioActual(id = 1, rol = "gerencia")
        val reservada = empresa().apply { enCarteraMaestra = true }
        every { empresaRepository.findById(10) } returns Optional.of(reservada)
        assertThatThrownBy { service.cambiarCarteraMaestra(10, false, null, gerencia) }
            .isInstanceOf(ValidacionException::class.java)
    }

    @Test
    fun `segmentosPorIds devuelve los segmentos de cada empresa como String`() {
        val entidad = empresa().apply { segmentos = mutableSetOf(pe.quantum.crm.shared.enums.Segmento.interprovincial) }
        every { empresaRepository.findAllById(setOf(1L)) } returns listOf(entidad)

        val resultado = service.segmentosPorIds(listOf(1L))

        assertThat(resultado[1L]).containsExactly("interprovincial")
    }

    @Test
    fun `eliminar borra la empresa cuando existe`() {
        val entidad = empresa()
        every { empresaRepository.findById(1) } returns Optional.of(entidad)
        every { empresaRepository.delete(entidad) } just Runs

        service.eliminar(1)

        verify { empresaRepository.delete(entidad) }
    }

    @Test
    fun `eliminar lanza NoEncontradoException si la empresa no existe`() {
        every { empresaRepository.findById(99) } returns Optional.empty()

        assertThatThrownBy { service.eliminar(99) }
            .isInstanceOf(pe.quantum.crm.shared.exception.NoEncontradoException::class.java)
        verify(exactly = 0) { empresaRepository.delete(any<Empresa>()) }
    }

    // ── Carpeta de Google Drive ───────────────────────────────

    @Test
    fun `crear abre la carpeta de Drive bajo la raiz y guarda su id`() {
        val guardada = slot<Empresa>()
        every { empresaRepository.existsByRuc(any()) } returns false
        every { driveStorageService.crearCarpeta("20123456789 - Transportes ABC", null) } returns "carpeta-abc"
        // JPA devuelve la entidad ya con id; el helper `empresa()` lo trae.
        every { empresaRepository.save(capture(guardada)) } answers {
            empresa().apply { driveFolderId = guardada.captured.driveFolderId }
        }
        every { empleadoService.resumenPorIds(any()) } returns emptyMap()

        val detalle =
            service.crear(
                CrearEmpresaRequest(ruc = "20123456789", razonSocial = "Transportes ABC"),
                UsuarioActual(id = 9, rol = "gerencia"),
            )

        assertThat(guardada.captured.driveFolderId).isEqualTo("carpeta-abc")
        assertThat(detalle.driveFolderId).isEqualTo("carpeta-abc")
    }

    @Test
    fun `crear no persiste la empresa si Drive falla`() {
        every { empresaRepository.existsByRuc(any()) } returns false
        every { driveStorageService.crearCarpeta(any(), any()) } throws DriveException("Drive caido")

        assertThatThrownBy {
            service.crear(
                CrearEmpresaRequest(ruc = "20123456789", razonSocial = "Transportes ABC"),
                UsuarioActual(id = 9, rol = "gerencia"),
            )
        }.isInstanceOf(DriveException::class.java)

        verify(exactly = 0) { empresaRepository.save(any<Empresa>()) }
    }

    @Test
    fun `asegurarCarpetaDrive no vuelve a llamar a Drive si la empresa ya tiene carpeta`() {
        val entidad = empresa().apply { driveFolderId = "ya-existe" }
        every { empresaRepository.findById(1) } returns Optional.of(entidad)
        every { empresaRepository.findByIdForUpdate(1) } returns entidad

        assertThat(service.asegurarCarpetaDrive(1)).isEqualTo("ya-existe")

        verify(exactly = 0) { driveStorageService.crearCarpeta(any(), any()) }
    }

    @Test
    fun `asegurarCarpetaDrive crea la carpeta de una empresa anterior a la migracion`() {
        val entidad = empresa()
        every { empresaRepository.findById(1) } returns Optional.of(entidad)
        every { empresaRepository.findByIdForUpdate(1) } returns entidad
        every { driveStorageService.crearCarpeta("20123456789 - Transportes ABC", null) } returns "carpeta-nueva"
        every { empresaRepository.save(entidad) } returns entidad

        assertThat(service.asegurarCarpetaDrive(1)).isEqualTo("carpeta-nueva")
        assertThat(entidad.driveFolderId).isEqualTo("carpeta-nueva")
    }

    @Test
    fun `asegurarCarpetaDrive usa el fetch con bloqueo pesimista, no el fetch simple`() {
        val entidad = empresa()
        every { empresaRepository.findByIdForUpdate(1) } returns entidad
        every { empresaRepository.findById(1) } returns Optional.of(entidad)
        every { driveStorageService.crearCarpeta(any(), any()) } returns "carpeta-nueva"
        every { empresaRepository.save(entidad) } returns entidad

        service.asegurarCarpetaDrive(1)

        verify { empresaRepository.findByIdForUpdate(1) }
    }

    @Test
    fun `asegurarCarpetaDrive con usuario responde 404 sobre una empresa ajena antes de tocar Drive`() {
        val jdv = UsuarioActual(id = 2, rol = "jdv")
        every { empresaRepository.findById(10) } returns Optional.of(empresa().apply { enCarteraMaestra = true })

        assertThatThrownBy { service.asegurarCarpetaDrive(10, jdv) }
            .isInstanceOf(pe.quantum.crm.shared.exception.NoEncontradoException::class.java)

        verify(exactly = 0) { driveStorageService.crearCarpeta(any(), any()) }
    }

    @Test
    fun `asegurarCarpetaDrive con usuario crea la carpeta si la empresa es visible y no tiene`() {
        val gerencia = UsuarioActual(id = 1, rol = "gerencia")
        val entidad = empresa()
        every { empresaRepository.findById(1) } returns Optional.of(entidad)
        every { empresaRepository.findByIdForUpdate(1) } returns entidad
        every { driveStorageService.crearCarpeta("20123456789 - Transportes ABC", null) } returns "carpeta-nueva"
        every { empresaRepository.save(entidad) } returns entidad

        assertThat(service.asegurarCarpetaDrive(1, gerencia)).isEqualTo("carpeta-nueva")
    }

    @Test
    fun `archivosDrive devuelve los documentos de la carpeta de la empresa`() {
        val gerencia = UsuarioActual(id = 1, rol = "gerencia")
        val entidad = empresa().apply { driveFolderId = "carpeta-abc" }
        every { empresaRepository.findById(1) } returns Optional.of(entidad)
        every { driveStorageService.listarArchivos("carpeta-abc") } returns
            listOf(
                pe.quantum.crm.integracion.drive.DriveArchivoSubido(
                    id = "archivo-1",
                    nombre = "ficha-ruc.pdf",
                    url = "https://drive.google.com/file/d/archivo-1/view",
                    tamanoBytes = 1024,
                    mimeType = "application/pdf",
                ),
            )

        val resultado = service.archivosDrive(1, gerencia)

        assertThat(resultado).hasSize(1)
        assertThat(resultado[0].nombre).isEqualTo("ficha-ruc.pdf")
    }

    @Test
    fun `archivosDrive de una empresa sin carpeta devuelve lista vacia sin llamar a Drive`() {
        val gerencia = UsuarioActual(id = 1, rol = "gerencia")
        every { empresaRepository.findById(1) } returns Optional.of(empresa())

        assertThat(service.archivosDrive(1, gerencia)).isEmpty()

        verify(exactly = 0) { driveStorageService.listarArchivos(any()) }
    }

    @Test
    fun `archivosDrive de una empresa ajena responde 404`() {
        val jdv = UsuarioActual(id = 2, rol = "jdv")
        every { empresaRepository.findById(10) } returns Optional.of(empresa().apply { enCarteraMaestra = true })

        assertThatThrownBy { service.archivosDrive(10, jdv) }
            .isInstanceOf(pe.quantum.crm.shared.exception.NoEncontradoException::class.java)
    }

    @Test
    fun `idsSinCarpetaDrive delega en el repositorio y devuelve los ids pendientes`() {
        every { empresaRepository.findIdsSinCarpetaDrive() } returns listOf(3L, 7L)

        assertThat(service.idsSinCarpetaDrive()).containsExactly(3L, 7L)
    }
}
