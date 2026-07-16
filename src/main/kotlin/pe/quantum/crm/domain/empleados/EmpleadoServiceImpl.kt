package pe.quantum.crm.domain.empleados

import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pe.quantum.crm.domain.empleados.dto.ActualizarEmpleadoRequest
import pe.quantum.crm.domain.empleados.dto.CrearEmpleadoRequest
import pe.quantum.crm.domain.empleados.dto.EmpleadoDto
import pe.quantum.crm.domain.empleados.dto.EmpleadoResumen
import pe.quantum.crm.domain.empleados.dto.toDto
import pe.quantum.crm.domain.empleados.dto.toResumen
import pe.quantum.crm.shared.exception.ConflictoException
import pe.quantum.crm.shared.exception.CredencialesInvalidasException
import pe.quantum.crm.shared.exception.NoEncontradoException

@Service
class EmpleadoServiceImpl(
    private val empleadoRepository: EmpleadoRepository,
    private val passwordEncoder: PasswordEncoder,
) : EmpleadoService {
    /**
     * Hash dummy (cost del encoder real) para equiparar el tiempo de respuesta
     * cuando el usuario no existe / esta inactivo / no tiene contraseña, y no
     * delatar esa condicion por timing (SECURITY §2.4). Se calcula una vez.
     */
    private val dummyHash: String by lazy { passwordEncoder.encode("timing-equalizer") }

    @Transactional(readOnly = true)
    override fun autenticar(
        email: String,
        passwordPlano: String,
    ): Empleado {
        val empleado = empleadoRepository.findByEmail(email)
        val hash = empleado?.takeIf { it.activo }?.passwordHash
        if (hash == null) {
            passwordEncoder.matches(passwordPlano, dummyHash)
            throw CredencialesInvalidasException()
        }
        if (!passwordEncoder.matches(passwordPlano, hash)) {
            throw CredencialesInvalidasException()
        }
        return empleado
    }

    @Transactional(readOnly = true)
    override fun porId(id: Long): Empleado = empleadoRepository.findById(id).orElseThrow { NoEncontradoException("El empleado no existe") }

    @Transactional(readOnly = true)
    override fun listar(
        activo: Boolean,
        rol: RolEmpleado?,
    ): List<EmpleadoDto> {
        val empleados =
            if (rol != null) {
                empleadoRepository.findByActivoAndRol(activo, rol)
            } else {
                empleadoRepository.findByActivo(activo)
            }
        return empleados.sortedBy { it.id }.map { it.toDto() }
    }

    @Transactional
    override fun crear(request: CrearEmpleadoRequest): EmpleadoDto {
        if (empleadoRepository.existsByEmail(request.email)) {
            throw ConflictoException("EMAIL_DUPLICADO", "Ya existe un empleado con ese email")
        }
        val empleado =
            Empleado(
                nombres = request.nombres,
                apellidos = request.apellidos,
                email = request.email,
                rol = request.rol,
                area = request.area,
                puesto = request.puesto,
                activo = true,
                passwordHash = passwordEncoder.encode(request.password),
                requiereCambioContrasena = true,
            )
        return empleadoRepository.save(empleado).toDto()
    }

    @Transactional
    override fun actualizar(
        id: Long,
        request: ActualizarEmpleadoRequest,
    ): EmpleadoDto {
        val empleado = porId(id)
        request.email?.let {
            if (it != empleado.email && empleadoRepository.existsByEmail(it)) {
                throw ConflictoException("EMAIL_DUPLICADO", "Ya existe un empleado con ese email")
            }
            empleado.email = it
        }
        request.nombres?.let { empleado.nombres = it }
        request.apellidos?.let { empleado.apellidos = it }
        request.rol?.let { nuevoRol ->
            if (empleado.rol == RolEmpleado.admin && nuevoRol != RolEmpleado.admin) {
                verificarNoUltimoAdmin(id)
            }
            empleado.rol = nuevoRol
        }
        request.area?.let { empleado.area = it }
        request.puesto?.let { empleado.puesto = it }
        return empleadoRepository.save(empleado).toDto()
    }

    @Transactional
    override fun cambiarActivo(
        id: Long,
        activo: Boolean,
    ): EmpleadoDto {
        val empleado = porId(id)
        if (!activo && empleado.rol == RolEmpleado.admin) {
            verificarNoUltimoAdmin(id)
        }
        empleado.activo = activo
        return empleadoRepository.save(empleado).toDto()
    }

    @Transactional(readOnly = true)
    override fun existeActivo(id: Long): Boolean = empleadoRepository.findById(id).map { it.activo }.orElse(false)

    @Transactional(readOnly = true)
    override fun esAsignableComoVendedor(id: Long): Boolean =
        empleadoRepository
            .findById(id)
            .map { it.activo && (it.rol == RolEmpleado.vendedor || it.rol == RolEmpleado.jdv) }
            .orElse(false)

    @Transactional(readOnly = true)
    override fun idsActivosPorRol(rol: RolEmpleado): List<Long> =
        empleadoRepository.findByActivoAndRol(true, rol).map { requireNotNull(it.id) }

    @Transactional(readOnly = true)
    override fun resumenPorIds(ids: Collection<Long>): Map<Long, EmpleadoResumen> =
        empleadoRepository.findAllById(ids.toSet()).associate { requireNotNull(it.id) to it.toResumen() }

    @Transactional(readOnly = true)
    override fun idsSupervisoresActivos(): List<Long> =
        empleadoRepository
            .findByActivoTrueAndRolIn(listOf(RolEmpleado.admin, RolEmpleado.gerencia, RolEmpleado.jdv))
            .map { requireNotNull(it.id) }

    /** Regla B1.4: el sistema nunca se queda sin un admin activo. */
    private fun verificarNoUltimoAdmin(id: Long) {
        if (empleadoRepository.countByRolAndActivoTrueAndIdNot(RolEmpleado.admin, id) == 0L) {
            throw ConflictoException("ULTIMO_ADMIN", "No se puede dejar el sistema sin un administrador activo")
        }
    }
}
