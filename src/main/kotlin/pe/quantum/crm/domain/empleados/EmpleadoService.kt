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

    /** Crea un empleado; nace con `requiere_cambio_contrasena = true` (B1.4). */
    fun crear(request: CrearEmpleadoRequest): EmpleadoDto

    fun actualizar(
        id: Long,
        request: ActualizarEmpleadoRequest,
    ): EmpleadoDto

    /** Activa/desactiva. No permite dejar el sistema sin admin activo. */
    fun cambiarActivo(
        id: Long,
        activo: Boolean,
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
}
