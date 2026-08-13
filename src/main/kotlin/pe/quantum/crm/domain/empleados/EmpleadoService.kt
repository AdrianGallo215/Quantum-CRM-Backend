package pe.quantum.crm.domain.empleados

import pe.quantum.crm.domain.empleados.dto.ActualizarEmpleadoRequest
import pe.quantum.crm.domain.empleados.dto.CrearEmpleadoRequest
import pe.quantum.crm.domain.empleados.dto.EmpleadoDto
import pe.quantum.crm.domain.empleados.dto.EmpleadoResumen

/**
 * Interfaz publica del modulo empleados. Otros modulos y los controllers usan esta
 * interfaz, nunca el repository ni la entidad directamente (regla del monolito
 * modular, CLAUDE.md §12).
 */
@Suppress("TooManyFunctions") // Autenticacion, CRUD y los resumenes que consumen los demas modulos.
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

    /** Lista para selectores de asignacion (contrato_api.md §7). */
    fun listar(
        activo: Boolean,
        rol: RolEmpleado?,
    ): List<EmpleadoDto>

    /**
     * Crea un empleado; nace con `requiere_cambio_contrasena = true` (B1.4).
     *
     * `idSolicitante` es el id del admin que pide la operacion, tomado del JWT. Las
     * tres operaciones de administracion de empleados lo exigen porque revalidan
     * contra la base que ese admin siga vigente: el `@PreAuthorize` del controller
     * confia en el claim `rol` del token, que sobrevive hasta 1h a una desactivacion
     * o degradacion. Sin esta revalidacion, una cuenta ya revocada podia deshacer su
     * propia revocacion (o crearse otro admin) mientras el token siguiera vivo.
     */
    fun crear(
        request: CrearEmpleadoRequest,
        idSolicitante: Long,
    ): EmpleadoDto

    /** Nadie puede cambiar su propio rol, ni siquiera un admin vigente. */
    fun actualizar(
        id: Long,
        request: ActualizarEmpleadoRequest,
        idSolicitante: Long,
    ): EmpleadoDto

    /**
     * Activa/desactiva. No permite dejar el sistema sin admin activo, ni que nadie
     * cambie su propio estado de activacion.
     */
    fun cambiarActivo(
        id: Long,
        activo: Boolean,
        idSolicitante: Long,
    ): EmpleadoDto

    /** Verifica que el empleado exista y este activo (para asignaciones). */
    fun existeActivo(id: Long): Boolean

    /** true si el empleado esta activo y su rol puede tener cartera (vendedor o jdv). */
    fun esAsignableComoVendedor(id: Long): Boolean

    /** Ids de empleados activos con el rol dado (destinatarios de notificaciones). */
    fun idsActivosPorRol(rol: RolEmpleado): List<Long>

    /** Resumenes (id, nombres, apellidos) para DTOs compuestos de otros modulos. */
    fun resumenPorIds(ids: Collection<Long>): Map<Long, EmpleadoResumen>

    /** Empleados activos con rol admin, gerencia o jdv (broadcast de notificaciones). */
    fun idsSupervisoresActivos(): List<Long>

    /**
     * Cambia la contraseña del propio empleado y apaga `requiere_cambio_contrasena`
     * (B1.4). Exige la contraseña actual: sin ella, una sesion robada podria
     * apoderarse de la cuenta de forma permanente.
     */
    fun cambiarContrasena(
        idEmpleado: Long,
        actual: String,
        nueva: String,
    )

    /**
     * Invalida cualquier refresh token vigente del empleado (logout, B0.9):
     * incrementa `token_version`, que `/auth/refresh` compara contra la del
     * token. No-op silencioso si el empleado ya no existe — logout debe poder
     * responder 204 siempre, nunca fallar.
     */
    fun revocarSesiones(id: Long)
}
