package pe.quantum.crm.domain.oportunidades

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.math.BigDecimal
import java.time.LocalDateTime

/**
 * Un modelo de bus dentro de una oportunidad (tabla `oportunidad_items`, migracion V42).
 * Una oportunidad con varios modelos distintos tiene varios items (plan-03-mapa-oportunidad-items.md, decision D6).
 *
 * `idOportunidad` es una columna `Long` simple, no una relacion `@ManyToOne` navegable:
 * mismo patron que `Oportunidad.idEmpresa`/`idVendedor` y `MetaVenta.idEmpleado`, evita
 * lazy-loading fuera de transaccion y exponer una entidad por serializacion accidental
 * (CLAUDE.md regla 9).
 *
 * `cuotaFinanciadora` es lo que el cliente paga a terceros (Calidda, cajas) por su
 * inicial; no participa del `monto_total` de la oportunidad (reglas_simulaciones.md §1.2).
 *
 * Sin logica de negocio: el calculo de montos y las validaciones se resuelven en un
 * plan posterior.
 */
@Entity
@Table(name = "oportunidad_items")
@Suppress("LongParameterList") // Una entidad JPA refleja las columnas de su tabla.
class OportunidadItem(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,
    @Column(name = "id_oportunidad", nullable = false)
    var idOportunidad: Long,
    @Column(name = "id_modelo", nullable = false)
    var idModelo: Long,
    var cantidad: Int? = null,
    @Column(name = "precio_venta")
    var precioVenta: BigDecimal? = null,
    var descuento: BigDecimal? = null,
    @Column(name = "cuota_financiadora", nullable = false)
    var cuotaFinanciadora: BigDecimal = BigDecimal("937.50"),
    @Column(name = "created_at", nullable = false)
    val createdAt: LocalDateTime = LocalDateTime.now(),
    @Column(name = "created_by", nullable = false)
    val createdBy: Long,
    @Column(name = "updated_at", nullable = false)
    var updatedAt: LocalDateTime = LocalDateTime.now(),
    @Column(name = "updated_by", nullable = false)
    var updatedBy: Long,
)
