package pe.quantum.crm.domain.empleados

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes

/**
 * Empleado / usuario del sistema (tabla `empleados`, migraciones V2 + V20 + V39).
 *
 * Entidad JPA — nunca se expone en controllers (se mapea a DTOs). `ddl-auto=validate`:
 * esta clase debe reflejar exactamente el schema. `passwordHash` es nullable: un
 * empleado sin contraseña no puede autenticarse (ver SECURITY-backend.md §2.3).
 */
@Entity
@Table(name = "empleados")
@Suppress("LongParameterList") // Una entidad JPA refleja las columnas de su tabla.
class Empleado(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,
    @Column(nullable = false)
    var nombres: String,
    @Column(nullable = false)
    var apellidos: String,
    @Column(nullable = false, unique = true)
    var email: String,
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(nullable = false, columnDefinition = "rol_empleado")
    var rol: RolEmpleado,
    var area: String? = null,
    var puesto: String? = null,
    @Column(nullable = false)
    var activo: Boolean = true,
    @Column(name = "password_hash")
    var passwordHash: String? = null,
    @Column(name = "requiere_cambio_contrasena", nullable = false)
    var requiereCambioContrasena: Boolean = true,
    @Column(name = "token_version", nullable = false)
    var tokenVersion: Int = 0,
)
