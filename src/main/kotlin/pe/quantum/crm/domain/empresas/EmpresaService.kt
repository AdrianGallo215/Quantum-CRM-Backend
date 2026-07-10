package pe.quantum.crm.domain.empresas

import pe.quantum.crm.domain.empresas.dto.ActualizarEmpresaRequest
import pe.quantum.crm.domain.empresas.dto.CambioEstadoCartera
import pe.quantum.crm.domain.empresas.dto.CrearEmpresaRequest
import pe.quantum.crm.domain.empresas.dto.EmpresaDetalleDto
import pe.quantum.crm.domain.empresas.dto.EmpresaFiltros
import pe.quantum.crm.domain.empresas.dto.EmpresaListaDto
import pe.quantum.crm.domain.empresas.dto.EmpresaResumen
import pe.quantum.crm.domain.empresas.dto.EmpresaVinculo
import pe.quantum.crm.domain.empresas.dto.RucCheckDto
import pe.quantum.crm.shared.Paginado
import pe.quantum.crm.shared.enums.EstadoCartera
import pe.quantum.crm.shared.security.UsuarioActual

/** Interfaz publica del modulo empresas. */
interface EmpresaService {
    fun listar(
        filtros: EmpresaFiltros,
        usuario: UsuarioActual,
        page: Int?,
        perPage: Int?,
        sort: String?,
        dir: String?,
    ): Paginado<EmpresaListaDto>

    /** Detalle (sin contactos: el controller los compone via ContactoService). */
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
     * Reasignacion de vendedor (solo admin/gerente/jdv — verificado en controller).
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

    /** Sin chequeo de visibilidad (job de sistema). Null si la empresa no existe o no tiene vendedor. */
    fun vendedorAsignado(id: Long): Long?
}
