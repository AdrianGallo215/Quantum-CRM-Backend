package pe.quantum.crm.domain.empresas

import pe.quantum.crm.domain.empresas.dto.ActualizarEmpresaRequest
import pe.quantum.crm.domain.empresas.dto.CambioEstadoCartera
import pe.quantum.crm.domain.empresas.dto.CarteraMaestraDto
import pe.quantum.crm.domain.empresas.dto.CrearEmpresaRequest
import pe.quantum.crm.domain.empresas.dto.EmpresaDetalleDto
import pe.quantum.crm.domain.empresas.dto.EmpresaFiltros
import pe.quantum.crm.domain.empresas.dto.EmpresaListaDto
import pe.quantum.crm.domain.empresas.dto.EmpresaResumen
import pe.quantum.crm.domain.empresas.dto.EmpresaVinculo
import pe.quantum.crm.domain.empresas.dto.RucCheckDto
import pe.quantum.crm.integracion.drive.DriveArchivoSubido
import pe.quantum.crm.shared.Paginado
import pe.quantum.crm.shared.enums.EstadoCartera
import pe.quantum.crm.shared.security.UsuarioActual

/**
 * Interfaz publica del modulo empresas: cartera, cascadas y Drive, igual que su
 * implementacion. `listar` arrastra los 4 parametros de paginacion del contrato
 * (page, per_page, sort, dir).
 */
@Suppress("TooManyFunctions", "LongParameterList")
interface EmpresaService {
    fun listar(
        filtros: EmpresaFiltros,
        usuario: UsuarioActual,
        page: Int?,
        perPage: Int?,
        sort: String?,
        dir: String?,
    ): Paginado<EmpresaListaDto>

    /** Detalle completo, con `contactos` ya poblado via ContactoService (§8). */
    fun detalle(
        id: Long,
        usuario: UsuarioActual,
    ): EmpresaDetalleDto

    fun checkRuc(ruc: String): RucCheckDto

    fun crear(
        request: CrearEmpresaRequest,
        usuario: UsuarioActual,
    ): EmpresaDetalleDto

    fun actualizar(
        id: Long,
        request: ActualizarEmpresaRequest,
        usuario: UsuarioActual,
    ): EmpresaDetalleDto

    /** Cambio manual de estado de cartera (solo estados manuales, B2.4). */
    fun cambiarEstadoCarteraManual(
        id: Long,
        estadoCartera: String,
        usuario: UsuarioActual,
    ): String

    /**
     * Reasignacion de vendedor (solo admin/gerencia — verificado en controller;
     * el jdv requiere una solicitud aprobada, ver modulo `solicitudes`).
     * Notifica al vendedor destino. Publica `VendedorEmpresaReasignadoEvent`, que
     * cascade el mismo vendedor a las oportunidades activas de la empresa
     * (reglas_negocio.md §8).
     */
    fun reasignarVendedor(
        id: Long,
        idVendedor: Long,
        usuario: UsuarioActual,
    ): Long

    /**
     * Empresa visible para el usuario (filtro IDOR: ajena o inexistente → 404).
     * Puerta de entrada de los demas modulos (contactos, oportunidades, tareas).
     */
    fun vinculoVisible(
        id: Long,
        usuario: UsuarioActual,
    ): EmpresaVinculo

    /**
     * Escritura de `estado_cartera` derivado con su guarda de entrada
     * (reglas_negocio.md §3.2 pasos 2-4). SOLO la invoca `actualizarEstadoCartera`
     * del modulo de oportunidades, dentro de la transaccion del evento disparador.
     */
    fun aplicarEstadoDerivado(
        idEmpresa: Long,
        derivado: EstadoCartera?,
    ): CambioEstadoCartera?

    fun resumenPorIds(ids: Collection<Long>): Map<Long, EmpresaResumen>

    /** Segmentos por empresa (para el detalle de contacto: empresas[].segmentos). */
    fun segmentosPorIds(ids: Collection<Long>): Map<Long, List<String>>

    /** Sin chequeo de visibilidad (job de sistema). Null si la empresa no existe o no tiene vendedor. */
    fun vendedorAsignado(id: Long): Long?

    /**
     * Devuelve la carpeta de Drive de la empresa, creandola si aun no existe.
     * Idempotente: si ya hay una, no toca Drive. Cubre las empresas anteriores a
     * V35 y cualquier hueco. Sin chequeo de visibilidad: uso interno entre modulos,
     * donde quien llama ya la verifico (p. ej. al crear una oportunidad).
     */
    fun asegurarCarpetaDrive(id: Long): String

    /**
     * Igual que [asegurarCarpetaDrive], pero para uso HTTP directo: verifica
     * visibilidad primero (ajena o inexistente → 404, IDOR SECURITY §3.2).
     */
    fun asegurarCarpetaDrive(
        id: Long,
        usuario: UsuarioActual,
    ): String

    /**
     * Documentos de la carpeta de Drive de la empresa. Lista vacia si aun no tiene
     * carpeta (no la crea: una lectura no debe tener efectos secundarios).
     */
    fun archivosDrive(
        id: Long,
        usuario: UsuarioActual,
    ): List<DriveArchivoSubido>

    /**
     * Mueve una empresa a la Cartera Maestra (reserva de gerencia, la desasigna)
     * o la libera asignando vendedor (obligatorio) y notificando `empresa_asignada`.
     * Solo gerencia/admin (el controller ya lo restringe; el servicio re-verifica).
     */
    fun cambiarCarteraMaestra(
        id: Long,
        enCarteraMaestra: Boolean,
        idVendedor: Long?,
        usuario: UsuarioActual,
    ): CarteraMaestraDto

    /**
     * Elimina definitivamente la empresa (hard delete, exclusivo admin —
     * verificado en el controller). Cascada de base de datos (V29): arrastra
     * sus oportunidades, tareas, eventos y el log de estados. Los contactos
     * vinculados NO se eliminan, solo se desvinculan.
     */
    fun eliminar(id: Long)

    /**
     * Ids de empresas sin carpeta de Drive. Sin chequeo de visibilidad: lo consume
     * el backfill administrativo, que corre sobre todo el sistema.
     */
    fun idsSinCarpetaDrive(): List<Long>
}
