package pe.quantum.crm.arquitectura

import jakarta.persistence.LockModeType
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.data.jpa.repository.Lock
import org.springframework.transaction.annotation.Transactional
import pe.quantum.crm.domain.empresas.EmpresaRepository
import pe.quantum.crm.domain.empresas.EmpresaServiceImpl
import pe.quantum.crm.domain.oportunidades.OportunidadRepository
import pe.quantum.crm.domain.oportunidades.OportunidadServiceImpl
import java.lang.reflect.Method

/**
 * Ninguna operacion que hable con Google Drive puede correr dentro de una
 * transaccion de base de datos.
 *
 * Por que importa: `app.drive.read-timeout-ms` vale hasta 120 s y el pool de
 * Hikari tiene 10 conexiones. Si la llamada de red ocurre con la transaccion
 * abierta, la conexion queda retenida durante toda la latencia de Drive: con
 * Drive degradado, 10 peticiones concurrentes agotan el pool y la API entera
 * empieza a rebotar (login incluido). No hace falta carga alta.
 *
 * Esto se verifica sobre la anotacion `@Transactional` del metodo publico porque
 * es lo que abre la transaccion: Spring la aplica en el proxy, de modo que todo
 * lo que el metodo llame despues (incluida la red) corre dentro de ella.
 *
 * UNICA EXCEPCION, deliberada y verificada abajo: `OportunidadServiceImpl.crear`.
 * contrato_api.md §8 exige que si Drive no responde la oportunidad NO se cree, y
 * el nombre de la carpeta (`OP-{id}`) necesita el id que solo existe tras el
 * insert: la llamada no se puede adelantar al inicio como en empresas. Su
 * exposicion se acota con `app.drive.folder-read-timeout-ms`, mucho mas corto.
 */
class DriveFueraDeTransaccionTest {
    private companion object {
        /**
         * Metodos publicos que terminan hablando con Drive. Se listan a mano (y se
         * comprueba que existen) para que un renombrado no deje el test verificando
         * la lista vacia.
         */
        val SIN_TRANSACCION =
            listOf(
                EmpresaServiceImpl::class.java to "crear",
                EmpresaServiceImpl::class.java to "crearSinCarpetaDrive",
                EmpresaServiceImpl::class.java to "asegurarCarpetaDrive",
                EmpresaServiceImpl::class.java to "archivosDrive",
                OportunidadServiceImpl::class.java to "asegurarCarpetaDrive",
                OportunidadServiceImpl::class.java to "archivosDrive",
            )
    }

    private fun metodos(
        clase: Class<*>,
        nombre: String,
    ): List<Method> = clase.declaredMethods.filter { it.name == nombre && !it.isSynthetic }

    @Test
    fun `los metodos que llaman a Drive no abren transaccion`() {
        val anotados =
            SIN_TRANSACCION.flatMap { (clase, nombre) ->
                metodos(clase, nombre)
                    .filter { it.isAnnotationPresent(Transactional::class.java) }
                    .map { "${clase.simpleName}.${it.name}(${it.parameterTypes.joinToString { p -> p.simpleName }})" }
            }
        assertThat(anotados)
            .withFailMessage(
                "Estos metodos hablan con Drive con la transaccion abierta y retienen una conexion del pool " +
                    "durante toda la latencia de red. La llamada debe ocurrir fuera de la transaccion:%n%s",
                anotados.joinToString(System.lineSeparator()),
            ).isEmpty()
    }

    @Test
    fun `la lista de metodos verificados existe de verdad (guarda contra un test vacio)`() {
        val inexistentes =
            SIN_TRANSACCION
                .filter { (clase, nombre) -> metodos(clase, nombre).isEmpty() }
                .map { (clase, nombre) -> "${clase.simpleName}.$nombre" }
        assertThat(inexistentes)
            .withFailMessage(
                "Metodos listados que ya no existen; el test de arriba no estaria verificando nada:%n%s",
                inexistentes.joinToString(System.lineSeparator()),
            ).isEmpty()
    }

    @Test
    fun `ningun repositorio bloquea la fila con SELECT FOR UPDATE para crear carpetas`() {
        // El bloqueo pesimista cumplia su funcion (una sola carpeta por registro),
        // pero mantenia la fila Y la conexion retenidas durante la llamada a Drive.
        // La exclusion la da ahora un UPDATE condicional (`... WHERE drive_folder_id
        // IS NULL`), que es atomico y no abarca la red.
        val conBloqueo =
            listOf(EmpresaRepository::class.java, OportunidadRepository::class.java).flatMap { repositorio ->
                repositorio.methods
                    .filter { it.getAnnotation(Lock::class.java)?.value == LockModeType.PESSIMISTIC_WRITE }
                    .map { "${repositorio.simpleName}.${it.name}" }
            }
        assertThat(conBloqueo)
            .withFailMessage(
                "Un SELECT ... FOR UPDATE seguido de una llamada a Drive retiene fila y conexion durante " +
                    "toda la latencia de red. Usar el UPDATE condicional. Metodos con bloqueo:%n%s",
                conBloqueo.joinToString(System.lineSeparator()),
            ).isEmpty()
    }

    @Test
    fun `OportunidadServiceImpl crear sigue siendo transaccional a proposito`() {
        // Si algun dia deja de estarlo, es un cambio de contrato (la oportunidad
        // pasaria a crearse aunque Drive falle) y debe decidirse, no filtrarse.
        val crear = metodos(OportunidadServiceImpl::class.java, "crear")
        assertThat(crear).hasSize(1)
        assertThat(crear.single().isAnnotationPresent(Transactional::class.java))
            .withFailMessage(
                "contrato_api.md §8 (POST /oportunidades) exige que sin carpeta de Drive no haya oportunidad; " +
                    "eso solo se garantiza si la creacion de la carpeta y el insert comparten transaccion.",
            ).isTrue()
    }
}
