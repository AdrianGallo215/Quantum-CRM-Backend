package pe.quantum.crm.domain.tipocambio

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pe.quantum.crm.domain.tipocambio.dto.TipoCambioDto
import pe.quantum.crm.integracion.sunat.SunatTipoCambioClient

@Service
class TipoCambioServiceImpl(
    private val tipoCambioRepository: TipoCambioRepository,
    private val sunatTipoCambioClient: SunatTipoCambioClient,
) : TipoCambioService {
    private val log = LoggerFactory.getLogger(TipoCambioServiceImpl::class.java)

    @Transactional(readOnly = true)
    override fun vigente(): TipoCambioDto? =
        tipoCambioRepository.findFirstByOrderByFechaDesc()?.let {
            TipoCambioDto(fecha = it.fecha, compra = it.compra, venta = it.venta)
        }

    /**
     * Fallback silencioso (reglas_simulaciones.md §12): si el proveedor no devuelve dato
     * utilizable se registra un WARN y no se lanza; el valor vigente sigue siendo el
     * ultimo guardado. El upsert es por PK natural (`fecha`), asi que repetir el mismo
     * dia sobreescribe la fila en vez de duplicarla.
     */
    @Transactional
    override fun actualizarDesdeSunat(): Boolean {
        val externo = sunatTipoCambioClient.consultar()
        if (externo == null) {
            log.warn("SUNAT no devolvio un tipo de cambio utilizable; se conserva el ultimo valor guardado")
            return false
        }
        tipoCambioRepository.save(
            TipoCambio(fecha = externo.fecha, compra = externo.compra, venta = externo.venta),
        )
        return true
    }
}
