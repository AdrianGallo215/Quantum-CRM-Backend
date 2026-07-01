package pe.quantum.crm.domain.empleados

import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
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
}
