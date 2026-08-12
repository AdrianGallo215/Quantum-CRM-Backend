package pe.quantum.crm.domain.contactos

import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import pe.quantum.crm.domain.contactos.dto.ActualizarContactoRequest
import pe.quantum.crm.domain.contactos.dto.ActualizarVinculoRequest
import pe.quantum.crm.domain.contactos.dto.ContactoDetalleDto
import pe.quantum.crm.domain.contactos.dto.ContactoDto
import pe.quantum.crm.domain.contactos.dto.ContactoListaDto
import pe.quantum.crm.domain.contactos.dto.CrearContactoRequest
import pe.quantum.crm.domain.contactos.dto.VincularContactoRequest
import pe.quantum.crm.domain.contactos.dto.VinculoDto
import pe.quantum.crm.domain.oportunidades.OportunidadesDeContacto
import pe.quantum.crm.domain.tareas.TareaService
import pe.quantum.crm.shared.ApiResponse
import pe.quantum.crm.shared.security.UsuarioActualProvider

/** Endpoints de contactos (contrato_api.md §9). */
@RestController
@RequestMapping("/api/v1/contactos")
class ContactoController(
    private val contactoService: ContactoService,
    private val oportunidadesDeContacto: OportunidadesDeContacto,
    private val tareaService: TareaService,
    private val usuarioProvider: UsuarioActualProvider,
) {
    @GetMapping
    fun buscar(
        @RequestParam(required = false) q: String?,
        @RequestParam(name = "id_empresa", required = false) idEmpresa: Long?,
        @RequestParam(required = false) page: Int?,
        @RequestParam(name = "per_page", required = false) perPage: Int?,
    ): ApiResponse<List<ContactoListaDto>> {
        val usuario = usuarioProvider.actual()
        val resultado = contactoService.buscar(q, idEmpresa, usuario, page, perPage, null, null)
        val conteos = oportunidadesDeContacto.contarPorContactos(resultado.items.map { it.id }, usuario)
        val conConteo = resultado.items.map { it.copy(oportunidadesCount = conteos[it.id] ?: 0) }
        return ApiResponse.ok(conConteo, resultado.meta)
    }

    @GetMapping("/{id}")
    fun detalle(
        @PathVariable id: Long,
    ): ApiResponse<ContactoDetalleDto> {
        val usuario = usuarioProvider.actual()
        val contacto = contactoService.detalle(id)
        val completo =
            contacto.copy(
                oportunidades = oportunidadesDeContacto.listar(id, usuario),
                actividades = tareaService.actividadesPorContacto(id, usuario),
            )
        return ApiResponse.ok(completo)
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun crear(
        @Valid @RequestBody request: CrearContactoRequest,
    ): ApiResponse<ContactoDto> = ApiResponse.ok(contactoService.crear(request, usuarioProvider.actual()))

    @PutMapping("/{id}")
    fun actualizar(
        @PathVariable id: Long,
        @RequestBody request: ActualizarContactoRequest,
    ): ApiResponse<ContactoDto> = ApiResponse.ok(contactoService.actualizar(id, request, usuarioProvider.actual()))

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAnyRole('admin', 'gerencia', 'jdv')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun eliminar(
        @PathVariable id: Long,
    ) {
        contactoService.eliminar(id)
    }
}

/** Vinculacion contacto-empresa (contrato_api.md §9, rutas bajo /empresas). */
@RestController
@RequestMapping("/api/v1/empresas/{idEmpresa}/contactos")
class EmpresaContactoController(
    private val contactoService: ContactoService,
    private val usuarioProvider: UsuarioActualProvider,
) {
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun vincular(
        @PathVariable idEmpresa: Long,
        @RequestBody request: VincularContactoRequest,
    ): ApiResponse<VinculoDto> = ApiResponse.ok(contactoService.vincular(idEmpresa, request, usuarioProvider.actual()))

    @PutMapping("/{idContacto}")
    fun actualizarVinculo(
        @PathVariable idEmpresa: Long,
        @PathVariable idContacto: Long,
        @RequestBody request: ActualizarVinculoRequest,
    ): ApiResponse<VinculoDto> =
        ApiResponse.ok(
            contactoService.actualizarVinculo(idEmpresa, idContacto, request, usuarioProvider.actual()),
        )

    @DeleteMapping("/{idContacto}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun desvincular(
        @PathVariable idEmpresa: Long,
        @PathVariable idContacto: Long,
    ) {
        contactoService.desvincular(idEmpresa, idContacto, usuarioProvider.actual())
    }
}
