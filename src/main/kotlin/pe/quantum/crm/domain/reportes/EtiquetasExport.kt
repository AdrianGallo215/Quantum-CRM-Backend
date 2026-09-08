package pe.quantum.crm.domain.reportes

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Traduccion del modelo al idioma del Excel que lee gerencia (plan-15).
 *
 * Dos responsabilidades, las dos puras y sin base de datos:
 *  1. La REGLA DEL GUION: ninguna celda queda vacia. Lo que no tiene dato lleva
 *     "-". Un vacio se lee como "falta cargarlo"; el guion dice "el CRM no
 *     guarda esto". Es un requisito explicito del solicitante.
 *  2. Etiquetas de negocio: `evaluacion_calidda` no se lee en una reunion;
 *     "Evaluacion Calidda" si.
 *
 * Un valor de enum desconocido NUNCA rompe el archivo: se emite crudo. Preferir
 * una celda fea a un export que no se genera.
 */
object EtiquetasExport {
    const val SIN_DATO = "-"

    private val FORMATO_FECHA_HORA = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
    private val FORMATO_DIA = DateTimeFormatter.ofPattern("yyyy-MM-dd")

    private val ESTADOS =
        mapOf(
            "evaluacion_calidda" to "Evaluación Cálidda",
            "documentos_legales" to "Documentos legales",
            "facturado" to "Facturado",
            "cerrado" to "Cerrado",
        )

    private val ORIGENES =
        mapOf(
            "cartera" to "Cartera propia",
            "visita_fria" to "Prospección en calle",
            "referido_calidda" to "Referido Cálidda",
            "red_contactos" to "Referido / red de contactos",
            "otro" to "Otro",
        )

    private val SEGMENTOS =
        mapOf(
            "urbano" to "Transporte urbano",
            "personal" to "Transporte de personal",
            "turismo" to "Turismo",
            "interprovincial" to "Interprovincial",
            "otro" to "Otro",
        )

    private val CARTERAS =
        mapOf(
            "no_contactado" to "No contactado",
            "no_aplica" to "No aplica",
            "no_interesado" to "No interesado",
            "prospeccion" to "Prospección",
            "oportunidad_activa" to "Oportunidad activa",
            "cliente" to "Cliente",
        )

    /** La regla del guion. Todo lo que va al Excel pasa por aqui. */
    fun texto(valor: Any?): String {
        val crudo = valor?.toString()
        return if (crudo.isNullOrBlank()) SIN_DATO else crudo
    }

    fun fecha(valor: LocalDateTime?): String = valor?.format(FORMATO_FECHA_HORA) ?: SIN_DATO

    /**
     * Un dia de calendario se exporta SIN hora. Darle hora a un DATE lo desplaza
     * de dia segun la zona (contrato_api.md §29, la trampa de las dos fechas).
     */
    fun dia(valor: LocalDate?): String = valor?.format(FORMATO_DIA) ?: SIN_DATO

    fun estado(valor: String?): String = traducir(valor, ESTADOS)

    fun origen(valor: String?): String = traducir(valor, ORIGENES)

    fun cartera(valor: String?): String = traducir(valor, CARTERAS)

    /** Multivalor: `empresa_segmentos` guarda varios por empresa, ya concatenados por el SQL. */
    fun segmento(valor: String?): String {
        if (valor.isNullOrBlank()) return SIN_DATO
        return valor.split(",").joinToString(", ") { parte ->
            val clave = parte.trim()
            SEGMENTOS[clave] ?: clave
        }
    }

    private fun traducir(
        valor: String?,
        etiquetas: Map<String, String>,
    ): String {
        if (valor.isNullOrBlank()) return SIN_DATO
        return etiquetas[valor] ?: valor
    }
}
