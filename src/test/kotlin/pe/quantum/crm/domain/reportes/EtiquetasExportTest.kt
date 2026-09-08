package pe.quantum.crm.domain.reportes

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * La regla del guion y la traduccion de enums a etiquetas de negocio (plan-15).
 *
 * Gerencia pidio explicitamente que ninguna celda quede vacia: lo que no tiene
 * dato lleva "-". Un vacio en Excel se confunde con "todavia no lo cargaron";
 * un guion dice "el CRM no guarda esto".
 */
class EtiquetasExportTest {
    // ── La regla del guion ─────────────────────────────────────

    @Test
    fun `un valor nulo se exporta como guion`() {
        assertThat(EtiquetasExport.texto(null)).isEqualTo("-")
    }

    @Test
    fun `una cadena vacia se exporta como guion`() {
        assertThat(EtiquetasExport.texto("")).isEqualTo("-")
    }

    @Test
    fun `una cadena de solo espacios se exporta como guion`() {
        assertThat(EtiquetasExport.texto("   ")).isEqualTo("-")
    }

    @Test
    fun `un valor presente se exporta tal cual`() {
        assertThat(EtiquetasExport.texto("Marova Tours S.A.C.")).isEqualTo("Marova Tours S.A.C.")
    }

    @Test
    fun `un numero se exporta como su representacion decimal`() {
        assertThat(EtiquetasExport.texto(12)).isEqualTo("12")
    }

    // ── Fechas ─────────────────────────────────────────────────

    @Test
    fun `una fecha con hora se exporta en formato legible`() {
        val momento = LocalDateTime.of(2026, 9, 8, 14, 30)

        assertThat(EtiquetasExport.fecha(momento)).isEqualTo("2026-09-08 14:30")
    }

    @Test
    fun `una fecha con hora nula se exporta como guion`() {
        assertThat(EtiquetasExport.fecha(null)).isEqualTo("-")
    }

    @Test
    fun `un dia de calendario se exporta sin hora`() {
        assertThat(EtiquetasExport.dia(LocalDate.of(2026, 12, 31))).isEqualTo("2026-12-31")
    }

    @Test
    fun `un dia de calendario nulo se exporta como guion`() {
        assertThat(EtiquetasExport.dia(null)).isEqualTo("-")
    }

    // ── Estado de la oportunidad: los 4 valores del enum, ni uno mas ──

    @Test
    fun `los cuatro estados del pipeline tienen etiqueta de negocio`() {
        assertThat(EtiquetasExport.estado("evaluacion_calidda")).isEqualTo("Evaluación Cálidda")
        assertThat(EtiquetasExport.estado("documentos_legales")).isEqualTo("Documentos legales")
        assertThat(EtiquetasExport.estado("facturado")).isEqualTo("Facturado")
        assertThat(EtiquetasExport.estado("cerrado")).isEqualTo("Cerrado")
    }

    @Test
    fun `un estado desconocido se exporta crudo en vez de romper el archivo`() {
        // Si algun dia el enum gana un valor, el export sigue generandose:
        // una celda con el valor sin traducir es infinitamente mejor que un 500.
        assertThat(EtiquetasExport.estado("estado_nuevo")).isEqualTo("estado_nuevo")
    }

    @Test
    fun `un estado nulo se exporta como guion`() {
        assertThat(EtiquetasExport.estado(null)).isEqualTo("-")
    }

    // ── Origen del lead ────────────────────────────────────────

    @Test
    fun `los cinco origenes de lead tienen etiqueta de negocio`() {
        assertThat(EtiquetasExport.origen("cartera")).isEqualTo("Cartera propia")
        assertThat(EtiquetasExport.origen("visita_fria")).isEqualTo("Prospección en calle")
        assertThat(EtiquetasExport.origen("referido_calidda")).isEqualTo("Referido Cálidda")
        assertThat(EtiquetasExport.origen("red_contactos")).isEqualTo("Referido / red de contactos")
        assertThat(EtiquetasExport.origen("otro")).isEqualTo("Otro")
    }

    @Test
    fun `un origen nulo se exporta como guion`() {
        assertThat(EtiquetasExport.origen(null)).isEqualTo("-")
    }

    // ── Segmentos: multivalor ──────────────────────────────────

    @Test
    fun `un segmento unico se traduce a su etiqueta`() {
        assertThat(EtiquetasExport.segmento("urbano")).isEqualTo("Transporte urbano")
    }

    @Test
    fun `varios segmentos se traducen uno a uno conservando el separador`() {
        assertThat(EtiquetasExport.segmento("urbano, turismo")).isEqualTo("Transporte urbano, Turismo")
    }

    @Test
    fun `una empresa sin segmentos se exporta como guion`() {
        assertThat(EtiquetasExport.segmento(null)).isEqualTo("-")
    }

    // ── Estado de cartera ──────────────────────────────────────

    @Test
    fun `los seis estados de cartera tienen etiqueta de negocio`() {
        assertThat(EtiquetasExport.cartera("no_contactado")).isEqualTo("No contactado")
        assertThat(EtiquetasExport.cartera("no_aplica")).isEqualTo("No aplica")
        assertThat(EtiquetasExport.cartera("no_interesado")).isEqualTo("No interesado")
        assertThat(EtiquetasExport.cartera("prospeccion")).isEqualTo("Prospección")
        assertThat(EtiquetasExport.cartera("oportunidad_activa")).isEqualTo("Oportunidad activa")
        assertThat(EtiquetasExport.cartera("cliente")).isEqualTo("Cliente")
    }

    @Test
    fun `un estado de cartera nulo se exporta como guion`() {
        assertThat(EtiquetasExport.cartera(null)).isEqualTo("-")
    }
}
