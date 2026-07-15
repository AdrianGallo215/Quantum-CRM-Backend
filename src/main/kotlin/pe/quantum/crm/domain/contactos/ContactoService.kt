package pe.quantum.crm.domain.contactos

import pe.quantum.crm.domain.contactos.dto.ActualizarContactoRequest
import pe.quantum.crm.domain.contactos.dto.ActualizarVinculoRequest
import pe.quantum.crm.domain.contactos.dto.ContactoDetalleDto
import pe.quantum.crm.domain.contactos.dto.ContactoDto
import pe.quantum.crm.domain.contactos.dto.ContactoListaDto
import pe.quantum.crm.domain.contactos.dto.ContactoResumen
import pe.quantum.crm.domain.contactos.dto.CrearContactoRequest
import pe.quantum.crm.domain.contactos.dto.VincularContactoRequest
import pe.quantum.crm.domain.contactos.dto.VinculoDto
import pe.quantum.crm.domain.empresas.dto.ContactoDeEmpresaDto
import pe.quantum.crm.shared.Paginado
import pe.quantum.crm.shared.security.UsuarioActual

/** Interfaz publica del modulo contactos. */
interface ContactoService {
    fun buscar(
        q: String?,
        idEmpresa: Long?,
        usuario: UsuarioActual,
        page: Int?,
        perPage: Int?,
        sort: String?,
        dir: String?,
    ): Paginado<ContactoListaDto>

    /** Contacto + vinculacion a empresa en una sola transaccion (§9). */
    fun crear(
        request: CrearContactoRequest,
        usuario: UsuarioActual,
    ): ContactoDto

    fun actualizar(
        id: Long,
        request: ActualizarContactoRequest,
        usuario: UsuarioActual,
    ): ContactoDto

    /** Elimina solo si no esta vinculado a ninguna empresa (reglas §11.2). */
    fun eliminar(id: Long)

    /** Detalle del contacto: empresas con segmentos. `oportunidades`/`actividades` los completa el controller. */
    fun detalle(id: Long): ContactoDetalleDto

    fun vincular(
        idEmpresa: Long,
        request: VincularContactoRequest,
        usuario: UsuarioActual,
    ): VinculoDto

    fun actualizarVinculo(
        idEmpresa: Long,
        idContacto: Long,
        request: ActualizarVinculoRequest,
        usuario: UsuarioActual,
    ): VinculoDto

    fun desvincular(
        idEmpresa: Long,
        idContacto: Long,
        usuario: UsuarioActual,
    )

    /** Contactos de una empresa con su cargo (para el detalle de empresa). */
    fun contactosDeEmpresa(idEmpresa: Long): List<ContactoDeEmpresaDto>

    fun countPorEmpresa(idEmpresa: Long): Int

    /** Verifica existencia (para oportunidades y tareas). */
    fun existe(id: Long): Boolean

    fun resumenPorIds(ids: Collection<Long>): Map<Long, ContactoResumen>
}
