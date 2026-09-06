package pe.quantum.crm.domain.simulaciones

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatCode
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.http.HttpStatus
import pe.quantum.crm.shared.exception.NoEncontradoException
import pe.quantum.crm.shared.exception.PermisoInsuficienteException
import pe.quantum.crm.shared.security.UsuarioActual

/**
 * Matriz completa de `reglas_simulaciones.md` §10, celda por celda.
 *
 * Existe sobre todo para dejar constancia de que el reparto de roles de
 * simulaciones NO es el de oportunidades (hallazgo K12 de
 * `plan-09-mapa-simulaciones-modulo.md`): `analista` tiene acceso total pese a
 * ser rol de apoyo en oportunidades, y `jdv` no tiene ninguno pese a ser
 * supervisor. Si alguien "simplifica" [SimulacionPermisos] reutilizando
 * `UsuarioActual.esRolApoyo` o `esSupervisor`, estos tests son los que lo
 * detienen.
 *
 * Sin mockk: [SimulacionPermisos] no tiene dependencias, es logica pura sobre
 * el rol.
 */
class SimulacionPermisosTest {
    private val permisos = SimulacionPermisos()

    // region exigirAcceso — acceso minimo al modulo (§10: todos salvo jdv y otro)

    @Test
    fun `exigirAcceso no lanza para admin`() {
        assertThatCode { permisos.exigirAcceso(usuario("admin")) }.doesNotThrowAnyException()
    }

    @Test
    fun `exigirAcceso no lanza para gerencia`() {
        assertThatCode { permisos.exigirAcceso(usuario("gerencia")) }.doesNotThrowAnyException()
    }

    @Test
    fun `exigirAcceso no lanza para analista`() {
        assertThatCode { permisos.exigirAcceso(usuario("analista")) }.doesNotThrowAnyException()
    }

    @Test
    fun `exigirAcceso no lanza para vendedor`() {
        // El vendedor no entra al listado del modulo, pero si al simulador de su
        // oportunidad y a la Calculadora Financiera (§9, §10).
        assertThatCode { permisos.exigirAcceso(usuario("vendedor")) }.doesNotThrowAnyException()
    }

    @Test
    fun `exigirAcceso lanza 403 para jdv`() {
        val ex = assertThrows<PermisoInsuficienteException> { permisos.exigirAcceso(usuario("jdv")) }
        assertThat(ex.status).isEqualTo(HttpStatus.FORBIDDEN)
    }

    @Test
    fun `exigirAcceso lanza 403 para otro`() {
        val ex = assertThrows<PermisoInsuficienteException> { permisos.exigirAcceso(usuario("otro")) }
        assertThat(ex.status).isEqualTo(HttpStatus.FORBIDDEN)
    }

    // endregion

    // region exigirAccesoAlModulo — listado GET /simulaciones (§10, decision D39)

    @Test
    fun `exigirAccesoAlModulo no lanza para admin`() {
        assertThatCode { permisos.exigirAccesoAlModulo(usuario("admin")) }.doesNotThrowAnyException()
    }

    @Test
    fun `exigirAccesoAlModulo no lanza para gerencia`() {
        assertThatCode { permisos.exigirAccesoAlModulo(usuario("gerencia")) }.doesNotThrowAnyException()
    }

    @Test
    fun `exigirAccesoAlModulo no lanza para analista`() {
        // §10: "es el rol dueño de este modulo".
        assertThatCode { permisos.exigirAccesoAlModulo(usuario("analista")) }.doesNotThrowAnyException()
    }

    @Test
    fun `exigirAccesoAlModulo lanza 403 para vendedor`() {
        // Llega a sus simulaciones por el contexto de la oportunidad y por la
        // Calculadora, nunca por el listado del modulo (D39).
        val ex = assertThrows<PermisoInsuficienteException> { permisos.exigirAccesoAlModulo(usuario("vendedor")) }
        assertThat(ex.status).isEqualTo(HttpStatus.FORBIDDEN)
    }

    @Test
    fun `exigirAccesoAlModulo lanza 403 para jdv`() {
        val ex = assertThrows<PermisoInsuficienteException> { permisos.exigirAccesoAlModulo(usuario("jdv")) }
        assertThat(ex.status).isEqualTo(HttpStatus.FORBIDDEN)
    }

    @Test
    fun `exigirAccesoAlModulo lanza 403 para otro`() {
        val ex = assertThrows<PermisoInsuficienteException> { permisos.exigirAccesoAlModulo(usuario("otro")) }
        assertThat(ex.status).isEqualTo(HttpStatus.FORBIDDEN)
    }

    // endregion

    // region alcanza — roles con acceso total al modulo (§10)

    @Test
    fun `analista alcanza simulaciones ajenas aunque sea rol de apoyo en oportunidades`() {
        // ESTE ES EL PUNTO DE K12. reglas_simulaciones.md §10: "`analista` es de
        // solo lectura en oportunidades pero tiene escritura completa en
        // simulaciones: es el rol dueño de este modulo". Por eso NO se puede
        // reutilizar `UsuarioActual.esRolApoyo` (que lo agrupa con `otro`, sin
        // acceso) ni delegar en `OportunidadVisibilidad.alcanza` (que lo
        // limitaria a las oportunidades donde colabora via tarea).
        val analista = usuario("analista")

        // Enlazada al item de la oportunidad de OTRO vendedor.
        assertThat(permisos.alcanza(idCreador = ID_OTRO, idVendedorDelItem = ID_OTRO, usuario = analista)).isTrue()
        // Sin item y creada por otro.
        assertThat(permisos.alcanza(idCreador = ID_OTRO, idVendedorDelItem = null, usuario = analista)).isTrue()
    }

    @Test
    fun `admin alcanza cualquier simulacion`() {
        val admin = usuario("admin")
        assertThat(permisos.alcanza(idCreador = ID_OTRO, idVendedorDelItem = ID_OTRO, usuario = admin)).isTrue()
        assertThat(permisos.alcanza(idCreador = ID_OTRO, idVendedorDelItem = null, usuario = admin)).isTrue()
    }

    @Test
    fun `gerencia alcanza cualquier simulacion`() {
        val gerencia = usuario("gerencia")
        assertThat(permisos.alcanza(idCreador = ID_OTRO, idVendedorDelItem = ID_OTRO, usuario = gerencia)).isTrue()
        assertThat(permisos.alcanza(idCreador = ID_OTRO, idVendedorDelItem = null, usuario = gerencia)).isTrue()
    }

    // endregion

    // region alcanza — roles sin acceso

    @Test
    fun `jdv no alcanza ninguna simulacion aunque sea supervisor en oportunidades`() {
        // `UsuarioActual.esSupervisor` incluye a jdv; §10 no le da acceso al
        // modulo. Ni siquiera las que el mismo hubiera creado.
        val jdv = usuario("jdv")
        assertThat(permisos.alcanza(idCreador = ID_USUARIO, idVendedorDelItem = ID_USUARIO, usuario = jdv)).isFalse()
        assertThat(permisos.alcanza(idCreador = ID_USUARIO, idVendedorDelItem = null, usuario = jdv)).isFalse()
    }

    @Test
    fun `otro no alcanza ninguna simulacion`() {
        val otro = usuario("otro")
        assertThat(permisos.alcanza(idCreador = ID_USUARIO, idVendedorDelItem = ID_USUARIO, usuario = otro)).isFalse()
        assertThat(permisos.alcanza(idCreador = ID_USUARIO, idVendedorDelItem = null, usuario = otro)).isFalse()
    }

    // endregion

    // region alcanza — vendedor (decision D31)

    @Test
    fun `vendedor alcanza la simulacion enlazada al item de su oportunidad`() {
        val vendedor = usuario("vendedor")
        assertThat(
            permisos.alcanza(idCreador = ID_OTRO, idVendedorDelItem = ID_USUARIO, usuario = vendedor),
        ).isTrue()
    }

    @Test
    fun `vendedor no alcanza la simulacion enlazada al item de otro vendedor`() {
        val vendedor = usuario("vendedor")
        assertThat(
            permisos.alcanza(idCreador = ID_OTRO, idVendedorDelItem = ID_OTRO, usuario = vendedor),
        ).isFalse()
    }

    @Test
    fun `vendedor no alcanza la enlazada al item de otro aunque el la haya creado`() {
        // Enlazada: manda el vendedor asignado del item, no la autoria (D31 +
        // §9: solo puede enlazar a items de oportunidades donde el es el
        // vendedor asignado, asi que si el item ya es de otro, la oportunidad
        // se reasigno y la simulacion se fue con ella).
        val vendedor = usuario("vendedor")
        assertThat(
            permisos.alcanza(idCreador = ID_USUARIO, idVendedorDelItem = ID_OTRO, usuario = vendedor),
        ).isFalse()
    }

    @Test
    fun `vendedor alcanza la simulacion sin item que el mismo creo`() {
        // Sin item no hay cadena a oportunidad: la autoria es el unico vinculo
        // posible (D31).
        val vendedor = usuario("vendedor")
        assertThat(
            permisos.alcanza(idCreador = ID_USUARIO, idVendedorDelItem = null, usuario = vendedor),
        ).isTrue()
    }

    @Test
    fun `vendedor no alcanza la simulacion sin item creada por otro`() {
        val vendedor = usuario("vendedor")
        assertThat(
            permisos.alcanza(idCreador = ID_OTRO, idVendedorDelItem = null, usuario = vendedor),
        ).isFalse()
    }

    // endregion

    // region exigirAlcance — 404, no 403 (CLAUDE.md regla 14)

    @Test
    fun `exigirAlcance lanza 404 y no 403 cuando la simulacion no es del vendedor`() {
        val ex =
            assertThrows<NoEncontradoException> {
                permisos.exigirAlcance(idCreador = ID_OTRO, idVendedorDelItem = ID_OTRO, usuario = usuario("vendedor"))
            }
        assertThat(ex.status).isEqualTo(HttpStatus.NOT_FOUND)
    }

    @Test
    fun `exigirAlcance lanza 404 para un rol sin acceso al modulo`() {
        val ex =
            assertThrows<NoEncontradoException> {
                permisos.exigirAlcance(idCreador = ID_USUARIO, idVendedorDelItem = null, usuario = usuario("jdv"))
            }
        assertThat(ex.status).isEqualTo(HttpStatus.NOT_FOUND)
    }

    @Test
    fun `exigirAlcance no lanza cuando la simulacion si esta al alcance`() {
        assertThatCode {
            permisos.exigirAlcance(idCreador = ID_OTRO, idVendedorDelItem = ID_USUARIO, usuario = usuario("vendedor"))
        }.doesNotThrowAnyException()
        assertThatCode {
            permisos.exigirAlcance(idCreador = ID_OTRO, idVendedorDelItem = ID_OTRO, usuario = usuario("analista"))
        }.doesNotThrowAnyException()
    }

    // endregion

    private fun usuario(rol: String) = UsuarioActual(id = ID_USUARIO, rol = rol)

    private companion object {
        const val ID_USUARIO = 7L
        const val ID_OTRO = 99L
    }
}
