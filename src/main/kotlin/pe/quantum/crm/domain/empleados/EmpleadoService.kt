package pe.quantum.crm.domain.empleados

/**
 * Interfaz publica del modulo empleados. Otros modulos y los controllers usan esta
 * interfaz, nunca el repository ni la entidad directamente (regla del monolito
 * modular, CLAUDE.md §12).
 */
interface EmpleadoService {
    /**
     * Valida email + contraseña. Devuelve el empleado si son correctos; lanza
     * `CredencialesInvalidasException` (generica) ante cualquier falla.
     */
    fun autenticar(
        email: String,
        passwordPlano: String,
    ): Empleado

    /** Empleado por id, o `NoEncontradoException`. */
    fun porId(id: Long): Empleado
}
