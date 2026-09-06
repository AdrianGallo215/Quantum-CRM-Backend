package pe.quantum.crm.domain.simulaciones

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import pe.quantum.crm.shared.enums.ModoSimulacion

/**
 * [NombreSimulacion] es una funcion pura (`reglas_simulaciones.md` §8.1): sin
 * mockk, sin Spring, los datos llegan por parametro.
 */
class NombreSimulacionTest {
    // region Ejemplos literales de §8.1

    @Test
    fun `ejemplo literal de §8_1 con empresa modelo y leasing`() {
        val nombre =
            NombreSimulacion.autogenerado(
                razonSocialEmpresa = "Transportes Lima SAC",
                codigoModelo = "MB-O500",
                modo = ModoSimulacion.leasing,
                correlativo = 2,
            )

        assertThat(nombre).isEqualTo("Transportes Lima SAC · MB-O500 · Leasing · #2")
    }

    @Test
    fun `ejemplo literal de §8_1 sin enlazar y credito directo`() {
        val nombre =
            NombreSimulacion.autogenerado(
                razonSocialEmpresa = null,
                codigoModelo = "MB-O500",
                modo = ModoSimulacion.credito_directo,
                correlativo = 1,
            )

        assertThat(nombre).isEqualTo("Sin enlazar · MB-O500 · Crédito Directo · #1")
    }

    // endregion

    // region {Empresa}: null o en blanco -> "Sin enlazar"

    @Test
    fun `razonSocialEmpresa null empieza por Sin enlazar`() {
        val nombre =
            NombreSimulacion.autogenerado(
                razonSocialEmpresa = null,
                codigoModelo = "MB-O500",
                modo = ModoSimulacion.leasing,
                correlativo = 1,
            )

        assertThat(nombre).startsWith(NombreSimulacion.SIN_ENLAZAR)
    }

    @Test
    fun `razonSocialEmpresa en blanco tambien es Sin enlazar`() {
        val nombre =
            NombreSimulacion.autogenerado(
                razonSocialEmpresa = "   ",
                codigoModelo = "MB-O500",
                modo = ModoSimulacion.leasing,
                correlativo = 1,
            )

        assertThat(nombre).startsWith(NombreSimulacion.SIN_ENLAZAR)
    }

    // endregion

    // region {Modelo}: null -> segmento omitido, sin separador doble

    @Test
    fun `codigoModelo null omite el segmento del modelo sin dejar separador doble`() {
        val nombre =
            NombreSimulacion.autogenerado(
                razonSocialEmpresa = "Transportes Lima SAC",
                codigoModelo = null,
                modo = ModoSimulacion.leasing,
                correlativo = 1,
            )

        assertThat(nombre).isEqualTo("Transportes Lima SAC · Leasing · #1")
        assertThat(nombre).doesNotContain("· ·")
    }

    // endregion

    // region {Modo}: etiqueta legible, no el valor del enum

    @Test
    fun `modo leasing produce la etiqueta Leasing`() {
        val nombre =
            NombreSimulacion.autogenerado(
                razonSocialEmpresa = "Transportes Lima SAC",
                codigoModelo = "MB-O500",
                modo = ModoSimulacion.leasing,
                correlativo = 1,
            )

        assertThat(nombre).contains("Leasing")
    }

    @Test
    fun `modo credito_directo produce la etiqueta Credito Directo`() {
        val nombre =
            NombreSimulacion.autogenerado(
                razonSocialEmpresa = "Transportes Lima SAC",
                codigoModelo = "MB-O500",
                modo = ModoSimulacion.credito_directo,
                correlativo = 1,
            )

        assertThat(nombre).contains("Crédito Directo")
    }

    // endregion

    // region {#n}: correlativo

    @Test
    fun `correlativo 12 termina en numeral 12`() {
        val nombre =
            NombreSimulacion.autogenerado(
                razonSocialEmpresa = "Transportes Lima SAC",
                codigoModelo = "MB-O500",
                modo = ModoSimulacion.leasing,
                correlativo = 12,
            )

        assertThat(nombre).endsWith("#12")
    }

    // endregion
}
