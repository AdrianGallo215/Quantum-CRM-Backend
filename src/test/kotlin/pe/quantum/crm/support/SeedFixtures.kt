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

    /** Total de migraciones aplicadas (V1..V19). */
    const val MIGRACIONES_TOTAL = 19
}
