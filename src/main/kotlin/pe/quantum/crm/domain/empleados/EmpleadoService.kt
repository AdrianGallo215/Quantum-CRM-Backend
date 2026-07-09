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

    /** Resumenes (id, nombres, apellidos) para DTOs compuestos de otros modulos. */
    fun resumenPorIds(ids: Collection<Long>): Map<Long, EmpleadoResumen>

    /** Empleados activos con rol admin, gerente o jdv (broadcast de notificaciones). */
    fun idsSupervisoresActivos(): List<Long>
}
