package pe.quantum.crm.domain.financiadoras

import org.springframework.data.jpa.repository.JpaRepository

interface FinanciadoraRepository : JpaRepository<Financiadora, Long> {
    fun findByEsDefaultTrue(): Financiadora?

    fun existsByEsDefaultTrue(): Boolean

    fun existsByEsDefaultTrueAndIdNot(id: Long): Boolean
}
