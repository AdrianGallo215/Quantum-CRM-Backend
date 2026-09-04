package pe.quantum.crm.domain.oportunidades

import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import pe.quantum.crm.domain.oportunidades.dto.ActualizarOportunidadItemRequest
import pe.quantum.crm.domain.oportunidades.dto.CrearOportunidadItemRequest
import pe.quantum.crm.domain.oportunidades.dto.OportunidadItemDto
import pe.quantum.crm.shared.ApiResponse
import pe.quantum.crm.shared.security.UsuarioActualProvider

/**
 * Endpoints de los items de una oportunidad (sub-recurso de `/oportunidades`,
 * plan-06-migrar-dominio-items.md, B4). Mismo patron que
 * `OportunidadController` para `/contactos`.
 */
@RestController
@RequestMapping("/api/v1/oportunidades/{id}/items")
class OportunidadItemController(
    private val oportunidadItemService: OportunidadItemService,
    private val usuarioProvider: UsuarioActualProvider,
) {
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun crear(
        @PathVariable id: Long,
        @Valid @RequestBody request: CrearOportunidadItemRequest,
    ): ApiResponse<OportunidadItemDto> = ApiResponse.ok(oportunidadItemService.crear(id, request, usuarioProvider.actual()))

    @PutMapping("/{itemId}")
    // id es de la oportunidad: la URL lo necesita para ser un sub-recurso coherente,
    // el Service resuelve todo desde itemId.
    @Suppress("UnusedParameter")
    fun actualizar(
        @PathVariable id: Long,
        @PathVariable itemId: Long,
        @Valid @RequestBody request: ActualizarOportunidadItemRequest,
    ): ApiResponse<OportunidadItemDto> =
        ApiResponse.ok(oportunidadItemService.actualizar(itemId, request, usuarioProvider.actual()))

    @DeleteMapping("/{itemId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    // id es de la oportunidad: la URL lo necesita para ser un sub-recurso coherente,
    // el Service resuelve todo desde itemId.
    @Suppress("UnusedParameter")
    fun eliminar(
        @PathVariable id: Long,
        @PathVariable itemId: Long,
    ) {
        oportunidadItemService.eliminar(itemId, usuarioProvider.actual())
    }
}
