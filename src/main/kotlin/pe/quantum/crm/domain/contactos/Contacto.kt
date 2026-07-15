package pe.quantum.crm.domain.contactos

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.LocalDateTime

/**
 * Contacto (tabla `contactos`, migracion V8). Puede vincularse a varias empresas;
 * el cargo y toma_decision viven en la relacion (`empresa_contactos`).
 */
@Entity
@Table(name = "contactos")
class Contacto(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,
    @Column(nullable = false)
    var nombres: String,
    @Column(nullable = false)
    var apellidos: String,
    @Column(name = "email_1")
    var email1: String? = null,
    @Column(name = "email_2")
    var email2: String? = null,
    @Column(name = "tlf_1")
    var tlf1: String? = null,
    @Column(name = "tlf_2")
    var tlf2: String? = null,
    var notas: String? = null,
    @Column(name = "created_at", nullable = false)
    val createdAt: LocalDateTime = LocalDateTime.now(),
    @Column(name = "created_by", nullable = false)
    val createdBy: Long,
    @Column(name = "updated_at", nullable = false)
    var updatedAt: LocalDateTime = LocalDateTime.now(),
    @Column(name = "updated_by", nullable = false)
    var updatedBy: Long,
)
