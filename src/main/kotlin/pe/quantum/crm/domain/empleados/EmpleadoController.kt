package pe.quantum.crm.domain.empleados

import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import pe.quantum.crm.domain.empleados.dto.ActualizarEmpleadoRequest
import pe.quantum.crm.domain.empleados.dto.CambiarActivoRequest
import pe.quantum.crm.domain.empleados.dto.CrearEmpleadoRequest
import pe.quantum.crm.domain.empleados.dto.EmpleadoDto
import pe.quantum.crm.domain.empleados.dto.PerfilPropioDto
import pe.quantum.crm.domain.empleados.dto.toPerfilPropio
import pe.quantum.crm.shared.ApiResponse

/** Endpoints de empleados (contrato_api.md §7). */
@RestController
@RequestMapping("/api/v1/empleados")
class EmpleadoController(
    private val empleadoService: EmpleadoService,
) {
    /**
     * Perfil del usuario autenticado. El id sale del JWT (principal del filtro).
     *
     * Devuelve [PerfilPropioDto], no [EmpleadoDto]: incluye ademas
     * `requiere_cambio_contrasena`, que el frontend necesita en cada restauracion
     * de sesion para re-forzar el cambio obligatorio tras recargar la pagina.
     */
    @GetMapping("/me")
    fun me(authentication: Authentication): ApiResponse<PerfilPropioDto> {
        val empleadoId = authentication.principal as Long
        return ApiResponse.ok(empleadoService.porId(empleadoId).toPerfilPropio())
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('admin', 'gerencia', 'jdv')")
    fun listar(
        @RequestParam(required = false, defaultValue = "true") activo: Boolean,
        @RequestParam(required = false) rol: RolEmpleado?,
    ): ApiResponse<List<EmpleadoDto>> = ApiResponse.ok(empleadoService.listar(activo, rol))

    // El `@PreAuthorize` de las tres operaciones siguientes se apoya en el claim
    // `rol` del JWT, que sobrevive hasta 1h a una desactivacion o degradacion. Por
    // eso ademas se arrastra el id del llamante: el servicio revalida contra la
    // base que ese admin siga vigente antes de tocar nada.

    @PostMapping
    @PreAuthorize("hasRole('admin')")
    @ResponseStatus(HttpStatus.CREATED)
    fun crear(
        @Valid @RequestBody request: CrearEmpleadoRequest,
        authentication: Authentication,
    ): ApiResponse<EmpleadoDto> = ApiResponse.ok(empleadoService.crear(request, authentication.principal as Long))

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('admin')")
    fun actualizar(
        @PathVariable id: Long,
        @Valid @RequestBody request: ActualizarEmpleadoRequest,
        authentication: Authentication,
    ): ApiResponse<EmpleadoDto> = ApiResponse.ok(empleadoService.actualizar(id, request, authentication.principal as Long))

    @PatchMapping("/{id}/activo")
    @PreAuthorize("hasRole('admin')")
    fun cambiarActivo(
        @PathVariable id: Long,
        @RequestBody request: CambiarActivoRequest,
        authentication: Authentication,
    ): ApiResponse<EmpleadoDto> = ApiResponse.ok(empleadoService.cambiarActivo(id, request.activo, authentication.principal as Long))
}
