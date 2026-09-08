package pe.quantum.crm.domain.actividades

import pe.quantum.crm.domain.actividades.dto.CambioAuditoriaDto
import pe.quantum.crm.domain.actividades.dto.CambioCampo

/**
 * Auditoria de ediciones sobre tareas y eventos.
 *
 * IMPORTANTE (dependencia circular): la implementacion de esta interfaz NO puede
 * depender de `TareaService` ni de `EventoService`, porque esos dos modulos
 * dependen de ESTA para registrar sus ediciones. Por eso `historial` no
 * comprueba visibilidad: la comprueba quien lo llama.
 */
interface AuditoriaActividadService {
    /**
     * Registra una fila por cambio. Lista vacia = no-op (una edicion que no
     * modifico nada no debe ensuciar el historial).
     *
     * Se llama DENTRO de la transaccion de la edicion: si esta se revierte, la
     * auditoria se revierte con ella.
     */
    fun registrar(
        tipo: TipoActividad,
        idActividad: Long,
        cambios: List<CambioCampo>,
        idActor: Long,
    )

    /** Cambios de una actividad, del mas reciente al mas antiguo. Sin guard de visibilidad. */
    fun historial(
        tipo: TipoActividad,
        idActividad: Long,
    ): List<CambioAuditoriaDto>
}
