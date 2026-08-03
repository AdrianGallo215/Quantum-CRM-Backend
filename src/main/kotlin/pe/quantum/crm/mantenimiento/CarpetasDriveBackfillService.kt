package pe.quantum.crm.mantenimiento

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import pe.quantum.crm.domain.empresas.EmpresaService
import pe.quantum.crm.domain.oportunidades.OportunidadService
import pe.quantum.crm.mantenimiento.dto.BackfillCarpetasDto
import pe.quantum.crm.mantenimiento.dto.ErrorBackfillDto

/**
 * Crea las carpetas de Drive que les faltan a empresas y oportunidades anteriores
 * a la integracion (ver docs/superpowers/specs/2026-07-31-carpetas-drive-creacion-explicita-design.md).
 *
 * NO lleva `@Transactional` a proposito. Cada `asegurarCarpetaDrive` se invoca
 * desde fuera del servicio de dominio, asi que pasa por el proxy de Spring y abre
 * SU PROPIA transaccion: lo ya procesado queda commiteado aunque la llamada se
 * corte a la mitad, y repetir el endpoint retoma donde quedo. Envolver todo el
 * bucle en una transaccion unica romperia justo esa garantia.
 */
@Service
class CarpetasDriveBackfillService(
    private val empresaService: EmpresaService,
    private val oportunidadService: OportunidadService,
) {
    private val log = LoggerFactory.getLogger(CarpetasDriveBackfillService::class.java)

    /**
     * @param tamanoLote tope de registros a procesar en esta llamada; `null` procesa
     *   todos los pendientes.
     */
    fun ejecutar(tamanoLote: Int?): BackfillCarpetasDto {
        val empresasPendientes = empresaService.idsSinCarpetaDrive()
        val oportunidadesPendientes = oportunidadService.idsSinCarpetaDrive()
        val errores = mutableListOf<ErrorBackfillDto>()

        // Empresas primero: la carpeta de una oportunidad cuelga de la de su
        // empresa, asi se evita trabajo redundante.
        var presupuesto = tamanoLote ?: (empresasPendientes.size + oportunidadesPendientes.size)
        val empresasTomadas = empresasPendientes.take(maxOf(presupuesto, 0))
        val empresasCreadas =
            empresasTomadas.count { id ->
                procesar("empresa", id, errores) { empresaService.asegurarCarpetaDrive(id) }
            }
        presupuesto -= empresasTomadas.size

        val oportunidadesTomadas = oportunidadesPendientes.take(maxOf(presupuesto, 0))
        val oportunidadesCreadas =
            oportunidadesTomadas.count { id ->
                procesar("oportunidad", id, errores) { oportunidadService.asegurarCarpetaDrive(id) }
            }

        val restantes =
            (empresasPendientes.size - empresasCreadas) + (oportunidadesPendientes.size - oportunidadesCreadas)
        log.info(
            "Backfill de carpetas de Drive: empresas={} oportunidades={} errores={} pendientes={}",
            empresasCreadas,
            oportunidadesCreadas,
            errores.size,
            restantes,
        )
        return BackfillCarpetasDto(
            empresasProcesadas = empresasCreadas,
            oportunidadesProcesadas = oportunidadesCreadas,
            errores = errores,
            pendientesRestantes = restantes,
        )
    }

    /**
     * Aisla el fallo de un registro: se anota y el bucle sigue con el siguiente.
     * Un RUC raro o una caida puntual de Drive no debe abortar todo el backfill.
     */
    @Suppress("TooGenericExceptionCaught") // El aislamiento por registro es justo el objetivo.
    private fun procesar(
        entidad: String,
        id: Long,
        errores: MutableList<ErrorBackfillDto>,
        accion: () -> String,
    ): Boolean =
        try {
            accion()
            true
        } catch (ex: RuntimeException) {
            log.warn("Backfill: no se pudo crear la carpeta de {} {}", entidad, id, ex)
            errores += ErrorBackfillDto(entidad = entidad, id = id, motivo = ex.message ?: ex.javaClass.simpleName)
            false
        }
}
