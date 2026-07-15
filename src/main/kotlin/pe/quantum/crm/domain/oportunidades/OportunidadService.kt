package pe.quantum.crm.domain.oportunidades

import pe.quantum.crm.domain.oportunidades.dto.ActualizarOportunidadRequest
import pe.quantum.crm.domain.oportunidades.dto.CambiarEstadoRequest
import pe.quantum.crm.domain.oportunidades.dto.CambioEstadoDto
import pe.quantum.crm.domain.oportunidades.dto.ContactoVinculoRequest
import pe.quantum.crm.domain.oportunidades.dto.CrearOportunidadRequest
import pe.quantum.crm.domain.oportunidades.dto.LogEstadoDto
import pe.quantum.crm.domain.oportunidades.dto.OportunidadDto
import pe.quantum.crm.domain.oportunidades.dto.OportunidadFiltros
import pe.quantum.crm.domain.oportunidades.dto.OportunidadRecordatorioDatos
import pe.quantum.crm.domain.oportunidades.dto.OportunidadVinculo
import pe.quantum.crm.shared.Paginado
import pe.quantum.crm.shared.security.UsuarioActual

/** Interfaz publica del modulo oportunidades. */
interface OportunidadService {
    fun crear(
        request: CrearOportunidadRequest,
        usuario: UsuarioActual,
    ): OportunidadDto

    fun actualizar(
        id: Long,
        request: ActualizarOportunidadRequest,
        usuario: UsuarioActual,
    ): OportunidadDto

    fun cambiarEstado(
        id: Long,
        request: CambiarEstadoRequest,
        usuario: UsuarioActual,
    ): CambioEstadoDto

    fun log(
        id: Long,
        usuario: UsuarioActual,
    ): List<LogEstadoDto>

    fun listar(
        filtros: OportunidadFiltros,
        usuario: UsuarioActual,
        page: Int?,
        perPage: Int?,
        sort: String?,
        dir: String?,
    ): Paginado<OportunidadDto>

    fun detalle(
        id: Long,
        usuario: UsuarioActual,
    ): OportunidadDto

    fun vincularContacto(
        id: Long,
        request: ContactoVinculoRequest,
        usuario: UsuarioActual,
    ): ContactoVinculoRequest

    fun actualizarContacto(
        id: Long,
        idContacto: Long,
        rolEnOportunidad: String?,
        usuario: UsuarioActual,
    ): ContactoVinculoRequest

    fun desvincularContacto(
        id: Long,
        idContacto: Long,
        usuario: UsuarioActual,
    )

    /** Para tareas de prospeccion: ¿la empresa tiene oportunidades activas? */
    fun tieneOportunidadesActivas(idEmpresa: Long): Boolean

    /** Cantidad de oportunidades distintas vinculadas a un contacto (listado de contactos). */
    fun countPorContacto(idContacto: Long): Int

    /** Oportunidad visible para el usuario (IDOR → 404). Para eventos/tareas. */
    fun vinculoVisible(
        id: Long,
        usuario: UsuarioActual,
    ): OportunidadVinculo

    /** Sin chequeo de visibilidad (job de sistema). Null si la oportunidad no existe. */
    fun datosRecordatorio(id: Long): OportunidadRecordatorioDatos?
}
