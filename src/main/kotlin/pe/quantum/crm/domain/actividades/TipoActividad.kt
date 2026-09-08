package pe.quantum.crm.domain.actividades

/**
 * Discriminador de actividad en la API del modulo. Es un enum de Kotlin puro,
 * NO un tipo de Postgres: en base, el origen de un comentario o de una entrada
 * de auditoria se guarda como dos FK nullable + CHECK (ver V48/V49), no como
 * una columna de tipo.
 *
 * Es API publica del modulo (CLAUDE.md regla 12): `tareas` y `eventos` lo
 * consumen al registrar auditoria.
 */
enum class TipoActividad {
    tarea,
    evento,
}
