package pe.quantum.crm.domain.contactos

import jakarta.persistence.Column
import jakarta.persistence.Embeddable
import jakarta.persistence.EmbeddedId
import jakarta.persistence.Entity
import jakarta.persistence.Table
import java.io.Serializable

/** PK compuesta de la vinculacion empresa-contacto. */
@Embeddable
data class EmpresaContactoId(
    @Column(name = "id_empresa")
    val idEmpresa: Long = 0,
    @Column(name = "id_contacto")
    val idContacto: Long = 0,
) : Serializable {
    private companion object {
        private const val serialVersionUID: Long = 1L
    }
}

/**
 * Vinculacion contacto-empresa (tabla `empresa_contactos`, migracion V9).
 * `cargo` y `toma_decision` son atributos de la relacion, no del contacto
 * (reglas_negocio.md §11.1). ON DELETE RESTRICT en ambos lados.
 */
@Entity
@Table(name = "empresa_contactos")
class EmpresaContacto(
    @EmbeddedId
    val id: EmpresaContactoId,
    var cargo: String? = null,
    @Column(name = "toma_decision")
    var tomaDecision: Boolean? = null,
    @Column(name = "es_principal", nullable = false)
    var esPrincipal: Boolean = false,
)
