package pe.quantum.crm.domain.empleados

import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.security.crypto.password.PasswordEncoder
import pe.quantum.crm.domain.empleados.dto.ActualizarEmpleadoRequest
import pe.quantum.crm.domain.empleados.dto.CrearEmpleadoRequest
import pe.quantum.crm.shared.exception.ConflictoException
import pe.quantum.crm.shared.exception.NoEncontradoException
import pe.quantum.crm.shared.exception.PermisoInsuficienteException
import java.util.Optional

/**
 * CRUD de empleados (B1.4) y las lecturas que otros modulos consumen.
 *
 * Complementa a EmpleadoServiceTest, que cubre autenticacion, contencion de
 * cuentas revocadas y cambio de contraseña. Aqui se fijan las reglas del alta y
 * la edicion: contraseña siempre hasheada y `requiere_cambio_contrasena = true`
 * al nacer, email unico, y la invariante de que el sistema nunca se queda sin un
 * administrador activo.
 */
class EmpleadoAdminServiceTest {
    private val repository = mockk<EmpleadoRepository>()
    private val passwordEncoder = mockk<PasswordEncoder>()
    private val service = EmpleadoServiceImpl(repository, passwordEncoder)

    private val idAdmin = 9L

    private fun empleado(
        id: Long = 1,
        rol: RolEmpleado = RolEmpleado.vendedor,
        activo: Boolean = true,
        email: String = "ana@quantum.pe",
    ) = Empleado(
        id = id,
        nombres = "Ana",
        apellidos = "Diaz",
        email = email,
        rol = rol,
        activo = activo,
    )

    /** Copia con `id` asignado: `Empleado.id` es `val` autogenerado, como en JPA al guardar. */
    private fun Empleado.conId(nuevoId: Long) =
        Empleado(
            id = nuevoId,
            nombres = nombres,
            apellidos = apellidos,
            email = email,
            rol = rol,
            area = area,
            puesto = puesto,
            activo = activo,
            passwordHash = passwordHash,
            requiereCambioContrasena = requiereCambioContrasena,
        )

    /** El solicitante de toda operacion de administracion: un admin activo real. */
    private fun conAdminVigente() {
        val root = empleado(id = idAdmin, rol = RolEmpleado.admin, email = "root@quantum.pe")
        every { repository.findById(idAdmin) } returns Optional.of(root)
    }

    private val nuevoVendedor =
        CrearEmpleadoRequest(
            nombres = "Mario",
            apellidos = "Quispe",
            email = "mario@quantum.pe",
            password = "contrasena-larga",
            rol = RolEmpleado.vendedor,
            area = "Comercial",
            puesto = "Ejecutivo de ventas",
        )

    // ── crear (B1.4) ──────────────────────────────────────────

    @Test
    fun `crear guarda el empleado activo, con la contrasena hasheada y obligado a cambiarla`() {
        conAdminVigente()
        every { repository.existsByEmail("mario@quantum.pe") } returns false
        every { passwordEncoder.encode("contrasena-larga") } returns "\$2a\$12\$hasheada"
        val guardado = slot<Empleado>()
        // `Empleado.id` es autogenerado: se reconstruye con un id real, simulando lo
        // que hace JPA al guardar. Devolver la misma instancia (con id nulo) haria
        // fallar a `toDto()`, que exige id, por un defecto del mock y no del codigo.
        every { repository.save(capture(guardado)) } answers { guardado.captured.conId(7) }

        val dto = service.crear(nuevoVendedor, idSolicitante = idAdmin)

        assertThat(guardado.captured.passwordHash).isEqualTo("\$2a\$12\$hasheada")
        // B1.4: la contraseña la elige el admin, asi que el empleado la cambia al entrar.
        assertThat(guardado.captured.requiereCambioContrasena).isTrue()
        assertThat(guardado.captured.activo).isTrue()
        assertThat(dto.email).isEqualTo("mario@quantum.pe")
        assertThat(dto.rol).isEqualTo("vendedor")
        assertThat(dto.area).isEqualTo("Comercial")
        assertThat(dto.puesto).isEqualTo("Ejecutivo de ventas")
        // La contraseña en claro nunca se persiste.
        verify(exactly = 0) { repository.save(match { it.passwordHash == "contrasena-larga" }) }
    }

    @Test
    fun `crear con un email ya usado es 409 EMAIL_DUPLICADO`() {
        conAdminVigente()
        every { repository.existsByEmail("mario@quantum.pe") } returns true

        assertThatThrownBy { service.crear(nuevoVendedor, idSolicitante = idAdmin) }
            .isInstanceOf(ConflictoException::class.java)
            .hasMessageContaining("email")
        verify(exactly = 0) { repository.save(any()) }
        verify(exactly = 0) { passwordEncoder.encode(any()) }
    }

    // ── actualizar ────────────────────────────────────────────

    @Test
    fun `actualizar cambia los datos enviados y deja intactos los ausentes`() {
        conAdminVigente()
        val empleado = empleado(id = 4)
        every { repository.findById(4) } returns Optional.of(empleado)
        every { repository.existsByEmail("ana.diaz@quantum.pe") } returns false
        every { repository.save(any()) } returnsArgument 0

        val dto =
            service.actualizar(
                id = 4,
                request =
                    ActualizarEmpleadoRequest(
                        nombres = "Ana Maria",
                        apellidos = "Diaz Lopez",
                        email = "ana.diaz@quantum.pe",
                        area = "Comercial",
                        puesto = "Ejecutiva senior",
                    ),
                idSolicitante = idAdmin,
            )

        assertThat(dto.nombres).isEqualTo("Ana Maria")
        assertThat(dto.apellidos).isEqualTo("Diaz Lopez")
        assertThat(dto.email).isEqualTo("ana.diaz@quantum.pe")
        assertThat(dto.area).isEqualTo("Comercial")
        assertThat(dto.puesto).isEqualTo("Ejecutiva senior")
        // El rol no venia en el body: sigue siendo el de antes.
        assertThat(dto.rol).isEqualTo("vendedor")
    }

    @Test
    fun `actualizar hacia un email de otro empleado es 409 EMAIL_DUPLICADO`() {
        conAdminVigente()
        val empleado = empleado(id = 4)
        every { repository.findById(4) } returns Optional.of(empleado)
        every { repository.existsByEmail("ocupado@quantum.pe") } returns true

        assertThatThrownBy {
            service.actualizar(4, ActualizarEmpleadoRequest(email = "ocupado@quantum.pe"), idSolicitante = idAdmin)
        }.isInstanceOf(ConflictoException::class.java)
        assertThat(empleado.email).isEqualTo("ana@quantum.pe")
        verify(exactly = 0) { repository.save(any()) }
    }

    @Test
    fun `reenviar el mismo email no lo trata como duplicado`() {
        conAdminVigente()
        every { repository.findById(4) } returns Optional.of(empleado(id = 4))
        every { repository.save(any()) } returnsArgument 0

        service.actualizar(4, ActualizarEmpleadoRequest(email = "ana@quantum.pe"), idSolicitante = idAdmin)

        verify(exactly = 0) { repository.existsByEmail(any()) }
    }

    @Test
    fun `actualizar un empleado inexistente es 404`() {
        conAdminVigente()
        every { repository.findById(404) } returns Optional.empty()

        assertThatThrownBy { service.actualizar(404, ActualizarEmpleadoRequest(nombres = "X"), idSolicitante = idAdmin) }
            .isInstanceOf(NoEncontradoException::class.java)
    }

    // ── verificarNoUltimoAdmin: el sistema nunca sin admin ─────

    @Test
    fun `degradar al ultimo admin activo es 409 ULTIMO_ADMIN`() {
        conAdminVigente()
        val otroAdmin = empleado(id = 4, rol = RolEmpleado.admin, email = "otro@quantum.pe")
        every { repository.findById(4) } returns Optional.of(otroAdmin)
        every { repository.countByRolAndActivoTrueAndIdNot(RolEmpleado.admin, 4) } returns 0L

        assertThatThrownBy {
            service.actualizar(4, ActualizarEmpleadoRequest(rol = RolEmpleado.vendedor), idSolicitante = idAdmin)
        }.isInstanceOf(ConflictoException::class.java)
            .hasMessageContaining("administrador")
        assertThat(otroAdmin.rol).isEqualTo(RolEmpleado.admin)
        verify(exactly = 0) { repository.save(any()) }
    }

    @Test
    fun `degradar a un admin si queda otro admin activo esta permitido`() {
        conAdminVigente()
        val otroAdmin = empleado(id = 4, rol = RolEmpleado.admin, email = "otro@quantum.pe")
        every { repository.findById(4) } returns Optional.of(otroAdmin)
        every { repository.countByRolAndActivoTrueAndIdNot(RolEmpleado.admin, 4) } returns 1L
        every { repository.save(any()) } returnsArgument 0

        val dto = service.actualizar(4, ActualizarEmpleadoRequest(rol = RolEmpleado.gerencia), idSolicitante = idAdmin)

        assertThat(dto.rol).isEqualTo("gerencia")
    }

    @Test
    fun `cambiar el rol de un no-admin no consulta cuantos admins quedan`() {
        conAdminVigente()
        every { repository.findById(4) } returns Optional.of(empleado(id = 4))
        every { repository.save(any()) } returnsArgument 0

        service.actualizar(4, ActualizarEmpleadoRequest(rol = RolEmpleado.jdv), idSolicitante = idAdmin)

        verify(exactly = 0) { repository.countByRolAndActivoTrueAndIdNot(any(), any()) }
    }

    @Test
    fun `mandar el mismo rol que ya tiene no dispara la guarda de ultimo admin`() {
        conAdminVigente()
        val otroAdmin = empleado(id = 4, rol = RolEmpleado.admin, email = "otro@quantum.pe")
        every { repository.findById(4) } returns Optional.of(otroAdmin)
        every { repository.save(any()) } returnsArgument 0

        val dto = service.actualizar(4, ActualizarEmpleadoRequest(rol = RolEmpleado.admin), idSolicitante = idAdmin)

        assertThat(dto.rol).isEqualTo("admin")
        verify(exactly = 0) { repository.countByRolAndActivoTrueAndIdNot(any(), any()) }
    }

    @Test
    fun `desactivar al ultimo admin activo es 409 ULTIMO_ADMIN`() {
        conAdminVigente()
        val otroAdmin = empleado(id = 4, rol = RolEmpleado.admin, email = "otro@quantum.pe")
        every { repository.findById(4) } returns Optional.of(otroAdmin)
        every { repository.countByRolAndActivoTrueAndIdNot(RolEmpleado.admin, 4) } returns 0L

        assertThatThrownBy { service.cambiarActivo(id = 4, activo = false, idSolicitante = idAdmin) }
            .isInstanceOf(ConflictoException::class.java)
        assertThat(otroAdmin.activo).isTrue()
        verify(exactly = 0) { repository.save(any()) }
    }

    @Test
    fun `reactivar a un admin no pasa por la guarda de ultimo admin`() {
        conAdminVigente()
        val otroAdmin = empleado(id = 4, rol = RolEmpleado.admin, activo = false, email = "otro@quantum.pe")
        every { repository.findById(4) } returns Optional.of(otroAdmin)
        every { repository.save(any()) } returnsArgument 0

        val dto = service.cambiarActivo(id = 4, activo = true, idSolicitante = idAdmin)

        assertThat(dto.activo).isTrue()
        verify(exactly = 0) { repository.countByRolAndActivoTrueAndIdNot(any(), any()) }
    }

    @Test
    fun `nadie cambia su propio estado de activacion, ni un admin vigente`() {
        conAdminVigente()

        assertThatThrownBy { service.cambiarActivo(id = idAdmin, activo = false, idSolicitante = idAdmin) }
            .isInstanceOf(PermisoInsuficienteException::class.java)
        verify(exactly = 0) { repository.save(any()) }
    }

    // ── listar ────────────────────────────────────────────────

    @Test
    fun `listar sin rol devuelve los del estado pedido ordenados por id`() {
        every { repository.findByActivo(true) } returns
            listOf(empleado(id = 7, email = "g@quantum.pe"), empleado(id = 2, email = "b@quantum.pe"))

        val resultado = service.listar(activo = true, rol = null)

        assertThat(resultado.map { it.id }).containsExactly(2L, 7L)
        verify(exactly = 0) { repository.findByActivoAndRol(any(), any()) }
    }

    @Test
    fun `listar con rol delega en la consulta filtrada`() {
        every { repository.findByActivoAndRol(false, RolEmpleado.jdv) } returns
            listOf(empleado(id = 3, rol = RolEmpleado.jdv, activo = false, email = "c@quantum.pe"))

        val resultado = service.listar(activo = false, rol = RolEmpleado.jdv)

        assertThat(resultado).hasSize(1)
        assertThat(resultado.first().id).isEqualTo(3L)
        assertThat(resultado.first().rol).isEqualTo("jdv")
        assertThat(resultado.first().activo).isFalse()
        verify(exactly = 0) { repository.findByActivo(any()) }
    }

    // ── esAsignableComoVendedor (destino de empresas y metas) ──

    @Test
    fun `solo un vendedor o jdv activo es asignable como vendedor`() {
        every { repository.findById(1) } returns Optional.of(empleado(id = 1, rol = RolEmpleado.vendedor))
        every { repository.findById(2) } returns Optional.of(empleado(id = 2, rol = RolEmpleado.jdv, email = "b@quantum.pe"))
        every { repository.findById(3) } returns
            Optional.of(empleado(id = 3, rol = RolEmpleado.vendedor, activo = false, email = "c@quantum.pe"))
        every { repository.findById(4) } returns Optional.of(empleado(id = 4, rol = RolEmpleado.admin, email = "d@quantum.pe"))
        every { repository.findById(5) } returns Optional.of(empleado(id = 5, rol = RolEmpleado.analista, email = "e@quantum.pe"))
        every { repository.findById(404) } returns Optional.empty()

        assertThat(service.esAsignableComoVendedor(1)).isTrue()
        assertThat(service.esAsignableComoVendedor(2)).isTrue()
        assertThat(service.esAsignableComoVendedor(3)).isFalse()
        assertThat(service.esAsignableComoVendedor(4)).isFalse()
        assertThat(service.esAsignableComoVendedor(5)).isFalse()
        // Un id inexistente responde false, no un 500 por clave foranea al guardar.
        assertThat(service.esAsignableComoVendedor(404)).isFalse()
    }

    @Test
    fun `existeActivo distingue activo, inactivo e inexistente`() {
        every { repository.findById(1) } returns Optional.of(empleado(id = 1))
        every { repository.findById(2) } returns Optional.of(empleado(id = 2, activo = false, email = "b@quantum.pe"))
        every { repository.findById(404) } returns Optional.empty()

        assertThat(service.existeActivo(1)).isTrue()
        assertThat(service.existeActivo(2)).isFalse()
        assertThat(service.existeActivo(404)).isFalse()
    }

    @Test
    fun `idsActivosPorRol devuelve los ids del rol pedido`() {
        every { repository.findByActivoAndRol(true, RolEmpleado.gerencia) } returns
            listOf(empleado(id = 1, rol = RolEmpleado.gerencia), empleado(id = 6, rol = RolEmpleado.gerencia, email = "f@quantum.pe"))

        assertThat(service.idsActivosPorRol(RolEmpleado.gerencia)).containsExactly(1L, 6L)
    }

    @Test
    fun `resumenPorIds deduplica los ids y devuelve nombre y apellidos`() {
        every { repository.findAllById(setOf(1L)) } returns listOf(empleado(id = 1))

        val resumen = service.resumenPorIds(listOf(1L, 1L))

        assertThat(resumen).containsOnlyKeys(1L)
        assertThat(resumen.getValue(1L).nombres).isEqualTo("Ana")
        assertThat(resumen.getValue(1L).apellidos).isEqualTo("Diaz")
    }

    @Test
    fun `porId de un empleado inexistente es 404`() {
        every { repository.findById(404) } returns Optional.empty()

        assertThatThrownBy { service.porId(404) }.isInstanceOf(NoEncontradoException::class.java)
    }
}
