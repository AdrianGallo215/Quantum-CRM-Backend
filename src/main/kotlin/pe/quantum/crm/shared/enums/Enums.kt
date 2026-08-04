package pe.quantum.crm.shared.enums

/**
 * Enums globales del dominio. Los nombres estan en minuscula a proposito: deben
 * coincidir exactamente con las etiquetas de los enums nativos de PostgreSQL
 * (migracion V1), que Hibernate mapea por nombre via `@JdbcTypeCode(NAMED_ENUM)`.
 */
@Suppress("ktlint:standard:enum-entry-name-case", "EnumNaming", "EnumEntryName")
enum class EstadoOportunidad {
    evaluacion_calidda,
    documentos_legales,
    facturado,
    cerrado,
    ;

    /** Posicion en la secuencia positiva del pipeline; `cerrado` esta fuera de ella. */
    val rango: Int
        get() =
            when (this) {
                evaluacion_calidda -> 0
                documentos_legales -> 1
                facturado -> 2
                cerrado -> 3
            }
}

@Suppress("ktlint:standard:enum-entry-name-case", "EnumNaming", "EnumEntryName")
enum class EstadoCartera {
    no_contactado,
    no_aplica,
    no_interesado,
    prospeccion,
    oportunidad_activa,
    cliente,
    ;

    /** Manual = lo establece el usuario. Derivado = solo `actualizarEstadoCartera`. */
    val esManual: Boolean
        get() = this != oportunidad_activa && this != cliente
}

@Suppress("ktlint:standard:enum-entry-name-case", "EnumNaming", "EnumEntryName")
enum class Segmento {
    urbano,
    personal,
    turismo,
    interprovincial,
    otro,
}

@Suppress("ktlint:standard:enum-entry-name-case", "EnumNaming", "EnumEntryName")
enum class Aplicacion {
    urbano,
    interprovincial,
    turismo,
    personal,
}

@Suppress("ktlint:standard:enum-entry-name-case", "EnumNaming", "EnumEntryName")
enum class OrigenLead {
    cartera,
    visita_fria,
    referido_calidda,
    red_contactos,
    otro,
}

@Suppress("ktlint:standard:enum-entry-name-case", "EnumNaming", "EnumEntryName")
enum class EstadoEvento {
    pendiente,
    ocurrido,
    descartado,
}

@Suppress("ktlint:standard:enum-entry-name-case", "EnumNaming", "EnumEntryName")
enum class TipoAccion {
    llamada,
    correo,
    reunion,
    whatsapp,
    otro,
}

@Suppress("ktlint:standard:enum-entry-name-case", "EnumNaming", "EnumEntryName")
enum class EstadoAccion {
    pendiente,
    completada,
    cancelada,
}
