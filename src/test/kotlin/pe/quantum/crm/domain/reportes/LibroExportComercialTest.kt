package pe.quantum.crm.domain.reportes

import org.apache.poi.xssf.usermodel.XSSFWorkbook
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import pe.quantum.crm.domain.reportes.dto.FilaExportComercial
import java.io.ByteArrayInputStream
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * El libro .xlsx del export comercial (plan-15). Se construye y se vuelve a
 * abrir con POI: un test que solo comprobara que el ByteArray no esta vacio no
 * probaria que el archivo es un Excel valido.
 */
class LibroExportComercialTest {
    private fun filaCompleta() =
        FilaExportComercial(
            ruc = "20123456789",
            razonSocial = "Marova Tours S.A.C.",
            empresaCreadaEn = LocalDateTime.of(2026, 1, 15, 9, 0),
            responsableEmpresa = "Aldo Martínez",
            origenLead = "cartera",
            segmentos = "urbano, turismo",
            estadoCartera = "oportunidad_activa",
            idOportunidad = 106,
            vendedorOportunidad = "Aldo Martínez",
            estadoOportunidad = "evaluacion_calidda",
            modelosYUnidades = "KinWin K12 x3",
            unidadesTotales = 3,
            montoTotal = BigDecimal("265440.00"),
            financiadora = "Calidda – Fraccionamiento GNV",
            oportunidadCreadaEn = LocalDateTime.of(2026, 2, 1, 10, 0),
            fechaCierreEstimado = LocalDate.of(2026, 12, 31),
            facturadoEn = null,
            motivoCierre = null,
            notasOportunidad = "Cliente pidió cotización de 3 unidades",
            siguienteAccion = "llamada",
            principalBloqueo = null,
            primerContacto = LocalDateTime.of(2026, 2, 2, 8, 0),
            ultimaGestion = LocalDateTime.of(2026, 3, 1, 16, 0),
            diasSinGestion = 12,
            actividadTipo = "tarea",
            actividadTitulo = "llamada",
            actividadTipoAccion = "llamada",
            actividadEstado = "completada",
            actividadFecha = LocalDateTime.of(2026, 3, 1, 16, 0),
            actividadResponsable = "Aldo Martínez",
            actividadDescripcion = "Seguimiento de cotización",
            actividadComentarios = "No contestó | Reagendado",
            actividadRegistradaEn = LocalDateTime.of(2026, 3, 1, 15, 0),
        )

    private fun filaVacia() =
        FilaExportComercial(
            ruc = null, razonSocial = null, empresaCreadaEn = null, responsableEmpresa = null,
            origenLead = null, segmentos = null, estadoCartera = null,
            idOportunidad = null, vendedorOportunidad = null, estadoOportunidad = null,
            modelosYUnidades = null, unidadesTotales = null, montoTotal = null, financiadora = null,
            oportunidadCreadaEn = null, fechaCierreEstimado = null, facturadoEn = null,
            motivoCierre = null, notasOportunidad = null, siguienteAccion = null,
            principalBloqueo = null, primerContacto = null, ultimaGestion = null, diasSinGestion = null,
            actividadTipo = null, actividadTitulo = null, actividadTipoAccion = null,
            actividadEstado = null, actividadFecha = null, actividadResponsable = null,
            actividadDescripcion = null, actividadComentarios = null, actividadRegistradaEn = null,
        )

    /** Abre el ByteArray como libro real y devuelve el texto de una celda. */
    private fun celda(
        bytes: ByteArray,
        indiceFila: Int,
        indiceColumna: Int,
    ): String =
        XSSFWorkbook(ByteArrayInputStream(bytes)).use { libro ->
            libro.getSheetAt(0).getRow(indiceFila).getCell(indiceColumna).stringCellValue
        }

    @Test
    fun `el archivo generado es un xlsx valido con una sola hoja`() {
        val bytes = LibroExportComercial.construir(listOf(filaCompleta()))

        XSSFWorkbook(ByteArrayInputStream(bytes)).use { libro ->
            assertThat(libro.numberOfSheets).isEqualTo(1)
            assertThat(libro.getSheetAt(0).sheetName).isEqualTo("Gestión comercial")
        }
    }

    @Test
    fun `la primera fila son las cabeceras en el orden declarado`() {
        val bytes = LibroExportComercial.construir(listOf(filaCompleta()))

        XSSFWorkbook(ByteArrayInputStream(bytes)).use { libro ->
            val cabecera = libro.getSheetAt(0).getRow(0)
            assertThat(cabecera.lastCellNum.toInt()).isEqualTo(LibroExportComercial.CABECERAS.size)
            LibroExportComercial.CABECERAS.forEachIndexed { indice, esperada ->
                assertThat(cabecera.getCell(indice).stringCellValue).isEqualTo(esperada)
            }
        }
    }

    @Test
    fun `cada fila de datos ocupa una fila del Excel debajo de la cabecera`() {
        val bytes = LibroExportComercial.construir(listOf(filaCompleta(), filaVacia()))

        XSSFWorkbook(ByteArrayInputStream(bytes)).use { libro ->
            // Fila 0 = cabecera; las dos de datos van en 1 y 2.
            assertThat(libro.getSheetAt(0).lastRowNum).isEqualTo(2)
        }
    }

    @Test
    fun `los valores presentes se escriben con su etiqueta de negocio`() {
        val bytes = LibroExportComercial.construir(listOf(filaCompleta()))

        assertThat(celda(bytes, 1, 0)).isEqualTo("20123456789")
        assertThat(celda(bytes, 1, 1)).isEqualTo("Marova Tours S.A.C.")
        assertThat(celda(bytes, 1, 4)).isEqualTo("Cartera propia")
        assertThat(celda(bytes, 1, 5)).isEqualTo("Transporte urbano, Turismo")
        assertThat(celda(bytes, 1, 6)).isEqualTo("Oportunidad activa")
        assertThat(celda(bytes, 1, 9)).isEqualTo("Evaluación Cálidda")
    }

    @Test
    fun `una fila enteramente vacia se exporta con guion en todas las celdas`() {
        val bytes = LibroExportComercial.construir(listOf(filaVacia()))

        XSSFWorkbook(ByteArrayInputStream(bytes)).use { libro ->
            val fila = libro.getSheetAt(0).getRow(1)
            LibroExportComercial.CABECERAS.indices.forEach { columna ->
                assertThat(fila.getCell(columna).stringCellValue)
                    .withFailMessage("La columna %d debia llevar guion", columna)
                    .isEqualTo("-")
            }
        }
    }

    @Test
    fun `el principal bloqueo siempre sale como guion porque el CRM no lo guarda`() {
        // R8 del ticket: no se inventa contenido para una columna sin campo real.
        val bytes = LibroExportComercial.construir(listOf(filaCompleta()))
        val columna = LibroExportComercial.CABECERAS.indexOf("Principal bloqueo")

        assertThat(celda(bytes, 1, columna)).isEqualTo("-")
    }

    @Test
    fun `sin filas el archivo se genera igual y solo lleva la cabecera`() {
        // Un rango de fechas sin datos NO es un error: es un Excel con cabecera y nada mas.
        val bytes = LibroExportComercial.construir(emptyList())

        XSSFWorkbook(ByteArrayInputStream(bytes)).use { libro ->
            assertThat(libro.getSheetAt(0).lastRowNum).isEqualTo(0)
            assertThat(libro.getSheetAt(0).getRow(0).getCell(0).stringCellValue).isEqualTo("RUC")
        }
    }

    @Test
    fun `un texto mas largo que el limite de Excel se recorta en vez de romper el archivo`() {
        val larguisimo = "x".repeat(40_000)
        val fila = filaCompleta().copy(actividadComentarios = larguisimo)
        val columna = LibroExportComercial.CABECERAS.indexOf("Comentarios de seguimiento")

        val bytes = LibroExportComercial.construir(listOf(fila))

        // Excel no admite mas de 32767 caracteres por celda: POI lanzaria si no se recorta.
        assertThat(celda(bytes, 1, columna)).hasSize(32_767)
    }
}
