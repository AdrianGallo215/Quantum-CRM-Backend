package pe.quantum.crm.domain.empresas

import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Test
import org.springframework.context.ApplicationEventPublisher
import org.springframework.transaction.support.TransactionTemplate
import pe.quantum.crm.domain.contactos.ContactoService
import pe.quantum.crm.domain.empleados.EmpleadoService
import pe.quantum.crm.domain.notificaciones.NotificacionService
import pe.quantum.crm.integracion.drive.DriveException
import pe.quantum.crm.integracion.drive.DriveStorageService
import pe.quantum.crm.shared.enums.EstadoCartera
import java.time.LocalDateTime
import java.util.Optional

/**
 * `eliminar` envia la carpeta de Drive a la papelera (hallazgo [Medio], B.3).
 * Fichero separado de `EmpresaServiceImplTest` para no engordar mas esa clase
 * (detekt LargeClass).
 */
class EmpresaEliminacionTest {
    private val empresaRepository = mockk<EmpresaRepository>()
    private val empleadoService = mockk<EmpleadoService>()
    private val notificacionService = mockk<NotificacionService>(relaxed = true)
    private val eventPublisher = mockk<ApplicationEventPublisher>(relaxed = true)
    private val driveStorageService = mockk<DriveStorageService>()
    private val contactoService = mockk<ContactoService>()
    private val transactionTemplate = mockk<TransactionTemplate>()
    private val service =
        EmpresaServiceImpl(
            empresaRepository,
            empleadoService,
            notificacionService,
            eventPublisher,
            driveStorageService,
            contactoService,
            transactionTemplate,
        )

    private fun empresa(driveFolderId: String? = null) =
        Empresa(
            id = 1,
            ruc = "20123456789",
            razonSocial = "Transportes ABC",
            estadoCartera = EstadoCartera.prospeccion,
            createdAt = LocalDateTime.now(),
            createdBy = 1,
            updatedAt = LocalDateTime.now(),
            updatedBy = 1,
        ).apply { this.driveFolderId = driveFolderId }

    @Test
    fun `eliminar envia la carpeta de Drive a la papelera`() {
        val entidad = empresa(driveFolderId = "carpeta-empresa")
        every { empresaRepository.findById(1) } returns Optional.of(entidad)
        every { empresaRepository.delete(entidad) } just Runs
        every { driveStorageService.enviarCarpetaAPapelera("carpeta-empresa") } just Runs

        service.eliminar(1)

        verify { driveStorageService.enviarCarpetaAPapelera("carpeta-empresa") }
    }

    @Test
    fun `eliminar una empresa sin carpeta no llama a Drive`() {
        val entidad = empresa()
        every { empresaRepository.findById(1) } returns Optional.of(entidad)
        every { empresaRepository.delete(entidad) } just Runs

        service.eliminar(1)

        verify(exactly = 0) { driveStorageService.enviarCarpetaAPapelera(any()) }
    }

    /** Un fallo de Drive no debe revertir el borrado: la empresa ya se elimino. */
    @Test
    fun `eliminar no propaga un fallo de Drive al enviar la carpeta a la papelera`() {
        val entidad = empresa(driveFolderId = "carpeta-empresa")
        every { empresaRepository.findById(1) } returns Optional.of(entidad)
        every { empresaRepository.delete(entidad) } just Runs
        every { driveStorageService.enviarCarpetaAPapelera("carpeta-empresa") } throws DriveException("caido")

        assertDoesNotThrow { service.eliminar(1) }

        verify { empresaRepository.delete(entidad) }
    }
}
