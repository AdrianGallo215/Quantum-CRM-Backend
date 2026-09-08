package pe.quantum.crm.domain.actividades

import org.springframework.data.jpa.repository.JpaRepository

interface ActividadAuditoriaRepository : JpaRepository<ActividadAuditoria, Long> {
    fun findByIdTareaOrderByChangedAtDesc(idTarea: Long): List<ActividadAuditoria>

    fun findByIdEventoOrderByChangedAtDesc(idEvento: Long): List<ActividadAuditoria>
}
