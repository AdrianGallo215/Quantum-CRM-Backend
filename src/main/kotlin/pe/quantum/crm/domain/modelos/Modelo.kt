package pe.quantum.crm.domain.modelos

import jakarta.persistence.CollectionTable
import jakarta.persistence.Column
import jakarta.persistence.ElementCollection
import jakarta.persistence.Entity
import jakarta.persistence.FetchType
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.Table
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import pe.quantum.crm.shared.enums.Aplicacion
import java.math.BigDecimal

/**
 * Modelo de bus del catalogo (tabla `modelos`, migracion V3). Las aplicaciones
 * viven en `modelo_aplicaciones` (V4) y se gestionan de forma atomica con el
 * modelo (reglas_negocio.md §2.4).
 */
@Entity
@Table(name = "modelos")
class Modelo(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,
    @Column(nullable = false, unique = true)
    var codigo: String,
    var longitud: BigDecimal? = null,
    @Column(name = "capacidad_tanques")
    var capacidadTanques: String? = null,
    @Column(name = "max_asientos")
    var maxAsientos: Int? = null,
    @Column(name = "precio_base")
    var precioBase: BigDecimal? = null,
    @Column(name = "ficha_tecnica")
    var fichaTecnica: String? = null,
    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(name = "modelo_aplicaciones", joinColumns = [JoinColumn(name = "id_modelo")])
    @Column(name = "aplicacion", nullable = false, columnDefinition = "aplicacion_enum")
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    var aplicaciones: MutableSet<Aplicacion> = mutableSetOf(),
)
