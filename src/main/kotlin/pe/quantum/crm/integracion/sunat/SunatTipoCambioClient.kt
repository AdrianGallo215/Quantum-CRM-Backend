package pe.quantum.crm.integracion.sunat

/**
 * Acceso al tipo de cambio publicado por SUNAT a traves del proveedor externo.
 *
 * El contrato es deliberadamente tolerante a fallos: la integracion nunca puede
 * tumbar al que la llama (reglas_simulaciones.md §12 exige fallback sin error
 * visible), asi que no lanza excepciones.
 */
interface SunatTipoCambioClient {
    /** Consulta el tipo de cambio publicado. Devuelve null si no hay respuesta utilizable. */
    fun consultar(): TipoCambioExterno?
}
