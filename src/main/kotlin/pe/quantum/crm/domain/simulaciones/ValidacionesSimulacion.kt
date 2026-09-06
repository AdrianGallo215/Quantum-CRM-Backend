package pe.quantum.crm.domain.simulaciones

import pe.quantum.crm.shared.exception.ValidacionException
import pe.quantum.crm.shared.simulacion.AritmeticaFinanciera
import pe.quantum.crm.shared.simulacion.ResultadoSimulacion
import java.math.BigDecimal

/**
 * Validaciones de negocio §13 de reglas_simulaciones.md, compartidas entre
 * `SimulacionServiceImpl` (que persiste) y `CalculadoraFinancieraServiceImpl`
 * (que no persiste, pero corre las mismas reglas sobre el mismo motor).
 * Funciones puras: reciben los `BigDecimal` y lanzan `ValidacionException`.
 */
object ValidacionesSimulacion {
    /** Base porcentual del descuento en `PV_efectivo` (§3.2). */
    private val CIEN: BigDecimal = BigDecimal("100")

    /**
     * §13: `cuota_inicial < PV_efectivo`, con
     * `PV_efectivo = precio_venta x (1 - descuento/100)`. Vive en el Service
     * porque requiere la formula del descuento, que la BD no conoce.
     */
    fun exigirCuotaInicialMenorQuePrecioEfectivo(
        precioVenta: BigDecimal,
        descuento: BigDecimal,
        cuotaInicial: BigDecimal,
    ) {
        val mc = AritmeticaFinanciera.MC
        val precioEfectivo = precioVenta.multiply(BigDecimal.ONE.subtract(descuento.divide(CIEN, mc)), mc)
        if (cuotaInicial >= precioEfectivo) {
            throw ValidacionException(
                "La cuota inicial (${cuotaInicial.toPlainString()}) debe ser menor al precio de venta con el " +
                    "descuento aplicado (${precioEfectivo.toPlainString()})",
                field = "cuota_inicial",
            )
        }
    }

    /**
     * §13: `valor_residual < Principal`. El `Principal` sale del motor y NO se
     * recalcula aqui: su formula depende del modo y ya vive alli (D35).
     */
    fun exigirValorResidualMenorQuePrincipal(
        valorResidual: BigDecimal,
        resultado: ResultadoSimulacion,
    ) {
        if (valorResidual >= resultado.principal) {
            throw ValidacionException(
                "El valor residual (${valorResidual.toPlainString()}) debe ser menor al monto financiado " +
                    "(${resultado.principal.toPlainString()})",
                field = "valor_residual",
            )
        }
    }
}
