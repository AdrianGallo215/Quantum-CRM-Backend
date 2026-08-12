package pe.quantum.crm.domain.modelos

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pe.quantum.crm.domain.modelos.dto.ActualizarModeloRequest
import pe.quantum.crm.domain.modelos.dto.CrearModeloRequest
import pe.quantum.crm.domain.modelos.dto.ModeloDto
import pe.quantum.crm.domain.modelos.dto.ModeloResumen
import pe.quantum.crm.domain.modelos.dto.toDto
import pe.quantum.crm.domain.modelos.dto.toResumen
import pe.quantum.crm.shared.exception.ConflictoException
import pe.quantum.crm.shared.exception.ModeloSinAplicacionesException
import pe.quantum.crm.shared.exception.NoEncontradoException

@Service
class ModeloServiceImpl(
    private val modeloRepository: ModeloRepository,
) : ModeloService {
    @Transactional(readOnly = true)
    override fun listar(): List<ModeloDto> = modeloRepository.findAll().sortedBy { it.codigo }.map { it.toDto() }

    /** Modelo + aplicaciones en una sola transaccion (reglas_negocio.md §2.4). */
    @Transactional
    override fun crear(request: CrearModeloRequest): ModeloDto {
        val aplicaciones = request.aplicaciones.orEmpty()
        if (aplicaciones.isEmpty()) {
            throw ModeloSinAplicacionesException()
        }
        if (modeloRepository.existsByCodigo(request.codigo)) {
            throw ConflictoException("CODIGO_DUPLICADO", "Ya existe un modelo con ese código", field = "codigo")
        }
        val modelo =
            Modelo(
                codigo = request.codigo,
                longitud = request.longitud,
                capacidadTanques = request.capacidadTanques,
                maxAsientos = request.maxAsientos,
                precioBase = request.precioBase,
                fichaTecnica = request.fichaTecnica,
                aplicaciones = aplicaciones.toMutableSet(),
            )
        return modeloRepository.save(modelo).toDto()
    }

    @Transactional
    override fun actualizar(
        id: Long,
        request: ActualizarModeloRequest,
    ): ModeloDto {
        val modelo = porId(id)
        request.codigo?.let {
            // Se valida en backend igual que en `crear`: dejarlo a la constraint
            // devolvia otro codigo de error para el mismo problema.
            if (it != modelo.codigo && modeloRepository.existsByCodigoAndIdNot(it, id)) {
                throw ConflictoException("CODIGO_DUPLICADO", "Ya existe un modelo con ese código", field = "codigo")
            }
            modelo.codigo = it
        }
        request.longitud?.let { modelo.longitud = it }
        request.capacidadTanques?.let { modelo.capacidadTanques = it }
        request.maxAsientos?.let { modelo.maxAsientos = it }
        request.precioBase?.let { modelo.precioBase = it }
        request.fichaTecnica?.let { modelo.fichaTecnica = it }
        request.aplicaciones?.let {
            if (it.isEmpty()) {
                throw ModeloSinAplicacionesException()
            }
            modelo.aplicaciones.clear()
            modelo.aplicaciones.addAll(it)
        }
        return modeloRepository.save(modelo).toDto()
    }

    @Transactional(readOnly = true)
    override fun resumen(id: Long): ModeloResumen = porId(id).toResumen()

    @Transactional(readOnly = true)
    override fun resumenPorIds(ids: Collection<Long>): Map<Long, ModeloResumen> =
        modeloRepository.findAllById(ids.toSet()).associate { requireNotNull(it.id) to it.toResumen() }

    private fun porId(id: Long): Modelo = modeloRepository.findById(id).orElseThrow { NoEncontradoException("El modelo no existe") }
}
