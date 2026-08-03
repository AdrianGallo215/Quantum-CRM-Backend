package pe.quantum.crm.domain.modelos

import org.springframework.data.jpa.repository.JpaRepository

interface ModeloRepository : JpaRepository<Modelo, Long> {
    fun existsByCodigo(codigo: String): Boolean
}
