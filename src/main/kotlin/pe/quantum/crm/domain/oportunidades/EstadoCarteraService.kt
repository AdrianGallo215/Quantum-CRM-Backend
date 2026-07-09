package pe.quantum.crm.domain.oportunidades

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pe.quantum.crm.domain.empresas.EmpresaService
import pe.quantum.crm.domain.empresas.dto.CambioEstadoCartera
import pe.quantum.crm.shared.enums.EstadoCartera
import pe.quantum.crm.shared.enums.EstadoOportunidad

/**
 * `actualizarEstadoCartera` (reglas_negocio.md §3.2) — la UNICA via por la que el
 * sistema modifica `estado_cartera`. Se invoca SIEMPRE dentro de la transaccion
 * del evento que lo puede afectar (crear oportunidad, cambiar estado, retroceso).
 *
 * Recalcula mirando el conjunto COMPLETO de oportunidades de la empresa; nunca
 * asume el estado desde la transicion individual. La guarda de entrada (no
 * escribir si no hay cambio, respetar estados manuales) vive en
 * `EmpresaService.aplicarEstadoDerivado`.
 */
@Service
class EstadoCarteraService(
    private val oportunidadRepository: OportunidadRepository,
    private val empresaService: EmpresaService,
) {
    @Transactional
    fun actualizar(idEmpresa: Long): CambioEstadoCartera? {
        val derivado =
            when {
                oportunidadRepository.existsByIdEmpresaAndEstado(idEmpresa, EstadoOportunidad.facturado) ->
                    EstadoCartera.cliente
                oportunidadRepository.existsByIdEmpresaAndEstadoIn(idEmpresa, ESTADOS_ACTIVOS) ->
                    EstadoCartera.oportunidad_activa
                else -> null
            }
        return empresaService.aplicarEstadoDerivado(idEmpresa, derivado)
    }

    companion object {
        val ESTADOS_ACTIVOS = listOf(EstadoOportunidad.evaluacion_calidda, EstadoOportunidad.documentos_legales)
    }
}
