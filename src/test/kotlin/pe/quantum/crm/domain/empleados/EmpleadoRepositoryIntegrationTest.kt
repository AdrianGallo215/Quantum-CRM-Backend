package pe.quantum.crm.domain.empleados

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import pe.quantum.crm.support.IntegrationTestBase

/**
 * Test de integracion del EmpleadoRepository (B0.7). Valida el mapeo real de la
 * entidad contra PostgreSQL, en particular la lectura del enum nativo `rol_empleado`
 * (@JdbcTypeCode NAMED_ENUM): 'admin' en la base -> RolEmpleado.admin.
 */
@Tag("integration")
@SpringBootTest
class EmpleadoRepositoryIntegrationTest : IntegrationTestBase() {
    @Autowired
    lateinit var repository: EmpleadoRepository

    @Test
    fun `findByEmail devuelve el admin sembrado con su rol y sin contraseña`() {
        val admin = repository.findByEmail("admin@quantum.pe")

        assertThat(admin).isNotNull
        assertThat(admin!!.rol).isEqualTo(RolEmpleado.admin)
        assertThat(admin.nombres).isEqualTo("Admin")
        assertThat(admin.activo).isTrue()
        // El seed (V19) no fija contraseña; el admin aun no puede autenticarse.
        assertThat(admin.passwordHash).isNull()
        assertThat(admin.requiereCambioContrasena).isTrue()
    }

    @Test
    fun `findByEmail devuelve null cuando el email no existe`() {
        assertThat(repository.findByEmail("nadie@quantum.pe")).isNull()
    }
}
