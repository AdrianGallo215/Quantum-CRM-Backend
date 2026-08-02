package pe.quantum.crm.domain.catalogoeventos

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pe.quantum.crm.domain.catalogoeventos.dto.ActualizarCatalogoEventoRequest
import pe.quantum.crm.domain.catalogoeventos.dto.CatalogoEventoDto
import pe.quantum.crm.domain.catalogoeventos.dto.CrearCatalogoEventoRequest
import pe.quantum.crm.domain.catalogoeventos.dto.toDto
import pe.quantum.crm.shared.enums.EstadoOportunidad
import pe.quantum.crm.shared.exception.ConflictoException
import pe.quantum.crm.shared.exception.NoEncontradoException
import pe.quantum.crm.shared.exception.ValidacionException

@Service
class CatalogoEventoServiceImpl(
    private val repository: CatalogoEventoRepository,
) : CatalogoEventoService {
    @Transactional(readOnly = true)
    override fun listar(etapaAsociada: EstadoOportunidad?): List<CatalogoEventoDto> {
        val eventos =
            if (etapaAsociada != null) {
                repository.findByEtapaAsociada(etapaAsociada)
            } else {
                repository.findAll()
            }
        return eventos.sortedBy { it.id }.map { it.toDto() }
    }

    @Transactional
    override fun crear(request: CrearCatalogoEventoRequest): CatalogoEventoDto {
        validarDisparo(request.disparaCambioEstado, request.estadoDestino)
        if (repository.existsByNombre(request.nombre)) {
            throw ConflictoException("NOMBRE_DUPLICADO", "Ya existe un evento del catálogo con ese nombre")
        }
        val evento =
            CatalogoEvento(
                nombre = request.nombre,
                etapaAsociada = request.etapaAsociada,
                disparaCambioEstado = request.disparaCambioEstado,
                estadoDestino = request.estadoDestino,
                esRecomendado = request.esRecomendado,
                esHitoProspeccion = request.esHitoProspeccion,
            )
        return repository.save(evento).toDto()
    }

    @Transactional
    override fun actualizar(
        id: Long,
        request: ActualizarCatalogoEventoRequest,
    ): CatalogoEventoDto {
        val evento = entidad(id)
        request.nombre?.let { evento.nombre = it }
        request.etapaAsociada?.let { evento.etapaAsociada = it }
        request.disparaCambioEstado?.let { evento.disparaCambioEstado = it }
        request.estadoDestino?.let { evento.estadoDestino = it }
        request.esRecomendado?.let { evento.esRecomendado = it }
        request.esHitoProspeccion?.let { evento.esHitoProspeccion = it }
        validarDisparo(evento.disparaCambioEstado, evento.estadoDestino)
        return repository.save(evento).toDto()
    }

    @Transactional(readOnly = true)
    override fun porId(id: Long): CatalogoEventoDto = entidad(id).toDto()

    @Transactional(readOnly = true)
    override fun todosPorId(): Map<Long, CatalogoEventoDto> = repository.findAll().associate { requireNotNull(it.id) to it.toDto() }

    @Transactional(readOnly = true)
    override fun hitosProspeccion(): List<CatalogoEventoDto> = repository.findByEsHitoProspeccionTrueOrderById().map { it.toDto() }

    /** Constraint `dispara_cambio_estado → estado_destino` (schema V13). */
    private fun validarDisparo(
        dispara: Boolean,
        destino: EstadoOportunidad?,
    ) {
        if (dispara && destino == null) {
            throw ValidacionException(
                "Un evento que dispara cambio de estado debe indicar estado_destino",
                field = "estado_destino",
            )
        }
    }

    private fun entidad(id: Long): CatalogoEvento =
        repository.findById(id).orElseThrow { NoEncontradoException("El evento del catálogo no existe") }
}
