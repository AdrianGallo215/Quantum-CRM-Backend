package pe.quantum.crm.domain.notificaciones

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.time.LocalDateTime

/** Valores de `origen_recordatorio_enum` (migracion V22). */
@Suppress("ktlint:standard:enum-entry-name-case", "EnumNaming", "EnumEntryName")
enum class OrigenRecordatorio {
    tarea,
    evento,
}

/** Valores de `umbral_recordatorio_enum` (migracion V22). */
@Suppress("ktlint:standard:enum-entry-name-case", "EnumNaming", "EnumEntryName")
enum class UmbralRecordatorio {
    proximo,
    vencido,
}

/** Dedup del job de recordatorios (tabla `recordatorios_enviados`, migracion V22). */
@Entity
@Table(name = "recordatorios_enviados")
class RecordatorioEnviado(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(nullable = false, columnDefinition = "origen_recordatorio_enum")
    val origen: OrigenRecordatorio,
    @Column(name = "id_origen", nullable = false)
    val idOrigen: Long,
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(nullable = false, columnDefinition = "umbral_recordatorio_enum")
    val umbral: UmbralRecordatorio,
    @Column(name = "created_at", nullable = false)
    val createdAt: LocalDateTime = LocalDateTime.now(),
)
