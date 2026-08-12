package pe.quantum.crm.shared

/**
 * Convierte el nombre de propiedad Kotlin de un `field` de error a snake_case, el
 * formato en que el frontend envio el campo (contrato_api.md §3).
 *
 * `fieldError.field` (Spring) y `propertyPath` (Jakarta Validation) devuelven el
 * nombre de la propiedad tal cual, sin pasar por la estrategia `SNAKE_CASE` de
 * Jackson: un error sobre `idContacto` volvia como `"idContacto"`, no
 * `"id_contacto"`, y el frontend no podia casarlo con el campo que envio.
 *
 * Convierte segmento a segmento (separados por `.`) para no romper los indices de
 * array de un campo anidado: `contactos[0].idContacto` -> `contactos[0].id_contacto`.
 */
fun String.aCampoSnakeCase(): String =
    split(".").joinToString(".") { segmento ->
        segmento.replace(Regex("([a-z0-9])([A-Z])"), "$1_$2").lowercase()
    }
