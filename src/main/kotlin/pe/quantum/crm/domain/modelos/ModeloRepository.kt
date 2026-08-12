package pe.quantum.crm.domain.modelos

import org.springframework.data.jpa.repository.JpaRepository

interface ModeloRepository : JpaRepository<Modelo, Long> {
    fun existsByCodigo(codigo: String): Boolean

    /** Unicidad al actualizar: el propio modelo no cuenta como duplicado de si mismo. */
    fun existsByCodigoAndIdNot(
        codigo: String,
        id: Long,
    ): Boolean
}
