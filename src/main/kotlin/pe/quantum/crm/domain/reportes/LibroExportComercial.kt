package pe.quantum.crm.domain.reportes

import org.apache.poi.ss.usermodel.Sheet
import org.apache.poi.ss.util.CellRangeAddress
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import pe.quantum.crm.domain.reportes.dto.FilaExportComercial
import java.io.ByteArrayOutputStream

/**
 * Construye el .xlsx del export comercial (plan-15). Funcion pura: filas -> bytes.
 *
 * UNA SOLA HOJA por decision del solicitante. La forma es una tabla plana de
 * hechos (una fila por actividad, repitiendo prospecto y oportunidad) porque es
 * la unica que Excel sabe filtrar y pivotar sin ayuda.
 *
 * TODAS las celdas se escriben como TEXTO, incluidas fechas y montos. Es
 * deliberado: el destino es lectura y filtrado, no aritmetica sobre el archivo,
 * y el texto evita que Excel reinterprete una fecha segun la configuracion
 * regional de quien lo abre — el fallo clasico de un export dd/mm vs mm/dd.
 */
object LibroExportComercial {
    const val NOMBRE_HOJA = "Gestión comercial"

    /** Tope duro del formato XLSX. POI lanza si una celda lo supera. */
    private const val MAX_CARACTERES_CELDA = 32_767

    private const val ANCHO_COLUMNA = 4_500

    val CABECERAS: List<String> =
        listOf(
            // Bloque 1: prospecto / empresa
            "RUC",
            "Razón social",
            "Fecha de ingreso",
            "Responsable del prospecto",
            "Origen del prospecto",
            "Segmento",
            "Estado de cartera",
            // Bloque 2: oportunidad
            "ID oportunidad",
            "Vendedor de la oportunidad",
            "Etapa / Estado actual",
            "Modelos y unidades",
            "Unidades totales",
            "Monto total",
            "Financiadora",
            "Fecha de creación de la oportunidad",
            "Fecha estimada de cierre",
            "Fecha de facturación",
            "Motivo de no cierre",
            "Notas de la oportunidad",
            "Siguiente acción",
            "Principal bloqueo",
            "Fecha de primer contacto",
            "Fecha de última gestión",
            "Días sin gestión",
            // Bloque 3: actividad
            "Tipo de actividad",
            "Actividad",
            "Tipo de acción",
            "Estado de la actividad",
            "Fecha de la actividad",
            "Responsable de la actividad",
            "Descripción",
            "Comentarios de seguimiento",
            "Fecha de registro",
        )

    fun construir(filas: List<FilaExportComercial>): ByteArray {
        XSSFWorkbook().use { libro ->
            val hoja = libro.createSheet(NOMBRE_HOJA)
            escribirCabecera(libro, hoja)
            filas.forEachIndexed { indice, fila ->
                escribirFila(hoja, indice + 1, valoresDe(fila))
            }
            anchoDeColumnas(hoja)
            // El autofiltro y el panel congelado son lo que convierte la hoja en
            // algo usable: gerencia filtra por vendedor o por etapa sin prepararla.
            hoja.createFreezePane(0, 1)
            // Sin filas el rango queda 0..0: solo la cabecera, que es valido.
            hoja.setAutoFilter(CellRangeAddress(0, filas.size, 0, CABECERAS.size - 1))
            return ByteArrayOutputStream().use { salida ->
                libro.write(salida)
                salida.toByteArray()
            }
        }
    }

    /**
     * El orden de esta lista ES el orden de [CABECERAS]. Si tocas una, toca la
     * otra: el test `la primera fila son las cabeceras en el orden declarado`
     * comprueba el tamano, pero no puede comprobar que cada valor este bajo su
     * titulo.
     */
    @Suppress("LongMethod") // 33 columnas en una lista plana; partirla la haria ilegible.
    private fun valoresDe(fila: FilaExportComercial): List<String> =
        listOf(
            EtiquetasExport.texto(fila.ruc),
            EtiquetasExport.texto(fila.razonSocial),
            EtiquetasExport.fecha(fila.empresaCreadaEn),
            EtiquetasExport.texto(fila.responsableEmpresa),
            EtiquetasExport.origen(fila.origenLead),
            EtiquetasExport.segmento(fila.segmentos),
            EtiquetasExport.cartera(fila.estadoCartera),
            EtiquetasExport.texto(fila.idOportunidad),
            EtiquetasExport.texto(fila.vendedorOportunidad),
            EtiquetasExport.estado(fila.estadoOportunidad),
            EtiquetasExport.texto(fila.modelosYUnidades),
            EtiquetasExport.texto(fila.unidadesTotales),
            EtiquetasExport.texto(fila.montoTotal?.toPlainString()),
            EtiquetasExport.texto(fila.financiadora),
            EtiquetasExport.fecha(fila.oportunidadCreadaEn),
            EtiquetasExport.dia(fila.fechaCierreEstimado),
            EtiquetasExport.fecha(fila.facturadoEn),
            EtiquetasExport.texto(fila.motivoCierre),
            EtiquetasExport.texto(fila.notasOportunidad),
            EtiquetasExport.texto(fila.siguienteAccion),
            // El CRM no guarda "principal bloqueo" en ningun campo. Se emite el
            // guion a proposito: inventar contenido aqui esta prohibido (R8).
            EtiquetasExport.texto(fila.principalBloqueo),
            EtiquetasExport.fecha(fila.primerContacto),
            EtiquetasExport.fecha(fila.ultimaGestion),
            EtiquetasExport.texto(fila.diasSinGestion),
            EtiquetasExport.texto(fila.actividadTipo),
            EtiquetasExport.texto(fila.actividadTitulo),
            EtiquetasExport.texto(fila.actividadTipoAccion),
            EtiquetasExport.texto(fila.actividadEstado),
            EtiquetasExport.fecha(fila.actividadFecha),
            EtiquetasExport.texto(fila.actividadResponsable),
            EtiquetasExport.texto(fila.actividadDescripcion),
            EtiquetasExport.texto(fila.actividadComentarios),
            EtiquetasExport.fecha(fila.actividadRegistradaEn),
        )

    private fun escribirCabecera(
        libro: XSSFWorkbook,
        hoja: Sheet,
    ) {
        val negrita =
            libro.createCellStyle().apply {
                setFont(libro.createFont().apply { bold = true })
            }
        val fila = hoja.createRow(0)
        CABECERAS.forEachIndexed { columna, titulo ->
            fila.createCell(columna).apply {
                setCellValue(titulo)
                cellStyle = negrita
            }
        }
    }

    private fun escribirFila(
        hoja: Sheet,
        indice: Int,
        valores: List<String>,
    ) {
        val fila = hoja.createRow(indice)
        valores.forEachIndexed { columna, valor ->
            fila.createCell(columna).setCellValue(valor.take(MAX_CARACTERES_CELDA))
        }
    }

    /**
     * Ancho fijo en vez de `autoSizeColumn`: el autoajuste recorre todas las
     * filas por columna y en un export de miles de filas domina el tiempo de
     * respuesta entero. Un ancho generoso y constante es suficiente.
     */
    private fun anchoDeColumnas(hoja: Sheet) {
        CABECERAS.indices.forEach { columna -> hoja.setColumnWidth(columna, ANCHO_COLUMNA) }
    }
}
