package pe.quantum.crm.domain.catalogoeventos

import org.springframework.data.jpa.repository.JpaRepository
import pe.quantum.crm.shared.enums.EstadoOportunidad

interface CatalogoEventoRepository : JpaRepository<CatalogoEvento, Long> {
    fun findByEtapaAsociada(etapa: EstadoOportunidad): List<CatalogoEvento>

    fun findByEsHitoProspeccionTrueOrderById(): List<CatalogoEvento>

    fun existsByNombre(nombre: String): Boolean
}
