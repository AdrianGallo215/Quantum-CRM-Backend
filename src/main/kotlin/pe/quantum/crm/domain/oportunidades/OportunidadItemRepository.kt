package pe.quantum.crm.domain.oportunidades

import org.springframework.data.jpa.repository.JpaRepository

interface OportunidadItemRepository : JpaRepository<OportunidadItem, Long> {
    fun findByIdOportunidadOrderByIdAsc(idOportunidad: Long): List<OportunidadItem>

    fun findByIdOportunidadInOrderByIdAsc(idsOportunidad: Collection<Long>): List<OportunidadItem>
}
