package pe.quantum.crm.domain.notificaciones

import org.springframework.data.jpa.repository.JpaRepository

interface RecordatorioEnviadoRepository : JpaRepository<RecordatorioEnviado, Long> {
    fun existsByOrigenAndIdOrigenAndUmbral(
        origen: OrigenRecordatorio,
        idOrigen: Long,
        umbral: UmbralRecordatorio,
    ): Boolean
}
