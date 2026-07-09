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
 * Importación "mejor esfuerzo" de empresas desde CSV: cada fila corre en la
 * transacción propia de `EmpresaService.crear`, así que una fila inválida no
 * revierte las demás y un RUC repetido dentro del mismo archivo se detecta solo
 * (la fila anterior ya commiteó antes de procesar la siguiente).
 */
@Service
class ImportCsvTempServiceImpl(
    private val empresaService: EmpresaService,
) : ImportCsvTempService {
    override fun importarEmpresas(
        archivo: MultipartFile,
        usuario: UsuarioActual,
    ): ImportEmpresasResultDto {
        if (archivo.isEmpty) {
            throw ValidacionException("El archivo CSV está vacío")
        }
        val lineas =
            try {
                archivo.inputStream.bufferedReader(Charsets.UTF_8).readLines()
                    .map { it.removeSuffix("\r") }
                    .filter { it.isNotBlank() }
            } catch (ex: IOException) {
                throw ValidacionException("No se pudo leer el archivo CSV")
            }
        if (lineas.size < 2) {
            throw ValidacionException("El archivo CSV no tiene filas de datos, solo cabecera")
        }
        val filasDatos = lineas.drop(1)
        if (filasDatos.size > MAX_FILAS_DATOS) {
            throw ValidacionException("El archivo excede el máximo de $MAX_FILAS_DATOS filas de datos")
        }

        val detalle =
            filasDatos.mapIndexed { indice, linea ->
                procesarFila(fila = indice + 2, linea = linea, usuario = usuario)
            }
        val creadas = detalle.count { it.estado == ESTADO_CREADA }
        return ImportEmpresasResultDto(
            totalFilas = detalle.size,
            creadas = creadas,
            conError = detalle.size - creadas,
            detalle = detalle,
        )
    }

    private fun procesarFila(
        fila: Int,
        linea: String,
        usuario: UsuarioActual,
    ): ImportEmpresaFilaResultado {
        val campos = parseCsvLine(linea)
        if (campos.size < 3) {
            return ImportEmpresaFilaResultado(
                fila = fila,
                ruc = campos.getOrNull(0)?.trim(),
                razonSocial = campos.getOrNull(1)?.trim(),
                estado = ESTADO_ERROR,
                motivo = "Fila incompleta: se esperaban 3 columnas (ruc, razon_social, segmento)",
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
            empresaService.crear(request, usuario)
            ImportEmpresaFilaResultado(fila, ruc, razonSocial, ESTADO_CREADA, null)
        } catch (ex: ApiException) {
            ImportEmpresaFilaResultado(fila, ruc, razonSocial, ESTADO_ERROR, ex.message)
        }
    }

    /** Parser CSV mínimo: soporta campos entre comillas dobles con comas internas. */
    private fun parseCsvLine(linea: String): List<String> {
        val campos = mutableListOf<String>()
        val actual = StringBuilder()
        var dentroComillas = false
        var i = 0
        while (i < linea.length) {
            val c = linea[i]
            when {
                c == '"' && dentroComillas && i + 1 < linea.length && linea[i + 1] == '"' -> {
                    actual.append('"')
                    i++
                }
                c == '"' -> dentroComillas = !dentroComillas
                c == ',' && !dentroComillas -> {
                    campos.add(actual.toString())
                    actual.clear()
                }
                else -> actual.append(c)
            }
            i++
        }
        campos.add(actual.toString())
        return campos
    }

    private companion object {
        const val MAX_FILAS_DATOS = 1000
        const val ESTADO_CREADA = "creada"
        const val ESTADO_ERROR = "error"
        val RUC_REGEX = Regex("\\d{11}")
    }
}
