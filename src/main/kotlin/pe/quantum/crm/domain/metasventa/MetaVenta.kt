package pe.quantum.crm.domain.metasventa

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import pe.quantum.crm.shared.enums.EstadoMeta
import java.time.LocalDateTime

/**
 * Meta de venta en unidades (tabla `metas_venta`, migracion V32). Una fila por
 * `(id_empleado, anio)`: los 12 meses + el total anual, con un unico ciclo de
 * aprobacion para el año completo (el JDV propone los 12 meses de una vez).
 */
@Entity
@Table(name = "metas_venta")
@Suppress("LongParameterList") // Una entidad JPA refleja las columnas de su tabla.
class MetaVenta(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,
    @Column(name = "id_empleado", nullable = false)
    val idEmpleado: Long,
    @Column(nullable = false)
    val anio: Int,
    @Column(name = "meta_enero", nullable = false) var metaEnero: Int = 0,
    @Column(name = "meta_febrero", nullable = false) var metaFebrero: Int = 0,
    @Column(name = "meta_marzo", nullable = false) var metaMarzo: Int = 0,
    @Column(name = "meta_abril", nullable = false) var metaAbril: Int = 0,
    @Column(name = "meta_mayo", nullable = false) var metaMayo: Int = 0,
    @Column(name = "meta_junio", nullable = false) var metaJunio: Int = 0,
    @Column(name = "meta_julio", nullable = false) var metaJulio: Int = 0,
    @Column(name = "meta_agosto", nullable = false) var metaAgosto: Int = 0,
    @Column(name = "meta_septiembre", nullable = false) var metaSeptiembre: Int = 0,
    @Column(name = "meta_octubre", nullable = false) var metaOctubre: Int = 0,
    @Column(name = "meta_noviembre", nullable = false) var metaNoviembre: Int = 0,
    @Column(name = "meta_diciembre", nullable = false) var metaDiciembre: Int = 0,
    @Column(name = "meta_anual", nullable = false) var metaAnual: Int = 0,
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(nullable = false, columnDefinition = "estado_meta_enum")
    var estado: EstadoMeta = EstadoMeta.propuesta,
    @Column(name = "id_propuesto_por", nullable = false)
    var idPropuestoPor: Long,
    @Column(name = "id_resolutor")
    var idResolutor: Long? = null,
    @Column(name = "motivo_rechazo")
    var motivoRechazo: String? = null,
    @Column(name = "resolved_at")
    var resolvedAt: LocalDateTime? = null,
    @Column(name = "created_at", nullable = false)
    val createdAt: LocalDateTime = LocalDateTime.now(),
    @Column(name = "updated_at", nullable = false)
    var updatedAt: LocalDateTime = LocalDateTime.now(),
) {
    /** Los 12 meses en orden calendario (enero..diciembre). */
    fun meses(): List<Int> =
        listOf(
            metaEnero, metaFebrero, metaMarzo, metaAbril, metaMayo, metaJunio,
            metaJulio, metaAgosto, metaSeptiembre, metaOctubre, metaNoviembre, metaDiciembre,
        )

    /** Valor del mes (1=enero..12=diciembre). */
    @Suppress("MagicNumber") // 1..12 son los meses del año, no una constante configurable.
    fun valorMes(mes: Int): Int {
        require(mes in 1..12) { "Mes inválido: $mes" }
        return meses()[mes - 1]
    }

    /** Reemplaza los 12 meses y recalcula `metaAnual` (SOLO LECTURA, igual que `monto_total`). */
    @Suppress("MagicNumber") // Indices posicionales de los 12 meses; nombrarlos uno a uno no aporta.
    fun establecerMeses(valores: List<Int>) {
        require(valores.size == 12) { "Se requieren 12 valores mensuales, se recibieron ${valores.size}" }
        metaEnero = valores[0]
        metaFebrero = valores[1]
        metaMarzo = valores[2]
        metaAbril = valores[3]
        metaMayo = valores[4]
        metaJunio = valores[5]
        metaJulio = valores[6]
        metaAgosto = valores[7]
        metaSeptiembre = valores[8]
        metaOctubre = valores[9]
        metaNoviembre = valores[10]
        metaDiciembre = valores[11]
        metaAnual = valores.sum()
    }
}
