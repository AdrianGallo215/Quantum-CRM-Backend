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
import pe.quantum.crm.domain.oportunidades.dto.OportunidadResumenParaContacto
import pe.quantum.crm.domain.oportunidades.dto.OportunidadVinculo
import pe.quantum.crm.shared.Paginado
import pe.quantum.crm.shared.security.UsuarioActual

/** Interfaz publica del modulo oportunidades. */
@Suppress("TooManyFunctions") // Nucleo del pipeline (B3.x), igual que su implementacion.
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

    /** Oportunidades vinculadas a un contacto, para su vista de detalle (contrato_api.md §9). */
    fun oportunidadesPorContacto(idContacto: Long): List<OportunidadResumenParaContacto>

    /** Oportunidad visible para el usuario (IDOR → 404). Para eventos/tareas. */
    fun vinculoVisible(
        id: Long,
        usuario: UsuarioActual,
    ): OportunidadVinculo

    /** Sin chequeo de visibilidad (job de sistema). Null si la oportunidad no existe. */
    fun datosRecordatorio(id: Long): OportunidadRecordatorioDatos?

    /**
     * Aplica un descuento ya aprobado por solicitud (modulo solicitudes): setea
     * `dcto`, recalcula `monto_total` y audita con el aprobador. NO valida limites
     * de rol (la aprobacion ES la autorizacion). 409 SOLICITUD_NO_APLICABLE si la
     * oportunidad no existe o ya salio del pipeline activo.
     */
    fun aplicarDescuentoAprobado(
        id: Long,
        dcto: java.math.BigDecimal,
        idAprobador: Long,
    )

    /**
     * Elimina definitivamente la oportunidad (hard delete, exclusivo admin —
     * verificado en el controller). Cascada de base de datos (V29): arrastra
     * su log de estados, sus vinculos de contacto, sus eventos y sus tareas.
     * Recalcula `estado_cartera` de la empresa (reglas_negocio.md §3.3) ya que
     * esta oportunidad deja de contar.
     */
    fun eliminar(id: Long)
}
