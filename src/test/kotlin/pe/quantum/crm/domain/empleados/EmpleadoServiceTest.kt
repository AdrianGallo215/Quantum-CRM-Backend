package pe.quantum.crm.domain.empleados

import io.mockk.every
import io.mockk.impl.annotations.InjectMockKs
import io.mockk.impl.annotations.MockK
import io.mockk.junit5.MockKExtension
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import org.springframework.security.crypto.password.PasswordEncoder
import pe.quantum.crm.domain.empleados.dto.ActualizarEmpleadoRequest
import pe.quantum.crm.domain.empleados.dto.CrearEmpleadoRequest
import pe.quantum.crm.shared.exception.CredencialesInvalidasException
import pe.quantum.crm.shared.exception.NoEncontradoException
import pe.quantum.crm.shared.exception.PermisoInsuficienteException
import pe.quantum.crm.shared.exception.ValidacionException
import java.util.Optional

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

    // ── Contencion de cuentas revocadas ──────────────────────────────────────
    // El `SecurityContext` se arma solo con los claims del JWT: ni el filtro ni
    // `UsuarioActual` releen la base. Como el access token vive 1h, un admin
    // desactivado o degradado conserva un token que dice `rol=admin` durante ese
    // rato. Si con el pudiera tocar la administracion de empleados, deshace su
    // propia revocacion de forma permanente y la desactivacion deja de contener
    // nada. Estos tests fijan que la administracion de empleados revalide contra
    // la base quien pide, y que nadie se edite a si mismo.

    private fun admin(
        id: Long = 9,
        activo: Boolean = true,
        rol: RolEmpleado = RolEmpleado.admin,
    ) = Empleado(
        id = id,
        nombres = "Root",
        apellidos = "Quantum",
        email = "root$id@quantum.pe",
        rol = rol,
        activo = activo,
    )

    private val nuevoAdmin =
        CrearEmpleadoRequest(
            nombres = "Mallory",
            apellidos = "Puerta",
            email = "mallory@quantum.pe",
            password = "contrasena-larga",
            rol = RolEmpleado.admin,
        )

    @Test
    fun `un admin desactivado no puede reactivarse a si mismo`() {
        every { repository.findById(9) } returns Optional.of(admin(id = 9, activo = false))

        assertThrows<PermisoInsuficienteException> {
            service.cambiarActivo(id = 9, activo = true, idSolicitante = 9)
        }
        verify(exactly = 0) { repository.save(any()) }
    }

    @Test
    fun `un admin desactivado no puede reactivar a otro empleado`() {
        every { repository.findById(9) } returns Optional.of(admin(id = 9, activo = false))

        assertThrows<PermisoInsuficienteException> {
            service.cambiarActivo(id = 4, activo = true, idSolicitante = 9)
        }
        verify(exactly = 0) { repository.save(any()) }
    }

    /**
     * La via alterna: aunque no pueda reactivarse, un admin desactivado con token
     * vigente podia crearse un admin nuevo con una contraseña elegida por el y
     * volver a entrar por ahi. Cierra el mismo agujero por otra puerta.
     */
    @Test
    fun `un admin desactivado no puede crear otro admin`() {
        every { repository.findById(9) } returns Optional.of(admin(id = 9, activo = false))

        assertThrows<PermisoInsuficienteException> {
            service.crear(nuevoAdmin, idSolicitante = 9)
        }
        verify(exactly = 0) { repository.save(any()) }
    }

    @Test
    fun `un admin degradado no puede administrar empleados`() {
        every { repository.findById(9) } returns Optional.of(admin(id = 9, rol = RolEmpleado.vendedor))

        assertThrows<PermisoInsuficienteException> {
            service.crear(nuevoAdmin, idSolicitante = 9)
        }
        verify(exactly = 0) { repository.save(any()) }
    }

    @Test
    fun `nadie puede cambiar su propio rol`() {
        every { repository.findById(9) } returns Optional.of(admin(id = 9))

        assertThrows<PermisoInsuficienteException> {
            service.actualizar(id = 9, request = ActualizarEmpleadoRequest(rol = RolEmpleado.vendedor), idSolicitante = 9)
        }
        verify(exactly = 0) { repository.save(any()) }
    }

    @Test
    fun `un admin vigente si puede desactivar a otro empleado`() {
        every { repository.findById(9) } returns Optional.of(admin(id = 9))
        every { repository.findById(4) } returns Optional.of(empleado())
        every { repository.save(any()) } returnsArgument 0

        val resultado = service.cambiarActivo(id = 4, activo = false, idSolicitante = 9)

        assertThat(resultado.activo).isFalse()
    }

    @Test
    fun `un admin vigente si puede editar sus propios datos de contacto`() {
        every { repository.findById(9) } returns Optional.of(admin(id = 9))
        every { repository.save(any()) } returnsArgument 0

        val resultado = service.actualizar(id = 9, request = ActualizarEmpleadoRequest(nombres = "Raiz"), idSolicitante = 9)

        assertThat(resultado.nombres).isEqualTo("Raiz")
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

    // ── Cambio de contraseña (D1) ────────────────────────────────────────────
    // El flag `requiere_cambio_contrasena` nace en `true` (crear()) y hasta ahora
    // no existia ningun camino que lo apagara: el login lo publicaba pero nunca se
    // podia cumplir. `cambiarContrasena` es ese camino. Usa un PasswordEncoder REAL
    // (BCrypt) en vez de un mock: el hashing es justo lo que se prueba.

    private val encoderReal: PasswordEncoder = BCryptPasswordEncoder()
    private val repositoryCambio: EmpleadoRepository = mockk()
    private val serviceCambio = EmpleadoServiceImpl(repositoryCambio, encoderReal)

    private fun empleadoConPassword(
        id: Long = 1,
        passwordPlano: String = "vieja",
        requiereCambio: Boolean = true,
    ) = Empleado(
        id = id,
        nombres = "Ana",
        apellidos = "Diaz",
        email = "ana@quantum.pe",
        rol = RolEmpleado.vendedor,
        activo = true,
        passwordHash = encoderReal.encode(passwordPlano),
        requiereCambioContrasena = requiereCambio,
    )

    @Test
    fun `cambiar contrasena con la actual correcta guarda el hash nuevo y apaga el flag`() {
        val empleado = empleadoConPassword()
        val hashAnterior = empleado.passwordHash
        every { repositoryCambio.findById(1) } returns Optional.of(empleado)
        every { repositoryCambio.save(any()) } returnsArgument 0

        serviceCambio.cambiarContrasena(1, "vieja", "NuevaSegura123")

        assertThat(empleado.passwordHash).isNotEqualTo(hashAnterior)
        assertThat(encoderReal.matches("NuevaSegura123", empleado.passwordHash!!)).isTrue()
        assertThat(empleado.requiereCambioContrasena).isFalse()
    }

    @Test
    fun `cambiar contrasena con la actual incorrecta lanza CREDENCIALES_INVALIDAS y no guarda`() {
        val empleado = empleadoConPassword()
        every { repositoryCambio.findById(1) } returns Optional.of(empleado)

        assertThrows<CredencialesInvalidasException> {
            serviceCambio.cambiarContrasena(1, "incorrecta", "NuevaSegura123")
        }
        verify(exactly = 0) { repositoryCambio.save(any()) }
    }

    @Test
    fun `cambiar contrasena rechaza que la nueva sea igual a la actual`() {
        val empleado = empleadoConPassword()
        every { repositoryCambio.findById(1) } returns Optional.of(empleado)

        val excepcion =
            assertThrows<ValidacionException> {
                serviceCambio.cambiarContrasena(1, "vieja", "vieja")
            }
        assertThat(excepcion.field).isEqualTo("password_nueva")
        verify(exactly = 0) { repositoryCambio.save(any()) }
    }

    @Test
    fun `cambiar contrasena de un empleado inexistente lanza NO_ENCONTRADO`() {
        every { repositoryCambio.findById(99) } returns Optional.empty()

        assertThrows<NoEncontradoException> {
            serviceCambio.cambiarContrasena(99, "vieja", "NuevaSegura123")
        }
        verify(exactly = 0) { repositoryCambio.save(any()) }
    }
}
