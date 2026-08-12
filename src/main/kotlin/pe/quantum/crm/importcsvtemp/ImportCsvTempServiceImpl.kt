package pe.quantum.crm.importcsvtemp

import org.springframework.stereotype.Service
import org.springframework.web.multipart.MultipartFile
import pe.quantum.crm.domain.empresas.EmpresaService
import pe.quantum.crm.domain.empresas.dto.CrearEmpresaRequest
import pe.quantum.crm.importcsvtemp.dto.ImportEmpresaFilaResultado
import pe.quantum.crm.importcsvtemp.dto.ImportEmpresasResultDto
import pe.quantum.crm.shared.enums.Segmento
import pe.quantum.crm.shared.exception.ApiException
import pe.quantum.crm.shared.exception.ValidacionException
import pe.quantum.crm.shared.security.UsuarioActual
import java.io.IOException

/**
 * Importación "mejor esfuerzo" de empresas desde CSV: cada fila corre en su propia
 * transacción, así que una fila inválida no revierte las demás y un RUC repetido
 * dentro del mismo archivo se detecta solo (la fila anterior ya commiteó antes de
 * procesar la siguiente).
 *
 * NO crea la carpeta de Google Drive de cada empresa. Hacerlo por fila añadía una
 * llamada de red (~300 ms) a cada una: 800 filas eran ~4 minutos, el proxy cortaba
 * la respuesta (~100 s en Render) y el cliente nunca sabía qué filas se habían
 * creado — pero las empresas ya estaban commiteadas, así que reintentar el archivo
 * devolvía N errores `RUC_DUPLICADO` indistinguibles de duplicados reales del
 * origen. Las carpetas se rellenan después con `POST /mantenimiento/carpetas-drive`,
 * que es idempotente y reanudable.
 *
 * Este módulo es desechable y NO forma parte de contrato_api.md (ver
 * [ImportCsvTempController]), así que puede desacoplarse de Drive; el alta unitaria
 * `POST /empresas` no puede.
 */
@Service
class ImportCsvTempServiceImpl(
    private val empresaService: EmpresaService,
) : ImportCsvTempService {
    @Suppress("SwallowedException") // Al usuario solo le sirve "no se pudo leer", no el detalle de IO.
    override fun importarEmpresas(
        archivo: MultipartFile,
        usuario: UsuarioActual,
    ): ImportEmpresasResultDto {
        if (archivo.isEmpty) {
            throw ValidacionException("El archivo CSV está vacío")
        }
        val texto =
            try {
                archivo.inputStream.bufferedReader(Charsets.UTF_8).readText()
            } catch (ex: IOException) {
                throw ValidacionException("No se pudo leer el archivo CSV")
            }
        val registros = parsearRegistros(texto)
        if (registros.isEmpty()) {
            throw ValidacionException("El archivo CSV está vacío")
        }
        val filasDatos = if (esCabecera(registros.first())) registros.drop(1) else registros
        if (filasDatos.isEmpty()) {
            throw ValidacionException("El archivo CSV no tiene filas de datos, solo cabecera")
        }
        if (filasDatos.size > MAX_FILAS_DATOS) {
            throw ValidacionException("El archivo excede el máximo de $MAX_FILAS_DATOS filas de datos")
        }

        val detalle =
            filasDatos.map { procesarFila(fila = it.linea, campos = it.campos, usuario = usuario) }
        val creadas = detalle.count { it.estado == ESTADO_CREADA }
        return ImportEmpresasResultDto(
            totalFilas = detalle.size,
            creadas = creadas,
            conError = detalle.size - creadas,
            detalle = detalle,
            // Todas las creadas quedan sin carpeta: hay que pasar el backfill.
            carpetasDrivePendientes = creadas,
        )
    }

    @Suppress("ReturnCount") // Cadena de validaciones tipo guard clause; dividirla no mejora la legibilidad.
    private fun procesarFila(
        fila: Int,
        campos: List<String>,
        usuario: UsuarioActual,
    ): ImportEmpresaFilaResultado {
        if (campos.size < COLUMNAS_ESPERADAS) {
            return ImportEmpresaFilaResultado(
                fila = fila,
                ruc = campos.getOrNull(0)?.trim(),
                razonSocial = campos.getOrNull(1)?.trim(),
                estado = ESTADO_ERROR,
                motivo = "Fila incompleta: se esperaban 3 columnas separadas por ';' (ruc;razon_social;segmento)",
            )
        }
        val ruc = campos[0].trim()
        val razonSocial = campos[1].trim()
        val segmentoTexto = campos[2].trim()

        if (!RUC_REGEX.matches(ruc)) {
            return ImportEmpresaFilaResultado(fila, ruc, razonSocial, ESTADO_ERROR, "RUC debe tener 11 dígitos")
        }
        if (razonSocial.isBlank()) {
            return ImportEmpresaFilaResultado(fila, ruc, razonSocial, ESTADO_ERROR, "Razón social no puede estar vacía")
        }
        val segmento =
            runCatching { Segmento.valueOf(segmentoTexto.lowercase()) }.getOrNull()
                ?: return ImportEmpresaFilaResultado(fila, ruc, razonSocial, ESTADO_ERROR, "Segmento desconocido: $segmentoTexto")

        return try {
            val request = CrearEmpresaRequest(ruc = ruc, razonSocial = razonSocial, segmentos = listOf(segmento))
            empresaService.crearSinCarpetaDrive(request, usuario)
            ImportEmpresaFilaResultado(fila, ruc, razonSocial, ESTADO_CREADA, null)
        } catch (ex: ApiException) {
            ImportEmpresaFilaResultado(fila, ruc, razonSocial, ESTADO_ERROR, ex.message)
        }
    }

    /** Un registro CSV con la linea FISICA (1-based) donde empieza: la que el usuario ve en Excel. */
    private data class FilaCsv(
        val linea: Int,
        val campos: List<String>,
    )

    /**
     * La primera fila es cabecera solo si NO parece un dato. Descartarla siempre
     * hacia que un archivo exportado sin fila de titulos perdiera su primera empresa
     * sin decir nada.
     */
    private fun esCabecera(fila: FilaCsv): Boolean = fila.campos.firstOrNull()?.trim()?.let { !RUC_REGEX.matches(it) } ?: false

    /**
     * Parte el texto en registros CSV. Delimitador `;`, comillas dobles escapadas por
     * duplicacion (`""`), y —esto es lo nuevo— un salto de linea DENTRO de un campo
     * entrecomillado NO separa registros: Excel exporta asi en cuanto una razon
     * social lleva un salto, y antes eso producia dos filas fantasma. Las lineas en
     * blanco se saltan pero SI se cuentan, para que los numeros del reporte de
     * errores casen con el archivo.
     */
    @Suppress("NestedBlockDepth", "CyclomaticComplexMethod") // Automata de un solo paso; partirlo dispersaria el estado.
    private fun parsearRegistros(texto: String): List<FilaCsv> {
        val filas = mutableListOf<FilaCsv>()
        val campos = mutableListOf<String>()
        val actual = StringBuilder()
        var dentroComillas = false
        var linea = 1
        var lineaInicio = 1
        var i = 0

        fun cerrarRegistro() {
            campos.add(actual.toString())
            actual.clear()
            if (campos.any { it.isNotBlank() }) {
                filas.add(FilaCsv(lineaInicio, campos.toList()))
            }
            campos.clear()
        }

        while (i < texto.length) {
            val c = texto[i]
            when {
                c == '"' && dentroComillas && i + 1 < texto.length && texto[i + 1] == '"' -> {
                    actual.append('"')
                    i++
                }
                c == '"' -> dentroComillas = !dentroComillas
                c == ';' && !dentroComillas -> {
                    campos.add(actual.toString())
                    actual.clear()
                }
                (c == '\n' || c == '\r') && !dentroComillas -> {
                    cerrarRegistro()
                    if (c == '\r' && i + 1 < texto.length && texto[i + 1] == '\n') {
                        i++
                    }
                    linea++
                    lineaInicio = linea
                }
                c == '\r' && dentroComillas -> Unit // `\r\n` interno se normaliza a `\n`
                else -> {
                    if (c == '\n') {
                        linea++
                    }
                    actual.append(c)
                }
            }
            i++
        }
        if (actual.isNotEmpty() || campos.isNotEmpty()) {
            cerrarRegistro()
        }
        return filas
    }

    private companion object {
        /**
         * Tope por archivo. Sin Drive cada fila son ~4 idas y vueltas a PostgreSQL;
         * a ~20 ms de latencia gestionada, 500 filas son ~40 s, holgadamente por
         * debajo de los ~100 s a los que el proxy corta. Antes eran 1000 filas CON
         * una llamada a Drive cada una, lo que garantizaba el corte. Para archivos
         * mayores, partirlos: el import es reanudable de hecho (las filas ya
         * creadas vuelven como `RUC_DUPLICADO`).
         */
        const val MAX_FILAS_DATOS = 500
        const val COLUMNAS_ESPERADAS = 3
        const val ESTADO_CREADA = "creada"
        const val ESTADO_ERROR = "error"
        val RUC_REGEX = Regex("\\d{11}")
    }
}
