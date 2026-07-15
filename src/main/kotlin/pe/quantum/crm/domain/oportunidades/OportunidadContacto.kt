package pe.quantum.crm.domain.oportunidades

import jakarta.persistence.Column
import jakarta.persistence.Embeddable
import jakarta.persistence.EmbeddedId
import jakarta.persistence.Entity
import jakarta.persistence.Table
import java.io.Serializable

/** PK compuesta de la vinculacion oportunidad-contacto. */
@Embeddable
data class OportunidadContactoId(
    @Column(name = "id_oportunidad")
    val idOportunidad: Long = 0,
    @Column(name = "id_contacto")
    val idContacto: Long = 0,
) : Serializable

/**
 * Contactos involucrados en una oportunidad con su rol (tabla
 * `oportunidad_contactos`, migracion V12). Independientes de los contactos
 * generales de la empresa (reglas_negocio.md §11.3).
 */
@Entity
@Table(name = "oportunidad_contactos")
class OportunidadContacto(
    @EmbeddedId
    val id: OportunidadContactoId,
    @Column(name = "rol_en_oportunidad")
    var rolEnOportunidad: String? = null,
)
