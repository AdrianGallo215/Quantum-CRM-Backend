package pe.quantum.crm.support

/**
 * Constantes de los datos sembrados por las migraciones de seed (V17/V18/V19),
 * para que los tests de integracion referencien valores conocidos sin duplicar
 * literales.
 *
 * NOTA: la "factory de entidades de prueba" que menciona B0.6 se agregara cuando
 * existan las entidades JPA (a partir de B0.7). Hoy no hay entidades que fabricar;
 * estos fixtures son los datos de seed que ya viven en la base.
 */
object SeedFixtures {
    /** Financiadora default sembrada en V17. */
    const val CALIDDA_NOMBRE = "Calidda – Fraccionamiento GNV"
    const val CALIDDA_CUOTA_POR_UNIDAD = "937.50"

    /** Catalogo de eventos sembrado en V18. */
    const val CATALOGO_EVENTOS_TOTAL = 10
    const val CATALOGO_HITOS_PROSPECCION = 3
    const val CATALOGO_DISPARAN_CAMBIO_ESTADO = 3

    /** Empleado admin inicial sembrado en V19. */
    const val ADMIN_EMAIL = "admin@quantum.pe"
    const val ADMIN_ROL = "admin"

    /**
     * Cuenta real de archivos de migración commiteados. Actualizar al agregar migraciones.
     *
     * NO coincide con [MIGRACION_VERSION_MAX] porque la numeración tiene un hueco: no
     * existe una V40. La migración de simulaciones nació como V40, pero se renumeró a
     * V43 cuando V41 (tipo de cambio) ya estaba aplicada en producción y `oportunidad_items`
     * —de la que V40 depende por FK— todavía no existía; Flyway corre con
     * `out-of-order = false`, así que una versión menor que la máxima ya aplicada no
     * puede entrar nunca. Ver docs/planes/plan-04-fundacion-items.md, tarea O6.
     *
     * V46 (drop de columnas planas de `oportunidades`) es correlativa a V45: no reabre
     * el hueco ni agrega uno nuevo. El hueco de V40 sigue vigente para siempre (es
     * historia ya aplicada en producción), así que este desfase de 1 entre archivos y
     * versión máxima tampoco desaparece con V46: pasa de 44/45 a 45/46.
     */
    const val MIGRACIONES_TOTAL = 45

    /**
     * Número de versión de la última migración. Distinto de [MIGRACIONES_TOTAL] por el
     * hueco de V40 descrito arriba; separarlos es deliberado, porque son dos hechos
     * distintos que coincidían por casualidad mientras la numeración fue correlativa.
     */
    const val MIGRACION_VERSION_MAX = 46
}
