package pe.quantum.crm.domain.actividades

import pe.quantum.crm.domain.actividades.dto.ActividadDto
import pe.quantum.crm.domain.actividades.dto.CambioAuditoriaDto
import pe.quantum.crm.domain.actividades.dto.HistorialFiltros
import pe.quantum.crm.shared.Paginado
import pe.quantum.crm.shared.security.UsuarioActual

/**
 * Vista unificada de las actividades (tareas + eventos) de un empleado.
 *
 * NO amplia ni reduce ninguna visibilidad existente: admin, gerencia y jdv ya
 * veian todas las tareas y eventos (matriz_permisos.md §1). Lo que esto agrega
 * es la forma de filtrar esa vision por empleado y de leerla en un solo sitio.
 */
interface HistorialActividadService {
    /**
     * Historial de `filtros.idEmpleado`. `403 PERMISO_INSUFICIENTE` si el usuario
     * pide el historial de otro y no es supervisor.
     */
    fun historial(
        filtros: HistorialFiltros,
        usuario: UsuarioActual,
        page: Int?,
        perPage: Int?,
    ): Paginado<ActividadDto>

    /** Cambios auditados de una actividad. 404 si el usuario no la alcanza. */
    fun auditoria(
        tipo: TipoActividad,
        idActividad: Long,
        usuario: UsuarioActual,
    ): List<CambioAuditoriaDto>
}
