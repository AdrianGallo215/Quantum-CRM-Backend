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
import pe.quantum.crm.integracion.drive.DriveArchivoSubido
import pe.quantum.crm.shared.Paginado
import pe.quantum.crm.shared.security.UsuarioActual

/**
 * Interfaz publica del modulo oportunidades: nucleo del pipeline (B3.x), igual
 * que su implementacion. `listar` arrastra los 4 parametros de paginacion del
 * contrato (page, per_page, sort, dir).
 */
@Suppress("TooManyFunctions", "LongParameterList")
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

    /** Oportunidad visible para el usuario (IDOR → 404). Para eventos/tareas. */
    fun vinculoVisible(
        id: Long,
        usuario: UsuarioActual,
    ): OportunidadVinculo

    /** Sin chequeo de visibilidad (job de sistema). Null si la oportunidad no existe. */
    fun datosRecordatorio(id: Long): OportunidadRecordatorioDatos?

    /**
     * Elimina definitivamente la oportunidad (hard delete, exclusivo admin —
     * verificado en el controller). Cascada de base de datos (V29): arrastra
     * su log de estados, sus vinculos de contacto, sus eventos y sus tareas.
     * Recalcula `estado_cartera` de la empresa (reglas_negocio.md §3.3) ya que
     * esta oportunidad deja de contar.
     */
    fun eliminar(id: Long)

    /**
     * Devuelve la carpeta de Drive de la oportunidad, creandola (y la de su empresa
     * si hiciera falta) cuando aun no existe. Verifica visibilidad: ajena o
     * inexistente → 404 (IDOR, SECURITY §3.2).
     *
     * Es una operacion corta y transaccional a proposito: la subida del archivo
     * ocurre DESPUES y fuera de la transaccion, para no retener una conexion a la
     * base de datos mientras se transfieren megabytes a Drive.
     */
    fun asegurarCarpetaDrive(
        id: Long,
        usuario: UsuarioActual,
    ): String

    /**
     * Igual que la sobrecarga con usuario, pero sin chequeo de visibilidad: uso
     * interno de jobs de sistema (backfill administrativo). Crea antes la carpeta
     * de la empresa si le falta.
     */
    fun asegurarCarpetaDrive(id: Long): String

    /**
     * Documentos de la carpeta de Drive de la oportunidad. Lista vacia si aun no
     * tiene carpeta (no la crea: una lectura no debe tener efectos secundarios).
     * Ajena o inexistente → 404 (IDOR, SECURITY §3.2).
     */
    fun archivosDrive(
        id: Long,
        usuario: UsuarioActual,
    ): List<DriveArchivoSubido>

    /**
     * Ids de oportunidades sin carpeta de Drive. Sin chequeo de visibilidad: lo
     * consume el backfill administrativo, que corre sobre todo el sistema.
     */
    fun idsSinCarpetaDrive(): List<Long>
}
