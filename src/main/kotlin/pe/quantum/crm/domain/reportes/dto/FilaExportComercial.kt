@file:Suppress("MatchingDeclarationName", "ktlint:standard:filename")

package pe.quantum.crm.domain.reportes.dto

import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * Una fila plana del export comercial (plan-15). Una hoja unica, una fila por
 * ACTIVIDAD, repitiendo los datos del prospecto y de la oportunidad.
 *
 * Los campos son nullable a proposito: aqui se guarda lo que la base devuelve,
 * crudo. La traduccion de null a "-" ocurre despues, en `EtiquetasExport`, para
 * que la conversion sea una sola y este testeada en un solo sitio.
 *
 * Tres bloques, en el orden en que salen las columnas del Excel:
 *   1. Prospecto/empresa  (7 columnas)
 *   2. Oportunidad        (17 columnas)
 *   3. Actividad          (9 columnas)
 */
@Suppress("LongParameterList") // Una fila plana de export refleja 33 columnas.
data class FilaExportComercial(
    // ── Bloque 1: prospecto / empresa ──────────────────────────
    val ruc: String?,
    val razonSocial: String?,
    val empresaCreadaEn: LocalDateTime?,
    val responsableEmpresa: String?,
    val origenLead: String?,
    val segmentos: String?,
    val estadoCartera: String?,
    // ── Bloque 2: oportunidad ──────────────────────────────────
    val idOportunidad: Long?,
    val vendedorOportunidad: String?,
    val estadoOportunidad: String?,
    val modelosYUnidades: String?,
    val unidadesTotales: Int?,
    val montoTotal: BigDecimal?,
    val financiadora: String?,
    val oportunidadCreadaEn: LocalDateTime?,
    val fechaCierreEstimado: LocalDate?,
    val facturadoEn: LocalDateTime?,
    val motivoCierre: String?,
    val notasOportunidad: String?,
    val siguienteAccion: String?,
    val principalBloqueo: String?,
    val primerContacto: LocalDateTime?,
    val ultimaGestion: LocalDateTime?,
    val diasSinGestion: Int?,
    // ── Bloque 3: actividad ────────────────────────────────────
    val actividadTipo: String?,
    val actividadTitulo: String?,
    val actividadTipoAccion: String?,
    val actividadEstado: String?,
    val actividadFecha: LocalDateTime?,
    val actividadResponsable: String?,
    val actividadDescripcion: String?,
    val actividadComentarios: String?,
    val actividadRegistradaEn: LocalDateTime?,
)
