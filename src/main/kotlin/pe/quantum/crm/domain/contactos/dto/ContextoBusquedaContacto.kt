package pe.quantum.crm.domain.contactos.dto

import pe.quantum.crm.shared.exception.ValidacionException
import pe.quantum.crm.shared.security.UsuarioActual

/**
 * Contexto de `GET /contactos` y `GET /contactos/:id` (contrato_api.md §9).
 *
 * El mismo endpoint sirve dos pantallas con reglas de visibilidad OPUESTAS para
 * los roles de apoyo (`analista`/`otro`), y hasta ahora nada en el request las
 * distinguia:
 *
 *  - `listado`  — vista de Contactos. Un rol de apoyo solo alcanza los contactos
 *    de las empresas donde colabora via tarea, y los ve completos.
 *  - `vincular` — buscador de "vincular contacto existente" a una empresa. Un rol
 *    de apoyo busca sobre TODO el CRM (si no, no podria vincular un contacto que
 *    todavia no conoce), pero la respuesta solo expone el nombre.
 *
 * Ausente => `listado`, que es el modo restrictivo: un cliente que todavia no
 * manda el parametro nunca abre la busqueda global por omision.
 */
@Suppress("ktlint:standard:enum-entry-name-case", "EnumNaming", "EnumEntryName")
enum class ContextoBusquedaContacto {
    listado,
    vincular,
    ;

    /**
     * true si a este usuario, en este contexto, solo se le expone el nombre del
     * contacto — sin telefonos, correos, notas ni empresas — y la busqueda NO se
     * restringe a su alcance de colaboracion.
     */
    fun esReducidoPara(usuario: UsuarioActual): Boolean = this == vincular && usuario.esRolApoyo

    /**
     * true si el resultado debe restringirse a los contactos que este usuario
     * alcanza por colaboracion (matriz_permisos.md §1).
     */
    fun aplicaFiltroDeVisibilidadPara(usuario: UsuarioActual): Boolean = this == listado && usuario.esRolApoyo

    companion object {
        /**
         * `?contexto=` fuera del enum es un error del cliente (400), no un valor
         * que se ignora: mismo criterio que `?estado_cartera=` en empresas.
         * Ausente, vacio o en blanco cae en `listado`.
         */
        fun desde(valor: String?): ContextoBusquedaContacto {
            val pedido = valor?.trim()?.takeIf { it.isNotEmpty() } ?: return listado
            return entries.firstOrNull { it.name == pedido }
                ?: throw ValidacionException(
                    "El contexto '$pedido' no es válido. Contextos permitidos: " +
                        entries.joinToString(", ") { it.name },
                    field = "contexto",
                )
        }
    }
}
