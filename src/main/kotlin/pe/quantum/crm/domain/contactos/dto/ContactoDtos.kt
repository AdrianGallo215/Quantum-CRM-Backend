package pe.quantum.crm.domain.contactos.dto

import jakarta.validation.constraints.NotBlank
import pe.quantum.crm.domain.contactos.Contacto
import pe.quantum.crm.domain.oportunidades.dto.OportunidadResumenParaContacto
import pe.quantum.crm.domain.tareas.dto.ActividadContactoDto

/** Empresa vinculada dentro de la busqueda de contactos. */
data class EmpresaDeContactoDto(
    val id: Long,
    val razonSocial: String,
    val cargo: String?,
)

/** Contacto en respuestas de busqueda/creacion (contrato_api.md §9). */
data class ContactoDto(
    val id: Long,
    val nombres: String,
    val apellidos: String,
    val email_1: String?,
    val email_2: String?,
    val tlf_1: String?,
    val tlf_2: String?,
    val notas: String?,
    val empresas: List<EmpresaDeContactoDto>,
)

/** Fila del listado paginado de contactos (contrato_api.md §9). */
data class ContactoListaDto(
    val id: Long,
    val nombres: String,
    val apellidos: String,
    val email_1: String?,
    val email_2: String?,
    val tlf_1: String?,
    val tlf_2: String?,
    val notas: String?,
    val empresas: List<EmpresaDeContactoDto>,
    val oportunidadesCount: Int = 0,
)

/** Empresa vinculada en el detalle de contacto, con datos completos de la relacion. */
data class EmpresaDeContactoDetalleDto(
    val id: Long,
    val razonSocial: String,
    val cargo: String?,
    val tomaDecision: Boolean?,
    val esPrincipal: Boolean,
    val segmentos: List<String>,
)

/** Detalle de contacto (contrato_api.md §9): empresas, oportunidades y actividades (solo tareas por ahora). */
data class ContactoDetalleDto(
    val id: Long,
    val nombres: String,
    val apellidos: String,
    val email_1: String?,
    val email_2: String?,
    val tlf_1: String?,
    val tlf_2: String?,
    val notas: String?,
    val empresas: List<EmpresaDeContactoDetalleDto>,
    val oportunidades: List<OportunidadResumenParaContacto> = emptyList(),
    val actividades: List<ActividadContactoDto> = emptyList(),
)

/** Body de `POST /contactos`: crea el contacto y lo vincula a una empresa. */
data class CrearContactoRequest(
    @field:NotBlank
    val nombres: String,
    @field:NotBlank
    val apellidos: String,
    val email_1: String? = null,
    val email_2: String? = null,
    val tlf_1: String? = null,
    val tlf_2: String? = null,
    val notas: String? = null,
    val idEmpresa: Long,
    val cargo: String? = null,
    val tomaDecision: Boolean? = null,
    val esPrincipal: Boolean = false,
)

/** Body de `PUT /contactos/:id`: solo datos propios del contacto. */
data class ActualizarContactoRequest(
    val nombres: String? = null,
    val apellidos: String? = null,
    val email_1: String? = null,
    val email_2: String? = null,
    val tlf_1: String? = null,
    val tlf_2: String? = null,
    val notas: String? = null,
)

/** Body de `POST /empresas/:id/contactos`: vincula un contacto existente. */
data class VincularContactoRequest(
    val idContacto: Long,
    val cargo: String? = null,
    val tomaDecision: Boolean? = null,
    val esPrincipal: Boolean = false,
)

/** Body de `PUT /empresas/:id/contactos/:contacto_id`. */
data class ActualizarVinculoRequest(
    val cargo: String? = null,
    val tomaDecision: Boolean? = null,
    val esPrincipal: Boolean? = null,
)

/** Vinculacion expuesta en respuestas. */
data class VinculoDto(
    val idEmpresa: Long,
    val idContacto: Long,
    val cargo: String?,
    val tomaDecision: Boolean?,
    val esPrincipal: Boolean,
)

/** Resumen para otros modulos (tareas, oportunidades). */
data class ContactoResumen(
    val id: Long,
    val nombres: String,
    val apellidos: String,
    val tlf_1: String? = null,
)

fun Contacto.toResumen(): ContactoResumen =
    ContactoResumen(id = requireNotNull(id), nombres = nombres, apellidos = apellidos, tlf_1 = tlf_1)
