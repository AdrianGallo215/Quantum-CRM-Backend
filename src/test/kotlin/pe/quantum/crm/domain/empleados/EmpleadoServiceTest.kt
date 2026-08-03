package pe.quantum.crm.domain.empleados

import io.mockk.every
import io.mockk.impl.annotations.InjectMockKs
import io.mockk.impl.annotations.MockK
import io.mockk.junit5.MockKExtension
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.security.crypto.password.PasswordEncoder
import pe.quantum.crm.shared.exception.CredencialesInvalidasException

/**
 * Tests unitarios de la autenticacion (B0.8). Toda falla — email inexistente,
 * contraseña incorrecta, empleado inactivo, empleado sin contraseña — produce la
 * MISMA excepcion generica (SECURITY §2.4: no revelar cual fallo).
 */
@ExtendWith(MockKExtension::class)
class EmpleadoServiceTest {
    @MockK
    lateinit var repository: EmpleadoRepository

    @MockK
    lateinit var passwordEncoder: PasswordEncoder

    @InjectMockKs
    lateinit var service: EmpleadoServiceImpl

    private fun empleado(
        activo: Boolean = true,
        passwordHash: String? = "\$2a\$12\$hashValido",
    ) = Empleado(
        id = 1,
        nombres = "Ana",
        apellidos = "Diaz",
        email = "ana@quantum.pe",
        rol = RolEmpleado.vendedor,
        activo = activo,
        passwordHash = passwordHash,
    )

    @Test
    fun `autenticar con credenciales validas devuelve el empleado`() {
        val empleado = empleado()
        every { repository.findByEmail("ana@quantum.pe") } returns empleado
        every { passwordEncoder.matches("secreta", empleado.passwordHash!!) } returns true

        val resultado = service.autenticar("ana@quantum.pe", "secreta")

        assertThat(resultado).isSameAs(empleado)
    }

    @Test
    fun `email inexistente lanza CredencialesInvalidasException`() {
        every { repository.findByEmail(any()) } returns null
        every { passwordEncoder.encode(any()) } returns "\$2a\$12\$dummy"
        every { passwordEncoder.matches(any(), any()) } returns false

        assertThrows<CredencialesInvalidasException> {
            service.autenticar("nadie@quantum.pe", "secreta")
        }
    }

    @Test
    fun `contraseña incorrecta lanza CredencialesInvalidasException`() {
        every { repository.findByEmail("ana@quantum.pe") } returns empleado()
        every { passwordEncoder.matches("mala", any()) } returns false

        assertThrows<CredencialesInvalidasException> {
            service.autenticar("ana@quantum.pe", "mala")
        }
    }

    @Test
    fun `empleado inactivo lanza CredencialesInvalidasException`() {
        every { repository.findByEmail("ana@quantum.pe") } returns empleado(activo = false)
        every { passwordEncoder.encode(any()) } returns "\$2a\$12\$dummy"
        every { passwordEncoder.matches(any(), any()) } returns false

        assertThrows<CredencialesInvalidasException> {
            service.autenticar("ana@quantum.pe", "secreta")
        }
    }

    @Test
    fun `empleado sin contraseña lanza CredencialesInvalidasException`() {
        every { repository.findByEmail("ana@quantum.pe") } returns empleado(passwordHash = null)
        every { passwordEncoder.encode(any()) } returns "\$2a\$12\$dummy"
        every { passwordEncoder.matches(any(), any()) } returns false

        assertThrows<CredencialesInvalidasException> {
            service.autenticar("ana@quantum.pe", "secreta")
        }
    }

    @Test
    fun `ante email inexistente igual invoca al encoder para equiparar el tiempo`() {
        every { repository.findByEmail(any()) } returns null
        every { passwordEncoder.encode(any()) } returns "\$2a\$12\$dummy"
        every { passwordEncoder.matches(any(), any()) } returns false

        assertThrows<CredencialesInvalidasException> {
            service.autenticar("nadie@quantum.pe", "secreta")
        }

        // Mitigacion de timing attack (SECURITY §2.4): se ejecuta BCrypt aunque el
        // usuario no exista, para no delatar la inexistencia por el tiempo de respuesta.
        verify { passwordEncoder.matches("secreta", any()) }
    }

    @Test
    fun `idsSupervisoresActivos devuelve los ids de admin, gerencia y jdv activos`() {
        every {
            repository.findByActivoTrueAndRolIn(listOf(RolEmpleado.admin, RolEmpleado.gerencia, RolEmpleado.jdv))
        } returns
            listOf(
                empleado().let {
                    Empleado(
                        id = 1,
                        nombres = it.nombres,
                        apellidos = it.apellidos,
                        email = "a@quantum.pe",
                        rol = RolEmpleado.admin,
                    )
                },
                empleado().let {
                    Empleado(
                        id = 2,
                        nombres = it.nombres,
                        apellidos = it.apellidos,
                        email = "b@quantum.pe",
                        rol = RolEmpleado.jdv,
                    )
                },
            )

        val resultado = service.idsSupervisoresActivos()

        assertThat(resultado).containsExactlyInAnyOrder(1, 2)
    }
}
