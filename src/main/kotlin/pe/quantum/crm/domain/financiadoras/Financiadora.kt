package pe.quantum.crm.domain.financiadoras

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.math.BigDecimal

/**
 * Financiadora (tabla `financiadoras`, migracion V5). Calidda es el seed default.
 * Terminos NULL = negociables por operacion (reglas_negocio.md §9.3). Solo una
 * puede tener `es_default = true` (unique index parcial + validacion en backend).
 */
@Entity
@Table(name = "financiadoras")
@Suppress("LongParameterList") // Entidad JPA: un parametro por columna de la tabla.
class Financiadora(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,
    @Column(nullable = false)
    var nombre: String,
    @Column(name = "monto_por_unidad")
    var montoPorUnidad: BigDecimal? = null,
    @Column(name = "plazo_meses")
    var plazoMeses: Int? = null,
    var tea: BigDecimal? = null,
    @Column(name = "cuota_por_unidad")
    var cuotaPorUnidad: BigDecimal? = null,
    @Column(name = "es_default", nullable = false)
    var esDefault: Boolean = false,
    var notas: String? = null,
)
