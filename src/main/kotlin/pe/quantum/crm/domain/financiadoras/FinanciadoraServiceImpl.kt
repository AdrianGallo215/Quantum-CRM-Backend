package pe.quantum.crm.domain.financiadoras

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pe.quantum.crm.domain.financiadoras.dto.ActualizarFinanciadoraRequest
import pe.quantum.crm.domain.financiadoras.dto.CrearFinanciadoraRequest
import pe.quantum.crm.domain.financiadoras.dto.FinanciadoraDto
import pe.quantum.crm.domain.financiadoras.dto.toDto
import pe.quantum.crm.shared.exception.ConflictoException
import pe.quantum.crm.shared.exception.FinanciadoraDefaultInexistenteException
import pe.quantum.crm.shared.exception.NoEncontradoException

@Service
class FinanciadoraServiceImpl(
    private val financiadoraRepository: FinanciadoraRepository,
) : FinanciadoraService {
    @Transactional(readOnly = true)
    override fun listar(): List<FinanciadoraDto> = financiadoraRepository.findAll().sortedBy { it.id }.map { it.toDto() }

    /** Valida el unique default en backend ANTES del insert (reglas §9.1). */
    @Transactional
    override fun crear(request: CrearFinanciadoraRequest): FinanciadoraDto {
        if (request.esDefault && financiadoraRepository.existsByEsDefaultTrue()) {
            throw ConflictoException(
                "FINANCIADORA_DEFAULT_DUPLICADA",
                "Ya existe una financiadora default; solo puede haber una",
            )
        }
        val financiadora =
            Financiadora(
                nombre = request.nombre,
                montoPorUnidad = request.montoPorUnidad,
                plazoMeses = request.plazoMeses,
                tea = request.tea,
                cuotaPorUnidad = request.cuotaPorUnidad,
                esDefault = request.esDefault,
                notas = request.notas,
            )
        return financiadoraRepository.save(financiadora).toDto()
    }

    @Transactional
    override fun actualizar(
        id: Long,
        request: ActualizarFinanciadoraRequest,
    ): FinanciadoraDto {
        val financiadora = entidad(id)
        if (request.esDefault == true && financiadoraRepository.existsByEsDefaultTrueAndIdNot(id)) {
            throw ConflictoException(
                "FINANCIADORA_DEFAULT_DUPLICADA",
                "Ya existe una financiadora default; solo puede haber una",
            )
        }
        request.nombre?.let { financiadora.nombre = it }
        request.montoPorUnidad?.let { financiadora.montoPorUnidad = it }
        request.plazoMeses?.let { financiadora.plazoMeses = it }
        request.tea?.let { financiadora.tea = it }
        request.cuotaPorUnidad?.let { financiadora.cuotaPorUnidad = it }
        request.esDefault?.let { financiadora.esDefault = it }
        request.notas?.let { financiadora.notas = it }
        return financiadoraRepository.save(financiadora).toDto()
    }

    @Transactional(readOnly = true)
    override fun default(): FinanciadoraDto =
        financiadoraRepository.findByEsDefaultTrue()?.toDto()
            ?: throw FinanciadoraDefaultInexistenteException()

    @Transactional(readOnly = true)
    override fun porId(id: Long): FinanciadoraDto = entidad(id).toDto()

    @Transactional(readOnly = true)
    override fun porIds(ids: Collection<Long>): Map<Long, FinanciadoraDto> =
        financiadoraRepository.findAllById(ids.toSet()).associate { requireNotNull(it.id) to it.toDto() }

    private fun entidad(id: Long): Financiadora =
        financiadoraRepository.findById(id).orElseThrow { NoEncontradoException("La financiadora no existe") }
}
