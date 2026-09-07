package pe.quantum.crm.domain.oportunidades

import pe.quantum.crm.domain.oportunidades.dto.OportunidadItemDatos
import pe.quantum.crm.domain.oportunidades.dto.OportunidadItemDto
import pe.quantum.crm.domain.simulaciones.SimulacionService
import pe.quantum.crm.shared.simulacion.AritmeticaFinanciera
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Agregacion de la cuota de §6.2 de `reglas_simulaciones.md`, como funciones
 * puras sobre los items ya cargados — mismo papel y mismo estilo que
 * [MontoTotal], y por el mismo motivo: `OportunidadServiceImpl` solo orquesta,
 * el calculo se lee (y se prueba) aparte.
 *
 * Nada de esto se persiste: los cinco campos que produce se calculan al vuelo
 * en cada lectura del DTO.
 *
 * La cuota Quantum de cada item NO se calcula aqui: la resuelve `simulaciones`
 * (`SimulacionService.cuotaQuantumPorItems`, D56) y llega ya hecha en el mapa
 * `cuotasPorItem`. Un item ausente de ese mapa es un item sin cuota que mostrar
 * — incompleto, o con parametros por defecto invalidos para su precio (D55) —,
 * nunca un error.
 */
object CuotaOportunidad {
    /**
     * §6.2 por item: `cuotaQuantum` es lo que resolvio `simulaciones` (la
     * `cuota_final` de su simulacion principal, o el estimado de §6.1), y
     * `cuotaTotal` le suma la `cuota_financiadora` del propio item. Los dos
     * quedan en `null` cuando el item no tiene cuota calculable.
     */
    fun porItem(
        items: List<OportunidadItemDto>,
        datos: Map<Long, OportunidadItemDatos>,
        cuotasPorItem: Map<Long, BigDecimal>,
    ): List<OportunidadItemDto> =
        items.map { item ->
            val cuotaQuantum = cuotasPorItem[item.id]
            val cuotaFinanciadora = datos[item.id]?.cuotaFinanciadora
            item.copy(
                cuotaQuantum = cuotaQuantum?.comoImporte(),
                cuotaTotal =
                    if (cuotaQuantum == null || cuotaFinanciadora == null) {
                        null
                    } else {
                        cuotaQuantum.add(cuotaFinanciadora, AritmeticaFinanciera.MC).comoImporte()
                    },
            )
        }

    /**
     * Los tres totales de §6.2 de la oportunidad, o `null` los TRES a la vez
     * (D62): basta con que UN item no aporte su cuota —por `cuotaQuantum` nulo o
     * por `cantidad` nula— para que la oportunidad no publique ninguno. Una
     * oportunidad sin items (imposible desde D17, pero el mapa podria venir
     * vacio) tambien da `null`.
     *
     * Es deliberadamente distinto del criterio de [MontoTotal.sumarItems], donde
     * un item incompleto aporta 0: un `monto_total` parcial se lee como "van 2
     * de 3 buses cargados", pero una cuota parcial se lee como "esto es lo que
     * paga al mes", y omitir un bus en silencio subestima el pago. Mejor no
     * mostrar nada que mostrar de menos.
     *
     * Aritmetica intermedia con [AritmeticaFinanciera.MC]; el redondeo a 2
     * decimales solo al exponer.
     */
    fun totales(
        items: List<OportunidadItemDatos>,
        cuotasPorItem: Map<Long, BigDecimal>,
    ): TotalesCuota? {
        val mc = AritmeticaFinanciera.MC
        val aportes =
            items.mapNotNull { item ->
                val cuotaQuantum = cuotasPorItem[item.id] ?: return@mapNotNull null
                val unidades = item.cantidad?.let { BigDecimal(it) } ?: return@mapNotNull null
                cuotaQuantum.multiply(unidades, mc) to
                    cuotaQuantum.add(item.cuotaFinanciadora, mc).multiply(unidades, mc)
            }
        if (items.isEmpty() || aportes.size != items.size) {
            return null
        }
        val quantum = aportes.fold(BigDecimal.ZERO) { acumulado, aporte -> acumulado.add(aporte.first, mc) }
        val total = aportes.fold(BigDecimal.ZERO) { acumulado, aporte -> acumulado.add(aporte.second, mc) }
        return TotalesCuota(
            cuotaQuantumTotal = quantum.comoImporte(),
            cuotaTotal = total.comoImporte(),
            // El divisor es la constante 22 de `simulaciones`, expuesta por su
            // interfaz de servicio (K34): el `object` de defaults donde vive de
            // verdad es interno de ese modulo y no cruza la frontera.
            cuotaDiariaTotal = total.divide(BigDecimal(SimulacionService.DIAS_TRABAJADOS_POR_DEFECTO), mc).comoImporte(),
        )
    }

    /** Convencion de dinero del repo: escala 2 HALF_UP y `toPlainString()` al exponer. */
    private fun BigDecimal.comoImporte(): String = setScale(2, RoundingMode.HALF_UP).toPlainString()
}

/** Los tres totales de §6.2 viajan juntos porque juntos son `null` (D62). */
data class TotalesCuota(
    val cuotaQuantumTotal: String,
    val cuotaTotal: String,
    val cuotaDiariaTotal: String,
)
