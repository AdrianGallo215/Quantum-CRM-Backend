package pe.quantum.crm.importcsvtemp

import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.mock.web.MockMultipartFile
import org.springframework.web.multipart.MultipartFile
import pe.quantum.crm.domain.empresas.EmpresaService
import pe.quantum.crm.domain.empresas.dto.CrearEmpresaRequest
import pe.quantum.crm.domain.empresas.dto.EmpresaDetalleDto
import pe.quantum.crm.shared.enums.Segmento
import pe.quantum.crm.shared.exception.RucDuplicadoException
import pe.quantum.crm.shared.exception.ValidacionException
import pe.quantum.crm.shared.security.UsuarioActual
import java.io.IOException
import java.time.Instant

/**
 * Unit tests de ImportCsvTempServiceImpl sin Spring ni base de datos: EmpresaService
 * se mockea directamente con MockK (mismo patron que EventoServiceImplTest).
 */
class ImportCsvTempServiceImplTest {
    private val empresaService = mockk<EmpresaService>()
    private val service = ImportCsvTempServiceImpl(empresaService)

    private val usuario = UsuarioActual(id = 1, rol = "vendedor")

    private fun csv(vararg filasDeDatos: String): MockMultipartFile {
        val contenido = (listOf("ruc;razon_social;segmento") + filasDeDatos.toList()).joinToString("\n")
        return MockMultipartFile("file", "empresas.csv", "text/csv", contenido.toByteArray(Charsets.UTF_8))
    }

    private fun empresaDetalleDto(
        ruc: String,
        razonSocial: String,
    ) = EmpresaDetalleDto(
        id = 1,
        ruc = ruc,
        razonSocial = razonSocial,
        actividadEcon = null,
        ciiu = null,
        sectorIndustrial = null,
        estadoSunat = null,
        condicionSunat = null,
        direccionFiscal = null,
        ubicacionReal = null,
        distrito = null,
        provincia = null,
        departamento = null,
        avalFiador = null,
        origenLead = null,
        estadoCartera = "no_contactado",
        fileDrive = null,
        driveFolderId = null,
        sitioWeb = null,
        notas = null,
        idVendedor = null,
        vendedor = null,
        segmentos = listOf("urbano"),
        contactos = null,
        createdAt = Instant.now(),
        createdBy = 1,
    )

    @Test
    fun `fila valida crea la empresa via EmpresaService`() {
        val slot = slot<CrearEmpresaRequest>()
        every { empresaService.crearSinCarpetaDrive(capture(slot), usuario) } answers {
            empresaDetalleDto(ruc = slot.captured.ruc, razonSocial = slot.captured.razonSocial)
        }

        val resultado = service.importarEmpresas(csv("20999999999;Beta SRL;urbano"), usuario)

        assertThat(resultado.totalFilas).isEqualTo(1)
        assertThat(resultado.creadas).isEqualTo(1)
        assertThat(resultado.conError).isEqualTo(0)
        assertThat(resultado.detalle.single().estado).isEqualTo("creada")
        assertThat(slot.captured.ruc).isEqualTo("20999999999")
        assertThat(slot.captured.razonSocial).isEqualTo("Beta SRL")
        assertThat(slot.captured.segmentos).containsExactly(Segmento.urbano)
    }

    @Test
    fun `la importacion no crea una carpeta de Drive por fila`() {
        every { empresaService.crearSinCarpetaDrive(any(), usuario) } answers {
            empresaDetalleDto(ruc = "20999999999", razonSocial = "Beta SRL")
        }

        val resultado =
            service.importarEmpresas(
                csv("20999999999;Beta SRL;urbano", "20888888888;Gamma SAC;turismo"),
                usuario,
            )

        // Una llamada a Drive por fila hacia que un archivo grande tardara minutos
        // y el proxy cortara la respuesta con las empresas ya commiteadas: el
        // cliente nunca sabia que filas se habian creado. Las carpetas se rellenan
        // despues con POST /mantenimiento/carpetas-drive, que es idempotente.
        verify(exactly = 0) { empresaService.crear(any(), any()) }
        verify(exactly = 2) { empresaService.crearSinCarpetaDrive(any(), usuario) }
        assertThat(resultado.carpetasDrivePendientes).isEqualTo(2)
    }

    @Test
    fun `ruc con menos de 11 digitos queda en error y no aborta el archivo`() {
        every { empresaService.crearSinCarpetaDrive(match { it.ruc == "20999999999" }, usuario) } returns
            empresaDetalleDto(ruc = "20999999999", razonSocial = "Beta SRL")

        val resultado = service.importarEmpresas(csv("123;Empresa Corta;urbano", "20999999999;Beta SRL;urbano"), usuario)

        assertThat(resultado.totalFilas).isEqualTo(2)
        assertThat(resultado.creadas).isEqualTo(1)
        assertThat(resultado.conError).isEqualTo(1)
        val filaInvalida = resultado.detalle.first { it.fila == 2 }
        assertThat(filaInvalida.estado).isEqualTo("error")
        assertThat(filaInvalida.motivo).isEqualTo("RUC debe tener 11 dígitos")
    }

    @Test
    fun `fila con menos de 3 columnas queda en error`() {
        val resultado = service.importarEmpresas(csv("20999999999;Beta SRL"), usuario)

        assertThat(resultado.detalle.single().estado).isEqualTo("error")
        assertThat(resultado.detalle.single().motivo).contains("Fila incompleta")
    }

    @Test
    fun `segmento desconocido queda en error y no llama a EmpresaService`() {
        val resultado = service.importarEmpresas(csv("20999999999;Beta SRL;corporativo"), usuario)

        assertThat(resultado.detalle.single().estado).isEqualTo("error")
        assertThat(resultado.detalle.single().motivo).isEqualTo("Segmento desconocido: corporativo")
        verify(exactly = 0) { empresaService.crearSinCarpetaDrive(any(), any()) }
    }

    @Test
    fun `ruc ya existente en BD queda en error con el motivo de RucDuplicadoException`() {
        every { empresaService.crearSinCarpetaDrive(any(), usuario) } throws RucDuplicadoException()

        val resultado = service.importarEmpresas(csv("20999999999;Beta SRL;urbano"), usuario)

        assertThat(resultado.detalle.single().estado).isEqualTo("error")
        assertThat(resultado.detalle.single().motivo).isEqualTo("Esta empresa ya está registrada en el sistema")
    }

    @Test
    fun `dos filas con el mismo ruc - la primera se crea y la segunda queda en error por duplicado`() {
        var primeraLlamada = true
        every { empresaService.crearSinCarpetaDrive(match { it.ruc == "20999999999" }, usuario) } answers {
            if (primeraLlamada) {
                primeraLlamada = false
                empresaDetalleDto(ruc = "20999999999", razonSocial = "Beta SRL")
            } else {
                throw RucDuplicadoException()
            }
        }

        val resultado =
            service.importarEmpresas(
                csv("20999999999;Beta SRL;urbano", "20999999999;Beta SRL Duplicada;turismo"),
                usuario,
            )

        assertThat(resultado.detalle[0].estado).isEqualTo("creada")
        assertThat(resultado.detalle[1].estado).isEqualTo("error")
        assertThat(resultado.detalle[1].motivo).isEqualTo("Esta empresa ya está registrada en el sistema")
    }

    @Test
    fun `archivo vacio lanza ValidacionException`() {
        val archivo = MockMultipartFile("file", "empresas.csv", "text/csv", ByteArray(0))

        assertThrows<ValidacionException> { service.importarEmpresas(archivo, usuario) }
    }

    @Test
    fun `archivo con solo cabecera lanza ValidacionException`() {
        assertThrows<ValidacionException> { service.importarEmpresas(csv(), usuario) }
    }

    @Test
    fun `archivo con mas de 500 filas de datos lanza ValidacionException`() {
        val filas = (1..501).map { "fila-de-datos-$it" }.toTypedArray()

        assertThrows<ValidacionException> { service.importarEmpresas(csv(*filas), usuario) }
    }

    @Test
    fun `razon social con punto y coma entre comillas se parsea completa`() {
        val slot = slot<CrearEmpresaRequest>()
        every { empresaService.crearSinCarpetaDrive(capture(slot), usuario) } answers {
            empresaDetalleDto(ruc = slot.captured.ruc, razonSocial = slot.captured.razonSocial)
        }

        service.importarEmpresas(csv("""20999999999;"Empresa S.A.; Sucursal Lima";urbano"""), usuario)

        assertThat(slot.captured.razonSocial).isEqualTo("Empresa S.A.; Sucursal Lima")
    }

    /** Con `;` como delimitador, la coma es un caracter comun y no necesita comillas. */
    @Test
    fun `razon social con coma sin comillas se parsea completa`() {
        val slot = slot<CrearEmpresaRequest>()
        every { empresaService.crearSinCarpetaDrive(capture(slot), usuario) } answers {
            empresaDetalleDto(ruc = slot.captured.ruc, razonSocial = slot.captured.razonSocial)
        }

        service.importarEmpresas(csv("20999999999;Empresa S.A., Sucursal Lima;urbano"), usuario)

        assertThat(slot.captured.razonSocial).isEqualTo("Empresa S.A., Sucursal Lima")
    }

    @Test
    fun `archivo que no se puede leer lanza ValidacionException`() {
        val archivo = mockk<MultipartFile>()
        every { archivo.isEmpty } returns false
        every { archivo.inputStream } throws IOException("boom")

        assertThrows<ValidacionException> { service.importarEmpresas(archivo, usuario) }
    }
}
