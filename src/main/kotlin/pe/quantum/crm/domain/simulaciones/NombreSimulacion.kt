package pe.quantum.crm.domain.simulaciones

import pe.quantum.crm.shared.enums.ModoSimulacion

/**
 * Nombre autogenerado de una simulacion (`reglas_simulaciones.md` §8.1).
 *
 * Funcion pura en un `object`, sin Spring ni JPA — mismo estilo que
 * [pe.quantum.crm.domain.oportunidades.MontoTotal].
 *
 * El nombre autogenerado NUNCA se persiste (restriccion 1 del encargo y §4 de
 * `reglas_simulaciones.md`): se compone al leer, cada vez. El nombre manual es
 * PEGAJOSO — si `simulaciones.nombre` tiene valor, ese manda y esta funcion no
 * se llama, ni siquiera al editar parametros o al enlazar a un item
 * (`plan-09-mapa-simulaciones-modulo.md`, decision D37).
 */
object NombreSimulacion {
    /** Lo que se muestra cuando la simulacion no esta enlazada a un item (§8.1). */
    const val SIN_ENLAZAR = "Sin enlazar"

    /** Separador entre segmentos del nombre (§8.1): espacio, U+00B7 MIDDLE DOT, espacio. */
    private const val SEPARADOR = " · "

    /**
     * Nombre autogenerado de §8.1: `{Empresa} · {Modelo} · {Modo} · #{n}`.
     *
     * - `{Empresa}`: [razonSocialEmpresa] si no es null ni en blanco; si no, [SIN_ENLAZAR].
     * - `{Modelo}`: [codigoModelo] si no es null ni en blanco; si no, se OMITE
     *   ese segmento entero junto con su separador (nunca queda `· ·` ni un
     *   hueco doble).
     * - `{Modo}`: etiqueta legible de [modo] (no el valor del enum).
     * - `#{n}`: `"#" + [correlativo]`.
     *
     * Ejemplos de §8.1:
     * ```
     * Transportes Lima SAC · MB-O500 · Leasing · #2
     * Sin enlazar · MB-O500 · Crédito Directo · #1
     * ```
     */
    fun autogenerado(
        razonSocialEmpresa: String?,
        codigoModelo: String?,
        modo: ModoSimulacion,
        correlativo: Int,
    ): String {
        val empresa = razonSocialEmpresa?.takeIf { it.isNotBlank() } ?: SIN_ENLAZAR
        val modelo = codigoModelo?.takeIf { it.isNotBlank() }

        return listOfNotNull(empresa, modelo, etiquetaModo(modo), "#$correlativo")
            .joinToString(SEPARADOR)
    }

    /** Etiqueta legible de [ModoSimulacion], nunca el valor crudo del enum (§8.1). */
    private fun etiquetaModo(modo: ModoSimulacion): String =
        when (modo) {
            ModoSimulacion.leasing -> "Leasing"
            ModoSimulacion.credito_directo -> "Crédito Directo"
        }
}
